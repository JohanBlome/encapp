#!/usr/bin/env python3
"""Aggregate compatible encoder RC probe result roots across products."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import statistics
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
import encoder_rc_probe as rcprobe  # noqa: E402
import encoder_rc_probe_plots as rcplots  # noqa: E402

MODE_COLORS = {"cbr": "#0072B2", "vbr": "#D55E00"}
PROFILE_LABELS = {"unbounded": "unbounded", "jcq_27_38": "QP 27–38"}
WINDOW_SECONDS = (0.2, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 10.0, 15.0, 20.0, 30.0)
RUN_METRICS = (
    "mean_rate_over_target",
    "vbv_delay_max_s",
    "i_p_size_ratio",
    "startup_i_vs_steady_i_ratio",
    "first_i_qp_avg",
    "p_median_qp_avg",
)
DROP_METRICS = (
    "region_bitrate_ratio",
    "post_size_vs_baseline_median",
    "post_qp_delta_median",
    "max_pts_gap_ms",
    "max_vbv_delay_ms",
)
QP_METRICS = ("qpy_low", "qpy_weighted_mean", "qpy_high")
RATE_QP_METRICS = (
    "achieved_over_target",
    "p_qpy_median",
    "p_qpy_stddev",
    "p_qpy_mean_abs_step",
    "pinned_at_max_fraction",
)
TAU_METRICS = ("qp_tau63_ms", "bitrate_tau63_ms")
PUBLIC_INVENTORY_FIELDS = (
    "product", "sample_id", "codec", "mime", "resolution", "fps",
    "bitrate_bps", "total_runs", "valid_focused_runs", "excluded_runs",
)


@dataclass
class Sample:
    product: str
    sample_id: str
    root: Path
    manifest: Dict[str, Any]
    contract: Dict[str, Any]
    signature: str
    inventory: Dict[str, Any]
    runs: List[Dict[str, Any]]
    windows: List[Dict[str, Any]]
    qp: List[Dict[str, Any]]
    rate_qp: List[Dict[str, Any]]
    steps: List[Dict[str, Any]]
    drops: List[Dict[str, Any]]


def _to_float(value: Any) -> Optional[float]:
    if value in (None, "", "None", "null", "n/a"):
        return None
    return float(value)


def _is_true(value: Any) -> bool:
    return str(value).lower() in {"1", "true", "yes"}


def _quantile(values: Sequence[float], q: float) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        return math.nan
    if len(ordered) == 1:
        return ordered[0]
    index = (len(ordered) - 1) * q
    lower = int(math.floor(index))
    upper = int(math.ceil(index))
    if lower == upper:
        return ordered[lower]
    weight = index - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def _read_csv(path: Path) -> List[Dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def _write_csv(path: Path, rows: Sequence[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields: List[str] = []
    seen = set()
    for row in rows:
        for field in row:
            if field not in seen:
                seen.add(field)
                fields.append(field)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def _contract(manifest: Dict[str, Any]) -> Dict[str, Any]:
    global_cfg = manifest["global"]
    global_contract = {
        key: global_cfg.get(key)
        for key in ("mime", "resolution", "fps", "bitrate_bps", "i_frame_interval")
    }
    recorded_input = manifest.get("input_contract", {})
    input_contract = {
        key: recorded_input[key]
        for key in ("source", "pixel_format", "deterministic", "complexity_ranks")
        if key in recorded_input
    }
    # Synthetic input has one fixed, non-configurable submission policy.
    input_contract["submission_policy"] = "as_fast_as_codec_accepts"
    cells: Dict[str, Dict[str, Any]] = {}
    for case in manifest["cases"]:
        cell = {
            "mode": case["mode"],
            "qp_profile": case["qp_profile"],
            "qp_bounds": case.get("qp_bounds", {}),
            "scenario": case["scenario"],
            "scenario_def": case["scenario_def"],
        }
        key = json.dumps(cell, sort_keys=True, separators=(",", ":"))
        cells[key] = cell
    return {
        "global": global_contract,
        "input_contract": input_contract,
        "matrix_cells": [cells[key] for key in sorted(cells)],
    }


def _contract_differences(baseline: Any, candidate: Any,
                          path: str = "") -> List[str]:
    """Describe normalized contract mismatches with reader-facing field paths."""
    if isinstance(baseline, dict) and isinstance(candidate, dict):
        differences: List[str] = []
        for key in sorted(set(baseline) | set(candidate)):
            child = f"{path}.{key}" if path else str(key)
            if key not in baseline:
                differences.append(
                    f"{child}: baseline=<missing>, candidate={candidate[key]!r}")
            elif key not in candidate:
                differences.append(
                    f"{child}: baseline={baseline[key]!r}, candidate=<missing>")
            else:
                differences.extend(_contract_differences(
                    baseline[key], candidate[key], child))
        return differences
    if isinstance(baseline, list) and isinstance(candidate, list):
        if len(baseline) != len(candidate):
            return [f"{path}.length: baseline={len(baseline)}, candidate={len(candidate)}"]
        differences: List[str] = []
        for index, (left, right) in enumerate(zip(baseline, candidate)):
            differences.extend(_contract_differences(
                left, right, f"{path}[{index}]"))
        return differences
    if baseline != candidate:
        return [f"{path}: baseline={baseline!r}, candidate={candidate!r}"]
    return []


def _signature(contract: Dict[str, Any]) -> str:
    encoded = json.dumps(contract, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def _sample_aggregate(
    rows: Sequence[Dict[str, Any]], key_fields: Sequence[str],
    metric_fields: Sequence[str], count_name: str,
) -> List[Dict[str, Any]]:
    groups: Dict[Tuple[Any, ...], List[Dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[tuple(row[field] for field in key_fields)].append(row)
    output: List[Dict[str, Any]] = []
    for key, group in sorted(groups.items(), key=lambda item: tuple(map(str, item[0]))):
        record = {field: value for field, value in zip(key_fields, key)}
        record[count_name] = len(group)
        for metric in metric_fields:
            values = [float(row[metric]) for row in group if row.get(metric) is not None]
            record[metric] = statistics.median(values) if values else None
        output.append(record)
    return output


def _product_aggregate(
    rows: Sequence[Dict[str, Any]], key_fields: Sequence[str],
    metric_fields: Sequence[str],
) -> List[Dict[str, Any]]:
    groups: Dict[Tuple[Any, ...], List[Dict[str, Any]]] = defaultdict(list)
    for row in rows:
        key = (row["product"],) + tuple(row[field] for field in key_fields)
        groups[key].append(row)
    output: List[Dict[str, Any]] = []
    for key, group in sorted(groups.items(), key=lambda item: tuple(map(str, item[0]))):
        record: Dict[str, Any] = {"product": key[0]}
        record.update({field: value for field, value in zip(key_fields, key[1:])})
        record["sample_count"] = len(group)
        for metric in metric_fields:
            values = [float(row[metric]) for row in group if row.get(metric) is not None]
            for suffix, q in (("median", 0.5), ("q25", 0.25), ("q75", 0.75),
                              ("min", 0.0), ("max", 1.0)):
                record[f"{metric}_{suffix}"] = _quantile(values, q) if values else None
        output.append(record)
    return output


def _load_sample(product: str, sample_id: str, root: Path,
                 profiles: Sequence[str]) -> Sample:
    root = root.resolve()
    manifest_path = root / "manifest.json"
    analysis = root / "analysis"
    required = [manifest_path, analysis / "runs.csv", analysis / "steps.csv",
                analysis / "drops.csv", analysis / "frames.csv"]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise ValueError(f"{sample_id}: missing required files: {', '.join(missing)}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    contract = _contract(manifest)
    target_bps = float(manifest["global"]["bitrate_bps"])

    raw_runs = _read_csv(analysis / "runs.csv")
    valid_ids = {
        row["case_id"] for row in raw_runs
        if _is_true(row.get("scientifically_valid")) and row.get("qp_profile") in profiles
    }
    run_records: List[Dict[str, Any]] = []
    for row in raw_runs:
        if row["case_id"] not in valid_ids:
            continue
        mean_bps = _to_float(row.get("mean_bitrate_bps"))
        vbv_ms = _to_float(row.get("vbv_delay_max_ms"))
        record: Dict[str, Any] = {
            "case_id": row["case_id"], "repeat": int(row["repeat"]),
            "mode": row["mode"], "qp_profile": row["qp_profile"],
            "scenario": row["scenario"],
            "mean_rate_over_target": mean_bps / target_bps if mean_bps is not None else None,
            "vbv_delay_max_s": vbv_ms / 1000.0 if vbv_ms is not None else None,
        }
        for metric in RUN_METRICS:
            if metric not in record:
                record[metric] = _to_float(row.get(metric))
        run_records.append(record)

    frame_rows = rcplots._read_frames(analysis / "frames.csv")
    by_case = rcplots._group_by_case(
        row for row in frame_rows if row["case_id"] in valid_ids)
    window_records = [
        row for row in rcplots.rolling_window_records(by_case, target_bps)
        if row["case_id"] in valid_ids and row["qp_profile"] in profiles
    ]
    qp_records = [
        row for row in rcplots.stream_qp_records(by_case)
        if row["case_id"] in valid_ids and row["qp_profile"] in profiles
    ]
    cases = {str(case["id"]): case for case in manifest["cases"]}
    rate_qp_records = [
        row for row in rcplots.rate_conditioned_qp_records(
            by_case, cases, target_bps)
        if row["case_id"] in valid_ids and row["qp_profile"] in profiles
    ]

    step_records: List[Dict[str, Any]] = []
    for row in _read_csv(analysis / "steps.csv"):
        if row.get("case_id") not in valid_ids or row.get("qp_profile") not in profiles:
            continue
        step_records.append({
            "case_id": row["case_id"], "repeat": int(row["repeat"]),
            "mode": row["mode"], "qp_profile": row["qp_profile"],
            "direction": row["direction"],
            "segment_frames": int(row["end_frame"]) - int(row["start_frame"]),
            "qp_tau63_ms": _to_float(row.get("qp_tau63_ms")),
            "bitrate_tau63_ms": _to_float(row.get("bitrate_tau63_ms")),
        })

    drop_records: List[Dict[str, Any]] = []
    for row in _read_csv(analysis / "drops.csv"):
        if row.get("case_id") not in valid_ids or row.get("qp_profile") not in profiles:
            continue
        record = {
            "case_id": row["case_id"], "repeat": int(row["repeat"]),
            "mode": row["mode"], "qp_profile": row["qp_profile"],
            "region": row["region"],
        }
        for metric in DROP_METRICS:
            record[metric] = _to_float(row.get(metric))
        drop_records.append(record)

    device: Dict[str, Any] = {}
    device_path = root / "device.json"
    if device_path.is_file():
        try:
            device = json.loads(device_path.read_text(encoding="utf-8"))
        except Exception:
            device = {}
    properties = device.get("properties", {}) if isinstance(device, dict) else {}
    inventory = {
        "product": product,
        "sample_id": sample_id,
        "result_root": str(root),
        "codec": manifest["global"].get("codec"),
        "mime": manifest["global"].get("mime"),
        "resolution": manifest["global"].get("resolution"),
        "fps": manifest["global"].get("fps"),
        "bitrate_bps": manifest["global"].get("bitrate_bps"),
        "model": properties.get("ro.product.model"),
        "soc": properties.get("ro.soc.model"),
        "build_fingerprint": properties.get("ro.build.fingerprint"),
        "total_runs": len(raw_runs),
        "valid_focused_runs": len(run_records),
        "excluded_runs": len(raw_runs) - len([
            row for row in raw_runs if _is_true(row.get("scientifically_valid"))]),
    }
    return Sample(product, sample_id, root, manifest, contract, _signature(contract),
                  inventory, run_records, window_records, qp_records,
                  rate_qp_records, step_records, drop_records)


def _decorate(rows: Iterable[Dict[str, Any]], sample: Sample) -> List[Dict[str, Any]]:
    return [{"product": sample.product, "sample_id": sample.sample_id,
             **row} for row in rows]


def _sample_tau(sample: Sample) -> List[Dict[str, Any]]:
    keys = ("mode", "qp_profile", "direction")
    groups: Dict[Tuple[Any, ...], List[Dict[str, Any]]] = defaultdict(list)
    for row in sample.steps:
        if row["segment_frames"] >= 3 * int(sample.manifest["global"]["fps"]):
            groups[tuple(row[field] for field in keys)].append(row)
    output: List[Dict[str, Any]] = []
    for key, group in groups.items():
        record = {"product": sample.product, "sample_id": sample.sample_id}
        record.update({field: value for field, value in zip(keys, key)})
        record["eligible_transitions"] = len(group)
        for metric in TAU_METRICS:
            values = [float(row[metric]) for row in group if row.get(metric) is not None]
            record[metric] = statistics.median(values) if values else None
            record[f"{metric}_valid"] = len(values)
        output.append(record)
    return output


def _aggregate(samples: Sequence[Sample]) -> Dict[str, List[Dict[str, Any]]]:
    sample_runs: List[Dict[str, Any]] = []
    sample_windows: List[Dict[str, Any]] = []
    sample_qp: List[Dict[str, Any]] = []
    sample_rate_qp: List[Dict[str, Any]] = []
    sample_drops: List[Dict[str, Any]] = []
    sample_tau: List[Dict[str, Any]] = []
    for sample in samples:
        for row in _sample_aggregate(
                sample.runs, ("mode", "qp_profile", "scenario"), RUN_METRICS,
                "run_count"):
            sample_runs.append({"product": sample.product,
                                "sample_id": sample.sample_id, **row})
        for row in _sample_aggregate(
                sample.windows, ("mode", "qp_profile", "window_s"),
                ("peak_over_target",), "run_count"):
            sample_windows.append({"product": sample.product,
                                   "sample_id": sample.sample_id, **row})
        for row in _sample_aggregate(
                sample.qp, ("mode", "qp_profile", "scenario", "frame_class"),
                QP_METRICS, "run_count"):
            sample_qp.append({"product": sample.product,
                              "sample_id": sample.sample_id, **row})
        for row in _sample_aggregate(
                sample.rate_qp, ("mode", "qp_profile", "scenario"),
                RATE_QP_METRICS, "run_count"):
            sample_rate_qp.append({"product": sample.product,
                                   "sample_id": sample.sample_id, **row})
        for row in _sample_aggregate(
                sample.drops, ("mode", "qp_profile", "region"),
                DROP_METRICS, "run_count"):
            sample_drops.append({"product": sample.product,
                                 "sample_id": sample.sample_id, **row})
        sample_tau.extend(_sample_tau(sample))

    product_tau = _product_aggregate(
        sample_tau, ("mode", "qp_profile", "direction"), TAU_METRICS)
    tau_groups: Dict[Tuple[str, str, str, str], List[Dict[str, Any]]] = defaultdict(list)
    for row in sample_tau:
        tau_groups[(row["product"], row["mode"], row["qp_profile"],
                    row["direction"])].append(row)
    for row in product_tau:
        group = tau_groups[(row["product"], row["mode"], row["qp_profile"],
                            row["direction"])]
        row["eligible_transitions"] = sum(int(item["eligible_transitions"]) for item in group)
        for metric in TAU_METRICS:
            row[f"{metric}_valid"] = sum(int(item[f"{metric}_valid"]) for item in group)

    return {
        "sample_runs": sample_runs,
        "product_runs": _product_aggregate(
            sample_runs, ("mode", "qp_profile", "scenario"), RUN_METRICS),
        "sample_windows": sample_windows,
        "product_windows": _product_aggregate(
            sample_windows, ("mode", "qp_profile", "window_s"),
            ("peak_over_target",)),
        "sample_qp": sample_qp,
        "product_qp": _product_aggregate(
            sample_qp, ("mode", "qp_profile", "scenario", "frame_class"),
            QP_METRICS),
        "sample_rate_qp": sample_rate_qp,
        "product_rate_qp": _product_aggregate(
            sample_rate_qp, ("mode", "qp_profile", "scenario"),
            RATE_QP_METRICS),
        "sample_drops": sample_drops,
        "product_drops": _product_aggregate(
            sample_drops, ("mode", "qp_profile", "region"), DROP_METRICS),
        "sample_tau": sample_tau,
        "product_tau": product_tau,
    }


def _lookup(rows: Sequence[Dict[str, Any]], **criteria: Any) -> Optional[Dict[str, Any]]:
    return next((row for row in rows
                 if all(row.get(key) == value for key, value in criteria.items())), None)


def _setup_matplotlib():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.backends.backend_pdf import PdfPages
    plt.rcParams.update({
        "axes.grid": True, "grid.alpha": 0.22,
        "axes.spines.top": False, "axes.spines.right": False,
        "legend.frameon": False, "savefig.bbox": "tight", "font.size": 9,
    })
    return plt, PdfPages


def _product_colors(plt, products: Sequence[str]) -> Dict[str, Any]:
    palette = plt.get_cmap("tab10")
    return {product: palette(index % 10) for index, product in enumerate(products)}


def _centered_display_offsets(count: int, spacing_points: float) -> List[float]:
    center = (count - 1) / 2.0
    return [(index - center) * spacing_points for index in range(count)]


def _bar_page(plt, rows: Sequence[Dict[str, Any]], products: Sequence[str],
              profiles: Sequence[str], metric: str, title: str, ylabel: str,
              scenario: str):
    fig, axes = plt.subplots(1, len(profiles), figsize=(13.5, 5.2), sharey=True)
    if len(profiles) == 1:
        axes = [axes]
    width = 0.34
    for ax, profile in zip(axes, profiles):
        for mode_index, mode in enumerate(("cbr", "vbr")):
            positions = [index + (mode_index - 0.5) * width
                         for index in range(len(products))]
            values: List[float] = []
            lower: List[float] = []
            upper: List[float] = []
            for product in products:
                row = _lookup(rows, product=product, mode=mode,
                              qp_profile=profile, scenario=scenario)
                value = float(row[f"{metric}_median"]) if row and row.get(f"{metric}_median") is not None else math.nan
                q25 = float(row[f"{metric}_q25"]) if row and row.get(f"{metric}_q25") is not None else value
                q75 = float(row[f"{metric}_q75"]) if row and row.get(f"{metric}_q75") is not None else value
                values.append(value); lower.append(value - q25); upper.append(q75 - value)
            ax.bar(positions, values, width=width, color=MODE_COLORS[mode],
                   alpha=0.86, label=mode.upper())
            ax.errorbar(positions, values, yerr=[lower, upper], fmt="none",
                        color="black", linewidth=0.8, capsize=2)
        ax.set_xticks(range(len(products)))
        ax.set_xticklabels(products, rotation=25, ha="right")
        ax.set_title(PROFILE_LABELS.get(profile, profile))
        ax.legend(loc="best")
    axes[0].set_ylabel(ylabel)
    fig.suptitle(title, fontsize=15)
    fig.text(0.5, 0.01,
             "Bars are medians of device/root-level medians; error bars are the inter-sample IQR.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.06, 1, 0.93))
    return fig


def _window_page(plt, rows: Sequence[Dict[str, Any]], products: Sequence[str],
                 profiles: Sequence[str]):
    from matplotlib.transforms import offset_copy

    colors = _product_colors(plt, products)
    fig, axes = plt.subplots(1, len(profiles), figsize=(13.5, 5.2), sharey=True)
    if len(profiles) == 1:
        axes = [axes]
    line_width = float(plt.rcParams["lines.linewidth"])
    product_offsets = dict(zip(
        products,
        _centered_display_offsets(len(products), line_width),
    ))
    for ax, profile in zip(axes, profiles):
        for product in products:
            display_transform = offset_copy(
                ax.transData,
                fig=fig,
                x=0.0,
                y=product_offsets[product],
                units="points",
            )
            for mode in ("cbr", "vbr"):
                selected = [row for row in rows if row["product"] == product
                            and row["mode"] == mode and row["qp_profile"] == profile]
                selected.sort(key=lambda row: float(row["window_s"]))
                x_values = [float(row["window_s"]) for row in selected]
                y_values = [float(row["peak_over_target_median"]) for row in selected]
                if not x_values:
                    continue
                ax.update_datalim(list(zip(x_values, y_values)))
                ax.plot(x_values, y_values,
                        color=colors[product], linestyle="-" if mode == "cbr" else "--",
                        marker="o" if mode == "cbr" else "s", markersize=3.5,
                        transform=display_transform,
                        label=f"{product} {mode.upper()}")
        ax.autoscale_view()
        ax.axhline(1.0, color="black", linestyle=":", linewidth=1.0)
        ax.set_xscale("log")
        ax.set_xticks(WINDOW_SECONDS)
        ax.set_xticklabels([f"{value:g}" for value in WINDOW_SECONDS], rotation=45)
        ax.set_title(PROFILE_LABELS.get(profile, profile))
        ax.set_xlabel("Averaging window (seconds)")
    axes[0].set_ylabel("Peak rolling rate / requested rate")
    axes[-1].legend(loc="upper right", fontsize=7, ncol=max(1, len(products) // 4 + 1))
    fig.suptitle("Peak-rate allowance by product", fontsize=15)
    fig.text(
        0.5,
        0.01,
        "Product traces use a one-line-width display-only vertical offset; source values are unchanged.",
        ha="center",
        fontsize=8,
    )
    fig.tight_layout(rect=(0, 0.05, 1, 0.94))
    return fig


def _qp_page(plt, rows: Sequence[Dict[str, Any]], products: Sequence[str],
             profiles: Sequence[str]):
    fig, axes = plt.subplots(1, len(profiles), figsize=(13.5, 5.4), sharey=False)
    if len(profiles) == 1:
        axes = [axes]
    width = 0.18
    for ax, profile in zip(axes, profiles):
        for product_index, product in enumerate(products):
            for mode_index, mode in enumerate(("cbr", "vbr")):
                row = _lookup(rows, product=product, mode=mode, qp_profile=profile,
                              scenario="dynamics", frame_class="all")
                if not row:
                    continue
                position = product_index + (mode_index - 0.5) * width
                low = float(row["qpy_low_median"])
                mean = float(row["qpy_weighted_mean_median"])
                high = float(row["qpy_high_median"])
                ax.vlines(position, low, high, color=MODE_COLORS[mode], linewidth=3)
                ax.scatter([position], [mean], color=MODE_COLORS[mode], s=45,
                           edgecolor="white", linewidth=0.7,
                           label=mode.upper() if product_index == 0 else None)
        if profile == "jcq_27_38":
            ax.axhline(27, color="black", linestyle=":", linewidth=1)
            ax.axhline(38, color="black", linestyle=":", linewidth=1)
        ax.set_xticks(range(len(products)))
        ax.set_xticklabels(products, rotation=25, ha="right")
        ax.set_title(PROFILE_LABELS.get(profile, profile))
        ax.legend(loc="best")
    axes[0].set_ylabel("Bitstream QPY low / weighted mean / high")
    fig.suptitle("Dynamics QPY allocation by product", fontsize=15)
    fig.text(0.5, 0.01,
             "Vertical ranges and dots are medians of sample-level stream extrema and weighted means.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.06, 1, 0.93))
    return fig


def _rate_conditioned_qp_page(
        plt, rows: Sequence[Dict[str, Any]], products: Sequence[str]):
    selected = [row for row in rows
                if row["qp_profile"] == "jcq_27_38"
                and row["scenario"] == "dynamics"]
    if not selected:
        return None
    colors = _product_colors(plt, products)
    product_order = {product: index for index, product in enumerate(products)}
    selected.sort(key=lambda row: (
        str(row["mode"]), product_order[str(row["product"])],
    ))
    fig, ax = plt.subplots(figsize=(12.5, 6.4))
    plot_points: List[Dict[str, Any]] = []
    for row in selected:
        product = str(row["product"])
        mode = str(row["mode"])
        x = float(row["achieved_over_target_median"])
        y = float(row["p_qpy_median_median"])
        pinned = float(row.get("pinned_at_max_fraction_median") or 0.0)
        plot_points.append({
            "product": product,
            "mode": mode,
            "x": x,
            "y": y,
            "x25": float(row.get("achieved_over_target_q25", x)),
            "x75": float(row.get("achieved_over_target_q75", x)),
            "y25": float(row.get("p_qpy_median_q25", y)),
            "y75": float(row.get("p_qpy_median_q75", y)),
            "pinned": pinned,
            "base_size": rcplots._pinning_marker_area(pinned),
        })
    display_sizes = rcplots._contained_marker_sizes([
        (point["x"], point["y"], point["base_size"])
        for point in plot_points
    ])
    for point in plot_points:
        ax.update_datalim([
            (point["x25"], point["y25"]),
            (point["x75"], point["y75"]),
        ])
        ax.errorbar(
            [point["x"]], [point["y"]],
            xerr=[[point["x"] - point["x25"]],
                  [point["x75"] - point["x"]]],
            yerr=[[point["y"] - point["y25"]],
                  [point["y75"] - point["y"]]],
            fmt="none", ecolor=colors[point["product"]], alpha=0.65,
            linewidth=1.0, capsize=2,
        )
    for point, display_size in zip(plot_points, display_sizes):
        marker = "o" if point["mode"] == "cbr" else "s"
        ax.scatter(
            [point["x"]], [point["y"]], s=display_size, marker=marker,
            color=colors[point["product"]], edgecolor="black", linewidth=0.6,
            label=(f"{point['product']} {point['mode'].upper()} "
                   f"({point['pinned'] * 100:.0f}% at max)"),
            zorder=3,
        )
    ax.autoscale_view()
    ax.axvline(1.0, color="black", linestyle="--", linewidth=1.0)
    ax.set_xlabel("Achieved mean bitrate / requested bitrate")
    ax.set_ylabel("Median P-frame QPY")
    ax.set_title("Rate-conditioned P-frame QPY, dynamics, configured bounds 27–38")
    ax.grid(True, alpha=0.25)
    ax.legend(loc="center left", bbox_to_anchor=(1.01, 0.5), fontsize=8)
    fig.text(
        0.39, 0.025,
        "Marker diameter encodes maximum-QP pinning; nested rings reveal "
        "coincident products (legend percentages are exact).\n"
        "Error bars are sample-level IQRs. Lower QPY is better typical-frame "
        "quality; more pinning is a worse tail; farther right spends more bits.\n"
        "This is rate-conditioned, not QP-at-target normalized. QPY includes "
        "skipped blocks; source coordinates and axis limits are unchanged.",
        ha="center", va="bottom", fontsize=8.5, linespacing=1.35,
    )
    fig.tight_layout(rect=(0, 0.14, 0.78, 1))
    return fig


def _tau_page(plt, rows: Sequence[Dict[str, Any]], products: Sequence[str],
              profiles: Sequence[str], metric: str, title: str):
    fig, axes = plt.subplots(2, len(profiles), figsize=(13.5, 8.0), sharey=True)
    if len(profiles) == 1:
        axes = [[axes[0]], [axes[1]]]
    width = 0.34
    for row_index, direction in enumerate(("up", "down")):
        for column, profile in enumerate(profiles):
            ax = axes[row_index][column]
            for mode_index, mode in enumerate(("cbr", "vbr")):
                positions = [index + (mode_index - 0.5) * width
                             for index in range(len(products))]
                values = []
                for product in products:
                    row = _lookup(rows, product=product, mode=mode,
                                  qp_profile=profile, direction=direction)
                    value = (float(row[f"{metric}_median"])
                             if row and row.get(f"{metric}_median") is not None else math.nan)
                    values.append(value)
                ax.bar(positions, values, width=width, color=MODE_COLORS[mode],
                       alpha=0.86, label=mode.upper())
            ax.set_xticks(range(len(products)))
            ax.set_xticklabels(products, rotation=25, ha="right")
            ax.set_title(f"{PROFILE_LABELS.get(profile, profile)} — {direction}")
            ax.legend(loc="best")
    axes[0][0].set_ylabel("τ63 (ms)")
    axes[1][0].set_ylabel("τ63 (ms)")
    fig.suptitle(title, fontsize=15)
    fig.text(0.5, 0.01,
             "Only significant sustained crossings are aggregated; coverage remains in product_tau.csv.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.04, 1, 0.95))
    return fig


def _drop_page(plt, rows: Sequence[Dict[str, Any]], products: Sequence[str],
               profiles: Sequence[str]):
    metrics = (
        ("region_bitrate_ratio", "Delivered bitrate / baseline", 1.0),
        ("post_size_vs_baseline_median", "Following P size / baseline", 1.0),
        ("post_qp_delta_median", "Following P ΔQPY", 0.0),
    )
    fig, axes = plt.subplots(len(profiles), 3, figsize=(13.5, 7.7))
    if len(profiles) == 1:
        axes = [axes]
    width = 0.34
    for row_index, profile in enumerate(profiles):
        for column, (metric, title, reference) in enumerate(metrics):
            ax = axes[row_index][column]
            for mode_index, mode in enumerate(("cbr", "vbr")):
                positions = [index + (mode_index - 0.5) * width
                             for index in range(len(products))]
                values = []
                for product in products:
                    row = _lookup(rows, product=product, mode=mode,
                                  qp_profile=profile, region="half_rate")
                    value = (float(row[f"{metric}_median"])
                             if row and row.get(f"{metric}_median") is not None else math.nan)
                    values.append(value)
                ax.bar(positions, values, width=width, color=MODE_COLORS[mode],
                       alpha=0.86, label=mode.upper())
            ax.axhline(reference, color="black", linestyle=":", linewidth=1)
            ax.set_xticks(range(len(products)))
            ax.set_xticklabels(products, rotation=25, ha="right")
            ax.set_title(f"{PROFILE_LABELS.get(profile, profile)}\n{title}")
            ax.legend(loc="best")
    fig.suptitle("Configured 30 fps, effective 15 fps input behavior", fontsize=15)
    fig.text(0.5, 0.01,
             "Every other input is omitted for four seconds; product bars aggregate sample-level medians.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.04, 1, 0.94))
    return fig


def _make_plots(output: Path, aggregates: Dict[str, List[Dict[str, Any]]],
                products: Sequence[str], profiles: Sequence[str]) -> List[str]:
    plt, PdfPages = _setup_matplotlib()
    figures = [
        ("01_mean_rate.png", _bar_page(
            plt, aggregates["product_runs"], products, profiles,
            "mean_rate_over_target", "Mean delivered rate by product",
            "Mean bitrate / requested bitrate", "dynamics")),
        ("02_peak_rate_windows.png", _window_page(
            plt, aggregates["product_windows"], products, profiles)),
        ("03_backlog.png", _bar_page(
            plt, aggregates["product_runs"], products, profiles,
            "vbv_delay_max_s", "Maximum observable backlog by product",
            "Backlog at requested drain rate (seconds)", "dynamics")),
        ("04_qpy.png", _qp_page(
            plt, aggregates["product_qp"], products, profiles)),
        ("05_ip_ratio.png", _bar_page(
            plt, aggregates["product_runs"], products, profiles,
            "i_p_size_ratio", "Hard-start I/P frame-size allocation",
            "Median I bytes / median P bytes", "hard_start")),
        ("06_bitrate_tau.png", _tau_page(
            plt, aggregates["product_tau"], products, profiles,
            "bitrate_tau63_ms", "P-frame bitrate response τ63")),
        ("07_qp_tau.png", _tau_page(
            plt, aggregates["product_tau"], products, profiles,
            "qp_tau63_ms", "Weighted-QPY response τ63")),
        ("08_half_rate.png", _drop_page(
            plt, aggregates["product_drops"], products, profiles)),
        ("09_rate_conditioned_qp.png", _rate_conditioned_qp_page(
            plt, aggregates["product_rate_qp"], products)),
    ]
    figures = [(name, figure) for name, figure in figures if figure is not None]
    names: List[str] = []
    with PdfPages(output / "product_comparison.pdf") as pdf:
        for name, figure in figures:
            figure.savefig(output / name, dpi=200)
            pdf.savefig(figure)
            plt.close(figure)
            names.append(name)
    return names


def _parse_product_specs(specs: Sequence[str]) -> List[Tuple[str, Path]]:
    result: List[Tuple[str, Path]] = []
    for spec in specs:
        if "=" not in spec:
            raise ValueError(f"product must be LABEL=RESULT_ROOT, got {spec!r}")
        label, path = spec.split("=", 1)
        if not label.strip() or not path.strip():
            raise ValueError(f"product must be LABEL=RESULT_ROOT, got {spec!r}")
        source = Path(path).expanduser()
        if source.name == "manifest.json":
            source = source.parent
        result.append((label.strip(), source))
    return result


def _published_inventory(samples: Sequence[Sample],
                         include_source_metadata: bool) -> List[Dict[str, Any]]:
    if include_source_metadata:
        return [dict(sample.inventory) for sample in samples]
    return [
        {field: sample.inventory.get(field) for field in PUBLIC_INVENTORY_FIELDS}
        for sample in samples
    ]


def _ensure_analysis(root: Path, args: argparse.Namespace) -> None:
    analysis = root / "analysis"
    required = [
        analysis / "runs.csv", analysis / "steps.csv",
        analysis / "drops.csv", analysis / "frames.csv",
    ]
    if not args.reanalyze and all(path.is_file() for path in required):
        return
    print(f"combine: analyzing {root}", flush=True)
    rcprobe.analyze(argparse.Namespace(
        manifest=str(root),
        case_regex=".*",
        qpextract=args.qpextract,
        ffmpeg=args.ffmpeg,
        smoothing_seconds=args.smoothing_seconds,
        raw_qp_overlay=args.raw_qp_overlay,
        plot_repeats=args.plot_repeats,
        no_plots=args.no_case_plots,
        allow_missing=True,
        jobs=args.jobs,
    ))


def compare(args: argparse.Namespace) -> None:
    product_specs = _parse_product_specs(args.product)
    profiles = tuple(value.strip() for value in args.focus_profiles.split(",")
                     if value.strip())
    counts: Dict[str, int] = defaultdict(int)
    samples: List[Sample] = []
    for ordinal, (product, root) in enumerate(product_specs, 1):
        print(
            f"combine [{ordinal}/{len(product_specs)}] loading {product}={root}",
            flush=True)
        counts[product] += 1
        sample_id = f"{product}#{counts[product]}"
        _ensure_analysis(root.resolve(), args)
        samples.append(_load_sample(product, sample_id, root, profiles))
    if len(samples) < 2:
        raise ValueError("at least two result roots are required")
    baseline = samples[0]
    ignored_fields = set(args.ignore_contract_field)
    used_ignored_fields: Set[str] = set()
    override_records: List[Dict[str, Any]] = []
    incompatible_details: List[str] = []
    for sample in samples[1:]:
        if sample.signature == baseline.signature:
            continue
        differences = _contract_differences(baseline.contract, sample.contract)
        remaining = []
        for difference in differences:
            field = difference.split(": baseline=", 1)[0]
            if field in ignored_fields:
                used_ignored_fields.add(field)
            else:
                remaining.append(difference)
        if remaining:
            shown = remaining[:12]
            if len(remaining) > len(shown):
                shown.append(f"... and {len(remaining) - len(shown)} more")
            hints = " ".join(
                f"--ignore-contract-field {difference.split(': baseline=', 1)[0]}"
                for difference in remaining[:12])
            incompatible_details.append(
                f"{sample.sample_id}={sample.signature[:12]}: "
                + "; ".join(shown)
                + f". Intentional override: {hints}")
        overridden = [
            difference for difference in differences
            if difference.split(": baseline=", 1)[0] in ignored_fields]
        if overridden:
            override_records.append({
                "sample_id": sample.sample_id,
                "contract_sha256": sample.signature,
                "differences": overridden,
            })
    if incompatible_details:
        raise ValueError(
            f"incompatible manifests; baseline {baseline.sample_id}="
            f"{baseline.signature[:12]}; mismatches: "
            + " | ".join(incompatible_details))
    unused_ignored_fields = ignored_fields - used_ignored_fields
    if unused_ignored_fields:
        raise ValueError(
            "contract override field(s) did not match any manifest difference: "
            + ", ".join(sorted(unused_ignored_fields)))
    if override_records:
        print(
            "WARNING: comparison contract mismatch explicitly overridden for: "
            + ", ".join(sorted(used_ignored_fields)), file=sys.stderr)
    available_profiles = {
        row["qp_profile"] for sample in samples for row in sample.runs
    }
    missing_profiles = [profile for profile in profiles if profile not in available_profiles]
    if missing_profiles:
        raise ValueError(f"requested profiles absent from inputs: {missing_profiles}")

    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    aggregates = _aggregate(samples)
    published_inventory = _published_inventory(
        samples, args.include_source_metadata)
    _write_csv(output / "samples.csv", published_inventory)
    for name, rows in aggregates.items():
        _write_csv(output / f"{name}.csv", rows)
    plot_names: List[str] = []
    if not args.no_plots:
        plot_names = _make_plots(
            output, aggregates, sorted(counts), profiles)
    body = {
        "schema_version": 1,
        "contract_sha256": baseline.signature,
        "contract": baseline.contract,
        "contract_overrides": {
            "ignored_fields": sorted(used_ignored_fields),
            "samples": override_records,
        },
        "focus_profiles": profiles,
        "products": sorted(counts),
        "sample_count": len(samples),
        "includes_source_metadata": args.include_source_metadata,
        "samples": published_inventory,
        "aggregates": aggregates,
        "plots": plot_names,
    }
    (output / "comparison.json").write_text(
        json.dumps(body, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    readme = [
        "# Encoder RC product comparison", "",
        f"Compatible contract: `{baseline.signature}`", "",
        f"Products: {', '.join(sorted(counts))}",
        f"Result roots: {len(samples)}", "",
        "Aggregation is deliberately two-stage:", "",
        "1. Median repeated cases inside each result root/device sample.",
        "2. Median and IQR of those sample-level values per product.", "",
        "This prevents a product with more repeats or roots from receiving extra weight.",
        "Only scientifically valid runs are included. Response-time CSVs retain valid/eligible coverage.",
        "Manifest compatibility excludes codec component name but requires identical MIME, operating point,",
        "stimulus bytes/schedule, scenarios, modes, and QP-bound cells.", "",
        ("Local result-root paths and device/build identifiers are included by explicit request."
         if args.include_source_metadata else
         "Local result-root paths and device/build identifiers are omitted by default."), "",
        "Outputs include sample- and product-level run, rolling-window, QPY, rate-conditioned QPY, response, and drop CSVs;",
        "`09_rate_conditioned_qp.png` places median P-frame QPY against achieved/requested rate and sizes markers by maximum-QP pinning.",
        "It exposes bitrate purchases without claiming a QP-at-target normalization; that requires fixed-QP calibration.",
        "`comparison.json`; individual PNGs; and `product_comparison.pdf`.", "",
    ]
    if override_records:
        warning_lines = [
            "## ⚠ Compatibility overrides", "",
            ("This comparison intentionally ignores manifest contract fields. "
             "Interpret codec differences with those confounders in mind."), "",
            "Ignored fields: " + ", ".join(
                f"`{field}`" for field in sorted(used_ignored_fields)), "",
        ]
        for record in override_records:
            warning_lines.append(f"- `{record['sample_id']}`:")
            warning_lines.extend(
                f"  - {difference}" for difference in record["differences"])
        warning_lines.append("")
        readme[2:2] = warning_lines
    (output / "README.md").write_text("\n".join(readme), encoding="utf-8")
    print(output / "product_comparison.pdf" if plot_names else output / "comparison.json")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", help="comparison output directory")
    parser.add_argument(
        "--group", "--product", dest="product", action="append", required=True,
        metavar="LABEL=RESULT_ROOT_OR_MANIFEST",
        help=("group label and result folder or manifest.json; repeat for "
              "hardware/projects/samples, reusing a label for repeated runs"))
    parser.add_argument(
        "--ignore-contract-field", action="append", default=[], metavar="PATH",
        help=("explicitly ignore one normalized manifest difference named by an "
              "incompatibility error; repeat for multiple fields"))
    parser.add_argument(
        "--focus-profiles", default="unbounded,jcq_27_38",
        help="comma-separated QP profiles to retain")
    parser.add_argument(
        "--reanalyze", action="store_true",
        help="rerun per-root analysis even when its CSV outputs exist")
    parser.add_argument("--qpextract", default="qpextract")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument(
        "--jobs", type=int, default=min(4, os.cpu_count() or 1),
        help="parallel workers for any per-root analysis")
    parser.add_argument(
        "--smoothing-seconds", type=float, default=1.0,
        help="per-case QP plot smoothing used when analysis is run")
    parser.add_argument(
        "--raw-qp-overlay", action="store_true",
        help="overlay raw average QP in generated per-case plots")
    parser.add_argument(
        "--plot-repeats", action="store_true",
        help="plot repeats individually during per-root analysis")
    parser.add_argument(
        "--no-case-plots", action="store_true",
        help="skip per-case plots when analysis is run")
    parser.add_argument(
        "--include-source-metadata", action="store_true",
        help=("include local result-root paths and device model, SoC, and build "
              "fingerprint in samples.csv and comparison.json"))
    parser.add_argument("--no-plots", action="store_true")
    parser.set_defaults(func=compare)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _parser().parse_args(argv)
    try:
        args.func(args)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
