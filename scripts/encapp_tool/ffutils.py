#!/usr/bin/env python3

"""Script providing ffmpeg-related utilities"""

import os

import encapp_tool

RAW_EXTENSION_LIST = (".yuv", ".rgb", ".rgba", ".raw")
FFPROBE_FIELDS = {
    "codec_name": "codec-name",
    "width": "width",
    "height": "height",
    "pix_fmt": "pix-fmt",
    "color_range": "color-range",
    "color_space": "color-space",
    "color_transfer": "color-transfer",
    "color_primaries": "color-primaries",
    #"r_frame_rate": "framerate",
    "avg_frame_rate": "framerate",
    "duration": "duration",
}

FFMPEG_SILENT = ["ffmpeg", "-hide_banner", "-y"]


def ffprobe_parse_output(stdout):
    videofile_config = {}
    for line in stdout.split("\n"):
        if not line:
            # ignore empty lines
            continue
        if line in ("[STREAM]", "[/STREAM]"):
            # ignore start/end of stream
            continue
        if "=" in line:
            key, value = line.split("=")
        else:
            continue
        # store interesting fields
        if key in FFPROBE_FIELDS.keys():
            try:
                # process some values
                # This is the lowest framerate with which all timestamps can be represented accurately (it is the least common multiple of all framerates in the stream). Note, this value is just a guess! For example, if the time base is 1/90000 and all frames have either approximately 3600 or 1800 timer ticks, then r_frame_rate will be 50/1.
                # This is problematic since that will be handled in a different way when running ffmpeg cli command.
                # Let us use the average instead.

                '''
                if key == "r_frame_rate":
                '''
                if key == "avg_frame_rate":
                    split = value.split("/")
                    if len(split) > 1:
                        value = round(float(split[0]) / float(split[1]), 2)
                    else:
                        value = float(value)
                elif key == "width" or key == "height":
                    value = int(value)
                elif key == "duration":
                    value = float(value)

                key = FFPROBE_FIELDS[key]
                videofile_config[key] = value
            except Exception as ex:
                print("\n\n***\nError: could not find key = '" + key + "', " + ex)
    return videofile_config


def video_is_raw(videofile):
    extension = os.path.splitext(videofile)[1]
    return extension in RAW_EXTENSION_LIST


def video_is_y4m(videofile):
    extension = os.path.splitext(videofile)[1]
    return extension == ".y4m"


def get_video_info(videofile, debug=0):
    assert os.path.exists(videofile), f"input video file {videofile} not exist"
    assert os.path.isfile(videofile), f"input video file {videofile} is not a file"
    assert os.access(
        videofile, os.R_OK
    ), f"input video file ({videofile}) is not readable"
    if video_is_raw(videofile):
        return {}
    # check using ffprobe
    cmd = f"ffprobe -v quiet -select_streams v -show_streams {videofile}"
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    assert ret, f"error: failed to analyze file {videofile}: {stderr}"
    videofile_config = ffprobe_parse_output(stdout)
    videofile_config["filepath"] = videofile
    return videofile_config


def ffmpeg_transcode_raw(input_filepath, output_filepath, settings, debug):
    resolution = settings.get("output", {}).get(
        "resolution", settings.get("input", {}).get("resolution")
    )
    width = int(resolution.split("x")[0])
    height = int(resolution.split("x")[1])
    pad_w = int(
        settings.get("output", {}).get(
            "hstride", settings.get("input", {}).get("hstride", width)
        )
    )
    pad_h = int(
        settings.get("output", {}).get(
            "vstride", settings.get("input", {}).get("vstride", width)
        )
    )

    filter_cmd = (
        f"scale={settings.get('output', {}).get('resolution', settings.get('input', {}).get('resolution'))},"
        f"pad={pad_w}:{pad_h},"
        f"fps={str(settings.get('output', {}).get('framerate', settings.get('input', {}).get('framerate')))}"
    )
    if debug > 0:
        print(f"filter command {filter_cmd}")
    cmd = FFMPEG_SILENT + [
        "-f",
        "rawvideo",
        "-pix_fmt",
        settings.get("input", {}).get("pix_fmt"),
        "-video_size",
        settings.get("input", {}).get("resolution"),
        "-framerate",
        str(settings.get("input", {}).get("framerate")),
        "-i",
        input_filepath,
        "-f",
        "rawvideo",
        "-pix_fmt",
        settings.get("output", {}).get(
            "pix_fmt", settings.get("input", {}).get("pix_fmt")
        ),
        "-vf",
        filter_cmd,
        output_filepath,
    ]

    # TODO(chema): run_cmd() should accept list of parameters
    cmd = " ".join(cmd)
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    assert ret, f"error: ffmpeg returned {stderr}"


def ffmpeg_convert_to_raw(input_filepath, output_filepath, settings, debug):
    resolution = settings.get("output", {}).get(
        "resolution", settings.get("input", {}).get("resolution")
    )
    width = int(resolution.split("x")[0])
    height = int(resolution.split("x")[1])
    hstride = int(
        settings.get("output", {}).get(
            "hstride", settings.get("input", {}).get("hstride", width)
        )
    )
    vstride = int(
        settings.get("output", {}).get(
            "vstride", settings.get("input", {}).get("vstride", -1)
        )
    )

    filter_cmd = f"scale={settings.get('output', {}).get('resolution', settings.get('input', {}).get('resolution'))}"

    if hstride > 0 and vstride > 0:
        filter_cmd += f",pad={hstride}:{vstride}"

    framerate = settings.get("output", {}).get(
        "framerate", settings.get("input", {}).get("framerate")
    )

    if framerate and float(framerate) > 0:
        filter_cmd = filter_cmd + f",fps={framerate}"

    if debug > 0:
        print(f"filter command {filter_cmd}")
    cmd = FFMPEG_SILENT + [
        "-i",
        input_filepath,
        "-f",
        "rawvideo",
        "-pix_fmt",
        settings.get("output", {}).get(
            "pix_fmt", settings.get("input", {}).get("pix_fmt")
        ),
        "-vf",
        filter_cmd,
        output_filepath,
    ]

    # TODO(chema): run_cmd() should accept list of parameters
    cmd = " ".join(cmd)
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    assert ret, f"error: ffmpeg returned {stderr}"


def ffmpeg_convert_to_raw_simple(
    input_filepath, output_filepath, pix_fmt, video_size, framerate, debug
):
    cmd = FFMPEG_SILENT + [
        "-i",
        input_filepath,
        "-f",
        "rawvideo",
        "-pix_fmt",
        pix_fmt,
        "-video_size",
        video_size,
        "-framerate",
        str(framerate),
        output_filepath,
    ]
    # TODO(chema): run_cmd() should accept list of parameters
    cmd = " ".join(cmd)
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug=debug)
    assert ret, f"error: ffmpeg returned {stderr}"
