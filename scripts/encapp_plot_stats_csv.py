#!/usr/bin/env python3

import argparse
import pandas as pd
import seaborn as sns
import matplotlib as mlp
import matplotlib.pyplot as plt
import numpy as np
import math
import os.path
import itertools
from scipy import stats


def clean_name(name, debug=0):
    ret = name.translate(str.maketrans({",": "_", " ": "_"}))
    return ret


def plotAverageBitrate(data, options):
    pair = []
    uniqheight = np.unique(data["height"])
    uniqbr = np.unique(data["bitrate"])
    uniqcodec = np.unique(data["codec"])
    uniqdescr = np.unique(data["description"])
    for codec in uniqcodec:
        cfilt = data.loc[(data["codec"] == codec)]
        for descr in uniqdescr:
            dfilt = cfilt.loc[(cfilt["description"] == descr)]
            for height in uniqheight:
                hfilt = dfilt.loc[(dfilt["height"] == height)]
                for br in uniqbr:
                    filtered = hfilt.loc[hfilt["bitrate"] == br]
                    if len(filtered) > 0:
                        match = filtered.iloc[0]
                        realbr = match["mean_bitrate"]
                        if math.isinf(realbr):
                            realbr = 0
                        pair.append([br, int(realbr), codec, height, descr])

    bitrates = pd.DataFrame(
        pair, columns=["bitrate", "real_bitrate", "codec", "height", "description"]
    )
    fig = plt.subplots(nrows=1, dpi=100)
    style = options.split_field
    p = sns.lineplot(
        x=bitrates["bitrate"] / 1000,
        y=bitrates["real_bitrate"] / bitrates["bitrate"],
        style=style,
        hue="height",
        data=bitrates,
        marker="o",
    )
    axs = p.axes
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
    # Calculate average based on first ts and last ts divided by frame count
    first_ts = data["starttime"].min()
    last_ts = data["stoptime"].max()
    calculated_average_rate = round(
        data["frame"].count() / ((last_ts - first_ts) / 1e9), 2
    )
    # plot the framerat
    u_codecs = np.unique(data["codec"])
    if not options.keep_na_codec and len(u_codecs) > 1:
        data = data.loc[data["codec"] != "na"]

    data["rel_start_quant"] = (
        (data["rel_start"] / (options.quantization * 1e6)).astype(int)
    ) * options.quantization

    slen = int(data["fps"].iloc[0])
    data["smooth_proctime"] = (
        data["stoptime"].shift(-slen, axis="index", fill_value=0) - data["stoptime"]
    ) / (1e9 * slen)
    data = data.drop(data.tail(slen).index)

    # st = np.min(data["starttime"])
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]

    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] >= (options.skip_head_sec * 1e9)]
    data = data.loc[(data["smooth_proctime"] > 0) & (data["starttime"] > 0)]
    hue = "codec"
    fig = plt.figure()
    hue = options.split_field
    p = sns.lineplot(  # noqa: F841
        x=data["rel_start_quant"] / 1e3,
        y=1.0 / data["smooth_proctime"],
        hue=hue,
        errorbar="sd",
        data=data,
    )
    # p.set_ylim(0, 90)
    axs = p.axes
    if "inflight" in data.columns:
        secAxis = axs.twinx()
        sns.lineplot(
            x=data["rel_start_quant"] / 1e3,
            y=data["inflight"],
            hue=hue,
            linestyle="--",
            data=data,
            legend=None,
        )

    if len(options.files) == 1:
        axs.set_title(
            f"{options.label} Processing framerate, wall time average {calculated_average_rate} fps"
        )
    else:
        axs.set_title(f"{options.label} Processing framerate")

    axs.legend(loc="best", fancybox=True, framealpha=0.5)
    # get legends and add average fps
    legends = p.axes.get_legend().texts
    for text in legends:
        tmp = data.loc[data[options.split_field] == text.get_text()]
        average = round(
            len(tmp) * 1e9 / (tmp["stoptime"].max() - tmp["starttime"].min()), 1
        )
        text.set_text(f"{text.get_text()} - {average} fps")
    axs.set(xlabel="Time (sec)", ylabel="Average processing fps")
    axs.get_yaxis().set_minor_locator(mlp.ticker.AutoMinorLocator())
    axs.grid(visible=True, which="minor", color="gray", linewidth=0.5)
    name = f"{options.output}.procrate.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotLatency(data, options):
    if "codec" in data.columns:
        u_codecs = np.unique(data["codec"])
        if not options.keep_na_codec and len(u_codecs) > 1:
            data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["proctime"] > 0) & (data["starttime"] > 0)]

    # filter outliers
    data = data[np.abs(stats.zscore(data["proctime"])) < 3]
    data["rel_start_quant"] = (
        (data["rel_start"] / (options.quantization * 1e6)).astype(int)
    ) * options.quantization
    fig = plt.figure()

    hue = options.split_field
    proctime = "proctime"
    if options.rolling > 1:
        # Rolling average
        for item in data[hue].unique():
            data.loc[data[hue] == item, "proctime_rolling"] = (
                data.loc[data[hue] == item]["proctime"]
                .rolling(window=options.rolling)
                .mean()
            )
        proctime = "proctime_rolling"

    # drop na
    data = data.dropna(subset=["proctime"])
    average_lat_msec = round(data["proctime"].mean() / 1e6, 2)
    p50_msec = int(round(data["proctime"].quantile(0.5) / 1e6, 0))
    p95_msec = int(round(data["proctime"].quantile(0.95) / 1e6, 0))
    p99_msec = int(round(data["proctime"].quantile(0.99) / 1e6, 0))
    p = sns.lineplot(  # noqa: F841
        x=data["rel_start_quant"] / 1e3,
        y=data[proctime] / 1e6,
        hue=hue,
        errorbar="sd",
        data=data,
    )
    axs = p.axes
    # p.set_ylim(0, 90)
    axs.set_title(
        f"{options.label}\nLatency, mean: {average_lat_msec} msec, p50,p95,p99: {p50_msec}, {p95_msec}, {p99_msec}"
    )
    axs.legend(loc="best", fancybox=True, framealpha=0.5)
    axs.get_yaxis().set_minor_locator(mlp.ticker.AutoMinorLocator())
    axs.set(xlabel="Time (sec)", ylabel="Latency (msec)")
    axs.grid(visible=True, which="minor", color="gray", linewidth=0.5)
    name = f"{options.output}.latency.png"
    plt.savefig(name.replace(" ", "_"), format="png")

    fig = plt.figure()
    # Show the mean, p50,90,99
    tmp = []
    text = ""
    print("Small proc:",data.loc[data["proctime"] < 1] )
    for item in data[hue].unique():
        itemdata = data.loc[data[hue] == item]
        average_lat_msec = round(itemdata["proctime"].mean() / 1e6, 2)
        p50_msec = round(itemdata["proctime"].quantile(0.5) / 1e6, 1)
        p95_msec = round(itemdata["proctime"].quantile(0.95) / 1e6, 1)
        p99_msec = round(itemdata["proctime"].quantile(0.99) / 1e6, 1)
        tmp.append([item, average_lat_msec, p50_msec, p95_msec, p99_msec])
    meandata = pd.DataFrame(tmp, columns=[hue, "average", "p50", "p90", "p99"])
    meandata.sort_values(["p50"], inplace=True)
    meandata["index"] = np.arange(1, len(meandata) + 1)
    meanmelt = pd.melt(meandata, ["index", hue])
    p = sns.lineplot(x="variable", y="value", hue=hue, data=meanmelt)
    ymax = meanmelt["value"].max()
    xmax = meanmelt["index"].max()
    for num in meanmelt["index"].unique():
        item = meanmelt.loc[meanmelt["index"] == num].iloc[0][hue]
        text += f"{num}: {item}\n"
    axs = p.axes
    axs.set_title("Averaged values")

    axs.set(ylabel="Latency (msec)")
    axs.set(xlabel="Stat value")
    name = f"{options.output}.latency.png"
    name = f"{options.output}.latency.stats.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotFrameRate(data, options):
    mean_input_fps = round(np.mean(data["fps"]), 2)
    # plot the framerate
    if "codec" in data:
        u_codecs = np.unique(data["codec"])
        if not options.keep_na_codec and len(u_codecs) > 1:
            data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] > (options.skip_head_sec * 1e9)]

    data["rel_start_quant"] = (
        (data["rel_start"] / (options.quantization * 1e6)).astype(int)
    ) * options.quantization
    fig = plt.figure()
    w, h = options.size.lower().lower().split("x")
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]
    hue = options.split_field
    p = sns.lineplot(  # noqa: F841
        x=data["rel_start_quant"] / 1e3,
        y="av_fps",
        hue=hue,
        errorbar="sd",
        data=data,
    )

    # p.set_ylim(0, 90)
    axs = p.axes
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Average framerate ( {mean_input_fps} fps )")
    else:
        axs.set_title(f"{options.label} Average framerate")
    axs.legend(loc="best", fancybox=True, framealpha=0.5)
    axs.get_yaxis().set_minor_locator(mlp.ticker.AutoMinorLocator())
    axs.grid(visible=True, which="minor", color="gray", linewidth=0.5)
    name = f"{options.output}.framerate.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotFrameSize(data, options):
    if "iframe" not in data.columns:
        print("ERROR: no iframe annotation in data")
        return
    # framesize
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]

    mean_fr = round(np.mean(data["size"]) / 1000, 2)

    data.loc[data["iframe"] == 0, "frame type"] = "P frames"
    data.loc[data["iframe"] == 1, "frame type"] = "I frames"

    u_frame_types = np.unique(data["frame type"])
    palette = ["r", "b"]
    if len(u_frame_types) < 2:
        print(f"There is only one type of frames: {u_frame_types[0]}")
        palette = ["g"]

    fig = plt.figure()
    if options.split_field and options.split_field in list(data.columns):
        data = data.sort_values(by=[options.split_field, "codec", "height"])
        u_test_description = np.unique(data[options.split_field].astype(str))
        col_wrap = len(u_test_description)
        fg = sns.relplot(
            x=data["pts"] / 1e6,
            y=data["size"] / 1000,
            style="codec",
            hue="frame type",
            col=options.split_field,
            col_wrap=col_wrap,
            data=data,
            kind="scatter",
            palette=palette,
        )
        p = fg.axes
        while hasattr(p, "__len__"):
            p = p[0]
    else:
        p = sns.scatterplot(
            x=data["pts"] / 1e6,
            y=data["size"] / 1000,
            style="codec",
            hue="frame type",
            data=data,
            palette=["r", "b"],
        )
        axs = p.axes
        if len(options.files) == 1:
            axs.set_title(f"{options.label} I/P Framesize in kb ( {mean_fr} kb )")
        else:
            axs.set_title(f"{options.label} I/P Framesize in kb")

    p.set_xlabel("time (sec)")
    p.set_ylabel("size (kb)")
    # plt.legend(loc='upper left', title='Teami')
    name = f"{options.output}.framesizes.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotBitrate(data, options):
    if "bitrate" not in data:
        print("ERROR: not bitrate in data")
        return
    # Bitrate
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["pts"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["pts"] <= (rel_end - options.skip_tail_sec * 1e6)]
    if options.skip_head_sec > 0:
        data = data.loc[data["pts"] > (options.skip_head_sec * 1e6)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]
    data = data.reset_index()

    data["Average kbps"] = (data["av_bitrate"] / 1000).astype("int")
    data["Bitrate in kbps"] = (data["bitrate"] / 1000).astype("int")

    # plot per codec
    mean_br = round(np.mean(data["bitrate_per_frame_bps"] / 1000.0), 2)

    u_codecs = np.unique(data["codec"])
    # u_model = pd.unique((data.loc[data["model"] != ""])["model"])
    set_label = True
    fig = plt.figure()
    if options.rename_codec is None:
        data = data.sort_values(by=["description", "codec", "height", "bitrate"])
        if options.split_Field and options.split_field in list(data.columns):
            # maybe filter on models
            u_test_description = np.unique(data["description"].astype(str))
            col_wrap = len(u_test_description)
            counter = 0

            fg = sns.relplot(
                x=data["pts"] / 1e6,
                y="Average kbps",
                hue="codec",
                style="model",
                col=options.split_field,
                col_wrap=col_wrap,
                kind="line",
                data=data,
                palette="dark",
            )
            p = fg.axes
            while hasattr(p, "__len__"):
                p = p[0]
            set_label = False
        else:
            # maybe filter on models
            counter = 0
            for codec in u_codecs:
                filt = data.loc[data["codec"] == codec]
                p = sns.lineplot(
                    x=filt["pts"] / 1e6,
                    y="Average kbps",
                    hue="Bitrate in kbps",
                    style="model",
                    data=filt,
                    palette="dark",
                )
                counter += 1

    else:
        # plot per model with codecs hard coded to be same
        counter = 0

        p = sns.lineplot(
            x=filt["pts"] / 1e6,
            y="Average kbps",
            label=f"{codec}",
            hue="model",
            style="Bitrate in kbps",
            data=data,
            palette="dark",
        )

    p.set_xlabel("time (sec)")
    p.set_ylabel("size (kbps)")
    plt.ticklabel_format(style="plain", axis="x")
    if set_label:
        if len(options.files) == 1:
            p.set_title(f"{options.label}, Bitrate in kbps ( {mean_br} kbps )")
        else:
            p.set_title(f"{options.label}, Bitrate in kbps ")
    name = f"{options.output}.bitrate.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotTestNumbers(data):
    # plot the number of test per test description
    u_descrs = data["description"].unique()
    items = []
    for desc in u_descrs:
        filt = data.loc[(data["description"] == desc) & (data["frame"] == 1)]
        num = len(filt)
        items.append([desc, num])

    data = pd.DataFrame(items, columns=["description", "num"])

    sns.barplot(x="description", y="num", data=data)
    plt.savefig("test_numbers.png", format="png")


def plot_cpu_load(data_: pd.DataFrame, options):
    # timestamp_ns,sum,source
    # per source remove duplicates
    if data_ is None:
        return
    data = data_.copy()
    ts_0 = data["timestamp_ns"].min()
    data["ts_sec_0"] = (data["timestamp_ns"] - ts_0) / 1e9
    data["ts_sec"] = 0
    for source in data["source"].unique():
        # Adjust the times
        t0 = data.loc[data["source"] == source]["ts_sec_0"].min()
        data.loc[data["source"] == source, "ts_sec"] = (
            data.loc[data["source"] == source, "ts_sec_0"] - t0
        ).round()
    # Let every dataset start from 0
    # Scale using the first value

    data = data.drop_duplicates(["source", "sum"])
    perf_t0 = data["sum"].iloc[0]
    data["sum_rel"] = data["sum"] - perf_t0
    data = data.fillna(0)
    data["sum_rel_diff"] = (data["sum_rel"].diff() + perf_t0) / perf_t0
    data["ts_msec"] = (data["ts_sec"] * 1000).astype(int)

    hue = options.split_field
    p = sns.lineplot(x="ts_sec", y="sum_rel_diff", hue=hue, data=data)

    p.set_title(
        f"{options.label}, Cpu load. Aggregated freq * time_in_state as fraction of initial load over all cpus"
    )
    name = f"{options.output}.cpu_load.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plot_named_timestamps(data, enc_dec_data, options):
    print(f"{len(data)=}, {len(enc_dec_data)=}")
    # find pars and calculate diff
    names = data["named_timestamp"].unique()
    if "original_media" not in data.columns:
        data["original_media"] = ""

    output = pd.DataFrame()
    for name in names:
        limited = pd.DataFrame(data.loc[data["named_timestamp"] == name])
        if len(limited) % 2 != 0:
            print("Data point is missing, not even")
        else:
            ts_min = limited["timestamp"].min()
            limited["timestamp"] = limited["timestamp"] - ts_min
            limited["diff"] = limited["timestamp"].astype(int).diff().shift(-1)
            limited = limited.iloc[::2]
            output = pd.concat([output, limited])
    output["time ms"] = output["diff"] / 1000000

    extra = []
    for source in data["source"].unique():
        print(f"{enc_dec_data.loc[enc_dec_data['frame'] == 0]}")
        print(f"frame 0: {enc_dec_data.loc[(enc_dec_data['source'] == source) ]['proctime']}")
        proctime = enc_dec_data.loc[
            (enc_dec_data["source"] == source)]["proctime"].values[0]
        original_media = enc_dec_data.loc[enc_dec_data["source"] == source][
            "original_media"
        ].values[0]
        print(f"{proctime=} {original_media=}")
        output.loc[output["source"] == source, "original_media"] = original_media

        extra.append(
            {
                "source": source,
                "original_media": original_media,
                "named_timestamp": "first.frame.transcode",
                "diff": proctime,
                "time ms": proctime / 1000000.0,
            }
        )

        complete_sum = output.loc[output["source"] == source]["diff"].sum() + proctime
        extra.append(
            {
                "source": source,
                "original_media": original_media,
                "named_timestamp": "timestamp.sum",
                "diff": complete_sum,
                "time ms": complete_sum / 1000000.0,
            }
        )

    ff = pd.DataFrame(extra)
    output = pd.concat([output, ff])
    output = output.ffill()
    print(output)
    p = sns.barplot(
        x="named_timestamp", y="time ms", hue=options.split_field, data=output
    )
    plt.xticks(rotation=70)
    plt.suptitle(f"{options.label} - Timestamp times in ms")
    axs = p.axes
    axs.legend(loc="best", fancybox=True, framealpha=0.5)
    axs.get_yaxis().set_minor_locator(mlp.ticker.AutoMinorLocator())
    axs.grid(visible=True, which="both")
    axs.grid(visible=True, which="minor", color="black", linewidth=0.1)

    plt.tight_layout()
    name = f"{options.output}.named_ts.png"
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
    parser.add_argument("--rename_codec", default=None)
    parser.add_argument("-o", "--output", default="", help="output name")
    parser.add_argument(
        "--limit", action="store_true", help="add sane axis limits for plots"
    )
    parser.add_argument("--show", action="store_true", help="show the plots")
    parser.add_argument("-fs", "--frame_size", action="store_true")
    parser.add_argument("-br", "--bit_rate", action="store_true")
    parser.add_argument(
        "-r",
        "--frame-rate",
        dest="frame_rate",
        action="store_true",
        help="Plots the framerate for each individual frame. It is derived from "
        "the actual processing time (time from pushing the buffer until it "
        "is encodede/decoded). Unless running in realtime this will higher than average throughput.",
    )
    parser.add_argument(
        "-l",
        "--latency",
        action="store_true",
        help="Plot the timestamp when a buffer is send to the encoder/decoder and the walltime untill it is returned,",
    )
    parser.add_argument(
        "--split-field",
        dest="split_field",
        type=str,
        default="codec",
        help="Use field to distinguish groups of data.",
    )
    parser.add_argument(
        "--split-dataset",
        action="store_true",
        dest="split_dataset",
        help="Use test input file data set to distinguish groups of data. Otherwise "
        "all data will be considered as being related. Overrides split_field",
    )
    parser.add_argument(
        "--dataset-labels",
        dest="dataset_labels",
        type=str,
        nargs="+",
        help="Labels for the datasets. If not provided, the file name will be used",
    )
    parser.add_argument(
        "--quantization",
        default="60",
        type=int,
        help="Quantize time in ms so multiple runs could more easily be calulated together",
    )
    parser.add_argument("--size", default="10x10", help="Set size of the plot")
    parser.add_argument(
        "--cpu-load",
        dest="cpu_load",
        action="store_true",
    )

    parser.add_argument(
        "--rolling",
        default=0,
        type=int,
        help="create a moving average of the y axis to be plotted.",
    )
    parser.add_argument("--keep_na_codec", action="store_true")
    parser.add_argument("--skip_tail_sec", default=0, type=float)
    parser.add_argument("--skip_head_sec", default=0, type=float)
    options = parser.parse_args()

    return options


def main():
    """
    Takes the output from encapp_stats_to_csv.py as a source for plotting performance stats.
    """
    options = parse_args()
    data = pd.DataFrame()
    cpu_data = None
    named_timestamps = pd.DataFrame()

    if len(options.files) == 1 and len(options.output) == 0:
        options.output = options.files[0]
    for file in options.files:
        if not file[-4:] == ".csv":
            print(f"Warning! {file} is not a csv file. Check input.")
        input_data = pd.read_csv(file)
        if options.dataset_labels is not None:
            input_data["dataset"] = options.dataset_labels.pop(0)
        else:
            input_data["dataset"] = file
        if "named_timestamp" in input_data.columns:
            named_timestamps = pd.concat(
                [named_timestamps, input_data], ignore_index=True
            )
        else:
            data = pd.concat([data, input_data], ignore_index=True)

        if options.cpu_load:
            cpu_filename = ""
            input_cpu_data = None
            # Assume original naming scheme
            if "encoding" in file:
                cpu_filename = file.replace("encoding", "aggregated_cpu")
            if os.path.exists(cpu_filename):
                input_cpu_data = pd.read_csv(cpu_filename)
            elif "decoding" in file:
                cpu_filename = file.replace("decoding", "aggregated_cpu")
                if os.path.exists(cpu_filename):
                    input_cpu_data = pd.read_csv(cpu_filename)
            if input_cpu_data is None:
                continue
            input_cpu_data["dataset"] = file
            if cpu_data is not None:
                cpu_data = pd.concat([cpu_data, input_cpu_data], ignore_index=True)
            else:
                cpu_data = input_cpu_data

    # special case

    sns.set_style("whitegrid")
    sns.set(rc={"xtick.bottom": True, "ytick.left": True})
    sns.set_theme(rc={"figure.figsize": (options.size.lower().split("x"))})
    if len(named_timestamps) > 0:
        plot_named_timestamps(named_timestamps, data, options)
        if options.show:
            plt.show()

    # clean up and removed potential error runs
    if not options.keep_na_codec:
        if "codec" in data.columns:
            data = data.dropna(subset=["codec"])

    if options.split_dataset:
        options.split_field = "dataset"

    if "codec" not in data.columns:
        data["codec"] = data["decoding_codec"] + "->" + data["encoding_codec"]
    if options.split_field not in data.columns:
        print("Split option does not exists, choices are: ", data.columns)
        exit(0)

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

    if options.output:
        if options.output.lower()[-4:] == ".png":
            options.output = options.output[:-4]
    if options.cpu_load:
        plot_cpu_load(cpu_data, options)

    if options.frame_rate:
        plotFrameRate(data, options)
        plotProcRate(data, options)
    if options.bit_rate:
        plotBitrate(data, options)
        plotAverageBitrate(data, options)
    if options.frame_size:
        plotFrameSize(data, options)
    if options.latency:
        plotLatency(data, options)
    """
    if len((data["description"].unique())) > 1:
        plotTestNumbers(data)
    """
    if options.show:
        plt.show()


if __name__ == "__main__":
    main()
