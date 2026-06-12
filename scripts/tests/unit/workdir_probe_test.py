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

from encapp_tool import workdir_probe  # noqa: E402


def _ok(stdout=""):
    return (True, stdout, "")


def _fail(stderr="err"):
    return (False, "", stderr)


class FakeAdb:
    """Programmable run_cmd stub. Each call consumes the next scripted reply
    if it matches a pattern; otherwise returns a default."""
    def __init__(self):
        self.calls = []
        self._cat_results = []   # list of (ok, stdout, stderr)
        self._am_result = _ok()
        self._rm_result = _ok()

    def set_am_start(self, result):
        self._am_result = result

    def queue_cat(self, *results):
        self._cat_results.extend(results)

    def __call__(self, cmd):
        self.calls.append(cmd)
        if " am start " in cmd:
            return self._am_result
        if " rm -f " in cmd:
            return self._rm_result
        if " cat " in cmd:
            if self._cat_results:
                return self._cat_results.pop(0)
            return _fail("No such file")
        return _ok()


class TestProbeWorkdir(unittest.TestCase):
    def test_probe_returns_marker_contents(self):
        adb = FakeAdb()
        adb.queue_cat(_ok("/storage/emulated/0\n"))
        wd = workdir_probe.probe_workdir(
            serial="X", activity="com.facebook.encapp/.MainActivity",
            run_cmd_fn=adb, timeout_sec=2.0, poll_interval_sec=0.01,
            _sleep_fn=lambda s: None,
        )
        self.assertEqual("/storage/emulated/0", wd)
        # Order check: rm, am start, cat (at least)
        kinds = [c.split()[3] for c in adb.calls]  # 4th token: shell|...
        self.assertIn("shell", kinds[0])

    def test_probe_polls_until_marker_appears(self):
        adb = FakeAdb()
        # First two cat calls fail (marker not written yet), third succeeds.
        adb.queue_cat(_fail("missing"), _fail("missing"), _ok("/sdcard"))
        sleep_calls = []
        wd = workdir_probe.probe_workdir(
            serial="X", activity="x/.M",
            run_cmd_fn=adb, timeout_sec=2.0, poll_interval_sec=0.01,
            _sleep_fn=lambda s: sleep_calls.append(s),
        )
        self.assertEqual("/sdcard", wd)
        self.assertEqual(2, len(sleep_calls), "expected to sleep between polls")

    def test_probe_strips_trailing_slash(self):
        adb = FakeAdb()
        adb.queue_cat(_ok("/sdcard/\n"))
        wd = workdir_probe.probe_workdir(
            serial="X", activity="x/.M",
            run_cmd_fn=adb, timeout_sec=2.0, poll_interval_sec=0.01,
            _sleep_fn=lambda s: None,
        )
        self.assertEqual("/sdcard", wd)

    def test_probe_raises_on_am_start_failure(self):
        adb = FakeAdb()
        adb.set_am_start(_fail("Activity not found"))
        with self.assertRaises(workdir_probe.ProbeFailed):
            workdir_probe.probe_workdir(
                serial="X", activity="x/.M",
                run_cmd_fn=adb, timeout_sec=0.05, poll_interval_sec=0.01,
                _sleep_fn=lambda s: None,
            )

    def test_probe_raises_on_timeout(self):
        adb = FakeAdb()
        # No cat results queued → every cat fails → timeout.
        ticks = [0.0]

        def time_fn():
            return ticks[0]

        def sleep_fn(s):
            ticks[0] += s

        with self.assertRaises(workdir_probe.ProbeFailed) as ctx:
            workdir_probe.probe_workdir(
                serial="X", activity="x/.M",
                run_cmd_fn=adb, timeout_sec=0.05, poll_interval_sec=0.01,
                _time_fn=time_fn, _sleep_fn=sleep_fn,
            )
        self.assertIn("timed out", str(ctx.exception))


class TestGetOrProbe(unittest.TestCase):
    def test_cache_miss_then_hit(self):
        with tempfile.TemporaryDirectory() as cache_dir:
            adb = FakeAdb()
            adb.queue_cat(_ok("/sdcard"))
            wd1 = workdir_probe.get_or_probe_workdir(
                serial="S", activity="x/.M", app_version="1.31",
                cache_dir=cache_dir, run_cmd_fn=adb,
            )
            self.assertEqual("/sdcard", wd1)
            # On the second call the cache file should be hit — no further
            # adb calls should fire (we don't queue anything).
            adb2 = FakeAdb()
            wd2 = workdir_probe.get_or_probe_workdir(
                serial="S", activity="x/.M", app_version="1.31",
                cache_dir=cache_dir, run_cmd_fn=adb2,
            )
            self.assertEqual("/sdcard", wd2)
            self.assertEqual([], adb2.calls,
                             "cache hit must not invoke adb")

    def test_app_version_mismatch_invalidates(self):
        with tempfile.TemporaryDirectory() as cache_dir:
            adb = FakeAdb()
            adb.queue_cat(_ok("/sdcard"))
            workdir_probe.get_or_probe_workdir(
                serial="S", activity="x/.M", app_version="1.31",
                cache_dir=cache_dir, run_cmd_fn=adb,
            )
            # Bump app_version → should re-probe.
            adb2 = FakeAdb()
            adb2.queue_cat(_ok("/data/data/com.facebook.encapp/files"))
            wd = workdir_probe.get_or_probe_workdir(
                serial="S", activity="x/.M", app_version="1.32",
                cache_dir=cache_dir, run_cmd_fn=adb2,
            )
            self.assertEqual("/data/data/com.facebook.encapp/files", wd)
            self.assertTrue(adb2.calls, "version bump must trigger re-probe")

    def test_refresh_forces_reprobe(self):
        with tempfile.TemporaryDirectory() as cache_dir:
            adb = FakeAdb()
            adb.queue_cat(_ok("/sdcard"))
            workdir_probe.get_or_probe_workdir(
                serial="S", activity="x/.M", app_version="1.31",
                cache_dir=cache_dir, run_cmd_fn=adb,
            )
            adb2 = FakeAdb()
            adb2.queue_cat(_ok("/sdcard"))
            workdir_probe.get_or_probe_workdir(
                serial="S", activity="x/.M", app_version="1.31",
                cache_dir=cache_dir, run_cmd_fn=adb2, refresh=True,
            )
            self.assertTrue(adb2.calls, "refresh=True must bypass cache")

    def test_invalidate_one(self):
        with tempfile.TemporaryDirectory() as cache_dir:
            adb = FakeAdb()
            adb.queue_cat(_ok("/sdcard"))
            workdir_probe.get_or_probe_workdir(
                serial="S", activity="x/.M", app_version="1.31",
                cache_dir=cache_dir, run_cmd_fn=adb,
            )
            workdir_probe.invalidate_cache(cache_dir, serial="S")
            self.assertFalse(os.path.exists(
                workdir_probe._cache_path(cache_dir, "S")))

    def test_invalidate_all(self):
        with tempfile.TemporaryDirectory() as cache_dir:
            for s in ("A", "B", "C"):
                adb = FakeAdb()
                adb.queue_cat(_ok("/sdcard"))
                workdir_probe.get_or_probe_workdir(
                    serial=s, activity="x/.M", app_version="1",
                    cache_dir=cache_dir, run_cmd_fn=adb,
                )
            workdir_probe.invalidate_cache(cache_dir)
            for s in ("A", "B", "C"):
                self.assertFalse(os.path.exists(
                    workdir_probe._cache_path(cache_dir, s)))


if __name__ == "__main__":
    unittest.main()
