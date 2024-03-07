#!/usr/local/bin/python3

import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import os.path as path
import argparse
import sys
import json
import os
import numpy as np
import pandas as pd
import seaborn as sns
import math
import re
import argparse

sns.set_style("whitegrid")
sns.set(rc={"xtick.bottom": True})


metrics = [
    "vmaf",
    "psnr",
    "ssim",
    "bitrate",
    "bitrate_ratio",
    "vmaf_hm",
    "psnr_u",
    "psnr_v",
]


def set_graph_props(g, args):
    g.fig.set_dpi(args.dpi)
    for axs in g.axes:
        if not isinstance(axs, np.ndarray):
            axs.grid(True, which="both", axis="both")
        else:
            for ax in axs:
                ax.grid(True, which="both", axis="both")
    if args.xlog:
        plt.xscale("log")
    plt.legend(ncols=2)


def clean_filename(text):
    return text.strip().replace(" ", ".")


def plot_by(data, args):
    # plot metric in graphs split my arg.split_by

    bitrate_column = "bitrate Mbps"
    if args.output_bitrate:
        bitrate_column = "calculated bitrate Mbps"

    if not args.label:
        args.label = "quality"

    fig_size = (
        float(args.graph_size.split("x")[0]),
        float(args.graph_size.split("x")[1]),
    )
    graph_height = fig_size[1]
    aspect_ratio = fig_size[0] / fig_size[1]
    # 1 split by source, plot metric with codec as marker and resolution as hue
    if args.split_by == "source":
        split_num = len(data["source"].unique())
        hue = "height"
        size = ""
        if len(data["model"]) > 1:
            hue = "model"
            size = "height"

        if args.separate:
            for source in data["source"].unique():
                filt = data.loc[data["source"] == source]
                g = sns.relplot(
                    data=filt,
                    x=bitrate_column,
                    y=args.metric,
                    hue=hue,
                    size=size,
                    style="codec",
                    kind="line",
                    height=graph_height,
                    aspect=aspect_ratio,
                )
                set_graph_props(g, args)
                plt.suptitle(f"{args.label}.{args.metric}.{source}")
                plt.savefig(f"{clean_filename(args.label)}.{args.metric}.{source}.png")
                plt.close()

        else:
            g = sns.relplot(
                x=bitrate_column,
                y=args.metric,
                hue=hue,
                size=size,
                style="codec",
                data=data,
                col="source",
                col_wrap=int(math.sqrt(split_num)),
                kind="line",
                height=graph_height,
                aspect=aspect_ratio,
            )
            set_graph_props(g, args)
            plt.suptitle(f"{args.label}.{args.metric} by source")
            plt.savefig(f"{args.label}.{args.metric}.by_source.png")
            plt.close()

    elif args.split_by == "codec":
        split_num = len(data["source"].unique())
        hue = "height"
        size = ""
        if len(data["model"]) > 1:
            hue = "model"
            size = "height"

        if args.separate:
            for codec in data["codec"].unique():
                filt = data.loc[data["codec"] == codec]
                g = sns.relplot(
                    x=bitrate_column,
                    y=args.metric,
                    hue=hue,
                    size=size,
                    style="framerate_fps",
                    data=filt,
                    kind="line",
                    height=graph_height,
                    aspect=aspect_ratio,
                )
                set_graph_props(g, args)
                plt.suptitle(f"{args.label}.{args.metric}.{codec}")
                plt.savefig(f"{clean_filename(args.label)}.{args.metric}.{codec}.png")
                plt.close()
        else:
            g = sns.relplot(
                x=bitrate_column,
                y=args.metric,
                hue="height",
                style="framerate_fps",
                data=data,
                col="codec",
                col_wrap=int(math.sqrt(split_num)),
                kind="line",
                height=graph_height,
                aspect=aspect_ratio,
            )
            set_graph_props(g, args)
            plt.suptitle(f"{args.label} {args.metric} by codec")
            plt.savefig(f"{clean_filename(args.label)}.{args.metric}.by_codec.png")
            plt.close()

    elif args.split_by == "model":
        split_num = len(data["model"].unique())

        if args.separate:
            for model in data["model"].unique():
                filt = data.loc[data["model"] == model]
                g = sns.relplot(
                    x=bitrate_column,
                    y=args.metric,
                    hue="height",
                    style="codec",
                    size="framerate_fps",
                    data=data,
                    kind="line",
                    height=graph_height,
                    aspect=aspect_ratio,
                )
                set_graph_props(g, args)
                plt.suptitle(f"{args.label}.{args.metric}.{model}")
                plt.savefig(f"{clean_filename(args.label)}.{args.metric}.{model}.png")
                plt.close()

        else:
            g = sns.relplot(
                x=bitrate_column,
                y=args.metric,
                hue="height",
                style="codec",
                size="framerate_fps",
                data=data,
                col="model",
                col_wrap=int(math.sqrt(split_num)),
                kind="line",
                height=graph_height,
                aspect=aspect_ratio,
            )
            set_graph_props(g, args)
            plt.suptitle(f"{args.label} {args.metric} by model")
            plt.savefig(f"{clean_filename(args.label)}.{args.metric}.by_model.png")
            plt.close()
    else:

        hue = "source"
        style = "codec"
        size = "model"
        if len(data["model"]) > 1:
            style = "mode"
            size = "codec"

        # implicit by height
        if args.separate:
            for height in data["height"].unique():
                filt = data.loc[data["height"] == height]
                g = sns.relplot(
                    x=bitrate_column,
                    y=args.metric,
                    hue="source",
                    style="codec",
                    data=filt,
                    col="framerate_fps",
                    kind="line",
                    height=graph_height,
                    aspect=aspect_ratio,
                )
                set_graph_props(g, args)
                plt.suptitle(f"{args.label} {args.metric} {height}")
                plt.savefig(f"{clean_filename(args.label)}.{args.metric}.{height}.png")
                plt.close()

        else:
            split_num = len(data["height"].unique())
            g = sns.relplot(
                x=bitrate_column,
                y=args.metric,
                hue="source",
                style="codec",
                data=data,
                col="height",
                row="framerate_fps",
                kind="line",
                height=graph_height,
                aspect=aspect_ratio,
            )
            set_graph_props(g, args)
            plt.suptitle(f"{args.label} {args.metric} by height")
            plt.savefig(f"{clean_filename(args.label)}.{args.metric}.by_height.png")
            plt.close()


def main():
    parser = argparse.ArgumentParser(description="")
    parser.add_argument(
        "files",
        nargs="+",
        help="Csv output file(s) from encapp_quality.py should be used. Multiple files will be merged.",
    )
    parser.add_argument("-l", "--label", help="", default=None)
    parser.add_argument(
        "-m",
        "--metric",
        default="vmaf",
        help=f"Metric to plot. Available {metrics}, default: vmaf",
    )
    parser.add_argument("--bitrate_mode", default="", help="Filter for bitrate mode")
    parser.add_argument(
        "--codec",
        default="",
        help="Filter for codec. Use ',' for multiple codec names. Codec names will be partially matched.",
    )
    parser.add_argument(
        "--source",
        default="",
        help="Filter for specific source name. Use ',' for multiple source names.",
    )
    parser.add_argument(
        "--framerate",
        default="",
        help="Filter for specific framerate. Use ',' for multiple rates.",
    )
    parser.add_argument(
        "--resolution",
        default="",
        help="Filter for specific resolution. Use ',' for multiple resolutions names. Either WxH or W or H independatly.",
    )
    parser.add_argument(
        "--split_by",
        default="",
        help="Split graph into multiple by 'source', 'description', 'model', 'codec'",
    )
    parser.add_argument(
        "--separate", action="store_true", help="Write all graphs a single plots"
    )
    parser.add_argument(
        "--output_bitrate",
        action="store_true",
        help="Plot using the calculated output and not the set value",
    )
    parser.add_argument(
        "--xlog", action="store_true", help="Plot x axis with log scale."
    )
    parser.add_argument("--graph_size", default="9x9")
    parser.add_argument("--dpi", type=int, default=100)

    args = parser.parse_args()
    data = pd.DataFrame()
    label = f"{args.metric}_bitrate"

    # combine all files to single DataFrame
    if args.label:
        label = args.label.strip().replace(" ", "_")
    for file in args.files:
        if not path.exists(file):
            print("File {} does not exist".format(file))
            sys.exit(1)
        else:
            tmp = pd.read_csv(file)
            data = pd.concat([data, tmp])

    if len(args.bitrate_mode) > 0:
        data = data.loc[data["bitrate_mode"] == args.bitrate_mode]

    if len(args.codec) > 0:
        codecs = args.codec.replace(",", "|")
        data = data.loc[data["codec"].str.contains(codecs)]

    if len(args.framerate) > 0:
        framerates = [float(x) for x in args.framerate.split("|")]
        data = data.loc[data["framerate_fps"].isin(framerates)]

    # todo: check this filter
    if len(args.source) > 0:
        sources = args.source.replace(",", "|")
        data = data[data["source"].str.contains(sources)]

    data["source"] = data.apply(
        lambda row: row["reference_file"].split("/")[-1].split(".")[0], axis=1
    )

    if len(args.resolution) > 0:
        resolutions = args.resolution.split(",")
        sides = [int(x) for x in resolutions if not "x" in x]
        resolutions_ = [x for x in resolutions if "x" in x]
        resolutions = "|".join(resolutions_)
        data_1 = pd.DataFrame()
        if resolutions:
            data["resolution"] = (
                data["width"].astype(str) + "x" + data["height"].astype(str)
            )
            data_1 = data.loc[data["resolution"].str.contains(resolutions)]
        data_2 = data.loc[(data["height"].isin(sides)) | (data["width"].isin(sides))]
        data = pd.concat([data_1, data_2])

    heights = data["height"].unique()
    codecs = np.unique(data["codec"])
    framerates = np.unique(data["framerate_fps"])

    data["bitrate Mbps"] = data["bitrate_bps"] / 1000000
    data["calculated bitrate Mbps"] = data["calculated_bitrate_bps"] / 1000000

    if args.metric == "bitrate_ratio":
        data["bitrate ratio"] = data["calculated_bitrate_bps"] / data["bitrate_bps"]
        args.metric = "bitrate ratio"
    if args.metric == "bitrate":
        args.metric = "calculated bitrate Mbps"

    # Colors
    sources = data["source"].unique()
    if len(args.split_by) > 0:
        sources = data[args.split_by].unique()
    colors = dict(zip(sources, sns.color_palette(n_colors=len(sources))))

    plot_by(data, args)


if __name__ == "__main__":
    main()
