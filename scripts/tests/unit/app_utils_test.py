import unittest
from unittest.mock import patch

from encapp_tool import app_utils

ADB_DEVICE_VALID_ID = "1234567890abcde"


@patch("encapp_tool.adb_cmds.install_apk")
@patch("encapp_tool.adb_cmds.grant_storage_permissions")
@patch("encapp_tool.adb_cmds.grant_camera_permission")
@patch("encapp_tool.adb_cmds.force_stop")
class TestAppUtils(unittest.TestCase):
    def test_install_app_shall_install_encapp_apk(
        self, mock_stop, mock_perm_camera, mock_perm_store, mock_install
    ):
        app_utils.install_app(ADB_DEVICE_VALID_ID)
        mock_install.assert_called_with(ADB_DEVICE_VALID_ID, app_utils.APK_MAIN, 0)
        mock_perm_store.assert_called_with(ADB_DEVICE_VALID_ID, 0)
        mock_perm_camera.assert_called_with(ADB_DEVICE_VALID_ID, 0)
        mock_stop.assert_called_with(ADB_DEVICE_VALID_ID, "com.facebook.encapp", 0)
