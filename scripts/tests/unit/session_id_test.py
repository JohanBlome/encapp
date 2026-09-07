#!/usr/bin/env python3

import os
import re
import sys
import unittest
import unittest.mock

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_ROOT_DIR = os.path.abspath(
    os.path.join(MODULE_PATH, os.pardir, os.pardir)
)
sys.path.insert(0, os.path.join(ENCAPP_SCRIPTS_ROOT_DIR, "proto"))

import encapp  # noqa: E402


class TestSessionId(unittest.TestCase):
    SHAPE = re.compile(r"^R\d{10,}_[0-9a-f]{6}$")

    def test_shape_is_R_unixseconds_underscore_6hex(self):
        sid = encapp.make_session_id()
        self.assertIsNotNone(self.SHAPE.match(sid), f"unexpected shape: {sid!r}")

    def test_unique_within_same_second(self):
        # The 6-hex suffix should defeat same-second collisions across
        # parallel CLI invocations. 100 draws in a 24-bit space → birthday
        # probability ~0.03% — fine for a unit test, and well-above any
        # realistic concurrent-runs cap.
        ids = {encapp.make_session_id() for _ in range(100)}
        self.assertEqual(100, len(ids), "session_id collision in 100 draws")
    def test_android_launch_clears_stale_activity_task(self):
        with unittest.mock.patch.object(
            encapp.encapp_tool.adb_cmds, "reset_logcat"
        ), unittest.mock.patch.object(
            encapp.encapp_tool.adb_cmds,
            "run_cmd",
            return_value=(True, "", ""),
        ) as run_cmd:
            encapp.run_encapp_test(
                "/sdcard/test.pbtxt",
                "SERIAL",
                "/sdcard",
                session_id="SID",
                debug=1,
            )
        run_cmd.assert_called_once_with(
            "adb -s SERIAL shell am start --activity-clear-task "
            "-e workdir /sdcard -e test /sdcard/test.pbtxt "
            "-e session_id SID com.facebook.encapp/.MainActivity",
            1,
        )


if __name__ == "__main__":
    unittest.main()
