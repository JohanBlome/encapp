#!/usr/bin/env python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
import copy
import humanfriendly
import json
import sys
import argparse
from argparse_formatter import FlexiFormatter
import itertools
import re
import shutil
import tempfile
import time
import datetime
from google.protobuf import text_format
import google.protobuf.descriptor_pool as descriptor_pool
import multiprocessing

import encapp_tool
import encapp_tool.app_utils
import encapp_tool.adb_cmds
import encapp_tool.ffutils
import encapp_quality
import copy
import random
import pandas as pd
import pprint

SCRIPT_ROOT_DIR = os.path.abspath(
    os.path.join(encapp_tool.app_utils.SCRIPT_DIR, os.pardir)
)
SCRIPT_PROTO_DIR = os.path.abspath(
    os.path.join(encapp_tool.app_utils.SCRIPT_DIR, "proto")
)
sys.path.append(SCRIPT_ROOT_DIR)
sys.path.append(SCRIPT_PROTO_DIR)
import tests_pb2 as tests_definitions  # noqa: E402


RD_RESULT_FILE_NAME = "rd_results.json"

DEBUG = False

QUALITY_PROCESSES = []

FUNC_CHOICES = {
    "help": "show help options",
    "install": "install apks",
    "uninstall": "uninstall apks",
    "list": "list codecs and devices supported. It can be used to search and file. For more options use 'list -h'.",
    "run": "run codec test case",
    "kill": "kill application",
    "clear": "remove all encapp associated files",
    "reset": "remove all encapp created output files (for debuggin)",
    "pull_result": "pull results to current directory",
}

default_values = {
    "debug": 0,
    "func": "help",
    "device_workdir": "/sdcard",
    "local_workdir": None,
    "install": False,
    "videofile": None,
    "configfile": None,
    "encoder": None,
    "bps": None,
    "ignore_results": False,
    "fast_copy": False,
    "idb": False,
    "multiply": "",
    "resolution": "",
    "mediastore": None,
    "framerate": None,
    "pix_fmt": "yuv420p",
}

OPERATION_TYPES = ("batch", "realtime")
PIX_FMT_TYPES_VALUES = {
    "yuv420p": 0,
    "yvu420p": 1,
    "nv12": 2,
    "nv21": 3,
    "rgba": 4,
}
PIX_FMT_TYPES = {
    "yuv420p": "yuv",
    "yvu420p": "yuv",
    "nv12": "yuv",
    "nv21": "yuv",
    "rgba": "rgba",
}
PREFERRED_PIX_FMT = "yuv420p"
KNOWN_CONFIGURE_TYPES = {
    "codec": str,
    "encode": bool,
    "surface": bool,
    "mime": str,
    "bitrate": int,
    "bitrate-mode": int,
    "durationUs": int,
    "resolution": str,
    "width": int,
    "height": int,
    "color-format": int,
    "color-standard": int,
    "color-range": int,
    "color-transfer": int,
    "color-transfer-request": int,
    "frame-rate": int,
    "i-frame-interval": int,
    "intra-refresh-period": int,
    "latency": int,
    "repeat-previous-frame-after": int,
    "ts-schema": str,
}
KNOWN_RUNTIME_TYPES = {
    "video-bitrate": int,
    "request-sync": None,
    "drop": None,
    "dynamic-framerate": int,
}
TYPE_LIST = (
    "int",
    "float",
    "str",
    "bool",
    "null",
)
BITRATE_MODE_VALUES = {
    "cq": 0,
    "vbr": 1,
    "cbr": 2,
    "cbr_fd": 3,
}
FFPROBE_FIELDS = {
    "codec_name": "codec-name",
    "width": "width",
    "height": "height",
    "pix_fmt": "pix-fmt",
    "color_range": "color-range",
    "color_space": "color-space",
    "color_transfer": "color-transfer",
    "color_primaries": "color-primaries",
    "r_frame_rate": "framerate",
    "duration": "duration",
}

video_extensions = [
    ".mp4",
    ".y4m",
    ".mov",
    ".raw",
    ".yuv",
    ".ivf",
    ".mkv",
]


def is_video_extension(filename):
    ending = f".{filename.rsplit('.')[-1]}"
    if ending is not None and ending in video_extensions:
        return True

    return False


def get_pix_fmt(numerical_id):
    return next(
        key for key, value in PIX_FMT_TYPES_VALUES.items() if value == numerical_id
    )


def parse_resolution(resolution):
    reg = "([0-9]*).([0-9]*)"
    match = re.search(reg, resolution)
    if match:
        return [int(match.group(1)), int(match.group(2))]
    return []


def remove_encapp_gen_files(
    serial, device_workdir=default_values["device_workdir"], debug=0
):
    # remove any files that are generated in previous runs
    regex_str = encapp_tool.adb_cmds.ENCAPP_OUTPUT_FILE_NAME_RE
    encapp_tool.adb_cmds.remove_files_using_regex(
        serial, regex_str, device_workdir, debug
    )


def wait_for_exit(serial, debug=0):
    if debug > 0:
        print("\n\n*** Wait for exit **\n\n")
    time.sleep(2)
    if encapp_tool.adb_cmds.USE_IDB:
        state = "Running"
        while state == "Running":
            time.sleep(1)
            # ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(f"idb list-apps  --fetch-process-state  --udid {serial} | grep {encapp_tool.adb_cmds.IDB_BUNDLE_ID}")
            # state = stdout.split("|")[4].strip()
            # Since the above does not work (not state info), let us look for the lock file
            if not encapp_tool.adb_cmds.file_exists_in_device("running.lock", serial):
                state = "Done"
    else:
        pid = -1
        current = 1
        while current != -1:
            current = encapp_tool.adb_cmds.get_app_pid(
                serial, encapp_tool.app_utils.APPNAME_MAIN, debug
            )
            if current > 0:
                pid = current
            time.sleep(1)
        if pid != -1 and debug > 0:
            print(f"exit from {pid}")

    if debug > 0:
        print("\n\n*** Done waiting **\n\n")


def valid_path(text):
    ret = re.sub(r"[ \/?*:%&{}$!+|=<>#]", ".", text)
    return ret


def run_encapp_test(protobuf_txt_filepath, serial, device_workdir, run_cmd="", debug=0):
    if debug > 0:
        print(f"running test: {protobuf_txt_filepath}")
    # TODO: add special exec command here.
    if len(run_cmd) > 0:
        # TODO: can we assume adb?
        encapp_tool.adb_cmds.reset_logcat(serial)
        cmd = f"adb -s {serial} shell {run_cmd} {protobuf_txt_filepath}"
        encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)

    else:
        if encapp_tool.adb_cmds.USE_IDB:
            # remove log file first
            ret, _, stderr = encapp_tool.adb_cmds.run_cmd(
                f"idb file rm Documents/encapp.log --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} ",
                debug,
            )
            if encapp_tool.adb_cmds.IOS_MAJOR_VERSION < 17:
                ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
                    f"idb launch --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} "
                    f"test {protobuf_txt_filepath}",
                    debug,
                )
            else:
                ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
                    f"xcrun devicectl device process launch --device {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} "
                    f"test {protobuf_txt_filepath}",
                    debug,
                )
        else:
            # clean the logcat first
            encapp_tool.adb_cmds.reset_logcat(serial)
            ret, _, stderr = encapp_tool.adb_cmds.run_cmd(
                f"adb -s {serial} shell am start "
                f"-e workdir {device_workdir} "
                f"-e test {protobuf_txt_filepath} "
                f"{encapp_tool.app_utils.ACTIVITY}",
                debug,
            )
            assert ret, f"ERROR: {stderr}"
        wait_for_exit(serial)


def collect_results(
    local_workdir, protobuf_txt_filepath, serial, device_workdir, debug
):
    if debug > 0:
        print(f"collecting result: {protobuf_txt_filepath}")
    if encapp_tool.adb_cmds.USE_IDB:
        # There seems to be somethign fishy here which causes files to show up late
        # Not a problem if running a single file but multiple is a problem. Sleep...
        time.sleep(2)
        cmd = f"idb file ls {device_workdir}/ --udid {serial} --bundle-id {encapp_tool.adb_cmds.IDB_BUNDLE_ID}"
    else:
        cmd = f"adb -s {serial} shell ls {device_workdir}/"
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)

    # If we have a output_filename template in the test we need to check the begining
    test_suite = tests_definitions.TestSuite()
    local_path = local_workdir + "/" + os.path.basename(protobuf_txt_filepath)
    with open(local_path, "rb") as fd:
        text_format.Merge(fd.read(), test_suite)

    output_files = []
    for test in test_suite.test:
        if test.common and test.common.output_filename:
            filename = test.common.output_filename
            output_files += re.findall(f"{filename}.*", stdout, re.MULTILINE)

    output_files += re.findall(
        encapp_tool.adb_cmds.ENCAPP_OUTPUT_FILE_NAME_RE, stdout, re.MULTILINE
    )

    if encapp_tool.adb_cmds.USE_IDB:
        # Set app in standby so screen is not locked
        if encapp_tool.adb_cmds.IOS_MAJOR_VERSION < 17:
            cmd = (
                f"idb launch --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} standby",
            )
        else:
            cmd = (
                f"xcrun devicectl device process launch --device {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} standby",
            )
        encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    if debug > 0:
        print(f"outputfiles: {len(output_files)}")
    # prepare the local working directory to pull the files in
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)
    result_json = []

    total_number = len(output_files)
    counter = 1
    for file in output_files:
        if debug > 0:
            print(f"*** Pull file {counter}/{total_number}, {file} **")
        counter += 1
        if file == "":
            print("No file found")
            continue
        # pull the output file
        if encapp_tool.adb_cmds.USE_IDB:
            cmd = f"idb file pull {device_workdir}/{file} {local_workdir} --udid {serial} --bundle-id {encapp_tool.adb_cmds.IDB_BUNDLE_ID}"
        else:
            cmd = f"adb -s {serial} pull {device_workdir}/{file} " f"{local_workdir}"
        encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
        # remove the file on the device
        # Too slow at least on ios, remove everyting as a last all instead.
        if not encapp_tool.adb_cmds.USE_IDB:
            cmd = f"adb -s {serial} shell rm {device_workdir}/{file}"
        encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
        # append results file (json files) to final results
        if file.endswith(".json"):
            path, tmpname = os.path.split(file)
            result_json.append(os.path.join(local_workdir, tmpname))
    # remove/process the test file
    if encapp_tool.adb_cmds.USE_IDB:
        cmd = f"idb file pull {device_workdir}/{protobuf_txt_filepath} {local_workdir} --udid {serial} --bundle-id {encapp_tool.adb_cmds.IDB_BUNDLE_ID}"
    else:
        cmd = f"adb -s {serial} shell rm " f"{device_workdir}/{protobuf_txt_filepath}"
    encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    if debug > 0:
        print(f"results collect: {result_json}")
    # dump device information
    dump_device_info(serial, local_workdir, debug)
    # get logcat
    result_ok = False
    if encapp_tool.adb_cmds.USE_IDB:
        cmd = f"idb file pull {device_workdir}/encapp.log {local_workdir} --udid {serial} --bundle-id {encapp_tool.adb_cmds.IDB_BUNDLE_ID}"
        encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
        # Release the app
        encapp_tool.app_utils.force_stop(serial)
        # Remove test output files
        if encapp_tool.adb_cmds.IOS_MAJOR_VERSION < 17:
            ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
                f"idb launch --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} reset",
                debug,
            )
        else:
            ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
                f"xcrun devicectl device process launch --device {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} reset",
                debug,
            )
        # TODO: checks on ios
        result_ok = True
        return result_ok, result_json
    else:
        try:
            logcat_contents = encapp_tool.adb_cmds.logcat_dump(serial, debug=0)
            result_ok = parse_logcat(logcat_contents, local_workdir)
        except Exception as ex:
            print(f"Failed to parse logcat: {ex}")
        return result_ok, result_json


def dump_device_info(serial, local_workdir, debug):
    props_dict = encapp_tool.adb_cmds.getprop(serial, debug=debug)
    device_info = {
        "props": props_dict,
    }
    device_info_path = os.path.join(local_workdir, "device.json")
    with open(device_info_path, "w") as fd:
        fd.write(json.dumps(device_info, sort_keys=True, indent=4))


def parse_logcat(logcat_contents, local_workdir):
    # 1. store the logcat
    logcat_filepath = f"{local_workdir}/logcat.txt"
    with open(logcat_filepath, "wb") as fd:
        fd.write(logcat_contents.encode("utf8"))
    # 2. look for the status
    line_re = re.compile(
        r".*Test finished id: \"?(?P<id>[^\"]+)\"?.*run_id: (?P<run_id>[^\ ]+) result: \"?(?P<result>[^\"]+)\"?(?P<rem>.*)"
    )
    result_ok = True
    for line in logcat_contents.splitlines():
        line_match = line_re.search(line)
        if line_match:
            if line_match.group("result").lower() == "ok":
                # experiment went well
                print(
                    f'ok: test id: "{line_match.group("id")}" run_id: {line_match.group("run_id")} result: {line_match.group("result")}'
                )
                result_ok = True
            elif line_match.group("result") == "error":
                if "error:" not in line_match.group("rem"):
                    print(f'error: invalid error line match: "{line}"')
                error_re = re.compile(r".*error: \"(?P<error_code>.+)\"")
                error_match = error_re.search(line_match.group("rem"))
                error_code = "not specified"
                if error_match:
                    error_code = error_match.group("error_code")
                print(
                    f'error: test id: "{line_match.group("id")}" run_id: {line_match.group("run_id")} result: {line_match.group("result")} error_code: "{error_code}"'
                )
                result_ok = False
    if not result_ok:
        print(f'logcat has been saved to "{logcat_filepath}"')
    return result_ok


def verify_video_size(videofile, resolution):
    assert os.path.exists(videofile) and os.access(videofile, os.R_OK)
    if not encapp_tool.ffutils.video_is_raw(videofile):
        # in this case the actual encoded size is used
        return True
    # video is raw
    file_size = os.path.getsize(videofile)
    if resolution is not None:
        # TODO(chema): this assumes 4:2:0 subsampling, and therefore YUV
        framesize = int(resolution.split("x")[0]) * int(resolution.split("x")[1]) * 1.5
        if file_size % framesize == 0:
            return True
    return False


def update_file_paths(test, device_workdir=default_values["device_workdir"]):
    # update subtests
    for subtest in test.parallel.test:
        update_file_paths(subtest, device_workdir)
    # if custom encoder update name
    if test.configure.codec and test.configure.codec[-3:] == ".so":
        basename = os.path.basename(test.configure.codec)
        test.configure.codec = f"{device_workdir}/{basename}"
    # camera tests do not need any input file paths
    if test.input.filepath == "camera":
        return
    # update main test
    basename = os.path.basename(test.input.filepath)
    if encapp_tool.adb_cmds.USE_IDB:
        test.input.filepath = f"{basename}"
    else:
        test.input.filepath = f"{device_workdir}/{basename}"


def get_media_files(test, all_files):
    # TODO: remove?
    if test.input.filepath != "camera":
        name = os.path.basename(test.input.filepath)
        if name not in all_files:
            all_files.add(name)
    for subtest in test.parallel.test:
        if subtest.input.filepath != "camera":
            get_media_files(subtest, all_files)
    return


def add_files_to_push(test, files_to_push):
    # Check customencoder
    if test.configure.codec and test.configure.codec[-3:] == ".so":
        full_path = os.path.expanduser(test.configure.codec)
        if full_path not in files_to_push:
            files_to_push.add(full_path)
    if test.input.filepath != "camera":
        full_path = os.path.expanduser(test.input.filepath)
        if full_path not in files_to_push:
            files_to_push.add(full_path)
    for subtest in test.parallel.test:
        if subtest.input.filepath != "camera":
            add_files_to_push(subtest, files_to_push)
    return


def update_media_files(test, options):
    if test.input.filepath != "camera":
        update_media(test, options)
    for subtest in test.parallel.test:
        if subtest.input.filepath != "camera":
            update_media_files(subtest, options)
    return


def parse_multiply(multiply):
    definition = []
    # [32,][3,"test"] or 32
    reg = r'([0-9]*),\s?([\w.\/"]*)'
    m = re.findall(reg, multiply)
    if m:
        for pair in m:
            definition.append([int(pair[0]), pair[1]])

    elif multiply is not None and len(multiply) > 0:
        # Simple copy
        definition.append([int(multiply), None])

    return definition


def update_fileoutput_names(test):
    """Upate output file name is existing according to placeholders
    Example: output_filename: "[input.filepath].[configure.bitrate].[XXX]"
    Where the input.filepath will set the basename from the test def and
    the bitrate will be set from the configure.bitrate.
    XXX means three random bytes in hex format.
    """
    if test.common.output_filename is not None:
        # Check if we have any placeholders
        reg = r"\[[\w.]*\]"
        filename = test.common.output_filename
        while True:
            m = re.search(reg, filename)

            if m:
                text = m.group(0)[1:-1]
                # check if match only contains X
                m2 = re.search(r"X*", text)

                if len(m2.group(0)) > 0:
                    # create random byte values and print as hex
                    nbr = len(m2.group(0))
                    hex = "".join(
                        [random.choice("0123456789abcdef") for i in range(nbr)]
                    )
                    filename = filename.replace(m.group(0), hex)
                else:
                    # split at .
                    parts = text.split(".")
                    value = ""
                    if hasattr(test, parts[0]):
                        comp = getattr(test, parts[0])
                        if hasattr(comp, parts[1]):
                            # If float and has not fractional part make it int
                            value = ""
                            value_ = getattr(comp, parts[1])
                            if isinstance(value_, float) and value_.is_integer():
                                value_ = int(value_)
                            value = str(value_)
                            # special case is filepaths (windows?)
                            if "/" in value or "\\" in value:
                                value = os.path.basename(value)
                                # however, at this stage we may have a conversion
                                # and we are more likely interested in the first part
                                for ex in video_extensions:
                                    if ex in value:
                                        lindex = value.index(ex)
                                        if lindex > 0:
                                            value = value[0:lindex]
                    filename = filename.replace(m.group(0), value)

            else:
                break
    if len(filename) > 0:
        test.common.output_filename = filename


def read_and_update_proto(protobuf_txt_filepath, local_workdir, options):
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)

    test_suite = tests_definitions.TestSuite()
    with open(protobuf_txt_filepath, "rb") as fd:
        text_format.Merge(fd.read(), test_suite)

    updated_test_suite = tests_definitions.TestSuite()
    update_codec_testsuite(
        test_suite,
        updated_test_suite,
        local_workdir,
        options.device_workdir,
        options.replace,
        options.mediastore,
    )

    test_suite = updated_test_suite
    test_suite = create_tests_from_definition_expansion(test_suite)
    if options.dry_run:
        # Write and exit
        with open(protobuf_txt_filepath, "w") as f:
            f.write(text_format.MessageToString(test_suite))
        return test_suite, [], protobuf_txt_filepath

    # now we need to go through all test and update media
    for test in test_suite.test:
        update_media_files(test, options)

    # 2. get a list of all the media files that will need to be pushed
    files_to_push = set()
    for test in test_suite.test:
        add_files_to_push(test, files_to_push)

    # 3. save the media files
    for filepath in files_to_push:
        # https://stackoverflow.com/a/30359308
        if not os.path.exists(options.mediastore):
            os.mkdir(options.mediastore)
        basename = os.path.basename(test.input.filepath)
        if not os.path.exists(f"{options.mediastore}/{basename}"):
            shutil.copy2(filepath, f"{options.mediastore}/{basename}")

    # 4. update all the file paths to the remote workdir
    for test in test_suite.test:
        update_file_paths(test, options.device_workdir)

    # 4.b Update outputfile name (if present)
    for test in test_suite.test:
        update_fileoutput_names(test)

    # 5. save the full protobuf text file(s)
    if options.split:
        # (a) one pbtxt file per subtest
        protobuf_txt_filepath = "split"
        for test in test_suite.test:
            output_dir = f"{local_workdir}/{valid_path(test.common.id)}"
            if not os.path.exists(output_dir):
                os.mkdir(output_dir)
            filename = f"{output_dir}/{valid_path(test.common.id)}.pbtxt"
            with open(filename, "w") as f:
                f.write(text_format.MessageToString(test))
            files_to_push |= {filename}
    else:
        # (b) one pbtxt for all tests
        protobuf_txt_filepath = f"{local_workdir}/run.pbtxt"
        with open(protobuf_txt_filepath, "w") as f:
            f.write(text_format.MessageToString(test_suite))
        files_to_push |= {protobuf_txt_filepath}
    return test_suite, files_to_push, protobuf_txt_filepath


def valid_path(text):
    ret = re.sub(r"[ \/?*]", ".", text)
    return ret


def run_codec_tests_file(
    protobuf_txt_filepath, model, serial, local_workdir, options, debug
):
    protobuf_txt_filepath = create_tests_from_definition_expansionPath(
        protobuf_txt_filepath, local_workdir
    )
    if debug > 0:
        print(f"reading test: {protobuf_txt_filepath}")
    test_suite, files_to_push, protobuf_txt_filepath = read_and_update_proto(
        protobuf_txt_filepath, local_workdir, options
    )

    # multiply tests per request
    parallel_defs = parse_multiply(options.multiply)

    additional = tests_definitions.Parallel()
    updated = False
    for test in test_suite.test:
        for definition in parallel_defs:
            template = test
            counts = definition[0] + 1
            if definition[1] is not None and len(definition[1]) > 0:
                # Read the file and setup
                (
                    tmpsuite,
                    add_files_to_push,
                    add_protobuf_txt_filepath,
                ) = read_and_update_proto(definition[1], local_workdir, options)
                # Not to overcomplicate things we will only use the first defined test
                template = tmpsuite.test[0]

            for n in range(1, counts, 1):
                ntest = tests_definitions.Test()
                ntest.CopyFrom(template)
                ntest.common.id = f"{ntest.common.id}_#{n}"
                additional.test.extend([ntest])

        test.parallel.test.extend(additional.test)
        updated = True

    if options.split:
        for test in test_suite.test:
            suite = tests_definitions.TestSuite()
            suite.test.extend([test])
            path = f"{local_workdir}/{valid_path(test.common.id)}.pbtxt"
            with open(path, "w") as f:
                f.write(text_format.MessageToString(suite))
            files_to_push |= {path}
    # Save the complete test if updated
    if updated:
        # remove any older pbtxt in existence
        if debug > 0:
            print("Remove other pbtxt files")
        files_to_push = {fl for fl in files_to_push if not fl.endswith(".pbtxt")}

        result_files = []
        global QUALITY_PROCESSES
        if options.separate_sources:
            # create test(s) for each source
            # dictionary with source as key
            test_collection = {}
            for test in test_suite.test:
                source = test.input.filepath
                tests = []
                if source in test_collection:
                    tests = test_collection[source]
                else:
                    test_collection[source] = tests
                tests.append(test)

            counter = 0
            # Clear target and run test, collect result and iterate
            encapp_tool.adb_cmds.remove_files_using_regex(
                serial, "encapp_.*", options.device_workdir, options.debug
            )
            encapp_tool.adb_cmds.remove_files_using_regex(
                serial, ".*pbtxt$", options.device_workdir, options.debug
            )
            encapp_tool.adb_cmds.remove_files_using_regex(
                serial, ".*[yuv|raw]$", options.device_workdir, options.debug
            )
            success = True
            for testsource in test_collection:
                files = []
                # find file
                basename = os.path.basename(testsource)
                for file in files_to_push:
                    if basename in file:
                        files.append(file)
                        break

                test_suite = tests_definitions.TestSuite()
                for test in test_collection[testsource]:
                    # Add tests to the test suite
                    test_suite.test.append(test)
                counter += 1
                protobuf_txt_filepath = (
                    f"{local_workdir}/{valid_path(test.common.id)}_{counter}.pbtxt"
                )
                with open(protobuf_txt_filepath, "w") as f:
                    f.write(text_format.MessageToString(test_suite))
                if debug > 0:
                    print(f"add {protobuf_txt_filepath}")
                files.append(protobuf_txt_filepath)

                results = run_codec_tests(
                    test_suite,
                    files,
                    model,
                    serial,
                    options.mediastore,
                    local_workdir,
                    options.device_workdir,
                    options.ignore_results,
                    options.fast_copy,
                    options.split,
                    debug,
                )

                # Remove test files
                for file in files:
                    basename = os.path.basename(file)
                    encapp_tool.adb_cmds.remove_file(
                        serial, f"{options.device_workdir}/{basename}", options.debug
                    )

                if not results[0]:
                    success = False
                result_files += results[1]
                # Run quality
                if success and options.quality:
                    output = f"{local_workdir}/quality{basename}.csv"
                    proc = multiprocessing.Process(
                        target=encapp_quality.calculate_quality,
                        args=(
                            results[1],
                            options.mediastore,
                            output,
                            True,
                            options.debug,
                        ),
                    )
                    QUALITY_PROCESSES.append([proc, output])
                    proc.start()
                    if debug:
                        print("\n*** Quality proc is started!!!\n***\n")

            return success, result_files

        else:
            # If we are using the id - we need to replace characters that are problematic in
            # a filepath (i.e. space)
            protobuf_txt_filepath = (
                f"{local_workdir}/{valid_path(test.common.id)}_aggr.pbtxt"
            )
            with open(protobuf_txt_filepath, "w") as f:
                f.write(text_format.MessageToString(test_suite))
            if debug > 0:
                print(f"add {protobuf_txt_filepath}")
            files_to_push |= {protobuf_txt_filepath}
            if options.dry_run:
                # Do nothing here
                if debug:
                    print("Dry run - do nothing")
                return None, None
            else:
                results = run_codec_tests(
                    test_suite,
                    files_to_push,
                    model,
                    serial,
                    options.mediastore,
                    local_workdir,
                    options.device_workdir,
                    options.ignore_results,
                    options.fast_copy,
                    options.split,
                    debug,
                )
                # Run quality
                success = True
                if not results[0]:
                    success = False
                result_files += results[1]
                if success and options.quality:
                    output = f"{local_workdir}/quality.csv"
                    proc = multiprocessing.Process(
                        target=encapp_quality.calculate_quality,
                        args=(
                            results[1],
                            options.mediastore,
                            output,
                            True,
                            options.debug,
                        ),
                    )
                    QUALITY_PROCESSES.append([proc, output])
                    proc.start()
                    if debug:
                        print("\n*** Quality proc is started!!!\n***\n")
                return success, result_files
    else:
        print(
            f"Apparently something is not quite right, check the test definition: {test_suite=}"
        )


def abort_test(local_workdir, message):
    print("\n*** Test failed ***")
    print(f"Remove {local_workdir}")
    print(message)
    # shutil.rmtree(local_workdir)
    sys.exit(-1)


def create_tests_from_definition_expansionPath(protobuf_txt_filepath, local_workdir):
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)

    test_suite = tests_definitions.TestSuite()
    with open(protobuf_txt_filepath, "rb") as fd:
        text_format.Merge(fd.read(), test_suite)

    test_suite_ = create_tests_from_definition_expansion(test_suite)
    # check if they are the same, if so do nothing
    # if (test_suite_.equals(test_suite)):
    #    return protobuf_txt_filepath

    # write in the workdir
    basename = os.path.basename(protobuf_txt_filepath)
    filepath = f"{local_workdir}/expanded_{basename}"
    with open(filepath, "w") as f:
        f.write(text_format.MessageToString(test_suite_))

    return filepath


def lookup_message_by_name(message, submessage_name):
    if hasattr(message, submessage_name):
        return getattr(message, submessage_name)
    else:
        return None


def multiply_tests_with_tems(test, parent, setting, expanded):
    tests = []
    for item in expanded:
        ntest = tests_definitions.Test()
        ntest.CopyFrom(test)
        submessage = lookup_message_by_name(ntest, parent)
        if submessage:
            setattr(submessage, setting, str(item))

        tests.append(ntest)
    return tests


def update_single_setting(tests, parent, settings_name, expanded):
    updated = []
    for test in tests:
        tests_ = multiply_tests_with_tems(test, parent, settings_name, expanded)
        if tests_:
            updated.extend(tests_)
    return updated


# Takes a test definition and looks through all settins
# every expanded setting will create copies of the previous
def create_tests_from_definition_expansion(testsuite):
    # First we may have multiple tests already (ouch)
    # They will be handled as separate cases

    force_update = False
    updated_testsuite = tests_definitions.TestSuite()
    # updated_testsuite.test = tests_definitions.Test
    for test in testsuite.test:
        tests = [test]
        fields = [[descr.name, val] for descr, val in test.ListFields()]
        for field in fields:
            parent = None
            for item in field:
                if not parent:
                    parent = item
                else:
                    settings = [val for val in item.ListFields()]
                    for setting in settings:
                        force_update = False
                        # special case: input file
                        if parent == "input" and setting[0].name == "filepath":
                            # If the filepath match _exactly_ one file, leave it at that.
                            path = os.path.expanduser(setting[1])
                            if os.path.exists(path) and os.path.isfile(path):
                                expanded = [path]
                            else:
                                expanded = expand_filepath(path)
                            force_update = True
                        else:
                            expanded = expand_ranges(setting[1])
                        if tests:
                            if len(expanded) > 1 or force_update:
                                tests_ = update_single_setting(
                                    tests, parent, setting[0].name, expanded
                                )
                                if tests_:
                                    tests = tests_
        updated_testsuite.test.extend(tests)

    return updated_testsuite


def expand_filepath(path):
    # Check if path is a folder
    basename = ""
    folder = ""
    if os.path.isdir(path):
        folder = path
    else:
        basename = os.path.basename(path)
        folder = os.path.dirname(path)
    video_files = []
    for root, _dirs, files in os.walk(folder):
        for file in files:
            if is_video_extension(file):
                if len(basename) > 0:
                    # let us make one exception to the reg exp
                    # accept common glob in case regexp fails
                    m = None
                    try:
                        m = re.search(basename, file)
                    except Exception as ex:
                        print(f"Error: problem in regexp {basename}: ", ex)
                        print(
                            "Try glob for '*', replacing '*' with '.*'. Fix definition if something else is wanted."
                        )
                        basename = basename.replace("*", ".*")

                        m = re.search(basename, file)
                    if m:
                        file = f"{root}/{m.group(0)}"
                    else:
                        continue
                else:
                    file = f"{root}/{file}"
                video_files.append(file)

    return video_files


# Same as the bitrate expanding i.e
# start-stop-step,start2-stop2-step2,val3,val4
def expand_ranges(definition):
    result = []
    try:
        for item in definition.split(","):
            if "-" in item:
                ranges = item.split("-")
                if len(ranges) == 1:
                    # do nothing
                    return []
                # filter endings
                # if bitrate or k,M it will be converted
                start, stop, step = [convert_to_bps(it) for it in ranges]
                result.extend(list(range(start, stop + 1, step)))
            else:
                result.append(item)
        return result
    except:
        return []


# produce a list of bitrates from a CLI spec. Options are:
# * (1) a single number (e.g. "100 kbps")
# * (2) a range (e.g. "100k-1M-100k") (start-stop-step)
# * (3) a list of single numbers or ranges (e.g. "100kbps,200kbps")
def parse_bitrate_field(bitrate):
    # parse lists
    if "," in bitrate:
        bitrate_list = [parse_bitrate_field(it) for it in bitrate.split(",")]
        # append the produced lists
        return list(itertools.chain(*bitrate_list))
    # parse ranges
    if "-" in bitrate:
        bitrate_spec = bitrate.split("-")
        assert len(bitrate_spec) == 3, f'error: invalid bitrate spec: "{bitrate}"'
        start, stop, step = [convert_to_bps(it) for it in bitrate_spec]
        # We want to include the last value...
        return list(range(start, stop + 1, step))
    # parse single elements
    return [convert_to_bps(bitrate)]


# TODO: fix and comment
def parse_resolution_field(resolution):
    # parse lists
    if "," in resolution:
        resolution_list = resolution.split(",")
        return resolution_list
    # parse ranges
    if "-" in resolution:
        resolution_spec = resolution.split("-")
        assert (
            len(resolution_spec) == 3
        ), f'error: invalid resolution spec: "{resolution}"'
        start, stop, step = resolution_spec
        return list(
            range(start, stop + 1, step)
        )  # We want to include the last value...
    # parse single elements
    return [resolution]


# TODO: fix and comment
def parse_framerate_field(framerate):
    # parse lists
    if "," in framerate:
        framerate_list = framerate.split(",")
        return framerate_list
    # parse ranges
    if "-" in framerate:
        framerate_spec = framerate.split("-")
        assert len(framerate_spec) == 3, f'error: invalid framerate spec: "{framerate}"'
        start, stop, step = framerate_spec
        return list(
            range(start, stop + 1, step)
        )  # We want to include the last value...
    # parse single elements
    return [framerate]


def update_input_section(
    test: tests_definitions.Test, videoinfo: dict
) -> tests_definitions.Test:
    infield = test.input

    if len(infield.resolution) == 0:
        resolution = f"{videoinfo['width']}x{videoinfo['height']}"
        infield.resolution = resolution
    if infield.framerate <= 0:
        infield.framerate = videoinfo.get("framerate", 0)
    fmt = PIX_FMT_TYPES_VALUES[videoinfo.get("pix-fmt", 0)]
    if infield.pix_fmt == fmt:
        infield.pix_fmt = fmt

    # TODO: set color details in configure unless they are there
    return test


def update_media(test, options):
    debug = options.debug
    input_is_raw = encapp_tool.ffutils.video_is_raw(test.input.filepath)

    # if there are not settings for res and rate check the file itself
    info = encapp_tool.ffutils.get_video_info(test.input.filepath, options.debug)

    # Let us help support function later by checking the file and populate the input
    # fields where we can
    if not input_is_raw:
        test = update_input_section(test, info)

    in_res = test.input.resolution
    in_rate = test.input.framerate
    in_pix_fmt = test.input.pix_fmt
    out_res = test.configure.resolution
    out_rate = test.configure.framerate
    replace = getattr(options, "replace", {})
    input_repl = replace.get("input", {})
    out_pix_fmt = input_repl.get("pix_fmt", in_pix_fmt)

    if len(out_res) == 0:
        out_res = in_res
    if out_rate == 0:
        out_rate = in_rate
        # Simplify the logic, return if no transcoding is needed
    if test.input.device_decode:
        if debug:
            print("Device decode set")
        return
    if (
        test.configure.surface
        and (
            out_pix_fmt == PIX_FMT_TYPES_VALUES["nv21"]
            or out_pix_fmt == PIX_FMT_TYPES_VALUES["rgba"]
        )
        and not encapp_tool.ffutils.video_is_y4m(test.input.filepath)
    ):
        if debug:
            print("Surface with compatible pixel formats")
        return

    if input_is_raw and (
        in_res == out_res
        and in_rate == out_rate
        and (in_pix_fmt == out_pix_fmt or out_pix_fmt is not None)
    ):
        if debug:
            print("Configure is same format as input")
        return

    # Passed this point, always transcode to a raw file
    if debug:
        print("Transcode input")
    reason = ""
    if in_res != out_res:
        reason = f" res ({in_res} != {out_res})"
    if in_rate != out_rate:
        reason = f" rate ({in_rate} != {out_rate})"
    if in_pix_fmt != out_pix_fmt:
        reason = f" pix_fmt ({in_pix_fmt} != {out_pix_fmt})"
    if options.raw:
        reason = "Always decode to raw set"

    reason = reason.strip()
    if options.debug > 0:
        print(f"Transcode raw input: {test.input.filepath} {reason = }")
    replace = {}
    input = {}
    output = {}
    basename = os.path.basename(test.input.filepath)

    input["pix_fmt"] = tests_definitions.PixFmt.Name(in_pix_fmt)
    input["resolution"] = in_res
    input["framerate"] = in_rate
    if (
        out_pix_fmt == PIX_FMT_TYPES_VALUES["nv21"]
        or out_pix_fmt == PIX_FMT_TYPES_VALUES["rgba"]
    ) and test.configure.surface:
        print("Attention.")
        print(
            "Raw nv21/rgba to a surface will scale on device using the Android default surface scaling"
        )
        out_res = in_res
        out_rate = in_rate

    output["resolution"] = out_res
    output["framerate"] = out_rate
    output["pix_fmt"] = out_pix_fmt

    stride = ""
    if (options.width_align > 0) or (options.height_align > 0):
        width = int(out_res.split("x")[0])
        height = int(out_res.split("x")[1])
        wa = options.width_align if options.width_align > 0 else 1
        ha = options.height_align if options.height_align > 0 else 1
        if width % wa != 0:
            width = (width // wa + 1) * wa
        if height % ha:
            height = (height // ha + 1) * ha
        output["hstride"] = width
        output["vstride"] = height
        stride = f"str.{output['hstride']}x{output['vstride']}"
    extension = "raw"
    if output["pix_fmt"] == "rgba":
        extension = "rgba"
    pix_fmt_id = out_pix_fmt if out_pix_fmt is not None else in_pix_fmt
    if str(pix_fmt_id).isnumeric():
        pix_fmt = tests_definitions.PixFmt.Name(pix_fmt_id)
    else:
        pix_fmt = pix_fmt_id

    # Special case. Transcoding on device using surfaces with no specific pixel format set, force nv21.
    if test.configure.surface:
        pix_fmt = "nv21"

    output["output_filepath"] = (
        f"{options.mediastore}/{basename}_{out_res}p{round(out_rate, 2)}_{pix_fmt}"
    )
    if len(stride) > 0:
        output["output_filepath"] += f"_{stride}"

    output["output_filepath"] += f".{extension}"
    output["pix_fmt"] = pix_fmt
    replace["input"] = input
    replace["output"] = output

    d = process_input_path(
        test.input.filepath, replace, test.input, options.mediastore, options.debug
    )
    # After transcoding input settings may have changed, adjust.
    # now both config and input should be the same i.e. matching config
    test.input.resolution = d["resolution"]
    test.input.framerate = d["framerate"]
    test.input.pix_fmt = d["pix_fmt"]  # ???? PIX_FMT_TYPES_VALUES[d["pix_fmt"]]
    test.input.filepath = d["filepath"]
    # Maybe not necessary but would just indicate that the input resolution was used.
    test.configure.resolution = d["resolution"]


# Update a set of tests with the CLI arguments.
# Note that update may include adding new tests (e.g. if bitrate is
# defined as a (from, to, step) tuple instead of a single value).
def update_codec_test(
    test,
    updated_test_suite,
    local_workdir,
    device_workdir,
    replace,
    mediastore,
    is_parallel=False,
    debug=0,
):
    # save the main test id
    if test.parallel:
        subtests = test.parallel.test
        for subtest in subtests:
            update_codec_test(
                subtest,
                test,
                local_workdir,
                device_workdir,
                replace,
                mediastore,
                True,
                debug,
            )

    # 1. update the tests with the CLI parameters

    # 1.1. replace the parameters that do not create multiple tests
    # TODO(chema): there should be an automatic way to do this
    CONFIGURE_INT_KEYS = (
        "quality",
        "complexity",
        "durationUs",
        "color_format",
        "i_frame_interval",
    )
    INPUT_INT_KEYS = ("playout_frames", "pursuit")
    CONFIGURE_FLOAT_KEYS = ("framerate", "stoptime_sec")
    INPUT_FLOAT_KEYS = ("framerate", "stoptime_sec")
    CONFIGURE_BOOL_KEYS = (
        "encode",
        "surface",
        "decode_dump",
    )
    INPUT_BOOL_KEYS = ("show", "realtime")

    for k1 in replace:
        for k2, val in replace[k1].items():
            if (k1, k2) == ("configure", "bitrate"):
                # already processed
                continue
            if (k1, k2) == ("configure", "framerate"):
                # We will deal with this later
                continue
            if (k1, k2) == ("configure", "resolution"):
                # We will deal with this later
                continue
            # process integer keys
            if (k1 == "configure" and k2 in CONFIGURE_INT_KEYS) or (
                k1 == "input" and k2 in INPUT_INT_KEYS
            ):
                # force integer value
                val = int(val)
            # process float keys
            if (k1 == "configure" and k2 in CONFIGURE_FLOAT_KEYS) or (
                k1 == "input" and k2 in INPUT_FLOAT_KEYS
            ):
                # force float value
                val = float(val)

            # process boolean keys
            if (k1 == "configure" and k2 in CONFIGURE_BOOL_KEYS) or (
                k1 == "input" and k2 in INPUT_BOOL_KEYS
            ):
                # force float value
                val = bool(val)
            # convert enum strings to integer
            if k1 == "input" and k2 == "pix_fmt":
                val = tests_definitions.PixFmt.Value(val)
            if k1 == "configure" and k2 == "bitrate_mode":
                val = tests_definitions.Configure.BitrateMode.Value(val)
            if k1 == "configure" and k2 == "color_standard":
                val = tests_definitions.Configure.ColorStandard.Value(val)
            if k1 == "configure" and k2 == "color_range":
                val = tests_definitions.Configure.ColorRange.Value(val)
            if k1 == "configure" and k2 == "color_transfer":
                val = tests_definitions.Configure.ColorTransfer.Value(val)
            try:
                if not test.HasField(k1):
                    # create the Message field
                    getattr(test, k1).SetInParent()
            except:
                print(f"Something could be wrong with {k1}, {k2} - {val}")
                continue
            setattr(getattr(test, k1), k2, val)

    # replace values from CLI options
    # Let us run the resolution first since that will create generated media
    resolution_str = replace.get("configure", {}).get("resolution", "")
    if len(resolution_str) > 0:
        # update the resolution
        resolution_list = parse_resolution_field(resolution_str)
        if len(resolution_list) > 1:
            for resolution in resolution_list:
                # create a new test with the new resolution and generate mediua
                if is_parallel:
                    ntest = test
                else:
                    ntest = tests_definitions.Test()
                    ntest.CopyFrom(test)
                ntest.common.id = test.common.id + f".{resolution}"
                ntest.configure.resolution = str(resolution)
                if not is_parallel:
                    # remove the options already taken care of
                    rep_copy = copy.deepcopy(replace)
                    rep_copy["configure"]["resolution"] = ""
                    update_codec_test(
                        ntest,
                        updated_test_suite,
                        local_workdir,
                        device_workdir,
                        rep_copy,
                        mediastore,
                    )
            return
        else:
            test.common.id = test.common.id + f".{resolution_str}"
            test.configure.resolution = str(resolution_str)

    framerate_str = replace.get("configure", {}).get("framerate", "")
    if len(framerate_str):
        # update the framerate
        framerate_list = parse_framerate_field(framerate_str)
        if len(framerate_list) > 1:
            for framerate in framerate_list:
                # create a new test with the new framerate and generate media
                if is_parallel:
                    ntest = test
                else:
                    ntest = tests_definitions.Test()
                    ntest.CopyFrom(test)
                ntest.common.id = test.common.id + f"@{framerate}fps"
                ntest.configure.framerate = float(framerate)

                if not is_parallel:
                    # remove the options already taken care of
                    rep_copy = copy.deepcopy(replace)
                    rep_copy["configure"]["framerate"] = ""
                    update_codec_test(
                        ntest,
                        updated_test_suite,
                        local_workdir,
                        device_workdir,
                        rep_copy,
                        mediastore,
                    )
            return
        else:
            test.common.id = test.common.id + f"@{framerate_list[0]}"
            test.configure.framerate = float(framerate_list[0])

    # 1.2. replace the parameters that create multiple tests
    # 1.2.1. process configure.bitrate

    bitrate_str = replace.get("configure", {}).get("bitrate", "")
    if bitrate_str:
        # update the bitrate
        bitrate_list = parse_bitrate_field(bitrate_str)
        if len(bitrate_list) > 1:
            for bitrate in bitrate_list:
                # create a new test with the new bitrate
                if is_parallel:
                    ntest = test
                else:
                    ntest = tests_definitions.Test()
                    ntest.CopyFrom(test)

                ntest.common.id = test.common.id + f".{bitrate}bps"
                ntest.configure.bitrate = str(bitrate)
                if not is_parallel:
                    # remove the options already taken care of
                    rep_copy = copy.deepcopy(replace)
                    rep_copy["configure"]["bitrate"] = ""
                    update_codec_test(
                        ntest,
                        updated_test_suite,
                        local_workdir,
                        device_workdir,
                        rep_copy,
                        mediastore,
                    )
            return
        else:
            # replace the namd and bitrate in the old test
            test.common.id = test.common.id + f".{bitrate_list[0]}bps"
            test.configure.bitrate = str(bitrate_list[0])

    if not is_parallel:
        updated_test_suite.test.extend([test])


# Update a set of tests with the CLI arguments.
# Note that update may include adding new tests (e.g. if bitrate is
# defined as a (from, to, step) tuple instead of a single value).
def update_codec_testsuite(
    test_suite,
    updated_test_suite,
    local_workdir,
    device_workdir,
    replace,
    mediastore,
    debug=0,
):
    for test in test_suite.test:
        update_codec_test(
            test,
            updated_test_suite,
            local_workdir,
            device_workdir,
            replace,
            mediastore,
            debug,
        )

    return updated_test_suite


def run_codec_tests(
    test_suite,
    files_to_push,
    model,
    serial,
    mediastore,
    local_workdir,
    device_workdir=None,
    ignore_results=False,
    fast_copy=False,
    split=False,
    debug=False,
):
    global default_values
    if device_workdir is None:
        device_workdir = default_values["device_workdir"]

    os.makedirs(local_workdir, exist_ok=True)

    collected_results = []
    # run the test(s)
    if split:
        # (a) one pbtxt file per subtest
        # push just the files we need by looking up the name
        tests_run = f"{local_workdir}/tests_run.log"
        total_number = len(test_suite.test)
        counter = 1
        for test in test_suite.test:
            print(
                f"*** Running {counter}/{total_number} split test {test.common.id} **"
            )
            counter += 1
            # Check last ran test
            if os.path.exists(tests_run):
                with open(tests_run, "r+") as passed:
                    data = passed.read()
                    if f"{valid_path(test.common.id)}.pbtxt" in data:
                        print("Test already done, moving on.")
                        ignore_results = True
                        continue
            ignore_results = False
            files = set()
            get_media_files(test, files)
            for filepath in files:
                if not encapp_tool.adb_cmds.push_file_to_device(
                    f"{mediastore}/{filepath}", serial, device_workdir, fast_copy, debug
                ):
                    abort_test(local_workdir, f"Error copying {filepath} to {serial}")
                if not encapp_tool.adb_cmds.push_file_to_device(
                    f"{local_workdir}/{valid_path(test.common.id)}.pbtxt",
                    serial,
                    device_workdir,
                    fast_copy,
                    debug,
                ):
                    abort_test(local_workdir, f"Error copying {filepath} to {serial}")

            if encapp_tool.adb_cmds.USE_IDB:
                protobuf_txt_filepath = f"{valid_path(test.common.id)}.pbtxt"
            else:
                protobuf_txt_filepath = (
                    f"{device_workdir}/{valid_path(test.common.id)}.pbtxt"
                )

            run_cmd = ""
            if test.test_setup and test.test_setup.run_cmd:
                run_cmd = test.test_setup.run_cmd
            run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug=debug)
            with open(tests_run, "a") as passed:
                passed.write(f"{valid_path(test.common.id)}.pbtxt\n")

            # Pull the log file (it will be overwritten otherwise)
            if encapp_tool.adb_cmds.USE_IDB:
                print(
                    "Currently filesystem synch on ios seems to be slow, sleep a little while"
                )
                time.sleep(1)
                cmd = f"idb file pull {device_workdir}/encapp.log {local_workdir} --udid {serial} --bundle-id {encapp_tool.adb_cmds.IDB_BUNDLE_ID}"
                encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
                try:
                    os.rename(
                        f"{local_workdir}/encapp.log",
                        f"{local_workdir}/{test.common.id}.log",
                    )
                except:
                    print("Changing name on the ios log file")
            collected_results.extend(
                collect_results(
                    local_workdir,
                    protobuf_txt_filepath,
                    serial,
                    device_workdir,
                    run_cmd=run_cmd,
                    debug=debug,
                )
            )

    else:
        # (b) one pbtxt for all tests
        # push all the files to the device workdir
        if encapp_tool.adb_cmds.USE_IDB:
            if encapp_tool.adb_cmds.IOS_MAJOR_VERSION < 17:
                cmd = (
                    f"idb launch --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} standby",
                )
            else:
                cmd = (
                    f"xcrun devicectl device process launch --device {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} standby",
                )
            encapp_tool.adb_cmds.run_cmd(cmd)
        protobuf_txt_filepath = ""

        for filepath in files_to_push:
            # Kind of stupid but there should be a pbtxtx, and just one here
            fc = fast_copy
            if filepath.endswith("pbtxt"):
                protobuf_txt_filepath = filepath
                # We always write the test definitions
                fc = False
            if not encapp_tool.adb_cmds.push_file_to_device(
                filepath, serial, device_workdir, fc, debug
            ):
                abort_test(local_workdir, f"Error copying {filepath} to {serial}")

        basename = os.path.basename(protobuf_txt_filepath)
        if encapp_tool.adb_cmds.USE_IDB:
            protobuf_txt_filepath = f"{basename}"
        else:
            protobuf_txt_filepath = f"{device_workdir}/{basename}"

        if encapp_tool.adb_cmds.USE_IDB:
            encapp_tool.app_utils.force_stop(serial, debug)
        run_cmd = ""
        test = test_suite.test[0]
        if test.test_setup and test.test_setup.run_cmd:
            run_cmd = test.test_setup.run_cmd
        run_encapp_test(
            protobuf_txt_filepath, serial, device_workdir, run_cmd=run_cmd, debug=debug
        )

        # collect the test results
        # Pull the log file (it will be overwritten otherwise)
        if encapp_tool.adb_cmds.USE_IDB:
            print(
                "Currently filesystem synch on ios seems to be slow, sleep a little while"
            )
            time.sleep(1)
            cmd = f"idb file pull {device_workdir}/encapp.log {local_workdir} --udid {serial} --bundle-id {encapp_tool.adb_cmds.IDB_BUNDLE_ID}"
            encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
            try:
                os.rename(
                    f"{local_workdir}/encapp.log",
                    f"{local_workdir}/{basename}.log",
                )
            except Exception as ex:
                print(f"ERROR: Changing name on the ios log file: {ex}")
        if ignore_results:
            return None, None
        collected_results.extend(
            collect_results(
                local_workdir, protobuf_txt_filepath, serial, device_workdir, debug
            )
        )
    return collected_results


def list_codecs(
    serial: str,
    model: str,
    device_workdir: str,
    output: str = None,
    cache: bool = True,
    run_cmd: str = None,
    debug: int = 0,
) -> str:
    folder = ""
    filename = None
    if output:
        # check if existing file or folder
        if os.path.exists(output):
            if os.path.isfile(output):
                filename = output
            else:
                folder = output
        else:
            if output[-1] == "/":
                # create dir
                os.mkdir(output)
                folder = output
            else:
                filename = output
    if len(folder) > 0:
        folder += "/"
    if not filename:
        model_clean = model.replace(" ", "_")
        filename = f"{folder}codecs_{model_clean}.txt"

    # If file exists and from today, read the cached file
    if os.path.isfile(filename) and cache:
        # check timestamp
        ts = os.path.getmtime(filename)
        now = time.time()
        diff = now - ts
        if diff < 3600:
            return filename

    if encapp_tool.adb_cmds.USE_IDB:
        if encapp_tool.adb_cmds.IOS_MAJOR_VERSION < 17:
            cmd = f"idb launch --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} list_codecs"
        else:
            cmd = f"xcrun devicectl device process launch --device {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} list_codecs"
        # cmd = {f"idb launch --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} list_codecs"}
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
        assert ret, 'error getting codec list: "%s"' % stdout
        # for some bizzare reason if using a destination a directory is created...
        cmd = f"idb file pull {device_workdir}/codecs.txt . --udid {serial} --bundle-id {encapp_tool.adb_cmds.IDB_BUNDLE_ID}"
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)

        cmd = f"mv codecs.txt {filename}"
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    else:
        if run_cmd:
            # call
            # TODO: can we assume adb?
            cmd = f"adb -s {serial} shell {run_cmd} "
            encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
        else:
            adb_cmd = (
                f"adb -s {serial} shell am start "
                f"-e workdir {device_workdir} "
                "-e ui_hold_sec 3 "
                f"-e list_codecs a {encapp_tool.app_utils.ACTIVITY}"
            )

            encapp_tool.adb_cmds.run_cmd(adb_cmd, debug=debug)
            wait_for_exit(serial, debug)
        adb_cmd = f"adb -s {serial} pull {device_workdir}/codecs.txt {filename}"
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(adb_cmd, debug=debug)
        assert ret, 'error getting codec list: "%s"' % stdout
    return filename


def read_json_file(jsonfile, debug):
    # read input file
    with open(jsonfile, "r") as fp:
        if debug > 0:
            print(f"jsonfile: {jsonfile}")
        input_config = json.load(fp)
    return input_config


def is_int(s):
    if isinstance(s, int):
        return True
    return s[1:].isdigit() if s[0] in ("-", "+") else s.isdigit()


def convert_to_bps(value):
    # support for integers
    if isinstance(value, int):
        return value
    # support for strings containing only integers
    if value.isnumeric():
        return int(value)
    # remove spaces
    val = value.replace(" ", "")
    # support for SI units (at least 'k' and 'M')
    mul = 1
    index = val.rfind("k")
    if index > 0:
        mul = int(1e3)
    else:
        index = val.rfind("M")
        if index > 0:
            mul = int(1e6)
        else:
            # not a valid number
            raise AssertionError(f"invalid bitrate: {value}")
    return int(float(value[0:index]) * mul)


# convert a value (in either time or frame units) into frame units
def convert_to_frames(value, fps=30):
    if is_int(value):
        # value is already fps
        return int(value)
    # check if it can be parsed as a duration (time)
    try:
        sec = humanfriendly.parse_timespan(value)
    except humanfriendly.InvalidTimespan:
        print('error: invalid frame value "%s"' % value)
        sys.exit(-1)
    return int(sec * fps)


def check_protobuf_txt_file(protobuf_txt_filepath, local_workdir, debug):
    # ensure the protobuf text file exists and is readable
    if protobuf_txt_filepath is None:
        abort_test(local_workdir, "ERROR: need a test file name")
    if (
        not os.path.exists(protobuf_txt_filepath)
        or not os.path.isfile(protobuf_txt_filepath)
        or not os.access(protobuf_txt_filepath, os.R_OK)
    ):
        abort_test(
            local_workdir, f'ERROR: invalid test file name "{protobuf_txt_filepath}"'
        )
    # use a temp file for the binary output
    _, protobuf_bin_file = tempfile.mkstemp(dir=tempfile.gettempdir())
    cmd = (
        f'protoc -I {protobuf_txt_filepath} --encode="TestSuite" '
        f"{protobuf_bin_file}"
    )
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    assert ret == 0, f"ERROR: {stderr}"


def setup_local_workdir(options: argparse.Namespace, model: str) -> argparse.Namespace:
    if options.local_workdir is None:
        now = datetime.datetime.now()
        dt_string = now.strftime("%Y%m%d_%H%M%S")
        options.local_workdir = (
            f"{options.desc.replace(' ', '_')}_{model.replace(' ', '_')}_{dt_string}"
        )

    if not os.path.exists(options.local_workdir):
        os.mkdir(options.local_workdir)

    return options


def rename_local_workdir(options: argparse.Namespace, model: str) -> argparse.Namespace:
    now = datetime.datetime.now()
    dt_string = now.strftime("%Y%m%d_%H%M%S")
    new_local_workdir = (
        f"{options.desc.replace(' ', '_')}_{model.replace(' ', '_')}_{dt_string}"
    )

    os.rename(options.local_workdir, new_local_workdir)

    # need to rename config files that may have been created
    files = []
    for file in options.configfile:
        files.append(file.replace(options.local_workdir, new_local_workdir))

    options.configfile = files
    options.local_workdir = new_local_workdir
    return options


def codec_test(options, model, serial, debug):
    if debug > 0:
        print(f"codec test: {options}")
    # get the local working directory (at the host)
    local_workdir = options.local_workdir
    if local_workdir is None:
        now = datetime.datetime.now()
        dt_string = now.strftime("%Y%m%d_%H%M%S")
        local_workdir = (
            f"{options.desc.replace(' ', '_')}_{model.replace(' ', '_')}_{dt_string}"
        )

    # Create local_workdir
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)
    # merge the protobuf files
    # First file will be the base
    # For later definitions on the first test will be merged
    # to all of the tests in the first prototbuf
    # TODO: should parallels be considered?
    test_suite = None
    for proto in options.configfile:
        tmp = tests_definitions.TestSuite()
        with open(proto, "rb") as fd:
            text_format.Merge(fd.read(), tmp)
        if test_suite is None:
            test_suite = tmp
        elif len(test_suite.test):
            for test in test_suite.test:
                if test is not None:
                    test.MergeFrom(tmp.test[0])
        else:
            print("ERROR, first config file lacks a test")
    basename = os.path.basename(options.configfile[0])
    options.configfile = f"{local_workdir}/{basename}"

    # Maybe not the right place to do this but let us do it anyways
    if options.source_dir:
        for test in test_suite.test:
            if test.input.filepath:
                test.input.filepath = f"{options.source_dir}/{test.input.filepath}"
    with open(options.configfile, "w") as f:
        f.write(text_format.MessageToString(test_suite))

    if options.mediastore is None:
        options.mediastore = local_workdir
    # check the protobuf text is correct
    protobuf_txt_filepath = options.configfile
    check_protobuf_txt_file(protobuf_txt_filepath, local_workdir, debug)

    # run the codec test
    return run_codec_tests_file(
        protobuf_txt_filepath, model, serial, local_workdir, options, debug
    )


def get_device_dir():
    if encapp_tool.adb_cmds.is_using_idb():
        return "Documents"
    else:
        return "/sdcard"


def set_idb_mode(mode):
    global default_values
    encapp_tool.adb_cmds.set_idb_mode(mode)
    default_values["device_workdir"] = get_device_dir()


def add_args(parser):
    # generic replacement mechanism
    class ReplaceAction(argparse.Action):
        def __call__(self, parser, options, values, option_string=None):
            if options.replace is None:
                options.replace = {}
            # make sure there is 1 and only 1 separator
            assert values[0].count(".") == 1, f"invalid replace key: {values[0]}"
            k1, k2 = values[0].split(".")
            if k1 not in options.replace:
                options.replace[k1] = {}
            options.replace[k1][k2] = values[1]

    parser.add_argument(
        "-e",
        "--replace",
        action=ReplaceAction,
        nargs=2,
        help='use <key> <value> (e.g. "-e configure.bitrate_mode cbr")',
        default={},
    )
    # replacement shortcuts
    parser.add_argument(
        "-i",
        "--videofile",
        type=str,
        dest="videofile",
        default=default_values["videofile"],
        metavar="input-video-file",
        help="input video file",
    )
    parser.add_argument(
        "-c",
        "--codec",
        type=str,
        dest="codec",
        default=default_values["encoder"],
        metavar="encoder",
        help="override encoder in config",
    )
    parser.add_argument(
        "-r",
        "--bitrate",
        type=str,
        dest="bitrate",
        default=default_values["bps"],
        metavar="input-video-bitrate",
        help="""input video bitrate. Can be
        1. a single number (e.g. "100 kbps")
        2. a list (e.g. "100kbps,200kbps")
        3. a range (e.g. "100k-1M-100k") (start-stop-step)""",
    )
    parser.add_argument(
        "-fps",
        "--framerate",
        type=str,
        dest="framerate",
        default=default_values["framerate"],
        metavar="input-video-framerate",
        help="""input video bitrate. Can be
        1. a single number (e.g. "100 kbps")
        2. a list (e.g. "100kbps,200kbps")
        3. a range (e.g. "100k-1M-100k") (start-stop-step)""",
    )
    parser.add_argument(
        "-s",
        "--size",
        type=str,
        dest="resolution",
        default=default_values["resolution"],
        metavar="input-video-resolution",
        help="""input video resolution. Can be
        1. a single size (e.g. "1280x720")
        2. a list (e.g. "320x240,1280x720")
        3. a range (e.g. "320x240-4000x4000-2") (start-stop-step)
        In the case of the step it is a multiplication of the first.
        It will stop when the pixels pass stop value (calculated as wxh)""",
    )
    parser.add_argument(
        "--multiply",
        type=str,
        dest="multiply",
        default=default_values["multiply"],
        metavar="multiply",
        help="""Multiply a test input. Can be a
        1. single number
        2. An array in the form of \"[nbr,source][nbr,source]...\" e.g.
           \"[x,][y,'../test/test1.pbtxt'][z,'test2.pbtxt']\"

        (1) will simply multiply the source test the set number of times as parallell case and
        (2) will add x number of the source in addition to
        the test1.pbxt y number of times etc.
        If updating a parameter all tests will get the same update.""",
    )
    parser.add_argument(
        "--pix-fmt",
        type=str,
        dest="pix_fmt",
        default=None,
        metavar="pixel/color-format",
        help="wanted pix fmt for the encoder",
    )
    # other parameters
    parser.add_argument(
        "-w",
        "--local-workdir",
        type=str,
        dest="local_workdir",
        default=default_values["local_workdir"],
        metavar="local workdir",
        help="work (storage) directory on local host",
    )
    parser.add_argument(
        "configfile",
        type=str,
        nargs="*",
        default=default_values["configfile"],
        metavar="input-config-file",
        help="input configuration file",
    )
    parser.add_argument(
        "--ignore-results",
        action="store_true",
        dest="ignore_results",
        default=False,
        help="Ignore results on an experiment",
    )
    parser.add_argument(
        "--fast-copy",
        action="store_true",
        dest="fast_copy",
        default=False,
        help="Minimize file interaction",
    )
    parser.add_argument(
        "--split",
        action="store_true",
        dest="split",
        default=False,
        help="Run serial test individually",
    )
    parser.add_argument(
        "--separate-sources",
        action="store_true",
        dest="separate_sources",
        default=False,
        help="Run each source separatly clearing space inbetween",
    )

    parser.add_argument(
        "--mediastore",
        type=str,
        nargs="?",
        default=default_values["mediastore"],
        metavar="mediastore",
        help="store all input and generated file in one folder",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        dest="dry_run",
        default=False,
        help="Do not execute the tests. Just prepare the test(s).",
    )
    parser.add_argument(
        "--width-align",
        dest="width_align",
        type=int,
        default=-1,
        metavar="pixels",
        help="Horizontal widht alignment in bits to calculate stride and add padding if converting to raw yuv",
    )
    parser.add_argument(
        "--height-align",
        dest="height_align",
        type=int,
        default=-1,
        metavar="pixels",
        help="Vertical height alignment in bits to calculate and add padding if converting to raw yuv",
    )
    parser.add_argument(
        "--dim-align",
        dest="dim_align",
        type=int,
        default=None,
        metavar="pixels",
        help="Horizontal and vertical alignment in bits to calculate stride and add padding if converting to raw yuv",
    )
    parser.add_argument(
        "--raw",
        action="store_true",
        help="Always decode to raw format before pushing to device",
    )
    parser.add_argument(
        "--source-dir",
        default=None,
        dest="source_dir",
        metavar="directory",
        help="Root directory for sources. If not set the input.filepath wll be absolute or relative from the current",
    )
    parser.add_argument(
        "--quality",
        action="store_true",
        help="Run the quality calculations as a part of the session. Same as running the encapp_quality tool after the encoding",
    )


input_args = {
    # generic
    "version": {
        "func": FUNC_CHOICES,
        "short": "-v",
        "long": "--version",
        "args": {
            "action": "store_true",
            "help": "Print version",
        },
    },
    "debug": {
        "func": FUNC_CHOICES,
        "short": "-d",
        "long": "--debug",
        "args": {
            "action": "count",
            "default": default_values["debug"],
            "help": "Increase verbosity (use multiple times for more)",
        },
    },
    "quiet": {
        "func": FUNC_CHOICES,
        "short": "-q",
        "long": "--quiet",
        "args": {
            "action": "store_true",
            "help": "Zero verbosity",
        },
    },
    "serial": {
        "func": FUNC_CHOICES,
        "long": "--serial",
        "args": {
            "type": str,
            "help": "Device serial.",
        },
    },
    "install": {
        "func": FUNC_CHOICES,
        "long": "--install",
        "args": {
            "action": "store_const",
            "const": True,
            "default": default_values["install"],
            "help": "Do install apk.",
        },
    },
    "idb": {
        "func": FUNC_CHOICES,
        "long": "--idb",
        "args": {
            "action": "store_true",
            "help": "Run on ios using idb",
        },
    },
    "bundleid": {
        "func": FUNC_CHOICES,
        "long": "--bundleid",
        "args": {
            "type": str,
            "default": None,
            "help": "Sets the bundleid to be used an implicitly turns on idb option",
        },
    },
    "device-workdir": {
        "func": FUNC_CHOICES,
        "long": "--device-workdir",
        "args": {
            "type": str,
            "default": None,
            "dest": "device_workdir",
            "metavar": "local directory",
            "help": "work (storage) directory on device",
        },
    },
    "run-cmd": {
        "func": FUNC_CHOICES,
        "long": "--run-cmd",
        "args": {
            "type": str,
            "dest": "run_cmd",
            "default": None,
            "help": "Excplicitly specify a command tobe run.",
        },
    },
    # List specific arguments
    "encoders": {
        "func": "list",
        "short": "-enc",
        "long": "--encoders",
        "args": {
            "action": "store_true",
            "help": "Show encoders",
        },
    },
    "decoders": {
        "func": "list",
        "short": "-dec",
        "long": "--decoders",
        "args": {
            "action": "store_true",
            "help": "Show decoders",
        },
    },
    "hw": {
        "func": "list",
        "short": "-hw",
        "long": "--hw",
        "args": {
            "action": "store_true",
            "help": "Only show hardware accelerated codecs",
        },
    },
    "sw": {
        "func": "list",
        "short": "--sw",
        "long": "--sw",
        "args": {
            "action": "store_true",
            "help": "Only show software accelerated codecs",
        },
    },
    "audio": {
        "func": "list",
        "long": "--audio",
        "args": {
            "action": "store_true",
            "help": "Only show audio codec",
        },
    },
    "info_level": {
        "func": "list",
        "short": "-l",
        "long": "--info-level",
        "args": {
            "type": int,
            "dest": "info_level",
            "help": "How much to show. Level > 0 filters parts of the information for more compact displat. Level -1 shows all. Default only name will be shown.",
        },
    },
    "codec": {
        "func": "list",
        "short": "-c",
        "long": "--codec",
        "args": {
            "type": str,
            "help": "Regexp filter codec name",
        },
    },
    "no_cache": {
        "func": "list",
        "short": "-nc",
        "long": "--no-cache",
        "args": {
            "action": "store_true",
            "dest": "no_cache",
            "help": "No cache, refresh from device",
        },
    },
    "output": {
        "func": "list",
        "short": "-o",
        "long": "--output",
        "args": {
            "type": str,
            "help": "Folder or filename. Folder decided either by prexising name or ending '/'",
        },
    },
    "file": {
        "func": "list",
        "short": "-f",
        "long": "--codecs-file",
        "args": {
            "type": str,
            "dest": "codecs_file",
            "help": "Read from file instead of fetching from a device.",
        },
    },
}


# Currently most args are connected to "run" but the number of args creates problem for e.g. list
# This is a way to have more granularity
def add_func_arg(func: str, parser: argparse.ArgumentParser) -> None:
    for key, value in input_args.items():
        if func in value["func"]:
            if "short" in value and value["short"]:
                # First part of add_argument has arbitrary length and not keywords
                # This is why short and long are set here (No need for more than these two I hope).
                parser.add_argument(
                    value["short"],  # type: ignore
                    value["long"],  # type: ignore
                    **value["args"],
                )
            else:
                parser.add_argument(
                    value["long"],  # type: ignore
                    **value["args"],  # type: ignore
                )


def add_functions(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "func",
        type=str,
        nargs="?",
        default=default_values["func"],
        choices=FUNC_CHOICES.keys(),
        metavar="%s"
        % (" | ".join("{}: {}".format(k, v) for k, v in FUNC_CHOICES.items())),
        help="function arg",
    )


def get_options(argv: list) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=FlexiFormatter
    )
    func_index = [
        index for index, val in enumerate(argv) if argv[index] in FUNC_CHOICES
    ]
    func = None
    if len(func_index) == 1:
        func = argv[func_index[0]]
    if not func:
        # empty
        func = default_values["func"]
    add_functions(parser)
    if func != "list":
        add_args(parser)
    if func in FUNC_CHOICES:
        add_func_arg(func, parser)
    options = parser.parse_args(argv[1:])
    options.desc = "testing"
    if options.version:
        return options

    # implement help
    if options.func == "help":
        parser.print_help()
        sys.exit(0)

    global DEBUG
    DEBUG = options.debug > 0
    return options


def process_target_options(options):
    global default_values
    if options.bundleid:
        encapp_tool.adb_cmds.set_bundleid(options.bundleid)
        options.idb = True
    set_idb_mode(options.idb)
    default_values["device_workdir"] = get_device_dir()

    if ("device_workdir" not in options) or (options.device_workdir is None):
        options.device_workdir = default_values["device_workdir"]

    # 1. process serial number
    if options.serial is None and "ANDROID_SERIAL" in os.environ:
        # read serial number from ANDROID_SERIAL env variable
        options.serial = os.environ["ANDROID_SERIAL"]


def process_options(options):
    process_target_options(options)
    # 0. Set device type and workdir
    # 2. process replacement shortcuts
    SHORTCUT_LIST = {
        # '-i', type=str, dest='videofile',
        "videofile": "input.filepath",
        "input_resolution": "input.resolution",
        "input_framerate": "input.framerate",
        "output_resolution": "output.resolution",
        "output_framerate": "output.framerate",
        "pix_fmt": "input.pix_fmt",
        # '-c', '--codec', type=str, dest='codec',
        "codec": "configure.codec",
        # '-r', '--bitrate', type=str, dest='bitrate',
        "bitrate": "configure.bitrate",
        "resolution": "configure.resolution",
        "framerate": "configure.framerate",
    }
    if "replace" in options:
        for key, val in SHORTCUT_LIST.items():
            if vars(options).get(key, None) is not None:
                # shortcut was defined
                k1, k2 = val.split(".")
                if k1 not in options.replace:
                    options.replace[k1] = {}
                options.replace[k1][k2] = vars(options)[key]
                # remove the old value
                delattr(options, key)

    # 3. check the validity of some parameters
    if "replace" not in options or not options.replace:
        options.replace = {}
    if options.replace.get("input", {}).get("filepath", ""):
        videofile = options.replace.get("input", {}).get("filepath", "")
        assert os.path.exists(videofile) and os.access(videofile, os.R_OK), (
            f"file {videofile} does not exist"
            if os.path.exists(videofile)
            else f"file {videofile} is not readable"
        )
    if "dim_align" in options and options.dim_align:
        options.width_align = options.dim_align
        options.height_align = options.dim_align

    # 4. derive replace values
    if "input" in options.replace and "filepath" in options.replace["input"]:
        input_filepath = options.replace["input"]["filepath"]
        # convert y4m (raw) files into yuv/rgba (raw) files
        if input_filepath != "camera" and encapp_tool.ffutils.video_is_y4m(
            input_filepath
        ):
            test_suite = tests_definitions.TestSuite()
            for test in test_suite.test:
                with open(options.configfile, "rb") as fd:
                    text_format.Merge(fd.read(), test_suite)

                # replace input and other derived values
                d = process_input_path(
                    input_filepath,
                    options.replace,
                    test,
                    options.mediastore,
                    options.debug,
                )
                if "input" in options:
                    options.input = d["input"]
                if "framerate" in options:
                    options.framerate = d["framerate"]
                if "resolution" in options:
                    options.resolution = d["resolution"]
                if "pix_fmt" in options:
                    options.pix_fmt = d["pix_fmt"]
                if "extension" in options:
                    options.extension = d["extension"]
    # 5. check mediastore
    if "mediastore" in options and options.mediastore:
        if not os.path.exists(options.mediastore):
            os.mkdir(options.mediastore)
    return options


def verify_app_version(json_files):
    if not json_files:
        return

    for fl in json_files:
        with open(fl) as f:
            try:
                data = json.load(f)
                if "encapp_version" in data:
                    version = data["encapp_version"]
                    if encapp_tool.__version__ != version:
                        print(
                            f"Warning, version missmatch between script "
                            f"({encapp_tool.__version__}) "
                            f"and application ({version})"
                        )
                else:
                    print(f"Version info is missing in {fl}")
            except:
                print(
                    f"Verify app version failed. File {fl} failed to be read properly."
                )


def process_input_path(input_filepath, replace, test_input, mediastore, debug=0):
    replace = replace.copy()
    if replace is None:
        print("Process input path with no replacement settings")
        # TODO: just return? or throw error?
    if "output" not in replace:
        print("No output settings in replacement")
        replace["output"] = {}

    # check whether the user has a preferred raw format
    pix_fmt = replace.get("output", {}).get("pix_fmt", PREFERRED_PIX_FMT)
    resolution = replace.get("output", {}).get("resolution", "")
    framerate = replace.get("output", {}).get("framerate", -1)
    extension = "yuv"

    info = encapp_tool.ffutils.get_video_info(input_filepath, debug)
    if len(resolution) == 0:
        # Get from input
        resolution = f"{info['width']}x{info['height']}"
        replace["output"]["resolution"] = resolution
    if framerate <= 0:
        framerate = info["framerate"]
        replace["output"]["framerate"] = framerate
    if "pix_fmt" not in replace["output"]:
        replace["output"]["pix_fmt"] = info["pix-fmt"]

    if "hstride" in replace.get("output", {}) and "vstride" in replace.get(
        "output", {}
    ):
        # Need to replace width and height in the test definitions
        resolution = f"{replace['output']['hstride']}x{replace['output']['vstride']}"
    output_filepath = replace.get("output", {}).get(
        "output_filepath",
        f"{input_filepath}_{resolution}p{framerate}_{pix_fmt}.{extension}",
    )

    # get raw filepath
    if mediastore:
        output_filepath = os.path.join(mediastore, os.path.basename(output_filepath))
    else:
        output_filepath = os.path.join(
            tempfile.gettempdir(), os.path.basename(output_filepath)
        )
    if debug > 0:
        print("***********")
        print("input file will be transcoded")
        print(f"resolution:  -> {resolution}")
        print(f"framerate:  -> {framerate}")
        print(f"pix_fmt: -> {pix_fmt}")
        print("***********")

    if encapp_tool.ffutils.video_is_raw(input_filepath):
        # lazy but let us skip transcodig if the target is already there...
        if not os.path.exists(output_filepath):
            encapp_tool.ffutils.ffmpeg_transcode_raw(
                input_filepath,
                output_filepath,
                replace,
                debug,
            )
        else:
            print(
                f"Warning, transcoded file {output_filepath} exists, assuming it is correct"
            )

    else:
        if not os.path.exists(output_filepath):
            encapp_tool.ffutils.ffmpeg_convert_to_raw(
                input_filepath,
                output_filepath,
                replace,
                debug,
            )
        # replace input and other derived values
    return {
        "filepath": output_filepath,
        "resolution": resolution,
        "pix_fmt": pix_fmt,
        "framerate": framerate,
    }


def check_protobuf_test_setup(options):
    # ensure there is an input configuration
    assert (
        options.configfile is not None
    ), "error: need a valid input configuration file"
    test_suite = tests_definitions.TestSuite()

    for file in options.configfile:
        with open(file, "rb") as fd:
            text_format.Merge(fd.read(), test_suite)
    options_ = copy.deepcopy(options)
    for test in test_suite.test:
        if test.test_setup.serial:
            options_.serial = test.test_setup.serial
        if test.test_setup.device_cmd:
            if test.test_setup.device_cmd.toLower() == "idb":
                set_idb_mode(True)
        if test.test_setup.device_workdir:
            options_.device_workdir = test.test_setup.device_workdir
        if test.test_setup.local_workdir:
            options_.local_workdir = test.test_setup.local_workdir
        if test.test_setup.separate_sources:
            options_.separate_sources = True
        if test.test_setup.mediastore:
            options_.mediastore = test.test_setup.mediastore
        if test.test_setup.source_dir:
            options_.source_dir = test.test_setup.source_dir
    return options_


def regexp_wildcard(pattern: str, data: str) -> re.Match:
    m = None
    try:
        m = re.search(pattern, data)
    except re.error as ex:
        # Just silently ignore
        pass
    if not m and "*" in pattern:
        pattern = pattern.replace("*", ".*")
        m = re.search(pattern, data)
    return m


def is_encoder(codec: dict) -> bool:
    if "is_encoder" in codec.keys():
        return codec["is_encoder"] in ["true", "True", "1", True]

    # iOS doe not have this
    return True


def is_hardware_accelerated(codec: dict) -> bool:
    if "is_hardware_accelerated" in codec.keys():
        return codec["is_hardware_accelerated"] in ["true", "True", "1", True]
    elif "IsHardwareAccelerated" in codec.keys():
        return codec["IsHardwareAccelerated"] in ["true", "True", "1", True]

    return True


def find_codecs(
    codecs: dict,
    codec_name: str,
    encoder: bool = True,
    decoder: bool = False,
    hw: bool = True,
    sw=True,
) -> str:
    for codec in codecs:
        try:
            if not encoder and "is_encoder" in codec and codec["is_encoder"]:
                continue
            if not decoder and "is_encoder" in codec and not codec["is_encoder"]:
                continue
            if "media_type" in codec and "mime_type" in codec["media_type"]:
                mime = codec["media_type"]["mime_type"]
                # We do not look at audio (for mime)
                if "audio" in mime:
                    continue
            m = re.find("is_hardware_accelerated|IsHardwareAccelerates", codec)
            if m:
                key = m.group(1)
                if hw and not codec[key]:
                    return
                if sw and codec[key]:
                    return

        except:
            # Rather do something than fail.
            pass
        # filter on codec, regexp
        m = regexp_wildcard(codec_name, codec["name"].lower())
        if not m:
            m = regexp_wildcard(codec_name, codec["canonical_name"].lower())
        if not m:
            continue

        print(codec["name"])
        return codec["name"]


def print_codec_info(codec: dict, options: argparse.Namespace) -> None:
    if options.encoders and not is_encoder(codec):
        return
    if options.decoders and is_encoder(codec):
        return
    if "media_type" in codec and "mime_type" in codec["media_type"].keys():
        mime = codec["media_type"]["mime_type"]
        if not options.audio and "audio" in mime:
            return
        if options.audio and not "audio" in mime:
            return
    if options.hw and not is_hardware_accelerated(codec):
        return
    if options.sw and is_hardware_accelerated(codec):
        return
    if options.codec:
        # filter on codec, regexp
        m = regexp_wildcard(options.codec, codec["name"].lower())
        if not m:
            m = regexp_wildcard(options.codec, codec["canonical_name"].lower())
        if not m:
            return

    if options.info_level:
        # print everything
        depth = options.info_level
        if depth < 0:
            depth = None
        pprint.pp(codec, sort_dicts=False, indent=4, depth=depth)
    else:
        print(codec["name"])


def merge_options(option1, options2):
    """Merge the two options, let the second have precedence."""
    if not option1:
        return options2
    if not options2:
        return option1
    for key in options2.__dict__:
        if options2.__dict__[key] is not None:
            option1.__dict__[key] = options2.__dict__[key]
    return option1


def main(argv):
    options = get_options(argv)
    # check if this is a test run and if these params are defined in the test

    proto_options = None

    rename_workdir = False
    if options.func == "run":
        # Make sure we are writing to a good place
        # It will be a chicken and egg situation i.e.
        # to handle the cli options after working with the
        # protobuf we may need to save some generated data.
        # Let us rename the folder later after cli options are handled.

        if not options.local_workdir:
            rename_workdir = True
        options = setup_local_workdir(options, f"{int(random.random()*1000)}")

        test_suite = tests_definitions.TestSuite()
        # let us accept a special case where the test is a single '.'
        # This allows us to run a simple test over all files in a folder or a
        # quick one off with only cli options
        if len(options.configfile) == 1 and options.configfile[0] == ".":
            # write an empty file
            filepath = f"{options.local_workdir}/corpus.pbtxt"
            test_suite.test.append(tests_definitions.Test())
            with open(filepath, "w") as f:
                f.write(text_format.MessageToString(test_suite))
            options.configfile[0] = filepath
        proto_options = check_protobuf_test_setup(options)
    options = process_options(options)

    # cli should always override
    if proto_options:
        options = merge_options(proto_options, options)
    if options.version:
        print("version: %s" % encapp_tool.__version__)
        sys.exit(0)

    serial = ""
    # get model and serial number
    if (
        "dry_run" in options
        and options.dry_run
        or "codecs_file" in options
        and options.codecs_file
    ):
        model = "dry run"
        options.seral = "dry run"
    else:
        # get model and serial number
        model, serial = encapp_tool.adb_cmds.get_device_info(
            options.serial, options.debug
        )

    # If needed rename local workfolder
    if rename_workdir:
        options = rename_local_workdir(options, model)

    # install app
    if options.func == "install" or options.install:
        encapp_tool.app_utils.install_app(serial, options.debug)
        exit(0)

    # uninstall app
    if options.func == "uninstall":
        encapp_tool.app_utils.uninstall_app(serial, options.debug)
        print("UNINSTALL")
        exit(0)
        return

    if options.func == "kill":
        print("Force stop")
        encapp_tool.app_utils.force_stop(serial, options.debug)
        return

    if options.func == "clear":
        print("Removes all encapp_*, raw and protobuf files in target folder")
        encapp_tool.adb_cmds.remove_files_using_regex(
            options.serial, "encapp_.*", options.device_workdir, options.debug
        )
        encapp_tool.adb_cmds.remove_files_using_regex(
            options.serial, ".*pbtxt$", options.device_workdir, options.debug
        )
        encapp_tool.adb_cmds.remove_files_using_regex(
            options.serial, ".*[yuv|raw]$", options.device_workdir, options.debug
        )
        return

    if options.func == "reset":
        print("Removes all encapp_* files in target folder")
        # idb is to slow so let us use the app
        if encapp_tool.adb_cmds.IOS_MAJOR_VERSION < 17:
            ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
                f"idb launch --udid {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} reset"
            )
        else:
            ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
                f"xcrun devicectl device process launch --device {serial} {encapp_tool.adb_cmds.IDB_BUNDLE_ID} reset",
            )
        return

    if options.func == "pull_result":
        print("Pulls all encapp_* files in target folder")
        encapp_tool.adb_cmds.pull_files_from_device(
            options.serial, "encapp_.*", options.device_workdir, options.debug
        )
        return

    if "dry_run" in options and not options.dry_run:
        # ensure the app is correctly installed
        assert encapp_tool.app_utils.install_ok(serial, options.debug), (
            "Apps not installed in %s" % serial
        )

    # run function
    if options.func == "list":
        codecs_file = None
        if options.codecs_file:
            codecs_file = options.codecs_file
        else:
            codecs_file = list_codecs(
                serial,
                model,
                options.device_workdir,
                options.output,
                not options.no_cache,
                options.run_cmd,
                options.debug,
            )
        # Read json
        data = None
        if not os.path.exists(codecs_file):
            print(f"ERROR: {codecs_file} does not exist")
            exit - 1
        with open(codecs_file, "r") as codec_file:
            data = json.load(codec_file)
        # check filters
        if not options.decoders and not options.encoders:
            # by default show encoders
            options.encoders = True
        for topkey in data.keys():
            for codec in data[topkey]:
                print_codec_info(codec, options)
        return 0

    elif options.func == "run":
        # ensure there is an input configuration
        assert (
            options.configfile is not None
        ), "error: need a valid input configuration file"

        if not options.dry_run:
            # first clear out old result
            remove_encapp_gen_files(serial, options.device_workdir, options.debug)
        result_ok, result_json = codec_test(options, model, serial, options.debug)

        global QUALITY_PROCESSES

        if options.quality:
            csvdata = []
            counter = 1
            total = len(QUALITY_PROCESSES)
            while len(QUALITY_PROCESSES) > 0:
                print(f"Wait for quality measurement, {counter}/{total}\r", end="")
                quality = QUALITY_PROCESSES.pop()
                proc = quality[0]
                proc.join()
                csvdata.append(quality[1])

            output_csv = []
            data = None
            for file in csvdata:
                data_ = pd.read_csv(file)
                if data is None:
                    data = data_
                else:
                    data = pd.merge(data, data_, how="outer")
                os.remove(file)
            if data is not None:
                data.to_csv(f"{options.local_workdir}/quality.csv")
            elif options.dry_run:
                # Dry run will generate protobuf files
                print("Dry run")
                print(f"* Protbuf output is in {options.local_workdir}")
                print(f"* Transcoded media is in {options.mediastore}/")
            else:
                print("ERROR: no data produced")

        if not options.ignore_results and not options.dry_run:
            verify_app_version(result_json)
        if not result_ok:
            sys.exit(-1)


if __name__ == "__main__":
    try:
        main(sys.argv)
    except AssertionError as ae:
        print(ae, file=sys.stderr)
        if DEBUG:
            raise
        sys.exit(1)
