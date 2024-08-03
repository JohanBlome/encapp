#!/usr/bin/env python3

import re
import os
import glob
import hashlib
import subprocess
import tempfile
import time
import typing


ENCAPP_OUTPUT_FILE_NAME_RE = r"encapp_.*"
USE_IDB = False
IDB_BUNDLE_ID = "Meta.Encapp"
IOS_VERSION_NAME = ""
IOS_MAJOR_VERSION = -1

# size for split adb push
MAX_SIZE_BYTES = 20000000


def run_cmd(cmd: str, debug: int = 0) -> typing.Tuple[bool, str, str]:
    """Run sh command

    Args:
        cmd (str/list): Command string/list to be executed by subprocess
        debug (int): Debug level from 0 (No debug)

    Returns:
        Tuple with boolean (True cmd execution succeeded, False otherwise)
        stdout and stderr messages.
    """
    try:
        if debug > 0:
            print(cmd, sep=" ")
        with subprocess.Popen(
            cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        ) as process:
            stdout, stderr = process.communicate()
            ret = bool(process.returncode == 0)
    except subprocess.SubprocessError:
        print(f"Failed to run command: {cmd}")
        return False, "", ""

    return ret, stdout.decode(), stderr.decode()


def get_device_info(
    serial: typing.Optional[str], debug=0
) -> typing.Tuple[typing.Dict, str]:
    """Get android device information for an specific device

    Get device information by executing and parsing command adb devices -l,
    if not serial input specified and only one device connected, it returns
    that device information.If serial input device not connected or none device
    is connected, it fails.

    Args:
        serial (str): Expected serial number to analyze.
        debug (): Debug level

    Returns:
        model, serial; Where model is the model and serial is the serial
            number of the device.
    """
    device_info = get_connected_devices(debug)
    assert len(device_info) > 0, "error: no devices connected"
    if debug > 2:
        print(f"available devices: {device_info}")

    # select output device
    if serial is None:
        # if user did not select a serial, make sure there is only one
        # device available
        assert len(device_info) > 0, "error: no devices available"
        assert len(device_info) == 1, (
            "error: need to choose a device " f'[{", ".join(device_info.keys())}]'
        )
        serial = list(device_info.keys())[0]
    # ensure the serial number is available

    assert serial in device_info, f"error: device {serial} not available"
    # get the model id

    model = device_info[serial]["model"].lower()
    if debug > 0:
        print(f"selecting device: serial: {serial} model: {model}")

    if USE_IDB:
        global IOS_VERSION, IOS_MAJOR_VERSION
        IOS_VERSION = device_info[serial]["version"]
        # iOS 17.3.1
        m = re.search(r"([\d]+)\.([\d]+)\.([\d]+)", IOS_VERSION)
        if m:
            IOS_MAJOR_VERSION = int(m.group(1))

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
    if USE_IDB:
        cmd = f"idb file ls {location} --udid {serial}  --bundle-id {IDB_BUNDLE_ID}"
        _, stdout, _ = run_cmd(cmd, debug)
        output_files = re.findall(regex_str, stdout, re.MULTILINE)
        counter = 1
        for file in output_files:
            # remove the output
            print(f"Removing {counter}/{len(output_files)}", end="\r")
            cmd = f"idb file rm {location}/{file} --udid {serial}  --bundle-id {IDB_BUNDLE_ID}"
            run_cmd(cmd, debug)
            counter += 1
    else:
        adb_cmd = f"adb -s {serial} shell ls {location}/"
        _, stdout, _ = run_cmd(adb_cmd, debug)
        output_files = re.findall(regex_str, stdout, re.MULTILINE)
        for file in output_files:
            # remove the output
            adb_cmd = f"adb -s {serial} shell rm {location}/{file}"
            run_cmd(adb_cmd, debug)


def get_connected_devices(debug: int) -> typing.Dict:
    """Get adb connected devices

    Get adb connected devices info by running adb devices -l

    Args:
        debug (int): Debug level

    Returns:
        Map of found connected devices through adb, with serial no.
        as key.
    """
    # list all available devices
    if USE_IDB:
        cmd = "idb list-targets | grep -i booted"
        ret, stdout, _ = run_cmd(cmd, debug)
        assert ret, "error: failed to get adb devices"
        # parse list
        device_info = {}
        for line in stdout.splitlines():
            item_dict = {}
            items = line.split("|")
            item_dict["model"] = items[0].strip()
            item_dict["type"] = items[3].strip()
            item_dict["version"] = items[4].strip()
            item_dict["platform"] = items[5].strip()
            device_info[items[1].strip()] = item_dict
        return device_info

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
    if USE_IDB:
        # This is only for >= V17 but lower version do not need the pid (at this point)
        cmd = f" xcrun devicectl device info processes --device  {serial} | grep Encapp.app"
        ret, stdout, _ = run_cmd(cmd, debug)
        if ret and stdout:
            m = re.search(r"^[0-9]*", stdout)
            if m:
                pid = int(m.group(0))
    else:
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
    r_code, _, err = run_cmd(f"adb -s {serial} install -g {apk_to_install}", debug)
    if r_code is False:
        raise RuntimeError(
            f"Unable to install {apk_to_install} " f"at device {serial} due to {err}"
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


def installed_apps(serial: str, debug=0) -> typing.List:
    """Get installed apps at android device using pm list

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level

    Returns:
        List of packages installed at android device.
    """
    ret, stdout, stderr = run_cmd(f"adb -s {serial} shell pm list packages", debug)
    assert ret, f"error: failed to get installed app list: {stderr}"
    return _parse_pm_list_packages(stdout)


def _parse_pm_list_packages(stdout: str) -> typing.List:
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
            package_list.append(line[len("package:") :])
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
        f"adb -s {serial} shell pm grant {package} " "android.permission.CAMERA", debug
    )


def force_stop(serial: str, package: str, debug=0):
    """Stop everything associated with app's package name

    Args:
        serial (str): Android device serial no.
        package (str): Android package name
        debug (int): Debug level
    """
    if USE_IDB:
        # for iso 17 devicectl needs to be used and it cannot terminate an application directly
        # lookup pid and send sigterm
        if IOS_MAJOR_VERSION < 17:
            run_cmd(f"idb terminate --udid {serial}  {IDB_BUNDLE_ID}", debug)
        else:
            pid = get_app_pid(serial, package, debug)
            if pid > 0:
                run_cmd(
                    f"xcrun devicectl device process signal --signal sigterm --device  {serial} --pid {pid}  ",
                    debug,
                )
    else:
        run_cmd(f"adb -s {serial} shell am force-stop {package}", debug)


def reset_logcat(serial: str, debug=0):
    """Clear logcat (if possible)

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level
    """
    run_cmd(f"adb -s {serial} logcat -c", debug)


def logcat_dump(serial: str, debug=0) -> str:
    """Capture current logcat buffers

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level

    Returns:
      Current logcat dump
    """
    ret, stdout, stderr = run_cmd(f"adb -s {serial} logcat -d", debug)
    assert ret, f"error: failed to dump logcat: {stderr}"
    return stdout


def parse_getprop(stdout: str) -> dict:
    """Parse the output of getprop

    Args:
        stdout (str): getprop stdout

    Returns:
      Current props dump
    """
    props_dict = {}
    if USE_IDB:
        # We translate to some of the known android names so comparison is easier...
        # on a pysical device there will be more info than on an emulator

        match = re.search(r"'HardwarePlatform': '([\w\d\s]*)'", stdout)
        if match:
            props_dict["ro.board.platform"] = match.group(1)

        match = re.search(r"'HardwareModel': '([\w\d\s]*)'", stdout)
        if match:
            props_dict["ro.product.model"] = match.group(1)

        match = re.search(r"'ProductVersion': '([\w\d\s]*)'", stdout)
        if match:
            props_dict["ProductVersion"] = match.group(1)

        match = re.search(r"'BuildVersion': '([\w\d\s]*)'", stdout)
        if match:
            props_dict["BuildVersion"] = match.group(1)

        match = re.search(r"'CPUArchitecture': '([\w\d\s]*)'", stdout)
        if match:
            props_dict["CPUArchitecture"] = match.group(1)

        if "ro.product.model" not in props_dict:
            match = re.search(r"target_type=<[\w.]*:\s?'([\w\d\s]*)'>", stdout)
            target_type = ""
            if match:
                target_type = match.group(1)
            match = re.search(r"name='([\w\d\s]*)'", stdout)
            if match:
                props_dict["ro.product.model"] = f"{match.group(1)}_{target_type}"
            else:
                props_dict["ro.product.model"] = "unknown ios"
        if "ro.board.platform" not in props_dict:
            match = re.search(r"os_version='([\w\d\s\.]*)'", stdout)
            os_version = ""
            if match:
                os_version = match.group(1)
            match = re.search(r"architecture='([\w\d\s]*)'", stdout)
            arch = ""
            if match:
                arch = match.group(1)
                props_dict["ro.board.platform"] = f"{os_version}-{arch}"
        match = re.search(r"udid='([\w\d\s\-]*)'", stdout)
        serial = ""
        if match:
            serial = match.group(1)
        props_dict["ro.serialno"] = serial
        # The rest is not that interesting.

    else:
        # [persist.sys.boot.reason]: []
        # [persist.sys.boot.reason.history]: [reboot,powerloss,1659352492
        # reboot,adb,1663120966
        # reboot,`db,1661299722]
        # [persist.sys.call_debug_v2]: [true]
        reading_val = False
        key = ""
        val = ""
        for line in stdout.splitlines():
            if not line:
                continue
            if not reading_val:
                try:
                    key, val = line.split(": ")
                    key = key.lstrip("[").rstrip("]")
                    val = val.lstrip("[")
                    if val[-1] == "]":
                        val = val.lstrip("[").rstrip("]")
                        # single-line pair
                        props_dict[key] = val
                    else:
                        # multiple-line value
                        reading_val = True
                    val = val.lstrip("[").rstrip("]")
                except:
                    print(f"Failed parsing: {line}")
            else:
                if line[-1] != "]":
                    # continued multiple-line value
                    val += "\n" + line
                else:
                    # end of multiple-line value
                    val += "\n" + line.rstrip("]")
                    props_dict[key] = val
                    reading_val = False
    return props_dict


def getprop(serial: str, debug=0) -> dict:
    """Capture current props

    Args:
        serial (str): Android device serial no.
        debug (int): Debug level

    Returns:
      Current props dump
    """
    if USE_IDB:
        ret, stdout, stderr = run_cmd(f"idb describe --udid {serial}", debug)
        assert ret, f"error: failed to getprop: {stderr}"
        return parse_getprop(stdout)
    else:
        ret, stdout, stderr = run_cmd(f"adb -s {serial} shell getprop", debug)
        assert ret, f"error: failed to getprop: {stderr}"
        return parse_getprop(stdout)


def get_device_size(serial, filepath, debug):
    # check if the file exists
    ret, stdout, stderr = run_cmd(f"adb -s {serial} shell test -e {filepath}", debug)
    if not ret:
        return -1
    # get the size in bytes
    try:
        ret, stdout, stderr = run_cmd(
            f'adb -s {serial} shell stat -c "%s" {filepath}', debug
        )
        filesize = int(stdout)
    except:
        print(f"Failed to grap file size for {filepath}")
        return 0
    return filesize


def get_device_hash(serial, filepath, debug):
    # check if the file exists
    ret, stdout, stderr = run_cmd(f"adb -s {serial} shell test -e {filepath}", debug)
    if not ret:
        return -1
    # get a hash
    try:
        ret, stdout, stderr = run_cmd(f"adb -s {serial} shell md5sum {filepath}", debug)
        filehash = stdout.split()[0]
    except:
        print(f"Failed to calc hash for {filepath}")
        return 0
    return filehash


def get_host_hash(filepath, debug):
    hash_md5 = hashlib.md5()
    with open(filepath, "rb") as f:
        for chunk in iter(lambda: f.read(4096), b""):
            hash_md5.update(chunk)
    return hash_md5.hexdigest()


def file_exists_in_device(filename, serial, debug=False):
    if USE_IDB:
        cmd = f"idb file ls /Documents --udid {serial}  --bundle-id {IDB_BUNDLE_ID}"
        _, stdout, _ = run_cmd(cmd, debug)
        output_files = re.findall(f"{filename}", stdout, re.MULTILINE)
        return len(output_files) > 0


def file_already_in_device(host_filepath, serial, device_filepath, fast_copy, debug):
    # Do not check .pbtxt files
    if host_filepath[-6] == ".pbtxt":
        return False

    if USE_IDB and fast_copy:
        # TODO: fix
        basename = os.path.basename(device_filepath)
        cmd = f"idb file ls Documents --udid {serial}  --bundle-id {IDB_BUNDLE_ID}"
        _, stdout, _ = run_cmd(cmd, debug)
        output_files = re.findall(f"{basename}", stdout, re.MULTILINE)
        return len(output_files) > 0
    # 1. check the size
    device_filesize = get_device_size(serial, device_filepath, debug)
    if device_filesize == -1:
        # file not in device
        return False
    host_filesize = os.path.getsize(host_filepath)
    if device_filesize != host_filesize:
        # files have different sizes
        return False
    if fast_copy:
        # optimistic copy: assume same size means same file without checking
        # the hash
        return True
    # 2. check the hash
    device_filehash = get_device_hash(serial, device_filepath, debug)
    host_filehash = get_host_hash(host_filepath, debug)
    if device_filehash != host_filehash:
        # files have different hashes
        return False
    return True


def push_file_to_device(filepath, serial, device_workdir, fast_copy, debug):
    if not os.path.exists(filepath):
        print(f'error: file "{filepath}" does not exist, check path')
        return False
    if not os.access(filepath, os.R_OK):
        print(f'error: file "{filepath}" is not readable')
        return False
    # check whether a file with the same name, size, and hash exists.
    # In that case, skip the step.
    if USE_IDB:
        device_filepath = os.path.join(device_workdir, os.path.basename(filepath))
        if file_already_in_device(filepath, serial, device_filepath, fast_copy, debug):
            return True
        ret, stdout, _ = run_cmd(
            f"idb file push  {filepath} {device_workdir}/ --udid {serial} --bundle-id {IDB_BUNDLE_ID}",
            debug,
        )
        if not ret:
            print(f'error: copying "{filepath}" to  {device_workdir}/ : {stdout}')
    else:
        device_filepath = os.path.join(device_workdir, os.path.basename(filepath))
        if file_already_in_device(filepath, serial, device_filepath, fast_copy, debug):
            return True
        # push the file
        ret = push_file_to_device_android(filepath, serial, device_workdir, debug)
    return ret


def push_file_to_device_android(
    filepath, serial, device_workdir, debug, max_size_bytes=MAX_SIZE_BYTES
):
    # 0. try a one-off copy
    cmd = f"adb -s {serial} push {filepath} {device_workdir}/"
    ret, stdout, stderr = run_cmd(cmd, debug)
    if ret:
        return ret
    print(f'warning: cannot copy "{filepath}": {stdout=} {stderr=}')
    if max_size_bytes == 0:
        return ret
    # 1. try split copy
    print(f'warning: trying split push for "{filepath}"')
    # 1.1. split filepath in pieces
    prefix = tempfile.NamedTemporaryFile(prefix="split.").name + "."
    cmd = f"split -b {max_size_bytes} {filepath} {prefix}"
    ret, stdout, stderr = run_cmd(cmd, debug)
    assert ret, f"error: cannot split {filepath}"
    # 1.2. push all pieces one by one
    split_pieces = glob.glob(f"{prefix}*")
    for split_piece in split_pieces:
        time.sleep(1)
        ret = push_file_to_device_android(
            split_piece, serial, device_workdir, debug, max_size_bytes=0
        )
        if not ret:
            print(f'error: cannot copy "{split_piece}": {stdout=} {stderr=}')
            return ret
    # 1.3. cat all pieces together
    device_split_pieces = list(
        os.path.join(device_workdir, os.path.basename(split_piece))
        for split_piece in split_pieces
    )
    device_filepath = os.path.join(device_workdir, os.path.basename(filepath))
    cmd = f"adb -s {serial} shell 'cat {' '.join(device_split_pieces)} > {device_filepath}'"
    ret, stdout, stderr = run_cmd(cmd, debug)
    if not ret:
        print(f"error: cannot cat split pieces together: {stdout=} {stderr=}")
        return ret
    # 1.4. check the final file is ok
    cmd = f"md5sum {filepath}"
    ret, stdout, stderr = run_cmd(cmd, debug)
    assert ret, f"error: cannot md5sum {filepath}"
    local_md5sum = stdout.split()[0]
    cmd = f"adb -s {serial} shell md5sum {device_filepath}"
    ret, stdout, stderr = run_cmd(cmd, debug)
    assert ret, f"error: cannot md5sum {device_filepath}"
    device_md5sum = stdout.split()[0]
    if local_md5sum != device_md5sum:
        print(
            f"error: push file was broken: md5sum's differ {local_md5sum}!={device_md5sum}"
        )
    # 1.5. clean up the split pieces at the device
    cmd = f"adb -s {serial} shell rm {' '.join(device_split_pieces)}"
    ret, stdout, stderr = run_cmd(cmd, debug)
    return True


def pull_files_from_device(
    serial: str, regex_str: str, location: str, debug: int
) -> None:

    if USE_IDB:
        cmd = f"idb file ls {location} --udid {serial}  --bundle-id {IDB_BUNDLE_ID}"
        _, stdout, _ = run_cmd(cmd, debug)
        output_files = re.findall(regex_str, stdout, re.MULTILINE)
        counter = 1
        for file in output_files:
            print(f"Pulling {counter}/{len(output_files)}", end="\r")
            cmd = f"idb file pull {location}/{file} .  --udid {serial}  --bundle-id {IDB_BUNDLE_ID}"
            run_cmd(cmd, debug)
            counter += 1
    else:
        adb_cmd = f"adb -s {serial} shell ls {location}/"
        _, stdout, _ = run_cmd(adb_cmd, debug)
        output_files = re.findall(regex_str, stdout, re.MULTILINE)
        for file in output_files:
            print(f"Pulling {counter}/{len(output_files)}", end="\r")
            adb_cmd = f"adb -s {serial} shell pull {location}/{file} . "
            run_cmd(adb_cmd, debug)
            counter += 1


def set_idb_mode(mode):
    global USE_IDB
    USE_IDB = mode


def is_using_idb():
    global USE_IDB
    return USE_IDB


def set_bundleid(bundleid):
    global IDB_BUNDLE_ID
    IDB_BUNDLE_ID = bundleid
