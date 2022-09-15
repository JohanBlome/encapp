#!/usr/bin/env python3

import os

from encapp_tool import adb_cmds
from encapp_tool._version import __version__

APPNAME_MAIN = 'com.facebook.encapp'
ACTIVITY = f'{APPNAME_MAIN}/.MainActivity'

MODULE_PATH = os.path.dirname(__file__)
SCRIPT_DIR = os.path.abspath(os.path.join(MODULE_PATH, os.pardir))

RELEASE_APK_DIR = os.path.abspath(
    os.path.join(SCRIPT_DIR, os.pardir, 'app', 'releases')
)
APK_NAME_MAIN = f'{APPNAME_MAIN}-v{__version__}-debug.apk'
APK_MAIN = os.path.join(RELEASE_APK_DIR, APK_NAME_MAIN)


def install_app(serial, debug=0):
    """Install encapp apk and grant required permissions

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level
    """
    adb_cmds.install_apk(serial, APK_MAIN, debug)
    adb_cmds.grant_camera_permission(serial, debug)
    adb_cmds.grant_storage_permissions(serial, debug)
    adb_cmds.force_stop(serial, APPNAME_MAIN, debug)


def install_ok(serial: str, debug=0) -> bool:
    """Verify encapp installation at android device

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level

    Returns:
        True if encapp is installed at device, False otherwise.
    """
    package_list = adb_cmds.installed_apps(serial, debug)
    if APPNAME_MAIN not in package_list:
        return False
    return True


def uninstall_app(serial: str, debug=0):
    """Uninstall encapp at android device

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level
    """
    adb_cmds.uninstall_apk(serial, APPNAME_MAIN, debug)


def force_stop(serial: str, debug=0):
    """Force stop of application

    Args:
       serial (str): Android device serial no.
       debug (int): Debug level
    """
    adb_cmds.force_stop(serial, APPNAME_MAIN, debug)
