#!/usr/bin/env python3

import argparse
import sys
import requests
import pathlib
import os
import encapp_tool
import encapp_tool.adb_cmds as adb
import encapp_tool.ffutils as ffutils
import encapp
import encapp_verify
import encapp_quality
import google.protobuf.json_format
import hashlib
import pprint
from google.protobuf import text_format
import copy
from argparse_formatter import FlexiFormatter
import re
import glob
import pandas as pd
import itertools

SCRIPT_ROOT_DIR = os.path.abspath(
    os.path.join(encapp_tool.app_utils.SCRIPT_DIR, os.pardir)
)
SCRIPT_PROTO_DIR = os.path.abspath(
    os.path.join(encapp_tool.app_utils.SCRIPT_DIR, "proto")
)
sys.path.append(SCRIPT_ROOT_DIR)
sys.path.append(SCRIPT_PROTO_DIR)
import tests_pb2 as tests_definitions  # noqa: E402


md5sums = {}


def check_md5sum(sourcefile, mediastore):
    global md5sums

    if len(md5sums) == 0:
        # readfiles and populate
        # get all files in mediastore
        for root, _dirs, files in os.walk(mediastore):
            for file in files:
                file = f"{root}/{file}"
                if file.endswith(".md5"):
                    with open(file, "r") as f:
                        data = f.read()
                        for line in data.split("\n"):
                            if len(line) > 0:
                                md5, filename = line.split(" ")
                                md5sums[filename] = md5

    if sourcefile in md5sums:
        if (
            md5sums[sourcefile]
            == hashlib.md5(open(f"{mediastore}/{sourcefile}", "rb").read()).hexdigest()
        ):
            return True
        else:
            print(f"md5sum for {file} not found")
    else:
        print("WARNING! md5 is missing, skipping md5sum check")
        return True
    return False


def video_to_yuv(input_filepath, output_filepath, pix_fmt):
    # lazy but let us skip transcodig if the target is already there...
    if not os.path.exists(output_filepath):
        cmd = f"ffmpeg -y -loglevel error -hide_banner -i {input_filepath} -pix_fmt {pix_fmt} {output_filepath}"
        ret, stdout, stderr = adb.run_cmd(cmd, debug=0)
        if ret != 0:
            print(f"Error: {stderr}")
            # raise Exception(f"Error: {stderr}")
    else:
        print("Warning, transcoded file exists, assuming it is correct")


def verify_source_file(filepath, mediastore):
    name = os.path.basename(filepath)
    # check if file name exists in mediastore
    if not os.path.exists(f"{mediastore}/{name}"):
        # Download
        resp = run_query(filepath)
        with open(f"{mediastore}/{name}", "wb") as f:
            f.write(resp.content)
    else:
        print(f"File {mediastore}/{name} exists")

    # Check md5sum
    if not check_md5sum(f"{name}", mediastore):
        raise Exception(f"md5sum failed for {mediastore}/{name}")

    return


def clear_files(options):
    adb.remove_files_using_regex(
        options.serial, "encapp_.*", options.device_workdir, options.debug
    )
    adb.remove_files_using_regex(
        options.serial, ".*pbtxt$", options.device_workdir, options.debug
    )
    adb.remove_files_using_regex(
        options.serial, ".*[yuv|raw]$", options.device_workdir, options.debug
    )


def merge_options(options, test_path, video_path):
    # merge options with defaults
    encapp_options = encapp.get_options(["", "run", "test"])
    options_vars = vars(options)
    # Add func
    options_vars["func"] = "run"
    # Add test
    if options_vars["template"] is None:
        options_vars["configfile"] = [test_path]
    else:
        options_vars["configfile"] = []
        for template in options_vars["template"]:
            options_vars["configfile"].append(template)
        options_vars["configfile"].append(test_path)
    # Add media
    options_vars["videofile"] = video_path
    options_vars["fast_copy"] = True
    for key in vars(encapp_options):
        if key not in options_vars:
            options_vars[key] = getattr(encapp_options, key)
    options = argparse.Namespace(**options_vars)
    return options


def run_encapp(files, md5sums, options):
    # Download files if not present, in the future check md5sum
    debug = True
    local_workdir = f"{options.local_workdir}"
    os.makedirs(local_workdir, exist_ok=True)

    # media dir:
    os.makedirs(options.mediastore, exist_ok=True)

    # Get all md5sums and parse them
    for filepath in md5sums:
        destination = f"{options.mediastore}/{filepath.split('/')[-1]}"
        file = run_query(filepath, debug=debug)
        with open(destination, "wb") as f:
            f.write(file.content)

    failed_tests = []
    # TODO: setting
    for counter, filepath in enumerate(files):
        print(f"Running {counter + 1}/{len(files)}")
        if counter < options.skip:
            print(f"Skip file: {filepath}")
            continue
        if options.clear_files:
            clear_files(options)
        test_path = f"/tmp/_encapp.pbtxt"
        test = tests_definitions.Test()
        encapp.remove_encapp_gen_files(options.serial)

        # check in mediastore
        if filepath[:4] == "http":
            verify_source_file(filepath, options.mediastore)
            filepath = f"{options.mediastore}/{filepath.split('/')[-1]}"

        # convert to yuv unless done already
        videoname = os.path.basename(filepath)
        videoinfo = ffutils.get_video_info(f"{filepath}")
        framerate = int(round(float(videoinfo["framerate"]), 0))
        resolution = f"{videoinfo['width']}x{videoinfo['height']}"
        yuvfile = f"{videoname}.yuv"

        test.common.id = f"{options.label} - {videoname}"
        test.common.description = f"Encoding test {videoname} {resolution}@{framerate}"
        if options.codec is not None:
            test.configure.codec = options.codec
        if options.bitrate_mode is not None:
            test.configure.bitrate_mode = tests_definitions.Configure.BitrateMode.Value(
                options.bitrate_mode
            )
        test.input.filepath = filepath
        test.input.resolution = resolution
        test.input.framerate = framerate
        if options.pix_fmt is not None:
            test.input.pix_fmt = tests_definitions.PixFmt.Value(options.pix_fmt)
        if options.realtime:
            test.input.realtime = True

        if options.bitrate is None:
            if options.template is None:
                print("Test configuration is not complete, missing bitrate")
                exit(1)

            # Assume that the template contains everythng we need.
            testsuite = tests_definitions.TestSuite()
            test_to_run = copy.deepcopy(test)
            testsuite.test.append(test_to_run)

            with open(test_path, "w") as f:
                f.write(text_format.MessageToString(testsuite))

            options = merge_options(options, test_path, filepath)
            encapp.codec_test(options, None, options.serial, options.debug)

        else:
            # check bitrate ladder
            bitrates = encapp.parse_bitrate_field(options.bitrate)

            for bitrate in bitrates:
                testsuite = tests_definitions.TestSuite()
                test_to_run = copy.deepcopy(test)
                test_to_run.configure.bitrate = str(bitrate)

                testsuite.test.append(test_to_run)

                with open(test_path, "w") as f:
                    f.write(text_format.MessageToString(testsuite))

                options = merge_options(options, test_path, filepath)
                encapp.codec_test(options, None, options.serial, options.debug)
        if options.clear_files:
            clear_files(options)
    # Done with the encoding
    # Find all output json file (let us check the number as well).
    json_files = glob.glob(f"{local_workdir}/*.json")
    quality_options = {}
    quality_options["media_path"] = options.mediastore
    result = []
    df = None
    for file in json_files:
        quality_dict = encapp_quality.run_quality(
            file, quality_options, debug=options.debug
        )
        if quality_dict is None:
            # parsing has failed (None)
            continue
        if df is None:
            df = pd.DataFrame(columns=quality_dict.keys())
        df.loc[df.size] = quality_dict.values()

    if df is None:
        print(f"error no valid results")
        sys.exit(-1)
    if options.output_csv == "":
        options.output_csv = f"{local_workdir}/encapp_quality.csv"
    df.to_csv(options.output_csv, index=False)


def find_links(text, path):
    y4mreg = r'href="(?P<y4m>[\w_\-\/.]*.y4m)"'
    md5sumreg = r'href="(?P<md5sum>[\w_\-\/.]*[.y4m]?.md5)"'
    folderreg = r'href="(?P<folder>[\w_\-\/.]*)\/"'

    files = []
    folders = []
    md5sums = []
    iterator = re.finditer(y4mreg, text)
    for match in iterator:
        files.append(match.group("y4m"))

    iterator = re.findall(folderreg, text)
    for match in iterator:
        folder = None
        if isinstance(match, str):
            folder = match
        else:
            folder = match.group("folder")

        if folder:
            if folder in path:
                continue
            folders.append(folder)
    iterator = re.finditer(md5sumreg, text)
    for match in iterator:
        md5sums.append(match.group("md5sum"))

    return files, folders, md5sums


def is_file(filename):
    if pathlib.Path(filename).suffix == "":
        return False
    return True


def run_query(path, debug=0):
    try:
        resp = requests.get(path)
        if resp.status_code == 200:
            return resp
        else:
            print(f"error {resp.status_code}")

    except requests.exceptions.HTTPError as errh:
        print(errh)
    except requests.exceptions.ConnectionError as errc:
        print(errc)
    except requests.exceptions.Timeout as errt:
        print(errt)
    except requests.exceptions.RequestException as err:
        print(err)
    raise Exception("Error in run_query")


def list_path(path, files, folders, md5sums, debug=0):
    result = run_query(path, debug=debug)
    _files, _folders, _md5sums = find_links(result.text, path)

    if result:
        for folder in _folders:
            folders.append(folder)
            list_path(path + "/" + folder, files, folders, md5sums, debug=debug)
        for file in _files:
            files.append(path + "/" + file)
        for md5sum in _md5sums:
            md5sums.append(path + "/" + md5sum)


def get_file_fps(file):
    reg = "_(?P<fps>[0-9]*)fps"
    match = re.search(reg, file)
    if match:
        value = float(match.group("fps"))
        if value > 100:
            value = value / 100
        return int(round(value, 0))

    return -1


def filter_files_on_name(files, options):
    # filter resolution. Assume file haz WxH in name
    # For now only accept case 1 and 2
    filter_on_name = options.get("filter_on_name", False)

    outfiles = files

    if options.resolution:
        filt = []
        if isinstance(options.resolution, str):
            options.resolution = [options.resolution]
        for resolution in options.resolution:
            _files = [f for f in outfiles if resolution.strip() in f]
            filt = filt + _files

        if len(outfiles) == 0:
            raise Exception("No files found with the given resolution")
        outfiles = filt

    # filter framerate. Assume file has Xfps in name, round to nearest integer
    # the fractional part does not matter in this context, framerate conversions
    # can done implicitly and causing strange results)
    if options.framerate:
        filt = []
        if (
            isinstance(options.framerate, str)
            or isinstance(options.framerate, int)
            or isinstance(options.framerate, float)
        ):
            options.framerate = [options.framerate]
        for framerate in options.framerate:
            framerate = int(round(float(framerate), 0))
            _files = [f for f in outfiles if get_file_fps(f) == framerate]
            filt = filt + _files
        outfiles = filt
        if len(outfiles) == 0:
            raise Exception("No files found with the given framerate")

    return outfiles


def filter_files(files, options):
    if options.resolution is None and options.framerate is None:
        return files

    for folder in options.corpus_dir:
        if folder[:4] == "http":
            return filter_files_on_name(files, options)

    outfiles = []
    if isinstance(options.resolution, str):
        options.resolution = [options.resolution]

    if (
        isinstance(options.framerate, str)
        or isinstance(options.framerate, int)
        or isinstance(options.framerate, float)
    ):
        options.framerate = [options.framerate]

    fileinfo = [[path, ffutils.get_video_info(path)] for path in files]
    if options.resolution is None:
        options.resolution = []
    if options.framerate is None:
        options.framerate = []
    for resolution, framerate in itertools.product(
        options.resolution, options.framerate
    ):
        for file, info in fileinfo:
            vid_res = f"{int(info['width'])}x{int(info['height'])}"
            vid_fps = int(round(float(info["framerate"]), 0))

            if resolution is None:
                resolution = vid_res
            if framerate is None:
                framerate = vid_fps
            if (
                resolution.strip() == vid_res
                and int(round(float(framerate), 0)) == vid_fps
            ):
                outfiles.append(file)

    if len(outfiles) == 0:
        raise Exception("No files found with the given settings")

    return outfiles


def find_media(options):
    files = []
    folders = []
    md5sums = []
    for directory in options.corpus_dir.split(","):
        if os.path.isfile(directory):
            files.append(directory)
        elif directory[:4] == "http":
            list_path(directory, files, folders, md5sums, debug=1)
        else:
            folder_files = glob.glob(f"{directory}/*.y4m")
            files = files + folder_files
            # No md5sums
    return files, md5sums


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
        "--corpus-dir",
        dest="corpus_dir",
        default="",
        help="Comma separated list of URLs to parse",
    )
    parser.add_argument(
        "-c",
        "--codec",
        type=str,
        dest="codec",
        default=None,
        metavar="encoder",
        help="override encoder in config",
    )
    parser.add_argument(
        "-r",
        "--bitrate",
        default=None,
        type=str,
        dest="bitrate",
        metavar="input-video-bitrate",
        help="""input video bitrate. Can be
        1. a single number (e.g. "100 kbps")
        2. a list (e.g. "100kbps,200kbps")
        3. a range (e.g. "100k-1M-100k") (start-stop-step)""",
    )
    parser.add_argument(
        "--bitrate-mode",
        type=str,
        dest="bitrate_mode",
        default=None,
        metavar="bitrate_mode",
        help="override bitrate mode",
    )
    parser.add_argument(
        "-fps",
        "--framerate",
        type=str,
        dest="framerate",
        default=None,
        metavar="input-video-framerate",
        help="""
        Note: to simplify quality calculations the framerate will be an integer i.e 29.97 will be 30""",
    )
    parser.add_argument(
        "-s",
        "--size",
        type=str,
        default=None,
        dest="resolution",
        metavar="input-video-resolution",
        help="""input video resolution. Can be
        1. a single size (e.g. "1280x720")
        2. a list (e.g. "320x240,1280x720")
        3. a range (e.g. "320x240-4000x4000-2") (start-stop-step)
        In the case of the step it is a multiplication of the first.
        It will stop when the pixel count pass stop value (calculated as wxh)""",
    )
    parser.add_argument(
        "--pix_fmt",
        type=str,
        dest="pix_fmt",
        default=None,
        metavar="pix_fmt",
        help="wanted pix fmt for the encoder",
    )
    parser.add_argument(
        "--serial",
        default=None,
        help="Serial number of the device",
    )
    parser.add_argument(
        "--mediastore",
        type=str,
        nargs="?",
        default="media",
        metavar="mediastore",
        help="store all input and generated file in one folder",
    )
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
        default="",
        metavar="local_workdir",
        help="work (storage) directory on local host",
    )
    parser.add_argument(
        "-o",
        "--output-csv",
        type=str,
        dest="output_csv",
        default="",
        metavar="output_csv",
        help="output csv file with quality metrics",
    )
    parser.add_argument(
        "-d",
        "--debug",
        action="count",
        dest="debug",
        default=0,
        help="Increase verbosity (use multiple times for more)",
    )
    parser.add_argument(
        "--clear_files",
        action="store_true",
        help="For memory challenged devices clear files before running.",
    )
    parser.add_argument(
        "--realtime",
        action="store_true",
        help="Run in realtime and not as fast as possible.",
    )
    parser.add_argument(
        "--skip",
        type=int,
        default=0,
        help="Skip the first N files. If a file crashes the device, it can be skipped",
    )
    parser.add_argument(
        "--width_align",
        type=int,
        default=-1,
        help="Horizontal widht alignment in bits to calculate stride and add padding if converting to raw yuv",
    )
    parser.add_argument(
        "--height_align",
        type=int,
        default=-1,
        help="Vertical height alignment in bits to calculate and add padding if converting to raw yuv",
    )
    parser.add_argument(
        "--dim_align",
        type=int,
        default=None,
        help="Horizontal and vertical alignment in bits to calculate stride and add padding if converting to raw yuv",
    )
    parser.add_argument(
        "--template",
        type=str,
        nargs="*",
        default=None,
        help="Test definition template, will be merged with the generated test definition",
    )
    parser.add_argument(
        "-l",
        "--label",
        type=str,
        default="encapp corpus test",
        help="Label for the test cases",
    )
    parser.add_argument(
        "--idb",
        action="store_true",
        dest="idb",
        default=False,
        help="Run on ios using idb",
    )
    parser.add_argument(
        "--bundleid",
        type=str,
        default=None,
        help="Implicitly it will turn on IDB option",
    )

    return parser.parse_args(argv[1:])


def main(argv):

    options = get_options(argv)
    encapp.process_target_options(options)

    if options.local_workdir == "":
        options.local_workdir = f"quality.{options.corpus_dir.replace(',','-')}"

    # get model and serial number
    model, options.serial = adb.get_device_info(options.serial, options.debug)
    model = ""
    print(f"Run on {model} with serial {options.serial}")
    if options.serial is None or len(options.serial) == 0:
        print("No device connected")
        return 1

    files, md5sums = find_media(options)
    files = filter_files(files, options)

    # run encapp on each file
    run_encapp(files, md5sums, options)


if __name__ == "__main__":
    main(sys.argv)
