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


def remove_encapp_gen_files(
    serial, device_workdir=default_values["device_workdir"], debug=0
):
    # remove any files that are generated in previous runs
    regex_str = encapp_tool.adb_cmds.ENCAPP_OUTPUT_FILE_NAME_RE
    encapp_tool.adb_cmds.remove_files_using_regex(
        serial, regex_str, device_workdir, debug
    )


def wait_for_exit(serial, debug=0):
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
    else:
        print(f"{encapp_tool.app_utils.APPNAME_MAIN} was not active")


def run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug):
    if debug > 0:
        print(f"running test: {protobuf_txt_filepath}")
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
    adb_cmd = f"adb -s {serial} shell ls {device_workdir}/"
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
    output_files = re.findall(
        encapp_tool.adb_cmds.ENCAPP_OUTPUT_FILE_NAME_RE, stdout, re.MULTILINE
    )
    # prepare the local working directory to pull the files in
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)
    result_json = []
    for file in output_files:
        if file == "":
            print("No file found")
            continue
        # pull the output file
        adb_cmd = f"adb -s {serial} pull {device_workdir}/{file} " f"{local_workdir}"
        encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
        # remove the file on the device
        adb_cmd = f"adb -s {serial} shell rm {device_workdir}/{file}"
        encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
        # append results file (json files) to final results
        if file.endswith(".json"):
            path, tmpname = os.path.split(file)
            result_json.append(os.path.join(local_workdir, tmpname))
    # remo/proceve the test file
    adb_cmd = f"adb -s {serial} shell rm " f"{device_workdir}/{protobuf_txt_filepath}"
    encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
    if debug >= 0:
        print(f"results collect: {result_json}")
    # dump device information
    dump_device_info(serial, local_workdir, debug)
    # get logcat
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


def update_file_paths(test, device_workdir):
    # update subtests
    for subtest in test.parallel.test:
        update_file_paths(subtest, device_workdir)
    # camera tests do not need any input file paths
    if test.input.filepath == "camera":
        return
    # update main test
    basename = os.path.basename(test.input.filepath)
    test.input.filepath = f"{device_workdir}/{basename}"


def add_media_files(test):
    files_to_push = set()
    if test.input.filepath != "camera":
        files_to_push.add(test.input.filepath)
    for subtest in test.parallel.test:
        if subtest.input.filepath != "camera":
            files_to_push |= add_media_files(subtest)
    return files_to_push


def run_codec_tests_file(
    protobuf_txt_file, model, serial, local_workdir, options, debug
):
    if debug > 0:
        print(f"reading test: {protobuf_txt_file}")
    test_suite = tests_definitions.TestSuite()
    with open(protobuf_txt_file, "rb") as fd:
        text_format.Merge(fd.read(), test_suite)
        # test_suite.ParseFromString(fd.read())
    if debug > 0:
        print(f"updating test: {protobuf_txt_file}")
    test_suite, files_to_push, protobuf_txt_filepath = update_codec_tests(
        test_suite, local_workdir, options.device_workdir, options.replace
    )
    return run_codec_tests(
        test_suite,
        files_to_push,
        protobuf_txt_filepath,
        model,
        serial,
        local_workdir,
        options.device_workdir,
        options.ignore_results,
        options.fast_copy,
        debug,
    )


def abort_test(local_workdir, message):
    print("\n*** Test failed ***")
    print(message)
    shutil.rmtree(local_workdir)
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
        return list(range(start, stop, step))
    # parse single elements
    return [convert_to_bps(bitrate)]


# Update a set of tests with the CLI arguments.
# Note that update may include adding new tests (e.g. if bitrate is
# defined as a (from, to, step) tuple instead of a single value).
def update_codec_tests(test_suite, local_workdir, device_workdir, replace):
    # 1. update the tests with the CLI parameters
    updated_test_suite = tests_definitions.TestSuite()
    tests_id = None
    # 1.1. replace the parameters that create multiple tests
    # 1.1.1. process configure.bitrate
    for test in test_suite.test:
        # save the main test id
        if tests_id is None:
            tests_id = test.common.id
        # replace values from CLI options
        bitrate_str = replace.get("configure", {}).get("bitrate", "")
        if bitrate_str:
            # update the bitrate
            bitrate_list = parse_bitrate_field(bitrate_str)
            for bitrate in bitrate_list:
                # create a new test with the new bitrate
                ntest = tests_definitions.Test()
                ntest.CopyFrom(test)
                ntest.common.id = test.common.id + f".{bitrate}"
                ntest.configure.bitrate = str(bitrate)
                updated_test_suite.test.extend([ntest])
        else:
            updated_test_suite.test.extend([test])
    test_suite = updated_test_suite

    # 1.2. replace the parameters that do not create multiple tests
    # TODO(chema): there should be an automatic way to do this
    CONFIGURE_INT_KEYS = ("quality", "complexity", "durationUs", "color_format")
    INPUT_INT_KEYS = ("playout_frames", "pursuit")
    CONFIGURE_FLOAT_KEYS = ("framerate",)
    INPUT_FLOAT_KEYS = ("framerate", "stoptime_sec")
    CONFIGURE_BOOL_KEYS = ("decode_dump",)
    INPUT_BOOL_KEYS = ("show",)
    for test in test_suite.test:
        for k1 in replace:
            for k2, val in replace[k1].items():
                if (k1, k2) == ("configure", "bitrate"):
                    # already processed
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
                # process integer keys
                if (k1 == "configure" and k2 in CONFIGURE_BOOL_KEYS) or (
                    k1 == "input" and k2 in INPUT_BOOL_KEYS
                ):
                    # force bool value
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
                if not test.HasField(k1):
                    # create the Message field
                    getattr(test, k1).SetInParent()
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

    # 2. get a list of all the media files that will need to be pushed
    files_to_push = set()
    for test in test_suite.test:
        files_to_push |= add_media_files(test)

    # 3. save the media files
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)
    for filepath in files_to_push:
        # https://stackoverflow.com/a/30359308
        shutil.copy2(filepath, local_workdir + "/")

    # 4. update all the file paths to the remote workdir
    for test in test_suite.test:
        update_file_paths(test, device_workdir)

    # 5. save the full protobuf text file(s)
    if False:  # one pbtxt file per subtest
        for test in test_suite.test:
            output_dir = f"{local_workdir}/{test.common.id}"
            if not os.path.exists(output_dir):
                os.mkdir(output_dir)
            filename = f"{output_dir}/{test.common.id}.pbtxt"
            with open(filename, "w") as f:
                f.write(text_format.MessageToString(test))
            files_to_push |= {filename}
    else:  # one pbtxt for all tests
        protobuf_txt_filepath = f"{local_workdir}/{tests_id}.pbtxt"
        with open(protobuf_txt_filepath, "w") as f:
            f.write(text_format.MessageToString(test_suite))
        files_to_push |= {protobuf_txt_filepath}

    # print(f'files to push: {files_to_push}')
    return test_suite, files_to_push, protobuf_txt_filepath


def run_codec_tests(
    test_suite,
    files_to_push,
    protobuf_txt_filepath,
    model,
    serial,
    local_workdir,
    device_workdir,
    ignore_results,
    fast_copy,
    debug,
):
    if debug > 0:
        print(f"running {protobuf_txt_filepath} ({len(test_suite.test)} test(s))")
    os.makedirs(local_workdir, exist_ok=True)

    # push all the files to the device workdir
    for filepath in files_to_push:
        if not encapp_tool.adb_cmds.push_file_to_device(
            filepath, serial, device_workdir, fast_copy, debug
        ):
            abort_test(local_workdir, f"Error copying {filepath} to {serial}")

    # run the test(s)
    if False:  # one pbtxt file per subtest
        for test in test_suite.test:
            protobuf_txt_filepath = f"{device_workdir}/{test.common.id}.pbtxt"
            run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug)
    else:  # one pbtxt for all tests
        basename = os.path.basename(protobuf_txt_filepath)
        protobuf_txt_filepath = f"{device_workdir}/{basename}"
        run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug)

    # collect the test results
    if ignore_results:
        return None, None
    return collect_results(
        local_workdir, protobuf_txt_filepath, serial, device_workdir, debug
    )


def list_codecs(serial, model, device_workdir, debug=0):
    adb_cmd = (
        f"adb -s {serial} shell am start "
        f"-e workdir {device_workdir} "
        "-e ui_hold_sec 3 "
        f"-e list_codecs a {encapp_tool.app_utils.ACTIVITY}"
    )

    encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
    wait_for_exit(serial, debug)
    filename = f"codecs_{model}.txt"
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
        abort_test(local_workdir, "ERROR: invalid test file name")
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
        local_workdir = f'{options.desc.replace(" ", "_")}' f"_{model}_{dt_string}"

    # check the protobuf text is correct
    protobuf_txt_file = options.configfile
    check_protobuf_txt_file(protobuf_txt_file, local_workdir, debug)

    # run the codec test
    return run_codec_tests_file(
        protobuf_txt_file, model, serial, local_workdir, options, debug
    )


def get_options(argv):
    parser = argparse.ArgumentParser(description=__doc__)
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
        help="input video bitrate. Can be a single number "
        '(e.g. "100 kbps"), a list (e.g. "100kbps,200kbps") or a range '
        '(e.g. "100k-1M-100k") (start-stop-step)',
    )
    # other parameters
    parser.add_argument(
        "--device-workdir",
        type=str,
        dest="device_workdir",
        default=default_values["device_workdir"],
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
    if not options.replace:
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
                options.replace["input"].update(d)
            if "framerate" in options:
                options.replace["framerate"].update(d)
            if "resolution" in options:
                options.replace["resolution"].update(d)
            if "pix_fmt" in options:
                options.replace["pix_fmt"].update(d)
            if "extension" in options:
                options.replace["extension"].update(d)
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
    remove_encapp_gen_files(serial, options.device_workdir, options.debug)

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
