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
from google.protobuf import text_format
from google.protobuf.json_format import MessageToDict
import multiprocessing as mp
import datetime
import tempfile

SCRIPT_ROOT_DIR = os.path.abspath(
    os.path.join(encapp_tool.app_utils.SCRIPT_DIR, os.pardir)
)
SCRIPT_PROTO_DIR = os.path.abspath(
    os.path.join(encapp_tool.app_utils.SCRIPT_DIR, "proto")
)
sys.path.append(SCRIPT_ROOT_DIR)
sys.path.append(SCRIPT_PROTO_DIR)
import tests_pb2 as tests_definitions  # noqa: E402

PSNR_RE = re.compile(
    r"y:(?P<psnr_y>[0-9.]*) u:(?P<psnr_u>[0-9.]*) v:(?P<psnr_v>[0-9.]*) average:(?P<psnr_avg>[0-9.]*)"
)
SSIM_RE = "SSIM Y:([0-9.]*)"
FFMPEG_SILENT = "ffmpeg -hide_banner -y "

VMAF_PERCENTILE_LIST = (5, 10, 25, 50, 75, 90, 95)

vmaf_models = ["vmaf_4k_v0.6.1", "vmaf_v0.6.1", "vmaf_v0.6.1neg"]
VMAF_MODEL = vmaf_models[2]

KEEP_QUALITY_FILES_ENV = os.environ.get("ENCAPP_KEEP_QUALITY_FILES", False) in [
    "True",
    "true",
    "1",
    True,
]

# until proven not be around
CVVDP_AVAILABLE = True
QPEXTRACT_AVAILABLE = True


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
    keyframes = pdata.loc[pdata["key_frame"] == 1]
    keyframes_cnt = len(keyframes)
    iframes = pdata.loc[pdata["pict_type"] == "I"]
    iframe_cnt = len(iframes)
    pframes = pdata.loc[pdata["pict_type"] == "P"]
    pframe_cnt = len(pframes)
    bframes = pdata.loc[pdata["pict_type"] == "B"]
    bframe_cnt = len(bframes)

    iframes = iframes.fillna(0)
    pframes = pframes.fillna(0)
    bframes = bframes.fillna(0)

    kmean = imean = pmean = bmean = 0
    kmax = imax = pmax = bmax = 0
    kmin = imin = pmin = bmin = 0

    if len(keyframes["size"]) > 0:
        kmean = np.mean(keyframes["size"])
        kmax = np.max(keyframes["size"])
        kmin = np.min(keyframes["size"])

    if len(iframes["size"]) > 0:
        imean = np.mean(iframes["size"])
        imax = np.max(iframes["size"])
        imin = np.min(iframes["size"])

    if len(pframes["size"]) > 0:
        pmean = np.mean(pframes["size"])
        pmax = np.max(pframes["size"])
        pmin = np.min(pframes["size"])

    if len(bframes["size"]) > 0:
        bmean = np.mean(bframes["size"])
        bmax = np.max(bframes["size"])
        bmin = np.min(bframes["size"])

    mean_ratio = imean / (pmean + bmean) / 2
    size_ratio = np.sum(iframes["size"]) / (
        np.sum(pframes["size"]) + np.sum(bframes["size"])
    )

    if options.get("info", False):
        print(f"ime,imx,imi = {imean}, {imax}, {imin}")
        print(f"{len(pframes)}")
        print("*** {:s} stats ***".format(label))
        print("Frame count: {:f}".format(len(pdata)))
        print("Duration: {:.3f} secs".format(total_duration))
        print("Fps: {:.2f} fps".format(fps))
        print("Bitrate {:.2f} kbps".format(8 * total_size / (total_duration * 1000)))

        print(
            "keyframes (mean, max, min): {:d}, {:d}, {:d}".format(
                int(kmean), int(kmax), int(kmin)
            )
        )
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
        print(
            "Bframes (mean, max, min): {:d}, {:d}, {:d} bytes".format(
                int(bmean), int(bmax), int(bmin)
            )
        )

        print("Kframe count: {:d}".format(keyframes_cnt))
        print("Iframe count: {:d}".format(iframe_cnt))
        print("Pframe count: {:d}".format(pframe_cnt))
        print("Bframe count: {:d}".format(bframe_cnt))
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
    df = pd.DataFrame()
    if os.path.exists(name):
        df = pd.read_csv(name)

    if "pts" not in df.columns:
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
        shell_cmd = (
            f"echo 'key_frame,pts_time,duration_time,pkt_size,pict_type' > {name}"
        )
        encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        # run the ffprobe command
        shell_cmd = (
            "ffprobe -select_streams v -show_frames -show_entries frame=pts_time,"
            "duration_time,pkt_size,key_frame,pict_type -v quiet -of csv='p=0' "
            f"{inputfile} >> {name}"
        )
        encapp_tool.adb_cmds.run_cmd(shell_cmd, debug=debug)
        # process data
        alt_dur = 1.0 / 29.97
        counter = 0
        time_error = False
        fps = -1
        override_dur = -1

        first_pts = -1
        pts = 0
        recalc_duration = False

        # ['key_frame', 'pts_time', 'duration_time', 'pkt_size', 'pict_type']

        with open(name, "r") as fd:
            datareader = csv.reader(fd, delimiter=",")
            for row in datareader:
                try:
                    if not row[0].isdigit():
                        continue
                    is_iframe = int(row[0].strip())
                    pict_type = 0
                    if len(row) > 4:
                        pict_type = row[4]
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
                                pict_type,
                                pts,
                                dur,
                                frame_size,
                                8 * frame_size / (dur * 1000),
                            ]
                        )

                except Exception as e:
                    print(str(e) + ", row = " + str(row))
                counter += 1
        labels = ["file", "key_frame", "pict_type", "pts", "duration", "size", "kbps"]
        df = pd.DataFrame.from_records(data, columns=labels, coerce_float=True)
        # overwrite with derived data
        df.to_csv(name)
    else:
        df = pd.read_csv(name)
    calc_stats(df, options, inputfile, True)
    if not options["keep_quality_files"] and not KEEP_QUALITY_FILES_ENV:
        os.remove(name)
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
    if "model" in data:
        vmaf_dict["model"] = data["model"]
    # Check for zero frames and frame count
    vmaf_dict.update({"framecount": len(data["frames"])})
    vmaf_dict.update({"zero_vmaf": np.any(vmaf_list == 0)})
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


def parse_quality_cvvdp(cvvdp_csv_file):
    # test, reference, cvvdp
    # jcq_hdr8/hdr8.jcq.0e56c5c3-9dd2-4ce8-af3f-5c7fc87a9b4f.video_0.gop-5.20000000bps.mp4, /tmp/encapp.jod.haawxdc_.y4m, 9.574105262756348
    try:
        data = pd.read_csv(cvvdp_csv_file)
        data.columns = data.columns.str.strip()
        return data["cvvdp"].values[0]
    except FileNotFoundError as fex:
        pass
    except Exception as ex:
        print(f"Failed to read cvvdp file: {cvvdp_csv_file}, {ex}")
    return -1


def parse_quality_siti(csv_file):
    try:
        data = pd.read_csv(csv_file)
        data.columns = data.columns.str.strip()
        return data.values[0]
    except FileNotFoundError as fex:
        pass
    except Exception as ex:
        print(f"Failed to read siti file: {csv_file}, {ex}")
    return [-1] * 6


def parse_quality_qpextract(qpextract_single_file):
    try:
        df = pd.read_csv(qpextract_single_file)
        return (
            df["qpy_min"].values[0],
            df["qpy_max"].values[0],
            df["qpy_avg"].values[0],
            df["qpu_min"].values[0],
            df["qpu_max"].values[0],
            df["qpu_avg"].values[0],
            df["qpv_min"].values[0],
            df["qpv_max"].values[0],
            df["qpv_avg"].values[0],
        )
    # , qpy_min, qpy_max, qpu_avg, qpu_min, qpu_max, qpv_avg, qpv_min,  qpv_max = parse_qpextract(qpextract_file)
    except FileNotFoundError as fex:
        pass
    except Exception as ex:
        print(f"Failed to parse qpvals file: {qpextract_single_file}, {ex}")
    return (
        -1,
        -1,
        -1,
        -1,
        -1,
        -1,
        -1,
        -1,
        -1,
    )


def run_quality_mp(args):
    result = None
    try:
        result = run_quality(args.get("test"), args.get("options"), args.get("debug"))
    except Exception as ex:
        print(f"Failed run test: {args=}, {ex} {type(ex)}")
        if ex.__traceback__:
            print(f"line: {ex.__traceback__.tb_lineno}")
        result = {"file": args.get("test"), "Error": f"Exception: {ex}"}
    return result


"""
def get_source_length:
    #TODO: fix this
"""


def isfloat(text):
    try:
        val = float(text)
    except:
        return False
    return True


def average_dicts(dicts):
    outdict = {}
    if len(dicts) == 0:
        return outdict
    for key in dicts[0].keys():
        if isinstance(dicts[0][key], type({})):
            subd = [d[key] for d in dicts]
            outdict[key] = average_dicts(subd)
        elif isinstance(dicts[0][key], type([])):
            vals = []
            for index in range(0, len(dicts[0][key])):
                if isinstance(dicts[0][key][index], type({})):
                    subd = {}
                    for innerkey in dicts[0][key][index].keys():
                        if isfloat(dicts[0][key][index][innerkey]):
                            subd[innerkey] = sum(
                                float(d[key][index][innerkey]) for d in dicts
                            ) / len(dicts)
                        else:
                            tmp = [d[key][index][innerkey] for d in dicts]
                            subd[innerkey] = average_dicts(tmp)
                    vals.append(subd)
            outdict[key] = vals
        else:
            if isfloat(dicts[0][key]):
                outdict[key] = sum(
                    [float(d[key]) for d in dicts if isfloat(d[key])]
                ) / len(dicts)
            else:
                # Assume string and use the first
                outdict[key] = dicts[0][key]
    return outdict


def run_quality(test_file, options, debug):
    """Compare the output found in test_file with the source/reference
    found in options.media_path directory or overriden
    """
    global VMAF_MODEL
    global CVVDP_AVAILABLE
    global QPEXTRACT_AVAILABLE

    # Dictionary used for a failed run
    failed = {"file": test_file}
    if not os.path.exists(test_file):
        failed["error"] = "File not found"
        return failed

    if debug > 0:
        print(options)
    duration = 0
    results = {}
    if test_file[-4:] == "json":
        # read test file results
        try:
            with open(test_file, "r") as fd:
                results = json.load(fd)
        except:
            return None

        if results.get("sourcefile") is None:
            print(f"ERROR, bad source, {test_file}, probably not an Encapp file")
            failed["error"] = "Not an encapp file"
            return failed

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
        print(f"No encoded file available: {encodedfile}")
        failed["error"] = f"No encoded file: {encodedfile}"
        return failed

    if len(directory) > 0:
        encodedfile = f"{directory}/{encodedfile}"

    if (len(encodedfile) == 0) or (not os.path.exists(encodedfile)):
        failed["error"] = f"ERROR! Encoded file name is missing, {encodedfile}"
        return failed

    vmaf_file = f"{encodedfile}.vmaf.json"
    ssim_file = f"{encodedfile}.ssim"
    psnr_file = f"{encodedfile}.psnr"
    cvvdp_file = f"{encodedfile}.cvvdp.csv"
    qpextract_file = f"{encodedfile}.qpvals.csv"
    siti_file = f"{reference_pathname}.siti.csv"

    vmaf_model_info = None

    video_info = None
    try:
        video_info = encapp_tool.ffutils.get_video_info(encodedfile, debug)
    except:
        print("Failed to parse with ffprobe")
        video_info = None

    recalc = options.get("recalc", None)
    test = results.get("test")
    if type(test) == str:
        # Historically this has always been a dictionary but if this is a string describing a protobuf
        # try to parse it into a dict
        test = text_format.Parse(test, tests_definitions.Test())
        test = MessageToDict(test)
    if (
        os.path.exists(vmaf_file)
        and os.path.exists(ssim_file)
        and os.path.exists(psnr_file)
        and (os.path.exists(cvvdp_file) or not options.get("cvvdp", False))
        and (os.path.exists(qpextract_file) or not options.get("qpextract", False))
        and (os.path.exists(siti_file) or not options.get("siti", False))
        and not recalc
    ):
        if options.get("debug", False) > 0:
            print(f"All quality indicators already calculated for media, {vmaf_file}")
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
                resolution = test["configure"].get("resolution", None)
                if not resolution or len(resolution) == 0:
                    resolution = test["input"].get("resolution", None)

                if resolution:
                    res = encapp.parse_resolution(resolution)
                    output_width = res[0]
                    output_height = res[1]

            if output_width == -1 or output_height == -1:
                output_width = video_info["width"]
                output_height = video_info["height"]

            if test != None:
                output_framerate = test["configure"].get("framerate", None)
                if not output_framerate or output_framerate == 0:
                    output_framerate = test["input"].get("framerate", None)
            if not output_framerate or output_framerate <= 0:
                output_framerate = video_info["framerate"]
            print("WARNING! output media format is missing, guessing values...")
            print(
                f"outputres: {output_width}x{output_height}\noutput_framerate: {output_framerate}"
            )

        output_resolution = f"{output_width}x{output_height}"
        if video_info is not None:
            media_res = f"{video_info['width']}x{video_info['height']}"
            # Although we assume that the distorted file is starting at the beginning
            # at least we limit the length to the duration of it.
            # TODO: shortest file wins :)
            duration = f"{video_info['duration']}"
        else:
            media_res = None
        if options.get("limit_length", -1) > 0:
            duration = float(options.limit_length)
        if output_framerate is None or output_framerate == 0:
            if video_info is not None:
                output_framerate = f"{video_info.get('framerate', 30)}"
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
                    f"json {input_media_format.get('width')}x"
                    f"{input_media_format.get('height')}"
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
        referenced = reference_pathname
        if not os.path.exists(distorted):
            print(f"Distorted {distorted} is unavailable")
            return None

        if debug > 0:
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

        tmp_ref = None
        tmp_dist = None
        if options.get("ignore_timing"):
            # It seems that even wih same framerate we can get into trouble so always use raw.
            # transcode to raw intermidiant file
            tmp_ref = f".ref_{time.time()}.raw"
            tmp_dist = f".dist_{time.time()}.raw"
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
        if tmp_dist is not None:
            pix_fmt = "yuv420p"
            # create them
            shell_cmd = f"{FFMPEG_SILENT} -i {encodedfile} -f rawvideo -pix_fmt {pix_fmt} -s {output_resolution} {tmp_dist} -y"
            ret, std, err = encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
            shell_cmd = f"{FFMPEG_SILENT} {ref_part} -f rawvideo -pix_fmt {pix_fmt} -s {output_resolution} {tmp_ref} -y"
            ret, std, err = encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
            dist_part = f"-f rawvideo -pix_fmt {pix_fmt} -s {output_resolution} -r 30 -i {tmp_dist}"
            ref_part = f"-f rawvideo -pix_fmt {pix_fmt} -s {input_resolution} -r 30 -i {tmp_ref}"
            distorted = tmp_dist
            referenced = tmp_ref

        # This is jsut a naming scheme for the color scaling
        diststream = "distorted"
        input_width = input_resolution.split("x")[0]
        input_height = input_resolution.split("x")[1]
        if len(force_scale) > 0:
            diststream = "d1"

        # We can choose to convert to the input framerate
        # Or convert to the output. This nomrally gives higher score to lower fps and process faster...
        # There is not point in scaling first to a intermedient yuv raw file, just chain all operation
        if input_framerate != output_framerate and not options.get("ignore_timing"):
            print(
                "WARNING! Encoded file framerate differs from reference and 'ignore-timing' is not set."
            )
        filter_cmd = ""
        # TODO: add vmaf offset
        crop = ""
        dist_vid = "0:v"
        orig_vid = "1:v"
        if options.get("vmaf_crop", None):
            sides = options.get("vmaf_crop")
            if sides == "source":
                sides = [input_width, input_height]
            else:
                sides = sides.split("x")
            if input_height > input_width:
                crop = f"{sides[1]}:{sides[0]}"
            else:
                crop = f"{sides[1]}:{sides[0]}"

            crop = f"crop={crop}"
            filter_cmd = f"[{dist_vid}]{crop}[{diststream}];[{orig_vid}]{crop}[source]"

            dist_vid = f"{diststream}"
            orig_vid = "source"

        scale = ""
        if options.get("vmaf_scale", None):
            sides = options.get("vmaf_scale")
            if sides == "source":
                sides = [input_width, input_height]
            else:
                sides = sides.split("x")
            if input_height > input_width:
                scale = f"{sides[1]}x{sides[0]}"
            else:
                scale = f"{sides[1]}x{sides[0]}"

            scale = f"scale={scale}:flags={options.get('vmaf_scaler', 'lanczos')}"
            filter_cmd = (
                f"[{dist_vid}]{scale}[{diststream}];[{orig_vid}]{scale}[source]"
            )

            dist_vid = f"{diststream}"
            orig_vid = "source"

        if options.get("ignore_timing") or (input_framerate == output_framerate):
            # TODO: old code cropped distorted and scaled source. Let us have the ability to crop or scale both as needed
            if debug > 0:
                print(
                    f"No framerate scaling, ref = {referenced}, {input_framerate=} vs {output_framerate=}"
                )
        else:
            filter_cmd = f"[{dist_vid}]fps={input_framerate}[d0];[d0]{crop}scale={input_width}:{input_height}[{diststream}]"

            dist_vid = f"{diststream}"
            orig_vid = "source"

        # Do calculations
        info = {}
        if recalc or not os.path.exists(vmaf_file):
            # Filter timings, for 60fps split in two parta and caclulate odd and even frame separately then average the result
            timefilter = ""
            loopmode = False

            vmaf_dicts = []
            if input_framerate == output_framerate and int(
                round(input_framerate) == 60
            ):
                loopmode = True

            for loop in range(0, 2):
                vmaf_file_ = vmaf_file
                if loopmode:
                    timefilter = f";[{diststream}]select=e='not(mod(n, 2) + {loop} - 1)'[{diststream}];[source]select=e='not(mod(n, 2) + {loop} -1)'[source];[{diststream}][source]"
                    vmaf_file_ = f"{vmaf_file[:-4]}_{loop}.json"
                elif len(filter_cmd) > 0:
                     filter_cmd = f"{filter_cmd};[{diststream}][source]"

                # important: vmaf must be called with videos in the right order
                # <distorted_video> <reference_video>
                # https://jina-liu.medium.com/a-practical-guide-for-vmaf-481b4d420d9c
                shell_cmd = (
                    f"{FFMPEG_SILENT} {dist_part} {ref_part} -t {duration} "
                    "-filter_complex "
                    f'"{filter_cmd}{timefilter}libvmaf=log_path={vmaf_file_}:'
                    "n_threads=16:log_fmt=json"
                )
                # Allow for an environment variable
                model = ""
                if os.environ.get("VMAF_MODEL_PATH", None):
                    if debug:
                        print("Environment VMAF_PATH override model")
                    model = "path=" + os.environ.get("VMAF_MODEL_PATH")
                else:
                    model = f"'version={VMAF_MODEL}'"
                shell_cmd += f":model={model}"
                shell_cmd += '" -f null - 2>&1'
                # write a settings file
                if not os.path.exists(f"{directory}/vmafmodel.txt"):
                    with open(f"{directory}/vmafmodel.txt", "w") as modelfile:
                        modelfile.write(
                            f"vmaf model: {model}\nUnless stated above phone mode is NOT used.\n"
                        )
                vmaf_model_info = model
                if debug > 0:
                    print(f"vmaf command: {shell_cmd}")
                encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                # Open json and add the info
                vmafdata = None
                try:
                    with open(vmaf_file_, "r") as fd:
                        vmafdata = json.load(fd)
                        vmaf_dicts.append(vmafdata)

                    with open(vmaf_file_, "w") as fd:
                        paths = model.split("/")
                        # this will be the same for all
                        if len(info) == 0:
                            info["model"] = paths[-1]
                            info["path"] = model if len(paths) > 1 else ""
                            if options.get("vmaf_crop", None):
                                info["resolution"] = options.get("vmaf_crop")
                            else:
                                info["resolution"] = input_resolution

                        for key in info.keys():
                            vmafdata[key] = info[key]
                        json.dump(vmafdata, fd, indent=4)

                except FileNotFoundError as ferror:
                    print(f"VMAF calculation failed. {ferror}")

            if loopmode:
                vmaf_dict = average_dicts(
                    vmaf_dicts
                )  # {key: (vmaf_dicts[0].get(key, 0) + vmaf_dicts[1].get(key, 0))/2 for key in vmaf_dicts[0].keys() if isfloat(vmaf_dicts[0][key])}
                with open(vmaf_file, "w") as fd:
                    json.dump(vmaf_dict, fd, indent=4)

        elif options.get("debug", False) > 0:
            print(f"vmaf already calculated for media, {vmaf_file}")

        # Other filter needs this definitions as well
        filter_cmd = f"{filter_cmd};[{diststream}][source]"
        if recalc or not os.path.exists(ssim_file):
            shell_cmd = (
                f"ffmpeg {dist_part} {ref_part} -t {duration} "
                "-filter_complex "
                f'"{filter_cmd}ssim=stats_file={ssim_file}.all" '
                f"-f null - 2>&1 | grep SSIM > {ssim_file}"
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        elif options.get("debug", False) > 0:
            print(f"ssim already calculated for media, {ssim_file}")

        if recalc or not os.path.exists(psnr_file):
            shell_cmd = (
                f"ffmpeg {dist_part} {ref_part} -t {duration} "
                "-filter_complex "
                f'"{filter_cmd}psnr=stats_file={psnr_file}.all" '
                f"-f null - 2>&1 | grep PSNR > {psnr_file}"
            )
            encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
        elif options.get("debug", False) > 0:
            print(f"psnr already calculated for media, {psnr_file}")

        if options.get("cvvdp", False) and (
            recalc or not os.path.exists(cvvdp_file) and CVVDP_AVAILABLE
        ):
            # No way to tell cvvdp raw file settings. Convert to y4m.
            with tempfile.NamedTemporaryFile(
                suffix=".y4m", prefix="encapp.jod."
            ) as y4mfile:
                y4m = y4mfile.name
                shell_cmd = f"ffmpeg -y {ref_part} -t {duration} -pix_fmt yuv420p {y4m}"
                encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                shell_cmd = (
                    f"cvvdp --test {distorted} --ref {y4m} "
                    f" --config-paths=/home/jblome/code/ColorVideoVDP/pycvvdp/vvdp_data/display_models.json --display standard_hdr_hlg "
                    f"--result {cvvdp_file}"
                )

                ret, std, stderr = encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                if stderr:
                    # cvvdp is printing to std err
                    if not ret:
                        print(f"Failed to run cvvdp: {stderr}")
                        if "command not found" in stderr:
                            print("** ColorVideoVDP needs to be installed! **\n\n")
                            CVVDP_AVAILABLE = False
                    else:
                        # collect info and write that to a txt file
                        if not os.path.isfile("cvvdp.settings.txt"):
                            with open("cvvdp.settings.txt", "w") as ifile:
                                ifile.write(stderr)
        elif options.get("debug", False) > 0:
            print(f"cvvdp already calculated for media, {cvvdp_file}")

        if options.get("qpextract", False) and (
            recalc
            or not os.path.exists(qpextract_file)
            and QPEXTRACT_AVAILABLE
            and video_info["codec-name"].lower() == "hevc"
        ):
            # Need a .265 (and also on hevc)
            with tempfile.NamedTemporaryFile(
                suffix=".265", prefix="encapp.qp."
            ) as mediafile:
                media = mediafile.name
                shell_cmd = f"ffmpeg -y -i {distorted} -t {duration} -vcodec copy -bsf hevc_mp4toannexb {media}"
                encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                shell_cmd = (
                    f"qpextract -i {media} --dump  --qpymode -o {qpextract_file}_y"
                )

                ret, std, stderr = encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                if stderr:
                    print(f"Failed to run cvvdp: {stderr}")
                    if "command not found" in stderr:
                        print("** qpextract needs to be installed! **\n\n")
                        QPEXTRACT_AVAILABLE = False

                shell_cmd = (
                    f"qpextract -i {media} --dump  --qpcbmode -o {qpextract_file}_cb"
                )

                ret, std, stderr = encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                shell_cmd = (
                    f"qpextract -i {media} --dump  --qpcrmode -o {qpextract_file}_cr"
                )

                ret, std, stderr = encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                qpydata = pd.read_csv(f"{qpextract_file}_y")
                qpcbdata = pd.read_csv(f"{qpextract_file}_cb")
                qpcrdata = pd.read_csv(f"{qpextract_file}_cr")

                vals = {
                    "source": distorted,
                    "qpy_avg": qpydata["qp_avg"].mean(),
                    "qpy_min": qpydata["qp_min"].min(),
                    "qpy_max": qpydata["qp_max"].max(),
                    "qpu_avg": qpcbdata["qp_avg"].mean(),
                    "qpu_min": qpcbdata["qp_min"].min(),
                    "qpu_max": qpcbdata["qp_max"].max(),
                    "qpv_avg": qpcbdata["qp_avg"].mean(),
                    "qpv_min": qpcbdata["qp_min"].min(),
                    "qpv_max": qpcbdata["qp_max"].max(),
                }

                df = pd.DataFrame([vals])
                df.to_csv(qpextract_file, index=False, header=True)
                os.remove(f"{qpextract_file}_y")
                os.remove(f"{qpextract_file}_cb")
                os.remove(f"{qpextract_file}_cr")
        elif options.get("debug", False) > 0:
            print(f"qpextract already calculated for media, {qpextract_file}")

        if options.get("siti", False) and not os.path.exists(siti_file):
            # If trunnign multi proc there wil be double work - but not that much
            with tempfile.NamedTemporaryFile(
                suffix=".txt", prefix="encapp.siti."
            ) as tfo:
                tf = tfo.name
                shell_cmd = (
                    f"ffmpeg  {ref_part} "
                    "-filter_complex "
                    f'"siti=print_summary=1 " '
                    f"-f null - &> {tf}"
                )
                encapp_tool.adb_cmds.run_cmd(shell_cmd, debug)
                # Parse the text and create a csv
                with open(tf, "r") as fd:
                    """
                    Spatial Information:
                    Average: 73.351112
                    Max: 93.298119
                    Min: 38.185577

                    Temporal Information:
                    Average: 41.029724
                    Max: 56.454857
                    """
                    data = {}
                    spatial = True
                    lines = fd.readlines()
                    for line in lines:
                        line = line.lower()
                        if "spatial" in line:
                            spatial = True
                            continue
                        if "temporal" in line.lower():
                            spatial = False
                            continue
                        try:
                            key, val = line.split(": ")
                        except Exception as ve:
                            if debug > 0:
                                print(f"SITI parse Val error: {ve} for {line}")
                            continue
                        val = val.strip()
                        if "average" in key:
                            if spatial:
                                data["si_avg"] = val
                            else:
                                data["ti_avg"] = val
                        if "max" in key:
                            if spatial:
                                data["si_max"] = val
                            else:
                                data["ti_max"] = val
                        if "min" in key:
                            if spatial:
                                data["si_min"] = val
                            else:
                                data["ti_min"] = val

                    if len(data) > 0:
                        pdata = pd.DataFrame([data])
                        pdata.to_csv(siti_file, index=False)

        elif options.get("debug", False) > 0:
            print(f"siti already calculated for media, {siti_file}")

        if distorted != encodedfile:
            os.remove(distorted)
        if referenced != reference_pathname:
            os.remove(referenced)

    if os.path.exists(vmaf_file):
        error_in_calc = False
        vmaf_dict = parse_quality_vmaf(vmaf_file)
        ssim = parse_quality_ssim(ssim_file)
        psnr, psnr_y, psnr_u, psnr_v = parse_quality_psnr(psnr_file)
        cvvdp = parse_quality_cvvdp(cvvdp_file)
        (
            qpy_min,
            qpy_max,
            qpy_avg,
            qpu_min,
            qpu_max,
            qpu_avg,
            qpv_min,
            qpv_max,
            qpv_avg,
        ) = parse_quality_qpextract(qpextract_file)

        (si_avg, si_min, si_max, ti_avg, ti_min, ti_max) = parse_quality_siti(siti_file)
        if options.get("csv", None):
            base, extension = os.path.splitext(vmaf_file)
            vmafcsv.process_infile(vmaf_file, f"{base}.csv", debug)
        if not options["keep_quality_files"] and not KEEP_QUALITY_FILES_ENV:
            os.remove(vmaf_file)
            os.remove(ssim_file)
            os.remove(psnr_file)
            os.remove(cvvdp_file)
            os.remove(psnr_file + ".all")
            os.remove(ssim_file + ".all")

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
        framecount = 0
        if test is not None:
            # get resolution and framerate
            resolution = test.get("configure").get("resolution")
            if not resolution:
                resolution = test.get("input").get("resolution")
            if not resolution:
                # get res from file
                resolution = f"{video_info['width']}x{video_info['height']}"
            framerate = test.get("configure").get("framerate")
            if not framerate:
                framerate = test.get("input").get("framerate")
            # derive the calculated_bitrate from the actual file size
            framecount = len(results.get("frames"))
            codec = test.get("configure").get("codec")
            bitrate = test.get("configure").get("bitrate")
            meanbitrate = results.get("meanbitrate")
            iframeinterval = test.get("configure").get("iFrameInterval", -1)
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
            resolution = f"{video_info['width']}x{video_info['height']}"
            codec = video_info["codec-name"]

        if reference_info:
            filepath = reference_pathname
        # get the data from ffmpeg
        ffmpeg_data = detailed_media_info(encodedfile, options, debug=debug)

        # TODO: check result
        ffmpeg_framecount = len(ffmpeg_data)
        if framecount == 0:
            print("Warning! did not get frame count from test. Get it from ffmpeg")
            framecount = ffmpeg_framecount
        if framecount != ffmpeg_framecount:
            failed["warning"] = (
                f"Warning! Frame count differs. Test: {framecount}, ffmpeg: {ffmpeg_framecount}"
            )
        # Check vmaf for zero vmaf and/or wrong number of frames
        try:
            if vmaf_dict.get("zero_vmaf", False) == True:
                error_in_calc = True
                # raise Exception("Warning! Some frames are zero, most likely a broken calculation.")
                print(
                    "Warning! Some frames are zero, most likely a broken calculation."
                )
                failed["warning"] = (
                    "Warning! Some frames are zero, most likely a broken calculation."
                )

            if vmaf_dict.get("framecount", 0) != framecount:
                error_in_calc = True
                print("Warning! Frame count diffes for vmaf and test")
                failed["warning"] = "Warning! Frame count diffes for vmaf and test"
        except Exception as ex:
            print(f"VMAF failed: {ex} - {type(ex)}")
            pass

        iframes = ffmpeg_data.loc[ffmpeg_data["pict_type"] == "I"]
        pframes = ffmpeg_data.loc[ffmpeg_data["pict_type"] == "P"]
        bframes = ffmpeg_data.loc[ffmpeg_data["pict_type"] == "B"]

        if iframeinterval < 0:
            iframeinterval = iframes["pts"].diff().max()
            if np.isna(iframeinterval):
                iframeinterval = 0
        iframe_size = pframes_size = bframes_size = 0
        if len(iframes) > 0:
            iframes_size = iframes["size"].mean()
        if len(pframes) > 0:
            pframes_size = pframes["size"].mean()
        if len(bframes) > 0:
            bframes_size = bframes["size"].mean()
        if not framerate or framerate == 0:
            print("Warning, framerate is 0, setting it to 30")
            framerate = 30
        if framecount == 0:
            exit(0)
        calculated_bitrate = (
            int((file_size * 8 * framerate) / framecount) if framecount > 0 else 0
        )
        source_complexity = ""
        source_motion = ""
        if options.get("mark_complexity", None):
            source_complexity = options.mark_complexity
        if options.get("mark_motion", None):
            source_motion = options.mark_motion
        if len(results) == 0:
            # something is wrong...
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
            "bitrate_bps": f"{encapp.parse_magnitude(bitrate)}",
            "meanbitrate_bps": f"{meanbitrate}",
            "mean_bpp": f"{mean_bpp}",
            "calculated_bitrate_bps": f"{calculated_bitrate}",
            "framecount": f"{framecount}",
            "size_bytes": f"{file_size}",
            "iframes": f"{len(iframes)}",
            "pframes": f"{len(pframes)}",
            "bframes": f"{len(bframes)}",
            "iframe_size_bytes": f"{iframes_size}",
            "pframe_size_bytes": f"{pframes_size}",
            "bframe_size_bytes": f"{bframes_size}",
        }
        quality_dict.update({f"vmaf_{k}": v for k, v in vmaf_dict.items()})
        quality_dict.update(
            {
                "ssim": f"{ssim}",
                "psnr": f"{psnr}",
                "psnr_y": f"{psnr_y}",
                "psnr_u": f"{psnr_u}",
                "psnr_v": f"{psnr_v}",
                "cvvdp": cvvdp,
                "qpy_min": qpy_min,
                "qpy_max": qpy_max,
                "qpy_avg": qpy_avg,
                "qpu_min": qpu_min,
                "qpu_max": qpu_max,
                "qpu_avg": qpu_avg,
                "qpv_min": qpv_min,
                "qpv_max": qpv_max,
                "qpv_avg": qpv_avg,
                "si_min": si_min,
                "si_max": si_max,
                "si_avg": si_avg,
                "ti_min": ti_min,
                "ti_max": ti_max,
                "ti_avg": ti_avg,
                "testfile": f"{test_file}",
                "reference_file": f"{filepath}",
                "source_complexity": source_complexity,
                "source_motions": source_motion,
            }
        )
        if "warning" in failed:
            quality_dict["warning"] = f'"{failed["warning"]}"'
        else:
            quality_dict["warning"] = ""

        return quality_dict
    else:
        failed["error"] = "No quality caclulated, probably error in encoded file"
    print("error: no vmaf data")
    return failed


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
        help=("Override reference, used when source is downsampled prior to encoding"),
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
        "--vmaf-crop",
        dest="vmaf_crop",
        help="Use crop for calculating vmaf, crop: WxH",
        default=None,
    )
    parser.add_argument(
        "--vmaf-offset",
        dest="vmaf_offset",
        help="If there is padding which is not automatically handled use this with crop, offset: HxV",
        default=None,
    )
    parser.add_argument(
        "--vmaf-scale",
        dest="vmaf_scale",
        help="Use a specific resolution for the calculation, resolution: WxH. Default scaler is lanczos",
        default=None,
    )
    parser.add_argument(
        "--vmaf-scaler",
        dest="vmaf_scaler",
        help="Scaler used for actual scaling. Default is lanczos",
        default="lanczos",
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
    parser.add_argument(
        "--vmaf_model",
        help=f"Override the vmaf model, models: {vmaf_models}, default is {VMAF_MODEL}",
        default=VMAF_MODEL,
    )
    parser.add_argument(
        "--keep-quality-files",
        dest="keep_quality_files",
        action="store_true",
        help=f'Keep the intermediant results of quality calculations. Can also be set using a environment variable: "ENCAPP_KEEP_QUALITY_FILES"',
    )
    parser.add_argument(
        "--ignore-timing",
        dest="ignore_timing",
        type=bool,
        default=True,
        help="Ignore all timing information and compare frame by frame between source and reference. "
        "This will create temporary file without andy framerate information. Slower but can help when the timing has been broken. "
        "The transcoding will only happen if there is a difference in the framerates.",
    )
    parser.add_argument(
        "--max-parallel",
        dest="max_parallel",
        type=int,
        default=1,
        help="Maximum number of parallel processes",
    )
    parser.add_argument(
        "--info",
        action="store_true",
        help="Extra information",
    )
    parser.add_argument(
        "--cvvdp",
        action="store_true",
        help="Calculate ColoVideoVdp value",
    )
    parser.add_argument(
        "--qpextract",
        action="store_true",
        help="Calculate qp distribution",
    )
    parser.add_argument(
        "--siti",
        action="store_true",
        help="Run siti to characterize the video. Done on the source to mimimize the runtime.",
    )

    options = parser.parse_args()

    if len(argv) == 1:
        parser.print_help()
        sys.exit()

    if options.media_path is not None:
        # make sure media_path holds a valid directory
        assert os.path.isdir(options.media_path), (
            f"Error: {options.media_path} not a valid directory"
        )
        assert os.access(options.media_path, os.R_OK), (
            f"Error: {options.media_path} not a readable directory"
        )

    return options


def calculate_quality(tests, source_path, output, quiet, debug):
    """
    Calculates vmaf, psnr and ssim for all test paths using the source_path to
    look for references. All parameters will be default.
    """
    # Everything will be default.
    # global VMAF_MODEL
    # VMAF_MODEL = options.vmaf_model

    # run all the tests
    current = 1
    total = len(tests)
    if not quiet:
        print(f"Total number of tests: {total}")
    df = None
    start = time.time()
    options = {
        "header": True,
        "media_path": source_path,
        "quiet": quiet,
        "keep_quality_files": True,
    }
    for test in tests:
        try:
            quality_dict = run_quality(test, options, False)
        except Exception as ex:
            print(f"{test} failed: {ex}")
            if ex.__traceback__:
                print(f"line: {ex.__traceback__.tb_lineno}")
            continue
        now = time.time()
        run_for = now - start
        time_per_test = float(run_for) / float(current)
        time_left = round(time_per_test * (total - current))
        time_left_m = int(time_left / 60)
        time_left_s = int(time_left) % 60
        if not quiet:
            print(
                f"Running {current}/{total}, Running for: {round(run_for)} sec, estimated time left {time_left_m}:{time_left_s:02} m:s"
            )
        current += 1
        if quality_dict is None:
            continue
        if df is None:
            df = pd.DataFrame(columns=quality_dict.keys())
        df.loc[df.size] = quality_dict.values()
    # write data to csv file
    mode = "w"

    if debug:
        print(f"Write to {output}")
    df.to_csv(output, mode=mode, index=False, header=True)


def main(argv):
    """Calculate video quality properties (vmaf/ssim/psnr) and write
    a csv with relevant data
    """
    global VMAF_MODEL
    global CVVDP_AVAILABLE
    options = get_options(argv)
    VMAF_MODEL = options.vmaf_model

    # run all the tests
    current = 1
    total = len(options.test)
    print(f"Total number of tests: {total}")
    values = []
    start = time.time()

    if options.max_parallel > 1:
        options.keep_quality_files = True
        voptions = vars(options).copy()
        del voptions["test"]
        mpargs = [
            ({"test": test, "options": voptions, "debug": options.debug})
            for test in options.test
        ]

        with mp.Pool(processes=options.max_parallel) as p:
            results = p.map(run_quality_mp, mpargs, chunksize=1)

        # rerun to get complete csv, quality files are kept so it will just read the files and compile the dataset

    failed = []
    success = []
    for test in options.test:
        try:
            quality_dict = run_quality(test, vars(options), options.debug)
            if "error" in quality_dict:
                failed.append({"file": test, "error": quality_dict["error"]})
            else:
                if len(quality_dict["warning"]) > 0:
                    success.append({"file": test, "warning": quality_dict["warning"]})
                else:
                    success.append({"file": test, "warning": "none"})

        except Exception as ex:
            print(f"{test} failed: {ex}")
            if ex.__traceback__:
                print(f"line: {ex.__traceback__.tb_lineno}")
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
            continue

        if "error" in quality_dict:
            continue
        values.append(quality_dict)
    # write data to csv file
    df = pd.DataFrame(values)
    mode = "a"
    if options.header:
        mode = "w"
        mode = "w"
    if df is not None:
        df.to_csv(options.output, mode=mode, index=False, header=options.header)

    # Summary
    dfs = pd.DataFrame(success)
    warn = pd.DataFrame()
    if "warning" in dfs.columns:
        warn = dfs.loc[dfs["warning"] != "none"]
    summary = "\n***********************\n"
    summary += f"Succesfully parsed :{len(success)}\n"
    summary += f"number of warnings:{len(warn)}\n"
    summary += f"Failed: {len(failed)}\n\n\n"

    print(summary)
    with open(f"{options.output}.summary.log", "w") as f:
        f.write(summary)
        f.write("Warnings:\n")
        if len(warn) > 0:
            f.write(warn.to_string(header=False, index=False))
        else:
            f.write("None\n")
        f.write("\nErrors:\n")
        for fail in failed:
            f.write(f"{fail['file']} - {fail['error']}\n")

    if not CVVDP_AVAILABLE:
        print("** ColorVideoVDP needs to be installed! **\n\n")

    if not QPEXTRACT_AVAILABLE:
        print("** qpextract needs to be installed! **\n\n")


if __name__ == "__main__":
    main(sys.argv)
