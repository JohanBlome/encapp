"""Centralized logging setup for the encapp CLI.

Logger naming convention: every encapp CLI/internal module uses a
child logger under the `encapp.` root:

    encapp.run             — top-level run orchestration
    encapp.suite           — pbtxt parse / suite generation
    encapp.adb             — adb command wrapper
    encapp.workdir_probe   — workdir handshake
    encapp.manifest        — session manifest oracle
    encapp.ffutils         — ffmpeg helpers
    encapp.app_utils       — install / version / app lifecycle

CLI flags (defined in encapp.py's argparse):
    --log-level {DEBUG,INFO,WARNING,ERROR}   default INFO
    --log-file PATH                          default None (stderr only)
    --log-modules a,b,c                      override level per module

Legacy `-d` / `--debug` and `-q` / `--quiet` still work — they map to
DEBUG and WARNING respectively. The deprecation timeline is in the
design doc; for now they coexist.

Format is fixed for greppability:

    2026-06-12 14:23:01.123 INFO  encapp.run  Test foo passed (2.5s)
"""
import logging
import sys
from typing import Iterable, Optional

LOG_FMT = "%(asctime)s.%(msecs)03d %(levelname)-5s %(name)-22s %(message)s"
DATE_FMT = "%Y-%m-%d %H:%M:%S"
ROOT_LOGGER = "encapp"


def setup_logging(
    level: str = "INFO",
    log_file: Optional[str] = None,
    module_overrides: Optional[Iterable[str]] = None,
) -> None:
    """Configure the encapp root logger.

    Idempotent: safe to call multiple times. Each call replaces the
    handlers on the encapp root, leaving the global root logger
    untouched (so other libraries' logging behavior is preserved).

    level: one of "DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL" or
        their integer equivalents. Default INFO.
    log_file: optional path. When set, log lines are written to the file
        in addition to stderr. Open mode is 'w' so each run starts
        clean — encapp logs are runtime diagnostics, not historical
        archives.
    module_overrides: iterable of "<module>=<level>" strings, e.g.
        ["encapp.adb=DEBUG", "encapp.manifest=WARNING"]. Lets the user
        scope verbose logging to one subsystem without flooding from
        the others.
    """
    root = logging.getLogger(ROOT_LOGGER)
    root.setLevel(_resolve_level(level))
    # Replace any prior handlers (idempotency).
    for h in list(root.handlers):
        root.removeHandler(h)
    formatter = logging.Formatter(LOG_FMT, datefmt=DATE_FMT)

    stderr_h = logging.StreamHandler(sys.stderr)
    stderr_h.setFormatter(formatter)
    root.addHandler(stderr_h)

    if log_file:
        file_h = logging.FileHandler(log_file, mode="w")
        file_h.setFormatter(formatter)
        root.addHandler(file_h)

    # Prevent double-emission via the global root logger.
    root.propagate = False

    if module_overrides:
        for spec in module_overrides:
            if "=" not in spec:
                root.warning("ignoring malformed --log-modules entry: %r", spec)
                continue
            name, lvl = spec.split("=", 1)
            logging.getLogger(name.strip()).setLevel(_resolve_level(lvl.strip()))


def _resolve_level(level) -> int:
    if isinstance(level, int):
        return level
    if isinstance(level, str):
        return getattr(logging, level.upper(), logging.INFO)
    return logging.INFO


def level_from_debug_kwarg(debug: int) -> str:
    """Map legacy integer `debug` to a level name.

    debug=0  → WARNING  (default — suppress info chatter)
    debug=1  → INFO
    debug>=2 → DEBUG

    Used by call sites that still receive the old kwarg but want to
    log at the right level without rewriting everything.
    """
    if debug is None or debug <= 0:
        return "WARNING"
    if debug == 1:
        return "INFO"
    return "DEBUG"
