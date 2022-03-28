# encapp

encapp is a tool to test video encoders in Android.

It provides an easy way to test an android video encoder by easily combining parameters like:

* codecs
* bitrate
* framerate
* i-frame interval
* coding mode
* others

encapp also has support for dynamically changing framerate, bitrate, and ltr.

This document describes how to use the tool.
* for tool development, check [README.dev.md](README.dev.md).
* for details on test configuration, check [README.test.md](README.test.md).


# 1. Prerequisites

For running encapp:
* adb connection to the device being tested.
* ffmpeg with decoding support for the codecs to be tested
* install some python packages


List of required python packages:
* humanfriendly
* numpy
* pandas
* seaborn


# 2. Operation: Get a List of Available Codecs

```
$ ./scripts/encapp.py --list
adb devices -l
adb -s <serial> shell ls /sdcard/
model = <model_name>
adb -s <serial> install -g proj/encapp/scripts/../app/build/outputs/apk/debug/com.facebook.encapp-v1.0-debug.apk
adb -s <serial> shell am start -W -e ui_hold_sec 3 -e list_codecs a com.facebook.encapp/.MainActivity
adb -s <serial> pull /sdcard/codecs.txt codecs_<model_name>.txt
--- List of supported encoders  ---

        MediaCodec {
            name: OMX.google.h264.encoder
            type {
                mime_type: video/avc
                max_supported_instances: 32
                color {
                    format: 2135033992
                    name: COLOR_FormatYUV420Flexible
                }
                color {
                    format: 19
                    name: COLOR_FormatYUV420Planar
                }
                ...
            }
        }

*******
--- List of supported decoders  ---

        MediaCodec {
            name: OMX.google.h264.decoder
            type {
                mime_type: video/avc
                max_supported_instances: 32
                color {
                    format: 2135033992
                    name: COLOR_FormatYUV420Flexible
                }
                ...
            }
        }

```

Note: The `scripts/encapp.py` scripts will install a prebuild apk before running the test. If you have several devices attached to your host, you can use either the "`ANDROID_SERIAL`" environment variable or the "`--serial <serial>`" CLI option.


# 3. Operation: Run an Encoding Experiment Using encapp

## 3.1. Small QCIF Encoding

First, select one of the codecs from step 4. In this case, we will use `OMX.google.h264.encoder`.

Push the (raw) video file to be encoded into the device. Note that we are using a QCIF video (176x144).
```
$ wget https://media.xiph.org/video/derf/y4m/akiyo_qcif.y4m -O /tmp/akiyo_qcif.y4m
$ ffmpeg -i /tmp/akiyo_qcif.y4m -f rawvideo -pix_fmt nv12 /tmp/akiyo_qcif.yuv
```

Now run the h264 encoder (`OMX.google.h264.encoder`):
```
$ encapp.py /media/johan/data/code/encapp/tests/simple.qcif.json 
adb devices -l
adb -s <serial>  shell ls /sdcard/
adb -s <serial>  rm /sdcard/encapp_*
tests [{'description': 'Simple QCIF Test', 'input_files': ['/tmp/akiyo_qcif.yuv'], 'input_resolution': '176x144', 'input_fps': '30', 'codecs': ['OMX.google.h264.encoder'], 'encode_resolutions': ['176x144'], 'bitrates': ['100k']}]
push data for test = {'description': 'Simple QCIF Test', 'input_files': ['/tmp/akiyo_qcif.yuv'], 'input_resolution': '176x144', 'input_fps': '30', 'codecs': ['OMX.google.h264.encoder'], 'encode_resolutions': ['176x144'], 'bitrates': ['100k']}
adb -s <serial>  push /tmp/akiyo_qcif.yuv /sdcard/
adb -s <serial>  shell am start -W  -e test /sdcard/simple.qcif.json_1.json com.facebook.encapp/.MainActivity
mkdir encapp_<serial> __<date>/simple.qcif_files/
pull encapp_e992d472-143f-4289-ae2b-fbde81ff3c4f.json to encapp_Portal__2022-01-18_23_37/simple.qcif_files/
pull encapp_e992d472-143f-4289-ae2b-fbde81ff3c4f.mp4 to encapp_Portal__2022-01-18_23_37/simple.qcif_files/
adb -s <serial>  shell rm /sdcard/akiyo_qcif.yuv
adb -s <serial>  shell rm /sdcard/simple.qcif.json_1.json

```

Results are copied into a directory called `encapp_*`. They include:
* encoded video, using the mp4 container for h264, and the ivf container for vp8 and vp9
* json file containing per-frame information


## 3.2. HD Video Encoding

Now, let's run the h264 encoder in an HD file. We will just select the codec ("h264"), and let encapp choose the actual encoder.

```
$ wget https://media.xiph.org/video/derf/y4m/KristenAndSara_1280x720_60.y4m -O /tmp/KristenAndSara_1280x720_60.y4m
$ ffmpeg -i /tmp/KristenAndSara_1280x720_60.y4m -f rawvideo -pix_fmt yuv420p /tmp/KristenAndSara_1280x720_60.yuv
```

```
$ encapp.py tests/simple.720p.json 
adb devices -l
adb -s <serial>  shell ls /sdcard/
adb -s <serial>  rm /sdcard/encapp_*
tests [{'description': 'Simple HD Test', 'input_files': ['/tmp/KristenAndSara_1280x720_60.yuv'], 'input_resolution': '1280x720', 'input_fps': '60', 'codecs': ['OMX.google.h264.encoder'], 'encode_resolutions': ['1280x720'], 'bitrates': ['1000k']}]
push data for test = {'description': 'Simple HD Test', 'input_files': ['/tmp/KristenAndSara_1280x720_60.yuv'], 'input_resolution': '1280x720', 'input_fps': '60', 'codecs': ['OMX.google.h264.encoder'], 'encode_resolutions': ['1280x720'], 'bitrates': ['1000k']}
adb -s <serial>  push /tmp/KristenAndSara_1280x720_60.yuv /sdcard/
adb -s <serial>  shell am start -W  -e test /sdcard/simple.720p.json_1.json com.facebook.encapp/.MainActivity
mkdir encapp_<serial> __<date>/simple.720p_files/
pull encapp_851b3fff-10cb-48b0-8244-b83424c84b01.json to encapp_Portal__2022-01-18_23_33/simple.720p_files/
pull encapp_851b3fff-10cb-48b0-8244-b83424c84b01.mp4 to encapp_Portal__2022-01-18_23_33/simple.720p_files/
adb -s <serial>  shell rm /sdcard/KristenAndSara_1280x720_60.yuv
adb -s <serial>  shell rm /sdcard/simple.720p.json_1.json

```


# 4. Multiple Encoding Experiments

First at all create your own json configuration file:

```
$ encapp_tests.py --config myconfig.json
$ cat myconfig.json
[
    {
        "description": "sample",
        "input_files": [
            ""
        ],
        "use_surface_enc": 1,
        "input_format": "mp4",
        "input_resolution": "1280x720",
        "codecs": [
            "hevc"
        ],
        "encode_resolutions": [
            "1280x720"
        ],
        "rc_modes": [
            "cbr"
        ],
        "bitrates": [
            500,
            1000,
            1500,
            2000,
            2500
        ],
        "i_intervals": [
            2
        ],
        "temporal_layer_counts": [
            1
        ],
        "playout-frames": 10,
        "enc_loop": 0
    }
]
```

Open the `myconfig.json` file in a editor and edit the test to add a file in "`input_files`".

Example 1: using an already-encoded (mp4) file (the script will convert it to raw before sending it to the device):

```
 [
    {
        "description": "sample",
        "input_files": [
            "red_short_hq_x264_yuv420p.mp4"
        ],
        "use_surface_enc": 1,
        "input_format": "mp4",
        "input_resolution": "1280x720",
        "codecs": [
            "hevc"
        ],
        "encode_resolutions": [
            "1280x720"
        ],
        "rc_modes": [
            "cbr"
        ],
        "bitrates": [
            3000,
            7000,
            10000
        ],
        "i_intervals": [
            0,
            1,
            10
        ],
        "playout-frames": 30
    }
]
```

Example 2: using a raw file:
```
[
    {
        "description": "sample",
        "input_files": [
            "red_short_720p_nv12_2_35sec_gop-10sec.yuv"
        ],
        "use_surface_enc": 0,
        "input_format": "nv12",
        "input_resolution": "1280x720",
        "codecs": [
            "hevc"
        ],
        "encode_resolutions": [
            "1280x720"
        ],
        "rc_modes": [
            "cbr"
        ],
        "bitrates": [
            3000,
            7000,
            10000
        ],
        "i_intervals": [
            0,
            1,
            10
        ],
        "playout-frames": 30
    }
]
```

# 5. JSON Test Definition Settings

Definitions of the keys in the sample json file

* '`bitrates`': list of encoding bitrates
* '`codecs`': list of encoders
* '`conc`': number of concurrent tests to run
* '`configure`': configure encoding parameters (see below for more information)
* '`configure_decoder`': configure decoder parameters
* '`decoder`': Normally decoder is created based on encoded file type but the specific decoder can be specified here.
* '`description`': test description (may be empty)
* '`enc_loop`': number of times the encoding loop is run. Used for encoding time profiling. When `enc_loop` is greater than 1, there is no output video
* '`encode`': Boolean to indicate whether encoding should be performed [default: true]
* '`encode_resolutions`': list of encoding resolutions
* '`framerates`': list of encoding framerates (drop frames to reach a specified frame rate)
* '`playout-frames`': duration of the encoding (ignored when `enc_loop` > 0)
* '`i_intervals`': list of I-frame intervals
* '`input_files`': list of input files
* '`input_fps`': input frame rate [for raw inputs]
* '`input_format`': input video format: mp4, nv12, yuv420p [for raw inputs]
* '`input_resolution`': input video resolution, as "`<width>x<height>`" [for raw inputs]
* '`loop`': number of times a source is looped [use to get a longer input video]
* '`pursuit`': pursuitmode. Start multiples of a test with a one sec delay. Values can be:
	* (1) '-1`': for infinite or until failure
	* (2) '0`': for no pursuit mode at all [default]
	* (3)  "`<X>`": start test until X is reached
* '`runtime_parameters`': settings corresponding to a certain frame (see below for more information)
* '`decoder_runtime_parameters`': `runtime_parameters` for the decoder
* '`realtime`': read input video in realtime i.e. wait until next pts
* '`rc_modes`': list of rate control modes
* '`temporal_layer_counts`': number of temporal layers


## 5.1. Encoder/Decoder Configuration

Additional settings (besides bitrate etc).

Example:
```
"configure":
[{
    "name": "tl-schema",
    "type": "string",
    "setting": "android.generic.2"
}],

"configure_decoder":
[{
    "name": "vendor.qti-ext-dec-picture-order.enable",
    "type": "int",
    "setting": "1"
},
{
  "name": "vendor.qti-ext-dec-low-latency.enable",
  "type": "int",
  "setting": "1"
}]
```

## 5.2. Runtime Configuration

Each setting consists of a pair `{FRAME_NUM, VALUE}`, where the VALUE can be an empty string.

* Dynamically change framerate:
```
`"runtime_parameters":
[{
    "name": "fps",
    "type": "int",
    "settings": [
    {
        "20": "15"
    },
    {
        "40": "5"
    },
    {
        "60": "20"
    },
    {
        "80": "30"
    }]
}]`
```

* Low latency (Android API 30)
```
"decoder_runtime_parameters":
[{
    "name": "low-latency",
    "type": "int",
    "settings": [
    {
        "0": "1"
    }]
}]
```


# 6. Navigating results

The names json result files does not give any clues as to what settings have been used.
To figure that out run:
```
$ encapp_search.py
```

Running it without any arguments will index all parsable json files in the current folder and below.

To find all 1080p files run:
```
$ encapp_search.py -s 1920x1080
```


Example: Calculate quality degradation

Run

```
$ encapp_quality.py --header --media MEDIA_FOLDER $(encapp_search.py)
```

Since the json file only contains the name of the source for an encoding the source folder needs to be provided.
The output will be a csv file (default name is 'quality.csv') containing vmaf, ssim, psnr and other relevant properties.
