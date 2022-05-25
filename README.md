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
...
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
In this case the raw format is yuv420p (which is stated in the codec list from (2.2), COLOR_FormatYUV420Planar).0
For sw codecs in most case it is yuv420p. For hw codecs e.g. Qcom: COLOR_QCOM_FormatYUV420SemiPlanar this is nv12.
In the case of surface encoder from raw the source should be nv21 regardless of codec.
```
$ wget https://media.xiph.org/video/derf/y4m/akiyo_qcif.y4m -O /tmp/akiyo_qcif.y4m
$ ffmpeg -i /tmp/akiyo_qcif.y4m -f rawvideo -pix_fmt yuv420p /tmp/akiyo_qcif.yuv

Now run the h264 encoder (`OMX.google.h264.encoder`):
```
$ encapp.py run tests/bitrate_buffer.pbtxt
...
results collect: ['PATH/bitrate_files/encapp_XXX.json']

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
...
results collect: ['PATH/bitrate_buffer.720p_files/encapp_XXX.json'

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

To find all 720p files run:
```
$ encapp_search.py -s 1280x720
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


# 8. License

encapp is BSD licensed, as found in the [LICENSE](LICENSE) file.
