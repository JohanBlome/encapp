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

from encapp_tool import session_manifest, run_summary  # noqa: E402


def _verdicts_from_events(events):
    return session_manifest.classify_session(events)


def _ev(event_name, **kw):
    d = {"event": event_name, "session_id": "S"}
    d.update(kw)
    return d


class TestRunSummary(unittest.TestCase):
    def test_build_summary_happy_path(self):
        events = [
            _ev("session_start"),
            _ev("test_start", test_id="t1", pid=1),
            _ev("artifact",  test_id="t1", kind="stats", path="t1.json", bytes=4096),
            _ev("artifact",  test_id="t1", kind="video", path="t1.mp4",  bytes=1024000),
            _ev("test_end",  test_id="t1", status="ok"),
            _ev("session_end", status="complete"),
        ]
        sv = _verdicts_from_events(events)
        s = run_summary.build_summary(
            "S", sv,
            started_at_unix_ms=1000, finished_at_unix_ms=2000,
            device={"serial": "X", "model": "Pixel", "app_version": "1.31"},
        )
        self.assertEqual(1, s["schema_version"])
        self.assertEqual("S", s["session_id"])
        self.assertEqual(1, s["total_tests"])
        self.assertEqual(1, s["by_status"]["pass"])
        self.assertEqual(0, s["by_status"]["fail"])
        self.assertEqual(1, len(s["tests"]))
        self.assertEqual("t1", s["tests"][0]["test_id"])
        self.assertEqual("pass", s["tests"][0]["status"])
        self.assertEqual(2, len(s["tests"][0]["artifacts"]))
        self.assertEqual("Pixel", s["device"]["model"])

    def test_build_summary_mixed_statuses(self):
        events = [
            _ev("session_start"),
            _ev("test_start", test_id="t1"), _ev("test_end", test_id="t1", status="ok"),
            _ev("test_start", test_id="t2"),
            _ev("test_end", test_id="t2", status="error",
                error={"code": "boom", "message": "kaboom"}),
            _ev("test_start", test_id="t3"),    # no test_end → CRASH
            _ev("session_end", status="complete"),
        ]
        sv = _verdicts_from_events(events)
        s = run_summary.build_summary("S", sv)
        self.assertEqual(3, s["total_tests"])
        self.assertEqual(1, s["by_status"]["pass"])
        self.assertEqual(1, s["by_status"]["fail"])
        self.assertEqual(1, s["by_status"]["crash"])
        reasons = {t["test_id"]: t["reason"] for t in s["tests"]}
        self.assertIsNone(reasons["t1"])
        self.assertIn("boom", reasons["t2"])
        self.assertIn("test_start", reasons["t3"])

    def test_write_summary_creates_file(self):
        events = [_ev("session_start"),
                  _ev("test_start", test_id="t1"),
                  _ev("test_end", test_id="t1", status="ok"),
                  _ev("session_end", status="complete")]
        sv = _verdicts_from_events(events)
        s = run_summary.build_summary("S", sv)
        with tempfile.TemporaryDirectory() as tmp:
            path = run_summary.write_run_summary(tmp, s)
            self.assertEqual(os.path.join(tmp, "run_summary.json"), path)
            with open(path) as f:
                round_trip = json.load(f)
            self.assertEqual(s, round_trip)

    def test_format_summary_line_all_pass(self):
        events = [_ev("session_start"),
                  _ev("test_start", test_id="t1"),
                  _ev("test_end", test_id="t1", status="ok"),
                  _ev("session_end", status="complete")]
        s = run_summary.build_summary("S", _verdicts_from_events(events))
        line = run_summary.format_summary_line(s, report_path="/tmp/run_summary.json")
        self.assertIn("1/1 passed", line)
        self.assertNotIn("Failed:", line)
        self.assertIn("/tmp/run_summary.json", line)

    def test_format_summary_line_with_failures(self):
        events = [_ev("session_start")]
        for i in range(3):
            events.append(_ev("test_start", test_id=f"t{i}"))
            events.append(_ev("test_end", test_id=f"t{i}", status="ok"))
        for i in range(2):
            events.append(_ev("test_start", test_id=f"bad{i}"))
            events.append(_ev("test_end", test_id=f"bad{i}", status="error",
                              error={"code": "boom", "message": "x"}))
        events.append(_ev("session_end", status="complete"))
        s = run_summary.build_summary("S", _verdicts_from_events(events))
        line = run_summary.format_summary_line(s)
        self.assertIn("3/5 passed", line)
        self.assertIn("Failed:", line)
        self.assertIn("bad0", line)
        self.assertIn("bad1", line)

    def test_format_summary_line_trims_long_failure_list(self):
        events = [_ev("session_start")]
        for i in range(8):
            events.append(_ev("test_start", test_id=f"bad{i}"))
            events.append(_ev("test_end", test_id=f"bad{i}", status="error",
                              error={"code": "boom", "message": "x"}))
        events.append(_ev("session_end", status="complete"))
        s = run_summary.build_summary("S", _verdicts_from_events(events))
        line = run_summary.format_summary_line(s)
        self.assertIn("0/8 passed", line)
        self.assertIn("(+3 more)", line)


if __name__ == "__main__":
    unittest.main()
