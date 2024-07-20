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
    "r_frame_rate": "framerate",
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
        key, value = line.split("=")
        # store interesting fields
        if key in FFPROBE_FIELDS.keys():
            # process some values
            if key == "r_frame_rate":
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
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
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
            "vstride", settings.get("input", {}).get("vstride", height)
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
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    print("Done\n*****\n")
    assert ret, f"error: ffmpeg returned {stderr}"


def ffmpeg_convert_to_raw_run_filter(input_settings, output_settings, filter_settings, debug=0):
    # Input settings only important if this is a raw file.
    input_def = ""
    output_resolution = output_settings.get("resolution", "")
    output_filepath = output_settings.get("filepath", "")
    output_pix_fmt = output_settings.get("pix_fmt", "")
    input_filepath = input_settings.get("filepath", "")

    if video_is_raw(input_filepath):
        pix_fmt = input_settings.get("pix_fmt", "yuv420p")
        resolution = input_settings.get("resolution", "1920x1080")
        framerate = input_settings.get("framerate", 30)
        input_def = f"-f rawvideo -pix_fmt {pix_fmt} -video_size {resolution} -framerate {framerate}"
        if debug > 0: 
            print(f"Raw input: {input_def}")
    # Filter settings
    method = filter_settings.method
    filter_cmd = ""


    if len(method) == 0:
        print("Error: ffmpeg filterdefinition without method stated")
        exit(0)
    # We will need some special handing for scale filter, ther rest need to ave everything in paramater list 
    if method == "scale":
        filter_cmd = f"scale={output_resolution}"

    for param in filter_settings.parameter:
        filter_cmd += f":{param.key}={param.value}"

    if debug > 0:
        print(f"Filter command: {filter_cmd}")

    cmd = FFMPEG_SILENT + [
        "-i",
        input_filepath,
        "-f",
        "rawvideo",
        "-pix_fmt",
        output_pix_fmt,
        "-vf",
        filter_cmd,
        output_filepath,
    ]

    # TODO(chema): run_cmd() should accept list of parameters
    cmd = " ".join(cmd)
    if debug > 0:
        print("cmd = ", cmd)
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    assert ret, f"error: ffmpeg returned {stderr}"


def ffmpeg_convert_to_raw(input_filepath, output_filepath, settings, debug):
    print(f"\n\n*** ffmpeg_convert_to_raw  {locals().items()=}")
    
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

    # defautl scaling is bicubic
    scaling_method = "bicubic"
    filter_cmd = f"scale={settings.get('output', {}).get('resolution', settings.get('input', {}).get('resolution'))}:flags={scaling_method}"

    if hstride > 0 and vstride > 0:
        filter_cmd += f",pad={hstride}:{vstride}"

    framerate = settings.get("output", {}).get(
        "framerate", settings.get("input", {}).get("framerate")
    )

    if framerate and framerate > 0:
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
    print("cmd = ", cmd)
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    print("Done\n****\n")
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
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    assert ret, f"error: ffmpeg returned {stderr}"
