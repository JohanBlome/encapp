#!/usr/bin/env python3
"""Create aggregate CBR/VBR behavior plots from an encoder RC probe result."""

import argparse
import csv
import json
import math
import os
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence, Tuple

WINDOW_SECONDS = (0.2, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 10.0, 15.0, 20.0, 30.0)
MODE_COLORS = {"cbr": "#0072B2", "vbr": "#D55E00"}
PROFILE_ORDER = ("unbounded", "min27", "max38", "jcq_27_38", "tight_30_35")
PROFILE_LABELS = {
    "unbounded": "unbounded",
    "min27": "min 27",
    "max38": "max 38",
    "jcq_27_38": "27–38",
    "tight_30_35": "30–35",
}
FOCUS_PROFILES = ("unbounded", "jcq_27_38")
PROFILE_STYLES = {
    "unbounded": ("o", "-"),
    "min27": ("s", "--"),
    "max38": ("^", "-."),
    "jcq_27_38": ("D", ":"),
    "tight_30_35": ("v", (0, (5, 1))),
}
NUMERIC_FIELDS = {
    "repeat": int,
    "output_index": int,
    "original_frame": int,
    "pts_us": int,
    "dt_us": int,
    "is_i": int,
    "size_bytes": int,
    "bits": float,
    "vbv_delay_ms": float,
    "rolling_bps_1s": float,
    "qp_min": float,
    "qp_avg": float,
    "qp_max": float,
}


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


def _read_frames(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with path.open(newline="", encoding="utf-8") as handle:
        for raw in csv.DictReader(handle):
            row: Dict[str, Any] = dict(raw)
            for field, convert in NUMERIC_FIELDS.items():
                value = row.get(field)
                if value not in (None, ""):
                    row[field] = convert(value)
                else:
                    row[field] = None
            rows.append(row)
    return rows


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


def _group_by_case(rows: Iterable[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    result: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for row in rows:
        result[str(row["case_id"])].append(row)
    for case_rows in result.values():
        case_rows.sort(key=lambda row: int(row["original_frame"]))
    return dict(result)


def _peak_rolling_bps(rows: Sequence[Dict[str, Any]], window_s: float) -> float:
    """Peak full-window bitrate, excluding partially filled startup windows."""
    window_us = int(round(window_s * 1_000_000))
    left = 0
    bit_sum = 0.0
    peak = 0.0
    for right, row in enumerate(rows):
        bit_sum += float(row["bits"])
        now = int(row["pts_us"])
        while left < right and now - int(rows[left]["pts_us"]) > window_us:
            bit_sum -= float(rows[left]["bits"])
            left += 1
        duration_us = now - int(rows[left]["pts_us"]) + int(row["dt_us"])
        if duration_us >= window_us:
            peak = max(peak, bit_sum * 1_000_000.0 / duration_us)
    return peak


def _rolling_p_qpy(rows: Sequence[Dict[str, Any]], window_s: float = 1.0
                   ) -> List[Dict[str, float]]:
    """Trailing temporal mean of per-frame area-weighted QPY, excluding I-frames."""
    window_us = int(round(window_s * 1_000_000))
    p_rows = [row for row in rows
              if not int(row["is_i"]) and row.get("qp_avg") is not None]
    result: List[Dict[str, float]] = []
    left = 0
    total = 0.0
    for right, row in enumerate(p_rows):
        total += float(row["qp_avg"])
        now = int(row["pts_us"])
        while left < right and now - int(p_rows[left]["pts_us"]) > window_us:
            total -= float(p_rows[left]["qp_avg"])
            left += 1
        duration_us = now - int(p_rows[left]["pts_us"]) + int(row["dt_us"])
        if duration_us >= window_us:
            count = right - left + 1
            result.append({
                "pts_us": float(now),
                "qpy_weighted_mean_1s": total / count,
                "p_frame_count": float(count),
            })
    return result


def rolling_window_records(
    by_case: Dict[str, List[Dict[str, Any]]], target_bps: float,
) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for case_id, rows in by_case.items():
        if not rows or rows[0].get("scenario") != "dynamics":
            continue
        for window_s in WINDOW_SECONDS:
            peak_bps = _peak_rolling_bps(rows, window_s)
            records.append({
                "case_id": case_id,
                "repeat": rows[0]["repeat"],
                "mode": rows[0]["mode"],
                "qp_profile": rows[0]["qp_profile"],
                "window_s": window_s,
                "peak_bps": peak_bps,
                "peak_over_target": peak_bps / target_bps,
            })
    return records


def _one_second_bps(rows: Sequence[Dict[str, Any]], start: int, fps: int) -> float:
    bits = sum(float(row["bits"]) for row in rows
               if start <= int(row["original_frame"]) < start + fps)
    return bits


def pulse_recovery_records(
    by_case: Dict[str, List[Dict[str, Any]]], cases: Dict[str, Dict[str, Any]], fps: int,
) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for case_id, rows in by_case.items():
        case = cases.get(case_id)
        if not case or case.get("scenario") != "dynamics" or not rows:
            continue
        events = [(int(frame), str(level))
                  for frame, level in case["scenario_def"]["events"]]
        pulses: List[Tuple[int, int, int]] = []
        for index in range(1, len(events) - 1):
            start, level = events[index]
            end, next_level = events[index + 1]
            if events[index - 1][1] == "LOW" and level == "HIGH" and next_level == "LOW":
                pulses.append((start, end, end - start))
        if not pulses:
            continue
        first_pulse_start = pulses[0][0]
        previous_low_start = max(frame for frame, level in events
                                 if frame < first_pulse_start and level == "LOW")
        baseline_start = max(previous_low_start, first_pulse_start - 4 * fps)
        baseline_bins = [
            _one_second_bps(rows, start, fps)
            for start in range(baseline_start, first_pulse_start, fps)
            if start + fps <= first_pulse_start
        ]
        if not baseline_bins:
            continue
        baseline_bps = statistics.median(baseline_bins)
        for _pulse_start, low_start, pulse_frames in pulses:
            next_event = next((frame for frame, _level in events if frame > low_start),
                              int(case["scenario_def"]["frames"]))
            post_seconds = max(0, (next_event - low_start) // fps)
            for post_second in range(post_seconds):
                observed = _one_second_bps(rows, low_start + post_second * fps, fps)
                records.append({
                    "case_id": case_id,
                    "repeat": rows[0]["repeat"],
                    "mode": rows[0]["mode"],
                    "qp_profile": rows[0]["qp_profile"],
                    "pulse_seconds": pulse_frames / float(fps),
                    "post_second": post_second,
                    "post_bps": observed,
                    "steady_low_bps": baseline_bps,
                    "post_over_steady_low": observed / baseline_bps,
                })
    return records


def stream_qp_records(
    by_case: Dict[str, List[Dict[str, Any]]],
) -> List[Dict[str, Any]]:
    """Per-stream coded-area QPY low/mean/high for all, I, and P frames."""
    records: List[Dict[str, Any]] = []
    for case_id, rows in by_case.items():
        if not rows:
            continue
        eligible = [row for row in rows
                    if row.get("qp_min") is not None
                    and row.get("qp_avg") is not None
                    and row.get("qp_max") is not None]
        frame_sets = (
            ("all", eligible),
            ("I", [row for row in eligible if int(row["is_i"]) != 0]),
            ("P", [row for row in eligible if int(row["is_i"]) == 0]),
        )
        for frame_class, selected in frame_sets:
            if not selected:
                continue
            records.append({
                "case_id": case_id,
                "repeat": rows[0]["repeat"],
                "mode": rows[0]["mode"],
                "qp_profile": rows[0]["qp_profile"],
                "scenario": rows[0]["scenario"],
                "frame_class": frame_class,
                "frame_count": len(selected),
                "qpy_low": min(float(row["qp_min"]) for row in selected),
                # qpy.avg is already pixel-area weighted within each frame. All
                # streams here have constant coded area, so its arithmetic mean
                # is also the stream-wide pixel-area-weighted mean.
                "qpy_weighted_mean": statistics.fmean(
                    float(row["qp_avg"]) for row in selected),
                "qpy_high": max(float(row["qp_max"]) for row in selected),
            })
    return records


def rate_conditioned_qp_records(
    by_case: Dict[str, List[Dict[str, Any]]],
    cases: Dict[str, Dict[str, Any]],
    target_bps: float,
) -> List[Dict[str, Any]]:
    """Relate P-frame QPY to the achieved rate that paid for it."""
    records: List[Dict[str, Any]] = []
    for case_id, rows in by_case.items():
        case = cases.get(case_id)
        if not rows or case is None:
            continue
        p_rows = [row for row in rows
                  if not int(row["is_i"]) and row.get("qp_avg") is not None]
        duration_us = sum(int(row["dt_us"]) for row in rows
                          if row.get("dt_us") is not None)
        if not p_rows or duration_us <= 0 or target_bps <= 0:
            continue
        qp_values = [float(row["qp_avg"]) for row in p_rows]
        qp_steps = [
            abs(float(right["qp_avg"]) - float(left["qp_avg"]))
            for left, right in zip(p_rows, p_rows[1:])
            if int(right["original_frame"]) == int(left["original_frame"]) + 1
        ]
        achieved_bps = sum(float(row["bits"]) for row in rows) * 1_000_000.0 / duration_us
        max_bound = case.get("qp_bounds", {}).get("max")
        pinned_fraction = None
        if max_bound is not None:
            pinned_fraction = sum(
                value >= float(max_bound) - 1e-6 for value in qp_values
            ) / len(qp_values)
        records.append({
            "case_id": case_id,
            "repeat": rows[0]["repeat"],
            "mode": rows[0]["mode"],
            "qp_profile": rows[0]["qp_profile"],
            "scenario": rows[0]["scenario"],
            "achieved_bps": achieved_bps,
            "achieved_over_target": achieved_bps / target_bps,
            "p_qpy_median": statistics.median(qp_values),
            "p_qpy_stddev": statistics.pstdev(qp_values),
            "p_qpy_mean_abs_step": (
                statistics.fmean(qp_steps) if qp_steps else 0.0),
            "pinned_at_max_fraction": pinned_fraction,
            "p_frame_count": len(qp_values),
        })
    return records


def ip_size_ratio_records(
    by_case: Dict[str, List[Dict[str, Any]]],
) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for case_id, rows in by_case.items():
        if not rows:
            continue
        i_sizes = [float(row["size_bytes"]) for row in rows if int(row["is_i"])]
        p_sizes = [float(row["size_bytes"]) for row in rows if not int(row["is_i"])]
        if not i_sizes or not p_sizes:
            continue
        i_median = statistics.median(i_sizes)
        p_median = statistics.median(p_sizes)
        records.append({
            "case_id": case_id,
            "repeat": rows[0]["repeat"],
            "mode": rows[0]["mode"],
            "qp_profile": rows[0]["qp_profile"],
            "scenario": rows[0]["scenario"],
            "i_median_bytes": i_median,
            "p_median_bytes": p_median,
            "i_over_p_size_ratio": i_median / p_median,
        })
    return records


def half_rate_records(path: Path) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row.get("region") != "half_rate" or row.get("qp_profile") not in FOCUS_PROFILES:
                continue
            records.append({
                "case_id": row["case_id"],
                "repeat": int(row["repeat"]),
                "mode": row["mode"],
                "qp_profile": row["qp_profile"],
                "configured_fps": 30,
                "submitted_fps": 15,
                "expected_input_slots": int(row["region_end"]) - int(row["region_start"]),
                "omitted_input_slots": int(row["observed_missing_count"]),
                "output_frame_count": int(row["output_frame_count"]),
                "region_bitrate_ratio": float(row["region_bitrate_ratio"]),
                "post_p_size_ratio": float(row["post_size_vs_baseline_median"]),
                "post_p_qp_delta": float(row["post_qp_delta_median"]),
                "max_pts_gap_ms": float(row["max_pts_gap_ms"]),
                "max_vbv_delay_ms": float(row["max_vbv_delay_ms"]),
            })
    return records


def _setup_matplotlib():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.backends.backend_pdf import PdfPages
    plt.rcParams.update({
        "axes.grid": True,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "figure.dpi": 120,
        "font.size": 10,
        "grid.alpha": 0.22,
        "legend.frameon": False,
        "savefig.bbox": "tight",
    })
    return plt, PdfPages


def _profile_summary(records: Sequence[Dict[str, Any]]) -> Dict[Tuple[str, str, float], float]:
    grouped: Dict[Tuple[str, str, float], List[float]] = defaultdict(list)
    for row in records:
        grouped[(str(row["mode"]), str(row["qp_profile"]), float(row["window_s"]))].append(
            float(row["peak_over_target"]))
    return {key: statistics.median(values) for key, values in grouped.items()}


def _plot_rolling_windows(plt, records: Sequence[Dict[str, Any]]):
    focused = [row for row in records if row["qp_profile"] in FOCUS_PROFILES]
    summary = _profile_summary(focused)
    fig, ax = plt.subplots(figsize=(13.5, 5.4))
    windows = list(WINDOW_SECONDS)
    for profile in FOCUS_PROFILES:
        for mode in ("cbr", "vbr"):
            x = [window for window in windows
                 if (mode, profile, window) in summary]
            y = [summary[(mode, profile, window)] for window in x]
            marker, linestyle = PROFILE_STYLES[profile]
            ax.plot(x, y, color=MODE_COLORS[mode], marker=marker,
                    linestyle=linestyle, linewidth=2.2, markersize=5.0,
                    label=f"{mode.upper()} {PROFILE_LABELS[profile]}")
    ax.axhline(1.0, color="black", linestyle="--", linewidth=1.0,
               label="requested rate")
    ax.set_xscale("log")
    ax.set_xticks(windows)
    ax.set_xticklabels([f"{value:g}" for value in windows], rotation=45)
    ax.set_xlabel("Averaging window (seconds)")
    ax.set_ylabel("Peak rolling average / 8 Mbps request")
    ax.set_xlim(min(windows) * 0.85, max(windows) * 1.1)
    ax.legend(loc="upper right", ncol=2)
    ax.set_title("Unbounded versus production QP 27–38 rate behavior", fontsize=15)
    fig.text(0.5, 0.01,
             "Each point is the median of three identical repeats; peaks require a fully populated window. "
             "A reporting window is not an identified internal controller constant.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.045, 1, 0.94))
    return fig


def _plot_pulse_recovery(plt, records: Sequence[Dict[str, Any]]):
    records = [row for row in records if row["qp_profile"] in FOCUS_PROFILES]
    pulses = sorted(set(float(row["pulse_seconds"]) for row in records))
    if not pulses:
        return None
    fig, axes = plt.subplots(1, len(pulses), figsize=(13.5, 4.6), sharey=True)
    if len(pulses) == 1:
        axes = [axes]
    for ax, pulse in zip(axes, pulses):
        for profile in FOCUS_PROFILES:
            for mode in ("cbr", "vbr"):
                grouped: Dict[int, List[float]] = defaultdict(list)
                for row in records:
                    if (row["mode"] == mode and row["qp_profile"] == profile
                            and float(row["pulse_seconds"]) == pulse):
                        grouped[int(row["post_second"])].append(
                            float(row["post_over_steady_low"]))
                x = [second + 0.5 for second in sorted(grouped)]
                median = [statistics.median(grouped[second]) for second in sorted(grouped)]
                low = [_quantile(grouped[second], 0.25) for second in sorted(grouped)]
                high = [_quantile(grouped[second], 0.75) for second in sorted(grouped)]
                marker, linestyle = PROFILE_STYLES[profile]
                ax.fill_between(x, low, high, color=MODE_COLORS[mode], alpha=0.08)
                ax.plot(x, median, color=MODE_COLORS[mode], marker=marker,
                        linestyle=linestyle, linewidth=2.0,
                        label=f"{mode.upper()} {PROFILE_LABELS[profile]}")
        ax.axhline(1.0, color="black", linestyle="--", linewidth=1.0,
                   label="steady LOW baseline")
        ax.axvline(0.0, color="#CC79A7", linestyle=":", linewidth=1.2)
        ax.text(
            0.02, 0.97, "HIGH → LOW\nrecovery starts",
            transform=ax.transAxes, ha="left", va="top", fontsize=8,
            color="#8E4A75",
            bbox={"facecolor": "white", "edgecolor": "none", "alpha": 0.75})
        ax.set_title(f"{pulse:g} s HIGH pulse")
        ax.set_xlabel("Seconds after HIGH → LOW")
        ax.set_xticks([second + 0.5 for second in sorted(
            set(int(row["post_second"]) for row in records
                if float(row["pulse_seconds"]) == pulse))])
        ax.set_xticklabels([f"{second}–{second + 1}" for second in sorted(
            set(int(row["post_second"]) for row in records
                if float(row["pulse_seconds"]) == pulse))], rotation=45)
    axes[0].set_ylabel("One-second output / steady LOW output")
    axes[-1].legend(loc="best", fontsize=8)
    fig.suptitle("CBR makes a stronger immediate repayment after complexity bursts", fontsize=15)
    fig.text(0.5, 0.01,
             "Lines are medians for unbounded and QP 27–38 across three repeats; "
             "bands are interquartile ranges. "
             "The repeated deterministic source causes residual one-second structure.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.07, 1, 0.92))
    return fig


def _shade_complexity(ax, events: Sequence[Tuple[int, str]], end_frame: int,
                      fps: int, annotate: bool = False) -> None:
    colors = {"LOW": "#009E73", "MID": "#E69F00", "HIGH": "#CC79A7"}
    for index, (start, level) in enumerate(events):
        end = events[index + 1][0] if index + 1 < len(events) else end_frame
        start_s, end_s = start / fps, end / fps
        ax.axvspan(start_s, end_s, color=colors[level], alpha=0.07, linewidth=0)
        if annotate:
            duration = end_s - start_s
            label = level if duration >= 5 else f"{level}\n{duration:g} s"
            ax.text(
                (start_s + end_s) / 2.0, 0.985, label,
                transform=ax.get_xaxis_transform(), ha="center", va="top",
                fontsize=7.5, color=colors[level], fontweight="bold",
                bbox={"facecolor": "white", "edgecolor": "none", "alpha": 0.68,
                      "pad": 1.3})


def _plot_qp_summary(plt, records: Sequence[Dict[str, Any]]):
    selected = [row for row in records if row["qp_profile"] in FOCUS_PROFILES]
    scenarios = ("dynamics", "hard_start", "drops")
    scenario_labels = {"dynamics": "Dynamics", "hard_start": "Hard start",
                       "drops": "Input omissions"}
    frame_classes = ("all", "I", "P")
    fig, axes = plt.subplots(2, 3, figsize=(13.5, 8.0), sharey="row")
    offsets = {"cbr": -0.13, "vbr": 0.13}
    for row_index, profile in enumerate(FOCUS_PROFILES):
        for column, frame_class in enumerate(frame_classes):
            ax = axes[row_index][column]
            for mode in ("cbr", "vbr"):
                positions: List[float] = []
                lows: List[float] = []
                means: List[float] = []
                highs: List[float] = []
                for index, scenario in enumerate(scenarios):
                    group = [row for row in selected
                             if row["qp_profile"] == profile
                             and row["frame_class"] == frame_class
                             and row["mode"] == mode and row["scenario"] == scenario]
                    if not group:
                        continue
                    positions.append(index + offsets[mode])
                    lows.append(statistics.median(float(row["qpy_low"]) for row in group))
                    means.append(statistics.median(
                        float(row["qpy_weighted_mean"]) for row in group))
                    highs.append(statistics.median(float(row["qpy_high"]) for row in group))
                ax.vlines(positions, lows, highs, color=MODE_COLORS[mode],
                          linewidth=3.0, alpha=0.72)
                ax.scatter(positions, means, color=MODE_COLORS[mode], s=54, zorder=3,
                           edgecolor="white", linewidth=0.8, label=mode.upper())
                for position, mean in zip(positions, means):
                    ax.text(position, mean + 0.35, f"{mean:.1f}",
                            color=MODE_COLORS[mode], ha="center", va="bottom", fontsize=7.5)
            if profile == "jcq_27_38":
                ax.axhline(27, color="black", linestyle=":", linewidth=1.0, alpha=0.55)
                ax.axhline(38, color="black", linestyle=":", linewidth=1.0, alpha=0.55)
            ax.set_xticks(range(len(scenarios)))
            ax.set_xticklabels([scenario_labels[value] for value in scenarios], rotation=25)
            ax.set_title(f"{frame_class}-frame" if frame_class != "all" else "All-frame")
        axes[row_index][0].set_ylabel(
            f"{PROFILE_LABELS[profile]}\nBitstream QPY")
        if profile == "unbounded":
            axes[row_index][0].set_ylim(22.5, 52.5)
        else:
            axes[row_index][0].set_ylim(25.5, 39.5)
    axes[0][2].legend(loc="upper right")
    fig.suptitle("Unbounded versus production QP 27–38 stream statistics",
                 fontsize=15)
    fig.text(0.5, 0.01,
             "Vertical lines span the lowest to highest coded-unit QPY; dots and labels show the "
             "stream-wide pixel-area-weighted mean. Dotted lines are the production bounds.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.04, 1, 0.95))
    return fig


def _plot_representative(plt, by_case: Dict[str, List[Dict[str, Any]]],
                         cases: Dict[str, Dict[str, Any]], fps: int,
                         target_bps: float, profile: str,
                         smoothing_seconds: float = 1.0):
    selected: Dict[str, Tuple[Dict[str, Any], List[Dict[str, Any]]]] = {}
    for case_id, case in cases.items():
        if (case.get("scenario") == "dynamics" and case.get("qp_profile") == profile
                and int(case.get("repeat", 0)) == 1 and case_id in by_case):
            selected[str(case["mode"])] = (case, by_case[case_id])
    if set(selected) != {"cbr", "vbr"}:
        return None
    fig, axes = plt.subplots(3, 1, figsize=(13.5, 9.6), sharex=True)
    qp_annotations: List[str] = []
    qp_trend_values: List[float] = []
    for mode in ("cbr", "vbr"):
        case, rows = selected[mode]
        x = [float(row["pts_us"]) / 1_000_000.0 for row in rows]
        axes[0].plot(x, [float(row["rolling_bps_1s"]) / target_bps for row in rows],
                     color=MODE_COLORS[mode], linewidth=1.6, label=mode.upper())
        axes[1].plot(x, [float(row["vbv_delay_ms"]) / 1000.0 for row in rows],
                     color=MODE_COLORS[mode], linewidth=1.8, label=mode.upper())
        qp_rows = [row for row in rows if row.get("qp_avg") is not None]
        trend = _rolling_p_qpy(rows, smoothing_seconds)
        qp_trend_values.extend(
            float(row["qpy_weighted_mean_1s"]) for row in trend)
        smoothing_label = ("raw P-frame QPY" if smoothing_seconds <= 0 else
                           f"{smoothing_seconds:g} s trailing P-frame mean")
        axes[2].plot(
            [row["pts_us"] / 1_000_000.0 for row in trend],
            [row["qpy_weighted_mean_1s"] for row in trend],
            color=MODE_COLORS[mode], linewidth=2.0,
            label=f"{mode.upper()} {smoothing_label}")
        qmean = [float(row["qp_avg"]) for row in qp_rows]
        qlow = [float(row["qp_min"]) for row in qp_rows]
        qhigh = [float(row["qp_max"]) for row in qp_rows]
        low = min(qlow)
        mean = statistics.fmean(qmean)
        high = max(qhigh)
        qp_annotations.append(
            f"{mode.upper()} all-frame low / mean / high: "
            f"{low:.0f} / {mean:.1f} / {high:.0f}")
    case = selected["cbr"][0]
    events = [(int(frame), str(level)) for frame, level in case["scenario_def"]["events"]]
    for index, ax in enumerate(axes):
        _shade_complexity(
            ax, events, int(case["scenario_def"]["frames"]), fps,
            annotate=index == 0)
    axes[0].axhline(1.0, color="black", linestyle="--", linewidth=1.0)
    axes[0].set_ylabel("One-second bitrate / request")
    axes[0].set_ylim(bottom=0)
    axes[0].legend(loc="upper right")
    axes[1].set_ylabel(f"Observable backlog at {target_bps / 1e6:g} Mbps (seconds)")
    axes[1].set_ylim(bottom=0)
    axes[1].legend(loc="upper left")
    bounds = case.get("qp_bounds", {})
    if bounds.get("min") is not None:
        axes[2].axhline(float(bounds["min"]), color="black", linestyle=":", linewidth=1.0)
    if bounds.get("max") is not None:
        axes[2].axhline(float(bounds["max"]), color="black", linestyle=":", linewidth=1.0)
    axes[2].set_ylabel(
        "P-frame QPY" if smoothing_seconds <= 0
        else f"{smoothing_seconds:g} s trailing P-frame mean QPY")
    if qp_trend_values:
        axes[2].set_ylim(min(qp_trend_values) - 0.75,
                         max(qp_trend_values) + 0.75)
    axes[2].set_xlabel("Stream time (seconds)")
    axes[2].legend(loc="upper left")
    axes[2].text(0.995, 0.97, "\n".join(qp_annotations), transform=axes[2].transAxes,
                 ha="right", va="top", fontsize=9,
                 bbox={"facecolor": "white", "edgecolor": "0.8", "alpha": 0.86})
    fig.suptitle(
        f"Same deterministic stimulus, QP {PROFILE_LABELS.get(profile, profile)}",
        fontsize=15)
    fig.text(0.5, 0.01,
             f"Background: LOW (green), MID (orange), HIGH (purple). QPY uses "
             f"{'raw P-frames' if smoothing_seconds <= 0 else f'a {smoothing_seconds:g} s trailing mean of pixel-area-weighted P-frame QPY'}; "
             "I-frames are excluded. Backlog is an external queue, not hardware VBV.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.045, 1, 0.95))
    return fig


def _plot_ip_ratio(plt, records: Sequence[Dict[str, Any]]):
    scenarios = ("dynamics", "hard_start", "drops")
    scenario_labels = {"dynamics": "Dynamics", "hard_start": "Hard start",
                       "drops": "Input omissions"}
    fig, axes = plt.subplots(1, 2, figsize=(13.5, 5.2), sharey=True)
    width = 0.32
    for ax, profile in zip(axes, FOCUS_PROFILES):
        for mode_index, mode in enumerate(("cbr", "vbr")):
            x = [index + (mode_index - 0.5) * width for index in range(len(scenarios))]
            values = []
            lows = []
            highs = []
            for scenario in scenarios:
                group = [float(row["i_over_p_size_ratio"]) for row in records
                         if row["qp_profile"] == profile and row["mode"] == mode
                         and row["scenario"] == scenario]
                values.append(statistics.median(group) if group else math.nan)
                lows.append(_quantile(group, 0.25))
                highs.append(_quantile(group, 0.75))
            bars = ax.bar(x, values, width=width, color=MODE_COLORS[mode],
                          alpha=0.86, label=mode.upper())
            ax.errorbar(x, values,
                        yerr=[[value - low for value, low in zip(values, lows)],
                              [high - value for value, high in zip(values, highs)]],
                        fmt="none", color="black", linewidth=0.8, capsize=2)
            for bar, value in zip(bars, values):
                ax.text(bar.get_x() + bar.get_width() / 2, value + 0.75,
                        f"{value:.2f}×", ha="center", va="bottom", fontsize=9)
        ax.set_xticks(range(len(scenarios)))
        ax.set_xticklabels([scenario_labels[value] for value in scenarios])
        ax.set_title(f"QP {PROFILE_LABELS[profile]}")
        ax.legend(loc="upper left")
    axes[0].set_ylabel("Median I-frame bytes / median P-frame bytes")
    axes[0].set_ylim(0, 36)
    fig.suptitle("I/P frame-size allocation", fontsize=15)
    fig.text(0.5, 0.01,
             "Bars are medians across three deterministic repeats; error bars are interquartile ranges. "
             "The omission scenario has fewer P frames and therefore a different population.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.05, 1, 0.93))
    return fig


def _pinning_marker_area(pinned_fraction: float) -> float:
    """Map pinning to a legible marker diameter; Matplotlib expects area."""
    diameter_points = 6.0 + 24.0 * max(0.0, min(1.0, pinned_fraction))
    return diameter_points ** 2


def _contained_marker_sizes(
    points: Sequence[Tuple[float, float, float]],
    *,
    radius_step: float = 0.5,
) -> List[float]:
    """Enlarge coincident markers into nested, coordinate-preserving layers."""
    if not points:
        return []
    x_values = [point[0] for point in points]
    y_values = [point[1] for point in points]
    x_tolerance = max((max(x_values) - min(x_values)) * 0.008, 1e-6)
    y_tolerance = max((max(y_values) - min(y_values)) * 0.015, 1e-3)
    parents = list(range(len(points)))

    def root(index: int) -> int:
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index

    def merge(left: int, right: int) -> None:
        left_root = root(left)
        right_root = root(right)
        if left_root != right_root:
            parents[right_root] = left_root

    for left, (left_x, left_y, _) in enumerate(points):
        for right in range(left + 1, len(points)):
            right_x, right_y, _ = points[right]
            if (abs(left_x - right_x) <= x_tolerance
                    and abs(left_y - right_y) <= y_tolerance):
                merge(left, right)

    clusters: Dict[int, List[int]] = defaultdict(list)
    for index in range(len(points)):
        clusters[root(index)].append(index)
    sizes = [point[2] for point in points]
    for indices in clusters.values():
        if len(indices) < 2:
            continue
        maximum_base = max(points[index][2] for index in indices)
        for rank, index in enumerate(indices):
            radius_scale = 1.0 + radius_step * (len(indices) - rank - 1)
            sizes[index] = maximum_base * radius_scale ** 2
    return sizes


def _plot_rate_conditioned_qp(plt, records: Sequence[Dict[str, Any]]):
    selected = [row for row in records if row["qp_profile"] == "jcq_27_38"]
    if not selected:
        return None
    scenario_markers = {
        "dynamics": "o",
        "hard_start": "s",
        "drops": "^",
    }
    scenario_labels = {
        "dynamics": "Dynamics",
        "hard_start": "Hard start",
        "drops": "Input omissions",
    }
    grouped: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)
    for row in selected:
        grouped[(str(row["mode"]), str(row["scenario"]))].append(row)

    fig, ax = plt.subplots(figsize=(12.5, 6.4))
    plot_points: List[Dict[str, Any]] = []
    for mode, scenario in sorted(grouped):
        rows = grouped[(mode, scenario)]
        rate_ratio = statistics.median(
            float(row["achieved_over_target"]) for row in rows)
        median_qp = statistics.median(float(row["p_qpy_median"]) for row in rows)
        pin_values = [float(row["pinned_at_max_fraction"]) for row in rows
                      if row.get("pinned_at_max_fraction") is not None]
        pin_fraction = statistics.median(pin_values) if pin_values else 0.0
        plot_points.append({
            "mode": mode,
            "scenario": scenario,
            "rate_ratio": rate_ratio,
            "median_qp": median_qp,
            "pin_fraction": pin_fraction,
            "base_size": _pinning_marker_area(pin_fraction),
        })
    display_sizes = _contained_marker_sizes([
        (point["rate_ratio"], point["median_qp"], point["base_size"])
        for point in plot_points
    ])
    for point, display_size in zip(plot_points, display_sizes):
        mode = point["mode"]
        scenario = point["scenario"]
        pin_fraction = point["pin_fraction"]
        ax.scatter(
            [point["rate_ratio"]], [point["median_qp"]],
            s=display_size,
            marker=scenario_markers.get(scenario, "o"),
            color=MODE_COLORS[mode], edgecolor="black", linewidth=0.6,
            alpha=0.9,
            label=(f"{mode.upper()} {scenario_labels.get(scenario, scenario)} "
                   f"({pin_fraction * 100:.0f}% at max)"),
        )

    ax.axvline(1.0, color="black", linestyle="--", linewidth=1.0)
    ax.set_xlabel("Achieved mean bitrate / requested bitrate")
    ax.set_ylabel("Median P-frame QPY")
    ax.set_title("Rate-conditioned P-frame QPY, configured bounds 27–38")
    ax.grid(True, alpha=0.25)
    ax.legend(loc="center left", bbox_to_anchor=(1.01, 0.5), fontsize=8)
    fig.text(
        0.39, 0.025,
        "Marker diameter encodes maximum-QP pinning; nested rings reveal "
        "coincident points (legend percentages are exact).\n"
        "Lower QPY is better typical-frame quality; more pinning is a worse "
        "tail; farther right spends more bits.\n"
        "This is rate-conditioned, not QP-at-target normalized. QPY includes "
        "skipped blocks; source coordinates and axis limits are unchanged.",
        ha="center", va="bottom", fontsize=8.5, linespacing=1.35,
    )
    fig.tight_layout(rect=(0, 0.14, 0.78, 1))
    return fig


def _plot_half_rate(plt, records: Sequence[Dict[str, Any]]):
    labels = ("CBR\nunbounded", "VBR\nunbounded", "CBR\nQP 27–38", "VBR\nQP 27–38")
    keys = (("cbr", "unbounded"), ("vbr", "unbounded"),
            ("cbr", "jcq_27_38"), ("vbr", "jcq_27_38"))
    colors = [MODE_COLORS[mode] for mode, _profile in keys]
    hatches = ["", "", "//", "//"]
    grouped: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)
    for row in records:
        grouped[(str(row["mode"]), str(row["qp_profile"]))].append(row)
    metrics = (
        ("region_bitrate_ratio", "Delivered bitrate\n÷ 30 fps baseline", 1.0),
        ("post_p_size_ratio", "Following P-frame size\n÷ baseline P frame", 1.0),
        ("post_p_qp_delta", "Following P-frame\nQPY change", 0.0),
    )
    fig, axes = plt.subplots(1, 3, figsize=(14.0, 6.4))
    for ax, (metric, title, reference) in zip(axes, metrics):
        values = [
            (statistics.median(float(row[metric]) for row in grouped[key])
             if grouped[key] else math.nan)
            for key in keys]
        bars = ax.bar(range(len(keys)), values, color=colors, alpha=0.88,
                      edgecolor="black", linewidth=0.4)
        for bar, hatch in zip(bars, hatches):
            bar.set_hatch(hatch)
        ax.axhline(reference, color="black", linestyle="--", linewidth=1.0)
        ax.set_xticks(range(len(keys)))
        ax.set_xticklabels(labels, fontsize=9)
        ax.set_title(title, fontsize=12, pad=14)
        ax.margins(y=0.22)
        for bar, value in zip(bars, values):
            label_y = value * 0.82
            ax.text(bar.get_x() + bar.get_width() / 2, label_y,
                    f"{value:.2f}", ha="center", va="center", fontsize=9,
                    fontweight="bold", color="white")
    fig.suptitle("30 fps configured; 15 fps submitted for four seconds",
                 fontsize=16, y=0.975)
    fig.text(0.5, 0.90,
             "Every other input omitted: 60 of 120 slots; 60 outputs; retained PTS gaps 66.7 ms",
             ha="center", fontsize=10)
    fig.text(0.5, 0.025,
             "Bars are medians across three deterministic repeats. Hatched bars use production QP 27–38; "
             "solid bars are unbounded.",
             ha="center", fontsize=9)
    fig.subplots_adjust(left=0.055, right=0.985, bottom=0.17, top=0.76, wspace=0.30)
    return fig


def _window_range(records: Sequence[Dict[str, Any]], mode: str, window: float,
                  include_tight: bool = False) -> Tuple[float, float]:
    summary = _profile_summary(records)
    values = [value for (item_mode, profile, item_window), value in summary.items()
              if item_mode == mode and item_window == window
              and (include_tight or profile != "tight_30_35")]
    return min(values), max(values)


def _format_summary_value(
        summary: Dict[Tuple[str, str, float], float],
        mode: str, profile: str, window: float) -> str:
    value = summary.get((mode, profile, window))
    return f"{value:.2f}×" if value is not None else "n/a"


AGGREGATE_GALLERY_BEGIN = "<!-- begin:encoder-rc-aggregate-plots -->"
AGGREGATE_GALLERY_END = "<!-- end:encoder-rc-aggregate-plots -->"


def _update_report_gallery(result_root: Path, output: Path,
                           image_names: Sequence[str]) -> None:
    report = result_root / "analysis" / "REPORT.md"
    if not report.is_file():
        return
    content = report.read_text(encoding="utf-8")
    if AGGREGATE_GALLERY_BEGIN in content:
        before, remainder = content.split(AGGREGATE_GALLERY_BEGIN, 1)
        after = (remainder.split(AGGREGATE_GALLERY_END, 1)[1]
                 if AGGREGATE_GALLERY_END in remainder else "")
    else:
        before, after = content, ""
    relative_output = Path(os.path.relpath(output, report.parent)).as_posix()
    gallery = [
        AGGREGATE_GALLERY_BEGIN, "", "## Aggregate plots", "",
    ]
    descriptions = {
        "01_peak_rolling_rate.png": (
            "Peak delivered rate versus observation-window length. The dashed 1× "
            "line is the requested target; separation at short windows reveals burst allowance."),
        "02_post_pulse_recovery.png": (
            "Output-rate recovery after each labeled HIGH→LOW transition. Values below 1× "
            "show repayment below the steady-LOW baseline."),
        "03_production_27_38_dynamics.png": (
            "Representative dynamics timeline with labeled LOW/MID/HIGH interventions for "
            "QP 27–38, comparing bitrate, reconstructed backlog, and smoothed P-frame QPY."),
        "04_unbounded_dynamics.png": (
            "The same labeled dynamics timeline without static QP bounds."),
        "05_stream_qp_stats.png": (
            "Per-scenario stream QPY range and area-weighted mean; dotted lines show the "
            "requested production bounds."),
        "06_ip_size_ratio.png": (
            "Median I-frame allocation relative to median P-frame size across scenarios."),
        "07_half_rate_30_to_15.png": (
            "Response during the explicit 16–20 s intervention where every other input "
            "slot is omitted while PTS remains on the configured timeline."),
        "08_rate_conditioned_qp.png": (
            "Median P-frame QPY versus achieved/requested bitrate for configured bounds "
            "27–38. Marker area shows the fraction pinned at the maximum QP."),
    }
    for name in image_names:
        title = Path(name).stem.split("_", 1)[-1].replace("_", " ").title()
        gallery += [
            f"### {title}", "", descriptions.get(name, ""), "",
            f"![{title}]({relative_output}/{name})", "",
        ]
    gallery.append(AGGREGATE_GALLERY_END)
    sections = [before.rstrip(), "\n".join(gallery), after.strip()]
    report.write_text(
        "\n\n".join(section for section in sections if section) + "\n",
        encoding="utf-8")
    try:
        from encoder_rc_probe_report import write_html_report
        write_html_report(report)
    except (ImportError, OSError, ValueError) as exc:
        print(f"warning: failed to update self-contained HTML report: {exc}")


def generate(result_root: Path, output: Path,
             smoothing_seconds: float = 1.0) -> None:
    manifest_path = result_root / "manifest.json"
    frames_path = result_root / "analysis" / "frames.csv"
    drops_path = result_root / "analysis" / "drops.csv"
    if (not manifest_path.is_file() or not frames_path.is_file()
            or not drops_path.is_file()):
        raise SystemExit(f"expected {manifest_path}, {frames_path}, and {drops_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = _read_frames(frames_path)
    by_case = _group_by_case(rows)
    cases = {str(case["id"]): case for case in manifest["cases"]}
    target_bps = float(manifest["global"]["bitrate_bps"])
    fps = int(manifest["global"]["fps"])
    windows = [row for row in rolling_window_records(by_case, target_bps)
               if row["qp_profile"] in FOCUS_PROFILES]
    recovery = [row for row in pulse_recovery_records(by_case, cases, fps)
                if row["qp_profile"] in FOCUS_PROFILES]
    qp_stats = [row for row in stream_qp_records(by_case)
                if row["qp_profile"] in FOCUS_PROFILES]
    rate_conditioned_qp = [
        row for row in rate_conditioned_qp_records(by_case, cases, target_bps)
        if row["qp_profile"] in FOCUS_PROFILES
    ]
    ip_stats = [row for row in ip_size_ratio_records(by_case)
                if row["qp_profile"] in FOCUS_PROFILES]
    half_rate = half_rate_records(drops_path)
    output.mkdir(parents=True, exist_ok=True)
    for stale_name in ("03_representative_dynamics.png", "04_stream_qp_stats.png"):
        (output / stale_name).unlink(missing_ok=True)
    _write_csv(output / "rolling_window_peaks.csv", windows)
    _write_csv(output / "pulse_recovery.csv", recovery)
    _write_csv(output / "stream_qp_stats.csv", qp_stats)
    _write_csv(output / "rate_conditioned_qp.csv", rate_conditioned_qp)
    _write_csv(output / "ip_size_ratios.csv", ip_stats)
    _write_csv(output / "half_rate_30_to_15.csv", half_rate)

    plt, PdfPages = _setup_matplotlib()
    figures = [
        ("01_peak_rolling_rate.png", _plot_rolling_windows(plt, windows)),
        ("02_post_pulse_recovery.png", _plot_pulse_recovery(plt, recovery)),
        ("03_production_27_38_dynamics.png",
         _plot_representative(
             plt, by_case, cases, fps, target_bps, "jcq_27_38",
             smoothing_seconds)),
        ("04_unbounded_dynamics.png",
         _plot_representative(
             plt, by_case, cases, fps, target_bps, "unbounded",
             smoothing_seconds)),
        ("05_stream_qp_stats.png", _plot_qp_summary(plt, qp_stats)),
        ("06_ip_size_ratio.png", _plot_ip_ratio(plt, ip_stats)),
        ("07_half_rate_30_to_15.png", _plot_half_rate(plt, half_rate)),
        ("08_rate_conditioned_qp.png",
         _plot_rate_conditioned_qp(plt, rate_conditioned_qp)),
    ]
    figures = [(name, figure) for name, figure in figures if figure is not None]
    with PdfPages(output / "encoder_rc_behavior_plots.pdf") as pdf:
        for name, figure in figures:
            figure.savefig(output / name, dpi=220)
            pdf.savefig(figure)
            plt.close(figure)

    window_summary = _profile_summary(windows)
    readme = f"""# CBR versus VBR aggregate plots

These plots compare only unbounded QP and production QP 27–38 for CBR and VBR.
Each rolling-window point is the median of three deterministic repeats. The
pulse plot shows the same four configurations. QPY values come from aligned
`qpextract` coded-unit data: low/high are stream extrema and mean is pixel-area
weighted over the stream. Trend plots use a {smoothing_seconds:g}-second trailing mean of P-frame QPY to
remove I-frame and deterministic source-phase noise; set the CLI smoothing
window to zero for raw P-frame QPY.

| Mode / QP | Peak 1 s | Peak 3 s | Peak 10 s |
|---|---:|---:|---:|
| CBR unbounded | {_format_summary_value(window_summary, "cbr", "unbounded", 1.0)} | {_format_summary_value(window_summary, "cbr", "unbounded", 3.0)} | {_format_summary_value(window_summary, "cbr", "unbounded", 10.0)} |
| CBR 27–38 | {_format_summary_value(window_summary, "cbr", "jcq_27_38", 1.0)} | {_format_summary_value(window_summary, "cbr", "jcq_27_38", 3.0)} | {_format_summary_value(window_summary, "cbr", "jcq_27_38", 10.0)} |
| VBR unbounded | {_format_summary_value(window_summary, "vbr", "unbounded", 1.0)} | {_format_summary_value(window_summary, "vbr", "unbounded", 3.0)} | {_format_summary_value(window_summary, "vbr", "unbounded", 10.0)} |
| VBR 27–38 | {_format_summary_value(window_summary, "vbr", "jcq_27_38", 1.0)} | {_format_summary_value(window_summary, "vbr", "jcq_27_38", 3.0)} | {_format_summary_value(window_summary, "vbr", "jcq_27_38", 10.0)} |

The figures support a longer VBR overshoot allowance, not an identified
three-second controller time constant. CBR visibly repays high-complexity
pulses; VBR generally does not repay them within the following LOW segment.

Artifacts:

- `encoder_rc_behavior_plots.pdf`: all eight figures in one document.
- `01_peak_rolling_rate.png`: unbounded and QP 27–38 peak rate versus window.
- `02_post_pulse_recovery.png`: four-way post-pulse output-rate recovery.
- `03_production_27_38_dynamics.png`: production-bound bitrate, backlog, and
  one-second trailing P-frame QPY trend plus stream low/mean/high statistics.
- `04_unbounded_dynamics.png`: matching unbounded-QP view.
- `05_stream_qp_stats.png`: unbounded versus QP 27–38 low/weighted-mean/high
  for all, I, and P frames in all three scenarios.
- `06_ip_size_ratio.png`: median I-frame size divided by median P-frame size.
- `07_half_rate_30_to_15.png`: configured-rate input-omission response.
- `08_rate_conditioned_qp.png`: median P-frame QPY versus achieved/requested
  bitrate for QP 27–38, with maximum-QP pinning encoded by marker area.
- `rolling_window_peaks.csv` and `pulse_recovery.csv`: rate source values.
- `stream_qp_stats.csv` and `rate_conditioned_qp.csv`: QPY source values,
  including achieved-rate ratio, median/variation, frame-to-frame change, and
  maximum-QP pinning.
- `ip_size_ratios.csv` and `half_rate_30_to_15.csv`: source values for the new
  I/P allocation and half-rate figures.
"""
    (output / "README.md").write_text(readme, encoding="utf-8")
    _update_report_gallery(result_root, output, [name for name, _figure in figures])
    print(output / "encoder_rc_behavior_plots.pdf")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--smoothing-seconds", type=float, default=1.0,
        help="trailing PTS-time QP smoothing window; 0 disables")
    args = parser.parse_args()
    if args.smoothing_seconds < 0:
        parser.error("--smoothing-seconds must be non-negative")
    result_root = args.result_root.resolve()
    output = (args.output.resolve() if args.output
              else result_root / "analysis" / "aggregate_plots")
    generate(result_root, output, args.smoothing_seconds)


if __name__ == "__main__":
    main()
