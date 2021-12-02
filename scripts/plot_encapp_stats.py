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
                palette=['green'],
                label="mean proc time (ms)")
    axs.set_title('Proc time in ms')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    
    p.set_xlabel("Presentation time in sec")
    p.set_ylabel("Time in ms")
    plt.suptitle(f"{options.label} - {description}")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_proc-time_' + description + '.png'
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

def plot_gpuprocessing(gpuload, gpuclock, description, options, maxclock):
    fig, axs = plt.subplots(nrows=1, figsize=(12, 9), dpi=200)   

    sb.lineplot(x=gpuclock['time_sec'],
                y=gpuclock['clock_perc']/maxclock,
                ci="sd", data=gpuclock,
                ax=axs, label=f"GPU clock percentage (max: {maxclock} MHz)")
    p = sb.lineplot(x=gpuload['time_sec'],
                y=gpuload['load_percentage'],
                ci="sd", data=gpuload,
                ax=axs, label=f"GPU load percentage")
    
    p.set_xlabel("Time in sec")
    p.set_ylabel("Percentage")
    
    
    axs.set_title('Percentage')
    axs.legend(loc='best', fancybox=True, framealpha=0.5)
    plt.suptitle(f"{options.label} - {description}")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    name = options.label + '_gpu-load_' +  description + '.png'
    plt.savefig(name.replace(' ', '_'), format="png")


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
            print(f"proctime {proctime}, count {framecount}")
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
            
            #See if there is decoding information
            decoded_data = pd.DataFrame(alldata['decoded_frames'])
            if (len(decoded_data) > 0):
                try:
                    decoded_data['codec'] = alldata['decoder_media_format']['mime']
                except Exception:
                    print("Failed to read decoder data")
                    decoded_data['codec'] = "unknown codec"
                
                try:
                    #decoded_data['description'] = alldata['description']
                    #decoded_data['bitrate'] = alldata['settings']['bitrate']
                    decoded_data['height'] = alldata['decoder_media_format']['height']
                except Exception:
                    print("Failed to read decoder data")
                    decoded_data['height'] = "unknown height"
                
                if len(decoded_data.loc[decoded_data['proctime'] < 0]):
                    print("Have negative time...")
                decoded_data = decoded_data.loc[decoded_data['proctime'] >= 0]
                mean = sum(decoded_data['proctime']/framecount)
                decoded_data['meanproctime'] = mean

            #See if there is gpu information
            gpu_data = pd.DataFrame(alldata['gpu_data'])
            if (len(gpu_data) > 0):
                gpu_model = alldata['gpu_data']['gpu_model']
                gpu_max_clock = int(alldata['gpu_data']['gpu_max_clock'])
                #print("codec = " + str(alldata['decoder_media_format']['mime']) + ", mean = "  + str(mean/1000000))
                gpuload_data = pd.DataFrame(alldata['gpu_data']['gpu_load_percentage'])
                gpuclock_data = pd.DataFrame(alldata['gpu_data']['gpu_clock_freq'])
                gpuclock_data['clock_perc'] = gpuclock_data['clock_MHz'].astype(float)*100.0/gpu_max_clock
                plot_gpuprocessing(gpuload_data, gpuclock_data, gpu_model, options, gpu_max_clock)
        if accum_data is None:
            accum_data = data
        else:
            accum_data = accum_data.append(data)

    frames = accum_data.loc[accum_data['size'] > 0]
    frames.to_csv("data.csv")
    sb.set(style="whitegrid", color_codes=True)
    codecs = pd.unique(frames['codec'])
    for codec in codecs:
        data = frames.loc[frames['codec'] == codec]
        if options.bitrate:
            plot_framesize(data, "codec", "encoder", options)

        if options.proctime:
            plot_processingtime(data, "codec", "encoder", options)
        
        if len(decoded_data) > 0:
            if options.proctime:
                plot_processingtime(decoded_data, "codec", "decoder", options)
    
    plt.show()


if __name__ == "__main__":
    main()
