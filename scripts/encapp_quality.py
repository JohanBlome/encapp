#!/usr/bin/env python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
import csv
import json
import sys
import argparse
import re
import time
import pandas as pd

import encapp_tool.adb_cmds
import encapp_tool.ffutils
import encapp

PSNR_RE = "average:([0-9.]*)"
SSIM_RE = "SSIM Y:([0-9.]*)"
FFMPEG_SILENT = "ffmpeg -hide_banner -y "


def parse_quality_vmaf(vmaf_file):
    """Parse log/output files and return quality score"""
    vmaf = -1
    with open(vmaf_file) as input_file:
        data = json.load(input_file)
        input_file.close()
        vmaf = data["pooled_metrics"]["vmaf"]["mean"]
    return vmaf


def parse_quality_ssim(ssim_file):
    """Parse log/output files and return quality score"""
    ssim = -1
    with open(ssim_file) as input_file:
        line = " "
        while len(line) > 0:
            line = input_file.readline()
            match = re.search(SSIM_RE, line)
            if match:
                ssim = round(float(match.group(1)), 2)
                break
    return ssim


def parse_quality_psnr(psnr_file):
    """Parse log/output files and return quality score"""
    psnr = -1
    with open(psnr_file) as input_file:
        line = " "
        while len(line) > 0:
            line = input_file.readline()
            match = re.search(PSNR_RE, line)
            if match:
                psnr = round(float(match.group(1)), 2)
                break
    return psnr


"""
def get_source_length:
    #TODO: fix this
"""


def run_quality(test_file, options, debug):
    """Compare the output found in test_file with the source/reference
    found in options.media_path directory or overriden
    """
    # read test file results
    with open(test_file, "r") as input_file:
        results = json.load(input_file)

    if results.get("sourcefile") is None:
        print(f"ERROR, bad source, {test_file}")
        return
    # read device info results
    device_info_file = os.path.join(os.path.dirname(test_file), "device.json")
    if os.path.exists(device_info_file):
        with open(device_info_file, "r") as input_file:
            device_info = json.load(input_file)
    else:
        device_info = {}

    # find the reference source
    reference_dirname = options.media_path
    if reference_dirname is None:
        # get the reference dirname from the test path
        reference_dirname = os.path.dirname(test_file)

    reference_pathname = os.path.join(reference_dirname, results.get("sourcefile"))
    if options.override_reference is not None:
        reference_pathname = options.override_reference
    elif options.guess_original:
        # g_mm_lc_720@30_nv12.yuv_448x240@7.0_nv12.raw -> g_mm_lc_720@30_nv12.yuv
        # only for raw, obviously
        split = reference_pathname.split(".yuv_")
        split = split[0].rsplit("/")
        reference_pathname = reference_dirname  + split[-1] + ".yuv"
    print(f'ref = "{reference_pathname}"\n')
    # For raw we assume the source is the same resolution as the media
    # For surface transcoding look at decoder_media_format

    # Assume encoded file in same directory as test result json file
    directory, _ = os.path.split(test_file)
    encodedfile = results.get("encodedfile")
    print(f"Encoded file: {encodedfile}")

    if len(encodedfile) <= 0:
        print(f"No file: {encodedfile}")
        return
    if len(directory) > 0:
        encodedfile = f"{directory}/{encodedfile}"

    if (len(encodedfile) == 0) or (not os.path.exists(encodedfile)):
        print(f"ERROR! Encoded file name is missing, {encodedfile}")
        return
    vmaf_file = f"{encodedfile}.vmaf"
    ssim_file = f"{encodedfile}.ssim"
    psnr_file = f"{encodedfile}.psnr"

    video_info = encapp_tool.ffutils.get_video_info(encodedfile, debug)
    test = results.get("test")
    if (
        os.path.exists(vmaf_file)
        and os.path.exists(ssim_file)
        and os.path.exists(psnr_file)
        and not options.recalc
    ):
        print("All quality indicators already calculated for media, " f"{vmaf_file}")
    else:
        input_media_format = results.get("decoder_media_format")
        raw = True

        print(f"test def: {test}")
        pix_fmt = test["input"]["pixFmt"]
        if options.pix_fmt is not None:
            pix_fmt = options.pix_fmt

        if isinstance(input_media_format, str):
            # surface mode
            raw = False
        else:
            input_media_format = results.get("encoder_media_format")

        if len(pix_fmt) == 0:
            # See if source contains a clue
            pix_fmt = "yuv420p"
            if reference_pathname.find("yuv420p") > -1:
                pix_fmt = "yuv420p"
            if reference_pathname.find("yvu420p") > -1:
                pix_fmt = "yvu420p"
            elif reference_pathname.find("nv12") > -1:
                pix_fmt = "nv12"
            elif reference_pathname.find("nv21") > -1:
                pix_fmt = "nv21"

        output_media_format = results.get("encoder_media_format")
        output_width = -1
        output_height = -1
        output_framerate = -1
        if output_media_format is not None:
            output_width = output_media_format.get("width")
            output_height = output_media_format.get("height")
            output_framerate = output_media_format.get("frame-rate")
        else:
            resolution = test["configure"]["resolution"]
            if len(resolution) == 0:
                resolution = test["input"]["resolution"]
            res = encapp.parse_resolution(resolution)
            output_width = res[0]
            output_height = res[1]
            output_framerate = test["configure"]["framerate"]
            if output_framerate == 0:
                output_framerate = test["input"]["framerate"]
            print("WARNING! output media format is missing, guessing values...")
            print(f"outputres: {res}\noutput_framerate: {output_framerate}")

        output_resolution = f"{output_width}x{output_height}"
        media_res = f'{video_info["width"]}x{video_info["height"]}'
        # Although we assume that the distorted file is starting at the beginning
        # at least we limit the length to the duration of it.
        # TODO: shortest file wins :)
        duration = f'{video_info["duration"]}'
        if options.limit_length > 0:
            duration = float(options.limit_length)
        if output_framerate is None or output_framerate == 0:
            output_framerate = f'{video_info["framerate"]}'
        if output_resolution != media_res:
            print("Warning. Discrepancy in resolutions for output")
            print(f"Json {output_resolution}, media {media_res}")
            output_resolution = media_res

        if options.resolution is not None:
            input_resolution = options.resolution
        elif input_media_format is not None:
            try:
                input_width = int(input_media_format.get("width"))
                input_height = int(input_media_format.get("height"))
                # If we did not get aything here use the encoded size
            except BaseException:
                print("Warning. Input size is wrong.")
                print(
                    f'json {input_media_format.get("width")}x'
                    f'{input_media_format.get("height")}'
                )
                input_resolution = output_resolution
            else:
                input_resolution = f"{input_width}x{input_height}"
        else:
             input_resolution = output_resolution

        if options.framerate is not None:
            input_framerate = options.framerate
        elif (
            input_media_format is not None
            and input_media_format.get("framerate") is not None
        ):
            # get the input framerate from the MediaFormat
            input_framerate = float(input_media_format.get("framerate"))
        else:
            input_framerate = output_framerate
        if not os.path.exists(reference_pathname):
            print(f"Reference {reference_pathname} is unavailable")
            exit(-1)
        distorted = encodedfile
        if not os.path.exists(distorted):
            print(f"Distorted {distorted} is unavailable")
            exit(-1)

        if debug:
            print(
                f"** Run settings:\n {input_resolution}@{input_framerate} &  {output_resolution}@{output_framerate}"
            )
        shell_cmd = ""

        force_scale = ""
        if options.fr_fr:
            force_scale = ";[d1]scale=in_range=full:out_range=full[distorted]"
        if options.fr_lr:
            force_scale = ";[d1]scale=in_range=full:out_range=limited[distorted]"
        if options.lr_lr:
            force_scale = ";[d1]scale=in_range=limited:out_range=limited[distorted]"
        if options.lr_fr:
            force_scale = ";[d1]scale=in_range=limited:out_range=full[distorted]"

        if raw:
            ref_part = (
                f"-f rawvideo -pix_fmt {pix_fmt} -s {input_resolution} "
                f" -r {input_framerate} -i {reference_pathname} "
            )
        else:
            ref_part = f"-i {reference_pathname} "

        if debug > 0:
            print(f"input res = {input_resolution} vs {output_resolution}")

        dist_part = f"-i {encodedfile} "

        # This is jsut a naming scheme for the color scaling
        diststream = "distorted"
        if len(force_scale) > 0:
            diststream = "d1"

        input_width = input_resolution.split("x")[0]
        input_height = input_resolution.split("x")[1]
        # We can choose to convert to the input framerate
        # Or convert to the output. This nomrally gives higher score to lower fps and process faster...
        # There is not point in scaling first to a intermedient yuv raw file, just chain all operation
        filter_cmd = f"[0:v]framerate={output_framerate}[d0];[d0]scale={input_width}:{input_height}[{diststream}];[1:v]framerate={output_framerate}[ref]{force_scale};[distorted][ref]"

        # Do calculations
        if options.recalc or not os.path.exists(vmaf_file):

            # important: vmaf must be called with videos in the right order
            # <distorted_video> <reference_video>
            # https://jina-liu.medium.com/a-practical-guide-for-vmaf-481b4d420d9c
            shell_cmd = (
                f"{FFMPEG_SILENT} {dist_part} {ref_part}   -t {duration} "
                "-filter_complex "
                f'"{filter_cmd}libvmaf=log_path={vmaf_file}:'
                'n_threads=16:log_fmt=json" -f null - 2>&1 '
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"vmaf already calculated for media, {vmaf_file}")

        if options.recalc or not os.path.exists(ssim_file):
            shell_cmd = (
                f"ffmpeg {dist_part} {ref_part}  -t {duration} "
                "-filter_complex "
                f'"{filter_cmd}ssim=stats_file={ssim_file}.all" '
                f"-f null - 2>&1 | grep SSIM > {ssim_file}"
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"ssim already calculated for media, {ssim_file}")

        if options.recalc or not os.path.exists(psnr_file):
            shell_cmd = (
                f"ffmpeg {dist_part} {ref_part} -t {duration} "
                "-filter_complex "
                f'"{filter_cmd}psnr=stats_file={psnr_file}.all" '
                f"-f null - 2>&1 | grep PSNR > {psnr_file}"
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"psnr already calculated for media, {psnr_file}")

        if distorted != encodedfile:
            os.remove(distorted)

    if os.path.exists(vmaf_file):
        vmaf = parse_quality_vmaf(vmaf_file)
        ssim = parse_quality_ssim(ssim_file)
        psnr = parse_quality_psnr(psnr_file)

        # media,codec,gop,framerate,width,height,bitrate,meanbitrate,calculated_bitrate,
        # framecount,size,vmaf,ssim,psnr,testfile,reference_file
        file_size = os.stat(encodedfile).st_size
        model = device_info.get("props", {}).get("ro.product.model", "")
        if options.model:
            model = options.model
        platform = device_info.get("props", {}).get("ro.board.platform", "")
        serial = device_info.get("props", {}).get("ro.serialno", "")
        # get resolution and framerate
        resolution = test.get("configure").get("resolution")
        if not resolution:
            resolution = test.get("input").get("resolution")
        if not resolution:
            # get res from file
            resolution= f'{video_info["width"]}x{video_info["height"]}'
        framerate = test.get("configure").get("framerate")
        if not framerate:
            framerate = test.get("input").get("framerate")
        # derive the calculated_bitrate from the actual file size
        framecount = len(results.get("frames"))
        calculated_bitrate = int(
            (file_size * 8 * framerate) / framecount
        )
        source_complexity = ""
        source_motion = ""
        if options.mark_complexity:
            source_complexity = options.mark_complexity
        if options.mark_motion:
            source_motion = options.mark_motion

        frames = pd.DataFrame(results["frames"])
        iframes = frames.loc[frames["iframe"] == 1]
        pframes = frames.loc[frames["iframe"] == 0]

        data = (
            f"{encodedfile}",
            f"{model}",
            f"{platform}",
            f"{serial}",
            f'{test.get("configure").get("codec")}',
            f'{test.get("configure").get("iFrameInterval")}',
            f"{framerate}",
            f'{resolution.split("x")[0]}',
            f'{resolution.split("x")[1]}',
            f'{encapp.convert_to_bps(test.get("configure").get("bitrate"))}',
            f'{results.get("meanbitrate")}',
            f"{calculated_bitrate}",
            f'{results.get("framecount")}',
            f"{file_size}",
            f"{len(iframes)}",
            f"{len(pframes)}",
            f"{iframes['size'].mean()}",
            f"{pframes['size'].mean()}",
            f"{vmaf}",
            f"{ssim}",
            f"{psnr}",
            f"{test_file}",
            f'{test.get("input").get("filepath")}',
            source_complexity,
            source_motion,
        )
        return data
    return []


def get_options(argv):
    """Parse cli args"""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawTextHelpFormatter
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
        "--quiet",
        action="store_const",
        dest="debug",
        const=-1,
        help="Zero verbosity",
    )
    parser.add_argument("test", nargs="*", help="Test result in JSON format.")
    parser.add_argument("-o", "--output", help="csv output", default="quality.csv")
    parser.add_argument(
        "--media",
        dest="media_path",
        help="Directory where to locate the reference video file",
        default=None,
    )
    parser.add_argument(
        "--pix_fmt", help=f"pixel format ({encapp.PIX_FMT_TYPES.keys()})", default=None
    )
    parser.add_argument(
        "-ref",
        "--override_reference",
        help=(
            "Override reference, used when source is downsampled prior to " "encoding"
        ),
        default=None,
    )
    parser.add_argument(
        "-s",
        "--resolution",
        help="Override reference resolution WxH",
        default=None,
    )
    parser.add_argument(
        "-fps",
        "--framerate",
        help="Override reference framerate",
        default=None,
    )
    parser.add_argument(
        "-l",
        "--limit_length",
        help="Limit the verification length (both reference and source needs to be same length)",
        type=float,
        default=-1,
    )
    parser.add_argument(
        "--header", help="print header to output", action="store_true", default=False
    )
    parser.add_argument(
        "--fr_fr",
        help=("force full range to full range on distorted file"),
        action="store_true",
        default=False,
    )
    parser.add_argument(
        "--lr_fr",
        help="force lr range to full range on distorted file",
        action="store_true",
        default=False,
    )
    parser.add_argument(
        "--lr_lr",
        help=("force limited range to limited range on distorted file"),
        action="store_true",
        default=False,
    )
    parser.add_argument(
        "--fr_lr",
        help=("force full range to limited range on distorted file"),
        action="store_true",
        default=False,
    )
    parser.add_argument(
        "--guess_original",
        help=("Assume source is transcoded in encapp"),
        action="store_true",
        default=False,
    )
    parser.add_argument(
        "--recalc",
        help="recalculate regardless of status",
        action="store_true",
        default=False,
    )
    parser.add_argument(
        "--model",
        help="override the model from device.json",
        default=None,
    )
    parser.add_argument(
        "--mark_complexity",
        help="Set a complexity marker for the whole collection e.g. low, mid, high",
        default=None,
    )
    # TODO: remove
    parser.add_argument(
        "--mark_motion",
        help="Set a motion marker for the whole collection e.g. low, mid, high",
        default=None,
    )

    options = parser.parse_args()

    if len(argv) == 1:
        parser.print_help()
        sys.exit()

    if options.media_path is not None:
        # make sure media_path holds a valid directory
        assert os.path.isdir(
            options.media_path
        ), f"Error: {options.media_path} not a valid directory"
        assert os.access(
            options.media_path, os.R_OK
        ), f"Error: {options.media_path} not a readable directory"

    return options


def main(argv):
    """Calculate video quality properties (vmaf/ssim/psnr) and write
    a csv with relevant data
    """
    options = get_options(argv)

    FIELD_LIST = [
        "media",
        "model",
        "platform",
        "serial",
        "codec",
        "gop",
        "framerate",
        "width",
        "height",
        "bitrate",
        "meanbitrate",
        "calculated_bitrate",
        "framecount",
        "size",
        "iframes",
        "pframes",
        "iframe_size",
        "pframe_size",
        "vmaf",
        "ssim",
        "psnr",
        "testfile",
        "reference_file",
        "source_complexity",
        "source_motions",
    ]

    mode = "a"
    if options.header:
        print("WARNING! Write header implies clearing the file.")
        mode = "w"

    with open(options.output, mode) as fd:
        writer = csv.writer(fd)
        if options.header:
            writer.writerow(FIELD_LIST)

        current = 1
        total = len(options.test)
        start = time.time()
        print(f"Total number of test: {total}")
        for test in options.test:
            data = run_quality(test, options, options.debug)
            now = time.time()
            run_for = now - start
            time_per_test = float(run_for) / float(current)
            time_left = round(time_per_test * (total - current))
            time_left_m = int(time_left / 60)
            time_left_s = int(time_left) % 60
            print(
                f"Running {current}/{total}, Running for: {round(run_for)} sec, estimated time left {time_left_m}:{time_left_s:02} m:s"
            )
            current += 1
            if data is not None:
                writer.writerow(data)


if __name__ == "__main__":
    main(sys.argv)
