#!/usr/bin/python3
import json
import argparse
import pandas as pd
import seaborn as sb
import matplotlib.pyplot as plt


  # "id": "encapp_3d989dae-2218-43a8-a96c-c4856f362c4b",
  # "description": "surface encoder",
  # "date": "Mon Jul 20 15:18:35 PDT 2020",
  # "proctime": 842594344696070,
  # "framecount": 294,
  # "encodedfile": "encapp_3d989dae-2218-43a8-a96c-c4856f362c4b.mp4",
  # "settings": {
    # "codec": "video/hevc",
    # "gop": 10,
    # "fps": 30,
    # "bitrate": 2000000,
    # "meanbitrate": 1905177,
    # "width": 1280,
    # "height": 720,
    # "encmode": "BITRATE_MODE_CBR",
    # "keyrate": 10,
    # "iframepreset": "UNLIMITED",
    # "colorformat": 2135033992,
    # "colorrange": 2,
    # "colorstandard": 4,
    # "colortransfer": 3,
    # "hierplayers": 0,
    # "ltrcount": 1
  # },
  # "frames": [
    # {
      # "frame": 0,
      # "iframe": 1,
      # "size": 31568,
      # "pts": 66666,
      # "proctime": 74273281
    # },


def plot_framesize(data, variant, description, options):
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    axs.set_title('Frame sizes in bytes')

    sb.lineplot(x=data['pts']/1000000,
                y='size',
                ci="sd",
                data=data,
                hue=variant, ax=axs)
    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.suptitle(options.label + description)

    name = options.label + '_bitrate_' +  description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


def plot_processingtime(data, variant, description, options):
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    sb.lineplot(x=data['pts']/1000000,
                y=data['proctime']/1000000,
                ci="sd", data=data, hue=variant,
                ax=axs)
    sb.lineplot(x=data['pts']/1000000,
                y=data['meanproctime']/1000000,
                ci=None,
                data=data,
                legend=False,
                hue=variant,
                ax=axs)
    axs.set_title('Proc time in ms')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    plt.suptitle(options.label + description)

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_proc-time_' +  description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


def parse_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file to analyze')
    parser.add_argument('--label', default="")
    parser.add_argument('--proctime', action='store_true')
    parser.add_argument('--bitrate', action='store_true')
    options = parser.parse_args()

    return options


def main():
    """
        Calculate stats for videos based on parsing individual frames
        with ffprobe frame parser.
        Can output data for a single file or aggregated data for several files.
    """
    options = parse_args()
    accum_data = None
    for inputfile in options.files:
        if options.files == 1 and len(options.label) == 0:
            splits = inputfile.rsplit('/')
            filename = splits[len(splits)-1]
            options.label = filename

        data = None
        with open(inputfile) as json_file:
            alldata = json.load(json_file)
            proctime = alldata['proctime']
            framecount = alldata['framecount']
            mean = proctime/framecount
            data = pd.DataFrame(alldata['frames'])
            data['codec'] = alldata['settings']['codec']
            data['description'] = alldata['description']
            data['bitrate'] = alldata['settings']['bitrate']
            data['height'] = alldata['settings']['height']
            data['meanproctime'] = mean
            print("__")
            print("file = {:s}".format(alldata['encodedfile']))
            print("codec = {:s}".format(alldata['settings']['codec']))
            print("bitrate = {:d}".format(alldata['settings']['bitrate']))
            print("height = {:d}".format(alldata['settings']['height']))
            print("mean processing time = {:.2f} ms".format(mean/1000000))
            print("__")
        if accum_data is None:
            accum_data = data
        else:
            accum_data = accum_data.append(data)

    frames = accum_data.loc[accum_data['size'] > 0]
    sb.set(style="whitegrid", color_codes=True)
    codecs = pd.unique(frames['codec'])
    for codec in codecs:
        data = frames.loc[frames['codec'] == codec]
        if options.bitrate:
            plot_framesize(data, "description", codec, options)

        if options.proctime:
            plot_processingtime(data, "description", codec, options)

    descrs = pd.unique(frames['description'])
    for desc in descrs:
        data = frames.loc[frames['description'] == desc]
        if options.bitrate:
            plot_framesize(data, "codec", desc, options)

        if options.proctime:
            plot_processingtime(data, "codec", desc, options)

    plt.show()

if __name__ == "__main__":
    main()
