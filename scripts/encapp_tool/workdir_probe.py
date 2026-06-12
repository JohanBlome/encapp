"""CLI-side workdir handshake.

The encapp app may not write to `/sdcard` — scoped storage on newer
Android versions, OEM permission quirks, app-internal-only sandboxes.
When the app falls back to internal storage, the CLI's hardcoded
`/sdcard` assumption breaks every push and pull.

This module discovers the app's actual workdir via a probe round-trip:

  1. CLI: `adb shell am start -e probe true com.facebook.encapp/.MainActivity`
  2. App: probes writability, writes its chosen workdir to
     /sdcard/encapp_workdir.txt, calls finish() without running tests.
  3. CLI: `adb shell cat /sdcard/encapp_workdir.txt` → that's the
     authoritative workdir for subsequent adb push/pull.

Result is cached per-(serial, app_version) so the round-trip happens
once per device per app upgrade, not once per run.
"""
import json
import logging
import os
import time
from typing import Callable, Optional, Tuple

log = logging.getLogger("encapp.workdir_probe")

PROBE_MARKER_REMOTE = "/sdcard/encapp_workdir.txt"
DEFAULT_TIMEOUT_SEC = 8.0
DEFAULT_POLL_INTERVAL_SEC = 0.25


class ProbeFailed(RuntimeError):
    """Raised when the probe round-trip cannot determine a workdir."""


def probe_workdir(
    serial: str,
    activity: str,
    run_cmd_fn: Callable[[str], Tuple[bool, str, str]],
    timeout_sec: float = DEFAULT_TIMEOUT_SEC,
    poll_interval_sec: float = DEFAULT_POLL_INTERVAL_SEC,
    _time_fn: Callable[[], float] = time.monotonic,
    _sleep_fn: Callable[[float], None] = time.sleep,
) -> str:
    """Launch the app in probe mode, wait for the marker, return the workdir.

    activity: e.g. "com.facebook.encapp/.MainActivity"
    run_cmd_fn: callback to run a shell command, returns (ok, stdout, stderr).
        In production this is encapp_tool.adb_cmds.run_cmd; in tests it's
        a mock so the module stays pure-Python and adb-free.
    """
    # Remove any stale marker so the poll only succeeds on a fresh write.
    run_cmd_fn(f"adb -s {serial} shell rm -f {PROBE_MARKER_REMOTE}")

    ok, _stdout, stderr = run_cmd_fn(
        f"adb -s {serial} shell am start -e probe true {activity}"
    )
    if not ok:
        raise ProbeFailed(f"am start failed: {stderr}")

    deadline = _time_fn() + timeout_sec
    last_err = ""
    while _time_fn() < deadline:
        ok, stdout, stderr = run_cmd_fn(
            f"adb -s {serial} shell cat {PROBE_MARKER_REMOTE}"
        )
        if ok and stdout.strip():
            workdir = stdout.strip()
            # Strip any trailing slash for consistency with the rest of
            # the CLI's path handling.
            return workdir.rstrip("/") or "/"
        last_err = stderr or stdout
        _sleep_fn(poll_interval_sec)
    raise ProbeFailed(
        f"timed out after {timeout_sec:.1f}s waiting for probe marker "
        f"at {PROBE_MARKER_REMOTE}; last adb error: {last_err!r}"
    )


def _cache_path(cache_dir: str, serial: str) -> str:
    return os.path.join(cache_dir, f".encapp_device_workdir.{serial}.json")


def get_or_probe_workdir(
    serial: str,
    activity: str,
    app_version: str,
    cache_dir: str,
    run_cmd_fn: Callable[[str], Tuple[bool, str, str]],
    refresh: bool = False,
    timeout_sec: float = DEFAULT_TIMEOUT_SEC,
) -> str:
    """Return the device workdir, hitting the on-disk cache when possible.

    Cache schema: `{"app_version": "<v>", "workdir": "<path>"}`.
    Cache is invalidated by an app_version mismatch (covers the "user
    just upgraded encapp" case) or by passing refresh=True (covers
    the manual `encapp install` flow that should always re-probe).
    """
    os.makedirs(cache_dir, exist_ok=True)
    cache_file = _cache_path(cache_dir, serial)
    if not refresh and os.path.exists(cache_file):
        try:
            with open(cache_file) as f:
                cached = json.load(f)
            if cached.get("app_version") == app_version and cached.get("workdir"):
                log.debug("workdir cache hit: %s", cached["workdir"])
                return cached["workdir"]
            log.debug("workdir cache stale (app_version %r != %r); reprobing",
                      cached.get("app_version"), app_version)
        except (OSError, json.JSONDecodeError) as e:
            log.warning("workdir cache unreadable, reprobing: %s", e)
    workdir = probe_workdir(serial, activity, run_cmd_fn, timeout_sec=timeout_sec)
    try:
        with open(cache_file, "w") as f:
            json.dump({"app_version": app_version, "workdir": workdir}, f)
    except OSError as e:
        log.warning("could not write workdir cache %s: %s", cache_file, e)
    return workdir


def invalidate_cache(cache_dir: str, serial: Optional[str] = None) -> None:
    """Drop the cached workdir for one device, or all devices when serial is None."""
    if serial is not None:
        p = _cache_path(cache_dir, serial)
        if os.path.exists(p):
            os.remove(p)
        return
    if not os.path.isdir(cache_dir):
        return
    for name in os.listdir(cache_dir):
        if name.startswith(".encapp_device_workdir.") and name.endswith(".json"):
            try:
                os.remove(os.path.join(cache_dir, name))
            except OSError:
                pass
