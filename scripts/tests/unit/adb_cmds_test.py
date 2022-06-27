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

ADB_INSTALL_ERROR = """
Performing Streamed Install
adb: failed to install ../app/releases/com.facebook.encapp-v1.6-debug.apk:
Exception occurred while executing:
android.os.ParcelableException: java.io.IOException: Requested internal only, but not enough space
\tat android.util.ExceptionUtils.wrap(ExceptionUtils.java:34)
"""

ADB_PM_LIST_OUT = """
package:com.android.package.a
package:com.android.package.b
package:com.android.package.c
"""


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

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_app_pid_active_process_shall_return_pid(self, mock_run):
        mock_run.return_value = (True, "19519", "")
        actual_pid = adb_cmds.get_app_pid(ADB_DEVICE_VALID_ID, "com.facebook.encapp", 0)
        self.assertEqual(actual_pid, 19519)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_app_pid_inactive_process_shall_return_not_running_code(self, mock_run):
        mock_run.return_value = (True, "", "")
        actual_pid = adb_cmds.get_app_pid(ADB_DEVICE_VALID_ID, "com.facebook.encapp", 0)
        self.assertEqual(actual_pid, -1)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_get_app_pid_invalid_id_output_shall_return_error_code(self, mock_run):
        mock_run.return_value = (True, "INVALID", "")
        actual_pid = adb_cmds.get_app_pid(ADB_DEVICE_VALID_ID, "com.facebook.encapp", 0)
        self.assertEqual(actual_pid, -2)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_install_apk_shall_run_install_cmd(self, mock_run):
        apk = "apk_to_install.apk"
        output = "Performing Streamed Install\nSuccess\n"
        mock_run.return_value = (True, output, "")
        expected_cmd = f"adb -s {ADB_DEVICE_VALID_ID} install -g {apk}"
        adb_cmds.install_apk(ADB_DEVICE_VALID_ID, apk)
        mock_run.assert_called_with(expected_cmd, 0)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_install_apk_shall_raise_exception_if_fail(self, mock_run):
        apk = "apk_to_install.apk"
        mock_run.return_value = (False, "", ADB_INSTALL_ERROR)
        with self.assertRaises(RuntimeError) as exc:
            adb_cmds.install_apk(ADB_DEVICE_VALID_ID, apk)
            self.assertEqual(
                exc.exception.__str__(),
                f"Unable to install apk_to_install.apk at "
                f"device {ADB_DEVICE_VALID_ID} due to {ADB_INSTALL_ERROR}",
            )

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_grant_storage_permission_shall_grant_external_storage_with_adb(
        self, mock_run
    ):
        apk = "com.example.myapp"
        pm_grant_str = f"adb -s {ADB_DEVICE_VALID_ID} shell pm grant com.example.myapp "
        adb_cmds.grant_storage_permissions(ADB_DEVICE_VALID_ID, apk, 0)
        mock_run.assert_has_calls(
            [
                call(pm_grant_str + "android.permission.WRITE_EXTERNAL_STORAGE", 0),
                call(pm_grant_str + "android.permission.READ_EXTERNAL_STORAGE", 0),
                call(
                    f"adb -s {ADB_DEVICE_VALID_ID} shell appops "
                    f"set --uid com.example.myapp MANAGE_EXTERNAL_STORAGE allow",
                    0,
                ),
            ],
            any_order=True,
        )

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_force_stop_shall_use_am_to_stop_package(self, mock_run):
        apk = "com.example.myapp"
        expected_cmd = f"adb -s {ADB_DEVICE_VALID_ID} shell am force-stop {apk}"
        adb_cmds.force_stop(ADB_DEVICE_VALID_ID, apk)
        mock_run.assert_called_with(expected_cmd, 0)

    @patch("encapp_tool.adb_cmds.run_cmd")
    def test_installed_apps_shall_list_pm_list_packages(self, mock_run):
        mock_run.return_value = (True, ADB_PM_LIST_OUT, "")
        result = adb_cmds.installed_apps(ADB_DEVICE_VALID_ID, 1)
        expect_out = [
            "com.android.package.a",
            "com.android.package.b",
            "com.android.package.c",
        ]
        self.assertEqual(result, expect_out)
        expected_call = f"adb -s {ADB_DEVICE_VALID_ID} shell pm list packages"
        mock_run.assert_called_with(expected_call, 1)

    @patch("encapp_tool.adb_cmds.run_cmd")
    @patch("encapp_tool.adb_cmds.installed_apps")
    def test_uninstall_apk_shall_uninstall_pkg_if_found(
            self, mock_installed, mock_run):
        installed_packages = [
            "com.android.package.a",
            "com.android.package.b",
            "com.android.package.c",
        ]
        mock_installed.return_value = installed_packages
        adb_cmds.uninstall_apk(ADB_DEVICE_VALID_ID, "com.not.found.package", 0)
        mock_run.assert_not_called()
        adb_cmds.uninstall_apk(ADB_DEVICE_VALID_ID, "com.android.package.a", 1)
        mock_run.assert_called_with(
            f"adb -s {ADB_DEVICE_VALID_ID} uninstall com.android.package.a", 1
        )


if __name__ == "__main__":
    unittest.main()
