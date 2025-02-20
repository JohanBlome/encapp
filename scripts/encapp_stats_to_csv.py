#!/usr/bin/env python3

import json
import argparse
import pandas as pd
import numpy as np
import encapp as ep
import os
import re


def parse_resolution(resolution):
    reg = "([0-9]*).([0-9]*)"
    match = re.search(reg, resolution)
    if match:
        return [int(match.group(1)), int(match.group(2))]

    return []


def parse_encoding_data(json, inputfile, debug=0):
    if debug > 0:
        print(f"Parse encoding data: {inputfile}")
    try:
        data = pd.DataFrame(json["frames"])
        test = json["test"]

        # Basic data
        data = data.loc[data["proctime"] > 0]
        start_pts = data.iloc[0]["pts"]
        start_ts = data.iloc[0]["starttime"]
        start_stop = data.iloc[0]["stoptime"]
        data["source"] = inputfile
        data["test_id"] = test["common"]["id"]
        data["test_description"] = test["common"]["description"]
        data["codec"] = test["configure"]["codec"]
        data["description"] = test["common"]["description"]
        data["camera"] = (test["input"]["filepath"].find('filepath: "camera"')) > 0
        data["test"] = test["common"]["id"]
        data["bitrate"] = ep.convert_to_bps(test["configure"]["bitrate"])
        resolution = test["configure"]["resolution"]
        if len(resolution) == 0:
            resolution = test["input"]["resolution"]

        data["height"] = parse_resolution(resolution)[1]
        fps = test["configure"]["framerate"]
        if fps == 0:
            fps = test["input"]["framerate"]
        data["fps"] = fps

        # Derived Data
        data["rel_pts"] = data["pts"] - start_pts
        data["rel_start"] = data["starttime"] - start_ts
        data["rel_stop"] = data["stoptime"] - start_stop

        if "pts" in data:
            data["duration_ms"] = round(
                (data["pts"].shift(-1, axis="index", fill_value=0) - data["pts"])
                / 1000,
                2,
            )
            data.loc[data["duration_ms"] < 0.0, "duration_ms"] = 0.0
            data["bitrate_per_frame_bps"] = (data["size"] * 8.0) / (
                data["duration_ms"] / 1000.0
            )
            # drop last row
            data.drop(data.tail(1).index, inplace=True)
            data["mean_bitrate"] = np.mean(data["bitrate_per_frame_bps"])

            fps = int(round(fps))
            data["av_bitrate"] = (
                data["bitrate_per_frame_bps"]
                .rolling(fps, min_periods=fps, win_type=None)
                .sum()
                / fps
            )
            data.replace([np.inf, -np.inf], 0, inplace=True)
            data.replace(np.nan, 0, inplace=True)
            data["av_bitrate"] = data["av_bitrate"].astype(int)
            data["real_fps"] = round(1000.0 / (data["duration_ms"]), 2)
            # delete the last item
            data = data.loc[data["starttime"] > 0]
            data["av_fps"] = (
                data["real_fps"].rolling(fps, min_periods=fps, win_type=None).sum()
                / fps
            )
            data, __ = calc_infligh(data, start_ts)
            data["proc_fps"] = (data["inflight"] * 1.0e9) / data["proctime"]
            data["av_proc_fps"] = (
                data["proc_fps"].rolling(fps, min_periods=fps, win_type=None).sum()
                / fps
            )
            data["av_fps"] = data["av_fps"].fillna(data["fps"])
            data["av_proc_fps"] = data["av_proc_fps"].fillna(data["proc_fps"])

        data.fillna(pd.Timedelta(seconds=0), inplace=True)
    except Exception as ex:
        print(f"Encode data parsing failed: {ex}")
        return None
    if debug > 2:
        print(f'data = "{data}"')

    return data


def parse_decoding_data(json, inputfile, debug=0):
    if debug > 0:
        print("Parse decoding data")
    decoded_data = None
    try:
        # Must sort according to pts
        decoded_data = pd.DataFrame(json["decoded_frames"])
        decoded_data["source"] = inputfile
        if len(decoded_data) > 0:
            test = json["test"]
            codec = json.get("decoder", "na")
            if len(codec) <= 0:
                codec = "na"
            fps = 30
            try:
                fps = json["decode_media_format"]["frame-rate"]
            except Exception:
                fps = test["input"]["framerate"]
            decoded_data = decoded_data.sort_values("pts")
            start_pts = decoded_data.iloc[0]["pts"]
            start_ts = decoded_data.iloc[0]["starttime"]
            start_stop = decoded_data.iloc[0]["stoptime"]
            decoded_data = decoded_data.loc[decoded_data["proctime"] > 0]
            # decoded_data["pts_sec"] = pd.to_timedelta(decoded_data["pts"], unit="s")

            decoded_data["rel_pts"] = decoded_data["pts"] - start_pts
            decoded_data["rel_start"] = decoded_data["starttime"] - start_ts
            decoded_data["rel_stop"] = decoded_data["stoptime"] - start_stop

            # data = decoded_data.loc[decoded_data["size"] != "0"]
            decoded_data["camera"] = (
                test["input"]["filepath"].find('filepath: "camera"')
            ) > 0
            # decoded_data["bitrate"] = ep.convert_to_bps(test["configure"]["bitrate"])
            # oh no we may have b frames...
            decoded_data["duration_ms"] = round(
                (
                    decoded_data["pts"].shift(-1, axis="index", fill_value=0)
                    - decoded_data["pts"]
                )
                / 1000,
                2,
            )

            decoded_data["fps"] = round(1000.0 / (decoded_data["duration_ms"]), 2)

            fps = int(round(fps))
            decoded_data["av_fps"] = (
                decoded_data["fps"].rolling(fps, min_periods=fps, win_type=None).sum()
                / fps
            )
            decoded_data, __ = calc_infligh(decoded_data, start_ts)
            decoded_data["proc_fps"] = (
                decoded_data["inflight"] * 1.0e9
            ) / decoded_data["proctime"]
            decoded_data["av_proc_fps"] = (
                decoded_data["proc_fps"]
                .rolling(fps, min_periods=fps, win_type=None)
                .sum()
                / fps
            )
            decoded_data["av_fps"] = decoded_data["av_fps"].fillna(decoded_data["fps"])
            decoded_data["av_proc_fps"] = decoded_data["av_proc_fps"].fillna(
                decoded_data["proc_fps"]
            )
            decoded_data["test"] = test["common"]["id"]
            decoded_data["description"] = test["common"]["description"]
            decoded_data["codec"] = codec
            try:
                decoded_data["height"] = json["decoder_media_format"]["height"]
            except Exception:
                print("Height missing in decoded data")
                resolution = test["input"]["resolution"]
                decoded_data["height"] = parse_resolution(resolution)[1]
                decoded_data["height"] = "unknown height"
            decoded_data.ffill(inplace=True)
    except Exception as ex:
        print(f"Failed to parse decode data for {inputfile}: {ex}")
        decoded_data = None

    return decoded_data


def parse_named_timestamps(json, inputfile, debug=0):
    data = None
    try:
        data = pd.DataFrame(json["named_timestamps"])
        if len(data) > 0:
            data["source"] = inputfile

            # transpose to single column
            mdata = data.melt(id_vars="source", var_name="named_timestamp", value_name="timestamp")
            data = mdata.dropna(subset="timestamp")
    except Exception as ex:
        print(f"Failed to parse decode data for {inputfile}: {ex}")
    return data


def parse_gpu_data(json, inputfile, debug=0):
    if debug > 0:
        print("Parse gpu data")
    gpu_data = None
    try:
        gpu_data = pd.DataFrame(json["gpu_data"]["gpu_load_percentage"])
        if len(gpu_data) > 0:
            gpuclock_data = pd.DataFrame(json["gpu_data"]["gpu_clock_freq"])
            gpu_max_clock = int(json["gpu_data"]["gpu_max_clock"])
            gpu_data["clock_perc"] = (
                100.0 * gpuclock_data["clock_MHz"].astype(float) / gpu_max_clock
            )
            gpu_data = gpu_data.merge(gpuclock_data)
            gpu_model = json["gpu_data"]["gpu_model"]
            gpu_data["source"] = inputfile
            gpu_data["gpu_max_clock"] = gpu_max_clock
            gpu_data["gpu_model"] = gpu_model
            gpu_data.fillna(0)
    except Exception as ex:
        print(f"GPU parsing failed: {ex}")
        pass
    return gpu_data


def calc_infligh(frames, time_ref):
    """Calculate how many frames have start but not yet stopped
    during a certain period.
    Remove the offset using time_ref"""

    sources = pd.unique(frames["source"])
    coding = []
    for source in sources:
        # Calculate how many frames starts encoding before a frame has finished
        # relying on the accurace of the System.nanoTime()
        inflight = []
        filtered = frames.loc[frames["source"] == source]
        start = np.min(filtered["starttime"])
        stop = np.max(filtered["stoptime"])
        # Calculate a time where the start offset (if existing) does not
        # blur the numbers
        coding.append([source, start - time_ref, stop - time_ref])
        for row in filtered.iterrows():
            start = row[1]["starttime"]
            stop = row[1]["stoptime"]
            intime = filtered.loc[
                (filtered["stoptime"] > start) & (filtered["starttime"] < stop)
            ]
            count = len(intime)
            inflight.append(count)
        frames.loc[frames["source"] == source, "inflight"] = inflight

    labels = ["source", "starttime", "stoptime"]
    concurrent = pd.DataFrame.from_records(coding, columns=labels, coerce_float=True)

    # calculate how many new encoding are started before stoptime
    inflight = []
    for row in concurrent.iterrows():
        start = row[1]["starttime"]
        stop = row[1]["stoptime"]
        count = len(
            concurrent.loc[
                (concurrent["stoptime"] > start) & (concurrent["starttime"] < stop)
            ]
        )
        inflight.append(count)
    concurrent["conc"] = inflight
    return frames, concurrent


def clean_name(name, debug=0):
    ret = name.translate(str.maketrans({",": "_", " ": "_"}))
    return ret


def parse_args():
    parser = argparse.ArgumentParser(description="")
    parser.add_argument(
        "--debug",
        action="count",
        dest="debug",
        default=0,
        help="Increase verbosity (use many times for more)",
    )
    parser.add_argument(
        "--quiet",
        action="store_const",
        dest="debug",
        const=-1,
        help="Zero verbosity",
    )
    parser.add_argument(
        "--model",
        dest="model",
        default=None,
        help="If info is missing tag it with this",
    )
    parser.add_argument("files", nargs="*", help="file to analyze")
    parser.add_argument("--label", default="")

    options = parser.parse_args()

    return options


def main():
    """
    Calculate stats for videos based on parsing individual frames
    with ffprobe frame parser.
    Can output data for a single file or aggregated data for several files.
    """
    options = parse_args()
    current_dir = None
    device_info = None
    for filename in options.files:
        with open(filename) as json_file:
            # see if there is device info avalable
            # read device info results
            directory = os.path.dirname(filename)
            if current_dir is None or current_dir != directory:
                device_info_file = os.path.join(directory, "device.json")
                if os.path.exists(device_info_file):
                    current_dir = directory
                    with open(device_info_file, "r") as input_file:
                        device_info = json.load(input_file)
                else:
                    device_info = {}

            alldata = json.load(json_file)
            if "frames" in alldata and len(alldata["frames"]) > 0:
                encoding_data = parse_encoding_data(alldata, filename, options.debug)
                if encoding_data is None:
                    print(f"Failed to parse encoding data in {filename}")
                    continue
                if device_info is not None:
                    model = device_info.get("props", {}).get("ro.product.model", "")
                    if options.model:
                        model = options.model
                    encoding_data["model"] = model
                    encoding_data["platform"] = device_info.get("props", {}).get(
                        "ro.board.platform", ""
                    )
                    encoding_data["serial"] = device_info.get("props", {}).get(
                        "ro.serialno", ""
                    )
                if encoding_data is not None and len(encoding_data) > 0:
                    encoding_data.to_csv(f"{filename}_encoding_data.csv")

            if (
                "decoded_frames" in alldata
                and alldata["decoded_frames"] is not None
                and len(alldata["decoded_frames"]) > 0
            ):
                decoded_data = parse_decoding_data(alldata, filename, options.debug)
                if decoded_data is not None and len(decoded_data) > 0:
                    decoded_data.to_csv(f"{filename}_decoding_data.csv")
            if "gpu_data" in alldata:
                gpu_data = parse_gpu_data(alldata, filename, options.debug)
                if gpu_data is not None and len(gpu_data) > 0:
                    gpu_data.to_csv(f"{filename}_gpu_data.csv")

            if "named_timestamps" in alldata:
                timestamps = parse_named_timestamps(alldata, filename, options.debug)
                if timestamps is not None and len(timestamps) > 0:
                    timestamps.to_csv(f"{filename}_named_ts_timestamps.csv")


if __name__ == "__main__":
    main()
