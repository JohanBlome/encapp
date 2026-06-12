"""Per-run JSON rollup of every test's verdict + key artifacts.

Written to ``<local_workdir>/run_summary.json`` at the end of every
encapp run. One file per session — machine-readable counterpart to the
human stdout summary block.

Schema (v1):

.. code-block:: json

    {
      "schema_version": 1,
      "session_id": "R1781289889_986e9d",
      "started_at_unix_ms": 1781289889123,
      "finished_at_unix_ms": 1781289895432,
      "device": {"serial": "...", "model": "...", "app_version": "..."},
      "total_tests": 24,
      "by_status": {"pass": 22, "fail": 1, "crash": 0, "timeout": 1,
                    "never_started": 0, "skipped": 0},
      "tests": [
        {
          "test_id": "h264_1mbps_johnny",
          "status": "pass",
          "reason": null,
          "artifacts": [
            {"kind": "stats", "path": "encapp_<uuid>.json", "bytes": 73483},
            {"kind": "video", "path": "encapp_<uuid>.mp4", "bytes": 1294181},
            {"kind": "log",   "path": "h264_1mbps_johnny.log", "bytes": 236}
          ]
        },
        ...
      ]
    }

Stable enough for downstream tools (CI, dashboards, bisection scripts)
to depend on. Version-bumped on breaking changes.
"""
import json
import os
from typing import Dict, List, Optional

SCHEMA_VERSION = 1
FILENAME = "run_summary.json"


def build_summary(
    session_id: str,
    verdicts,
    started_at_unix_ms: Optional[int] = None,
    finished_at_unix_ms: Optional[int] = None,
    device: Optional[Dict[str, str]] = None,
) -> dict:
    """Build the summary dict from a SessionVerdicts.

    Pure data — no I/O. Callers compose the device dict from whatever
    sources they have (adb getprop, encapp_tool.__version__, etc.).
    """
    # Late import: session_manifest depends on stdlib only, but keeping
    # this module's import cost zero is worth a deferred lookup.
    from encapp_tool import session_manifest

    by_status: Dict[str, int] = {s.value: 0 for s in session_manifest.Status}
    tests: List[dict] = []
    for tid, v in verdicts.tests.items():
        by_status[v.status.value] = by_status.get(v.status.value, 0) + 1
        tests.append({
            "test_id": tid,
            "status": v.status.value,
            "reason": v.reason,
            "artifacts": [
                {
                    "kind": a.get("kind"),
                    "path": a.get("path"),
                    "bytes": a.get("bytes"),
                }
                for a in v.artifacts
            ],
        })

    return {
        "schema_version": SCHEMA_VERSION,
        "session_id": session_id,
        "started_at_unix_ms": started_at_unix_ms,
        "finished_at_unix_ms": finished_at_unix_ms,
        "device": device or {},
        "total_tests": len(verdicts.tests),
        "by_status": by_status,
        "tests": tests,
    }


def write_run_summary(local_workdir: str, summary: dict) -> str:
    """Write summary to <local_workdir>/run_summary.json. Returns the path."""
    path = os.path.join(local_workdir, FILENAME)
    os.makedirs(local_workdir, exist_ok=True)
    with open(path, "w") as f:
        json.dump(summary, f, indent=2, sort_keys=False)
        f.write("\n")
    return path


def format_summary_line(summary: dict, report_path: Optional[str] = None) -> str:
    """One-line human summary: 'N/M passed. Failed: ... Full report: <path>'.

    Used for the final INFO log at the end of a run.
    """
    n_total = summary.get("total_tests", 0)
    n_pass = summary.get("by_status", {}).get("pass", 0)
    failed = [t["test_id"] for t in summary.get("tests", [])
              if t.get("status") != "pass"]
    parts = [f"{n_pass}/{n_total} passed"]
    if failed:
        # Trim to first 5 ids so the line stays readable on noisy suites.
        shown = failed[:5]
        more = "" if len(failed) <= 5 else f" (+{len(failed) - 5} more)"
        parts.append(f"Failed: {', '.join(shown)}{more}")
    if report_path:
        parts.append(f"Full report: {report_path}")
    return ". ".join(parts)
