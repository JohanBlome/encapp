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
    uniqheight = np.unique(data['height'])
    print(f"{data['height']}")
    print(f"heights: {uniqheight}")
    uniqbr = np.unique(data['bitrate'])
    uniqcodec = np.unique(data['codec'])
    uniqdescr = np.unique(data['description'])
    for codec in uniqcodec:
        cfilt = data.loc[(data['codec'] == codec)]
        for descr in uniqdescr:
            dfilt = cfilt.loc[(cfilt['description'] == descr)]
            for height in uniqheight:
                hfilt = dfilt.loc[(dfilt['height'] == height)]
                for br in uniqbr:
                    filtered = hfilt.loc[hfilt['bitrate'] == br]
                    if len(filtered) > 0:
                        match = filtered.iloc[0]
                        realbr = match['mean_bitrate']
                        if math.isinf(realbr):
                            realbr = 0
                        pair.append([br, int(realbr), codec, height, descr])

    bitrates = pd.DataFrame(
        pair, columns=['bitrate', 'real_bitrate', 'codec', 'height','description'])
    print(f"br: {bitrates}")
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    style = "codec"
    if options.split_descr:
        style = "description"
    p = sns.lineplot(
        x=bitrates['bitrate']/1000,
        y=bitrates['real_bitrate']/bitrates['bitrate'],
        style=style,
        hue='height',
        data=bitrates,
        marker="o",
        ax=axs)
    p.set_xlabel('Target bitrate (kbps)')
    p.set_ylabel('Bitrate ratio')
    plt.ticklabel_format(style='plain', axis='x')
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Bitrate in kbps")
    else:
        axs.set_title(f"{options.label} Bitrate in kbps")
    name = f'{options.output}.av_bitrate.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def plotProcRate(data, options):
    mean_input_fps = round(np.mean(data["av_proc_fps"]), 2)
    # plot the framerat
    u_codecs = np.unique(data["codec"])
    if not options.keep_na_codec and len(u_codecs) > 1:
        data = data.loc[data["codec"] != "na"]

    data["rel_start_quant"] = (
        (data["rel_start"] / (options.quantization * 1e6)).astype(int)
    ) * options.quantization
    print(
        f"Any inf? {data['rel_start'].isin([np.inf, -np.inf]).values.sum()}, na {data.isin([np.NaN]).values.sum()}"
    )
    print(f"rel_start_quant: {data['rel_start_quant']}")
    print(f"{data['fps']}")

    slen = int(data["fps"].iloc[0])
    data["smooth_proctime"] = (
        (data["stoptime"].shift(-slen, axis="index", fill_value=0) - data["stoptime"])
    ) / (1e9 * slen)
    data = data.drop(data.tail(slen).index)

    st = np.min(data["starttime"])
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]

    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] >= (options.skip_head_sec * 1e9)]
    data = data.loc[(data["smooth_proctime"] > 0) & (data["starttime"] > 0)]
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    hue = "codec"
    if options.split_descr:
        hue = "description"
    p = sns.lineplot(  # noqa: F841
        x=data["rel_start_quant"] / 1e3,
        y=1.0 / data["smooth_proctime"],
        hue=hue,
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
    u_codecs = np.unique(data["codec"])
    if not options.keep_na_codec and len(u_codecs) > 1:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["proctime"] > 0) & (data["starttime"] > 0)]

    data["rel_start_quant"] = (
        (data["rel_start"] / (options.quantization * 1e6)).astype(int)
    ) * options.quantization
    first = data.iloc[0]["rel_start_quant"]
    last = data.iloc[0]["rel_start_quant"]

    print(f"head {data.head(3)['rel_stop']/1e9}")
    print(f"tail {data.tail(3)['rel_stop']/1e9}")
    st = np.min(data["starttime"])
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)

    hue = "codec"
    if options.split_descr:
        hue = "description"
    p = sns.lineplot(  # noqa: F841
        x=data["rel_start_quant"] / 1e3,
        y=data["proctime"] / 1e6,
        hue=hue,
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
    u_codecs = np.unique(data["codec"])
    if not options.keep_na_codec and len(u_codecs) > 1:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    hue = "codec"
    if options.split_descr:
        hue = "description"
    p = sns.lineplot(  # noqa: F841
        x=data["rel_pts"] / 1e6, y="av_fps", hue=hue, ci="sd", data=data, ax=axs
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
    rel_end = np.max(data["rel_stop"])
    if options.skip_tail_sec > 0:
        data = data.loc[data["rel_stop"] <= (rel_end - options.skip_tail_sec * 1e9)]
    if options.skip_head_sec > 0:
        data = data.loc[data["rel_start"] > (options.skip_head_sec * 1e9)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]

    mean_fr = round(np.mean(data["size"]) / 1000, 2)
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)

    data.loc[data["iframe"] == 0, "frame type"] = "P frames"
    data.loc[data["iframe"] == 1, "frame type"] = "I frames"
    
    u_frame_types = np.unique(data["frame type"])
    palette = ["r", "b"]
    if len(u_frame_types) < 2:
        print(f"There is only one type of frames: {u_frame_types[0]}")
        palette = ["g"]
        
    
    if len(options.label) == 0 and "test_description" in list(data.columns):
        data = data.sort_values(by=['test_description', 'codec','height' , 'bitrate' ])
        u_test_description = np.unique(data["test_description"].astype(str))
        col_wrap = len(u_test_description)
        print(f"ft: {np.unique(data['frame type'])}")
        fg = sns.relplot(
            x=data["pts"] / 1e6,
            y=data["size"] / 1000,
            style="codec",
            hue="frame type",
            col = "test_description",
            col_wrap=col_wrap,
            data=data,
            kind="scatter",
            ax=axs,
            palette=palette,
        )
        p = fg.axes
        while hasattr(p, '__len__'):
            p = p[0]
    else:
        p = sns.scatterplot(
            x=data["pts"] / 1e6,
            y=data["size"] / 1000,
            style="codec",
            hue="frame type",
            data=data,
            ax=axs,
            palette=["r", "b"],
        )
        if len(options.files) == 1:
            axs.set_title(f"{options.label} I/P Framesize in kb ( {mean_fr} kb )")
        else:
            axs.set_title(f"{options.label} I/P Framesize in kb")

    p.set_xlabel("time (sec)")
    p.set_ylabel("size (kb)")
    #plt.legend(loc='upper left', title='Teami')
    name = f"{options.output}.framesizes.png"
    plt.savefig(name.replace(" ", "_"), format="png")


def plotBitrate(data, options):
    # Bitrate
    if not options.keep_na_codec:
        data = data.loc[data["codec"] != "na"]
    rel_end = np.max(data["pts"])
    print(f"end: {rel_end}")
    if options.skip_tail_sec > 0:
        data = data.loc[data["pts"] <= (rel_end - options.skip_tail_sec * 1e6)]
    if options.skip_head_sec > 0:
        data = data.loc[data["pts"] > (options.skip_head_sec * 1e6)]
    data = data.loc[(data["av_fps"] > 0) & (data["starttime"] > 0)]

    # plot per codec
    mean_br = round(np.mean(data["bitrate_per_frame_bps"] / 1000.0), 2)

    u_codecs = np.unique(data["codec"])
    u_model = pd.unique((data.loc[data["model"] != ""])["model"])
    print(f"last ts: {data.iloc[-1]}")
    set_label = True
    if options.rename_codec is None:

        data = data.sort_values(by=['description', 'codec','height' , 'bitrate' ])
        if len(options.label) == 0 and "description" in list(data.columns):
            print(f"td: {np.unique(data['description'])}") 
            # maybe filter on models
            u_test_description = np.unique(data["description"].astype(str))
            col_wrap = len(u_test_description)
            fig = plt.figure(figsize=(12, 9), dpi=100)
            counter = 0
            
            fg = sns.relplot(
                x=data["pts"] / 1e6,
                y=data["av_bitrate"]/1000,
                hue="codec",
                style="model",
                col="description",
                col_wrap=col_wrap,
                kind = "line",
                data=data,
                palette="dark",
            )
            p = fg.axes
            while hasattr(p, '__len__'):
                p = p[0]
            set_label = False
        else:
            # maybe filter on models
            fig, axs = plt.subplots(nrows=len(u_codecs), figsize=(12, 9), dpi=100)
            counter = 0
            for codec in u_codecs:
                filt = data.loc[data["codec"] == codec]
                ax1 = axs
                if len(u_codecs) > 1:
                    ax1 = axs[counter]
                p = sns.lineplot(
                    x=filt["pts"] / 1e6,
                    y=data["av_bitrate"]/1000,
                    label=f"{codec}",
                    hue="bitrate",
                    style="model",
                    data=filt,
                    ax=ax1,
                    palette="dark",
                )
                counter += 1

    else:
        # plot per model with codecs hard coded to be same
        fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
        counter = 0

        p = sns.lineplot(
            x=filt["pts"] / 1e6,
            y=data["av_bitrate"]/1000,
            label=f"{codec}",
            hue="model",
            style="bitrate",
            data=data,
            ax=ax1,
            palette="dark",
        )

    p.set_xlabel("time (sec)")
    p.set_ylabel("size (kbps)")
    plt.ticklabel_format(style="plain", axis="x")
    if set_label:
        if len(options.files) == 1:
            p.set_title(f"{options.label} Bitrate in kbps ( {mean_br} kbps )")
        else:
            p.set_title(f"{options.label} Bitrate in kbps )")
    name = f"{options.output}.bitrate.png"
    plt.savefig(name.replace(" ", "_"), format="png")

def plotTestNumbers(data):
    # plot the number of test per test description
    u_descrs = np.unique(data["description"])
    items = []
    print(f"u_descrs: {u_descrs}")
    for desc in u_descrs:
        filt = data.loc[(data["description"] == desc) & (data["frame"]== 1)]
        num = len(filt)
        print(f"descr: {desc} num = {num}")
        print(f"{filt}")
        items.append([desc, num])
        
    data = pd.DataFrame(items, columns=["description", "num"])
    
    sns.barplot(x="description", y="num", data=data)
    plt.savefig("test_numbers.png", format="png")


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
    parser.add_argument("-r", "--frame_rate", action="store_true")
    parser.add_argument("-a", "--av_frame_rate", action="store_true")
    parser.add_argument("-l", "--latency", action="store_true")
    parser.add_argument("--split_descr", action="store_true")
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
    if options.av_frame_rate:# and len(options.files) > 1:
        print(f"data = {data}") 
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
    if len(np.unique(data["description"])) > 1:
        plotTestNumbers(data)
    if options.show:
        plt.show()


if __name__ == "__main__":
    main()
