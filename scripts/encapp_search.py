#!/usr/bin/env python3

"""Python script to for index and search in Encapp result json files.
It will search all directories below the specified one (unless --no_rec
option enabled).

Searchable properties are
* size (WxH)
* codec (partial name is fine)
* bitrate where bitrate can be
    - sctrict size, e.g. 200k
    - a range 200000-1M
* group of pictures (gop)
* frame rate

The output can either be the video source files or the json result.
"""

import argparse
import sys
import json
import os
import pandas as pd
import re

import encapp

INDEX_FILE_NAME = ".encapp_index"


def getProperties(options, json):
    data = getData(options, True)
    _, filename = os.path.split(json)
    row = data.loc[data["filename"].str.contains(filename)]
    return row


def getFilesInDir(directory, recursive):
    regexp = "^encapp_.*json$"
    files = []
    for path in os.listdir(directory):
        full_path = os.path.join(directory, path)
        if os.path.isfile(full_path):
            if re.match(regexp, path):
                files.append(full_path)
        else:
            if recursive:
                files = files + getFilesInDir(full_path, recursive)
    return files


common_data = ["common", "input", "configure"]


def dict_flatten(test):
    key_list = []
    val_list = []
    for k1, v1 in test.items():
        if k1 in common_data:
            for k2, v2 in v1.items():
                key_list.append(f"{k1}.{k2}")
                val_list.append(v2)
    return key_list, val_list


def indexDirectory(options, recursive):
    files = getFilesInDir(f"{options.path}", recursive)
    settings = []

    key_list = []
    for filename in files:
        model = ""
        platform = ""
        serial = ""
        try:
            # get device data
            device_filename = os.path.join(os.path.dirname(filename), "device.json")
            with open(device_filename) as f:
                device_info = json.load(f)
            model = device_info.get("props", {}).get("ro.product.model", "")
            platform = device_info.get("props", {}).get("ro.board.platform", "")
            serial = device_info.get("props", {}).get("ro.serialno", "")
        except Exception as exc:
            print("json " + filename + ", load failed: " + str(exc))
        try:

            # get experiment data
            with open(filename) as f:
                data = json.load(f)
                key_list, val_list = dict_flatten(data["test"])
                settings.append(
                    [
                        model,
                        platform,
                        serial,
                        filename,
                        data["encodedfile"],
                        *val_list,
                        data["meanbitrate"],
                    ]
                )
        except Exception as exc:
            print("json " + filename + ", load failed: " + str(exc))

    labels = (
        ["model", "platform", "serial", "filename", "encodedfile"]
        + key_list
        + ["meanbitrate"]
    )
    pdata = pd.DataFrame.from_records(settings, columns=labels, coerce_float=True)
    pdata.to_csv(f"{options.path}/{INDEX_FILE_NAME}", index=False)


def getData(options, recursive):
    index_filename = f"{options.path}/{INDEX_FILE_NAME}"
    try:
        data = pd.read_csv(index_filename)
    except Exception:
        if not os.path.exists(index_filename):
            sys.stderr.write(f"Warning: Recreating {index_filename}\n")
        else:
            sys.stderr.write(f"Error when reading {index_filename}, reindex\n")
        indexDirectory(options, recursive)
        try:
            data = pd.read_csv(index_filename)
        except Exception:
            sys.stderr.write(f"Failed to read index file: {index_filename}")
            exit(-1)
    return data


def derive_values(data):
    # get derived values: width/height (resolution), framerate. Configure
    # info has priority
    input_framerate_list = data["input.framerate"].tolist()
    configure_framerate_list = data["configure.framerate"].tolist()
    framerate_list = []
    for c_framerate, i_framerate in zip(configure_framerate_list, input_framerate_list):
        framerate = c_framerate if c_framerate != 0 else i_framerate
        framerate_list.append(framerate)
    data["framerate"] = framerate_list
    input_resolution_list = data["input.resolution"].tolist()
    configure_resolution_list = data["configure.resolution"].tolist()
    resolution_list = []
    width_list = []
    height_list = []
    for c_resolution, i_resolution in zip(
        configure_resolution_list, input_resolution_list
    ):
        resolution = c_resolution if not None and not "nan" else i_resolution
        resolution_list.append(resolution)
        width = height = 0
        if "x" in resolution:
            width, height = resolution.split("x")
        else:
            width=height=-1;
        width_list.append(width)
        height_list.append(height)
    data["resolution"] = resolution_list
    data["width"] = width_list
    data["height"] = height_list
    data["configure.bitrate"] = data["configure.bitrate"].apply(
        lambda x: encapp.convert_to_bps(x)
    )


def force_options(data, options):
    if options.codec:
        for val in data['configure.codec']:
            print(f"{val}")
        data = data.loc[data["configure.codec"].str.contains(options.codec, case=False, na=False)]
    if options.bitrate:
        ranges = options.bitrate.split("-")
        vals = []
        for val in ranges:
            bitrate = encapp.convert_to_bps(val)
            vals.append(int(bitrate))

        print(f"vals = {vals}")
        if len(vals) == 2:
            data = data.loc[
                (data["configure.bitrate"] >= vals[0])
                & (data["configure.bitrate"] <= vals[1])
            ]
        else:
            data = data.loc[data["configure.bitrate"] == vals[0]]
    if options.gop:
        data = data.loc[data["configure.iFrameInterval"] == options.gop]
    if options.fps:
        data = data.loc[data["framerate"] == options.fps]
    if options.size:
        sizes = options.size.split("x")
        print(f"sizes = {sizes}")
        if len(sizes) == 2:
            data = data.loc[
                (data["width"] == int(sizes[0])) & (data["height"] == int(sizes[1]))
            ]
        else:
            print(f"Filter on size: befeor = {len(data)}")
            data = data.loc[(data["width"] == sizes[0]) | (data["height"] == sizes[0])]
        print(f"Filter on size: after = {len(data)}")
    return data


def search(options):
    # get data from json files or csv cache
    data = getData(options, not options.no_rec)
    # get derived values
    derive_values(data)
    # force values from CLI
    data = force_options(data, options)
    return data


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument("path", nargs="?", help="Search path, default current")
    parser.add_argument("-s", "--size", default=None)  # WxH
    parser.add_argument("-c", "--codec", default=None)
    parser.add_argument("-b", "--bitrate", default=None)
    parser.add_argument("-g", "--gop", type=int, default=None)
    parser.add_argument("-f", "--fps", type=float, default=None)
    parser.add_argument("--no_rec", action="store_true")
    parser.add_argument("-i", "--index", action="store_true")
    parser.add_argument("-v", "--video", action="store_true")
    parser.add_argument("-p", "--print_data", action="store_true")

    options = parser.parse_args()
    if options.path is None:
        options.path = os.getcwd()

    if options.index:
        indexDirectory(options, not options.no_rec)

    data = search(options)
    data = data.sort_values(
        by=[
            "model",
            "configure.codec",
            "configure.iFrameInterval",
            "framerate",
            "height",
            "configure.bitrate",
        ]
    )
    if options.print_data:
        print(data[["filename", "encodedfile", "configure.codec", "configure.iFrameInterval", "framerate", "height", "configure.bitrate","meanbitrate"]])
    else:
        files = data["filename"].values
        for fl in files:
            directory, filename = os.path.split(fl)
            if options.video:
                video = data.loc[data["filename"] == fl]
                name = directory + "/" + video["encodedfile"].values[0]
            else:
                name = fl
            print(name)


if __name__ == "__main__":
    main()
