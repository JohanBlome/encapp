"""CLI-side consumer of the app's session manifest.

The app writes an append-only JSONL file to <device_workdir>/<session_id>.session.jsonl
with one event per line. fsync-per-line gives durable partial state on
process kill.

This module is pure-Python: it parses manifest text, classifies test
verdicts, and exposes a poll loop. Device I/O (pulling the manifest,
force-stopping the app) is injected as callbacks so the module is
trivially unit-testable.

Schema (v1):
    {ts, event:"session_start", session_id, schema_version, app_version, device_workdir}
    {ts, event:"test_start",   session_id, test_id, pid}
    {ts, event:"artifact",     session_id, test_id, kind, path, bytes}
    {ts, event:"test_end",     session_id, test_id, status:"ok|error|timeout|skipped",
                                error:{code,message,stack}|null, frames_encoded?, encoded_file?}
    {ts, event:"session_end",  session_id, status:"complete|aborted|error"}

A partial file (final line truncated, no session_end) is the normal crash
shape. Any lines that fail JSON parse are skipped with a warning; the
rest of the file is still used.
"""
import json
import logging
import os
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Dict, List, Optional

log = logging.getLogger("encapp.manifest")


class Status(Enum):
    PASS = "pass"
    FAIL = "fail"
    CRASH = "crash"
    TIMEOUT = "timeout"
    SKIPPED = "skipped"
    NEVER_STARTED = "never_started"


@dataclass
class TestVerdict:
    test_id: str
    status: Status
    reason: Optional[str] = None
    artifacts: List[dict] = field(default_factory=list)   # list of artifact event dicts
    raw_events: List[dict] = field(default_factory=list)


@dataclass
class SessionVerdicts:
    session_id: Optional[str] = None
    session_started: bool = False
    session_completed: bool = False         # session_end with status=="complete"
    session_end_status: Optional[str] = None
    tests: Dict[str, TestVerdict] = field(default_factory=dict)
    raw_events: List[dict] = field(default_factory=list)


def parse_manifest_text(text: str) -> List[dict]:
    """Parse JSONL text into a list of event dicts. Skips malformed lines."""
    events = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError as e:
            log.warning("manifest line %d unparseable, skipping: %s", lineno, e)
    return events


def classify_session(events: List[dict],
                     expected_test_ids: Optional[List[str]] = None) -> SessionVerdicts:
    """Classify a manifest event list into per-test verdicts.

    expected_test_ids: ids the CLI is expecting from its suite. Any
        expected id with no events is classified NEVER_STARTED. If None,
        only tests that appeared in the events are classified.
    """
    sv = SessionVerdicts(raw_events=list(events))
    per_test_events: Dict[str, List[dict]] = {}

    for ev in events:
        et = ev.get("event")
        if et == "session_start":
            sv.session_started = True
            sv.session_id = ev.get("session_id")
        elif et == "session_end":
            sv.session_end_status = ev.get("status")
            sv.session_completed = (ev.get("status") == "complete")
        elif et in ("test_start", "test_end", "artifact"):
            tid = ev.get("test_id")
            if tid is not None:
                per_test_events.setdefault(tid, []).append(ev)

    seen_ids = list(per_test_events.keys())
    all_ids = list(seen_ids)
    if expected_test_ids is not None:
        for tid in expected_test_ids:
            if tid not in per_test_events:
                all_ids.append(tid)

    for tid in all_ids:
        sv.tests[tid] = _classify_one(tid, per_test_events.get(tid, []))
    return sv


def _classify_one(test_id: str, events: List[dict]) -> TestVerdict:
    if not events:
        return TestVerdict(test_id, Status.NEVER_STARTED,
                           reason="no events emitted for this test")
    artifacts = [ev for ev in events if ev.get("event") == "artifact"]
    end = next((ev for ev in events if ev.get("event") == "test_end"), None)
    if end is None:
        return TestVerdict(test_id, Status.CRASH,
                           reason="test_start emitted but no test_end before manifest ended",
                           artifacts=artifacts, raw_events=events)
    status = end.get("status")
    if status == "ok":
        return TestVerdict(test_id, Status.PASS,
                           artifacts=artifacts, raw_events=events)
    if status == "skipped":
        return TestVerdict(test_id, Status.SKIPPED,
                           reason=end.get("error", {}).get("message") if end.get("error") else None,
                           artifacts=artifacts, raw_events=events)
    if status == "timeout":
        return TestVerdict(test_id, Status.TIMEOUT,
                           reason="app reported timeout",
                           artifacts=artifacts, raw_events=events)
    # status == "error" (or anything else)
    err = end.get("error") or {}
    code = err.get("code", "unknown")
    msg = err.get("message", "(no message)")
    return TestVerdict(test_id, Status.FAIL,
                       reason=f"{code}: {msg}",
                       artifacts=artifacts, raw_events=events)


def wait_for_session(
    pull_fn: Callable[[str, str], bool],
    device_workdir: str,
    local_workdir: str,
    session_id: str,
    expected_test_ids: List[str],
    timeout_sec: float,
    on_timeout: Optional[Callable[[], None]] = None,
    is_alive_fn: Optional[Callable[[], bool]] = None,
    poll_interval: float = 1.0,
    _time_fn: Callable[[], float] = time.monotonic,
    _sleep_fn: Callable[[float], None] = time.sleep,
) -> SessionVerdicts:
    """Poll the device for the session manifest until done or timeout.

    pull_fn(remote_path, local_dir) -> bool
        Pull <remote_path> into <local_dir>. Returns True if the file is
        now present locally. Called with the absolute device path of the
        manifest and the local workdir.
    on_timeout()
        Optional callback invoked when the wall-clock deadline expires
        (CLI typically force-stops the app here). Always called BEFORE
        the final manifest pull, so any in-flight test_end events that
        the app writes during shutdown are captured.
    is_alive_fn() -> bool
        Optional callback: True if the app process is still alive. When
        provided, the poll exits early once the app has died and the
        manifest is parseable — any test_start without matching
        test_end becomes a real CRASH verdict (the app exited without
        emitting test_end). Without this callback, an externally-killed
        app forces the poll to wait until timeout_sec elapses.

    Terminal-state semantics: PASS / FAIL / TIMEOUT / SKIPPED are always
    terminal. CRASH is terminal only after is_alive_fn() returns False
    (otherwise a test_start without test_end could just mean "still
    running"). NEVER_STARTED is never terminal — that's what timeout is
    for.

    Returns the SessionVerdicts parsed from the final manifest state.
    """
    remote_path = f"{device_workdir.rstrip('/')}/{session_id}.session.jsonl"
    local_path = os.path.join(local_workdir, f"{session_id}.session.jsonl")
    deadline = _time_fn() + timeout_sec
    expected_set = set(expected_test_ids)
    terminal_alive = (Status.PASS, Status.FAIL, Status.TIMEOUT, Status.SKIPPED)
    terminal_dead = terminal_alive + (Status.CRASH,)

    while True:
        pull_fn(remote_path, local_workdir)
        sv = _read_local(local_path, expected_test_ids)
        if sv.session_completed:
            return sv

        app_dead = (is_alive_fn is not None) and (not is_alive_fn())
        terminal_set = terminal_dead if app_dead else terminal_alive
        finished = {tid for tid, v in sv.tests.items()
                    if v.status in terminal_set}
        if expected_set and expected_set.issubset(finished) and sv.session_started:
            return sv
        if app_dead and sv.session_started:
            # App is dead but not every expected test reported a verdict —
            # final pull then return whatever we have (NEVER_STARTED for
            # any test that didn't emit a test_start).
            pull_fn(remote_path, local_workdir)
            return _read_local(local_path, expected_test_ids)

        if _time_fn() >= deadline:
            log.warning("manifest poll timed out after %.1fs, session_id=%s",
                        timeout_sec, session_id)
            if on_timeout is not None:
                on_timeout()
            # One last pull after the app is force-stopped.
            pull_fn(remote_path, local_workdir)
            sv = _read_local(local_path, expected_test_ids)
            # Mark any test_start without test_end as TIMEOUT instead of
            # CRASH, since the CLI initiated the kill.
            for tid, v in sv.tests.items():
                if v.status == Status.CRASH:
                    sv.tests[tid] = TestVerdict(
                        tid, Status.TIMEOUT,
                        reason=f"CLI timeout after {timeout_sec:.0f}s, app force-stopped",
                        artifacts=v.artifacts, raw_events=v.raw_events,
                    )
            return sv

        _sleep_fn(poll_interval)


def _read_local(local_path: str, expected_test_ids: List[str]) -> SessionVerdicts:
    if not os.path.exists(local_path):
        sv = SessionVerdicts()
        if expected_test_ids:
            for tid in expected_test_ids:
                sv.tests[tid] = TestVerdict(tid, Status.NEVER_STARTED,
                                            reason="manifest not present yet")
        return sv
    with open(local_path) as f:
        text = f.read()
    return classify_session(parse_manifest_text(text), expected_test_ids)


def format_test_failure(verdict: TestVerdict) -> str:
    """Multi-line human-readable failure block for stdout. CLI calls this
    after the run for each non-PASS test."""
    if verdict.status == Status.PASS:
        return ""
    head = f"  [{verdict.status.value.upper()}] {verdict.test_id}"
    if verdict.reason:
        head += f"  ({verdict.reason})"
    artifacts_str = ""
    if verdict.artifacts:
        bullets = "\n".join(
            f"      - {a.get('kind','?')}: {a.get('path','?')}"
            f" ({a.get('bytes', '?')} bytes)"
            for a in verdict.artifacts
        )
        artifacts_str = f"\n    artifacts:\n{bullets}"
    return head + artifacts_str
