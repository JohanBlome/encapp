#!/usr/bin/env python3

"""Script providing ffmpeg-related utilities"""

import os

import encapp_tool

RAW_EXTENSION_LIST = ('.yuv', '.rgb', '.rgba', '.raw')
FFPROBE_FIELDS = {
    'codec_name': 'codec-name',
    'width': 'width',
    'height': 'height',
    'pix_fmt': 'pix-fmt',
    'color_range': 'color-range',
    'color_space': 'color-space',
    'color_transfer': 'color-transfer',
    'color_primaries': 'color-primaries',
    'r_frame_rate': 'framerate',
    'duration': 'duration',
}
R_FRAME_RATE_MAP = {
    '30/1': 30,
    '50/1': 50,
    '60/1': 60,
    '30000/1001': 29.97,
}


def ffprobe_parse_output(stdout):
    videofile_config = {}
    for line in stdout.split('\n'):
        if not line:
            # ignore empty lines
            continue
        if line in ('[STREAM]', '[/STREAM]'):
            # ignore start/end of stream
            continue
        key, value = line.split('=')
        # store interesting fields
        if key in FFPROBE_FIELDS.keys():
            # process some values
            if key == 'r_frame_rate':
                value = R_FRAME_RATE_MAP[value]
            elif key == 'width' or key == 'height':
                value = int(value)
            elif key == 'duration':
                value = float(value)
            key = FFPROBE_FIELDS[key]
            videofile_config[key] = value
    return videofile_config


def video_is_raw(videofile):
    extension = os.path.splitext(videofile)[1]
    return extension in RAW_EXTENSION_LIST


def get_video_info(videofile, debug=0):
    assert os.path.exists(videofile), (
        'input video file (%s) does not exist' % videofile)
    assert os.path.isfile(videofile), (
        'input video file (%s) is not a file' % videofile)
    assert os.access(videofile, os.R_OK), (
        'input video file (%s) is not readable' % videofile)
    if video_is_raw(videofile):
        return {}
    # check using ffprobe
    cmd = f'ffprobe -v quiet -select_streams v -show_streams {videofile}'
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    assert ret, f'error: failed to analyze file {videofile}: {stderr}'
    videofile_config = ffprobe_parse_output(stdout)
    videofile_config['filepath'] = videofile
    return videofile_config
