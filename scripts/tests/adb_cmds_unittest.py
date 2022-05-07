import unittest
from unittest.mock import call, patch

from encapp_tool import adb_cmds

ADB_DEVICES_EMPTY = "List of devices attached\n\n"
ADB_DEVICE_VALID_ID = "1234567890abcde"
ADB_DEVICES_ONE_DEV = (
    "List of devices attached\n"
    f"{ADB_DEVICE_VALID_ID}       device "
    "usb:123456789X "
    "product:product01 "
    "model:MODEL_123 "
    "device:device_01 "
    "transport_id:8\n\n"
)

ADB_DEVICES_MULTI_DEV = (
    "List of devices attached\n"
    "1234567890abcde       device "
    "usb:123456789X "
    "product:product01 "
    "model:MODEL_123 "
    "device:device_01 "
    "transport_id:8\n"
    "abcde1234567890       device "
    "usb:987654321X "
    "product:product02 "
    "device:device_02 "
    "transport_id:9\n\n"
)

ADB_LS_ENCAPP = (
    "Android\nDCIM\nencapp_1.txt\nmyencapp_2.txt\nenc.txt\nencapp_logs.log\n"
)


class TestAdbCommands(unittest.TestCase):
    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_device_info_with_not_devices_shall_raise_error(self, mock_run):
        mock_run.return_value = (True, ADB_DEVICES_EMPTY, "")
        with self.assertRaises(AssertionError) as exc:
            adb_cmds.get_device_info(ADB_DEVICE_VALID_ID, debug=0)
        self.assertEqual(exc.exception.__str__(), "error: no devices connected")

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_device_info_with_found_device_shall_return_info(self, mock_run):
        mock_run.return_value = (True, ADB_DEVICES_MULTI_DEV, "")
        expected_result = (
            {
                "device": "device_01",
                "model": "MODEL_123",
                "product": "product01",
                "transport_id": "8",
                "usb": "123456789X",
            },
            "1234567890abcde",
        )
        actual_result = adb_cmds.get_device_info(ADB_DEVICE_VALID_ID, debug=0)
        self.assertEqual(expected_result, actual_result)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_device_info_with_missing_device_shall_raise_exception(self, mock_run):
        mock_run.return_value = (True, ADB_DEVICES_MULTI_DEV, "")
        with self.assertRaises(AssertionError) as exc:
            adb_cmds.get_device_info("not_connected_device", debug=0)
        self.assertEqual(
            exc.exception.__str__(), "error: device not_connected_device not available"
        )

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_device_info_with_multiple_device_not_specified_return_info(
        self, mock_run
    ):
        mock_run.return_value = (True, ADB_DEVICES_MULTI_DEV, "")
        error_str = (
            "error: need to choose a device [1234567890abcde, abcde1234567890]"
        )
        with self.assertRaises(AssertionError) as exc:
            adb_cmds.get_device_info(None, debug=0)
        self.assertEqual(exc.exception.__str__(), error_str)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_device_info_with_single_device_not_specified_return_info(
        self, mock_run
    ):
        mock_run.return_value = (True, ADB_DEVICES_ONE_DEV, "")
        expected_result = (
            {
                "device": "device_01",
                "model": "MODEL_123",
                "product": "product01",
                "transport_id": "8",
                "usb": "123456789X",
            },
            "1234567890abcde",
        )
        actual_result = adb_cmds.get_device_info(None, debug=0)
        self.assertEqual(expected_result, actual_result)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_remove_files_using_regex_shall_remove_regex_match_files(self, mock_run):
        mock_run.return_value = (True, ADB_LS_ENCAPP, "")
        adb_cmds.remove_files_using_regex(
            ADB_DEVICE_VALID_ID, r"encapp_.*", "/sdcard/", 1
        )
        mock_run.assert_has_calls(
            [
                call(f"adb -s {ADB_DEVICE_VALID_ID} shell ls /sdcard/", 1),
                call(f"adb -s {ADB_DEVICE_VALID_ID} shell rm /sdcard/encapp_1.txt", 1),
                call(f"adb -s {ADB_DEVICE_VALID_ID} shell rm /sdcard/encapp_2.txt", 1),
                call(
                    f"adb -s {ADB_DEVICE_VALID_ID} shell rm /sdcard/encapp_logs.log", 1
                ),
            ],
            any_order=True,
        )


if __name__ == "__main__":
    unittest.main()
