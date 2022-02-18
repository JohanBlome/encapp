#!/usr/bin/python3
import json
import argparse
import pandas as pd
import seaborn as sb
import matplotlib.pyplot as plt
import numpy as np

# pd.options.mode.chained_assignment = 'raise'

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
    print('Plot frame sizes')
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    axs.set_title('Frame sizes in bytes')

    p = sb.lineplot(x=data['pts']/1000000,
                    y='size',
                    ci='sd',
                    data=data,
                    hue=variant,
                    ax=axs)
    p.set_xlabel('Presentation time in sec')
    p.set_ylabel('Frame size in bytes')
    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.suptitle(f'{options.label} - {description}')

    name = options.label + '_framesizes_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def plot_bitrate(data, variant, description, options):
    print('Plot bitrate')
    print(f'{data}')
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    axs.set_title('Bitrate in kbps')
    # bytes/msec = kbytes/sec
    data['target in kbps'] = ((data['bitrate']/1000).astype(int)).astype(str)
    data['kbps'] = (round(
        (8 * data['size']/(data['duration_ms'])), 0)).astype(int)
    mean = np.mean(data['kbps'])
    data['kbps'] = data['kbps'].where(data['kbps'] < mean * 20, other=0)
    print(f'mean br = {mean}')
    fps = int(np.mean(data['fps']))
    print(f'fps = {fps}')
    p = sb.lineplot(x=data['pts']/1000000,
                    y='kbps',
                    ci='sd',
                    data=data,
                    hue=variant,
                    ax=axs)
    p.set_xlabel('Presentation time in sec')
    p.set_ylabel('Bitrate in kbps')
    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.suptitle(f'{options.label} - {description}')

    name = options.label + '_bitrate_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')

    # vs target
    heights = np.unique(data['height'])
    for height in heights:
        fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
        axs.legend(loc='best', fancybox=True, framealpha=0.5)
        axs.set_title(f'Bitrate in kbps as function of target, {height}p')
        filtered = data.loc[data['height'] == height]
        filtered['sm_kbps'] = filtered['kbps'].rolling(
            fps,  min_periods=1, win_type=None).sum()/fps
        p = sb.lineplot(x=filtered['pts']/1000000,
                        y='sm_kbps',
                        ci='sd',
                        data=filtered,
                        hue='target in kbps',
                        style='codec',
                        ax=axs)
        p.set_xlabel('Presentation time in sec')
        p.set_ylabel('Bitrate in kbps')
        plt.tight_layout(rect=[0, 0.03, 1, 0.95])
        plt.suptitle(f'{options.label} - {description}')

        name = (options.label + f'_target-bitrate_{height}_' + description +
                '.png')
        plt.savefig(name.replace(' ', '_'), format='png')


def plot_processingtime(data, variant, description, options):
    print('Plot per frame latency and a straight line is average processing time')

    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    p = sb.lineplot(x=data['pts']/1000000,
                    y=data['proctime']/1000000,
                    ci='sd', data=data,
                    hue=variant,
                    ax=axs)
    p = sb.lineplot(x=data['pts']/1000000,
                    y=data['mean_proc_ms'],
                    ci='sd', data=data,
                    hue=variant,
                    ax=axs,
                    legend = False)
    p = sb.lineplot(x=data['pts']/1000000,
                    y=33,
                    ci='sd',
                    color='red', linewidth=5,
                    ax=axs,
                    legend = False)
    plt.suptitle(f'{options.label} - {description}')

    axs.set_title('Frame latency in ms\nStraight line is average proc in ms - Red is 33 ms')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    p.set_ylim(0, 300)
    p.set_xlabel('Presentation time in sec')
    p.set_ylabel('Time in ms')
    plt.suptitle(f'{options.label} - {description}')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_proc-time_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')


    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    axs.set_title('Frame latency in ms')
    p = sb.histplot(x=data['proctime']/1000000,
                    data=data,
                    hue=variant,
                    ax=axs)
    p.set_xlabel('Time in ms')
    p.set_xlim(0, 300)
    plt.suptitle(f'{options.label} - {description}')

    name = options.label + '_proc-time_hist_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')



def plot_processing_framerate(data, variant, description, options):
    print('Calculate the distance between every finished decoding and invert')

    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    variants = np.unique(data[variant])

    rates = []
    for var in variants:
        print(f'checking {var}')
        filtered = data.loc[data[variant] == var]
        max_pts_sec = int(np.max(filtered['pts'])/1000000.0) + 1
        min_pts_sec = int(np.min(filtered['pts'])/1000000.0)

        length = max_pts_sec - min_pts_sec
        for sec in range(min_pts_sec, length):
            frames = filtered.loc[(filtered['pts'] >= sec * 1000000) &
                              (filtered['pts'] < (sec + 1) * 1000000)]
            average_time = (np.sum(frames['proctime'])/len(frames))/1000000000.0
            fps = round(1.0/average_time, 2)
            rates.append(( var, sec, fps ))



    labels = ['var', 'time', 'fps']
    framerate = pd.DataFrame.from_records(rates, columns=labels,
                                           coerce_float=True)
    p = sb.lineplot(x=framerate['time'],
                    y=framerate['fps'],
                    ci='sd', data=framerate,
                    hue='var',
                    ax=axs)
    p = sb.lineplot(x=framerate['time'],
                    y=30,
                    ci='sd',
                    color='red', linewidth=5,
                    ax=axs,
                    legend = False)
    plt.suptitle(f'{options.label} - {description}')
    axs.set_title('Alt Framerate in fps\n - Red is 30 fps')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
   # p.set_ylim(0, 120)
    p.set_xlabel('Presentation time in sec')
    p.set_ylabel('fps')
    plt.suptitle(f'{options.label} - {description}')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_fps_alt_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')

    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    axs.set_title('Fps')
    p = sb.histplot(x=framerate['fps'],
                    data=framerate,
                    hue='var',
                    ax=axs)
   # p.set_ylabel('fps')
    #p.set_xlim(0, 120)
    axs.set_title('Alt Framerate histogram')
    plt.suptitle(f'{options.label} - {description}')
    name = options.label + '_fps_alt_hist_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def plot_framerate(data, variant, description, options):
    print('Calculate the distance between every finished decoding and invert')

    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    variants = np.unique(data[variant])
    fps = int(np.mean(data['fps']))
    print(f'fps = {fps}')

    calculated = None
    for var in variants:
        print(f'checking {var}')
        filtered = data.loc[data[variant] == var]
        print(f'{filtered}')
        filtered.loc[filtered[variant] == var,'fps'] = 1000000000.0/(filtered['stoptime'].shift(
                -1, axis='index', fill_value=0) - filtered['stoptime'])

        filtered['av_fps'] = filtered['fps'].rolling(
            fps,  min_periods=fps, win_type=None).sum()/fps

        if isinstance(calculated, type(None)):
            calculated = filtered
        else:
            calculated = calculated.append(filtered)

    p = sb.lineplot(x=calculated['pts']/1000000,
                    y=calculated['av_fps'],
                    ci='sd', data=calculated,
                    hue=variant,
                    ax=axs)
    p = sb.lineplot(x=data['pts']/1000000,
                    y=30,
                    ci='sd',
                    color='red', linewidth=5,
                    ax=axs,
                    legend = False)
    plt.suptitle(f'{options.label} - {description}')
    axs.set_title('Framerate in fps\n- Red is 30 fps')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    #p.set_ylim(0, 0)
    p.set_xlabel('Presentation time in sec')
    p.set_ylabel('fps')
    plt.suptitle(f'{options.label} - {description}')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_fps_' + description
    plt.savefig(name.replace(' ', '_')+'.png', format='png')
    if options.csv is not None:
        name = f'{options.csv}/{name}_fps_data.csv'
        calculated.to_csv(clean_name(name))

    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    axs.set_title('Fps')
    p = sb.histplot(x='av_fps',
                    data=calculated,
                    hue=variant,
                    ax=axs)
   # p.set_ylabel('fps')
   # p.set_xlim(0, 120)
    plt.suptitle(f'{options.label} - {description}')
    name = options.label + '_fps_hist_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')

def plot_times(data, variant, description, options):
    print('Plot times')
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    sb.lineplot(x=data.index,
                y=data['starttime']/1000000,
                ci='sd', data=data, hue=variant,
                ax=axs)
    p = sb.lineplot(x=data.index,
                    y=data['stoptime']/1000000,
                    ci='sd', data=data, hue=variant,
                    ax=axs)
    plt.suptitle(f'{options.label} - {description}')
    axs.set_title('starttime vs stoptime')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)

    p.set_xlabel('Presentation time in sec')
    p.set_ylabel('Time in ms')
    plt.suptitle(f'{options.label} - {description}')


def plot_concurrency(data, description, options):
    print('Plot concurrency')
    fig, axs = plt.subplots(figsize=(12, 9), dpi=200)
    data['simple'] = round(data['starttime']/1000000)
    p = sb.barplot(x=data['simple'],
                   y=data['conc'],
                   ci='sd', data=data,
                   ax=axs)

    axs.set_title('Concurrent codecs')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)

    p.set_ylabel('Number codecs')
    p.set_xlabel('Start time of encoding in sec')
    plt.suptitle(f'{options.label} - {description}')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_concurrent_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def plot_inflight_data(data, variant, description, options):
    print('Plot inflight data')
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    p = sb.lineplot(x=data['pts']/1000000,
                    y=data['inflight'],
                    ci='sd', data=data, hue=variant,
                    ax=axs)

    axs.set_title('Frames in flight')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)

    p.set_ylabel('Number of frames in codec at the same time')
    p.set_xlabel('Time in sec')
    plt.suptitle(f'{options.label} - {description}')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_inflight_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')

    # "gpu_model": "Adreno615v2",
    # "gpu_max_clock": "180",
    # "gpu_min_clock": "780",
    # {
    #  "time_sec": 3.7,
    #  "load_percentage": 38
    # },
    # {
    #  "time_sec": 3.8,
    #  "load_percentage": 38
    # },
    # {
    #  "time_sec": 0,
    #  "clock_MHz": "180"
    # },


def plot_gpuprocessing(gpuload, description, options):
    print('Plot gpu processing')
    maxclock = gpuload['gpu_max_clock'].values[0]
    gpumodel = gpuload['gpu_model'].values[0]

    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)
    sb.lineplot(x=gpuload['time_sec'],
                y=gpuload['clock_perc'],
                ci='sd', data=gpuload,
                ax=axs, label=f'GPU clock percentage (max: {maxclock} MHz)')
    p = sb.lineplot(x=gpuload['time_sec'],
                    y=gpuload['load_percentage'],
                    ci='sd', data=gpuload,
                    ax=axs, label='GPU load percentage')

    p.set_xlabel('Time in sec')
    p.set_ylabel('Percentage')

    axs.set_title(f'Gpu load ({gpumodel})')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    plt.suptitle(f'{options.label} - {description}')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_gpu-load_' + description + '.png'
    plt.savefig(name.replace(' ', '_'), format='png')


def parse_encoding_data(json, inputfile):
    print('Parse encoding data')
    try:
        data = pd.DataFrame(json['frames'])
        data['source'] = inputfile
        data['codec'] = json['settings']['codec']
        data['description'] = json['description']
        data['test'] = json['test']
        data['bitrate'] = json['settings']['bitrate']
        data['height'] = json['settings']['height']
        data['duration_ms'] = round((data['pts'].shift(-1, axis='index',
                                     fill_value=0) - data['pts']) / 1000, 2)
        data['fps'] = round(1000.0/(data['duration_ms']), 2)
        data.fillna(0)
    except Exception:
        return None
    return data


def parse_decoding_data(json, inputfile):
    print('Parse decoding data')
    decoded_data = None
    try:
        decoded_data = pd.DataFrame(json['decoded_frames'])
        print(f'decoded data: {decoded_data}')
        decoded_data['source'] = inputfile
        if (len(decoded_data) > 0):
            try:
                decoded_data['codec'] = json['decoder_media_format']['mime']
            except Exception:
                print('Failed to read decoder data')
                decoded_data['codec'] = 'unknown codec'
            try:
                decoded_data['height'] = json['decoder_media_format']['height']
            except Exception:
                print('Failed to read decoder data')
                decoded_data['height'] = 'unknown height'

            decoded_data = decoded_data.loc[decoded_data['proctime'] >= 0]
            #oh no we may have b frames...
            decoded_data['duration_ms'] = round((decoded_data['pts'].shift(-1, axis='index',
                                     fill_value=0) - decoded_data['pts']) / 1000, 2)
            decoded_data['fps'] = round(1000.0/(decoded_data['duration_ms']), 2)
            decoded_data.fillna(0)
    except Exception as ex:
        print(f'Failed to parse decode data for {inputfile}: {ex}')
        decoded_data = None

    return decoded_data


def parse_gpu_data(json, inputfile):
    print('Parse gpu data')
    gpu_data = None
    try:
        gpu_data = pd.DataFrame(json['gpu_data']['gpu_load_percentage'])
        if len(gpu_data) > 0:
            gpuclock_data = pd.DataFrame(json['gpu_data']['gpu_clock_freq'])
            gpu_max_clock = int(json['gpu_data']['gpu_max_clock'])
            gpu_data['clock_perc'] = (
                 100.0 * gpuclock_data['clock_MHz'].astype(float) /
                 gpu_max_clock)
            gpu_data = gpu_data.merge(gpuclock_data)
            gpu_model = json['gpu_data']['gpu_model']
            gpu_data['source'] = inputfile
            gpu_data['gpu_max_clock'] = gpu_max_clock
            gpu_data['gpu_model'] = gpu_model
            gpu_data.fillna(0)
    except Exception as ex:
        print(f'GPU parsing failed: {ex}')
        pass
    return gpu_data


def calc_infligh(frames, time_ref):
    sources = pd.unique(frames['source'])
    coding = []
    for source in sources:
        # Calculate how many frames starts encoding before a frame has finished
        # relying on the accurace of the System.nanoTime()
        inflight = []
        filtered = frames.loc[frames['source'] == source]
        start = np.min(filtered['starttime'])
        stop = np.max(filtered['stoptime'])
        # Calculate a time where the start offset (if existing) does not
        # blur the numbers
        coding.append([source, start - time_ref, stop - time_ref])
        for row in filtered.iterrows():
            start = row[1]['starttime']
            stop = row[1]['stoptime']
            intime = (filtered.loc[(filtered['stoptime'] > start) &
                                   (filtered['starttime'] < stop)])
            count = len(intime)
            inflight.append(count)
        frames.loc[frames['source'] == source, 'inflight'] = inflight

    labels = ['source', 'starttime', 'stoptime']
    concurrent = pd.DataFrame.from_records(coding, columns=labels,
                                           coerce_float=True)

    # calculate how many new encoding are started before stoptime
    inflight = []
    for row in concurrent.iterrows():
        start = row[1]['starttime']
        stop = row[1]['stoptime']
        count = (len(concurrent.loc[(concurrent['stoptime'] > start) &
                                    (concurrent['starttime'] < stop)]))
        inflight.append(count)
    concurrent['conc'] = inflight
    return frames, concurrent


def clean_name(name):
    ret = name.translate(str.maketrans({',': '_', ' ': '_'}))
    print(f'{name} -> {ret}')
    return ret

def parse_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('files', nargs='+', help='file to analyze')
    parser.add_argument('--label', default='')
    parser.add_argument('-c', '--concurrency', action='store_true',
                        help='plot encodings overlapping in time')
    parser.add_argument('-pt', '--proctime', action='store_true',
                        help='plot processing time per frame for a codec')
    parser.add_argument('-br', '--bitrate', action='store_true')
    parser.add_argument('-fs', '--framesize', action='store_true')
    parser.add_argument('-if', '--inflight', action='store_true',
                        help='plot number of frames in the codec '
                             'simultanously')
    parser.add_argument('-dd', '--decode_data', action='store_true',
                        help='plot data for decoder')
    parser.add_argument('-gd', '--gpu_data', action='store_true',
                        help='plot performance data for the gpu')
    parser.add_argument('--csv',
                        help='folder for export data in csv format')
    parser.add_argument('--limit', action='store_true',
                        help='add sane axis limits for plots')
    parser.add_argument('--no_show', action='store_true',
                        help='do not show the plots')

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
    pts_mult = 1000000

    for inputfile in options.files:
        if options.files == 1 and len(options.label) == 0:
            splits = inputfile.rsplit('/')
            filename = splits[len(splits)-1]
            options.label = filename

        with open(inputfile) as json_file:
            print(f'Checking {inputfile}')
            alldata = json.load(json_file)

            video_length = 0
            first_frame = 0
            last_frame = 0
            encoding_data = parse_encoding_data(alldata, inputfile)
            decoded_data = parse_decoding_data(alldata, inputfile)

            first_frame_start = -1
            last_frame_end = -1
            if not isinstance(encoding_data, type(None)):
                print(f'encoding_data data len = {len(encoding_data)}')
                # pts is in microsec
                first_frame = np.min(encoding_data['pts'])
                # approx.
                last_frame = np.max(encoding_data['pts'])
                first_frame_start = np.min(encoding_data['starttime'])
                last_frame_end = np.max(encoding_data['stoptime'])
                video_length = (last_frame - first_frame)/pts_mult
            elif not isinstance(decoded_data, type(None)):
                print(f'decoding data len = {len(decoded_data)}')
                # pts is in microsec
                first_frame = np.min(decoded_data['pts'])
                # approx.
                last_frame = np.max(decoded_data['pts'])
                first_frame_start = np.min(decoded_data['starttime'])
                last_frame_end = np.max(decoded_data['stoptime'])
                video_length = (last_frame - first_frame)/pts_mult


            print(f'{type(decoded_data)}')
            gpu_data = parse_gpu_data(alldata, inputfile)
            framecount = alldata['framecount']
            total_time_sec = round((last_frame_end-first_frame_start) /
                                 1000000000.0, 2)
            if  not isinstance(encoding_data, type(None)):
                proctime_sec = np.sum(encoding_data.loc[encoding_data['proctime'] > 0].proctime)/(1000000000)
            else:
                proctime_sec = -1;

            print('__')
            print('Media = {:s}'.format(alldata['encodedfile']))
            print('Test run = {:s}'.format(alldata['test']))
            print('Description = {:s}'.format(alldata['description']))
            print('Video length = {:.2f}'.format(video_length))
            print(f'Framecount = {framecount}')
            print(f'Proctime {proctime_sec} sec')
            print(f'Total time = {total_time_sec} sec')
            print('Codec = {:s}'.format(alldata['settings']['codec']))
            print('Bitrate = {:d}'.format(alldata['settings']['bitrate']))
            print('Height = {:d}'.format(alldata['settings']['height']))
            # Mean processing incuded file reading and format changes etc.

            if not isinstance(encoding_data, type(None)):
                mean_proc = 1000 * total_time_sec/len(encoding_data)
                print('Mean processing time = {:.2f} ms'.
                  format(mean_proc))

                # Latency is the time it takes for the
                # frame to pass the encoder
                mean_latency = np.mean(encoding_data.
                                       loc[encoding_data['proctime'] > 0,
                                           'proctime'])/1000000
                print('Mean frame latency = {:.2f} ms'.format(mean_latency))
                print('Encoding speed = {:.2f} times'.format(
                    (video_length/total_time_sec)))
                encoding_data['mean_proc_ms'] = mean_proc
            if not isinstance(decoded_data, type(None)):
                mean_proc = 1000 * total_time_sec/len(decoded_data)
                print('Mean processing time = {:.2f} ms'.
                  format(mean_proc))
                # Latency is the time it takes for the
                # frame to pass the encoder
                mean_latency = np.mean(decoded_data.
                                       loc[decoded_data['proctime'] > 0,
                                           'proctime'])/1000000
                print('Mean frame latency = {:.2f} ms'.format(mean_latency))
                print('Encoding speed = {:.2f} times'.format(
                    (video_length/total_time_sec)))
                decoded_data['mean_proc_ms'] = mean_proc
            print('__')

        if isinstance(accum_data, type(None)):
            accum_data = encoding_data
        elif not isinstance(encoding_data, type(None)):
            accum_data = accum_data.append(encoding_data)

        if isinstance(accum_dec_data, type(None)):
            accum_dec_data = decoded_data
        elif not isinstance(decoded_data, type(None)):
            accum_dec_data = accum_dec_data.append(decoded_data)

        if isinstance(accum_gpu_data, type(None)):
            accum_gpu_data = gpu_data
        elif not isinstance(gpu_data, type(None)):
            accum_gpu_data = accum_gpu_data.append(gpu_data)

    print(f'All files checked')
    concurrency = None
    frames = None
    if not isinstance(accum_data, type(None)):
        frames = accum_data.loc[accum_data['size'] > 0]
        sb.set(style='whitegrid', color_codes=True)
        # codecs = pd.unique(frames['codec'])
        # sources = pd.unique(frames['source'])
        first = np.min(frames['starttime'])

        frames, concurrency = calc_infligh(frames, first)

    if options.inflight:
        plot_inflight_data(frames, 'codec', 'encoding pipeline', options)

    if (options.concurrency and concurrency is not None and
            len(concurrency) > 1):
        plot_concurrency(concurrency, 'conc', options)

    if frames is not None:
        #plot_processing_framerate(frames, 'test', 'encoder', options)
        if options.framesize:
            plot_framesize(frames, 'test', 'encoder', options)

        if options.bitrate:
            plot_bitrate(frames, 'test', 'encoder', options)

        if options.proctime:
            plot_processingtime(frames, 'test', 'encoder', options)
            plot_framerate(frames, 'test', 'encoder', options)
        if options.csv is not None:
            name = f'{options.csv}/{options.label}_encoded_frames.csv'
            frames.to_csv(clean_name(name))

    print(f'dec data: {accum_dec_data}')
    if (options.decode_data and not isinstance(accum_dec_data, type(None)) and
            len(accum_dec_data) > 0):
        first = np.min(accum_dec_data['starttime'])
        accum_dec_data, concurrency = calc_infligh(accum_dec_data, first)
        plot_inflight_data(accum_dec_data, 'codec', 'decoding pipeline',
                           options)
        plot_framerate(accum_dec_data, 'codec', 'decoder', options)

        plot_processingtime(accum_dec_data, 'codec', 'decoder', options)

        if options.csv is not None:
            name = f'{options.csv}/{options.label}_decoded_frames.csv'
            accum_dec_data.to_csv(clean_name(name))

    if (options.gpu_data and accum_gpu_data is not None and
            len(accum_gpu_data) > 0):
        plot_gpuprocessing(accum_gpu_data, 'gpu load', options)
        if options.csv is not None:
            name = f'{options.csv}/{options.label}_gpu_data.csv'
            gpu_data.to_csv(clean_name(name))

    if not options.no_show:
        sb.set(style='whitegrid', color_codes=True)
        plt.show()


if __name__ == '__main__':
    main()
