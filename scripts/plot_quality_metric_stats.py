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
import itertools as it

sns.set_style("whitegrid")
sns.set(rc={"xtick.bottom": True})
sns.color_palette("dark")

metrics = [
    "vmaf_mean",
    "vmaf_pX",
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
    if args.ylog:
        plt.yscale("log")
    plt.subplots_adjust(top=0.88)


def clean_filename(text):
    return text.strip().replace(" ", ".")


def plot_by(data, args):
    # plot metric in graphs split my args.split_by
    if args.use_dimension == "resolution":
        data.sort_values("pixel_count", inplace=True)
    else:
        data.sort_values(args.use_dimension, inplace=True)

    bitrate_column = f"bitrate {args.bitrate_magnitude}bps"
    if args.output_bitrate:
        bitrate_column = f"calculated bitrate {args.bitrate_magnitude}bps"

    label = "quality"
    if args.label:
        label = clean_label_for_filename(args.label)

    fig_size = (
        float(args.graph_size.split("x")[0]),
        float(args.graph_size.split("x")[1]),
    )
    graph_height = fig_size[1]
    aspect_ratio = fig_size[0] / fig_size[1]
    # 1 split by source, plot metric with codec as marker and resolution as hue
    if args.split_by == "source":
        split_num = len(data["source"].unique())
        hue = args.use_dimension
        size = ""
        if len(data["model"]) > 1:
            hue = "model"
            size = args.use_dimension

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
                plt.suptitle(f"{label}.{args.metric}.{source}")
                plt.savefig(f"{clean_filename(label)}.{args.metric}.{source}.png")
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
            plt.suptitle(f"{label}.{args.metric} by source")
            plt.savefig(f"{label}.{args.metric}.by_source.png")
            plt.close()

    elif args.split_by == "codec":
        split_num = len(data["source"].unique())
        hue = args.use_dimension
        size = ""
        if len(data["model"]) > 1:
            hue = "model"
            size = args.use_dimension

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
                plt.suptitle(f"{label}.{args.metric}.{codec}")
                plt.savefig(f"{clean_filename(label)}.{args.metric}.{codec}.png")
                plt.close()
        else:
            g = sns.relplot(
                x=bitrate_column,
                y=args.metric,
                hue=args.use_dimension,
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
                    hue=args.use_dimension,
                    style="codec",
                    size="framerate_fps",
                    data=data,
                    kind="line",
                    height=graph_height,
                    aspect=aspect_ratio,
                )
                set_graph_props(g, args)
                plt.suptitle(f"{label}.{args.metric}.{model}")
                plt.savefig(f"{clean_filename(label)}.{args.metric}.{model}.png")
                plt.close()

        else:
            g = sns.relplot(
                x=bitrate_column,
                y=args.metric,
                hue=args.use_dimension,
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
            plt.suptitle(f"{label} {args.metric} by model")
            plt.savefig(f"{clean_filename(label)}.{args.metric}.by_model.png")
            plt.close()
    else:

        hue = "source"
        style = "codec"
        size = "model"
        if len(data["model"]) > 1:
            style = "model"
            size = "codec"
        if args.split_on_datasource:
            style = "data source"
            hue = "source"
            size = "codec"

        # implicit by height
        if args.separate:
            for height in data[args.use_dimension].unique():
                filt = data.loc[data[args.use_dimension] == height]
                if args.average:
                    for framerate in data["framerate_fps"].unique():
                        filt2 = filt.loc[filt["framerate_fps"] == framerate]
                        g = sns.catplot(
                            x=bitrate_column,
                            y=args.metric,
                            hue="codec",
                            col="model",
                            kind="box",
                            data=filt2,
                        )
                        set_graph_props(g, args)
                        plt.suptitle(
                            f"{label} {args.metric} {height} @  {framerate} fps"
                        )
                        plt.savefig(
                            f"{clean_filename(label)}.{args.metric}.{height}.{framerate}fps.png"
                        )
                        plt.close()

                else:
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
                    plt.suptitle(f"{label} {args.metric} {height}")
                    plt.savefig(f"{clean_filename(label)}.{args.metric}.{height}.png")
                    plt.close()

        else:
            g = None
            if args.average:
                g = sns.catplot(
                    x=bitrate_column,
                    y=args.metric,
                    hue="codec",
                    col="model",
                    kind="box",
                    data=data,
                )
            else:
                split_num = len(data[args.use_dimension].unique())
                g = sns.relplot(
                    x=bitrate_column,
                    y=args.metric,
                    hue=hue,
                    style=style,
                    data=data,
                    col=args.use_dimension,
                    row="framerate_fps",
                    kind="line",
                    height=graph_height,
                    aspect=aspect_ratio,
                )
            set_graph_props(g, args)
            plt.suptitle(f"{label} {args.metric} by {args.use_dimension}")
            plt.savefig(f"{clean_filename(label)}.{args.metric}.png")
            plt.close()


def lookup_vmaf_representative(filename, value):
    # writes a png for the closest match
    closest = -1
    if path.exists:
        with open(filename) as f:
            jsondata = json.load(f)
            data = pd.json_normalize(jsondata["frames"])
            data["distance"] = np.abs(data["metrics.vmaf"] - value)
            closest = (data["metrics.vmaf"] - value).abs().idxmin()

    return closest


def find_mean_max_examples_per_file(data, args):
    sources = data["source"].unique()
    bitrates = data["bitrate_bps"].unique()
    dims = data[args.use_dimension].unique()
    matches = []
    for source in sources:
        sfilt = data.loc[data["source"] == source]
        for bitrate in bitrates:
            bfilt = sfilt.loc[sfilt["bitrate_bps"] == bitrate]
            for dim in dims:
                dfilt = bfilt.loc[bfilt[args.use_dimension] == dim]
                bitrate = dfilt["bitrate_bps"].values[0]
                vmaf = dfilt["vmaf"].values[0]
                vmaf_min = dfilt["vmaf_min"].values[0]
                testfile = dfilt["testfile"].values[0]
                reference_file = dfilt["reference_file"].values[0]
                source = dfilt["source"].values[0]
                vmaffile = testfile.rsplit(".")[0] + ".mp4.vmaf.json"
                closest = lookup_vmaf_representative(vmaffile, vmaf)
                matches.append(
                    {
                        "testfile": testfile,
                        "reference_file": reference_file,
                        "bitrate_bps": bitrate,
                        "type": "vmaf_mean",
                        "value": vmaf,
                        "frame": closest,
                    }
                )
                closest = lookup_vmaf_representative(vmaffile, vmaf_min)
                matches.append(
                    {
                        "testfile": testfile,
                        "reference_file": reference_file,
                        "bitrate_bps": bitrate,
                        "type": "vmaf_min",
                        "value": vmaf_min,
                        "frame": closest,
                    }
                )

    matches = pd.DataFrame(matches)
    return matches


def plot_percentile(data, args):

    bitrate_label = f"bitrate {args.bitrate_magnitude}bps"
    framerates = data["framerate_fps"].unique()
    data.sort_values("pixel_count", inplace=True)
    pixel_counts = data["pixel_count"].unique()
    bitrates = data["bitrate_bps"].unique()
    max_resolution = data.loc[data["pixel_count"].idxmax()]["resolution"]
    resolutions = data["resolution"].unique()
    bitrates = data[bitrate_label].unique()

    percentiles = []

    # find high ref
    max_pixels = data["pixel_count"].max()
    max_fps = data["framerate_fps"].max()
    max_bitrate = data[bitrate_label].max()
    percs = []
    max_percentile = None

    # Draw the highest quality as a reference on all graphs
    if args.high_reference and len(resolutions) > 1:
        df = data.loc[
            (data[bitrate_label] == max_bitrate)
            & (data["resolution"] == max_resolution)
            & (data["framerate_fps"] == max_fps)
        ]
        if len(df) == 0:
            print("ERROR: not valid combination")
        else:
            perc = [np.percentile(df[args.metric], perc) for perc in range(10, 100, 10)]
            for index, p in enumerate(perc):
                percs.append(
                    {
                        "metric": "mean",
                        "resolution": max_resolution,
                        "framerate": max_fps,
                        bitrate_label: max_bitrate,
                        "percentile": index * 10,
                        args.metric: p,
                    }
                )
            if args.metric == "vmaf":
                perc = [
                    np.percentile(df["vmaf_min"], perc) for perc in range(10, 100, 10)
                ]
                for index, p in enumerate(perc):
                    percs.append(
                        {
                            "metric": "min",
                            "width": df["resolution"].values[0],
                            "framerate": max_fps,
                            bitrate_label: max_bitrate,
                            "percentile": index * 10,
                            args.metric: p,
                        }
                    )

        max_percentile = pd.DataFrame(percs)

    for resolution, fps, bitrate in it.product(*[resolutions, framerates, bitrates]):
        if (resolution == max_resolution) & (fps == max_fps) & (bitrate == max_bitrate):
            continue
        df = data.loc[
            (data[bitrate_label] == bitrate)
            & (data["resolution"] == resolution)
            & (data["framerate_fps"] == fps)
        ]
        if len(df) == 0:
            print("ERROR: not valid combination")
        else:
            perc = [np.percentile(df[args.metric], perc) for perc in range(10, 100, 10)]

            for index, p in enumerate(perc):
                percentiles.append(
                    {
                        "metric": "mean",
                        "resolution": resolution,
                        "framerate": fps,
                        bitrate_label: bitrate,
                        "percentile": index * 10,
                        args.metric: p,
                    }
                )

        if args.metric == "vmaf":
            perc = [np.percentile(df["vmaf_min"], perc) for perc in range(10, 100, 10)]
            for index, p in enumerate(perc):
                percentiles.append(
                    {
                        "metric": "min",
                        "resolution": resolution,
                        "framerate": fps,
                        bitrate_label: bitrate,
                        "percentile": index * 10,
                        args.metric: p,
                    }
                )

    df = pd.DataFrame(percentiles)
    if len(df) == 0:
        return
    custom_palette = sns.color_palette()
    g = None
    if args.metric == "vmaf":
        g = sns.relplot(
            x="percentile",
            y=args.metric,
            hue=bitrate_label,
            kind="line",
            col="resolution",
            row="framerate",
            style="metric",
            data=df,
            palette=custom_palette,
            linewidth=2,
        )
        alpha = 0.3
        g.map(plt.axhspan, ymin=0, ymax=40, color="red", alpha=alpha, zorder=-100)
        g.map(plt.axhspan, ymin=40, ymax=70, color="orange", alpha=alpha, zorder=-100)
        g.map(plt.axhspan, ymin=70, ymax=80, color="yellow", alpha=alpha, zorder=-100)
        g.map(
            plt.axhspan, ymin=80, ymax=90, color="lightgreen", alpha=alpha, zorder=-100
        )
        g.map(plt.axhspan, ymin=90, ymax=100, color="green", alpha=alpha, zorder=-100)
        if max_percentile is not None:
            g.map(
                sns.lineplot,
                x="percentile",
                y=args.metric,
                errorbar=None,
                color="black",
                style="metric",
                data=max_percentile,
                linewidth=3,
                label=f"{max_bitrate}{args.bitrate_magnitude}bps {max_resolution}, {max_fps}fps",
                legend="full",
                zorder=-1,
            )
        plt.legend()
    else:
        g = sns.relplot(
            x="percentile",
            y=args.metric,
            hue=bitrate_label,
            kind="line",
            col="width",
            row="framerate",
            data=df,
            c="red",
            linewidth=2,
        )
    if args.limit_y:
        miny = df[args.metric].min() // 10 * 10
        plt.ylim(miny)
    plt.savefig(f"{clean_label_for_filename(args.label)}.percentiles.png")


def clean_label_for_filename(label):
    return (
        label.replace(" ", "_")
        .replace("(", "")
        .replace(")", "")
        .replace("/", "_")
        .replace(",", "_")
    )


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
        default="vmaf_mean",
        help=f"Metric to plot. Available {metrics}, default: vmaf_mean",
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
        help="Split graph into multiple by 'source', 'description', 'model', 'codec', 'datasource'",
    )
    parser.add_argument(
        "--separate", action="store_true", help="Write all graphs as single plots"
    )
    parser.add_argument(
        "--output_bitrate",
        action="store_true",
        help="Plot using the calculated output and not the set value",
    )
    parser.add_argument(
        "--xlog", action="store_true", help="Plot x axis with log scale."
    )
    parser.add_argument(
        "--ylog", action="store_true", help="Plot y axis with log scale."
    )
    parser.add_argument(
        "--average", action="store_true", help="Plot average of the metric"
    )
    parser.add_argument(
        "--scale_by_bitrate", action="store_true", help="Scale metric by bitrate ratio"
    )
    parser.add_argument(
        "--use_dimension",
        default="resolution",
        help="either sort on width, height or both",
    )
    parser.add_argument("--find_min_max_vmaf", action="store_true")
    parser.add_argument(
        "--percentile",
        action="store_true",
    )
    parser.add_argument(
        "--bitrates",
        default=None,
    )
    parser.add_argument(
        "--high_reference",
        action="store_true",
    )
    parser.add_argument(
        "--limit_y",
        action="store_true",
    )
    parser.add_argument(
        "--bitrate_magnitude", default="k", help="Represent bps as 'k' or 'M'bps"
    )
    parser.add_argument(
        "--split_on_datasource",
        action="store_true",
        help="Treat separate input files as separaters",
    )

    parser.add_argument("--graph_size", default="9x9")
    parser.add_argument("--dpi", type=int, default=100)

    args = parser.parse_args()
    data = pd.DataFrame()
    if not args.label:
        args.label = f"{args.metric}_bitrate"

    # combine all files to single DataFrame
    if args.label:
        label = clean_label_for_filename(args.label)
    for file in args.files:
        if not path.exists(file):
            print("File {} does not exist".format(file))
            sys.exit(1)
        else:
            tmp = pd.read_csv(file)
            tmp["data source"] = file
            data = pd.concat([data, tmp])

    data.reset_index(drop=True, inplace=True)
    data.to_csv("all_quality_data.csv")
    if len(args.bitrate_mode) > 0:
        data = data.loc[data["bitrate_mode"] == args.bitrate_mode]

    if len(args.codec) > 0:
        codecs = args.codec.replace(",", "|")
        data = data.loc[data["codec"].str.contains(codecs)]

    if len(args.framerate) > 0:
        framerates = [float(x) for x in args.framerate.split(",")]
        data = data.loc[data["framerate_fps"].isin(framerates)]

    # todo: check this filter
    if len(args.source) > 0:
        sources = args.source.replace(",", "|")
        print(f"{sources}")
        data = data[data["reference_file"].str.contains(sources)]

    data["source"] = data.apply(
        lambda row: row["reference_file"].split("/")[-1].split(".")[0], axis=1
    )

    # filter on resolutions
    data["resolution"] = data["width"].astype(str) + "x" + data["height"].astype(str)

    if len(args.resolution) > 0:
        resolutions = args.resolution.split(",")
        sides = [int(x) for x in resolutions if not "x" in x]
        resolutions_ = [x for x in resolutions if "x" in x]
        resolutions = "|".join(resolutions_)
        data_1 = pd.DataFrame()
        if resolutions:
            data_1 = data.loc[data["resolution"].str.contains(resolutions)]
        data_2 = data.loc[(data["height"].isin(sides)) | (data["width"].isin(sides))]
        data = pd.concat([data_1, data_2])

    # Filter on bitrates
    bitrates = None
    if args.bitrates:
        bitrates = [int(br) for br in args.bitrates.split(",")]

    if bitrates:
        data = data.loc[data["bitrate_bps"].isin(bitrates)]

    # filter on framerates
    if args.framerate:
        framerates = [float(x) for x in args.framerate.split(",")]
        data = data.loc[data["framerate_fps"].isin(framerates)]

    # Make sorting on absolute size possible
    data["pixel_count"] = data["width"] * data["height"]

    # Easier read
    magnitudes = ["k", "M"]
    if args.bitrate_magnitude:
        if args.bitrate_magnitude not in magnitudes:
            print("Magnitude can only be either 'k' or 'M'")
            exit(0)

        mult = 1000
        if args.bitrate_magnitude == "M":
            mult = 1000000

        data[f"bitrate {args.bitrate_magnitude}bps"] = data["bitrate_bps"] / mult
        data[f"calculated bitrate {args.bitrate_magnitude}bps"] = (
            data["calculated_bitrate_bps"] / mult
        ).round(2)
    data["bitrate ratio"] = data["calculated_bitrate_bps"] / data["bitrate_bps"]

    if args.scale_by_bitrate:
        data[f"scaled {args.metric}"] = data[args.metric] / data["bitrate ratio"]
        args.metric = f"scaled {args.metric}"

    if args.metric == "bitrate_ratio":
        args.metric = "bitrate ratio"
    if args.metric == "bitrate":
        args.metric = f"calculated bitrate {args.bitrate_magnitude}bps"

    # Colors
    sources = data["source"].unique()
    if len(args.split_by) > 0:
        sources = data[args.split_by].unique()
    colors = dict(zip(sources, sns.color_palette(n_colors=len(sources))))

    if args.percentile:
        plot_percentile(data, args)
    elif args.find_min_max_vmaf:
        matches = find_mean_max_examples_per_file(data, args)
        matches.to_csv("vmaf_matches.csv")
    else:
        plot_by(data, args)


if __name__ == "__main__":
    main()
