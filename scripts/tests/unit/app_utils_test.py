#!/usr/bin/env python3

import unittest
import unittest.mock
import os
import sys

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_ROOT_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir)
sys.path.append(ENCAPP_SCRIPTS_ROOT_DIR)

import encapp_tool  # noqa: E402
import encapp_tool.app_utils  # noqa: E402

ADB_DEVICE_VALID_ID = '1234567890abcde'


class TestAppUtils(unittest.TestCase):
    @unittest.mock.patch('encapp_tool.adb_cmds.install_apk')
    @unittest.mock.patch('encapp_tool.adb_cmds.grant_storage_permissions')
    @unittest.mock.patch('encapp_tool.adb_cmds.grant_camera_permission')
    @unittest.mock.patch('encapp_tool.adb_cmds.force_stop')
    def test_install_app_shall_install_encapp_apk(
        self, mock_stop, mock_perm_camera, mock_perm_store, mock_install
    ):
        encapp_tool.app_utils.install_app(ADB_DEVICE_VALID_ID)
        mock_install.assert_called_with(
            ADB_DEVICE_VALID_ID, encapp_tool.app_utils.APK_MAIN, 0)
        mock_perm_store.assert_called_with(ADB_DEVICE_VALID_ID, 0)
        mock_perm_camera.assert_called_with(ADB_DEVICE_VALID_ID, 0)
        mock_stop.assert_called_with(
            ADB_DEVICE_VALID_ID, 'com.facebook.encapp', 0)

    @unittest.mock.patch('encapp_tool.adb_cmds.installed_apps')
    def test_install_ok_shall_verify_if_encapp_is_installed(self, mock_apps):
        installed_encapp = [
            'com.android.package.a',
            'com.facebook.encapp',
            'com.android.package.c',
        ]
        not_installed_encapp = [
            'com.android.package.a',
            'com.android.package.b',
        ]
        mock_apps.side_effect = [installed_encapp, not_installed_encapp]
        self.assertTrue(encapp_tool.app_utils.install_ok(
            ADB_DEVICE_VALID_ID, 1))
        self.assertFalse(encapp_tool.app_utils.install_ok(
            ADB_DEVICE_VALID_ID, 1))

    @unittest.mock.patch('encapp_tool.adb_cmds.uninstall_apk')
    def test_uninstall_app_shall_uninstall_encapp(self, mock_uninstall):
        encapp_tool.app_utils.uninstall_app(ADB_DEVICE_VALID_ID, 1)
        mock_uninstall.assert_called_with(
            ADB_DEVICE_VALID_ID, 'com.facebook.encapp', 1
        )


if __name__ == '__main__':
    unittest.main()
