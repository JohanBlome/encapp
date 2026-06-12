#!/usr/bin/env python3

import json
import os
import sys
import tempfile
import unittest

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_ROOT_DIR = os.path.abspath(
    os.path.join(MODULE_PATH, os.pardir, os.pardir)
)
sys.path.insert(0, os.path.join(ENCAPP_SCRIPTS_ROOT_DIR, "proto"))

from encapp_tool.session_manifest import (  # noqa: E402
    Status,
    parse_manifest_text,
    classify_session,
    wait_for_session,
    format_test_failure,
)


def _line(d):
    return json.dumps(d)


HAPPY_PATH = "\n".join([
    _line({"event": "session_start", "session_id": "S1", "schema_version": 1,
           "app_version": "1.31", "device_workdir": "/sdcard"}),
    _line({"event": "test_start", "session_id": "S1", "test_id": "t1", "pid": 1234}),
    _line({"event": "artifact",   "session_id": "S1", "test_id": "t1",
           "kind": "stats", "path": "t1.json", "bytes": 4096}),
    _line({"event": "artifact",   "session_id": "S1", "test_id": "t1",
           "kind": "video", "path": "t1.mp4",  "bytes": 1024000}),
    _line({"event": "test_end",   "session_id": "S1", "test_id": "t1",
           "status": "ok", "error": None, "frames_encoded": 600}),
    _line({"event": "session_end", "session_id": "S1", "status": "complete"}),
])

CRASH_MID_TEST = "\n".join([
    _line({"event": "session_start", "session_id": "S2"}),
    _line({"event": "test_start", "session_id": "S2", "test_id": "t1", "pid": 99}),
    _line({"event": "artifact",   "session_id": "S2", "test_id": "t1",
           "kind": "video", "path": "t1.mp4", "bytes": -1}),
    # No test_end, no session_end — process killed.
])


class TestParseClassify(unittest.TestCase):
    def test_parse_skips_blank_and_malformed(self):
        text = (HAPPY_PATH
                + "\n\nthis is not json\n"
                + _line({"event": "session_end", "session_id": "S1", "status": "complete"}))
        events = parse_manifest_text(text)
        # 6 real events from HAPPY_PATH + 1 extra session_end at the tail.
        # blanks and malformed are dropped.
        self.assertEqual(7, len(events))

    def test_happy_path_yields_pass(self):
        sv = classify_session(parse_manifest_text(HAPPY_PATH), expected_test_ids=["t1"])
        self.assertEqual("S1", sv.session_id)
        self.assertTrue(sv.session_started)
        self.assertTrue(sv.session_completed)
        self.assertEqual(Status.PASS, sv.tests["t1"].status)
        self.assertIsNone(sv.tests["t1"].reason)
        self.assertEqual(2, len(sv.tests["t1"].artifacts))

    def test_crash_mid_test_yields_crash(self):
        sv = classify_session(parse_manifest_text(CRASH_MID_TEST), expected_test_ids=["t1"])
        self.assertTrue(sv.session_started)
        self.assertFalse(sv.session_completed)
        self.assertEqual(Status.CRASH, sv.tests["t1"].status)
        self.assertIn("no test_end", sv.tests["t1"].reason)
        self.assertEqual(1, len(sv.tests["t1"].artifacts))

    def test_error_status_yields_fail_with_reason(self):
        text = "\n".join([
            _line({"event": "session_start", "session_id": "S3"}),
            _line({"event": "test_start", "session_id": "S3", "test_id": "t1", "pid": 1}),
            _line({"event": "test_end", "session_id": "S3", "test_id": "t1",
                   "status": "error",
                   "error": {"code": "configure_failed",
                             "message": "MediaCodec.configure threw",
                             "stack": ""}}),
            _line({"event": "session_end", "session_id": "S3", "status": "complete"}),
        ])
        sv = classify_session(parse_manifest_text(text), expected_test_ids=["t1"])
        self.assertEqual(Status.FAIL, sv.tests["t1"].status)
        self.assertIn("configure_failed", sv.tests["t1"].reason)
        self.assertIn("MediaCodec.configure threw", sv.tests["t1"].reason)

    def test_missing_expected_test_id_yields_never_started(self):
        sv = classify_session(parse_manifest_text(HAPPY_PATH),
                              expected_test_ids=["t1", "t2_missing"])
        self.assertEqual(Status.PASS, sv.tests["t1"].status)
        self.assertEqual(Status.NEVER_STARTED, sv.tests["t2_missing"].status)

    def test_format_test_failure_includes_artifacts(self):
        verdict_text = "\n".join([
            _line({"event": "session_start", "session_id": "S4"}),
            _line({"event": "test_start", "session_id": "S4", "test_id": "t1", "pid": 1}),
            _line({"event": "artifact", "session_id": "S4", "test_id": "t1",
                   "kind": "video", "path": "t1.mp4", "bytes": 2048}),
            _line({"event": "test_end", "session_id": "S4", "test_id": "t1",
                   "status": "error", "error": {"code": "boom", "message": "kaboom"}}),
        ])
        sv = classify_session(parse_manifest_text(verdict_text), expected_test_ids=["t1"])
        s = format_test_failure(sv.tests["t1"])
        self.assertIn("[FAIL]", s)
        self.assertIn("t1", s)
        self.assertIn("kaboom", s)
        self.assertIn("t1.mp4", s)


class TestWaitForSession(unittest.TestCase):
    def test_returns_when_session_end_appears(self):
        with tempfile.TemporaryDirectory() as tmp:
            local = tmp
            states = [HAPPY_PATH]  # one-shot: manifest already complete on first pull

            def pull(remote, local_dir):
                local_path = os.path.join(local_dir, "S1.session.jsonl")
                with open(local_path, "w") as f:
                    f.write(states[0])
                return True

            sv = wait_for_session(
                pull_fn=pull,
                device_workdir="/sdcard",
                local_workdir=local,
                session_id="S1",
                expected_test_ids=["t1"],
                timeout_sec=10.0,
                poll_interval=0.01,
            )
            self.assertTrue(sv.session_completed)
            self.assertEqual(Status.PASS, sv.tests["t1"].status)

    def test_timeout_force_stop_when_app_hangs_with_no_events(self):
        """App hung — session_start written but no test_start ever
        appeared (encoder stalled before announcing). Timeout fires,
        on_timeout is called (CLI force-stops), the orphan expected
        test stays NEVER_STARTED (no test_start was ever seen)."""
        with tempfile.TemporaryDirectory() as tmp:
            local = tmp
            on_timeout_calls = []

            session_start_only = _line(
                {"event": "session_start", "session_id": "S2"}
            )

            def pull(remote, local_dir):
                local_path = os.path.join(local_dir, "S2.session.jsonl")
                with open(local_path, "w") as f:
                    f.write(session_start_only)
                return True

            def on_timeout():
                on_timeout_calls.append(True)

            sv = wait_for_session(
                pull_fn=pull,
                device_workdir="/sdcard",
                local_workdir=local,
                session_id="S2",
                expected_test_ids=["t1"],
                timeout_sec=0.05,           # immediate timeout
                on_timeout=on_timeout,
                poll_interval=0.01,
            )
            self.assertEqual(1, len(on_timeout_calls),
                             "on_timeout must fire exactly once")
            # t1 was expected but never started → NEVER_STARTED.
            self.assertEqual(Status.NEVER_STARTED, sv.tests["t1"].status)

    def test_timeout_relabels_crash_to_timeout_when_no_expected_ids(self):
        """Edge case: no expected_test_ids known to CLI, but the manifest
        contains a test_start without test_end. Early-exit doesn't fire
        (no expected_set), so we hit the timeout branch — CRASH gets
        relabeled to TIMEOUT because the CLI initiated the kill."""
        with tempfile.TemporaryDirectory() as tmp:
            local = tmp
            on_timeout_calls = []

            def pull(remote, local_dir):
                local_path = os.path.join(local_dir, "S5.session.jsonl")
                with open(local_path, "w") as f:
                    f.write(CRASH_MID_TEST)
                return True

            sv = wait_for_session(
                pull_fn=pull,
                device_workdir="/sdcard",
                local_workdir=local,
                session_id="S5",
                expected_test_ids=[],         # CLI doesn't know the suite
                timeout_sec=0.05,
                on_timeout=lambda: on_timeout_calls.append(True),
                poll_interval=0.01,
            )
            self.assertEqual(1, len(on_timeout_calls))
            self.assertEqual(Status.TIMEOUT, sv.tests["t1"].status)
            self.assertIn("force-stopped", sv.tests["t1"].reason)

    def test_crash_is_terminal_only_when_app_is_dead(self):
        """CRASH-state tests early-exit ONLY when is_alive_fn says the app
        is dead. While the app is still running, a test_start without
        test_end just means 'still encoding' — keep polling."""
        with tempfile.TemporaryDirectory() as tmp:
            local = tmp
            sleep_calls = []

            def pull(remote, local_dir):
                with open(os.path.join(local_dir, "S6.session.jsonl"), "w") as f:
                    f.write(CRASH_MID_TEST)
                return True

            # Case A: app dead → should early-exit on iteration 1.
            sleep_calls.clear()
            sv = wait_for_session(
                pull_fn=pull,
                device_workdir="/sdcard",
                local_workdir=local,
                session_id="S6",
                expected_test_ids=["t1"],
                timeout_sec=60.0,
                is_alive_fn=lambda: False,    # app already dead
                poll_interval=0.01,
                _sleep_fn=lambda s: sleep_calls.append(s),
            )
            self.assertEqual(0, len(sleep_calls),
                             "expected early-exit when app dead")
            self.assertEqual(Status.CRASH, sv.tests["t1"].status)

            # Case B: app alive → must NOT early-exit; must hit timeout.
            on_timeout_calls = []
            sv = wait_for_session(
                pull_fn=pull,
                device_workdir="/sdcard",
                local_workdir=local,
                session_id="S6",
                expected_test_ids=["t1"],
                timeout_sec=0.02,             # tight; must trigger
                is_alive_fn=lambda: True,     # app still running
                on_timeout=lambda: on_timeout_calls.append(True),
                poll_interval=0.005,
            )
            self.assertEqual(1, len(on_timeout_calls),
                             "expected timeout branch when app alive")
            self.assertEqual(Status.TIMEOUT, sv.tests["t1"].status)

    def test_never_started_when_manifest_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            # pull always "fails" — no file ever appears locally.
            def pull(remote, local_dir):
                return False

            sv = wait_for_session(
                pull_fn=pull,
                device_workdir="/sdcard",
                local_workdir=tmp,
                session_id="Sx",
                expected_test_ids=["t1"],
                timeout_sec=0.02,
                poll_interval=0.005,
            )
            self.assertEqual(Status.NEVER_STARTED, sv.tests["t1"].status)


if __name__ == "__main__":
    unittest.main()
