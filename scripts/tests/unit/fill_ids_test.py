#!/usr/bin/env python3

import os
import sys
import unittest

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_ROOT_DIR = os.path.abspath(
    os.path.join(MODULE_PATH, os.pardir, os.pardir)
)
# scripts dir is added by ../../__init__.py. proto dir is local to this test.
sys.path.insert(0, os.path.join(ENCAPP_SCRIPTS_ROOT_DIR, "proto"))

import tests_pb2  # noqa: E402
import encapp  # noqa: E402


class TestFillIds(unittest.TestCase):
    def test_fills_missing_id_from_description_and_configure(self):
        suite = tests_pb2.TestSuite()
        t = suite.test.add()
        t.common.description = "johnny"
        t.configure.codec = "c2.qti.hevc.encoder"
        t.configure.bitrate = "1Mbps"
        t.configure.resolution = "1280x720"
        encapp.fill_ids(suite)
        self.assertNotEqual("", suite.test[0].common.id)
        self.assertIn("johnny", suite.test[0].common.id)
        self.assertIn("1280x720", suite.test[0].common.id)
        self.assertIn("c2_qti_hevc_encoder", suite.test[0].common.id)

    def test_preserves_existing_id(self):
        suite = tests_pb2.TestSuite()
        t = suite.test.add()
        t.common.id = "my_explicit_id"
        t.common.description = "johnny"
        t.configure.codec = "c2.qti.hevc.encoder"
        encapp.fill_ids(suite)
        self.assertEqual("my_explicit_id", suite.test[0].common.id)

    def test_disambiguates_duplicates_with_numeric_suffix(self):
        suite = tests_pb2.TestSuite()
        for _ in range(3):
            t = suite.test.add()
            t.common.description = "johnny"
            t.configure.codec = "c2.qti.hevc.encoder"
            t.configure.bitrate = "1Mbps"
        encapp.fill_ids(suite)
        ids = [t.common.id for t in suite.test]
        self.assertEqual(3, len(set(ids)), f"duplicate ids: {ids}")

    def test_fills_parallel_siblings(self):
        suite = tests_pb2.TestSuite()
        t = suite.test.add()
        t.common.description = "primary"
        sub = t.parallel.test.add()
        sub.common.description = "sibling_a"
        encapp.fill_ids(suite)
        self.assertNotEqual("", suite.test[0].common.id)
        self.assertIn("primary", suite.test[0].common.id)
        self.assertNotEqual("", suite.test[0].parallel.test[0].common.id)
        self.assertIn("sibling_a", suite.test[0].parallel.test[0].common.id)

    def test_fills_with_encapp_when_no_description(self):
        suite = tests_pb2.TestSuite()
        suite.test.add()  # nothing set
        encapp.fill_ids(suite)
        self.assertEqual("encapp", suite.test[0].common.id)

    def test_slug_replaces_non_alphanumeric(self):
        self.assertEqual("c2_qti_hevc_encoder", encapp._slug("c2.qti.hevc.encoder"))
        self.assertEqual("1280x720", encapp._slug("1280x720"))
        self.assertEqual("foo_bar", encapp._slug("__foo__bar__"))


if __name__ == "__main__":
    unittest.main()
