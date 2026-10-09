#!/usr/bin/env python3
"""Reproducible black-box video encoder rate-control characterization.

This tool builds, runs, and analyzes a deterministic Encapp matrix that probes:
  * CBR/VBR complexity-step response and effective integration time
  * accumulated overshoot / leaky-bucket delay
  * input-frame omission behavior
  * I/P frame-size ratio and first-GOP strategy
  * interaction with static QP bounds (JCQ-style operation)

The generated tests use Encapp's in-process ``fake_input_rc`` source. The source
is deterministic and avoids camera/display/ISP confounders. Each run gets its
own Encapp process by default, making frame-zero behavior reproducible. Before
execution the tool verifies that the installed Encapp advertises the required
probe contract.

Examples:
  python scripts/encoder_rc_probe.py generate /tmp/rc-probe \
      --codec c2.qti.hevc.encoder --mime video/hevc \
      --resolution 2304x1728 --bitrate 8M --repeats 3
  python scripts/encoder_rc_probe.py run /tmp/rc-probe/manifest.json \
      --serial SERIAL --encapp-python .venv/bin/python
  python scripts/encoder_rc_probe.py analyze /tmp/rc-probe/manifest.json
"""
from __future__ import annotations

import argparse
import bisect
from contextlib import contextmanager
import concurrent.futures
import csv
import dataclasses
import datetime as dt
import hashlib
import json
import math
import os
import fcntl
from pathlib import Path
import random
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

SCHEMA_VERSION = 1
DEFAULT_CODEC = "c2.qti.hevc.encoder"
DEFAULT_MIME = "video/hevc"
DEFAULT_RESOLUTION = "2304x1728"
DEFAULT_FPS = 30
DEFAULT_BITRATE = "8M"
DEFAULT_BITRATE_BPS = 8_000_000
DEFAULT_REPEATS = 1
ENCAPP_PACKAGE = "com.facebook.encapp"
ENCAPP_PROBE_CONTRACT_KEY = f"{ENCAPP_PACKAGE}.encoder_rc_probe_contract"
ENCAPP_PROBE_ACTION = f"{ENCAPP_PACKAGE}.action.QUERY_PROBE_CAPABILITIES"
ENCAPP_PROBE_RECEIVER = f"{ENCAPP_PACKAGE}/.ProbeCapabilitiesReceiver"
ENCAPP_PROBE_CONTRACT = 3
ENCAPP_PRIVATE_WORKDIR = f"/data/user/0/{ENCAPP_PACKAGE}/files"

QP_PROFILES: Dict[str, Dict[str, int]] = {
    "unbounded": {},
    "min27": {"min": 27},
    "max38": {"max": 38},
    "jcq_27_38": {"min": 27, "max": 38},
    "tight_30_35": {"min": 30, "max": 35},
}

COMPLEXITY_RANK = {"LOW": 0, "MID": 1, "HIGH": 2}
SCENARIO_ORDER = {"dynamics": 0, "hard_start": 1, "drops": 2}
PROFILE_ORDER = {
    "unbounded": 0, "min27": 1, "max38": 2,
    "jcq_27_38": 3, "tight_30_35": 4,
}
MODE_ORDER = {"cbr": 0, "vbr": 1}


def _case_sort_key(row: Dict[str, Any]) -> Tuple[Any, ...]:
    """Semantic publication order, independent of randomized execution order."""
    return (
        SCENARIO_ORDER.get(str(row.get("scenario", "")), 999),
        PROFILE_ORDER.get(str(row.get("qp_profile", "")), 999),
        MODE_ORDER.get(str(row.get("mode", "")), 999),
        int(row.get("repeat", 0)),
        str(row.get("case_id", row.get("id", ""))),
    )


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def _parse_bitrate(value: str) -> int:
    s = value.strip().lower().replace("bps", "").replace(" ", "")
    m = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)([kmg]?)", s)
    if not m:
        raise ValueError(f"invalid bitrate: {value!r}")
    number = float(m.group(1))
    multiplier = {"": 1, "k": 1_000, "m": 1_000_000, "g": 1_000_000_000}[m.group(2)]
    return int(round(number * multiplier))


def _csv_names(value: str, allowed: Iterable[str], what: str) -> List[str]:
    known = set(allowed)
    out = [x.strip() for x in value.split(",") if x.strip()]
    bad = [x for x in out if x not in known]
    if bad:
        raise ValueError(f"unknown {what}: {', '.join(bad)}; allowed: {', '.join(sorted(known))}")
    if not out:
        raise ValueError(f"at least one {what} is required")
    return out


def _scenario_definitions(fps: int) -> Dict[str, Dict[str, Any]]:
    f = int(fps)
    # Dynamics: long plateaus plus 1/2/4-second impulses.  The final HIGH
    # segment provides a second occurrence of a startup complexity for
    # frame-zero-vs-steady-state comparisons.
    phase = 11
    dynamics_events = [
        (0, "LOW"),
        (5 * f + phase, "MID"),
        (10 * f + phase, "HIGH"),
        (20 * f + phase, "LOW"),
        (30 * f + phase, "HIGH"),
        (31 * f + phase, "LOW"),
        (33 * f + phase, "HIGH"),
        (35 * f + phase, "LOW"),
        (39 * f + phase, "HIGH"),
        (43 * f + phase, "LOW"),
        (51 * f + phase, "HIGH"),
    ]
    hard_start_events = [
        (0, "HIGH"),
        (10 * f + phase, "LOW"),
        (20 * f + phase, "HIGH"),
    ]

    # Omit isolated and burst inputs, then a deterministic half-rate interval
    # and a deterministic 10% pseudo-random interval.  PTS remains on the
    # configured 30-fps timeline, so the encoder sees real elapsed-time gaps.
    drops: List[int] = []
    drops += [3 * f + 7]
    drops += list(range(6 * f + 7, 6 * f + 9))
    drops += list(range(9 * f + 7, 9 * f + 11))
    drops += list(range(12 * f + 7, 12 * f + 15))
    drops += [n for n in range(16 * f, 20 * f) if (n - 16 * f) % 2 == 1]
    rng = random.Random(0x52435052)
    drops += sorted(rng.sample(list(range(22 * f, 28 * f)), int(0.10 * 6 * f)))

    return {
        "dynamics": {
            "description": "LOW/MID/HIGH plateaus and 1/2/4 s complexity impulses",
            "frames": 60 * f,
            "events": dynamics_events,
            "drops": [],
            "request_sync": [],
        },
        "hard_start": {
            "description": "hard frame-zero state, easy middle, repeated hard steady state",
            "frames": 30 * f,
            "events": hard_start_events,
            "drops": [],
            "request_sync": [],
        },
        "drops": {
            "description": "steady hard content with isolated, burst, half-rate, and random input omissions",
            "frames": 30 * f,
            "events": [(0, "HIGH")],
            "drops": sorted(set(drops)),
            "request_sync": [],
            "drop_regions": [
                {"name": "single", "start": 3 * f, "end": 4 * f},
                {"name": "burst2", "start": 6 * f, "end": 7 * f},
                {"name": "burst4", "start": 9 * f, "end": 10 * f},
                {"name": "burst8", "start": 12 * f, "end": 13 * f},
                {"name": "half_rate", "start": 16 * f, "end": 20 * f},
                {"name": "random10", "start": 22 * f, "end": 28 * f},
            ],
        },
    }


def _qp_parameter_lines(profile: Dict[str, int]) -> List[str]:
    lines: List[str] = []
    for frame_type in ("i", "p"):
        for bound in ("min", "max"):
            if bound not in profile:
                continue
            lines.extend([
                "    parameter {",
                f'      key: "video-qp-{frame_type}-{bound}"',
                "      type: intType",
                f'      value: "{profile[bound]}"',
                "    }",
            ])
    return lines


def _render_pbtxt(case: Dict[str, Any], global_cfg: Dict[str, Any]) -> str:
    qp_lines = _qp_parameter_lines(case["qp_bounds"])
    event_lines: List[str] = []
    for event in case["scenario_def"]["events"]:
        event_lines.extend([
            "    fake_input_complexity {",
            f"      framenum: {event[0]}",
            f'      complexity: "{event[1]}"',
            "    }",
        ])
    for frame in case["scenario_def"].get("drops", []):
        event_lines.append(f"    drop: {frame}")
    for frame in case["scenario_def"].get("request_sync", []):
        event_lines.append(f"    request_sync: {frame}")

    lines = [
        "# Generated by scripts/encoder_rc_probe.py; do not edit by hand.",
        "test {",
        "  test_setup {",
        f"    timeout_sec: {max(120, math.ceil(case['scenario_def']['frames'] / global_cfg['fps']) + 90)}",
        "    ignore_power_status: true",
        "  }",
        "  common {",
        f'    id: "{case["id"]}"',
        f'    output_filename: "{case["id"]}"',
        f'    description: "encoder RC probe {case["scenario"]} {case["mode"]} {case["qp_profile"]}"',
        "  }",
        "  input {",
        '    filepath: "fake_input_rc"',
        f'    resolution: "{global_cfg["resolution"]}"',
        f"    framerate: {global_cfg['fps']}",
        "    pix_fmt: nv12",
        f"    playout_frames: {case['scenario_def']['frames']}",
        "  }",
        "  configure {",
        f'    codec: "{global_cfg["codec"]}"',
        f'    mime: "{global_cfg["mime"]}"',
        f'    bitrate: "{global_cfg["bitrate"]}"',
        f"    framerate: {global_cfg['fps']}",
        "    color_format: 21",
        f"    bitrate_mode: {case['mode']}",
        f"    i_frame_interval: {global_cfg['i_frame_interval']}",
        "    nal_log: true",
        "    parameter {",
        '      key: "max-bframes"',
        "      type: intType",
        '      value: "0"',
        "    }",
        *qp_lines,
        "  }",
        "  runtime {",
        *event_lines,
        "  }",
        "}",
        "",
    ]
    return "\n".join(lines)


def generate(args: argparse.Namespace) -> Path:
    output_arg = os.fspath(args.output)
    if not output_arg.strip():
        raise ValueError(
            "output folder is empty or whitespace-only; this usually means a "
            "shell line-continuation backslash had trailing spaces")
    if output_arg != output_arg.strip():
        raise ValueError(
            f"output folder has leading or trailing whitespace: {output_arg!r}; "
            "remove spaces after shell line-continuation backslashes")
    root = Path(output_arg).resolve()
    configs = root / "configs"
    configs.mkdir(parents=True, exist_ok=True)
    modes = _csv_names(args.modes, ("cbr", "vbr"), "mode")
    qp_names = _csv_names(args.qp_profiles, QP_PROFILES, "QP profile")
    scenarios_all = _scenario_definitions(args.fps)
    scenario_names = _csv_names(args.scenarios, scenarios_all, "scenario")
    if not re.fullmatch(r"\d+(?:k|M)?", args.bitrate.strip()):
        raise ValueError(
            "bitrate must use Encapp's integer syntax: raw bps, lowercase k, or uppercase M")
    bitrate_bps = _parse_bitrate(args.bitrate)

    global_cfg = {
        "codec": args.codec,
        "mime": args.mime,
        "resolution": args.resolution,
        "fps": args.fps,
        "bitrate": args.bitrate,
        "bitrate_bps": bitrate_bps,
        "i_frame_interval": args.i_frame_interval,
        "repeats": args.repeats,
    }

    cases: List[Dict[str, Any]] = []
    for repeat in range(args.repeats):
        block: List[Dict[str, Any]] = []
        for scenario_name in scenario_names:
            for mode in modes:
                for qp_name in qp_names:
                    case_id = f"rc_{scenario_name}_{mode}_{qp_name}_r{repeat + 1:02d}"
                    case = {
                        "id": case_id,
                        "repeat": repeat + 1,
                        "mode": mode,
                        "qp_profile": qp_name,
                        "qp_bounds": dict(QP_PROFILES[qp_name]),
                        "scenario": scenario_name,
                        "scenario_def": scenarios_all[scenario_name],
                        "config": f"configs/{case_id}.pbtxt",
                    }
                    block.append(case)
        random.Random(args.order_seed + repeat).shuffle(block)
        cases.extend(block)

    manifest: Dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "created_utc": _utc_now(),
        "generator": Path(__file__).name,
        "generator_sha256": _sha256(Path(__file__)),
        "order_seed": args.order_seed,
        "global": global_cfg,
        "input_contract": {
            "source": "Encapp fake_input",
            "pixel_format": "nv12",
            "deterministic": True,
            "submission_policy": "as_fast_as_codec_accepts",
            "complexity_ranks": COMPLEXITY_RANK,
            "note": "Same generated bytes for every codec and every run at a given resolution/profile.",
        },
        "cases": cases,
    }

    for case in cases:
        pbtxt_path = root / case["config"]
        pbtxt_path.write_text(_render_pbtxt(case, global_cfg), encoding="utf-8")
        case["config_sha256"] = _sha256(pbtxt_path)

    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"generated {len(cases)} cases: {manifest_path}")
    return manifest_path


def _run_checked(cmd: Sequence[str], *, cwd: Optional[Path] = None,
                 timeout: Optional[int] = None, capture: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        list(cmd), cwd=str(cwd) if cwd else None, timeout=timeout, check=True,
        text=True, capture_output=capture,
    )


def _resolve_serial(requested: Optional[str]) -> str:
    serial = (requested or os.environ.get("ANDROID_SERIAL", "")).strip()
    if serial:
        return serial
    completed = _run_checked(["adb", "devices"], timeout=30, capture=True)
    devices = []
    for line in completed.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    if len(devices) == 1:
        return devices[0]
    if not devices:
        raise RuntimeError(
            "no authorized ADB device found; connect one or pass --serial")
    raise RuntimeError(
        f"multiple authorized ADB devices found ({', '.join(devices)}); pass --serial")


def _adb(serial: str, *args: str, timeout: int = 30, check: bool = True) -> subprocess.CompletedProcess:
    last: Optional[subprocess.CompletedProcess] = None
    transport_errors = (
        "device offline", "no devices", "device not found", "closed",
        "protocol fault", "cannot connect", "transport error")
    for attempt in range(3):
        last = subprocess.run(
            ["adb", "-s", serial, *args], text=True, capture_output=True,
            timeout=timeout, check=False,
        )
        if last.returncode == 0:
            return last
        transient = any(token in (last.stderr or "").lower()
                        for token in transport_errors)
        if attempt < 2 and (check or transient):
            time.sleep(2)
            continue
        break
    assert last is not None
    if check:
        last.check_returncode()
    return last


def _parse_encapp_package_dump(text: str) -> Dict[str, Any]:
    version_name_match = re.search(r"^\s*versionName=([^\s]+)", text, re.MULTILINE)
    version_code_match = re.search(r"^\s*versionCode=(\d+)", text, re.MULTILINE)
    return {
        "package": ENCAPP_PACKAGE,
        "version_name": (version_name_match.group(1)
                         if version_name_match else None),
        "version_code": (int(version_code_match.group(1))
                         if version_code_match else None),
    }


def _parse_encapp_capability_broadcast(text: str) -> Optional[int]:
    match = re.search(
        re.escape(ENCAPP_PROBE_CONTRACT_KEY) + r"\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else None


def _require_compatible_encapp(serial: str) -> Dict[str, Any]:
    package_path = _adb(
        serial, "shell", "pm", "path", ENCAPP_PACKAGE,
        timeout=30, check=False)
    if package_path.returncode != 0 or "package:" not in package_path.stdout:
        raise RuntimeError(
            f"Encapp is not installed on {serial!r}. Build and install Encapp "
            "from source before running the probe.")
    package_dump = _adb(
        serial, "shell", "dumpsys", "package", ENCAPP_PACKAGE,
        timeout=60, check=False)
    if package_dump.returncode != 0:
        raise RuntimeError(
            f"unable to inspect installed Encapp: {package_dump.stderr.strip()}")
    info = _parse_encapp_package_dump(package_dump.stdout)
    info["package_path"] = package_path.stdout.strip().splitlines()[0]
    capability = _adb(
        serial, "shell", "am", "broadcast", "--receiver-foreground",
        "-a", ENCAPP_PROBE_ACTION, "-n", ENCAPP_PROBE_RECEIVER,
        timeout=30, check=False)
    capability_text = "\n".join(
        value for value in (capability.stdout, capability.stderr) if value)
    observed = _parse_encapp_capability_broadcast(capability_text)
    info["probe_contract"] = observed
    if capability.returncode != 0 or observed != ENCAPP_PROBE_CONTRACT:
        raise RuntimeError(
            "installed Encapp is incompatible with this rate-control probe: "
            f"required {ENCAPP_PROBE_CONTRACT_KEY}={ENCAPP_PROBE_CONTRACT}, "
            f"observed {observed!r}. Update Encapp from source and reinstall it.")
    return info


def check_encapp(args: argparse.Namespace) -> None:
    serial = _resolve_serial(args.serial)
    info = _require_compatible_encapp(serial)
    info["probe_workdir"] = _require_probe_workdir(serial)
    print(json.dumps(info, indent=2, sort_keys=True))


def _require_probe_workdir(serial: str) -> str:
    """Verify app-private storage through the installed app's own uid."""
    relative = "files/.encoder_rc_probe_write_test"
    mkdir = _adb(
        serial, "shell", "run-as", ENCAPP_PACKAGE, "mkdir", "-p", "files",
        timeout=30, check=False)
    if mkdir.returncode != 0:
        raise RuntimeError(
            "installed Encapp does not expose a writable app-private workdir "
            f"through run-as: {mkdir.stderr.strip()}. Install the checkout's "
            "debug APK or grant Encapp All files access on the device.")
    write = _adb(
        serial, "shell", "run-as", ENCAPP_PACKAGE, "touch", relative,
        timeout=30, check=False)
    _adb(
        serial, "shell", "run-as", ENCAPP_PACKAGE, "rm", "-f", relative,
        timeout=30, check=False)
    if write.returncode != 0:
        raise RuntimeError(
            "installed Encapp cannot write its app-private probe workdir: "
            f"{write.stderr.strip()}")
    return ENCAPP_PRIVATE_WORKDIR


def _encapp_failure_details(case_dir: Path, returncode: int,
                            result_path: Optional[Path], stderr: str,
                            result_complete: bool) -> Optional[Dict[str, Any]]:
    """Return a concise, persisted classification for a failed case."""
    if returncode == 0 and result_complete:
        return None
    details: Dict[str, Any] = {
        "kind": "artifact_incomplete" if returncode == 0 else "encapp_cli_failure",
        "reason": "required result artifacts are incomplete",
        "returncode": returncode,
    }
    summary_path = case_dir / "run_summary.json"
    try:
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        tests = list(summary.get("tests", []))
    except (OSError, ValueError, TypeError):
        tests = []
    if tests:
        session_status = str(tests[0].get("status", "unknown"))
        reason = str(tests[0].get("reason") or f"session status {session_status}")
        details.update({"session_status": session_status, "reason": reason})
        details["kind"] = {
            "never_started": "setup_failure",
            "crash": "app_crash",
            "timeout": "app_timeout",
            "fail": "test_failure",
            "skipped": "test_skipped",
        }.get(session_status, details["kind"])
    elif stderr.strip():
        details["reason"] = stderr.strip().splitlines()[-1]
    diagnostics = [
        path for path in (
            case_dir / "run_summary.json",
            case_dir / "encapp_cli.log",
            max(case_dir.glob("*.android_logcat.txt"),
                key=lambda item: item.stat().st_mtime, default=None),
        ) if path is not None and path.is_file()
    ]
    details["diagnostics"] = [str(path) for path in diagnostics]
    details["pre_result"] = result_path is None
    return details


def _parse_thermalservice(text: str) -> Dict[str, Any]:
    status_match = re.search(r"Thermal Status:\s*(\d+)", text)
    current = text.split("Current temperatures from HAL:", 1)[-1]
    current = current.split("Current cooling devices from HAL:", 1)[0]
    temperatures: Dict[str, float] = {}
    sensor_statuses: Dict[str, int] = {}
    sensor_types: Dict[str, int] = {}
    for value, sensor_type, sensor, status in re.findall(
            r"Temperature\{mValue=([-0-9.]+), mType=(\d+), mName=([^,]+), mStatus=(\d+)\}",
            current):
        temp = float(value)
        # Android's Thermal HAL specifies degrees Celsius, but some devices
        # expose milli-degrees through dumpsys (for example 35416 for 35.416 C).
        # Normalize only clearly scaled values so ordinary Celsius readings and
        # small non-temperature sentinels retain their original meaning.
        if abs(temp) >= 1000.0:
            temp /= 1000.0
        if 0.0 < temp < 150.0:
            temperatures[sensor] = temp
            sensor_statuses[sensor] = int(status)
            sensor_types[sensor] = int(sensor_type)
    soc_prefixes = ("cpu", "gpu", "ddr", "cpul3", "nsp", "aoss", "soc", "ap")
    soc_names = [name for name in temperatures
                 if sensor_types.get(name) in {0, 1, 9}
                 or name.lower().startswith(soc_prefixes)]
    # Some devices expose no CPU/GPU/NPU temperature through ThermalService.
    # In that case, use a real temperature-class proxy (battery, skin, USB, or
    # power amplifier), never BCL/comparator/percentage pseudo-temperatures.
    proxy_names = [name for name in temperatures
                   if sensor_types.get(name) in {2, 3, 4, 5}]
    if soc_names:
        thermal_names = soc_names
        thermal_basis = "soc"
    elif proxy_names:
        thermal_names = proxy_names
        thermal_basis = "proxy"
    else:
        thermal_names = []
        thermal_basis = None
    return {
        "status": int(status_match.group(1)) if status_match else None,
        "temperatures_c": temperatures,
        "sensor_types": sensor_types,
        "sensor_statuses": sensor_statuses,
        "max_sensor_status": max(sensor_statuses.values(), default=None),
        "max_soc_c": max((temperatures[name] for name in soc_names), default=None),
        "max_thermal_c": max(
            (temperatures[name] for name in thermal_names), default=None),
        "thermal_sensor_basis": thermal_basis,
        "thermal_sensor_names": thermal_names,
    }


def _thermal_max_c(thermal: Dict[str, Any]) -> Optional[float]:
    value = thermal.get("max_thermal_c")
    if value is None:
        value = thermal.get("max_soc_c")
    return float(value) if value is not None else None


def _wait_for_thermal(serial: str, max_thermal_c: float, timeout_s: int,
                      ignore_thermal: bool = False) -> Dict[str, Any]:
    started = time.time()
    while True:
        raw = _adb(serial, "shell", "dumpsys", "thermalservice", check=False).stdout
        parsed = _parse_thermalservice(raw)
        if ignore_thermal:
            print(
                "WARNING: thermal gate bypassed by --ignore-thermal; "
                f"status={parsed.get('status')} "
                f"sensor_status={parsed.get('max_sensor_status')} "
                f"max_thermal_c={parsed.get('max_thermal_c')} "
                f"basis={parsed.get('thermal_sensor_basis')}",
                file=sys.stderr, flush=True)
            return parsed
        if (parsed.get("status") == 0
                and parsed.get("max_sensor_status") == 0
                and parsed.get("max_thermal_c") is not None
                and parsed["max_thermal_c"] <= max_thermal_c):
            return parsed
        if (parsed.get("status") == 0
                and parsed.get("max_sensor_status") == 0
                and parsed.get("max_thermal_c") is None):
            raise RuntimeError(
                "thermal gate has no usable SoC or proxy temperature; "
                "rerun with --ignore-thermal to bypass the pre-case gate")
        elapsed = time.time() - started
        print(
            f"thermal gate waiting ({elapsed:.0f}s/{timeout_s}s): "
            f"status={parsed.get('status')} "
            f"sensor_status={parsed.get('max_sensor_status')} "
            f"max_thermal_c={parsed.get('max_thermal_c')} "
            f"basis={parsed.get('thermal_sensor_basis')} "
            f"threshold={max_thermal_c}", flush=True)
        if elapsed > timeout_s:
            raise RuntimeError(
                f"thermal gate timed out: status={parsed.get('status')} "
                f"sensor_status={parsed.get('max_sensor_status')} "
                f"max_thermal_c={parsed.get('max_thermal_c')} "
                f"basis={parsed.get('thermal_sensor_basis')} "
                f"threshold={max_thermal_c}; use --ignore-thermal to bypass")
        time.sleep(10)


def _sample_thermal_until(serial: str, stop: threading.Event,
                          samples: List[Dict[str, Any]], interval_s: float = 5.0) -> None:
    while not stop.is_set():
        try:
            raw = _adb(serial, "shell", "dumpsys", "thermalservice",
                       timeout=30, check=False).stdout
            samples.append({"captured_utc": _utc_now(), **_parse_thermalservice(raw)})
        except Exception as exc:
            samples.append({"captured_utc": _utc_now(), "error": str(exc)})
        stop.wait(interval_s)


def _device_provenance(serial: str) -> Dict[str, Any]:
    props = _adb(serial, "shell", "getprop", timeout=30).stdout
    selected = {}
    wanted = {
        "ro.product.model", "ro.product.device", "ro.build.fingerprint",
        "ro.build.version.incremental", "ro.soc.model", "ro.boot.hardware",
    }
    for line in props.splitlines():
        m = re.fullmatch(r"\[([^]]+)\]: \[(.*)\]", line)
        if m and m.group(1) in wanted:
            selected[m.group(1)] = m.group(2)
    thermal_raw = _adb(serial, "shell", "dumpsys", "thermalservice", check=False).stdout
    return {
        "serial": serial,
        "captured_utc": _utc_now(),
        "properties": selected,
        "battery": _adb(serial, "shell", "dumpsys", "battery", check=False).stdout,
        "thermal": _parse_thermalservice(thermal_raw),
        "thermalservice": thermal_raw,
    }


def _find_case_result(case_dir: Path, case_id: str) -> Optional[Path]:
    candidates = list(case_dir.glob("**/*.json"))
    exact = [p for p in candidates if p.name == f"{case_id}.json"]
    if exact:
        return max(exact, key=lambda p: p.stat().st_mtime)
    for path in sorted(candidates, key=lambda p: p.stat().st_mtime, reverse=True):
        if path.name.endswith(".nal.json") or path.name in {"status.json", "device_pre.json", "device_post.json"}:
            continue
        try:
            body = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(body, dict):
            continue
        if body.get("id") == case_id:
            return path
    return None


def _result_is_complete(path: Path, case: Dict[str, Any]) -> bool:
    try:
        body = json.loads(path.read_text(encoding="utf-8"))
        frames = [f for f in body.get("frames", []) if int(f.get("size", 0)) > 0]
        originals = [int(f.get("original_frame", f.get("frame", -1))) for f in frames]
        output_indices = [int(f.get("frame", -1)) for f in frames]
        pts = [int(f["pts"]) for f in frames]
    except Exception:
        return False
    expected = sorted(
        set(range(int(case["scenario_def"]["frames"])))
        - set(int(x) for x in case["scenario_def"].get("drops", [])))
    return (len(frames) == len(expected)
            and originals == expected
            and output_indices == list(range(len(frames)))
            and all(b > a for a, b in zip(pts, pts[1:])))


def _device_workdir_from_case(case_dir: Path) -> Optional[str]:
    manifests = sorted(
        case_dir.glob("*.session.jsonl"),
        key=lambda path: path.stat().st_mtime,
        reverse=True)
    for manifest in manifests:
        try:
            for line in manifest.read_text(encoding="utf-8").splitlines():
                event = json.loads(line)
                if event.get("event") == "session_start" and event.get("device_workdir"):
                    return str(event["device_workdir"]).rstrip("/")
        except (OSError, ValueError, TypeError):
            continue
    return None


def _private_workdir_relative(device_workdir: str, name: str) -> Optional[str]:
    for prefix in (
            f"/data/user/0/{ENCAPP_PACKAGE}",
            f"/data/data/{ENCAPP_PACKAGE}"):
        if device_workdir == prefix:
            return name
        if device_workdir.startswith(prefix + "/"):
            return f"{device_workdir[len(prefix) + 1:]}/{name}"
    return None


def _pull_workdir_file(serial: str, device_workdir: str, name: str,
                        local: Path, timeout_s: int) -> subprocess.CompletedProcess:
    private_relative = _private_workdir_relative(device_workdir, name)
    if private_relative is None:
        return _adb(
            serial, "pull", f"{device_workdir}/{name}", str(local),
            timeout=timeout_s, check=False)

    temporary = local.with_name(local.name + ".part")
    try:
        with temporary.open("wb") as output:
            completed = subprocess.run([
                "adb", "-s", serial, "exec-out", "run-as", ENCAPP_PACKAGE,
                "cat", private_relative,
            ], stdout=output, stderr=subprocess.PIPE, timeout=timeout_s,
               check=False)
        if completed.returncode == 0:
            temporary.replace(local)
        else:
            temporary.unlink(missing_ok=True)
        return subprocess.CompletedProcess(
            completed.args, completed.returncode, "",
            completed.stderr.decode("utf-8", errors="replace"))
    except OSError:
        temporary.unlink(missing_ok=True)
        raise


def _stat_workdir_file(serial: str, device_workdir: str,
                        name: str) -> subprocess.CompletedProcess:
    private_relative = _private_workdir_relative(device_workdir, name)
    if private_relative is not None:
        return _adb(
            serial, "shell", "run-as", ENCAPP_PACKAGE,
            "stat", "-c", "%s", private_relative, timeout=30, check=False)
    return _adb(
        serial, "shell", "stat", "-c", "%s", f"{device_workdir}/{name}",
        timeout=30, check=False)


def _remove_workdir_files(serial: str, device_workdir: str,
                           names: Sequence[str]) -> None:
    if not names:
        return
    private_names = [
        _private_workdir_relative(device_workdir, name) for name in names]
    if all(name is not None for name in private_names):
        _adb(serial, "shell", "run-as", ENCAPP_PACKAGE, "rm", "-f",
             *[str(name) for name in private_names], check=False)
    else:
        _adb(serial, "shell", "rm", "-f",
             *[f"{device_workdir}/{name}" for name in names], check=False)


def _pull_nal_sidecar(serial: str, case: Dict[str, Any], case_dir: Path,
                      timeout_s: int = 30) -> Path:
    name = f"{case['id']}.nal.json"
    local = case_dir / name
    expected = (int(case["scenario_def"]["frames"])
                - len(set(int(x) for x in case["scenario_def"].get("drops", []))))
    device_workdir = _device_workdir_from_case(case_dir)
    if device_workdir is None and not local.is_file():
        raise RuntimeError(
            f"failed to collect {name}: Encapp device workdir is unavailable")
    deadline = time.time() + timeout_s
    last_error = "sidecar did not appear"
    while time.time() < deadline:
        if not local.is_file() or local.stat().st_size == 0:
            assert device_workdir is not None
            pulled = _pull_workdir_file(
                serial, device_workdir, name, local, timeout_s=60)
            if pulled.returncode != 0:
                last_error = pulled.stderr.strip() or "sidecar pull failed"
                time.sleep(1)
                continue
        try:
            body = json.loads(local.read_text(encoding="utf-8"))
            frames = list(body.get("frames", []))
            if len(frames) == expected and all(
                    int(frame.get("frame_index", -1)) == idx
                    for idx, frame in enumerate(frames)):
                return local
            last_error = f"sidecar has {len(frames)}/{expected} frames"
        except Exception as exc:
            last_error = f"sidecar is not complete JSON: {exc}"
        local.unlink(missing_ok=True)
        time.sleep(1)
    raise RuntimeError(f"failed to collect {name}: {last_error}")


def _refresh_encoded_file(serial: str, result_path: Path,
                          result: Dict[str, Any]) -> None:
    encoded = _find_encoded(result_path, result)
    if encoded is None:
        raise RuntimeError("encoded media is missing from collected artifacts")
    device_workdir = _device_workdir_from_case(result_path.parent)
    if device_workdir is None:
        raise RuntimeError("Encapp device workdir is unavailable")
    stat = _stat_workdir_file(serial, device_workdir, encoded.name)
    if stat.returncode != 0:
        raise RuntimeError(
            f"completed encoded file is missing in {device_workdir}: "
            f"{encoded.name}: {stat.stderr.strip()}")
    try:
        remote_size = int(stat.stdout.strip())
    except ValueError as exc:
        raise RuntimeError(
            f"invalid remote size for completed encoded file {encoded.name}: "
            f"{stat.stdout!r}") from exc
    if encoded.stat().st_size != remote_size:
        pulled = _pull_workdir_file(
            serial, device_workdir, encoded.name, encoded, timeout_s=120)
        if pulled.returncode != 0 or encoded.stat().st_size != remote_size:
            raise RuntimeError(
                f"failed to refresh completed encoded file {encoded.name}: "
                f"{pulled.stderr.strip()}")


def _verify_no_b_frames(encoded: Path, case: Dict[str, Any],
                        case_dir: Path) -> Dict[str, int]:
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        raise RuntimeError("ffprobe is required to verify that B-frames are disabled")
    completed = subprocess.run([
        ffprobe, "-v", "error", "-select_streams", "v:0",
        "-show_entries", "frame=pict_type", "-of", "json", str(encoded),
    ], text=True, capture_output=True, timeout=180, check=True)
    body = json.loads(completed.stdout)
    types = [str(frame.get("pict_type", "?")) for frame in body.get("frames", [])]
    counts = {name: types.count(name) for name in sorted(set(types))}
    expected = (int(case["scenario_def"]["frames"])
                - len(set(int(x) for x in case["scenario_def"].get("drops", []))))
    evidence = {"expected_frame_count": expected,
                "observed_frame_count": len(types), "type_counts": counts}
    (case_dir / "frame_types.json").write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if len(types) != expected:
        raise RuntimeError(
            f"ffprobe frame count mismatch expected={expected} observed={len(types)}")
    if any(frame_type not in {"I", "P"} for frame_type in types):
        raise RuntimeError(f"unexpected coded frame types: {counts}")
    return counts


_LEGACY_NON_ARTIFACT_PREFIXES = (
    "pre thermal ", "post thermal ", "pre max SoC ", "post max SoC ",
    "pre sensor thermal ", "post sensor thermal ",
    "no in-run thermal samples", "thermal sample error=",
    "in-run thermal status=", "in-run max SoC temperature=",
    "feed pacing unavailable", "feed wall/PTS ratio=",
    "per-frame feed gap evidence unavailable", "max feed gap excess=",
)


def _status_artifact_error(status: Dict[str, Any]) -> Optional[str]:
    """Return only true collection/verification errors.

    Older runner versions appended thermal and wall-clock pacing validity
    messages to artifact_error and consequently set result_complete=false.
    Reclassify those messages so existing encoded matrices remain analyzable.
    """
    raw = status.get("artifact_error")
    if not raw:
        return None
    parts = [part.strip() for part in str(raw).split("; ") if part.strip()]
    artifact_parts = [
        part for part in parts
        if not part.startswith(_LEGACY_NON_ARTIFACT_PREFIXES)]
    return "; ".join(artifact_parts) or None


def _resolve_manifest_path(value: str) -> Path:
    path = Path(value).expanduser().resolve()
    if path.is_dir():
        path = path / "manifest.json"
    if not path.is_file():
        raise RuntimeError(
            f"probe manifest not found: {path} "
            "(pass either the result folder or its manifest.json)")
    return path


@contextmanager
def _device_run_lock(serial: str):
    """Prevent concurrent probe matrices from replacing one another's activity."""
    safe_serial = re.sub(r"[^A-Za-z0-9_.-]", "_", serial)
    path = Path(tempfile.gettempdir()) / f"encoder_rc_probe.{safe_serial}.lock"
    handle = path.open("a+", encoding="utf-8")
    try:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            handle.seek(0)
            owner = handle.read().strip()
            detail = f"; owner: {owner}" if owner else ""
            raise RuntimeError(
                f"another encoder RC probe is already using adb device {serial}"
                f"{detail}. Stop that run or wait for it to finish.") from exc
        handle.seek(0)
        handle.truncate()
        json.dump({
            "pid": os.getpid(),
            "serial": serial,
            "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "command": sys.argv,
        }, handle, sort_keys=True)
        handle.write("\n")
        handle.flush()
        yield
    finally:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()


def run_matrix(args: argparse.Namespace) -> None:
    serial = _resolve_serial(args.serial)
    args.serial = serial
    with _device_run_lock(serial):
        _run_matrix_locked(args)


def _run_matrix_locked(args: argparse.Namespace) -> None:
    manifest_path = _resolve_manifest_path(args.manifest)
    root = manifest_path.parent
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise RuntimeError("unsupported manifest schema")
    serial = _resolve_serial(args.serial)
    state = _adb(serial, "get-state", timeout=10).stdout.strip()
    if state != "device":
        raise RuntimeError(f"adb device {serial!r} is not ready: {state!r}")
    encapp_info = _require_compatible_encapp(serial)
    probe_workdir = _require_probe_workdir(serial)

    run_root = root / "raw"
    run_root.mkdir(exist_ok=True)
    device = _device_provenance(serial)
    device["encapp"] = encapp_info
    device["probe_workdir"] = probe_workdir
    (root / "device.json").write_text(
        json.dumps(device, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    encapp_script = Path(args.encapp_script).resolve()
    encapp_python = Path(args.encapp_python).resolve()
    if not encapp_script.is_file():
        raise RuntimeError(f"encapp script not found: {encapp_script}")
    if not encapp_python.is_file():
        raise RuntimeError(f"python interpreter not found: {encapp_python}")

    selected = [c for c in manifest["cases"] if re.search(args.case_regex, c["id"])]
    if args.limit:
        selected = selected[: args.limit]
    print(f"running {len(selected)} cases on {serial}")
    failures: List[str] = []
    consecutive_pre_result_signature: Optional[Tuple[str, str]] = None
    consecutive_pre_result_failures = 0

    for ordinal, case in enumerate(selected, 1):
        config_path = root / case["config"]
        if (not config_path.is_file()
                or _sha256(config_path) != case.get("config_sha256")):
            raise RuntimeError(f"config hash mismatch: {config_path}")
        case_dir = run_root / case["id"]
        case_dir.mkdir(parents=True, exist_ok=True)
        status_path = case_dir / "status.json"
        if args.resume and status_path.exists():
            try:
                status = json.loads(status_path.read_text(encoding="utf-8"))
                result_path = _find_case_result(case_dir, case["id"])
                if (status.get("returncode") == 0
                        and status.get("config_sha256") == case.get("config_sha256")
                        and _status_artifact_error(status) is None
                        and result_path and _result_is_complete(result_path, case)
                        and (case_dir / f"{case['id']}.nal.json").is_file()
                        and (case_dir / "frame_types.json").is_file()):
                    print(f"[{ordinal}/{len(selected)}] skip complete {case['id']}")
                    continue
            except Exception:
                pass

        # Every non-resumed attempt starts from an empty evidence directory so
        # stale or partial artifacts cannot satisfy completeness checks.
        shutil.rmtree(case_dir, ignore_errors=True)
        case_dir.mkdir(parents=True, exist_ok=True)

        _wait_for_thermal(
            serial, args.max_soc_temp_c, args.thermal_wait_timeout_s,
            ignore_thermal=bool(args.ignore_thermal))
        if args.cold_process:
            stopped = _adb(
                serial, "shell", "am", "force-stop", "com.facebook.encapp",
                timeout=30, check=False)
            if stopped.returncode != 0:
                raise RuntimeError(f"Encapp force-stop failed: {stopped.stderr.strip()}")
            for _ in range(30):
                pid = _adb(
                    serial, "shell", "pidof", "com.facebook.encapp",
                    timeout=10, check=False).stdout.strip()
                if not pid:
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError(f"Encapp remained alive after force-stop: pid={pid}")
        pre = _device_provenance(serial)
        (case_dir / "device_pre.json").write_text(
            json.dumps(pre, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        cmd = [
            str(encapp_python), str(encapp_script), "run",
            str(root / case["config"]), "--serial", serial,
            "--device-workdir", probe_workdir,
            "-w", str(case_dir), "--log-file", str(case_dir / "encapp_cli.log"),
        ]
        print(f"[{ordinal}/{len(selected)}] {case['id']}")
        started = time.time()
        host_timeout_s = max(
            240, math.ceil(case["scenario_def"]["frames"] / manifest["global"]["fps"]) + 180)
        timed_out = False
        thermal_samples: List[Dict[str, Any]] = []
        thermal_stop = threading.Event()
        thermal_thread = threading.Thread(
            target=_sample_thermal_until,
            args=(serial, thermal_stop, thermal_samples),
            name=f"thermal-{case['id']}", daemon=True)
        thermal_thread.start()
        try:
            try:
                completed = subprocess.run(
                    cmd, text=True, capture_output=True, timeout=host_timeout_s)
                returncode = completed.returncode
                stdout = completed.stdout
                stderr = completed.stderr
            except subprocess.TimeoutExpired as exc:
                timed_out = True
                returncode = 124
                stdout = exc.stdout or ""
                stderr = exc.stderr or ""
                if isinstance(stdout, bytes):
                    stdout = stdout.decode("utf-8", errors="replace")
                if isinstance(stderr, bytes):
                    stderr = stderr.decode("utf-8", errors="replace")
                stderr += f"\nHOST TIMEOUT after {host_timeout_s}s\n"
        finally:
            thermal_stop.set()
            thermal_thread.join(timeout=35)
            (case_dir / "thermal_samples.json").write_text(
                json.dumps(thermal_samples, indent=2, sort_keys=True) + "\n",
                encoding="utf-8")
        elapsed = time.time() - started
        (case_dir / "stdout.txt").write_text(stdout, encoding="utf-8")
        (case_dir / "stderr.txt").write_text(stderr, encoding="utf-8")
        result_path = _find_case_result(case_dir, case["id"])
        artifact_error = None
        frame_type_counts = None
        if returncode == 0 and result_path is not None:
            try:
                _pull_nal_sidecar(serial, case, case_dir)
                result_body = json.loads(result_path.read_text(encoding="utf-8"))
                _refresh_encoded_file(serial, result_path, result_body)
                encoded = _find_encoded(result_path, result_body)
                if encoded is None:
                    raise RuntimeError("encoded media missing after refresh")
                frame_type_counts = _verify_no_b_frames(encoded, case, case_dir)
                remote_names = [
                    f"{case['id']}.nal.json",
                    f"{case['id']}.json",
                    f"{case['id']}.log",
                ]
                if result_body.get("encodedfile"):
                    remote_names.append(Path(result_body["encodedfile"]).name)
                device_workdir = _device_workdir_from_case(case_dir)
                if device_workdir is not None:
                    _remove_workdir_files(serial, device_workdir, remote_names)
            except Exception as exc:
                artifact_error = str(exc)
        post = _device_provenance(serial)
        (case_dir / "device_post.json").write_text(
            json.dumps(post, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        throughput = _feed_throughput(
            case_dir, int(case["scenario_def"]["frames"]))
        validity_warnings: List[str] = []
        for phase, provenance in (("pre", pre), ("post", post)):
            thermal = provenance.get("thermal", {})
            max_thermal = _thermal_max_c(thermal)
            if thermal.get("status") != 0:
                validity_warnings.append(f"{phase} thermal status={thermal.get('status')}")
            if max_thermal is None:
                validity_warnings.append(f"{phase} thermal temperature unavailable")
            elif max_thermal > args.max_soc_temp_c:
                validity_warnings.append(
                    f"{phase} max thermal {max_thermal}C exceeds "
                    f"{args.max_soc_temp_c}C")
            if int(thermal.get("max_sensor_status", 0)) != 0:
                validity_warnings.append(
                    f"{phase} sensor thermal status={thermal.get('max_sensor_status')}")
        if not thermal_samples:
            validity_warnings.append("no in-run thermal samples")
        for sample in thermal_samples:
            if sample.get("error"):
                validity_warnings.append(f"thermal sample error={sample['error']}")
                break
            if sample.get("status") != 0 or sample.get("max_sensor_status") != 0:
                validity_warnings.append(
                    f"in-run thermal status={sample.get('status')}/"
                    f"{sample.get('max_sensor_status')}")
                break
            max_thermal = _thermal_max_c(sample)
            if (max_thermal is None
                    or max_thermal > args.max_soc_temp_c):
                validity_warnings.append(
                    f"in-run max thermal temperature={max_thermal}")
                break
        result_complete = bool(
            result_path and _result_is_complete(result_path, case)
            and (case_dir / f"{case['id']}.nal.json").is_file()
            and artifact_error is None)
        failure_details = _encapp_failure_details(
            case_dir, returncode, result_path, stderr, result_complete)
        status = {
            "case_id": case["id"],
            "config_sha256": case.get("config_sha256"),
            "started_utc": dt.datetime.fromtimestamp(started, dt.timezone.utc).isoformat(),
            "elapsed_s": elapsed,
            "host_timeout_s": host_timeout_s,
            "max_soc_temp_c": args.max_soc_temp_c,
            "thermal_gate_ignored": bool(args.ignore_thermal),
            "feed_throughput": throughput,
            "thermal_sample_count": len(thermal_samples),
            "thermal_peak_soc_c": max(
                (sample["max_soc_c"] for sample in thermal_samples
                 if sample.get("max_soc_c") is not None), default=None),
            "thermal_peak_c": max(
                (_thermal_max_c(sample) for sample in thermal_samples
                 if _thermal_max_c(sample) is not None), default=None),
            "thermal_sensor_bases": sorted({
                str(sample["thermal_sensor_basis"])
                for sample in thermal_samples
                if sample.get("thermal_sensor_basis") is not None
            }),
            "coded_frame_type_counts": frame_type_counts,
            "timed_out": timed_out,
            "returncode": returncode,
            "result_complete": result_complete,
            "scientifically_valid": bool(result_complete and not validity_warnings),
            "validity_warnings": validity_warnings,
            "artifact_error": artifact_error,
            "failure": failure_details,
            "result_json": str(result_path.relative_to(root)) if result_path else None,
            "command": cmd,
        }
        status_path.write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
        if returncode != 0 or not result_complete:
            assert failure_details is not None
            kind = str(failure_details["kind"])
            reason = str(failure_details["reason"])
            diagnostics = list(failure_details.get("diagnostics", []))
            diagnostic_text = diagnostics[-1] if diagnostics else str(case_dir)
            msg = (
                f"case failed: {case['id']} kind={kind} rc={returncode} "
                f"reason={reason!r} diagnostics={diagnostic_text}")
            failures.append(msg)
            print(msg, file=sys.stderr)

            if failure_details.get("pre_result"):
                signature = (kind, reason)
                if signature == consecutive_pre_result_signature:
                    consecutive_pre_result_failures += 1
                else:
                    consecutive_pre_result_signature = signature
                    consecutive_pre_result_failures = 1
            else:
                consecutive_pre_result_signature = None
                consecutive_pre_result_failures = 0

            if kind == "setup_failure":
                raise RuntimeError(
                    f"aborting matrix after setup failure: {msg}")
            if consecutive_pre_result_failures >= 2:
                raise RuntimeError(
                    "aborting matrix after 2 consecutive identical pre-result "
                    f"failures; another process may be launching Encapp: {msg}")
            if not args.keep_going:
                raise RuntimeError(msg)
        else:
            consecutive_pre_result_signature = None
            consecutive_pre_result_failures = 0
        if args.cooldown_s:
            time.sleep(args.cooldown_s)
    if failures:
        raise RuntimeError(f"{len(failures)} case(s) failed; first: {failures[0]}")


def _percentile(values: Sequence[float], q: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(float(x) for x in values)
    if len(ordered) == 1:
        return ordered[0]
    pos = (len(ordered) - 1) * q
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return ordered[lo]
    return ordered[lo] * (hi - pos) + ordered[hi] * (pos - lo)


def _median(values: Sequence[float]) -> Optional[float]:
    return statistics.median(values) if values else None


def _mean(values: Sequence[float]) -> Optional[float]:
    return statistics.fmean(values) if values else None


def _safe_ratio(a: Optional[float], b: Optional[float]) -> Optional[float]:
    if a is None or b in (None, 0):
        return None
    return a / b


def _complexity_for(frame: int, events: Sequence[Sequence[Any]]) -> str:
    starts = [int(e[0]) for e in events]
    idx = bisect.bisect_right(starts, frame) - 1
    return str(events[max(0, idx)][1])


def _find_encoded(result_path: Path, result: Dict[str, Any]) -> Optional[Path]:
    name = result.get("encodedfile")
    if name:
        direct = result_path.parent / name
        if direct.is_file():
            return direct
        hits = list(result_path.parent.glob(f"**/{Path(name).name}"))
        if hits:
            return max(hits, key=lambda p: p.stat().st_mtime)
    stem = result.get("id", result_path.stem)
    for suffix in (".mp4", ".mov", ".webm"):
        hits = list(result_path.parent.glob(f"**/{stem}{suffix}"))
        if hits:
            return max(hits, key=lambda p: p.stat().st_mtime)
    return None


def _find_nal_sidecar(result_path: Path, result_id: str) -> Optional[Path]:
    exact = list(result_path.parent.glob(f"**/{result_id}.nal.json"))
    if exact:
        return max(exact, key=lambda p: p.stat().st_mtime)
    return None


def _validate_stats_sidecar(frames: Sequence[Dict[str, Any]],
                            sidecar_path: Optional[Path]) -> Optional[str]:
    if sidecar_path is None:
        return "NAL sidecar missing; cannot prove stats-to-bitstream alignment"
    try:
        sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
        side_frames = list(sidecar.get("frames", []))
    except Exception as exc:
        return f"NAL sidecar unreadable: {exc}"
    if len(side_frames) != len(frames):
        return f"frame-count mismatch stats={len(frames)} sidecar={len(side_frames)}"
    for idx, (stats_frame, nal_frame) in enumerate(zip(frames, side_frames)):
        if int(nal_frame.get("frame_index", -1)) != idx:
            return f"NAL sidecar frame_index mismatch at output {idx}"
        if int(stats_frame.get("pts", -1)) != int(nal_frame.get("pts_us", -2)):
            return f"PTS mismatch at output {idx}: stats={stats_frame.get('pts')} sidecar={nal_frame.get('pts_us')}"
        if int(stats_frame.get("size", -1)) != int(nal_frame.get("total_size", -2)):
            return f"size mismatch at output {idx}: stats={stats_frame.get('size')} sidecar={nal_frame.get('total_size')}"
    return None


def _validate_qp_alignment(frames: Sequence[Dict[str, Any]], sidecar_path: Optional[Path],
                           qp: Dict[int, Dict[str, float]]) -> Optional[str]:
    stats_error = _validate_stats_sidecar(frames, sidecar_path)
    if stats_error:
        return stats_error
    if len(qp) != len(frames):
        return f"frame-count mismatch stats={len(frames)} qpextract={len(qp)}"
    if set(qp) != set(range(len(frames))):
        return "qpextract frame indices are not contiguous from zero"
    return None


def _read_qp_csv(path: Path) -> Dict[int, Dict[str, float]]:
    out: Dict[int, Dict[str, float]] = {}
    with path.open(newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            try:
                idx = int(row["frame"])
                out[idx] = {
                    "qp_min": float(row["qpy.min"]),
                    "qp_avg": float(row["qpy.weighted.avg"]),
                    "qp_unweighted_avg": float(row["qpy.avg"]),
                    "qp_max": float(row["qpy.max"]),
                }
            except (KeyError, TypeError, ValueError):
                continue
    return out


def _qpextract_hevc(encoded: Path, work: Path, qpextract: str, ffmpeg: str,
                    frame_count: int) -> Dict[int, Dict[str, float]]:
    annexb = work / "stream.265"
    out_csv = work / "qp_y.csv"
    if out_csv.is_file():
        cached = _read_qp_csv(out_csv)
        if set(cached) == set(range(frame_count)):
            return cached
    _run_checked([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(encoded),
        "-c:v", "copy", "-bsf:v", "hevc_mp4toannexb", str(annexb),
    ], timeout=120)
    _run_checked([
        qpextract, "-i", str(annexb), "--dump", "--qpymode", "-o", str(out_csv),
    ], timeout=max(300, math.ceil(frame_count * 0.4)))
    return _read_qp_csv(out_csv)


def _rolling_bitrate(rows: List[Dict[str, Any]], window_us: int,
                     out_key: str, *, include_i: bool = True) -> None:
    left = 0
    bit_sum = 0.0
    for right, row in enumerate(rows):
        bit_sum += row["bits"] if include_i or not row["is_i"] else 0.0
        now = row["pts_us"]
        while left < right and now - rows[left]["pts_us"] > window_us:
            bit_sum -= (rows[left]["bits"]
                        if include_i or not rows[left]["is_i"] else 0.0)
            left += 1
        duration = max(1, now - rows[left]["pts_us"] + row["dt_us"])
        row[out_key] = bit_sum * 1_000_000.0 / duration


def _tau63(rows: Sequence[Dict[str, Any]], start_frame: int, end_frame: int,
           key: str, fps: int) -> Optional[float]:
    if end_frame - start_frame < 3 * fps:
        return None
    before = [float(r[key]) for r in rows
              if start_frame - 2 * fps <= r["original_frame"] < start_frame
              and not r.get("is_i") and r.get(key) is not None]
    after = [r for r in rows if start_frame <= r["original_frame"] < end_frame
             and not r.get("is_i") and r.get(key) is not None]
    if len(before) < max(3, fps // 2) or len(after) < max(3, fps):
        return None
    steady_n = min(2 * fps, max(fps, len(after) // 3))
    y0 = statistics.median(before)
    y1 = statistics.median(float(r[key]) for r in after[-steady_n:])
    delta = y1 - y0
    pre_mad = statistics.median(abs(value - y0) for value in before)
    if abs(delta) < max(0.01, abs(y0) * 0.01, 1.5 * pre_mad):
        return None
    threshold = y0 + 0.6321205588 * delta
    first_pts = after[0]["pts_us"]
    required = max(2, min(5, fps // 10))
    streak = 0
    crossing_pts: Optional[int] = None
    for r in after:
        value = float(r[key])
        crossed = ((delta > 0 and value >= threshold)
                   or (delta < 0 and value <= threshold))
        if crossed:
            if streak == 0:
                crossing_pts = int(r["pts_us"])
            streak += 1
            if streak >= required and crossing_pts is not None:
                return (crossing_pts - first_pts) / 1000.0
        else:
            streak = 0
            crossing_pts = None
    return None


def _later_matching_segment(events: Sequence[Sequence[Any]],
                            total_frames: int) -> Optional[Tuple[int, int]]:
    if not events:
        return None
    initial_complexity = str(events[0][1])
    later_indices = [i for i, event in enumerate(events[1:], 1)
                     if str(event[1]) == initial_complexity]
    if not later_indices:
        return None
    idx = later_indices[-1]
    start = int(events[idx][0])
    end = int(events[idx + 1][0]) if idx + 1 < len(events) else total_frames
    return start, end


def _startup_convergence(rows: Sequence[Dict[str, Any]], events: Sequence[Sequence[Any]],
                         total_frames: int, fps: int, key: str,
                         *, absolute_tolerance: Optional[float] = None,
                         relative_tolerance: Optional[float] = None) -> Optional[int]:
    if not rows or not events:
        return None
    segment = _later_matching_segment(events, total_frames)
    if segment is None:
        return None
    later_start, later_end = segment
    steady = [float(r[key]) for r in rows
              if max(later_start, later_end - 2 * fps) <= r["original_frame"] < later_end
              and not r.get("is_i") and r.get(key) is not None]
    if len(steady) < max(3, fps // 2):
        return None
    target = statistics.median(steady)
    initial_end = int(events[1][0]) if len(events) > 1 else total_frames
    initial = [r for r in rows if r["original_frame"] < initial_end
               and not r.get("is_i") and r.get(key) is not None]
    required = max(3, fps // 6)
    streak = 0
    first_frame: Optional[int] = None
    for row in initial:
        value = float(row[key])
        tolerance = (absolute_tolerance if absolute_tolerance is not None
                     else abs(target) * float(relative_tolerance or 0.0))
        if abs(value - target) <= tolerance:
            if streak == 0:
                first_frame = int(row["original_frame"])
            streak += 1
            if streak >= required:
                return first_frame
        else:
            streak = 0
            first_frame = None
    return None


def _analyze_one(case: Dict[str, Any], result_path: Path, global_cfg: Dict[str, Any],
                 analysis_root: Path, qpextract: Optional[str], ffmpeg: Optional[str]) -> Tuple[Dict[str, Any], List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
    result = json.loads(result_path.read_text(encoding="utf-8"))
    encoded = _find_encoded(result_path, result)
    frames = [dict(f) for f in result.get("frames", []) if int(f.get("size", 0)) > 0]
    frames.sort(key=lambda f: int(f.get("frame", 0)))
    qp: Dict[int, Dict[str, float]] = {}
    qp_error = None
    if encoded and global_cfg["mime"] == "video/hevc" and qpextract and ffmpeg:
        try:
            qwork = analysis_root / "qpextract" / case["id"]
            qwork.mkdir(parents=True, exist_ok=True)
            qp = _qpextract_hevc(
                encoded, qwork, qpextract, ffmpeg, len(frames))
        except Exception as exc:  # preserve rate-control evidence even if QP tooling fails
            qp_error = str(exc)

    if qp:
        alignment_error = _validate_qp_alignment(
            frames, _find_nal_sidecar(result_path, case["id"]), qp)
        if alignment_error:
            qp = {}
            qp_error = alignment_error

    fps = int(global_cfg["fps"])
    target_bps = int(global_cfg["bitrate_bps"])
    default_dt_us = int(round(1_000_000 / fps))
    rows: List[Dict[str, Any]] = []
    backlog = 0.0
    cumulative_error = 0.0
    prev_pts: Optional[int] = None
    for out_index, frame in enumerate(frames):
        pts = int(frame["pts"])
        original = int(frame.get("original_frame", out_index))
        drain_dt_us = 0 if prev_pts is None else max(1, pts - prev_pts)
        dt_us = default_dt_us if prev_pts is None else drain_dt_us
        bits = int(frame["size"]) * 8
        target_bits = target_bps * drain_dt_us / 1_000_000.0
        error = bits - target_bits
        cumulative_error += error
        # Drain the queue over elapsed time before this frame arrives, then
        # enqueue the complete access unit at its PTS.
        backlog = max(0.0, backlog - target_bits) + bits
        row: Dict[str, Any] = {
            "case_id": case["id"], "repeat": case["repeat"],
            "mode": case["mode"], "qp_profile": case["qp_profile"],
            "scenario": case["scenario"], "output_index": out_index,
            "original_frame": original, "pts_us": pts, "dt_us": dt_us,
            "drain_dt_us": drain_dt_us,
            "is_i": int(frame.get("iframe", 0)), "size_bytes": int(frame["size"]),
            "bits": bits, "target_bits": target_bits, "error_bits": error,
            "cumulative_error_bits": cumulative_error, "backlog_bits": backlog,
            "vbv_delay_ms": backlog * 1000.0 / target_bps,
            "encode_time_ms": float(frame.get("proctime", 0)) / 1_000_000.0,
            "complexity": _complexity_for(original, case["scenario_def"]["events"]),
        }
        if out_index in qp:
            row.update(qp[out_index])
        else:
            row.update({"qp_min": None, "qp_avg": None,
                        "qp_unweighted_avg": None, "qp_max": None})
        rows.append(row)
        prev_pts = pts
    _rolling_bitrate(rows, 1_000_000, "rolling_bps_1s")
    _rolling_bitrate(rows, 200_000, "rolling_bps_200ms")
    _rolling_bitrate(
        rows, 200_000, "rolling_p_bps_200ms", include_i=False)

    i_rows = [r for r in rows if r["is_i"]]
    p_rows = [r for r in rows if not r["is_i"]]
    first_i = i_rows[0] if i_rows else None
    repeated_segment = _later_matching_segment(
        case["scenario_def"]["events"], case["scenario_def"]["frames"])
    steady_i = ([r for r in i_rows
                 if repeated_segment[0] <= r["original_frame"] < repeated_segment[1]]
                if repeated_segment else [])
    expected_drops = set(int(x) for x in case["scenario_def"].get("drops", []))
    observed_originals = set(int(r["original_frame"]) for r in rows)
    all_missing = set(range(case["scenario_def"]["frames"])) - observed_originals
    missing_expected = sorted(expected_drops - all_missing)
    unexpected_all = sorted(all_missing - expected_drops)
    last_observed = max(observed_originals, default=-1)
    unexpected_missing_interior = [n for n in unexpected_all if n <= last_observed]
    unexpected_missing_trailing = [n for n in unexpected_all if n > last_observed]

    encoder_format = result.get("encoder_media_format") or {}
    requested_keys: Dict[str, int] = {}
    for frame_type in ("i", "p"):
        for bound in ("min", "max"):
            if bound in case["qp_bounds"]:
                requested_keys[f"video-qp-{frame_type}-{bound}"] = int(case["qp_bounds"][bound])
    qp_bounds_echoed = (all(key in encoder_format and int(encoder_format[key]) == value
                             for key, value in requested_keys.items())
                         if requested_keys else True)
    qp_bound_violations = 0
    for row in rows:
        if row.get("qp_min") is None:
            continue
        if "min" in case["qp_bounds"] and row["qp_min"] < case["qp_bounds"]["min"]:
            qp_bound_violations += 1
        if "max" in case["qp_bounds"] and row["qp_max"] > case["qp_bounds"]["max"]:
            qp_bound_violations += 1
    if not requested_keys:
        qp_bounds_status = "unbounded"
    elif not qp:
        qp_bounds_status = "unverified_no_aligned_qp"
    elif qp_bound_violations:
        qp_bounds_status = "violated"
    elif qp_bounds_echoed:
        qp_bounds_status = "echoed_and_observed"
    else:
        qp_bounds_status = "observed_only_not_echoed"

    run_summary: Dict[str, Any] = {
        "case_id": case["id"], "repeat": case["repeat"], "mode": case["mode"],
        "qp_profile": case["qp_profile"], "qp_min_requested": case["qp_bounds"].get("min"),
        "qp_max_requested": case["qp_bounds"].get("max"),
        "qp_bounds_echoed": qp_bounds_echoed,
        "qp_bound_violation_count": qp_bound_violations,
        "qp_bounds_status": qp_bounds_status,
        "scenario": case["scenario"],
        "result_json": str(result_path), "encoded_file": str(encoded) if encoded else None,
        "frame_count": len(rows), "expected_input_frames": case["scenario_def"]["frames"],
        "expected_drop_count": len(expected_drops),
        "unexpected_missing_count": len(unexpected_all),
        "unexpected_missing_interior_count": len(unexpected_missing_interior),
        "unexpected_missing_trailing_count": len(unexpected_missing_trailing),
        "expected_drop_not_missing_count": len(missing_expected),
        "mean_bitrate_bps": (sum(r["bits"] for r in rows) * 1_000_000.0 /
                              max(1, rows[-1]["pts_us"] - rows[0]["pts_us"] + rows[-1]["dt_us"]))
                             if rows else None,
        "max_frame_bytes": max((r["size_bytes"] for r in rows), default=None),
        "vbv_delay_p50_ms": _percentile([r["vbv_delay_ms"] for r in rows], 0.50),
        "vbv_delay_p90_ms": _percentile([r["vbv_delay_ms"] for r in rows], 0.90),
        "vbv_delay_p99_ms": _percentile([r["vbv_delay_ms"] for r in rows], 0.99),
        "vbv_delay_max_ms": max((r["vbv_delay_ms"] for r in rows), default=None),
        "peak_overshoot_bits": max((r["backlog_bits"] for r in rows), default=None),
        "first_i_size_bytes": first_i["size_bytes"] if first_i else None,
        "first_i_qp_avg": first_i.get("qp_avg") if first_i else None,
        "steady_i_median_size_bytes": _median([r["size_bytes"] for r in steady_i]),
        "steady_i_median_qp_avg": _median([r["qp_avg"] for r in steady_i if r.get("qp_avg") is not None]),
        "p_median_size_bytes": _median([r["size_bytes"] for r in p_rows]),
        "p_median_qp_avg": _median([r["qp_avg"] for r in p_rows if r.get("qp_avg") is not None]),
        "i_p_size_ratio": _safe_ratio(_median([r["size_bytes"] for r in i_rows]), _median([r["size_bytes"] for r in p_rows])),
        "startup_i_vs_steady_i_ratio": _safe_ratio(first_i["size_bytes"] if first_i else None, _median([r["size_bytes"] for r in steady_i])),
        "startup_qp_convergence_frame": _startup_convergence(
            rows, case["scenario_def"]["events"], case["scenario_def"]["frames"],
            fps, "qp_avg", absolute_tolerance=1.0),
        "startup_size_convergence_frame": _startup_convergence(
            rows, case["scenario_def"]["events"], case["scenario_def"]["frames"],
            fps, "size_bytes", relative_tolerance=0.15),
        "qp_observed_min": min((r["qp_min"] for r in rows if r.get("qp_min") is not None), default=None),
        "qp_observed_max": max((r["qp_max"] for r in rows if r.get("qp_max") is not None), default=None),
        "qp_error": qp_error,
        "unexpected_missing_frames": unexpected_all,
        "unexpected_missing_interior_frames": unexpected_missing_interior,
        "unexpected_missing_trailing_frames": unexpected_missing_trailing,
        "expected_drops_not_missing": missing_expected,
    }

    steps: List[Dict[str, Any]] = []
    events = case["scenario_def"]["events"]
    for idx, event in enumerate(events[1:], 1):
        start = int(event[0])
        end = int(events[idx + 1][0]) if idx + 1 < len(events) else int(case["scenario_def"]["frames"])
        prev_complexity = str(events[idx - 1][1])
        next_complexity = str(event[1])
        step = {
            "case_id": case["id"], "repeat": case["repeat"], "mode": case["mode"],
            "qp_profile": case["qp_profile"], "scenario": case["scenario"],
            "start_frame": start, "end_frame": end,
            "from_complexity": prev_complexity, "to_complexity": next_complexity,
            "direction": "up" if COMPLEXITY_RANK[next_complexity] > COMPLEXITY_RANK[prev_complexity] else "down",
            "qp_tau63_ms": _tau63(rows, start, end, "qp_avg", fps),
            "bitrate_tau63_ms": _tau63(rows, start, end, "rolling_p_bps_200ms", fps),
            "pre_qp_median": _median([r["qp_avg"] for r in rows if start - 2 * fps <= r["original_frame"] < start and r.get("qp_avg") is not None]),
            "steady_qp_median": _median([r["qp_avg"] for r in rows if max(start, end - 2 * fps) <= r["original_frame"] < end and r.get("qp_avg") is not None]),
            "pre_bitrate_median_bps": _median([r["rolling_bps_1s"] for r in rows if start - 2 * fps <= r["original_frame"] < start]),
            "steady_bitrate_median_bps": _median([r["rolling_bps_1s"] for r in rows if max(start, end - 2 * fps) <= r["original_frame"] < end]),
        }
        steps.append(step)

    drop_rows: List[Dict[str, Any]] = []
    if expected_drops:
        by_orig = {int(r["original_frame"]): r for r in rows}
        missing_actual = set(range(case["scenario_def"]["frames"])) - observed_originals
        for region in case["scenario_def"].get("drop_regions", []):
            start = int(region["start"])
            end = int(region["end"])
            expected_region = sorted(n for n in expected_drops if start <= n < end)
            before = [r for r in rows if start - 2 * fps <= r["original_frame"] < start]
            within = [r for r in rows if start <= r["original_frame"] < end]
            baseline_size = _median([r["size_bytes"] for r in before if not r["is_i"]])
            baseline_qp = _median([r["qp_avg"] for r in before
                                   if not r["is_i"] and r.get("qp_avg") is not None])
            baseline_bps = (sum(r["bits"] for r in before) * fps / len(before)) if before else None
            region_bps = sum(r["bits"] for r in within) * fps / max(1, end - start)
            post_rows: List[Dict[str, Any]] = []
            post_p_rows: List[Dict[str, Any]] = []
            for dropped in expected_region:
                search = range(dropped + 1, end + fps)
                post = next((by_orig[n] for n in search if n in by_orig), None)
                if post is not None and (not post_rows or post["original_frame"] != post_rows[-1]["original_frame"]):
                    post_rows.append(post)
                post_p = next((by_orig[n] for n in search
                               if n in by_orig and not by_orig[n]["is_i"]), None)
                if (post_p is not None
                        and (not post_p_rows
                             or post_p["original_frame"] != post_p_rows[-1]["original_frame"])):
                    post_p_rows.append(post_p)
            post_size_ratios = [r["size_bytes"] / baseline_size for r in post_p_rows
                                if baseline_size not in (None, 0)]
            post_qp_deltas = [r["qp_avg"] - baseline_qp for r in post_p_rows
                              if r.get("qp_avg") is not None and baseline_qp is not None]
            drop_rows.append({
                "case_id": case["id"], "repeat": case["repeat"], "mode": case["mode"],
                "qp_profile": case["qp_profile"], "region": region["name"],
                "region_start": start, "region_end": end,
                "expected_drop_count": len(expected_region),
                "observed_missing_count": len(set(expected_region) & missing_actual),
                "output_frame_count": len(within),
                "post_frame_count": len(post_rows),
                "post_p_frame_count": len(post_p_rows),
                "post_i_frame_count": sum(1 for r in post_rows if r["is_i"]),
                "baseline_bps": baseline_bps, "region_bps": region_bps,
                "region_bitrate_ratio": _safe_ratio(region_bps, baseline_bps),
                "post_size_vs_baseline_median": _median(post_size_ratios),
                "post_size_vs_baseline_max": max(post_size_ratios, default=None),
                "post_qp_delta_median": _median(post_qp_deltas),
                "max_pts_gap_ms": max((r["dt_us"] / 1000.0 for r in within), default=None),
                "max_vbv_delay_ms": max((r["vbv_delay_ms"] for r in within), default=None),
            })

    return run_summary, rows, steps, drop_rows


def _write_csv(path: Path, rows: Sequence[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    keys: List[str] = []
    seen = set()
    for row in rows:
        for key in row:
            if key not in seen:
                seen.add(key)
                keys.append(key)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=keys, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def _rolling_time_weighted(
        rows: Sequence[Dict[str, Any]], key: str,
        window_s: float) -> List[float]:
    """Trailing PTS-window mean weighted by each frame's represented duration."""
    if window_s <= 0:
        return [float(row[key]) if row.get(key) is not None else math.nan
                for row in rows]
    window_us = max(1, int(round(window_s * 1_000_000.0)))
    output: List[float] = []
    for right, row in enumerate(rows):
        end_us = int(row["pts_us"]) + int(row["dt_us"])
        start_us = end_us - window_us
        weighted_sum = 0.0
        total_weight = 0
        for candidate in rows[:right + 1]:
            if candidate.get(key) is None:
                continue
            candidate_start = int(candidate["pts_us"])
            candidate_end = candidate_start + int(candidate["dt_us"])
            overlap = max(
                0, min(candidate_end, end_us) - max(candidate_start, start_us))
            if overlap:
                weighted_sum += float(candidate[key]) * overlap
                total_weight += overlap
        output.append(weighted_sum / total_weight if total_weight else math.nan)
    return output


def _repeat_group_key(row: Dict[str, Any]) -> Tuple[str, str, str]:
    return (
        str(row.get("scenario", "")),
        str(row.get("qp_profile", "")),
        str(row.get("mode", "")),
    )


def _average_repeat_rows(rows: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Align deterministic repeats by original frame and average plot signals."""
    by_frame: Dict[int, List[Dict[str, Any]]] = {}
    for row in rows:
        by_frame.setdefault(int(row["original_frame"]), []).append(row)
    metrics = (
        "pts_us", "dt_us", "size_bytes", "rolling_bps_1s",
        "qp_min", "qp_avg", "qp_max", "vbv_delay_ms",
    )
    output: List[Dict[str, Any]] = []
    for frame in sorted(by_frame):
        group = by_frame[frame]
        first = dict(group[0])
        first["case_id"] = "repeat_average"
        first["repeat"] = 0
        first["repeat_count"] = len(group)
        for metric in metrics:
            values = [float(row[metric]) for row in group
                      if row.get(metric) is not None]
            first[metric] = statistics.fmean(values) if values else None
            first[f"{metric}_repeat_min"] = min(values) if values else None
            first[f"{metric}_repeat_max"] = max(values) if values else None
        first["is_i"] = int(any(int(row.get("is_i", 0)) for row in group))
        output.append(first)
    return output


def _average_repeat_summary(rows: Sequence[Dict[str, Any]], case_id: str
                            ) -> Dict[str, Any]:
    first = dict(rows[0])
    first["case_id"] = case_id
    first["repeat"] = 0
    first["repeat_count"] = len(rows)
    for key in (
            "mean_bitrate_bps", "vbv_delay_max_ms", "p_median_qp_avg",
            "i_p_size_ratio", "startup_i_vs_steady_i_ratio",
            "startup_qp_convergence_frame", "startup_size_convergence_frame"):
        values = [float(row[key]) for row in rows if row.get(key) is not None]
        first[key] = statistics.fmean(values) if values else None
    return first


def _repeat_average_plot_inputs(
        selected: Sequence[Dict[str, Any]], summaries: Sequence[Dict[str, Any]],
        frame_rows: Sequence[Dict[str, Any]],
) -> List[Tuple[Dict[str, Any], Dict[str, Any], List[Dict[str, Any]]]]:
    """Return semantically ordered (case, summary, averaged rows) plot inputs."""
    cases_by_group: Dict[Tuple[str, str, str], List[Dict[str, Any]]] = {}
    for case in selected:
        cases_by_group.setdefault(_repeat_group_key(case), []).append(case)
    summaries_by_group: Dict[Tuple[str, str, str], List[Dict[str, Any]]] = {}
    for summary in summaries:
        summaries_by_group.setdefault(_repeat_group_key(summary), []).append(summary)
    frames_by_group: Dict[Tuple[str, str, str], List[Dict[str, Any]]] = {}
    for row in frame_rows:
        frames_by_group.setdefault(_repeat_group_key(row), []).append(row)

    output = []
    for group in sorted(cases_by_group, key=lambda key: _case_sort_key({
            "scenario": key[0], "qp_profile": key[1], "mode": key[2],
            "repeat": 0})):
        group_summaries = summaries_by_group.get(group, [])
        group_frames = frames_by_group.get(group, [])
        if not group_summaries or not group_frames:
            continue
        representative = dict(sorted(cases_by_group[group], key=_case_sort_key)[0])
        case_id = f"rc_{group[0]}_{group[2]}_{group[1]}_avg"
        representative["id"] = case_id
        representative["repeat"] = 0
        representative["repeat_count"] = len(group_summaries)
        output.append((
            representative,
            _average_repeat_summary(group_summaries, case_id),
            _average_repeat_rows(group_frames),
        ))
    return output


def _annotate_case_interventions(axes: Sequence[Any], case: Dict[str, Any],
                                 fps: int) -> None:
    """Add experiment phases and interventions to every per-case panel."""
    colors = {"LOW": "#009E73", "MID": "#E69F00", "HIGH": "#CC79A7"}
    events = [(int(frame), str(level))
              for frame, level in case["scenario_def"].get("events", [])]
    end_frame = int(case["scenario_def"]["frames"])
    for index, (start, level) in enumerate(events):
        end = events[index + 1][0] if index + 1 < len(events) else end_frame
        start_s, end_s = start / float(fps), end / float(fps)
        for ax in axes:
            ax.axvspan(start_s, end_s, color=colors.get(level, "gray"),
                       alpha=0.075, linewidth=0, zorder=0)
        duration = end_s - start_s
        label = level if duration >= 5 else f"{level}\n{duration:g} s"
        axes[0].text(
            (start_s + end_s) / 2.0, 0.985, label,
            transform=axes[0].get_xaxis_transform(), ha="center", va="top",
            fontsize=7.5, color=colors.get(level, "black"), fontweight="bold",
            bbox={"facecolor": "white", "edgecolor": "none", "alpha": 0.66,
                  "pad": 1.5})

    region_labels = {
        "single": "1 omitted input", "burst2": "2-frame omission",
        "burst4": "4-frame omission", "burst8": "8-frame omission",
        "half_rate": "30→15 fps (4 s)", "random10": "10% random omissions",
    }
    for region in case["scenario_def"].get("drop_regions", []):
        start_s = int(region["start"]) / float(fps)
        end_s = int(region["end"]) / float(fps)
        for ax in axes:
            ax.axvspan(start_s, end_s, facecolor="none", edgecolor="#D55E00",
                       hatch="////", alpha=0.18, linewidth=0.8, zorder=1)
        axes[1].text(
            (start_s + end_s) / 2.0, 0.985,
            region_labels.get(str(region["name"]), str(region["name"])),
            transform=axes[1].get_xaxis_transform(), ha="center", va="top",
            fontsize=7.0, color="#A33B00", rotation=90 if end_s - start_s <= 1 else 0,
            bbox={"facecolor": "white", "edgecolor": "none", "alpha": 0.72,
                  "pad": 1.2})

    for frame in case["scenario_def"].get("request_sync", []):
        second = int(frame) / float(fps)
        for ax in axes:
            ax.axvline(second, color="#0072B2", linestyle="-.", linewidth=1.1,
                       alpha=0.75)
        axes[0].text(
            second, 0.04, "sync request", transform=axes[0].get_xaxis_transform(),
            rotation=90, ha="right", va="bottom", fontsize=7, color="#0072B2")

    dropped = [int(frame) / float(fps)
               for frame in case["scenario_def"].get("drops", [])]
    if dropped:
        axes[-1].scatter(
            dropped, [-0.055] * len(dropped), marker="|", s=28,
            color="#D55E00", alpha=0.72,
            transform=axes[-1].get_xaxis_transform(), clip_on=False,
            label="omitted input slot")


def _plot_case(rows: List[Dict[str, Any]], case: Dict[str, Any], out: Path,
               target_bps: int, fps: int, smoothing_seconds: float = 1.0,
               raw_qp_overlay: bool = False) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        return
    if not rows:
        return
    x = [r["pts_us"] / 1_000_000.0 for r in rows]
    fig, axes = plt.subplots(4, 1, figsize=(14, 11), sharex=True)
    smoothing_label = ("raw" if smoothing_seconds <= 0
                       else f"{smoothing_seconds:g} s trailing mean")
    average_plot = int(case.get("repeat_count", 1)) > 1
    size_values = [r["size_bytes"] / 1024 for r in rows]
    if average_plot:
        axes[0].fill_between(
            x,
            [r["size_bytes_repeat_min"] / 1024 for r in rows],
            [r["size_bytes_repeat_max"] / 1024 for r in rows],
            color="tab:blue", alpha=0.16, label="repeat range")
    axes[0].plot(
        x, size_values, linewidth=0.9,
        label="mean coded frame" if average_plot else "coded frame")
    i_rows = [row for row in rows if row.get("is_i")]
    if i_rows:
        axes[0].scatter(
            [row["pts_us"] / 1_000_000.0 for row in i_rows],
            [row["size_bytes"] / 1024 for row in i_rows],
            marker="o", facecolors="none", edgecolors="#D55E00", s=30,
            linewidths=0.9, label="I-frame", zorder=4)
        axes[0].legend(loc="upper right", ncol=2, fontsize=8)
    axes[0].set_title("Encoded frame size")
    axes[0].set_ylabel("frame KiB")
    bitrate_values = [r["rolling_bps_1s"] / 1e6 for r in rows]
    if average_plot:
        axes[1].fill_between(
            x,
            [r["rolling_bps_1s_repeat_min"] / 1e6 for r in rows],
            [r["rolling_bps_1s_repeat_max"] / 1e6 for r in rows],
            color="tab:blue", alpha=0.16, label="repeat range")
    axes[1].plot(
        x, bitrate_values, linewidth=1.2,
        label="repeat mean" if average_plot else "one-second bitrate")
    axes[1].axhline(target_bps / 1e6, color="black", linestyle="--", linewidth=0.8,
                    label="requested target")
    axes[1].legend(loc="upper right", ncol=3, fontsize=8)
    axes[1].set_title("One-second trailing bitrate")
    axes[1].set_ylabel("1 s Mbps")
    if any(r.get("qp_avg") is not None for r in rows):
        qp_avg = _rolling_time_weighted(rows, "qp_avg", smoothing_seconds)
        qp_min = _rolling_time_weighted(rows, "qp_min", smoothing_seconds)
        qp_max = _rolling_time_weighted(rows, "qp_max", smoothing_seconds)
        if average_plot:
            qp_repeat_min = _rolling_time_weighted(
                rows, "qp_avg_repeat_min", smoothing_seconds)
            qp_repeat_max = _rolling_time_weighted(
                rows, "qp_avg_repeat_max", smoothing_seconds)
            axes[2].fill_between(
                x, qp_repeat_min, qp_repeat_max, alpha=0.20, color="gray",
                label="repeat mean-QPY range")
        axes[2].fill_between(
            x, qp_min, qp_max, alpha=0.16, color="tab:blue",
            label=f"{smoothing_label} min–max")
        axes[2].plot(
            x, qp_avg, color="tab:blue", linewidth=1.8,
            label=f"{smoothing_label} weighted avg")
        if raw_qp_overlay and smoothing_seconds > 0:
            axes[2].plot(
                x, [r.get("qp_avg", math.nan)
                    if r.get("qp_avg") is not None else math.nan for r in rows],
                color="black", linewidth=0.45, alpha=0.20, label="raw avg")
        axes[2].legend(loc="best", ncol=2)
    axes[2].set_title(f"Luma QP ({smoothing_label})")
    axes[2].set_ylabel("QPY")
    backlog_values = [r["vbv_delay_ms"] for r in rows]
    if average_plot:
        axes[3].fill_between(
            x,
            [r["vbv_delay_ms_repeat_min"] for r in rows],
            [r["vbv_delay_ms_repeat_max"] for r in rows],
            color="tab:blue", alpha=0.16, label="repeat range")
    axes[3].plot(
        x, backlog_values, linewidth=1.2,
        label="repeat mean" if average_plot else "reconstructed backlog")
    if average_plot:
        axes[3].legend(loc="upper right", ncol=2, fontsize=8)
    axes[3].set_title("Observable leaky-bucket backlog")
    axes[3].set_ylabel("VBV delay ms")
    axes[3].set_xlabel("PTS seconds")
    _annotate_case_interventions(axes, case, fps)
    scenario = str(case.get("scenario", "unknown")).replace("_", " ").title()
    mode = str(case.get("mode", "unknown")).upper()
    profile_labels = {
        "unbounded": "unbounded", "min27": "minimum 27",
        "max38": "maximum 38", "jcq_27_38": "27–38",
        "tight_30_35": "30–35",
    }
    profile = profile_labels.get(
        str(case.get("qp_profile", "unknown")),
        str(case.get("qp_profile", "unknown")).replace("_", " "))
    description = str(case["scenario_def"].get("description", ""))
    repeat_line = (f"mean of {case['repeat_count']} repeats (shading = repeat range)"
                   if average_plot else str(case["id"]))
    fig.suptitle(
        f"{scenario} — {mode}, QP profile {profile}\n"
        f"{description}\n{repeat_line}",
        fontsize=13)
    fig.tight_layout(rect=(0, 0.01, 1, 0.90))
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=140)
    plt.close(fig)


def _fmt(value: Any, digits: int = 1) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def _group_median(rows: Sequence[Dict[str, Any]], key: str) -> Optional[float]:
    values = [float(row[key]) for row in rows if row.get(key) is not None]
    return statistics.median(values) if values else None


def _scenario_design_lines(manifest: Dict[str, Any]) -> List[str]:
    fps = int(manifest["global"]["fps"])
    scenarios: Dict[str, Dict[str, Any]] = {}
    for case in manifest["cases"]:
        scenarios.setdefault(str(case["scenario"]), case["scenario_def"])
    colors = "LOW=green, MID=orange, HIGH=purple"
    lines = [
        "## What this experiment does", "",
        ("The probe submits deterministic NV12 fake input as quickly as the codec "
         "accepts it. The configured frame rate defines PTS spacing and the "
         "rate-control timeline; wall-clock feed speed is diagnostic only."), "",
        ("Every case disables B-frames and varies only rate-control mode, static "
         "QP profile, scenario, and repeat. Plot backgrounds encode source "
         f"complexity ({colors}); hollow orange circles mark I-frames; orange "
         "hatched regions and bottom tick marks identify omitted input slots."), "",
        "### Intervention timeline", "",
        "| Scenario | Scheduled intervention | What it probes |", "|---|---|---|",
    ]
    for name in ("dynamics", "hard_start", "drops"):
        scenario = scenarios.get(name)
        if not scenario:
            continue
        if name in {"dynamics", "hard_start"}:
            events = [
                f"{int(frame) / fps:.2f} s → {level}"
                for frame, level in scenario.get("events", [])]
            timeline = "; ".join(events)
        else:
            labels = {
                "single": "one omitted input", "burst2": "2-frame omission",
                "burst4": "4-frame omission", "burst8": "8-frame omission",
                "half_rate": "every other input omitted (30→15 fps)",
                "random10": "deterministic 10% omissions",
            }
            timeline = "; ".join(
                f"{int(region['start']) / fps:g}–{int(region['end']) / fps:g} s: "
                f"{labels.get(str(region['name']), region['name'])}"
                for region in scenario.get("drop_regions", []))
        lines.append(
            f"| {name.replace('_', ' ').title()} | {timeline} | "
            f"{scenario.get('description', '')} |")
    lines += [
        "", "### How to read the plots", "",
        "- Frame-size spikes with hollow orange circles are I-frames, not complexity interventions.",
        "- Complexity transitions are labeled at the top of each plot and shaded consistently across panels.",
        "- In omission cases, hatched windows name the intervention and orange ticks show the exact omitted PTS slots.",
        "- The dashed bitrate reference is the requested target; backlog is an externally reconstructed leaky bucket, not an internal hardware VBV state.",
        "- QPY is a trailing PTS-time-weighted display trend; tables and CSVs retain the underlying aligned per-frame values.",
        "",
    ]
    return lines


def _executive_observations(manifest: Dict[str, Any],
                            summaries: Sequence[Dict[str, Any]],
                            steps: Sequence[Dict[str, Any]],
                            drops: Sequence[Dict[str, Any]]) -> List[str]:
    target = float(manifest["global"]["bitrate_bps"])
    observations: List[str] = []
    by_mode = {
        mode: [row for row in summaries if row.get("mode") == mode]
        for mode in sorted({str(row["mode"]) for row in summaries})
    }
    if "cbr" in by_mode and "vbr" in by_mode:
        cbr_rate = _median([
            float(row["mean_bitrate_bps"]) / target for row in by_mode["cbr"]
            if row.get("mean_bitrate_bps") is not None])
        vbr_rate = _median([
            float(row["mean_bitrate_bps"]) / target for row in by_mode["vbr"]
            if row.get("mean_bitrate_bps") is not None])
        cbr_backlog = _group_median(by_mode["cbr"], "vbv_delay_max_ms")
        vbr_backlog = _group_median(by_mode["vbr"], "vbv_delay_max_ms")
        observations.append(
            f"Across all scenarios and QP profiles, median mean output was "
            f"{_fmt(cbr_rate, 2)}× target for CBR and {_fmt(vbr_rate, 2)}× for "
            f"VBR; median maximum reconstructed backlog was "
            f"{_fmt(cbr_backlog, 0)} ms versus {_fmt(vbr_backlog, 0)} ms.")

    for mode in sorted(by_mode):
        valid_steps = [row for row in steps if row.get("mode") == mode]
        qp_values = [float(row["qp_tau63_ms"]) for row in valid_steps
                     if row.get("qp_tau63_ms") is not None]
        rate_values = [float(row["bitrate_tau63_ms"]) for row in valid_steps
                       if row.get("bitrate_tau63_ms") is not None]
        if valid_steps:
            observations.append(
                f"For {mode.upper()} complexity transitions, reportable τ63 "
                f"medians were {_fmt(_median(qp_values), 0)} ms for QPY "
                f"({len(qp_values)}/{len(valid_steps)} transitions) and "
                f"{_fmt(_median(rate_values), 0)} ms for P-frame bitrate "
                f"({len(rate_values)}/{len(valid_steps)}).")

    half_rate = [row for row in drops if row.get("region") == "half_rate"]
    for mode in sorted({str(row["mode"]) for row in half_rate}):
        group = [row for row in half_rate if row.get("mode") == mode]
        observations.append(
            f"During the explicit 30→15 fps interval, {mode.upper()} delivered "
            f"a median {_fmt(_group_median(group, 'region_bitrate_ratio'), 2)}× "
            f"its pre-intervention bitrate; the first following P-frames were "
            f"{_fmt(_group_median(group, 'post_size_vs_baseline_median'), 2)}× "
            f"baseline size with median ΔQPY "
            f"{_fmt(_group_median(group, 'post_qp_delta_median'), 1)}.")

    hard_start = [row for row in summaries if row.get("scenario") == "hard_start"]
    if hard_start:
        for mode in sorted({str(row["mode"]) for row in hard_start}):
            group = [row for row in hard_start if row.get("mode") == mode]
            observations.append(
                f"At hard start, {mode.upper()} first I-frames were a median "
                f"{_fmt(_group_median(group, 'startup_i_vs_steady_i_ratio'), 2)}× "
                f"the later I-frame size in the matching HIGH segment.")
    return observations


def _finding_lines(manifest: Dict[str, Any], summaries: Sequence[Dict[str, Any]],
                   steps: Sequence[Dict[str, Any]],
                   drops: Sequence[Dict[str, Any]]) -> List[str]:
    target = float(manifest["global"]["bitrate_bps"])
    lines = [
        "## Key findings", "",
        ("These are descriptive summaries of this result root. Medians prevent "
         "one repeat from dominating; detailed repeat-level values remain in the "
         "tables below and in the CSV files."), "",
        "### Executive observations", "",
    ]
    lines.extend(
        f"- {observation}"
        for observation in _executive_observations(
            manifest, summaries, steps, drops))
    lines += [
        "", "### Overall rate-control behavior", "",
        "| Mode | Runs | Median mean rate / target | Median maximum backlog | Median P-frame QPY | Median I/P size ratio |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for mode in sorted({str(row["mode"]) for row in summaries}):
        group = [row for row in summaries if row["mode"] == mode]
        mean_rates = [float(row["mean_bitrate_bps"]) / target
                      for row in group if row.get("mean_bitrate_bps") is not None]
        lines.append(
            f"| {mode.upper()} | {len(group)} | {_fmt(_median(mean_rates), 2)}× | "
            f"{_fmt(_group_median(group, 'vbv_delay_max_ms'), 0)} ms | "
            f"{_fmt(_group_median(group, 'p_median_qp_avg'), 1)} | "
            f"{_fmt(_group_median(group, 'i_p_size_ratio'), 2)}× |")

    profiles = sorted({str(row["qp_profile"]) for row in summaries})
    lines += [
        "", "### Complexity-step response", "",
        ("τ63 is the first 63.2% crossing after a scheduled complexity change. "
         "Coverage is shown because noisy or unsettled transitions intentionally "
         "remain unreported rather than being forced into a number."), "",
        "| Mode / QP profile | Direction | QP τ63 median (coverage) | P-bitrate τ63 median (coverage) |",
        "|---|---|---:|---:|",
    ]
    for mode in sorted({str(row["mode"]) for row in steps}):
        for profile in profiles:
            for direction in ("up", "down"):
                group = [row for row in steps
                         if row["mode"] == mode and row["qp_profile"] == profile
                         and row["direction"] == direction]
                if not group:
                    continue
                qp_values = [float(row["qp_tau63_ms"]) for row in group
                             if row.get("qp_tau63_ms") is not None]
                rate_values = [float(row["bitrate_tau63_ms"]) for row in group
                               if row.get("bitrate_tau63_ms") is not None]
                lines.append(
                    f"| {mode.upper()} / {profile} | {direction} | "
                    f"{_fmt(_median(qp_values), 0)} ms ({len(qp_values)}/{len(group)}) | "
                    f"{_fmt(_median(rate_values), 0)} ms ({len(rate_values)}/{len(group)}) |")

    half_rate = [row for row in drops if row.get("region") == "half_rate"]
    if half_rate:
        lines += [
            "", "### Four-second 30→15 fps omission interval", "",
            ("The source remains on the configured PTS timeline while every other "
             "input is omitted. Ratios below compare the intervention and its "
             "first following P-frames with the pre-intervention baseline."), "",
            "| Mode / QP profile | Delivered bitrate / baseline | Following P size / baseline | Following P ΔQPY |",
            "|---|---:|---:|---:|",
        ]
        for mode in sorted({str(row["mode"]) for row in half_rate}):
            for profile in profiles:
                group = [row for row in half_rate
                         if row["mode"] == mode and row["qp_profile"] == profile]
                if not group:
                    continue
                lines.append(
                    f"| {mode.upper()} / {profile} | "
                    f"{_fmt(_group_median(group, 'region_bitrate_ratio'), 2)}× | "
                    f"{_fmt(_group_median(group, 'post_size_vs_baseline_median'), 2)}× | "
                    f"{_fmt(_group_median(group, 'post_qp_delta_median'), 1)} |")

    hard_start = [row for row in summaries if row.get("scenario") == "hard_start"]
    if hard_start:
        lines += [
            "", "### Hard-start behavior", "",
            ("These cases begin at HIGH complexity at frame zero, switch to LOW, "
             "then return to HIGH. The first/steady-I ratio isolates startup from "
             "a later matching HIGH segment."), "",
            "| Mode / QP profile | First I / later HIGH I | Median I/P size | Startup QP convergence frame |",
            "|---|---:|---:|---:|",
        ]
        for mode in sorted({str(row["mode"]) for row in hard_start}):
            for profile in profiles:
                group = [row for row in hard_start
                         if row["mode"] == mode and row["qp_profile"] == profile]
                if not group:
                    continue
                lines.append(
                    f"| {mode.upper()} / {profile} | "
                    f"{_fmt(_group_median(group, 'startup_i_vs_steady_i_ratio'), 2)}× | "
                    f"{_fmt(_group_median(group, 'i_p_size_ratio'), 2)}× | "
                    f"{_fmt(_group_median(group, 'startup_qp_convergence_frame'), 0)} |")
    lines.append("")
    return lines


def _case_plot_note(summary: Dict[str, Any], target_bps: float) -> str:
    rate_ratio = (_safe_ratio(summary.get("mean_bitrate_bps"), target_bps)
                  if target_bps else None)
    subject = (f"{int(summary['repeat_count'])}-repeat average:"
               if int(summary.get("repeat_count", 1)) > 1 else "This run:")
    return (
        f"{subject} mean rate {_fmt(rate_ratio, 2)}× target; maximum reconstructed backlog "
        f"{_fmt(summary.get('vbv_delay_max_ms'), 0)} ms; P-frame median QPY "
        f"{_fmt(summary.get('p_median_qp_avg'), 1)}; I/P size ratio "
        f"{_fmt(summary.get('i_p_size_ratio'), 2)}×."
    )


def _write_report(path: Path, manifest: Dict[str, Any], summaries: List[Dict[str, Any]],
                  steps: List[Dict[str, Any]], drops: List[Dict[str, Any]],
                  selected_count: int, missing_cases: Sequence[str],
                  invalid_cases: Sequence[Dict[str, str]],
                  plot_summaries: Optional[Sequence[Dict[str, Any]]] = None) -> None:
    lines = [
        "# Encoder rate-control probe", "",
        f"Generated: {_utc_now()}", "",
        f"Codec: `{manifest['global']['codec']}` (`{manifest['global']['mime']}`)",
        f"Operating point: {manifest['global']['resolution']} at {manifest['global']['fps']} fps; target {manifest['global']['bitrate']}; GOP {manifest['global']['i_frame_interval']} s",
        f"Analyzed runs: {len(summaries)}/{selected_count}; missing: {len(missing_cases)}; invalid: {len(invalid_cases)}",
        "",
    ]
    if invalid_cases:
        lines += ["## Invalid runs excluded", ""]
        for item in invalid_cases:
            lines.append(f"- `{item['case_id']}`: {item['reason']}")
        lines.append("")
    lines += _scenario_design_lines(manifest)
    lines += _finding_lines(manifest, summaries, steps, drops)
    lines += ["## Detailed run summary", "",
              "| Case | Mean Mbps | Feed throughput fps | Max VBV ms | I/P size | First/steady I | Startup QP/size convergence frame | QP range | QP-bound status | Missing interior/trailing |",
              "|---|---:|---:|---:|---:|---:|---:|---|---|---:|"]
    for s in summaries:
        qp_range = f"{_fmt(s.get('qp_observed_min'))}–{_fmt(s.get('qp_observed_max'))}"
        lines.append(
            f"| {s['case_id']} | {_fmt((s.get('mean_bitrate_bps') or 0)/1e6, 2)} | "
            f"{_fmt(s.get('feed_achieved_fps'), 2)} | "
            f"{_fmt(s.get('vbv_delay_max_ms'))} | {_fmt(s.get('i_p_size_ratio'), 2)} | "
            f"{_fmt(s.get('startup_i_vs_steady_i_ratio'), 2)} | "
            f"{_fmt(s.get('startup_qp_convergence_frame'), 0)}/{_fmt(s.get('startup_size_convergence_frame'), 0)} | "
            f"{qp_range} | {s.get('qp_bounds_status')} | "
            f"{s.get('unexpected_missing_interior_count', 0)}/{s.get('unexpected_missing_trailing_count', 0)} |"
        )
    lines += ["", "## Complexity-step response", "",
              "| Case | Step | QP τ63 ms | bitrate τ63 ms | QP pre→steady | Mbps pre→steady |",
              "|---|---|---:|---:|---:|---:|"]
    for s in steps:
        lines.append(
            f"| {s['case_id']} | {s['from_complexity']}→{s['to_complexity']} @{s['start_frame']} | "
            f"{_fmt(s.get('qp_tau63_ms'))} | {_fmt(s.get('bitrate_tau63_ms'))} | "
            f"{_fmt(s.get('pre_qp_median'))}→{_fmt(s.get('steady_qp_median'))} | "
            f"{_fmt((s.get('pre_bitrate_median_bps') or 0)/1e6, 2)}→"
            f"{_fmt((s.get('steady_bitrate_median_bps') or 0)/1e6, 2)} |"
        )
    lines += ["", "## Input-omission response", "",
              "| Case | Region | Missing/expected | Region/base bitrate | Post size/base median/max | ΔQP median | Max PTS gap ms | Max VBV ms |",
              "|---|---|---:|---:|---:|---:|---:|---:|"]
    for d in drops:
        lines.append(
            f"| {d['case_id']} | {d['region']} | {d['observed_missing_count']}/{d['expected_drop_count']} | "
            f"{_fmt(d.get('region_bitrate_ratio'), 2)} | "
            f"{_fmt(d.get('post_size_vs_baseline_median'), 2)}/{_fmt(d.get('post_size_vs_baseline_max'), 2)} | "
            f"{_fmt(d.get('post_qp_delta_median'))} | {_fmt(d.get('max_pts_gap_ms'))} | "
            f"{_fmt(d.get('max_vbv_delay_ms'))} |"
        )
    qp_failures = [s for s in summaries if s.get("qp_error")]
    if qp_failures:
        lines += ["", "## QP extraction/alignment failures", ""]
        for item in qp_failures:
            lines.append(f"- `{item['case_id']}`: {item['qp_error']}")
    published_plots = list(plot_summaries if plot_summaries is not None else summaries)
    case_plots = [
        str(summary["case_id"])
        for summary in published_plots
        if (path.parent / "plots" / f"{summary['case_id']}.png").is_file()
    ]
    if case_plots:
        by_case_summary = {str(summary["case_id"]): summary
                           for summary in published_plots}
        target_bps = float(manifest["global"]["bitrate_bps"])
        scenario_labels = {
            "dynamics": "Dynamics", "hard_start": "Hard Start",
            "drops": "Input Omissions",
        }
        profile_labels = {
            "unbounded": "Unbounded QP", "min27": "QP minimum 27",
            "max38": "QP maximum 38", "jcq_27_38": "QP 27–38",
            "tight_30_35": "QP 30–35",
        }
        lines += ["", "## Per-configuration plots", "",
                  ("By default, aligned frame traces are arithmetic means across "
                   "repeats and shaded bands show the repeat range. Plots are "
                   "grouped by scenario, QP profile, and rate-control mode. Use "
                   "`--plot-repeats` when individual repeat diagnostics are needed."), ""]
        previous_group: Optional[Tuple[str, str, str]] = None
        for case_id in case_plots:
            summary = by_case_summary[case_id]
            group = (
                str(summary["scenario"]), str(summary["qp_profile"]),
                str(summary["mode"]),
            )
            if previous_group is None or group[0] != previous_group[0]:
                lines += [f"### {scenario_labels.get(group[0], group[0].title())}", ""]
            if previous_group is None or group != previous_group:
                lines += [
                    f"#### {profile_labels.get(group[1], group[1])} — {group[2].upper()}", ""]
            repeat_count = int(summary.get("repeat_count", 1))
            item_label = (f"{repeat_count}-repeat average"
                          if repeat_count > 1 and int(summary.get("repeat", 0)) == 0
                          else f"Repeat {summary['repeat']}")
            lines += [
                f"##### {item_label}: `{case_id}`", "",
                _case_plot_note(summary, target_bps), "",
                f"![{case_id}](plots/{case_id}.png)", "",
            ]
            previous_group = group
    lines += [
        "", "## Interpretation guardrails", "",
        "- `VBV delay` is a zero-floor leaky-bucket backlog drained at the configured target bitrate.",
        "- `τ63` excludes I-frames and uses the first 63.2% crossing between the two-second pre-step median and the final steady median; segments under three seconds or unsettled signals are `n/a`.",
        "- Bitrate `τ63` uses a 200 ms trailing window, so values near that duration are measurement-limited rather than precise internal controller constants.",
        "- QP comes from `qpextract` for HEVC and is retained only after frame-count/index and NAL-sidecar PTS/size alignment checks. Missing QP never invalidates frame-size, timestamp, overshoot, or drop evidence.",
        "- Feed wall duration and achieved throughput are diagnostics only; synthetic input is submitted as fast as the codec accepts it and feed performance does not invalidate a run.",
        "- Every codec must use the same generated stimulus bytes. Per-codec noise scaling is intentionally forbidden.",
        "", "Raw evidence: `runs.csv`, `steps.csv`, `drops.csv`, `frames.csv`, `plots/`, and `analysis.json`.", "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")
    try:
        from encoder_rc_probe_report import write_html_report
        write_html_report(path)
    except (ImportError, OSError, ValueError) as exc:
        print(f"warning: failed to write self-contained HTML report: {exc}",
              file=sys.stderr)


def _feed_throughput(
        case_dir: Path, frame_count: int) -> Dict[str, Optional[float]]:
    """Report host/device feed throughput without treating it as a rate-control axis."""
    starts: List[dt.datetime] = []
    ends: List[dt.datetime] = []
    reported_wall_us: List[int] = []
    timestamp_re = re.compile(r"^\s*(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")
    for path in case_dir.glob("*.android_logcat.txt"):
        try:
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                match = timestamp_re.match(line)
                if not match:
                    continue
                stamp = dt.datetime.strptime(
                    "2000-" + match.group(1), "%Y-%m-%d %H:%M:%S.%f")
                timing_match = re.search(
                    r"Input pacing wall_duration_us=(\d+)", line)
                if timing_match:
                    reported_wall_us.append(int(timing_match.group(1)))
                if "InputFeeder started" in line:
                    starts.append(stamp)
                elif f"Queued EOS at frame {frame_count}" in line:
                    ends.append(stamp)
        except OSError:
            continue
    if reported_wall_us:
        wall_s = reported_wall_us[-1] / 1_000_000.0
        return {
            "feed_wall_duration_s": wall_s,
            "feed_achieved_fps": frame_count / wall_s if wall_s > 0 else None,
        }
    if not starts or not ends:
        return {"feed_wall_duration_s": None, "feed_achieved_fps": None}
    start = min(starts)
    end_candidates = [value for value in ends if value >= start]
    if not end_candidates:
        return {"feed_wall_duration_s": None, "feed_achieved_fps": None}
    wall_s = (max(end_candidates) - start).total_seconds()
    return {
        "feed_wall_duration_s": wall_s,
        "feed_achieved_fps": frame_count / wall_s if wall_s > 0 else None,
    }


def _analyze_case(
        case: Dict[str, Any], root: Path, manifest: Dict[str, Any],
        analysis_root: Path, qpextract: Optional[str], ffmpeg: Optional[str],
) -> Tuple[Dict[str, Any], List[Dict[str, Any]], List[Dict[str, Any]],
           List[Dict[str, Any]]]:
    """Validate and analyze one case without touching shared output files."""
    case_dir = root / "raw" / case["id"]
    result_path = _find_case_result(case_dir, case["id"])
    if not result_path:
        raise FileNotFoundError("result statistics are missing")

    config_path = root / case["config"]
    if (not config_path.is_file()
            or _sha256(config_path) != case.get("config_sha256")):
        raise RuntimeError("config hash mismatch")
    status = json.loads((case_dir / "status.json").read_text(encoding="utf-8"))
    status_artifact_error = _status_artifact_error(status)
    if status.get("returncode") != 0 or status_artifact_error is not None:
        raise RuntimeError(
            f"run artifacts are not successful: returncode="
            f"{status.get('returncode')} error={status_artifact_error}")
    if not _result_is_complete(result_path, case):
        raise RuntimeError("stats frame set is incomplete, duplicated, or reordered")
    result_body = json.loads(result_path.read_text(encoding="utf-8"))
    raw_frames = [dict(frame) for frame in result_body.get("frames", [])
                  if int(frame.get("size", 0)) > 0]
    raw_frames.sort(key=lambda frame: int(frame.get("frame", 0)))
    alignment_error = _validate_stats_sidecar(
        raw_frames, _find_nal_sidecar(result_path, case["id"]))
    if alignment_error:
        raise RuntimeError(alignment_error)
    if _find_encoded(result_path, result_body) is None:
        raise RuntimeError("encoded media is missing")
    frame_types = json.loads(
        (case_dir / "frame_types.json").read_text(encoding="utf-8"))
    if (int(frame_types.get("observed_frame_count", -1))
            != int(frame_types.get("expected_frame_count", -2))
            or any(name not in {"I", "P"}
                   for name in frame_types.get("type_counts", {}))):
        raise RuntimeError(f"invalid coded frame types: {frame_types}")

    summary, rows, case_steps, case_drops = _analyze_one(
        case, result_path, manifest["global"], analysis_root, qpextract, ffmpeg)
    for phase in ("pre", "post"):
        provenance_path = case_dir / f"device_{phase}.json"
        try:
            provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
            thermal = provenance.get("thermal", {})
            summary[f"thermal_{phase}_status"] = thermal.get("status")
            summary[f"thermal_{phase}_max_soc_c"] = thermal.get("max_soc_c")
            summary[f"thermal_{phase}_max_c"] = _thermal_max_c(thermal)
            summary[f"thermal_{phase}_sensor_basis"] = thermal.get(
                "thermal_sensor_basis", "soc" if thermal.get("max_soc_c") is not None else None)
        except Exception:
            summary[f"thermal_{phase}_status"] = None
            summary[f"thermal_{phase}_max_soc_c"] = None
            summary[f"thermal_{phase}_max_c"] = None
            summary[f"thermal_{phase}_sensor_basis"] = None
    throughput = _feed_throughput(
        case_dir, int(case["scenario_def"]["frames"]))
    summary.update(throughput)
    threshold = float(status.get("max_soc_temp_c", 70.0))
    summary["thermal_gate_ignored"] = bool(status.get("thermal_gate_ignored", False))
    summary["thermal_valid"] = bool(
        summary.get("thermal_pre_status") == 0
        and summary.get("thermal_post_status") == 0
        and summary.get("thermal_pre_max_c") is not None
        and summary.get("thermal_post_max_c") is not None
        and summary["thermal_pre_max_c"] <= threshold
        and summary["thermal_post_max_c"] <= threshold)
    summary["scientifically_valid"] = bool(summary["thermal_valid"])
    return summary, rows, case_steps, case_drops


def _analysis_jobs(value: Any) -> int:
    jobs = int(value)
    if jobs < 1:
        raise ValueError("jobs must be at least 1")
    return jobs


def _analysis_progress(done: int, total: int, case_id: str) -> None:
    percent = 100.0 * done / max(1, total)
    filled = min(20, int(percent / 5.0))
    meter = "#" * filled + "-" * (20 - filled)
    print(
        f"analyze [{meter}] {done}/{total} {percent:5.1f}% {case_id}",
        flush=True)


def analyze(args: argparse.Namespace) -> None:
    if float(args.smoothing_seconds) < 0:
        raise ValueError("smoothing-seconds must be non-negative")
    manifest_path = _resolve_manifest_path(args.manifest)
    root = manifest_path.parent
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    analysis_root = root / "analysis"
    analysis_root.mkdir(parents=True, exist_ok=True)
    qpextract = shutil.which(args.qpextract) if args.qpextract else shutil.which("qpextract")
    ffmpeg = shutil.which(args.ffmpeg) if args.ffmpeg else shutil.which("ffmpeg")

    summaries: List[Dict[str, Any]] = []
    frame_rows: List[Dict[str, Any]] = []
    steps: List[Dict[str, Any]] = []
    drops: List[Dict[str, Any]] = []
    missing_cases: List[str] = []
    invalid_cases: List[Dict[str, str]] = []
    selected = [c for c in manifest["cases"] if re.search(args.case_regex, c["id"])]
    jobs = min(_analysis_jobs(args.jobs), max(1, len(selected)))
    print(f"analyzing {len(selected)} cases with {jobs} worker(s)", flush=True)
    ordered_results: Dict[
        int, Tuple[Dict[str, Any], List[Dict[str, Any]],
                   List[Dict[str, Any]], List[Dict[str, Any]]]
    ] = {}
    with concurrent.futures.ThreadPoolExecutor(
            max_workers=jobs, thread_name_prefix="rc-analysis") as executor:
        futures = {
            executor.submit(
                _analyze_case, case, root, manifest, analysis_root,
                qpextract, ffmpeg): (index, case)
            for index, case in enumerate(selected)
        }
        completed = 0
        for future in concurrent.futures.as_completed(futures):
            index, case = futures[future]
            try:
                ordered_results[index] = future.result()
            except FileNotFoundError:
                missing_cases.append(case["id"])
            except Exception as exc:
                invalid_cases.append({"case_id": case["id"], "reason": str(exc)})
            completed += 1
            _analysis_progress(completed, len(selected), case["id"])

    # Merge and plot in manifest order so --jobs never changes published data.
    for index, case in enumerate(selected):
        result = ordered_results.get(index)
        if result is None:
            continue
        summary, rows, case_steps, case_drops = result
        summaries.append(summary)
        frame_rows.extend(rows)
        steps.extend(case_steps)
        drops.extend(case_drops)
        if not args.no_plots and args.plot_repeats:
            _plot_case(
                rows, case, analysis_root / "plots" / f"{case['id']}.png",
                manifest["global"]["bitrate_bps"], manifest["global"]["fps"],
                smoothing_seconds=float(args.smoothing_seconds),
                raw_qp_overlay=bool(args.raw_qp_overlay))

    # Publish semantically; randomized manifest order remains the execution order.
    summaries.sort(key=_case_sort_key)
    frame_rows.sort(key=lambda row: _case_sort_key(row) + (int(row.get("frame", 0)),))
    steps.sort(key=lambda row: _case_sort_key(row) + (int(row.get("start_frame", 0)),))
    drops.sort(key=lambda row: _case_sort_key(row) + (int(row.get("region_start", 0)),))
    missing_cases.sort(key=lambda case_id: _case_sort_key(
        next((case for case in selected if case["id"] == case_id), {"id": case_id})))
    invalid_cases.sort(key=lambda item: _case_sort_key(
        next((case for case in selected if case["id"] == item["case_id"]),
             {"id": item["case_id"]})))

    plot_summaries: List[Dict[str, Any]] = []
    if not args.no_plots:
        if args.plot_repeats:
            plot_summaries = list(summaries)
        else:
            for case, average_summary, average_rows in _repeat_average_plot_inputs(
                    selected, summaries, frame_rows):
                _plot_case(
                    average_rows, case,
                    analysis_root / "plots" / f"{case['id']}.png",
                    manifest["global"]["bitrate_bps"],
                    manifest["global"]["fps"],
                    smoothing_seconds=float(args.smoothing_seconds),
                    raw_qp_overlay=bool(args.raw_qp_overlay))
                plot_summaries.append(average_summary)
        keep = {f"{summary['case_id']}.png" for summary in plot_summaries}
        plot_dir = analysis_root / "plots"
        if plot_dir.is_dir():
            for stale in plot_dir.glob("*.png"):
                if stale.name not in keep:
                    stale.unlink()

    _write_csv(analysis_root / "runs.csv", summaries)
    _write_csv(analysis_root / "frames.csv", frame_rows)
    _write_csv(analysis_root / "steps.csv", steps)
    _write_csv(analysis_root / "drops.csv", drops)
    body = {
        "schema_version": SCHEMA_VERSION,
        "analyzed_utc": _utc_now(),
        "manifest_sha256": _sha256(manifest_path),
        "analyzer_sha256": _sha256(Path(__file__)),
        "qpextract": qpextract,
        "ffmpeg": ffmpeg,
        "jobs": jobs,
        "missing_cases": missing_cases,
        "invalid_cases": invalid_cases,
        "runs": summaries,
        "steps": steps,
        "drops": drops,
        "plot_repeats": bool(args.plot_repeats),
        "plot_count": len(plot_summaries),
    }
    (analysis_root / "analysis.json").write_text(
        json.dumps(body, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    _write_report(
        analysis_root / "REPORT.md", manifest, summaries, steps, drops,
        len(selected), missing_cases, invalid_cases, plot_summaries)
    print(f"analyzed {len(summaries)}/{len(selected)} cases: {analysis_root / 'REPORT.md'}")
    if (missing_cases or invalid_cases) and not args.allow_missing:
        if invalid_cases:
            raise RuntimeError(
                f"invalid {len(invalid_cases)} case(s); first: {invalid_cases[0]}")
        raise RuntimeError(f"missing {len(missing_cases)} case(s): {', '.join(missing_cases[:5])}")


def full(args: argparse.Namespace) -> Path:
    """Generate, run, analyze, and plot one complete probe matrix."""
    args.serial = _resolve_serial(args.serial)
    manifest_path = generate(args)
    workflow = dict(vars(args))
    workflow.update({
        "manifest": str(manifest_path),
        "case_regex": ".*",
        "limit": 0,
    })
    workflow_args = argparse.Namespace(**workflow)
    run_error: Optional[RuntimeError] = None
    try:
        run_matrix(workflow_args)
    except RuntimeError as exc:
        # Completed matrices can contain execution or artifact failures. Analyze
        # the available subset and write REPORT.md before returning the failure.
        # Preflight/setup errors still fail immediately.
        if not re.match(r"^\d+ case\(s\) failed; first:", str(exc)):
            raise
        run_error = exc
    analysis_args = argparse.Namespace(**vars(workflow_args))
    if run_error is not None:
        # A failed case is excluded, not allowed to suppress later phases.
        analysis_args.allow_missing = True
    analyze(analysis_args)
    if run_error is not None:
        print(f"warning: continuing with valid cases only: {run_error}",
              file=sys.stderr)
    if not args.no_plots:
        plot_script = Path(__file__).with_name("encoder_rc_probe_plots.py")
        if not plot_script.is_file():
            raise RuntimeError(f"aggregate plot script not found: {plot_script}")
        _run_checked([
            sys.executable, str(plot_script), str(Path(args.output).resolve()),
            "--smoothing-seconds", str(args.smoothing_seconds),
        ])
    report = Path(args.output).resolve() / "analysis" / "REPORT.md"
    print(f"complete: {report}")
    if run_error is not None and not args.allow_missing:
        raise RuntimeError(
            f"workflow completed with failed cases excluded; see {report}; "
            f"{run_error}")
    return manifest_path


def _add_generate_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "output", metavar="OUTPUT",
        help="result folder; manifest is written to OUTPUT/manifest.json")
    parser.add_argument(
        "--codec", default=DEFAULT_CODEC,
        help="Android MediaCodec component name")
    parser.add_argument("--mime", default=DEFAULT_MIME, help="encoded MIME type")
    parser.add_argument(
        "--resolution", default=DEFAULT_RESOLUTION,
        help="input dimensions as WIDTHxHEIGHT")
    parser.add_argument("--fps", type=int, default=DEFAULT_FPS, help="input frame rate")
    parser.add_argument(
        "--bitrate", default=DEFAULT_BITRATE,
        help="target bitrate, for example 8M or 250k")
    parser.add_argument(
        "--i-frame-interval", type=int, default=1,
        help="keyframe interval in seconds")
    parser.add_argument(
        "--modes", default="cbr,vbr",
        help="comma-separated rate-control modes")
    parser.add_argument(
        "--qp-profiles", default=",".join(QP_PROFILES),
        help="comma-separated static QP profile names")
    parser.add_argument(
        "--scenarios", default="dynamics,hard_start,drops",
        help="comma-separated scenario names")
    parser.add_argument(
        "--repeats", type=int, default=DEFAULT_REPEATS,
        help="repetitions per mode/profile/scenario combination")
    parser.add_argument(
        "--order-seed", type=int, default=0x454E4341,
        help="deterministic case-order shuffle seed")


class _HelpFormatter(argparse.ArgumentDefaultsHelpFormatter,
                     argparse.RawDescriptionHelpFormatter):
    pass


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Generate, run, and analyze a deterministic encoder RC probe.",
        epilog="""Typical workflow:
  encoder_rc_probe.py full OUTPUT [generator and runner options]

OUTPUT is a result folder. The generated matrix description is always
OUTPUT/manifest.json, raw case evidence is under OUTPUT/raw/, and analysis is
written under OUTPUT/analysis/. The run and analyze commands accept either
OUTPUT or OUTPUT/manifest.json.

Combine multiple result roots with scripts/encoder_rc_probe_compare.py; repeat
--group LABEL=OUTPUT (or LABEL=OUTPUT/manifest.json), reusing a label for
multiple runs of the same hardware/project. Missing per-root analyses are
created automatically.
""",
        formatter_class=_HelpFormatter,
    )
    sub = p.add_subparsers(dest="command", required=True)

    c = sub.add_parser(
        "check-encapp",
        help="verify that the installed Encapp satisfies the probe contract",
        description="Verify the already-installed Encapp before running a matrix.",
        formatter_class=_HelpFormatter)
    c.add_argument(
        "--serial", help="ADB serial; auto-selected when exactly one device is authorized")
    c.set_defaults(func=check_encapp)

    g = sub.add_parser(
        "generate",
        help="create OUTPUT/manifest.json and immutable case pbtxt files",
        description="Generate a probe matrix without running it.",
        epilog="Writes OUTPUT/manifest.json and OUTPUT/configs/*.pbtxt.",
        formatter_class=_HelpFormatter)
    _add_generate_arguments(g)
    g.set_defaults(func=generate)

    r = sub.add_parser(
        "run",
        help="execute cases from a generated result folder or manifest",
        description="Run the generated matrix and collect evidence under OUTPUT/raw/.",
        epilog="MANIFEST may be either OUTPUT or OUTPUT/manifest.json.",
        formatter_class=_HelpFormatter)
    r.add_argument(
        "manifest", metavar="OUTPUT_OR_MANIFEST",
        help="result folder or its manifest.json")
    r.add_argument(
        "--serial", help="ADB serial; auto-selected when exactly one device is authorized")
    r.add_argument(
        "--encapp-script", default=str(Path(__file__).with_name("encapp.py")),
        help="checkout-native Encapp CLI path")
    r.add_argument(
        "--encapp-python", default=sys.executable,
        help="Python interpreter used to invoke the Encapp CLI")
    r.add_argument("--case-regex", default=".*", help="run only matching case IDs")
    r.add_argument("--limit", type=int, default=0, help="maximum cases; 0 means all")
    r.add_argument(
        "--resume", action=argparse.BooleanOptionalAction, default=True,
        help="skip already valid cases")
    r.add_argument(
        "--cold-process", action=argparse.BooleanOptionalAction, default=True,
        help="force-stop Encapp before each case")
    r.add_argument(
        "--keep-going", action=argparse.BooleanOptionalAction, default=True,
        help="continue after an execution or artifact failure")
    r.add_argument(
        "--cooldown-s", type=float, default=5.0,
        help="delay between cases")
    r.add_argument(
        "--ignore-thermal", action="store_true",
        help=("bypass the pre-case thermal wait; telemetry is still recorded "
              "and used for scientific validity"))
    r.add_argument(
        "--max-soc-temp-c", type=float, default=70.0,
        help="maximum accepted active SoC temperature")
    r.add_argument(
        "--thermal-wait-timeout-s", type=int, default=900,
        help="maximum wait for a thermally valid starting state")
    r.set_defaults(func=run_matrix)

    a = sub.add_parser(
        "analyze",
        help="analyze valid collected cases from a result folder or manifest",
        description="Analyze collected evidence and write OUTPUT/analysis/.",
        epilog="""MANIFEST may be either OUTPUT or OUTPUT/manifest.json.

Outputs include analysis/REPORT.md, analysis/analysis.json, CSV tables, and
per-configuration plots (repeat-averaged by default). Invalid or missing cases
are listed in the report. By default they also make the command exit nonzero;
--allow-missing analyzes the valid subset successfully.
""",
        formatter_class=_HelpFormatter)
    a.add_argument(
        "manifest", metavar="OUTPUT_OR_MANIFEST",
        help="result folder or its manifest.json")
    a.add_argument("--case-regex", default=".*", help="analyze only matching case IDs")
    a.add_argument("--qpextract", default="qpextract", help="HEVC QP extractor executable")
    a.add_argument("--ffmpeg", default="ffmpeg", help="FFmpeg executable")
    a.add_argument(
        "--jobs", type=int, default=min(4, os.cpu_count() or 1),
        help="parallel case-analysis workers; plotting remains serial")
    a.add_argument(
        "--smoothing-seconds", type=float, default=1.0,
        help="trailing PTS-time smoothing window for per-case QP plots; 0 disables")
    a.add_argument(
        "--raw-qp-overlay", action="store_true",
        help="overlay raw per-frame average QP at low opacity")
    a.add_argument(
        "--plot-repeats", action="store_true",
        help="plot every repeat separately instead of averaging aligned repeats")
    a.add_argument("--no-plots", action="store_true",
                   help="skip repeat-averaged configuration plots")
    a.add_argument(
        "--allow-missing", action="store_true",
        help="succeed after analyzing the valid subset")
    a.set_defaults(func=analyze)

    f = sub.add_parser(
        "full",
        help="generate, run, analyze, and plot one complete matrix",
        description="""Run the complete checkout-native workflow:
  1. Generate OUTPUT/manifest.json and case pbtxt files.
  2. Run every case and collect evidence under OUTPUT/raw/.
  3. Analyze valid evidence under OUTPUT/analysis/.
  4. Produce aggregate plots unless --no-plots is set.
""",
        epilog="""OUTPUT is a result folder, not a manifest filename.

Scientific warnings never stop later phases. If cases have execution or artifact
failures, full analyzes and plots the available subset before exiting nonzero;
--allow-missing accepts that subset. Re-running the same command resumes the
folder and retries only incomplete cases.
""",
        formatter_class=_HelpFormatter)
    _add_generate_arguments(f)
    f.add_argument(
        "--serial", help="ADB serial; auto-selected when exactly one device is authorized")
    f.add_argument(
        "--encapp-script", default=str(Path(__file__).with_name("encapp.py")),
        help="checkout-native Encapp CLI path")
    f.add_argument(
        "--encapp-python", default=sys.executable,
        help="Python interpreter used to invoke the Encapp CLI")
    f.add_argument(
        "--resume", action=argparse.BooleanOptionalAction, default=True,
        help="skip cases with complete verified artifacts")
    f.add_argument(
        "--cold-process", action=argparse.BooleanOptionalAction, default=True,
        help="force-stop Encapp before each case")
    f.add_argument(
        "--keep-going", action=argparse.BooleanOptionalAction, default=True,
        help="run the remaining cases after an execution or artifact failure")
    f.add_argument("--cooldown-s", type=float, default=5.0, help="delay between cases")
    f.add_argument(
        "--ignore-thermal", action="store_true",
        help=("bypass the pre-case thermal wait; telemetry is still recorded "
              "and used for scientific validity"))
    f.add_argument(
        "--max-soc-temp-c", type=float, default=70.0,
        help="maximum accepted active SoC temperature")
    f.add_argument(
        "--thermal-wait-timeout-s", type=int, default=900,
        help="maximum wait for a thermally valid starting state")
    f.add_argument("--qpextract", default="qpextract", help="HEVC QP extractor executable")
    f.add_argument("--ffmpeg", default="ffmpeg", help="FFmpeg executable")
    f.add_argument(
        "--jobs", type=int, default=min(4, os.cpu_count() or 1),
        help="parallel case-analysis workers; plotting remains serial")
    f.add_argument(
        "--smoothing-seconds", type=float, default=1.0,
        help="trailing PTS-time smoothing window for per-case QP plots; 0 disables")
    f.add_argument(
        "--raw-qp-overlay", action="store_true",
        help="overlay raw per-frame average QP at low opacity")
    f.add_argument(
        "--plot-repeats", action="store_true",
        help="plot every repeat separately instead of averaging aligned repeats")
    f.add_argument(
        "--no-plots", action="store_true",
        help="skip per-case and aggregate plots")
    f.add_argument(
        "--allow-missing", action="store_true",
        help="analyze and plot the valid subset when cases fail")
    f.set_defaults(func=full)
    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        result = args.func(args)
        if isinstance(result, Path):
            return 0
        return 0
    except (RuntimeError, ValueError, subprocess.SubprocessError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
