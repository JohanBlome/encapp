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

import encapp_tool.adb_cmds
import encapp_tool.ffutils
import encapp

PSNR_RE = "average:([0-9.]*)"
SSIM_RE = "SSIM Y:([0-9.]*)"
FFMPEG_SILENT = "ffmpeg -hide_banner -y "


def parse_quality(vmaf_file, ssim_file, psnr_file):
    """Read calculated log/output files and pick relevant vmaf/ssim/psnr
    data
    """
    # currently only vmaf
    vmaf = -1
    with open(vmaf_file) as input_file:
        data = json.load(input_file)
        input_file.close()
        vmaf = data["pooled_metrics"]["vmaf"]["mean"]

    ssim = -1
    with open(ssim_file) as input_file:
        line = " "
        while len(line) > 0:
            line = input_file.readline()
            match = re.search(SSIM_RE, line)
            if match:
                ssim = round(float(match.group(1)), 2)
                break

    psnr = -1
    with open(psnr_file) as input_file:
        line = " "
        while len(line) > 0:
            line = input_file.readline()
            match = re.search(PSNR_RE, line)
            if match:
                psnr = round(float(match.group(1)), 2)
                break

    return vmaf, ssim, psnr


def run_quality(test_file, override_settings, debug):
    """Compare the output found in test_file with the source/reference
    found in options.media_path directory or overriden
    """
    # read test file results
    with open(test_file, "r") as input_file:
        results = json.load(input_file)

    # read device info results
    device_info_file = os.path.join(os.path.dirname(test_file), "device.json")
    if os.path.exists(device_info_file):
        with open(device_info_file, "r") as input_file:
            device_info = json.load(input_file)
    else:
        device_info = {}

    # find the reference source
    reference_dirname = override_settings["media_path"]
    if reference_dirname is None:
        # get the reference dirname from the test path
        reference_dirname = os.path.dirname(test_file)
    reference_pathname = os.path.join(reference_dirname, results.get("sourcefile"))
    if override_settings["override_reference"] is not None:
        reference_pathname = override_settings["override_reference"]

    # For raw we assume the source is the same resolution as the media
    # For surface transcoding look at decoder_media_format

    # Assume encoded file in same directory as test result json file
    directory, _ = os.path.split(test_file)
    encodedfile = directory + "/" + results.get("encodedfile")

    vmaf_file = f"{encodedfile}.vmaf"
    ssim_file = f"{encodedfile}.ssim"
    psnr_file = f"{encodedfile}.psnr"

    test = results.get("test")
    if (
        os.path.exists(vmaf_file)
        and os.path.exists(ssim_file)
        and os.path.exists(psnr_file)
        and not override_settings["recalc"]
    ):
        print("All quality indicators already calculated for media, " f"{vmaf_file}")
    else:
        input_media_format = results.get("decoder_media_format")
        raw = True

        pix_fmt = test["input"]["pixFmt"]
        if override_settings["pix_fmt"] is not None:
            pix_fmt = override_settings["pix_fmt"]

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
        output_width = output_media_format.get("width")
        output_height = output_media_format.get("height")
        output_framerate = output_media_format.get("frame-rate")

        output_res = f"{output_width}x{output_height}"
        video_info = encapp_tool.ffutils.get_video_info(encodedfile, debug)
        media_res = f'{video_info["width"]}x{video_info["height"]}'

        if output_res != media_res:
            print("Warning. Discrepancy in resolutions for output")
            print(f"Json {output_res}, media {media_res}")
            output_res = media_res

        if override_settings["reference_resolution"] is not None:
            input_res = override_settings["reference_resolution"]
        else:
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
                input_res = output_res
            else:
                input_res = f"{input_width}x{input_height}"

        if not os.path.exists(reference_pathname):
            print(f"Reference {reference_pathname} is unavailable")
            exit(-1)
        distorted = encodedfile
        if not os.path.exists(distorted):
            print(f"Distorted {distorted} is unavailable")
            exit(-1)

        shell_cmd = ""

        force_scale = ""
        if override_settings["fr_fr"]:
            force_scale = "scale=in_range=full:out_range=full[o];[o]"
        if override_settings["fr_lr"]:
            force_scale = "scale=in_range=full:out_range=limited[o];[o]"
        if override_settings["lr_lr"]:
            force_scale = "scale=in_range=limited:out_range=limited[o];[o]"
        if override_settings["lr_fr"]:
            force_scale = "scale=in_range=limited:out_range=full[o];[o]"

        if input_res != output_res:
            distorted = f"{encodedfile}.yuv"

            # Scale
            shell_cmd = (
                f"{FFMPEG_SILENT} -i {encodedfile} -f rawvideo "
                f"-pix_fmt {pix_fmt} -s {input_res} {distorted}"
            )

            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        if raw:
            ref_part = (
                f"-f rawvideo -pix_fmt {pix_fmt} -s {input_res} "
                f"-r {output_framerate} -i {reference_pathname} "
            )
        else:
            ref_part = f"-r {output_framerate} -i {reference_pathname} "

        if debug > 0:
            print(f"input res = {input_res} vs {output_res}")
        if input_res != output_res:
            dist_part = (
                f"-f rawvideo -pix_fmt {pix_fmt} "
                f"-s {input_res} -r {output_framerate} -i {distorted}"
            )
        else:
            dist_part = f"-r {output_framerate} -i {distorted} "

        # Do calculations
        if override_settings["recalc"] or not os.path.exists(vmaf_file):

            # important: vmaf must be called with videos in the right order
            # <distorted_video> <reference_video>
            # https://jina-liu.medium.com/a-practical-guide-for-vmaf-481b4d420d9c
            shell_cmd = (
                f"{FFMPEG_SILENT} {dist_part} {ref_part} "
                "-filter_complex "
                f'"{force_scale}libvmaf=log_path={vmaf_file}:'
                'n_threads=16:log_fmt=json" -f null - 2>&1 '
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"vmaf already calculated for media, {vmaf_file}")

        if override_settings["recalc"] or not os.path.exists(ssim_file):
            shell_cmd = (
                f"ffmpeg {dist_part} {ref_part} "
                "-filter_complex "
                f'"{force_scale}ssim=stats_file={ssim_file}.all" '
                f"-f null - 2>&1 | grep SSIM > {ssim_file}"
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"ssim already calculated for media, {ssim_file}")

        if override_settings["recalc"] or not os.path.exists(psnr_file):
            shell_cmd = (
                f"ffmpeg {dist_part} {ref_part} "
                "-filter_complex "
                f'"{force_scale}psnr=stats_file={psnr_file}.all" '
                f"-f null - 2>&1 | grep PSNR > {psnr_file}"
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"psnr already calculated for media, {psnr_file}")

        if distorted != encodedfile:
            os.remove(distorted)

    if os.path.exists(vmaf_file):
        vmaf, ssim, psnr = parse_quality(vmaf_file, ssim_file, psnr_file)

        # media,codec,gop,framerate,width,height,bitrate,meanbitrate,calculated_bitrate,
        # framecount,size,vmaf,ssim,psnr,testfile,reference_file
        file_size = os.stat(encodedfile).st_size
        model = device_info.get("props", {}).get("ro.product.model", "")
        platform = device_info.get("props", {}).get("ro.board.platform", "")
        serial = device_info.get("props", {}).get("ro.serialno", "")
        # get resolution and framerate
        resolution = test.get("configure").get("resolution")
        if not resolution:
            resolution = test.get("input").get("resolution")
        framerate = test.get("configure").get("framerate")
        if not framerate:
            framerate = test.get("input").get("framerate")
        # derive the calculated_bitrate from the actual file size
        calculated_bitrate = int(
            (file_size * 8 * framerate) / results.get("framecount")
        )
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
            f"{vmaf}",
            f"{ssim}",
            f"{psnr}",
            f"{test_file}",
            f'{test.get("input").get("filepath")}',
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
        "-ref_res",
        "--reference_resolution",
        help="Override reference resolution WxH",
        default=None,
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
        "--recalc",
        help="recalculate regardless of status",
        action="store_true",
        default=False,
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
        "vmaf",
        "ssim",
        "psnr",
        "testfile",
        "reference_file",
    ]

    with open(options.output, "a") as fd:
        writer = csv.writer(fd)
        if options.header:
            writer.writerow(FIELD_LIST)
        override_settings = {
            "media_path": options.media_path,
            "override_reference": options.override_reference,
            "pix_fmt": options.pix_fmt,
            "reference_resolution": options.reference_resolution,
            "fr_fr": options.fr_fr,
            "fr_lr": options.fr_lr,
            "lr_lr": options.lr_lr,
            "lr_fr": options.lr_fr,
            "recalc": options.recalc,
        }
        for test in options.test:
            data_list = run_quality(test, override_settings, options.debug)
            if data_list is not None:
                writer.writerow(data_list)


if __name__ == "__main__":
    main(sys.argv)
