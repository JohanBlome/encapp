#!/usr/bin/env python3

import argparse
import pandas as pd
import seaborn as sns
import matplotlib as mlp
import matplotlib.pyplot as plt
import numpy as np
import math


def clean_name(name, debug=0):
    ret = name.translate(str.maketrans({",": "_", " ": "_"}))
    print(f"{name} -> {ret}")
    return ret


def plotAverageBitrate(data, options):
    pair = []
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["relStop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["relStop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["relStart"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]

    uniqheight = np.unique(data["height"])
    uniqbr = np.unique(data["bitrate"])
    uniqcodec = np.unique(data["codec"])
    for codec in uniqcodec:
        for height in uniqheight:
            for br in uniqbr:
                filtered = data.loc[
                    (data["codec"] == codec)
                    & (data["height"] == height)
                    & (data["bitrate"] == br)
                ]
                if len(filtered) > 0:
                    match = filtered.iloc[0]
                    realbr = match["average_bitrate"]
                    if math.isinf(realbr):
                        realbr = 0
                    pair.append([br, int(realbr), codec, height])

    bitrates = pd.DataFrame(
        pair, columns=["bitrate", "real_bitrate", "codec", "height"]
    )
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.lineplot(
        x=bitrates["bitrate"] / 1000,
        y=bitrates["real_bitrate"] / bitrates["bitrate"],
        style="codec",
        hue="height",
        data=bitrates,
        marker="o",
        ax=axs,
    )
    p.set_xlabel("Target bitrate (kbps)")
    p.set_ylabel("Bitrate ratio")
    plt.ticklabel_format(style="plain", axis="x")
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Bitrate in kbps")
    else:
        axs.set_title(f"{options.label} Bitrate in kbps")
    name = f"{options.output}.av_bitrate.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotProcRate(data, options):
    mean_input_fps = round(np.mean(data["av_proc_fps"]), 2)
    # plot the framerat
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]

    data["relStart_quant"] = (
        (data["relStart"] / (options.quantization * 1e6)).astype(int)
    ) * options.quantization
    slen = int(data["fps"].iloc[0])
    data["smooth_proctime"] = (
        (data["stoptime"].shift(-slen, axis="index", fill_value=0) - data["stoptime"])
    ) / (1e9 * slen)
    data = data.drop(data.tail(slen).index)

    st = np.min(data["starttime"])
    rel_end = np.max(data["relStop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["relStop"] <= (rel_end - options.skip_tail_sec * 1e9)]

    if options.skip_head_sec > 0:
        data = data.loc[data["relStart"] >= (options.skip_head_sec * 1e9)]
    data = data.loc[(data["smooth_proctime"] > 0) & (data["starttime"] > 0)]
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.lineplot(  # noqa: F841
        x=data["relStart_quant"] / 1e3,
        y=1.0 / data["smooth_proctime"],
        hue="codec",
        ci="sd",
        data=data,
        ax=axs,
    )
    # p.set_ylim(0, 90)
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Framerate ( {mean_input_fps} fps )")
    else:
        axs.set_title(f"{options.label} Framerate")
    if options.limit:
        axs.set_ylim(0, 15)
    axs.set(xlabel="Time (sec)", ylabel="Average processing fps")
    axs.legend(loc="best", fancybox=True, framealpha=0.5)
    axs.get_yaxis().set_minor_locator(mlp.ticker.AutoMinorLocator())
    axs.grid(b=True, which="minor", color="gray", linewidth=0.5)
    name = f"{options.output}.procrate.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotLatency(data, options):
    # plot the framerate
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["relStop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["relStop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["relStart"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["proctime"] > 0) & (data["starttime"] > 0)]
    data["relStart_quant"] = (
        (data["relStart"] / (options.quantization * 1e6)).astype(int)
    ) * options.quantization
    st = np.min(data["starttime"])
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.lineplot(  # noqa: F841
        x=data["relStart_quant"] / 1e3,
        y=data["proctime"] / 1e6,
        hue="codec",
        ci="sd",
        data=data,
        ax=axs,
    )
    # p.set_ylim(0, 90)
    axs.set_title(f"{options.label} Latency (ms)")
    axs.legend(loc="best", fancybox=True, framealpha=0.5)
    axs.get_yaxis().set_minor_locator(mlp.ticker.AutoMinorLocator())
    axs.set(xlabel="Time (sec)", ylabel="Latency (msec)")
    axs.grid(b=True, which="minor", color="gray", linewidth=0.5)
    name = f"{options.output}.latency.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotFrameRate(data, options):
    mean_input_fps = round(np.mean(data["fps"]), 2)
    # plot the framerate
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["relStop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["relStop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["relStart"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.lineplot(  # noqa: F841
        x=data["relPts"] / 1e6, y="av_fps", hue="codec", ci="sd", data=data, ax=axs
    )
    # p.set_ylim(0, 90)
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Framerate ( {mean_input_fps} fps )")
    else:
        axs.set_title(f"{options.label} Framerate")
    axs.legend(loc="best", fancybox=True, framealpha=0.5)
    axs.get_yaxis().set_minor_locator(mlp.ticker.AutoMinorLocator())
    axs.grid(b=True, which="minor", color="gray", linewidth=0.5)
    name = f"{options.output}.framerate.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotFrameSize(data, options):
    # framesize
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["relStop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["relStop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["relStart"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]

    mean_fr = round(np.mean(data["size"]) / 1000, 2)
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.scatterplot(
        x=data["pts"] / 1e6, y=data["size"] / 1000, hue="codec", data=data, ax=axs
    )
    p.set_xlabel("time (sec)")
    p.set_ylabel("size (kb)")
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Framesize in kb ( {mean_fr} kb )")
    else:
        axs.set_title(f"{options.label} Framesize in kb )")
    name = f"{options.output}.framesizes.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotBitrate(data, options):
    # Bitrate
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["relStop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["relStop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["relStart"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]

    mean_br = round(np.mean(data["bitrate_per_frame_bps"] / 1000.0), 2)
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.scatterplot(
        x=data["pts"] / 1e6,
        y=data["bitrate_per_frame_bps"] / 1000,
        hue="codec",
        data=data,
        ax=axs,
    )
    p = sns.lineplot(
        x=data["pts"] / 1e6,
        y=data["bitrate_per_frame_bps"].rolling(30).mean() / 1000,
        hue="bitrate",
        style="codec",
        data=data,
        ax=axs,
    )
    p.set_xlabel("time (sec)")
    p.set_ylabel("size (kbps)")
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Bitrate in kbps ( {mean_br} kbps )")
    else:
        axs.set_title(f"{options.label} Bitrate in kbps )")
    name = f"{options.output}.bitrate.png"
    plt.savefig(name.replace(" ", "_"), format="png")


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
    parser.add_argument("files", nargs="*", help="file to analyze")
    parser.add_argument("--label", default="")
    parser.add_argument("-o", "--output", default="", help="output name")
    parser.add_argument(
        "--limit", action="store_true", help="add sane axis limits for plots"
    )
    parser.add_argument("--show", action="store_true", help="show the plots")
    parser.add_argument("-fs", "--frame_size", action="store_true")
    parser.add_argument("-br", "--bit_rate", action="store_true")
    parser.add_argument("-r", "--frame_rate", action="store_true")
    parser.add_argument("-a", "--av_frame_rate", action="store_true")
    parser.add_argument("-l", "--latency", action="store_true")
    parser.add_argument(
        "--quantization",
        default="60",
        type=int,
        help="Quantize time in ms so multiple runs could more easily be calulated together",
    )
    parser.add_argument("--keep_na_codec", action="store_true")
    parser.add_argument("--skip_tail_sec", default=0, type=float)
    parser.add_argument("--skip_head_sec", default=0, type=float)
    options = parser.parse_args()

    return options


def main():
    """
    Calculate stats for videos based on parsing individual frames
    with ffprobe frame parser.
    Can output data for a single file or aggregated data for several files.
    """
    options = parse_args()
    data = None
    if len(options.files) == 1 and len(options.output) == 0:
        options.output = options.files[0]
    for file in options.files:
        input_data = pd.read_csv(file)
        if data is not None:
            data = pd.concat([data, input_data])
        else:
            data = input_data

    sns.set_style("whitegrid")
    sns.set(rc={"xtick.bottom": True, "ytick.left": True})
    # `fps` column contains the framerate calculated from the
    # input-reported timings (the input/camera framerate)
    mean_input_fps = "na"
    if "fps" in data:
        mean_input_fps = round(np.mean(data["fps"]), 2)
    # `proc_fps` column contains the framerate calculated from the
    # system-reported timings (the gettimeofday framerate)
    mean_sys_fps = "na"
    if "proc_fps" in data:
        mean_sys_fps = round(np.mean(data["proc_fps"]), 2)
    print(f"mean fps: {mean_input_fps}")
    print(f"mean system fps: {mean_sys_fps}")

    # Average proc time
    if options.bit_rate and len(options.files) > 1 and options.av_frame_rate:
        plotAverageBitrate(data, options)

    if options.frame_rate:
        plotFrameRate(data, options)
        plotProcRate(data, options)
    if options.bit_rate:
        plotBitrate(data, options)
    if options.frame_size:
        plotFrameSize(data, options)
    if options.latency:
        plotLatency(data, options)
    if options.show:
        plt.show()


if __name__ == "__main__":
    main()
