#!/usr/bin/env python3

import unittest
import os
import sys
import tempfile
from unittest import mock

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_ROOT_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir)
sys.path.append(ENCAPP_SCRIPTS_ROOT_DIR)

import encapp  # noqa: E402

parseBitrateFieldCases = [
    {
        "name": "single basic",
        "bitrate_string": "100",
        "bitrate_list": [100],
    },
    {
        "name": "single prefix",
        "bitrate_string": "100kbps",
        "bitrate_list": [100000],
    },
    {
        "name": "single prefix space",
        "bitrate_string": "100 kbps",
        "bitrate_list": [100000],
    },
    {
        "name": "single float",
        "bitrate_string": "1.5 Mbps",
        "bitrate_list": [1500000],
    },
    {
        "name": "list basic",
        "bitrate_string": "100,200",
        "bitrate_list": [100, 200],
    },
    {
        "name": "list prefix",
        "bitrate_string": "100kbps,200kbps",
        "bitrate_list": [100000, 200000],
    },
    {
        "name": "list prefix/mixed",
        "bitrate_string": "100,100kbps,200kbps,1Mbps",
        "bitrate_list": [100, 100000, 200000, 1000000],
    },
    {
        "name": "range basic",
        "bitrate_string": "100-200-50",
        "bitrate_list": [100, 150, 200],
    },
    {
        "name": "range prefix",
        "bitrate_string": "100kbps-200kbps-50kbps",
        "bitrate_list": [100000, 150000, 200000],
    },
    {
        "name": "range prefix and float",
        "bitrate_string": "1Mbps-4.5Mbps-0.5Mbps",
        "bitrate_list": [1000000, 1500000, 2000000, 2500000, 3000000, 3500000, 4000000, 4500000],
    },
    {
        "name": "mix 1",
        "bitrate_string": "100,100kbps,200kbps,100kbps-1Mbps-50kbps",
        "bitrate_list": [
            100,
            100000,
            200000,
            100000,
            150000,
            200000,
            250000,
            300000,
            350000,
            400000,
            450000,
            500000,
            550000,
            600000,
            650000,
            700000,
            750000,
            800000,
            850000,
            900000,
            950000,
            1000000,
        ],
    },
]


class EncappTest(unittest.TestCase):
    def testGetDataBasic(self):
        """parse_bitrate_field tests."""
        for test_case in parseBitrateFieldCases:
            print("...running %s" % test_case["name"])
            bitrate_list = encapp.parse_bitrate_field(test_case["bitrate_string"])
            msg = 'unittest failed: "%s"' % test_case["name"]
            self.assertTrue(
                test_case["bitrate_list"] == bitrate_list,
                msg=(f'{msg} {test_case["bitrate_list"]} != ' f"{bitrate_list}"),
            )


    def testStreamingLogcatPreservesCompleteBuffer(self):
        complete_log = (
            "01-01 00:00:00.000 1 2 D encapp.main: started\n"
            "01-01 00:00:00.001 1 2 I encapp.fakeinput: "
            "Generated fake input pattern: 2368x1728\n"
            "01-01 00:00:00.002 3 4 E CCodec: vendor configure failed\n"
            "01-01 00:00:00.003 5 6 E AndroidRuntime: fatal detail\n")
        process = mock.Mock()
        process.communicate.return_value = ("", "")
        with tempfile.TemporaryDirectory() as td, mock.patch.object(
                encapp.subprocess, "Popen", return_value=process) as popen:
            capture = encapp.AndroidLogcatCapture(td, "S1", "SERIAL")
            capture.start()
            capture._file.write(complete_log)
            capture._file.flush()
            self.assertEqual(1, capture.progress_token())
            extra_log = (
                "01-01 00:00:00.004 1 2 D encapp.main: "
                "Sleep while waiting for init to be done\n"
                "01-01 00:00:00.005 1 3 D encapp.async_buffer_encoder: "
                "case - AsyncBufferEncoder: frames: 500 outframes: 499\n")
            capture._file.write(extra_log)
            capture._file.flush()
            self.assertEqual(2, capture.progress_token())
            path = capture.stop()
            with open(path) as handle:
                self.assertEqual(handle.read(), complete_log + extra_log)
        popen.assert_called_once_with(
            ["adb", "-s", "SERIAL", "logcat", "-v", "threadtime"],
            stdout=mock.ANY, stderr=encapp.subprocess.PIPE, text=True)
        process.terminate.assert_called_once_with()

    def testDumpSessionLogcatPreservesCompleteBuffer(self):
        complete_log = (
            "01-01 00:00:00.000 1 2 D encapp.main: started\n"
            "01-01 00:00:00.001 3 4 E CCodec: vendor configure failed\n"
            "01-01 00:00:00.002 5 6 E AndroidRuntime: fatal detail\n")
        with tempfile.TemporaryDirectory() as td, mock.patch.object(
                encapp.encapp_tool.adb_cmds, "run_cmd",
                return_value=(True, complete_log, "")) as run_cmd:
            path = encapp.dump_session_logcat(td, "S1", "SERIAL")
            with open(path) as handle:
                self.assertEqual(handle.read(), complete_log)
        run_cmd.assert_called_once_with(
            "adb -s SERIAL logcat -d -v threadtime",
            ignore_errors=True, debug=0)

    def testCodecMediaTypesPrefersCompleteList(self):
        codec = {
            "media_type": {"mime_type": "video/avc"},
            "media_types": [
                {"mime_type": "video/avc"},
                {"mime_type": "video/hevc"},
            ],
        }
        self.assertEqual(codec["media_types"], encapp.get_media_types(codec))

    def testCodecMediaTypesSupportsLegacySingularField(self):
        media_type = {"mime_type": "audio/opus"}
        self.assertEqual([media_type], encapp.get_media_types({"media_type": media_type}))
        self.assertTrue(encapp.has_audio_media_type({"media_type": media_type}))
        self.assertTrue(encapp.is_audio_only_codec({"media_type": media_type}))

    def testCodecMediaTypesDoesNotTreatMixedCodecAsAudioOnly(self):
        codec = {
            "media_types": [
                {"mime_type": "video/avc"},
                {"mime_type": "audio/opus"},
            ]
        }
        self.assertTrue(encapp.has_audio_media_type(codec))
        self.assertFalse(encapp.is_audio_only_codec(codec))


if __name__ == "__main__":
    unittest.main()
