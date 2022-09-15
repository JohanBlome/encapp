#!/usr/bin/env python3

import json
import argparse
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt
import numpy as np
import math


def clean_name(name, debug=0):
    ret = name.translate(str.maketrans({',': '_', ' ': '_'}))
    print(f'{name} -> {ret}')
    return ret


def plotAverageBitrate(data, options):
    pair = []
    uniqheight = np.unique(data['height'])
    uniqbr = np.unique(data['bitrate'])
    uniqcodec = np.unique(data['codec'])
    for codec in uniqcodec:
        for height in uniqheight:
            for br in uniqbr:
                filtered = data.loc[(data['codec'] == codec) & (
                    data['height'] == height) & (data['bitrate'] == br)]
                if len(filtered) > 0:
                    match = filtered.iloc[0]
                    realbr = match['average_bitrate']
                    if math.isinf(realbr):
                        realbr = 0
                    pair.append([br, int(realbr), codec, height])

    bitrates = pd.DataFrame(
        pair, columns=['bitrate', 'real_bitrate', 'codec', 'height'])
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.lineplot(
        x=bitrates['bitrate']/1000,
        y=bitrates['real_bitrate']/bitrates['bitrate'],
        style='codec',
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


def plotFrameRate(data, options):
    mean_input_fps = round(np.mean(data['fps']), 2)
    # plot the framerate
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.lineplot(  # noqa: F841
        x=data['pts']/1000000,
        y=data['av_fps'],
        hue='codec',
        ci='sd', data=data,
        ax=axs)
    # p.set_ylim(0, 90)
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Framerate ( {mean_input_fps} fps )")
    else:
        axs.set_title(f"{options.label} Framerate")
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    name = f'{options.output}.framerate.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def plotFrameSize(data, options):
    # framesize
    mean_fr = round(np.mean(data['size'])/1000, 2)
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.scatterplot(
        x=data['pts']/1000000,
        y=data['size']/1000,
        hue='codec',
        data=data,
        ax=axs)
    p.set_xlabel('time (sec)')
    p.set_ylabel('size (kb)')
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Framesize in kb ( {mean_fr} kb )")
    else:
        axs.set_title(f"{options.label} Framesize in kb )")
    name = f'{options.output}.framesizes.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def plotBitrate(data, options):
    # Bitrate
    mean_br = round(np.mean(data['bitrate_per_frame_bps']/1000.0), 2)
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=100)
    p = sns.scatterplot(
        x=data['pts']/1000000,
        y=data['bitrate_per_frame_bps']/1000,
        hue='codec',
        data=data,
        ax=axs)
    p = sns.lineplot(
        x=data['pts']/1000000,
        y=data['bitrate_per_frame_bps'].rolling(30).mean()/1000,
        hue='bitrate',
        style='codec',
        data=data,
        ax=axs)
    p.set_xlabel('time (sec)')
    p.set_ylabel('size (kbps)')
    if len(options.files) == 1:
        axs.set_title(f"{options.label} Bitrate in kbps ( {mean_br} kbps )")
    else:
        axs.set_title(f"{options.label} Bitrate in kbps )")
    name = f'{options.output}.bitrate.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def parse_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('--debug', action='count',
                        dest='debug', default=0,
                        help='Increase verbosity (use many times for more)',)
    parser.add_argument('--quiet', action='store_const',
                        dest='debug', const=-1,
                        help='Zero verbosity',)
    parser.add_argument('files', nargs='*', help='file to analyze')
    parser.add_argument('--label', default='')
    parser.add_argument('-o',
                        '--output',
                        default='',
                        help='output name')
    parser.add_argument('--limit', action='store_true',
                        help='add sane axis limits for plots')
    parser.add_argument('--show', action='store_true',
                        help='show the plots')
    parser.add_argument('-fs',
                        '--frame_size',
                        action='store_true')
    parser.add_argument('-br',
                        '--bit_rate',
                        action='store_true')
    parser.add_argument('-r',
                        '--frame_rate',
                        action='store_true')
    parser.add_argument('-a',
                        '--av_frame_rate',
                        action='store_true')

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
    # `fps` column contains the framerate calculated from the
    # input-reported timings (the input/camera framerate)
    mean_input_fps = round(np.mean(data['fps']), 2)
    # `proc_fps` column contains the framerate calculated from the
    # system-reported timings (the gettimeofday framerate)
    mean_sys_fps = round(np.mean(data['proc_fps']), 2)
    print(f"mean fps: {mean_input_fps}")
    print(f'mean system fps: {mean_sys_fps}')

    # Average proc time
    if len(options.files) > 1 and options.av_frame_rate:
        plotAverageBitrate(data, options)

    if options.frame_rate:
        plotFrameRate(data, options)
    if options.bit_rate:
        plotBitrate(data, options)
    if options.frame_size:
        plotFrameSize(data, options)

    if options.show:
        plt.show()


if __name__ == '__main__':
    main()
