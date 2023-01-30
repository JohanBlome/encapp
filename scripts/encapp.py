#!/usr/bin/env python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
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

import encapp_tool
import encapp_tool.app_utils
import encapp_tool.adb_cmds
import encapp_tool.ffutils
import copy

SCRIPT_ROOT_DIR = os.path.abspath(
    os.path.join(encapp_tool.app_utils.SCRIPT_DIR, os.pardir)
)
sys.path.append(SCRIPT_ROOT_DIR)
import proto.tests_pb2 as tests_definitions  # noqa: E402


RD_RESULT_FILE_NAME = "rd_results.json"

DEBUG = False

FUNC_CHOICES = {
    "help": "show help options",
    "install": "install apks",
    "uninstall": "uninstall apks",
    "list": "list codecs and devices supported",
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
        print(f"\n\n*** Wait for exit **\n\n")
    time.sleep(2)
    if encapp_tool.adb_cmds.USE_IDB:
        state = "Running"
        while state == "Running":
            time.sleep(1)
            # ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(f"idb list-apps  --fetch-process-state  --udid {serial} | grep Meta.Encapp")
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
        print(f"\n\n*** Done waiting **\n\n")


def run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug):
    if debug > 0:
        print(f"running test: {protobuf_txt_filepath}")
    # clean the logcat first
    encapp_tool.adb_cmds.reset_logcat(serial)
    if encapp_tool.adb_cmds.USE_IDB:
        # remove log file first

        ret, _, stderr = encapp_tool.adb_cmds.run_cmd(
            f"idb file rm Documents/encapp.log --udid {serial} Meta.Encapp ",
            debug,
        )

        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
            f"idb launch --udid {serial} Meta.Encapp " f"test {protobuf_txt_filepath}",
            debug,
        )
    else:
        # clean the logcat first
        encapp_tool.adb_cmds.reset_logcat(serial)
        ret, _, stderr = encapp_tool.adb_cmds.run_cmd(
            f"adb -s {serial} shell am start -W "
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
        cmd = f"idb file ls {device_workdir}/ --udid {serial} --bundle-id Meta.Encapp"
    else:
        cmd = f"adb -s {serial} shell ls {device_workdir}/"
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    output_files = re.findall(
        encapp_tool.adb_cmds.ENCAPP_OUTPUT_FILE_NAME_RE, stdout, re.MULTILINE
    )
    if encapp_tool.adb_cmds.USE_IDB:
        # Set app in standby so screen is not locked
        cmd = f"idb launch --udid {serial} Meta.Encapp standby",
        encapp_tool.adb_cmds.run_cmd(cmd, debug)
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
            cmd = f"idb file pull {device_workdir}/{file} {local_workdir} --udid {serial} --bundle-id Meta.Encapp"
        else:
            cmd = f"adb -s {serial} pull {device_workdir}/{file} " f"{local_workdir}"
        encapp_tool.adb_cmds.run_cmd(cmd, debug)
        # remove the file on the device
        # Too slow at least on ios, remove everyting as a last all instead.
        if not encapp_tool.adb_cmds.USE_IDB:
            cmd = f"adb -s {serial} shell rm {device_workdir}/{file}"
        encapp_tool.adb_cmds.run_cmd(cmd, debug)
        # append results file (json files) to final results
        if file.endswith(".json"):
            path, tmpname = os.path.split(file)
            result_json.append(os.path.join(local_workdir, tmpname))
    # remo/proceve the test file
    if encapp_tool.adb_cmds.USE_IDB:
        cmd = f"idb file pull {device_workdir}/{protobuf_txt_filepath} {local_workdir} --udid {serial} --bundle-id Meta.Encapp"
    else:
        cmd = f"adb -s {serial} shell rm " f"{device_workdir}/{protobuf_txt_filepath}"
    encapp_tool.adb_cmds.run_cmd(cmd, debug)
    if debug > 0:
        print(f"results collect: {result_json}")
    # dump device information
    dump_device_info(serial, local_workdir, debug)
    # get logcat
    if encapp_tool.adb_cmds.USE_IDB:
        cmd = f"idb file pull {device_workdir}/encapp.log {local_workdir} --udid {serial} --bundle-id Meta.Encapp"
        encapp_tool.adb_cmds.run_cmd(cmd, debug)
        # Release the app
        encapp_tool.app_utils.force_stop(serial)
        # Remove test output files
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
            f"idb launch --udid {serial} Meta.Encapp reset",
            debug,
        )
        # TODO: checks on ios
        result_ok = True
        return result_ok, result_json
    else:
      logcat_contents = encapp_tool.adb_cmds.logcat_dump(serial)
      result_ok = parse_logcat(logcat_contents, local_workdir)
      return result_ok, result_json


def dump_device_info(serial, local_workdir, debug):
    props_dict = encapp_tool.adb_cmds.getprop(serial, debug)
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
        r".*Test finished id: \"(?P<id>[^\"]+)\".*run_id: (?P<run_id>[^\ ]+) result: \"(?P<result>[^\"]+)\"(?P<rem>.*)"
    )
    result_ok = True
    for line in logcat_contents.splitlines():
        line_match = line_re.search(line)
        if line_match:
            if line_match.group("result") == "ok":
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
    if test.input.filepath != "camera":
        name = os.path.basename(test.input.filepath)
        if name not in all_files:
            all_files.add(name)
    for subtest in test.parallel.test:
        if subtest.input.filepath != "camera":
            get_media_files(subtest, all_files)
    return


def add_media_files(test, files_to_push):
    if test.input.filepath != "camera":
        full_path = os.path.expanduser(test.input.filepath)
        if full_path not in files_to_push:
            files_to_push.add(full_path)
    for subtest in test.parallel.test:
        if subtest.input.filepath != "camera":
            add_media_files(subtest, files_to_push)
    return


def update_media_files(test, options):
    files_to_push = set()
    if test.input.filepath != "camera":
        update_media(test, options)
    for subtest in test.parallel.test:
        if subtest.input.filepath != "camera":
            update_media_files(subtest, options)
    return


def parse_multiply(multiply):
    definition = []
    # [32,][3,"test"] or 32
    reg = '([0-9]*),\s?([\w.\/"]*)'
    m = re.findall(reg, multiply)
    if m:
        for pair in m:
            definition.append([int(pair[0]), pair[1]])

    elif multiply is not None and len(multiply) > 0:
        # Simple copy
        definition.append([int(multiply), None])

    return definition


def read_and_update_proto(protobuf_txt_file, local_workdir, options):
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)

    test_suite = tests_definitions.TestSuite()
    with open(protobuf_txt_file, "rb") as fd:
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
    # now we need to go through all test and update media
    for test in test_suite.test:
        update_media_files(test, options)

    # 2. get a list of all the media files that will need to be pushed
    files_to_push = set()
    for test in test_suite.test:
        add_media_files(test, files_to_push)

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

    # 5. save the full protobuf text file(s)
    if options.split:  # one pbtxt file per subtest
        protobuf_txt_filepath = "split"
        for test in test_suite.test:
            output_dir = f"{local_workdir}/{test.common.id}"
            if not os.path.exists(output_dir):
                os.mkdir(output_dir)
            filename = f"{output_dir}/{test.common.id}.pbtxt"
            with open(filename, "w") as f:
                f.write(text_format.MessageToString(test))
            files_to_push |= {filename}
    else:  # one pbtxt for all tests
        protobuf_txt_filepath = f"{local_workdir}/run.pbtxt"
        with open(protobuf_txt_filepath, "w") as f:
            f.write(text_format.MessageToString(test_suite))
        files_to_push |= {protobuf_txt_filepath}
    return test_suite, files_to_push, protobuf_txt_filepath


def run_codec_tests_file(
    protobuf_txt_file, model, serial, local_workdir, options, debug
):
    if debug > 0:
        print(f"reading test: {protobuf_txt_file}")
    test_suite, files_to_push, protobuf_txt_filepath = read_and_update_proto(
        protobuf_txt_file, local_workdir, options
    )

    # multiply tests per request
    parallel_defs = parse_multiply(options.multiply)
    additional = tests_definitions.Parallel()
    updated = False
    for test in test_suite.test:
        for definition in parallel_defs:
            template = test
            counts = definition[0] + 1
            if definition[1] != None and len(definition[1]) > 0:
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
            path = f"{local_workdir}/{test.common.id}.pbtxt"
            with open(path, "w") as f:
                f.write(text_format.MessageToString(suite))
            files_to_push |= {path}
    # Save the complete test if updated
    if updated:
        # remove any older pbtxt in existence
        if debug > 0:
            print("Remove other pbtxt files")
        files_to_push = {fl for fl in files_to_push if not fl.endswith(".pbtxt")}
        protobuf_txt_filepath = f"{local_workdir}/{test.common.id}_aggr.pbtxt"
        with open(protobuf_txt_filepath, "w") as f:
            f.write(text_format.MessageToString(test_suite))
        if debug > 0:
            print(f"add {protobuf_txt_filepath}")
        files_to_push |= {protobuf_txt_filepath}
    return run_codec_tests(
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


def abort_test(local_workdir, message):
    print("\n*** Test failed ***")
    print(f"Remove {local_workdir}")
    print(message)
    # shutil.rmtree(local_workdir)
    sys.exit(-1)


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
        return list(
            range(start, stop + 1, step)
        )  # We want to include the last value...
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
        resolution_spec = bitrate.split("-")
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
        framerate_spec = bitrate.split("-")
        assert len(framerate_spec) == 3, f'error: invalid framerate spec: "{framerate}"'
        start, stop, step = framerate_spec
        return list(
            range(start, stop + 1, step)
        )  # We want to include the last value...
    # parse single elements
    return [framerate]


def update_media(test, options):
    in_res = test.input.resolution
    in_rate = test.input.framerate
    in_pix_fmt = test.input.pix_fmt
    out_res = test.configure.resolution
    out_rate = test.configure.framerate
    out_pix_fmt = options.pix_fmt

    if len(out_res) == 0:
        out_res = in_res
    if out_rate == 0:
        out_rate = in_rate

    if encapp_tool.ffutils.video_is_raw(test.input.filepath) and (in_res != out_res or in_rate != out_rate or  in_pix_fmt != out_pix_fmt):
        print(f"Transcode raw input: {test.input.filepath}")
        replace = {}
        input = {}
        output = {}
        basename = os.path.basename(test.input.filepath)

        input["pix_fmt"] = tests_definitions.Input.PixFmt.Name(in_pix_fmt)
        input["resolution"] = in_res
        input["framerate"] = in_rate
        if options.pix_fmt is None:
             output["pix_fmt"] = input["pix_fmt"]
        else:
            output["pix_fmt"] = options.pix_fmt
        output["resolution"] = out_res
        output["framerate"] = out_rate

        extension = "raw"
        if output["pix_fmt"] == "rgba":
            extension = "rgba"
        output[
            "output_filepath"
        ] = f"{options.mediastore}/{basename}_{out_res}@{round(out_rate, 2)}_{out_pix_fmt}.{extension}"
        replace["input"] = input
        replace["output"] = output

        d = process_input_path(test.input.filepath, replace)
        # now both config and input should be the same i.e. matchign config
        print(f"INPUT {input}, OUTPUT {output} red:{d}")
        test.input.resolution = d["resolution"]
        test.input.framerate = d["framerate"]
        test.input.pix_fmt = PIX_FMT_TYPES_VALUES[d["pix_fmt"]]
        test.input.filepath = d["filepath"]


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
):
    tests_id = None
    # save the main test id
    if test.parallel:
        subtests = test.parallel.test
        loop_counter = 0
        for subtest in subtests:
            update_codec_test(
                subtest, test, local_workdir, device_workdir, replace, mediastore, True
            )

    # 1.2. replace the parameters that do not create multiple tests
    # TODO(chema): there should be an automatic way to do this
    CONFIGURE_INT_KEYS = ("quality", "complexity", "durationUs", "color_format")
    INPUT_INT_KEYS = ("playout_frames", "pursuit")
    CONFIGURE_FLOAT_KEYS = ("framerate","stoptime_sec")
    INPUT_FLOAT_KEYS = ("framerate", "stoptime_sec")
    CONFIGURE_BOOL_KEYS = ("encode", "surface", "decode_dump",)
    INPUT_BOOL_KEYS = ("show","realtime")

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
                val = tests_definitions.Input.PixFmt.Value(val)
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
                print(f"Something is wrong with {k1}, {k2} - {val}")
                continue
            setattr(getattr(test, k1), k2, val)

    # If no surface decode and no raw input, decode to raw
    if encapp_tool.ffutils.video_is_y4m(test.input.filepath):
        options = None
        if test.configure.surface:
            # set pixel config to rgba
            options = {}
            input = {}
            input["pix_fmt"] = "rgba"
            options["input"] = input
        d = process_input_path(test.input.filepath, options)
        test.input.resolution = d["resolution"]
        test.input.framerate = d["framerate"]
        test.input.pix_fmt = PIX_FMT_TYPES_VALUES[d["pix_fmt"]]
        test.input.filepath = d["filepath"]

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

                    # remove the options already teken care of
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
                    # remove the options already teken care of
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
                    # remove the options already teken care of
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
            test.common.id = test.common.id + f".{bitrate_list[0]}bps"
    if not is_parallel:
        updated_test_suite.test.extend([test])


# Update a set of tests with the CLI arguments.
# Note that update may include adding new tests (e.g. if bitrate is
# defined as a (from, to, step) tuple instead of a single value).
def update_codec_testsuite(
    test_suite, updated_test_suite, local_workdir, device_workdir, replace, mediastore
):
    # 1. update the tests with the CLI parameters

    # 1.1. replace the parameters that create multiple tests
    # 1.1.1. process configure.bitrate
    for test in test_suite.test:
        update_codec_test(
            test, updated_test_suite, local_workdir, device_workdir, replace, mediastore
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
    if device_workdir == None:
        device_workdir = default_values["device_workdir"]

    os.makedirs(local_workdir, exist_ok=True)
        
    collected_results = []
    # run the test(s)
    if split:  # one pbtxt file per subtest
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
                    if f"{test.common.id}.pbtxt" in data:
                        print("Test already done, moving on.")
                        ignore_results = True
                        continue
            ignore_results = False
            files = set()
            get_media_files(test, files)
            for filepath in files:
                print(f"Copy file to device work dir: {device_workdir}")
                if not encapp_tool.adb_cmds.push_file_to_device(
                    f"{mediastore}/{filepath}", serial, device_workdir, fast_copy, debug
                ):
                    abort_test(local_workdir, f"Error copying {filepath} to {serial}")
                print(f"Push test defs: lw = {local_workdir}")
                if not encapp_tool.adb_cmds.push_file_to_device(
                    f"{local_workdir}/{test.common.id}.pbtxt",
                    serial,
                    device_workdir,
                    fast_copy,
                    debug,
                ):
                    abort_test(local_workdir, f"Error copying {filepath} to {serial}")

            if encapp_tool.adb_cmds.USE_IDB:
                protobuf_txt_filepath = f"{test.common.id}.pbtxt"
            else:
                protobuf_txt_filepath = f"{device_workdir}/{test.common.id}.pbtxt"
            run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug)

            with open(tests_run, "a") as passed:
                passed.write(f"{test.common.id}.pbtxt\n")

            # Pull the log file (it will be overwritten otherwise)
            if encapp_tool.adb_cmds.USE_IDB:
                print(
                    "Currently filesystem synch on ios seems to be slow, sleep a little while"
                )
                time.sleep(1)
                cmd = f"idb file pull {device_workdir}/encapp.log {local_workdir} --udid {serial} --bundle-id Meta.Encapp"
                encapp_tool.adb_cmds.run_cmd(cmd, debug)
                try:
                    os.rename(
                        f"{local_workdir}/encapp.log",
                        f"{local_workdir}/{test.common.id}.log",
                    )
                except:
                    print("Changing name on the ios log file")
            print("Collect results")
            collected_results.extend(collect_results(
                local_workdir,
                protobuf_txt_filepath,
                serial, device_workdir,
                debug))
    

    else:  # one pbtxt for all tests
        # push all the files to the device workdir
        if encapp_tool.adb_cmds.USE_IDB:
            cmd = f"idb launch --udid {serial} Meta.Encapp standby"
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
            
        run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug)

        # collect the test results
        # Pull the log file (it will be overwritten otherwise)
        if encapp_tool.adb_cmds.USE_IDB:
            print(
                "Currently filesystem synch on ios seems to be slow, sleep a little while"
            )
            time.sleep(1)
            cmd = f"idb file pull {device_workdir}/encapp.log {local_workdir} --udid {serial} --bundle-id Meta.Encapp"
            encapp_tool.adb_cmds.run_cmd(cmd, debug)
            try:
                os.rename(
                    f"{local_workdir}/encapp.log",
                    f"{local_workdir}/{basename}.log",
                )
            except Exception as ex:
                print(f"ERROR: Changing name on the ios log file: {ex}")
        if ignore_results:
            return None, None
        collected_results.extend(collect_results(
            local_workdir, protobuf_txt_filepath, serial, device_workdir, debug
        ))
    return collected_results


def list_codecs(serial, model, device_workdir, debug=0):
    model_clean = model.replace(" ", "_")
    filename = f"codecs_{model_clean}.txt"
    if encapp_tool.adb_cmds.USE_IDB:
        cmd = {f"idb launch --udid {serial} Meta.Encapp list_codecs"}
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
        assert ret, 'error getting codec list: "%s"' % stdout
        # for some bizzare reason if using a destination a directory is created...
        cmd = f"idb file pull {device_workdir}/codecs.txt . --udid {serial} --bundle-id Meta.Encapp"
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)

        cmd = f"mv codecs.txt {filename}"
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    else:
        adb_cmd = (
            f"adb -s {serial} shell am start "
            f"-e workdir {device_workdir} "
            "-e ui_hold_sec 3 "
            f"-e list_codecs a {encapp_tool.app_utils.ACTIVITY}"
        )

        encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
        wait_for_exit(serial, debug)
        adb_cmd = f"adb -s {serial} pull {device_workdir}/codecs.txt {filename}"
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
        assert ret, 'error getting codec list: "%s"' % stdout

    with open(filename, "r") as codec_file:
        lines = codec_file.readlines()
        for line in lines:
            print(line.split("\n")[0])
        print(f"File is available in current dir as {filename}")


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


def check_protobuf_txt_file(protobuf_txt_file, local_workdir, debug):
    # ensure the protobuf text file exists and is readable
    if protobuf_txt_file is None:
        abort_test(local_workdir, "ERROR: need a test file name")
    if (
        not os.path.exists(protobuf_txt_file)
        or not os.path.isfile(protobuf_txt_file)
        or not os.access(protobuf_txt_file, os.R_OK)
    ):
        abort_test(local_workdir, f'ERROR: invalid test file name "{protobuf_txt_file}"')
    # use a temp file for the binary output
    _, protobuf_bin_file = tempfile.mkstemp(dir=tempfile.gettempdir())
    cmd = f'protoc -I {protobuf_txt_file} --encode="TestSuite" ' f"{protobuf_bin_file}"
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    assert ret == 0, f"ERROR: {stderr}"


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
    if options.mediastore is None:
        options.mediastore = local_workdir
    # check the protobuf text is correct
    protobuf_txt_file = options.configfile
    check_protobuf_txt_file(protobuf_txt_file, local_workdir, debug)

    # run the codec test
    return run_codec_tests_file(
        protobuf_txt_file, model, serial, local_workdir, options, debug
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


def get_options(argv):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=FlexiFormatter
    )
    parser.add_argument(
        "-v",
        "--version",
        action="store_true",
        dest="version",
        default=False,
        help="Print version",
    )
    parser.add_argument(
        "-d",
        "--debug",
        action="count",
        dest="debug",
        default=default_values["debug"],
        help="Increase verbosity (use multiple times for more)",
    )
    parser.add_argument(
        "--quiet",
        action="store_const",
        dest="debug",
        const=-1,
        help="Zero verbosity",
    )
    parser.add_argument("--serial", help="Android device serial number")
    parser.add_argument(
        "--install",
        action="store_const",
        dest="install",
        const=True,
        default=default_values["install"],
        help="Do install apk",
    )
    parser.add_argument(
        "--no-install",
        action="store_const",
        dest="install",
        const=False,
        help="Do not install apk",
    )
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
        It will stop when the pixel count pass stop value (calculated as wxh)""",
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
        "--pix_fmt",
        type=str,
        dest="pix_fmt",
        default=None,
        metavar="pix_fmt",
        help="wanted pix fmt for the encoder",
    )
    # other parameters
    parser.add_argument(
        "--device-workdir",
        type=str,
        dest="device_workdir",
        default=None,
        metavar="device_workdir",
        help="work (storage) directory on device",
    )
    parser.add_argument(
        "-w",
        "--local-workdir",
        type=str,
        dest="local_workdir",
        default=default_values["local_workdir"],
        metavar="local_workdir",
        help="work (storage) directory on local host",
    )
    parser.add_argument(
        "configfile",
        type=str,
        nargs="?",
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
        "--idb",
        action="store_true",
        dest="idb",
        default=False,
        help="Run on ios using idb",
    )
    parser.add_argument(
        "--split",
        action="store_true",
        dest="split",
        default=False,
        help="Run serial test individually",
    )
    parser.add_argument(
        "--mediastore",
        type=str,
        nargs="?",
        default=default_values["mediastore"],
        metavar="mediastore",
        help="store all input and generated file in one folder",
    )

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


def process_options(options):
    # 0. Set device type and workdir
    encapp_tool.adb_cmds.set_idb_mode(options.idb)
    default_values["device_workdir"] = get_device_dir()

    if ("device_workdir" not in options) or (options.device_workdir is None):
        options.device_workdir = default_values["device_workdir"]

    # 1. process serial number
    if options.serial is None and "ANDROID_SERIAL" in os.environ:
        # read serial number from ANDROID_SERIAL env variable
        options.serial = os.environ["ANDROID_SERIAL"]

    # 2. process replacement shortcuts
    SHORTCUT_LIST = {
        # '-i', type=str, dest='videofile',
        "videofile": "input.filepath",
        "input_resolution": "input.resolution",
        "input_framerate": "input.framerate",
        "output_resolution": "output.resolution",
        "output_framerate": "output.framerate",
        # '-c', '--codec', type=str, dest='codec',
        "codec": "configure.codec",
        # '-r', '--bitrate', type=str, dest='bitrate',
        "bitrate": "configure.bitrate",
        "resolution": "configure.resolution",
        "framerate": "configure.framerate",
    }
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

    # 4. derive replace values
    if "input" in options.replace and "filepath" in options.replace["input"]:
        input_filepath = options.replace["input"]["filepath"]
        # convert y4m (raw) files into yuv/rgba (raw) files
        if input_filepath != "camera" and encapp_tool.ffutils.video_is_y4m(
            input_filepath
        ):
            # replace input and other derived values
            d = process_input_path(input_filepath, options.replace, options.debug)
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
    return options


def verify_app_version(json_files):
    for fl in json_files:
        with open(fl) as f:
            try:
                data = json.load(f)
                version = data["encapp_version"]
                if encapp_tool.__version__ != version:
                    print(
                        f"Warning, version missmatch between script "
                        f"({encapp_tool.__version__}) "
                        f"and application ({version})"
                    )
            except:
                print(f"File {fl} failed to be read")


def process_input_path(input_filepath, replace, debug=0):
    # Raw input?
    if encapp_tool.ffutils.video_is_raw(input_filepath):
        settings = {}
        settings["pix_fmt"] = replace.get("output", {}).get(
            "pix_fmt", replace.get("input", {}).get("pix_fmt")
        )
        settings["resolution"] = replace.get("output", {}).get(
            "resolution", replace.get("input", {}).get("resolution")
        )
        settings["framerate"] = replace.get("output", {}).get(
            "framerate", replace.get("input", {}).get("framerate")
        )

        if debug > 0:
            print("***********")
            print(f"raw input file {input_filepath} will be transcoded")
            print(f"resolution:  -> {settings['resolution']}")
            print(f"framerate:  -> {settings['framerate']}")
            print(f"pix_fmt: -> {settings['pix_fmt'] =}")
            print("***********")
            print(f"{replace}")
        output_filepath = replace.get("output", {}).get(
            "output_filepath",
            f"{input_filepath}_{settings['resolution']}@{settings['framerate']}_{settings['pix_fmt']}.raw",
        )

        # lazy but let us skip transcodig if the target is already there...
        if not os.path.exists(output_filepath):
            encapp_tool.ffutils.ffmpeg_transcode_raw(
                input_filepath,
                output_filepath,
                replace,
                debug,
            )
        else:
            print("Warning, transcoded file exists, assuming it is correct")
        # replace input and other derived values
        return {
            "filepath": output_filepath,
            "resolution": settings["resolution"],
            "pix_fmt": settings["pix_fmt"],
            "framerate": settings["framerate"],
        }

    else:
        # decode encoded video (the encoder wants raw video)
        videofile_config = encapp_tool.ffutils.get_video_info(input_filepath)
        if replace is not None:
            # check whether the user has a preferred raw format
            pix_fmt = replace.get("input", {}).get("pix_fmt", PREFERRED_PIX_FMT)
            resolution = f'{videofile_config["width"]}x{videofile_config["height"]}'
            # TODO: why not framerate?
            framerate = videofile_config["framerate"]
            extension = PIX_FMT_TYPES[pix_fmt]
        else:
            pix_fmt = PREFERRED_PIX_FMT
            resolution = f"{videofile_config['width']}x{videofile_config['height']}"
            framerate = videofile_config["framerate"]
            extension = PIX_FMT_TYPES[pix_fmt]

        # get raw filepath
        input_filepath_raw = os.path.join(
            tempfile.gettempdir(), os.path.basename(input_filepath)
        )
        input_filepath_raw += f".{resolution}"
        input_filepath_raw += f".{framerate}"
        input_filepath_raw += f".{pix_fmt}"
        input_filepath_raw += f".{extension}"
        if debug > 0:
            print("***********")
            print("input file will be transcoded")
            print(f"resolution:  -> {resolution}")
            print(f"framerate:  -> {framerate}")
            print(f"pix_fmt: -> {pix_fmt}")
            print("***********")

        encapp_tool.ffutils.ffmpeg_convert_to_raw(
            input_filepath,
            input_filepath_raw,
            pix_fmt,
            resolution,
            videofile_config["framerate"],
            debug,
        )
        # replace input and other derived values
        return {
            "filepath": input_filepath_raw,
            "resolution": resolution,
            "pix_fmt": pix_fmt,
            "framerate": framerate,
        }


def main(argv):
    options = get_options(argv)
    options = process_options(options)

    if options.version:
        print("version: %s" % encapp_tool.__version__)
        sys.exit(0)

    # get model and serial number
    model, serial = encapp_tool.adb_cmds.get_device_info(options.serial, options.debug)

    # install app
    if options.func == "install" or options.install:
        encapp_tool.app_utils.install_app(serial, options.debug)

    # uninstall app
    if options.func == "uninstall":
        encapp_tool.app_utils.uninstall_app(serial, options.debug)
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
        ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(
            f"idb launch --udid {serial} Meta.Encapp reset"
        )
        return

    if options.func == "pull_result":
        print("Pulls all encapp_* files in target folder")
        encapp_tool.adb_cmds.pull_files_from_device(
            options.serial, "encapp_.*", options.device_workdir, options.debug
        )
        return

    # ensure the app is correctly installed
    assert encapp_tool.app_utils.install_ok(serial, options.debug), (
        "Apps not installed in %s" % serial
    )

    # run function
    if options.func == "list":
        list_codecs(serial, model, options.device_workdir, options.debug)

    elif options.func == "run":
        # ensure there is an input configuration
        assert (
            options.configfile is not None
        ), "error: need a valid input configuration file"

        # first clear out old result
        remove_encapp_gen_files(serial, options.device_workdir, options.debug)
        result_ok, result_json = codec_test(options, model, serial, options.debug)

        if not options.ignore_results:
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
