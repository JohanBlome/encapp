#!/usr/bin/python3
import json
import argparse
import pandas as pd
import seaborn as sb
import matplotlib.pyplot as plt
import numpy as np

# "id": "encapp_3d989dae-2218-43a8-a96c-c4856f362c4b",
# "description": "surface encoder",
# "date": "Mon Jul 20 15:18:35 PDT 2020",
# "proctime": 842594344696070,
# "framecount": 294,
# "encodedfile": "encapp_3d989dae-2218-43a8-a96c-c4856f362c4b.mp4",
# "settings": {
#   "codec": "video/hevc",
#   "gop": 10,
#   "fps": 30,
#   "bitrate": 2000000,
#   "meanbitrate": 1905177,
#   "width": 1280,
#   "height": 720,
#   "encmode": "BITRATE_MODE_CBR",
#   "keyrate": 10,
#   "iframepreset": "UNLIMITED",
#   "colorformat": 2135033992,
#   "colorrange": 2,
#   "colorstandard": 4,
#   "colortransfer": 3,
#   "hierplayers": 0,
#   "ltrcount": 1
# },
# "frames": [
#   {
#   "frame": 0,
#   "iframe": 1,
#   "size": 31568,
#   "pts": 66666,
#   "proctime": 74273281
#   },


def plot_framesize(data, variant, description, options):
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    axs.set_title('Frame sizes in bytes')

    p = sb.lineplot(x=data['pts']/1000000,
                y='size',
                ci="sd",
                data=data,
                hue=variant,
                ax=axs)
    p.set_xlabel("Presentation time in sec")
    p.set_ylabel("Frame size in bytes")
    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.suptitle(f"{options.label} - {description}")

    name = options.label + '_framesizes_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


def plot_processingtime(data, variant, description, options):
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    sb.lineplot(x=data['pts']/1000000,
                y=data['proctime']/1000000,
                ci="sd", data=data, hue=variant,
                ax=axs)

    p = sb.lineplot(x=data['pts']/1000000,
                y=data['meanproctime']/1000000,
                ci=None,
                data=data,
                legend=False,
                hue=variant,
                ax=axs,
                label="mean proc time (ms)")
    axs.set_title('Proc time in ms')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)

    p.set_xlabel("Presentation time in sec")
    p.set_ylabel("Time in ms")
    plt.suptitle(f"{options.label} - {description}")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_proc-time_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


def plot_times(data, variant, description, options):
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    sb.lineplot(x=data.index,
                y=data['starttime']/1000000,
                ci="sd", data=data, hue=variant,
                ax=axs)
    p = sb.lineplot(x=data.index,
                y=data['stoptime']/1000000,
                ci="sd", data=data, hue=variant,
                ax=axs)

    axs.set_title('starttime vs stoptime')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)

    p.set_xlabel("Presentation time in sec")
    p.set_ylabel("Time in ms")
    plt.suptitle(f"{options.label} - {description}")


def plot_concurrency(data, description, options):
    fig, axs = plt.subplots(figsize=(12, 9), dpi=200)
    data['simple'] = round(data['starttime']/1000000)
    p = sb.barplot(x=data['simple'],
                y=data['conc'],
                ci="sd", data=data,
                ax=axs)


    axs.set_title('Concurrent codecs')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)

    p.set_ylabel("Number codecs")
    p.set_xlabel("Start time of encoding in sec")
    plt.suptitle(f"{options.label} - {description}")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_concurrent_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


def plot_inflight_data(data, variant, description, options):
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    p = sb.lineplot(x=data['pts']/1000000,
                y=data['inflight'],
                ci="sd", data=data, hue=variant,
                ax=axs)

    axs.set_title('Frames in flight')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)

    p.set_ylabel("Number of frames in codec at the same time")
    p.set_xlabel("Time in sec")
    plt.suptitle(f"{options.label} - {description}")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_inflight_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


#    "gpu_model": "Adreno615v2",
 #   "gpu_max_clock": "180",
  #  "gpu_min_clock": "780",
      #{
      #  "time_sec": 3.7,
      #  "load_percentage": 38
      #},
      #{
      #  "time_sec": 3.8,
      #  "load_percentage": 38

      #},
      #{
      #  "time_sec": 0,
      #  "clock_MHz": "180"
      #},

def plot_gpuprocessing(gpuload, description, options):
    maxclock = gpuload['gpu_max_clock'].values[0]
    gpumodel = gpuload['gpu_model'].values[0]


    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    sb.lineplot(x=gpuload['time_sec'],
                y=gpuload['clock_perc'],
                ci="sd", data=gpuload,
                ax=axs, label=f"GPU clock percentage (max: {maxclock} MHz)")
    p = sb.lineplot(x=gpuload['time_sec'],
                y=gpuload['load_percentage'],
                ci="sd", data=gpuload,
                ax=axs, label=f"GPU load percentage")

    p.set_xlabel("Time in sec")
    p.set_ylabel("Percentage")

    axs.set_title(f'Gpu load ({gpumodel})')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    plt.suptitle(f"{options.label} - {description}")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_gpu-load_' +  description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


def parse_encoding_data(json, inputfile):
    print(f"Parse encoding data")
    data = pd.DataFrame(json['frames'])
    data['source'] = inputfile
    data['codec'] = json['settings']['codec']
    data['description'] = json['description']
    data['bitrate'] = json['settings']['bitrate']
    data['height'] = json['settings']['height']
    proctime = json['proctime']
    framecount = json['framecount']
    mean = proctime/framecount
    data['meanproctime'] = mean
    data.fillna(0)
    return data


def parse_decoding_data(json, inputfile):
    print(f"Parse decoding data")
    decoded_data = None
    try:
        decoded_data = pd.DataFrame(json['decoded_frames'])
        decoded_data['source'] = inputfile
        if (len(decoded_data) > 0):
            try:
                decoded_data['codec'] = json['decoder_media_format']['mime']
            except Exception:
                print("Failed to read decoder data")
                decoded_data['codec'] = "unknown codec"
            try:
                decoded_data['height'] = json['decoder_media_format']['height']
            except Exception:
                print("Failed to read decoder data")
                decoded_data['height'] = "unknown height"

            decoded_data = decoded_data.loc[decoded_data['proctime'] >= 0]
            framecount = len(decoded_data)
            mean = sum(decoded_data['proctime']/framecount)
            decoded_data['meanproctime'] = mean
            decoded_data.fillna(0)
    except Exception as ex:
        print(f"Filed to parse decode data for {inputfile}: {ex}")
        decoded_data = None

    return decoded_data


def parse_gpu_data(json, inputfile):
    print(f"Parse gpu data")
    gpu_data = None
    try:
        gpu_data = pd.DataFrame(json['gpu_data']['gpu_load_percentage'])
        if len(gpu_data) > 0:
            gpuclock_data = pd.DataFrame( json['gpu_data']['gpu_clock_freq'])
            gpu_max_clock = int(json['gpu_data']['gpu_max_clock'])
            gpu_data['clock_perc'] = 100.0 * gpuclock_data['clock_MHz'].astype(float) / gpu_max_clock
            gpu_data = gpu_data.merge(gpuclock_data)
            gpu_model = json['gpu_data']['gpu_model']
            gpu_data['source'] = inputfile
            gpu_data['gpu_max_clock'] = gpu_max_clock
            gpu_data['gpu_model'] = gpu_model
            gpu_data.fillna(0)
    except Exception as ex:
        print(f"GPU parsing failed: {ex}")
        pass
    return gpu_data


def calc_infligh(frames, time_ref):
    sources = pd.unique(frames['source'])
    tmp = []
    for source in sources:
        # Calulate how many frames starts encoding befoe a frame has finished
        # relying on the accurace of the System.nanoTime()
        inflight = []
        filtered = frames.loc[frames['source'] == source]
        start = np.min(filtered['starttime'])
        stop =  np.max(filtered['stoptime'])
        #Calulate a time where the start offset (if existing) does not blur the numbers)
        tmp.append([source,start - time_ref, stop - time_ref])
        for row in filtered.iterrows():
            start = row[1]['starttime']
            stop = row[1]['stoptime']
            intime = filtered.loc[(filtered['starttime'] > start) & (filtered['starttime'] < stop)]
            count = len(intime)
            inflight.append(count)
        frames.loc[frames['source'] == source,('inflight')] = inflight

    labels = ['source', 'starttime', 'stoptime']
    concurrent = pd.DataFrame.from_records(tmp, columns=labels,
                                      coerce_float=True)

    # calculate how many new encoding are started before stoptime
    inflight = []
    for row in concurrent.iterrows():
        start = row[1]['starttime']
        stop = row[1]['stoptime']
        count = len(concurrent.loc[(concurrent['starttime'] > start) & (concurrent['starttime'] < stop)]) + 1
        inflight.append(count)
    concurrent['conc'] = inflight
    return frames, concurrent

def parse_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file to analyze')
    parser.add_argument('--label', default="")
    parser.add_argument('--proctime', default=True)
    parser.add_argument('--bitrate', default=True)
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
    accum_dec_data = None
    accum_gpu_data = None

    for inputfile in options.files:
        if options.files == 1 and len(options.label) == 0:
            splits = inputfile.rsplit('/')
            filename = splits[len(splits)-1]
            options.label = filename

        data = None
        with open(inputfile) as json_file:
            alldata = json.load(json_file)

            encoding_data = parse_encoding_data(alldata, inputfile)
            decoded_data = parse_decoding_data(alldata, inputfile)
            gpu_data = parse_gpu_data(alldata, inputfile)

            proctime = alldata['proctime']
            framecount = alldata['framecount']
            mean_ms = encoding_data['meanproctime'][0]/1000000
            print(f"mean = {mean_ms}")
            first_frame = np.min(encoding_data['pts']) # pts is in microsec
            last_frame = np.max(encoding_data['pts']) # approx.
            video_length = last_frame - first_frame
            print(f"proctime {proctime}, count {framecount}")
            print("__")
            print("file = {:s}".format(alldata['encodedfile']))
            print("framecount = {:d}".format(framecount))
            print("video length = {:.2f} sec, start, stop = {:.2f}/{:.2f}".format(video_length/1000000, first_frame/1000000, last_frame/1000000))
            print("total time = {:d} ms".format(int(proctime/1000000.0)))
            print("codec = {:s}".format(alldata['settings']['codec']))
            print("bitrate = {:d}".format(alldata['settings']['bitrate']))
            print("height = {:d}".format(alldata['settings']['height']))
            print("mean processing time = {:.2f} ms".format(mean_ms))
            print("mean frame latency = {:.2f} ms".format(np.mean(encoding_data.loc[encoding_data['proctime'] > 0,'proctime'])/1000000))
            print("average frames encoded simultenously = {:.2f}".format((proctime/video_length * 10000)/1000000))
            print("__")

        if accum_data is None:
            accum_data = encoding_data
        elif encoding_data is not None:
            accum_data = accum_data.append(encoding_data)

        if accum_dec_data is None:
            accum_dec_data = decoded_data
        elif decoded_data is not None:
            accum_dec_data = accum_dec_data.append(decoded_data)

        if accum_gpu_data is None:
            accum_gpu_data = gpu_data
        elif gpu_data is not None:
            accum_gpu_data = accum_gpu_data.append(gpu_data)

    frames = accum_data.loc[accum_data['size'] > 0]
    sb.set(style="whitegrid", color_codes=True)
    codecs = pd.unique(frames['codec'])
    sources = pd.unique(frames['source'])
    first = np.min(frames['starttime'])

    frames, concurrency = calc_infligh(frames, first)
    plot_inflight_data(frames, 'codec', "encoding pipeline", options)

    if concurrency is not None and len(concurrency) > 1:
        plot_concurrency(concurrency, "conc", options)

    if options.bitrate:
        plot_framesize(frames, "codec", "encoder", options)

    if options.proctime:
        plot_processingtime(frames, "codec", "encoder", options)

    if accum_dec_data is not None and len(accum_dec_data) > 0:
        first = np.min(accum_dec_data['starttime'])
        accum_dec_data, concurrency = calc_infligh(accum_dec_data, first)
        plot_inflight_data(accum_dec_data, 'codec', "decoding pipeline", options)
        plot_processingtime(accum_dec_data, "codec", "decoder", options)

    if accum_gpu_data is not None and len(accum_gpu_data) > 0:
        plot_gpuprocessing(accum_gpu_data, "gpu load", options)



    sb.set(style="whitegrid", color_codes=True)
    plt.show()

if __name__ == "__main__":
    main()
