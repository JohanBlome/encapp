import unittest
from unittest.mock import patch

from encapp_tool import app_utils

ADB_DEVICE_VALID_ID = "1234567890abcde"


class TestAppUtils(unittest.TestCase):
    @patch("encapp_tool.adb_cmds.install_apk")
    @patch("encapp_tool.adb_cmds.grant_storage_permissions")
    @patch("encapp_tool.adb_cmds.grant_camera_permission")
    @patch("encapp_tool.adb_cmds.force_stop")
    def test_install_app_shall_install_encapp_apk(
        self, mock_stop, mock_perm_camera, mock_perm_store, mock_install
    ):
        app_utils.install_app(ADB_DEVICE_VALID_ID)
        mock_install.assert_called_with(ADB_DEVICE_VALID_ID, app_utils.APK_MAIN, 0)
        mock_perm_store.assert_called_with(ADB_DEVICE_VALID_ID, 0)
        mock_perm_camera.assert_called_with(ADB_DEVICE_VALID_ID, 0)
        mock_stop.assert_called_with(ADB_DEVICE_VALID_ID, "com.facebook.encapp", 0)

    @patch("encapp_tool.adb_cmds.installed_apps")
    def test_install_ok_shall_verify_if_encapp_is_installed(self, mock_apps):
        installed_encapp = [
            "com.android.package.a",
            "com.facebook.encapp",
            "com.android.package.c",
        ]
        not_installed_encapp = ["com.android.package.a", "com.android.package.b"]
        mock_apps.side_effect = [installed_encapp, not_installed_encapp]
        self.assertTrue(app_utils.install_ok(ADB_DEVICE_VALID_ID, 1))
        self.assertFalse(app_utils.install_ok(ADB_DEVICE_VALID_ID, 1))

    @patch("encapp_tool.adb_cmds.uninstall_apk")
    def test_uninstall_app_shall_uninstall_encapp(self, mock_uninstall):
        app_utils.uninstall_app(ADB_DEVICE_VALID_ID, 1)
        mock_uninstall.assert_called_with(
            ADB_DEVICE_VALID_ID, "com.facebook.encapp", 1
        )
