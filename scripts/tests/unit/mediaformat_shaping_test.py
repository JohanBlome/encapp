#!/usr/bin/env python3

import json
import os
import sys
import tempfile
import unittest
import unittest.mock

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_ROOT_DIR = os.path.abspath(
    os.path.join(MODULE_PATH, os.pardir, os.pardir)
)
sys.path.insert(0, ENCAPP_SCRIPTS_ROOT_DIR)
sys.path.insert(0, os.path.join(ENCAPP_SCRIPTS_ROOT_DIR, "proto"))

import encapp  # noqa: E402
import tests_pb2 as tests_definitions  # noqa: E402


def _make_suite(vbr=False, shaping_override=None):
    suite = tests_definitions.TestSuite()
    test = suite.test.add()
    test.common.id = "t1"
    test.common.output_filename = "out"
    test.input.filepath = "fake_input"
    test.configure.codec = "c2.example.encoder"
    test.configure.bitrate = "4000000"
    if vbr:
        test.configure.bitrate_mode = tests_definitions.Configure.vbr
    if shaping_override is not None:
        test.test_setup.enable_mediaformat_shaping = shaping_override
    return suite


class MediaFormatShapingTest(unittest.TestCase):
    def test_resolve_policy_defaults_to_disabled(self):
        options = unittest.mock.Mock(enable_mediaformat_shaping=None)
        self.assertFalse(
            encapp._resolve_mediaformat_shaping_policy(_make_suite(), options)
        )

    def test_resolve_policy_prefers_cli_override(self):
        options = unittest.mock.Mock(enable_mediaformat_shaping=True)
        self.assertTrue(
            encapp._resolve_mediaformat_shaping_policy(
                _make_suite(shaping_override=False), options
            )
        )

    @unittest.mock.patch.object(encapp.encapp_tool.adb_cmds, "USE_IDB", False)
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.clearprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.setprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.getprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.push_file_to_device")
    @unittest.mock.patch("encapp.run_encapp_test")
    @unittest.mock.patch("encapp.make_session_id", return_value="S1")
    def test_run_disables_shaping_and_restores_unset(
        self,
        _mock_session_id,
        mock_run,
        mock_push,
        mock_getprop,
        mock_setprop,
        mock_clearprop,
    ):
        mock_push.return_value = True
        mock_getprop.side_effect = ["", "0", ""]

        with tempfile.TemporaryDirectory() as tmp:
            result = encapp.run_codec_tests(
                _make_suite(),
                [],
                "model",
                "serial",
                tmp,
                tmp,
                "/sdcard",
                ignore_results=True,
                enable_mediaformat_shaping=False,
            )
            self.assertEqual((None, None), result)
            mock_run.assert_called_once()
            mock_setprop.assert_called_once_with(
                "serial", encapp.MEDIAFORMAT_SHAPING_PROP, "0", debug=0
            )
            mock_clearprop.assert_called_once_with(
                "serial", encapp.MEDIAFORMAT_SHAPING_PROP, debug=0
            )
            with open(os.path.join(tmp, "S1.mediaformat_shaping.json")) as f:
                metadata = json.load(f)
            self.assertFalse(metadata["requested_enabled"])
            self.assertEqual("", metadata["original_value"])
            self.assertEqual("0", metadata["effective_value"])
            self.assertEqual("", metadata["restored_value"])

    @unittest.mock.patch.object(encapp.encapp_tool.adb_cmds, "USE_IDB", False)
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.clearprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.setprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.getprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.push_file_to_device")
    @unittest.mock.patch("encapp.run_encapp_test", side_effect=TimeoutError("boom"))
    @unittest.mock.patch("encapp.make_session_id", return_value="S2")
    def test_restore_happens_when_run_fails(
        self,
        _mock_session_id,
        _mock_run,
        mock_push,
        mock_getprop,
        mock_setprop,
        mock_clearprop,
    ):
        del mock_clearprop
        mock_push.return_value = True
        mock_getprop.side_effect = ["1", "0", "1"]

        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(TimeoutError):
                encapp.run_codec_tests(
                    _make_suite(),
                    [],
                    "model",
                    "serial",
                    tmp,
                    tmp,
                    "/sdcard",
                    ignore_results=False,
                    enable_mediaformat_shaping=False,
                )

            mock_setprop.assert_has_calls(
                [
                    unittest.mock.call(
                        "serial", encapp.MEDIAFORMAT_SHAPING_PROP, "0", debug=0
                    ),
                    unittest.mock.call(
                        "serial", encapp.MEDIAFORMAT_SHAPING_PROP, "1", debug=0
                    ),
                ]
            )
            with open(os.path.join(tmp, "S2.mediaformat_shaping.json")) as f:
                metadata = json.load(f)
            self.assertEqual("1", metadata["original_value"])
            self.assertEqual("0", metadata["effective_value"])
            self.assertEqual("1", metadata["restored_value"])
            self.assertIsNone(metadata["restore_error"])

    @unittest.mock.patch.object(encapp.encapp_tool.adb_cmds, "USE_IDB", False)
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.clearprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.setprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.getprop_value")
    @unittest.mock.patch("encapp.encapp_tool.adb_cmds.push_file_to_device")
    @unittest.mock.patch("encapp.run_encapp_test")
    @unittest.mock.patch("encapp.make_session_id", return_value="S3")
    def test_verification_failure_restores_original_value(
        self,
        _mock_session_id,
        mock_run,
        mock_push,
        mock_getprop,
        mock_setprop,
        mock_clearprop,
    ):
        del mock_clearprop
        mock_push.return_value = True
        mock_getprop.side_effect = ["1", "1", "1"]

        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(RuntimeError):
                encapp.run_codec_tests(
                    _make_suite(),
                    [],
                    "model",
                    "serial",
                    tmp,
                    tmp,
                    "/sdcard",
                    ignore_results=True,
                    enable_mediaformat_shaping=False,
                )

            mock_run.assert_not_called()
            mock_setprop.assert_has_calls(
                [
                    unittest.mock.call(
                        "serial", encapp.MEDIAFORMAT_SHAPING_PROP, "0", debug=0
                    ),
                    unittest.mock.call(
                        "serial", encapp.MEDIAFORMAT_SHAPING_PROP, "1", debug=0
                    ),
                ]
            )
            with open(os.path.join(tmp, "S3.mediaformat_shaping.json")) as f:
                metadata = json.load(f)
            self.assertEqual("1", metadata["original_value"])
            self.assertEqual("1", metadata["effective_value"])
            self.assertEqual("1", metadata["restored_value"])


if __name__ == "__main__":
    unittest.main()
