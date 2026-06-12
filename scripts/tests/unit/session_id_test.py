#!/usr/bin/env python3

import os
import re
import sys
import unittest

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


if __name__ == "__main__":
    unittest.main()
