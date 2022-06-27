#!/usr/bin/env python3
import re
from subprocess import PIPE, Popen, SubprocessError
from typing import Dict, List, Optional, Tuple

ENCAPP_OUTPUT_FILE_NAME_RE = r"encapp_.*"


def run_cmd(cmd: str, debug: int = 0) -> Tuple[bool, str, str]:
    """Run sh command

    Args:
        cmd (str): Command string to be executed by subprocess
        debug (int): Debug level from 0 (No debug)

    Returns:
        Tuple with boolean (True cmd execution succeeded, false otherwise)
        stdout and stderr messages.
    """
    try:
        if debug > 0:
            print(cmd, sep=" ")
        with Popen(cmd, shell=True, stdout=PIPE, stderr=PIPE) as process:
            stdout, stderr = process.communicate()
            ret = bool(process.returncode == 0)
    except SubprocessError:
        print("Failed to run command: " + cmd)
        return False, "", ""

    return ret, stdout.decode(), stderr.decode()


def get_device_info(serial_inp: Optional[str], debug=0) -> Tuple[Dict, str]:
    """Get android device information for an specific device

    Get device information by executing and parsing command adb devices -l,
    if not serial input specified and only one device connected, it returns
    that device information.If serial input device not connected or none device
    is connected, it fails.

    Args:
        serial_inp (str): Expected serial number to analyze.
        debug (): Debug level

    Returns:
        device_info, serial; Where device info is a map with device info
        and serial is the serial no. of the device.
    """
    device_info = get_connected_devices(debug)
    assert len(device_info) > 0, "error: no devices connected"
    if debug > 2:
        print(f"available devices: {device_info}")

    # select output device
    if serial_inp is None:
        # if user did not select a serial_inp, make sure there is only one
        # device available
        assert len(device_info) == 1, f"error: need to choose a device [{', '.join(device_info.keys())}]"
        serial = list(device_info.keys())[0]
        model = device_info[serial]

    else:
        # if user forced a serial number, make sure it is available
        assert serial_inp in device_info, f"error: device {serial_inp} not available"
        serial = serial_inp
        model = device_info[serial]

    if debug > 0:
        print(f"selecting device: serial: {serial} model: {model}")

    return model, serial


def remove_files_using_regex(
    serial: str, regex_str: str, location: str, debug: int
) -> None:
    """Remove files from an android device specific path following regex.

    Args:
        serial (str): Android device serial no.
        regex_str (str): Regex to match file string
        location (str): Path/directory to analyze and remove files from
        debug (int): Debug level
    """
    adb_cmd = "adb -s " + serial + " shell ls " + location
    _, stdout, _ = run_cmd(adb_cmd, debug)
    output_files = re.findall(regex_str, stdout, re.MULTILINE)
    for file in output_files:
        # remove the output
        adb_cmd = "adb -s " + serial + " shell rm " + location + file
        run_cmd(adb_cmd, debug)


def get_connected_devices(debug: int) -> Dict:
    """Get adb connected devices

    Get adb connected devices info by running adb devices -l

    Args:
        debug (int): Debug level

    Returns:
        Map of found connected devices through adb, with serial no.
        as key.
    """
    # list all available devices
    adb_cmd = "adb devices -l"
    ret, stdout, _ = run_cmd(adb_cmd, debug)
    assert ret, "error: failed to get adb devices"
    # parse list
    device_info = {}
    for line in stdout.splitlines():
        if line in ["List of devices attached", ""]:
            continue
        serial = line.split()[0]
        item_dict = {}
        for item in line.split()[1:]:
            # ':' used to separate key/values
            if ":" in item:
                key, val = item.split(":", 1)
                item_dict[key] = val
        # ensure the 'model' field exists
        if "model" not in item_dict:
            item_dict["model"] = "generic"
        device_info[serial] = item_dict
    return device_info


def get_app_pid(serial: str, package_name: str, debug=0):
    """Get running pid for an specified program.

    Args:
        serial (str): Android device serial no.
        package_name (str): Android package name.
        debug (int): Debug level

    Returns:
        Current process ID if program is running,
        -1 if process not running; -2 if fail to process.
    """
    pid = -1
    adb_cmd = f"adb -s {serial} shell pidof {package_name}"
    ret, stdout, _ = run_cmd(adb_cmd, debug)
    if ret is True and stdout:
        try:
            pid = int(stdout)
        except ValueError:
            print(f'Unable to cast stdout: "{stdout}" to int')
            pid = -2
    return pid


def install_apk(serial: str, apk_to_install: str, debug=0):
    """Install apk on android device.

    Args:
        serial (str): Android device serial no.
        apk_to_install (str): Host path of apk to install
        debug (int): Debug level

    Raises:
        RuntimeError if unable to install app on device
    """
    r_code, _, err = run_cmd(
        f"adb -s {serial} install -g {apk_to_install}",
        debug
    )
    if r_code is False:
        raise RuntimeError(
            f"Unable to install {apk_to_install} "
            f"at device {serial} due to {err}"
        )


def uninstall_apk(serial: str, apk: str, debug=0):
    """Uninstall app at android device

    Args:
        serial (str): Android device serial no.
        apk (str): apk/package to uninstall
        debug (int): Debug level
    """
    package_list = installed_apps(serial, debug)
    if apk in package_list:
        run_cmd(f"adb -s {serial} uninstall {apk}", debug)
    else:
        print(f"warning: {apk} not installed")


def installed_apps(serial: str, debug=0) -> List:
    """Get installed apps at android device using pm list

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level

    Returns:
        List of packages installed at android device.
    """
    ret, stdout, stderr = run_cmd(
        f"adb -s {serial} shell pm list packages", debug
    )
    assert ret, f"error: failed to get installed app list: {stderr}"
    return _parse_pm_list_packages(stdout)


def _parse_pm_list_packages(stdout: str) -> List:
    """Parse pm list output to get packages list

    Args:
        stdout (str): pm list cmd output string

    Returns:
        List of packages installed at android device
    """
    package_list = []
    for line in stdout.splitlines():
        # ignore blank lines
        if not line:
            continue
        if line.startswith("package:"):
            package_list.append(line[len("package:"):])
    return package_list


def grant_storage_permissions(serial: str, package: str, debug=0):
    """Grant all android storage permissions to a package

    Args:
        serial (str): Android device serial no.
        package (str): Android package name
        debug (int): Debug level
    """
    run_cmd(
        f"adb -s {serial} shell pm grant {package} "
        "android.permission.WRITE_EXTERNAL_STORAGE",
        debug,
    )
    run_cmd(
        f"adb -s {serial} shell pm grant {package} "
        "android.permission.READ_EXTERNAL_STORAGE",
        debug,
    )
    run_cmd(
        f"adb -s {serial} shell appops set --uid {package} "
        "MANAGE_EXTERNAL_STORAGE allow",
        debug,
    )


def grant_camera_permission(serial: str, package: str, debug=0):
    """Grant android camera permission to a package

    Args:
        serial (str): Android device serial no.
        package (str): Android package name
        debug (int): Debug level
    """
    run_cmd(
        f"adb -s {serial} shell pm grant {package} "
        "android.permission.CAMERA",
        debug
    )


def force_stop(serial: str, package: str, debug=0):
    """Stop everything associated with app's package name

    Args:
        serial (str): Android device serial no.
        package (str): Android package name
        debug (int): Debug level
    """
    run_cmd(f"adb -s {serial} shell am force-stop {package}", debug)
