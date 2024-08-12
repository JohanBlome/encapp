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
import numpy as np
import encapp_tool.adb_cmds
import encapp_tool.ffutils
import encapp
import vmaf_json2csv as vmafcsv

PSNR_RE = re.compile(
    r"y:(?P<psnr_y>[0-9.]*) u:(?P<psnr_u>[0-9.]*) v:(?P<psnr_v>[0-9.]*) average:(?P<psnr_avg>[0-9.]*)"
)
SSIM_RE = "SSIM Y:([0-9.]*)"
FFMPEG_SILENT = "ffmpeg -hide_banner -y "

VMAF_PERCENTILE_LIST = (5, 10, 25, 75, 90, 95)
if sys.platform == "linux" or sys.platform == "linux2":
    # linux
    VMAF_MODEL_DIR = "/usr/share/model"
elif sys.platform == "darwin":
    # OS X
    VMAF_MODEL_DIR = "/opt/homebrew/Cellar/libvmaf/3.0.0/share/libvmaf/model"
elif sys.platform == "win32":
    # Windows
    VMAF_MODEL_DIR = "/usr/share/model"

VMAF_MODEL = f"{VMAF_MODEL_DIR}/vmaf_4k_v0.6.1.json"
VMAF_MODEL = f"{VMAF_MODEL_DIR}/vmaf_v0.6.1.json"
VMAF_MODEL = f"{VMAF_MODEL_DIR}/vmaf_v0.6.1neg.json"


def calc_stats(pdata, options, label, print_text=False):
    if pdata.empty:
        print("Failed to read data")
        return None
    last_frame_index = np.argmax(pdata["pts"])
    total_duration = (
        pdata.iloc[last_frame_index]["pts"] + pdata.iloc[last_frame_index]["duration"]
    )
    total_size = np.sum(pdata["size"])
    frame_count = len(pdata)
    # Need to check all individual clips and calculate
    files = pd.unique(pdata["file"])
    fps = 0
    for fl in files:
        fdata = pdata.loc[pdata["file"] == fl]
        findex = np.argmax(fdata["pts"])
        fduration = fdata.iloc[findex]["pts"] + fdata.iloc[findex]["duration"]
        fps += len(fdata) / fduration

    fps = fps / len(files)
    iframes = pdata.loc[pdata["iframe"] == 1]
    iframe_cnt = len(iframes)
    pframes = pdata.loc[pdata["iframe"] != 1]
    pframe_cnt = len(pframes)
    iframes = iframes.fillna(0)
    pframes = pframes.fillna(0)

    imean = 0
    pmean = 0
    imax = imin = pmax = pmin = 0
    if len(iframes["size"]) > 0:
        imean = np.mean(iframes["size"])
        imax = np.max(iframes["size"])
        imin = np.min(iframes["size"])
    if len(pframes["size"]) > 0:
        pmean = np.mean(pframes["size"])
        pmax = np.max(pframes["size"])
        pmin = np.min(pframes["size"])
    mean_ratio = imean / pmean
    size_ratio = np.sum(iframes["size"]) / np.sum(pframes["size"])

    if options.get("quiet", None) is None:
        print(f"ime,imx,imi = {imean}, {imax}, {imin}")
        print(f"{len(pframes)}")
        print("*** {:s} stats ***".format(label))
        print("Frame count: {:f}".format(len(pdata)))
        print("Duration: {:.3f} secs".format(total_duration))
        print("Fps: {:.2f} fps".format(fps))
        print("Bitrate {:.2f} kbps".format(8 * total_size / (total_duration * 1000)))
        print(
            "Iframes (mean, max, min): {:d}, {:d}, {:d} bytes".format(
                int(imean), int(imax), int(imin)
            )
        )
        print(
            "Pframes (mean, max, min): {:d}, {:d}, {:d} bytes".format(
                int(pmean), int(pmax), int(pmin)
            )
        )
        print("Iframe count: {:d}".format(iframe_cnt))
        print("Pframe count: {:d}".format(pframe_cnt))
        print("mean size iframe/pframe ratio: {:.2f}".format(mean_ratio))
        print("total size iframe/pframe ratio: {:.2f}".format(size_ratio))
        print("___")

    """
    elif options.csv and print_text:
        if options.header:
            print(
                "file,frames,duration,fps,ipratio,imean,imax,imin,pmean,pmax,pmin"
            )
        print(
            "{:s},{:d},{:.3f},{:.2f},{:.2f},{:d},{:d},{:d},{:d},{:d},{:d}".format(
                label,
                frame_count,
                total_duration,
                fps,
                mean_ratio,
                int(imean),
                int(imax),
                int(imin),
                int(pmean),
                int(pmax),
                int(pmin),
            )
        )
    """
    return frame_count, total_duration, fps


def detailed_media_info(inputfile, options, debug):
    # read file
    df = pd.DataFrame()
    data = []

    name = inputfile + ".frames.csv"
    if not os.path.exists(name):
        """
        media_type=video
        stream_index=0
        key_frame=0
        pts=2884
        pts_time=0.032044
        pkt_dts=2884
        pkt_dts_time=0.032044
        best_effort_timestamp=2884
        best_effort_timestamp_time=0.032044
        pkt_duration=6115
        pkt_duration_time=0.067944
        pkt_pos=24546
        pkt_size=714
        width=640
        height=360
        pix_fmt=yuv420p
        sample_aspect_ratio=1:1
        pict_type=P
        coded_picture_number=1
        display_picture_number=0
        interlaced_frame=0
        top_field_first=0
        repeat_pict=0
        color_range=tv
        color_space=gbr
        color_primaries=reserved
        color_transfer=reserved
        chroma_location=left
        """
        # write the CSV header
        shell_cmd = f"echo 'key_frame,pts_time,duration_time,pkt_size' > {name}"
        encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        # run the ffprobe command
        shell_cmd = (
            "ffprobe -select_streams v -show_frames -show_entries frame=pts_time,"
            "duration_time,pkt_size,key_frame -v quiet -of csv='p=0' "
            f"{inputfile} >> {name}"
        )
        encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        # process data
        alt_dur = 1.0 / 29.97
        counter = 0
        time_error = False
        fps = -1
        override_dur = -1

        first_pts = -1
        pts = 0
        recalc_duration = False
        with open(name, "r") as fd:
            datareader = csv.reader(fd, delimiter=",")
            for row in datareader:
                try:
                    is_iframe = int(row[0])
                    try:
                        dur = float(row[2])
                    except Exception as ex:
                        print(f"{ex}")
                        recalc_duration = True
                        dur = -1

                    if fps < 0 and dur == 0:
                        dur = override_dur
                        time_error = True
                    else:
                        if not time_error:
                            try:
                                if first_pts == -1:
                                    pts = 0
                                    first_pts = float(row[1])
                                else:
                                    pts = float(row[1]) - first_pts
                            except Exception as ex:
                                print(f"{ex}")
                                time_error = True

                    frame_size = int(row[3])
                    if time_error:
                        # Assume 29.97 fps
                        data.append(
                            [
                                inputfile,
                                is_iframe,
                                counter * alt_dur,
                                alt_dur,
                                frame_size,
                                8 * frame_size / (alt_dur * 1000),
                            ]
                        )
                    else:
                        data.append(
                            [
                                inputfile,
                                is_iframe,
                                pts,
                                dur,
                                frame_size,
                                8 * frame_size / (dur * 1000),
                            ]
                        )

                except Exception as e:
                    print(str(e) + ", row = " + str(row))
                counter += 1

        labels = ["file", "iframe", "pts", "duration", "size", "kbps"]
        df = pd.DataFrame.from_records(data, columns=labels, coerce_float=True)
        # overwrite with derived data
        df.to_csv(name)
    else:
        df = pd.read_csv(name)
    calc_stats(df, options, inputfile, True)
    return df


def parse_quality_vmaf(vmaf_file):
    """Parse log/output files and return quality score"""
    with open(vmaf_file) as fd:
        data = json.load(fd)
    vmaf_dict = {
        "mean": data["pooled_metrics"]["vmaf"]["mean"],
        "harmonic_mean": data["pooled_metrics"]["vmaf"]["harmonic_mean"],
        "min": data["pooled_metrics"]["vmaf"]["min"],
        "max": data["pooled_metrics"]["vmaf"]["max"],
    }
    # get per-frame VMAF values
    vmaf_list = np.array(
        list(data["frames"][i]["metrics"]["vmaf"] for i in range(len(data["frames"])))
    )
    # add some percentiles
    vmaf_dict.update(
        {
            f"p{percentile}": np.percentile(vmaf_list, percentile)
            for percentile in VMAF_PERCENTILE_LIST
        }
    )
    return vmaf_dict


def parse_quality_ssim(ssim_file):
    """Parse log/output files and return quality score"""
    ssim = -1
    with open(ssim_file) as fd:
        line = " "
        while len(line) > 0:
            line = fd.readline()
            match = re.search(SSIM_RE, line)
            if match:
                ssim = round(float(match.group(1)), 2)
                break
    return ssim


def parse_quality_psnr(psnr_file):
    """Parse log/output files and return quality score"""
    psnr = -1
    psnr_y = -1
    psnr_u = -1
    psnr_v = -1

    with open(psnr_file) as fd:
        line = " "
        while len(line) > 0:
            line = fd.readline()
            match = re.search(PSNR_RE, line)
            if match:
                psnr = round(float(match.group("psnr_avg")), 2)
                psnr_y = round(float(match.group("psnr_y")), 2)
                psnr_u = round(float(match.group("psnr_u")), 2)
                psnr_v = round(float(match.group("psnr_v")), 2)
                break
    return psnr, psnr_y, psnr_u, psnr_v


"""
def get_source_length:
    #TODO: fix this
"""


def run_quality(test_file, options, debug):
    """Compare the output found in test_file with the source/reference
    found in options.media_path directory or overriden
    """
    print(f"Run quality, {test_file}")
    if not os.path.exists(test_file):
        print("File not found: " + test_file)
        return None

    if debug > 0:
        print(options)
    duration = 0
    results = {}
    if test_file[-4:] == "json":
        # read test file results
        with open(test_file, "r") as fd:
            results = json.load(fd)

        if results.get("sourcefile") is None:
            print(f"ERROR, bad source, {test_file}")
            return None

    # read device info results
    device_info_file = os.path.join(os.path.dirname(test_file), "device.json")
    if os.path.exists(device_info_file):
        with open(device_info_file, "r") as fd:
            device_info = json.load(fd)
    else:
        device_info = {}

    reference_pathname = ""
    reference_info = None
    reference_dirname = options.get("media_path", None)
    if options.get("guess_original", None):
        # g_mm_lc_720@30_nv12.yuv_448x240@7.0_nv12.raw -> g_mm_lc_720@30_nv12.yuv
        # only for raw, obviously
        split = reference_dirname.split(".yuv_")
        split = split[0].rsplit("/")
        reference_pathname = reference_dirname + split[-1] + ".yuv"
    elif options.get("override_reference", None) == None:
        # find the reference source
        if reference_dirname is None:
            # get the reference dirname from the test path
            reference_dirname = os.path.dirname(test_file)
        reference_pathname = os.path.join(reference_dirname, results.get("sourcefile"))
    else:
        reference_pathname = options["override_reference"]
    # For raw we assume the source is the same resolution as the media
    # For surface transcoding look at decoder_media_format

    # Assume encoded file in same directory as test result json file
    directory, _ = os.path.split(test_file)
    if len(results) > 0:
        encodedfile = results.get("encodedfile")
    else:
        encodedfile = test_file

    if len(encodedfile) <= 0:
        print(f"No file: {encodedfile}")
        return None
    if len(directory) > 0:
        encodedfile = f"{directory}/{encodedfile}"

    if (len(encodedfile) == 0) or (not os.path.exists(encodedfile)):
        print(f"ERROR! Encoded file name is missing, {encodedfile}")
        return None
    vmaf_file = f"{encodedfile}.vmaf.json"
    ssim_file = f"{encodedfile}.ssim"
    psnr_file = f"{encodedfile}.psnr"

    video_info = None
    try:
        video_info = encapp_tool.ffutils.get_video_info(encodedfile, debug)
    except:
        print("Failed to parse with ffprobe")
        video_info = None

    recalc = options.get("recalc", None)
    test = results.get("test")
    if (
        os.path.exists(vmaf_file)
        and os.path.exists(ssim_file)
        and os.path.exists(psnr_file)
        and not recalc
    ):
        print("All quality indicators already calculated for media, " f"{vmaf_file}")
    else:
        input_media_format = results.get("decoder_media_format")
        raw = encapp_tool.ffutils.video_is_raw(reference_pathname)

        reference_info = None
        if raw:
            if options.get("pix_fmt", None):
                pix_fmt = options.pix_fmt
            else:
                pix_fmt = test["input"]["pixFmt"]
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

        else:
            reference_info = encapp_tool.ffutils.get_video_info(reference_pathname)

        output_media_format = results.get("encoder_media_format")
        output_width = -1
        output_height = -1
        output_framerate = -1
        if output_media_format is not None:
            output_width = output_media_format.get("width")
            output_height = output_media_format.get("height")
            output_framerate = output_media_format.get("frame-rate")
        else:
            if test != None:
                resolution = test["configure"]["resolution"]
                if len(resolution) == 0:
                    resolution = test["input"]["resolution"]

                res = encapp.parse_resolution(resolution)
                output_width = res[0]
                output_height = res[1]

            else:
                output_width = video_info["width"]
                output_height = video_info["height"]

            if test != None:
                output_framerate = test["configure"]["framerate"]
                if output_framerate == 0:
                    output_framerate = test["input"]["framerate"]
            else:
                output_framerate = video_info["framerate"]
            print("WARNING! output media format is missing, guessing values...")
            print(
                f"outputres: {output_width}x{output_height}\noutput_framerate: {output_framerate}"
            )

        output_resolution = f"{output_width}x{output_height}"
        if video_info is not None:
            media_res = f'{video_info["width"]}x{video_info["height"]}'
            # Although we assume that the distorted file is starting at the beginning
            # at least we limit the length to the duration of it.
            # TODO: shortest file wins :)
            duration = f'{video_info["duration"]}'
        else:
            media_res = None
        if options.get("limit_length", -1) > 0:
            duration = float(options.limit_length)
        if output_framerate is None or output_framerate == 0:
            output_framerate = f'{video_info["framerate"]}'
        if media_res != None and output_resolution != media_res:
            print("Warning. Discrepancy in resolutions for output")
            print(f"Json {output_resolution}, media {media_res}")
            output_resolution = media_res

        if options.get("resolution", None):
            input_resolution = options.resolution
        if input_media_format is not None:
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
        elif reference_info:
            input_resolution = f"{reference_info['width']}x{reference_info['height']}"
        else:
            input_resolution = output_resolution

        if options.get("framerate", None):
            input_framerate = options.framerate
        elif reference_info:
            input_framerate = reference_info["framerate"]
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
            # check raw instead of yuv
            if reference_pathname[-3:] == "yuv":
                reference_pathname = reference_pathname[:-3] + "raw"
            if not os.path.exists(reference_pathname):
                print(f"Reference {reference_pathname} is unavailable")
                return None
        distorted = encodedfile
        if not os.path.exists(distorted):
            print(f"Distorted {distorted} is unavailable")
            return None

        if debug:
            print(
                f"** Run settings:\n {input_resolution}@{input_framerate} &  {output_resolution}@{output_framerate}"
            )
        shell_cmd = ""

        force_scale = ""
        if options.get("fr_fr", None):
            force_scale = ";[d1]scale=in_range=full:out_range=full[distorted]"
        if options.get("fr_lr", None):
            force_scale = ";[d1]scale=in_range=full:out_range=limited[distorted]"
        if options.get("lr_lr", None):
            force_scale = ";[d1]scale=in_range=limited:out_range=limited[distorted]"
        if options.get("lr_fr", None):
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
        crop = ""
        if options.get("crop_input", None):
            crop = f"crop={options.get('crop_input')},"
        filter_cmd = f"[0:v]fps={input_framerate}[d0];[d0]{crop}scale={input_width}:{input_height}[{diststream}];[{diststream}][1:v]"
        print(f"\nFPS -> {input_framerate}")
        # Do calculations
        if recalc or not os.path.exists(vmaf_file):

            # important: vmaf must be called with videos in the right order
            # <distorted_video> <reference_video>
            # https://jina-liu.medium.com/a-practical-guide-for-vmaf-481b4d420d9c
            shell_cmd = (
                f"{FFMPEG_SILENT} {dist_part} {ref_part} -t {duration} "
                "-filter_complex "
                f'"{filter_cmd}libvmaf=log_path={vmaf_file}:'
                "n_threads=16:log_fmt=json"
            )
            # Allow for an environment variable
            if os.environ.get("VMAF_MODEL_PATH", None):
                print("Environment VMAF_PATH override model")
                VMAF_MODEL = os.environ.get("VMAF_MODEL_PATH")
            if os.path.isfile(VMAF_MODEL):
                shell_cmd += f":model=path={VMAF_MODEL}"
            else:
                print(f"warn: cannot find VMAF model {VMAF_MODEL}. Using default model")
            shell_cmd += '" -f null - 2>&1'
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"vmaf already calculated for media, {vmaf_file}")

        if recalc or not os.path.exists(ssim_file):
            shell_cmd = (
                f"ffmpeg {dist_part} {ref_part} -t {duration} "
                "-filter_complex "
                f'"{filter_cmd}ssim=stats_file={ssim_file}.all" '
                f"-f null - 2>&1 | grep SSIM > {ssim_file}"
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        else:
            print(f"ssim already calculated for media, {ssim_file}")

        if recalc or not os.path.exists(psnr_file):
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
        vmaf_dict = parse_quality_vmaf(vmaf_file)
        ssim = parse_quality_ssim(ssim_file)
        psnr, psnr_y, psnr_u, psnr_v = parse_quality_psnr(psnr_file)

        if options.get("csv", None):
            base, extension = os.path.splitext(vmaf_file)
            vmafcsv.process_infile(vmaf_file, f"{base}.csv", debug)
        # media,codec,gop,framerate,width,height,bitrate,meanbitrate,calculated_bitrate,
        # framecount,size,vmaf,ssim,psnr,testfile,reference_file
        file_size = os.stat(encodedfile).st_size
        model = device_info.get("props", {}).get("ro.product.model", "")
        if options.get("model", None):
            model = options.model
        platform = device_info.get("props", {}).get("ro.board.platform", "")
        serial = device_info.get("props", {}).get("ro.serialno", "")
        bitrate = 0
        codec = ""
        iframeinterval = 0
        framecount = 0
        meanbitrate = 0
        filepath = ""
        iframes_size = 0
        pframes_size = 0
        description = ""
        bitratemode = ""
        quality = -1  # CQ is between 0-100
        id = ""
        if test is not None:
            # get resolution and framerate
            resolution = test.get("configure").get("resolution")
            if not resolution:
                resolution = test.get("input").get("resolution")
            if not resolution:
                # get res from file
                resolution = f'{video_info["width"]}x{video_info["height"]}'
            framerate = test.get("configure").get("framerate")
            if not framerate:
                framerate = test.get("input").get("framerate")
            # derive the calculated_bitrate from the actual file size
            framecount = len(results.get("frames"))
            codec = test.get("configure").get("codec")
            bitrate = test.get("configure").get("bitrate")
            meanbitrate = results.get("meanbitrate")
            iframeinterval = test.get("configure").get("iFrameInterval")
            description = test.get("common").get("description")
            id = test.get("common").get("id")
            filepath = test.get("input").get("filepath")
            bitratemode = test.get("configure").get("bitrateMode")
            if bitratemode == "cq":
                # look for a quality parameter
                parameters = test.get("configure").get("parameter")
                for par in parameters:
                    if par.get("key") == "quality":
                        quality = int(par.get("value"))
                        continue
        else:
            framerate = video_info["framerate"]
            resolution = f'{video_info["width"]}x{video_info["height"]}'
            codec = video_info["codec-name"]

        if reference_info:
            filepath = reference_pathname
        # get the data from ffmpeg
        ffmpeg_data = detailed_media_info(encodedfile, options, debug)
        framecount = len(ffmpeg_data)
        iframes = ffmpeg_data.loc[ffmpeg_data["iframe"] == 1]
        pframes = ffmpeg_data.loc[ffmpeg_data["iframe"] == 0]
        iframeinterval = video_info["duration"] / len(iframes)

        if len(iframes) > 0:
            iframes_size = iframes["size"].mean()
        pframes_size = pframes["size"].mean()
        calculated_bitrate = int((file_size * 8 * framerate) / framecount)
        source_complexity = ""
        source_motion = ""
        if options.get("mark_complexity", None):
            source_complexity = options.mark_complexity
        if options.get("mark_motion", None):
            source_motion = options.mark_motion

        if len(results) > 0:
            frames = pd.DataFrame(results["frames"])
            iframes = frames.loc[frames["iframe"] == 1]
            pframes = frames.loc[frames["iframe"] == 0]
        else:
            meanbitrate = bitrate = calculated_bitrate
        # calculate the bits/pixel from the meanbitrate
        width, height = [int(x) for x in resolution.split("x")]
        mean_bpp = (1.0 * meanbitrate) / (framerate * width * height)
        quality_dict = {
            "media": f"{encodedfile}",
            "description": f"{description}",
            "id": f"{id}",
            "model": f"{model}",
            "platform": f"{platform}",
            "serial": f"{serial}",
            "codec": f"{codec}",
            "bitrate_mode": f"{bitratemode}",
            "quality": f"{quality}",  # cq setting
            "gop_sec": f"{iframeinterval}",
            "framerate_fps": f"{framerate}",
            "width": f"{width}",
            "height": f"{height}",
            "bitrate_bps": f"{encapp.convert_to_bps(bitrate)}",
            "meanbitrate_bps": f"{meanbitrate}",
            "mean_bpp": f"{mean_bpp}",
            "calculated_bitrate_bps": f"{calculated_bitrate}",
            "framecount": f"{framecount}",
            "size_bytes": f"{file_size}",
            "iframes": f"{len(iframes)}",
            "pframes": f"{len(pframes)}",
            "iframe_size_bytes": f"{iframes_size}",
            "pframe_size_bytes": f"{pframes_size}",
        }
        quality_dict.update({f"vmaf_{k}": v for k, v in vmaf_dict.items()})
        quality_dict.update(
            {
                "ssim": f"{ssim}",
                "psnr": f"{psnr}",
                "psnr_y": f"{psnr_y}",
                "psnr_u": f"{psnr_u}",
                "psnr_v": f"{psnr_v}",
                "testfile": f"{test_file}",
                "reference_file": f"{filepath}",
                "source_complexity": source_complexity,
                "source_motions": source_motion,
            }
        )
        return quality_dict
    print("error: no vmaf data")
    return None


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
        "--crop_input",
        help="If there is padding which is not automatically handled, crop: ACTUAL_W:ACTUAL_H'. It will assume that all cropping is right and bottom.",
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
        "--csv",
        help="output csv data from calculated results not in csv format",
        action="store_true",
        default=False,
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
    # run all the tests
    current = 1
    total = len(options.test)
    print(f"Total number of tests: {total}")
    df = None
    start = time.time()
    for test in options.test:
        try:
            quality_dict = run_quality(test, vars(options), options.debug)
        except Exception as ex:
            print(f"{test} failed: {ex}")
            continue
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
        if quality_dict is None:
            # invalid test result
            continue
        if df is None:
            df = pd.DataFrame(columns=quality_dict.keys())
        df.loc[df.size] = quality_dict.values()
    # write data to csv file
    mode = "a"
    if options.header:
        print("WARNING! Write header implies clearing the file.")
        mode = "w"
    df.to_csv(options.output, mode=mode, index=False, header=options.header)


if __name__ == "__main__":
    main(sys.argv)
