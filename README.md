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
* protobuf (https://developers.google.com/protocol-buffers/docs/downloads)

List of required python packages:
* humanfriendly
* numpy
* pandas
* seaborn
* protobuf (google protocol buffers)



# 2. Operation: Get a List of Available Codecs

(1) install the app and give it permission to access the external storage:
```
$ ./scripts/encapp.py install
$ adb shell appops set --uid com.facebook.encapp MANAGE_EXTERNAL_STORAGE allow
```


(2) run the list command:
```
$ ./scripts/encapp.py list
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
$ encapp.py run tests/bitrate_buffer.pbtxt
codec test: {'videofile': None, 'configfile': '/media/johan/data/code/encapp/tests/bitrate.pbtxt', 'encoder': None, 'output': None, 'bitrate': None, 'desc': 'testing', 'inp_resolution': None, 'out_resolution': None, 'inp_framerate': None, 'out_framerate': None}
cmd: protoc -I / --encode="Tests" /media/johan/data/code/encapp/proto/tests.proto < /media/johan/data/code/encapp/tests/bitrate.pbtxt > /media/johan/data/code/encapp/tests/bitrate.bin
run test: /media/johan/data/code/encapp/tests/bitrate.bin
Videofile exchanged? None
test {
  common {
    id: "Bitrate"
    description: "Verify encoding bitrate"
  }
  input {
    filepath: "/sdcard/akiyo_qcif.yuv"
    resolution: "176x144"
    framerate: 30.0
  }
  configure {
    codec: "OMX.google.h264.encoder"
    surface: true
    bitrate: "100 kbps"
  }
}

files_to_push = ['/tmp/akiyo_qcif.yuv', 'bitrate.run.bin']
Push /tmp/akiyo_qcif.yuv
Push bitrate.run.bin
Exit from 14903
pull encapp_a6896c13-1c2a-48b0-9e6c-a2e1e25a632a.json to testing_Portal_2022-03-04_16_17/bitrate_files/
pull encapp_a6896c13-1c2a-48b0-9e6c-a2e1e25a632a.mp4 to testing_Portal_2022-03-04_16_17/bitrate_files/
results collect: ['testing_Portal_2022-03-04_16_17/bitrate_files//encapp_a6896c13-1c2a-48b0-9e6c-a2e1e25a632a.json']

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
$ encapp.py run tests/bitrate_buffer.720p.pbtxt
codec test: {'videofile': None, 'configfile': '/media/johan/data/code/encapp/tests/bitrate_buffer.720p.pbtxt', 'encoder': None, 'output': None, 'bitrate': None, 'desc': 'testing', 'inp_resolution': None, 'out_resolution': None, 'inp_framerate': None, 'out_framerate': None}
cmd: protoc -I / --encode="Tests" /media/johan/data/code/encapp/proto/tests.proto < /media/johan/data/code/encapp/tests/bitrate_buffer.720p.pbtxt > /media/johan/data/code/encapp/tests/bitrate_buffer.720p.bin
run test: /media/johan/data/code/encapp/tests/bitrate_buffer.720p.bin
Videofile exchanged? None
test {
  common {
    id: "Bitrate"
    description: "Verify encoding bitrate - buffer"
  }
  input {
    filepath: "/sdcard/KristenAndSara_1280x720_60.yuv"
    resolution: "1280x720"
    framerate: 60.0
  }
  configure {
    codec: "OMX.google.h264.encoder"
    bitrate: "1000 kbps"
  }
}

files_to_push = ['/tmp/KristenAndSara_1280x720_60.yuv', 'bitrate_buffer.720p.run.bin']
Push /tmp/KristenAndSara_1280x720_60.yuv
Push bitrate_buffer.720p.run.bin
Exit from 17015
pull encapp_72a1ff62-a7b2-4ab2-9600-61896d24c8dd.json to testing_Portal_2022-03-04_16_23/bitrate_buffer.720p_files/
pull encapp_72a1ff62-a7b2-4ab2-9600-61896d24c8dd.mp4 to testing_Portal_2022-03-04_16_23/bitrate_buffer.720p_files/
results collect: ['testing_Portal_2022-03-04_16_23/bitrate_buffer.720p_files//encapp_72a1ff62-a7b2-4ab2-9600-61896d24c8dd.json'

```


# 4. Multiple Encoding Experiments

First at all create your own proto configuration file:
Get the codec from the codec list.

```
test {
    common {
        id: "X"
        description: "My test is simple"
    }
    input {
        filepath: "mediaX.yuv"
        resolution: "WxH"
        framerate: X
    }
    configure {
        codec: "X"
        bitrate: "XXX kbps"
    }
}

# 5. Test Definition Settings

Definitions of the keys in the proto buf definition: proto/tests.proto


## 5.1. Encoder/Decoder Configuration

Additional settings (besides bitrate etc).

Example:
```
    configure {
        codec: "encoder.avc"
        bitrate: "100 kbps"
        bitrate_mode: cbr
        i_frame_interval: 2000
        parameter {
            key: "vendor.qti-ext-enc-ltr-count.num-ltr-frames"
            type: intType
            value: "3"
        }
        parameter {
            key: "vendor.qti-ext-enc-caps-ltr.max-frames"
            type: intType
            value: "3"
        }
    }
```

## 5.2. Runtime Configuration

Each setting consists of a pair `{FRAME_NUM, VALUE}`, where the VALUE can be an empty string.

* Dynamically change framerate:
```
    runtime {
        video_bitrate {
            framenum: 60
            bitrate: "50k"
        }
        video_bitrate {
            framenum: 120
            bitrate: "100k"
        }
        video_bitrate {
            framenum: 180
            bitrate: "400k"
        }
    }
```

* Low latency (Android API 30)
```

    decoder_runtime
    {
        parameter {
            framenum: 60
            key: low-latency"
            type: intType
            value: 1
        }
    }
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


# 7. Requirements

## 7.1. Linux

Just install the pip packages.

```
$ sudo pip install humanfriendly numpy pandas seaborn protobuf
```


## 7.2. OSX

Make sure your pip3 and your default python3 shell are coherent.

```
bash-3.2$ sudo chown -R $(whoami) /usr/local/bin /usr/local/lib /usr/local/sbin

bash-3.2$ brew install python@3.9
...

bash-3.2$ brew link --overwrite python@3.9
Linking /usr/local/Cellar/python@3.9/3.9.12... 24 symlinks created.

bash-3.2$ pip3 install protobuf
...
Collecting protobuf
  Downloading protobuf-3.19.4-cp39-cp39-macosx_10_9_x86_64.whl (961 kB)
...
```
