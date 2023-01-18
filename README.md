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

To make sure the test data is avalable. run:

```
$ ./scripts/prepare_test_data.sh
$ ls -Flasg /tmp/akiyo_qcif*
   56K -rw-r--r--. 1 ...    56604 Jan 18 11:10 /tmp/akiyo_qcif.mp4
11140K -rw-r--r--. 1 ... 11406644 Jan 18 11:10 /tmp/akiyo_qcif.y4m
11140K -rw-r--r--. 1 ... 11404800 Jan 18 11:10 /tmp/akiyo_qcif.yuv
```

Note that the scripts:
* 1. downloads a raw (y4m) video file to `/tmp/akiyo_qcif.y4m` (for encoder tests)
* 2. converts the raw format from y4m (yuv4mpeg2) to yuv (for encoder tests)
* 3. encodes the raw video using h264 into `/tmp/akiyo_qcif.mp4` (decoder tests)

Note that the resolution of the videos used is QCIF (176x144).


## 3.1. Small QCIF Encoding

First, select one of the codecs from step 4. In this case, we will use `OMX.google.h264.encoder`.

Push the (raw) video file to be encoded into the device. Note that we are using a QCIF video (176x144).
In this case the raw format is yuv420p (which is stated in the codec list from (2.2), COLOR_FormatYUV420Planar).0
For sw codecs in most case it is yuv420p. For hw codecs e.g. Qcom: COLOR_QCOM_FormatYUV420SemiPlanar this is nv12.
In the case of surface encoder from raw the source should be nv21 regardless of codec.

Now run the h264 encoder (`OMX.google.h264.encoder`):
```
$ ./scripts/encapp.py run tests/bitrate_buffer.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.y4m
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

Note that the json file is not the only result of the experiment copied to the directory specified using "--local-workdir":
```
$ ls -Flasg /tmp/test/
11140K -rw-r--r--.   1 ... 11404800 Jan 18 11:28 akiyo_qcif.y4m.176x144.29.97.yuv420p.yuv
    4K -rw-r--r--.   1 ...      327 Jan 18 11:28 bitrate_buffer.pbtxt
   48K -rw-r--r--.   1 ...    46820 Jan 18 11:28 device.json
   72K -rw-r--r--.   1 ...    70663 Jan 18 11:28 encapp_d4197e3d-c751-463b-935c-112381f15fcf.json
  276K -rw-r--r--.   1 ...   278933 Jan 18 11:28 encapp_d4197e3d-c751-463b-935c-112381f15fcf.mp4
  232K -rw-r--r--.   1 ...   234217 Jan 18 11:28 logcat.txt

```

Files include:
* results file (`encapp_<uuid>.json`)
* encoded video, using the mp4 container for h264/h265, and the ivf container for vp8/vp9 (`encapp_<uuid>.<ext>`)
* experiment run (`<name>.pbtxt`)
* raw video ised as input vp9 (`encapp_<uuid>.<ext>`)
* json file containing per-frame information


Note that the default encoder value is a Google-provided, software, h264 encoder (`OMX.google.h264.encoder`). If you want to test one of the encoders (from the "`list`" command output), use the CLI. For example, some Qualcomm devices offer an h264 HW encoder called "`OMX.qcom.video.encoder.avc`". In order to test it, use:

```
$ ./scripts/encapp.py run tests/bitrate_surface.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.y4m --codec OMX.qcom.video.encoder.avc
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

Note that we are changing the test from "`tests/bitrate_buffer.pbtxt`" to "`tests/bitrate_surface.pbtxt`". This is due to Buffer mode for HW encoders being broken at the moment.


## 3.2. HD Video Encoding

Now, let's run the h264 encoder in an HD file. We will just select the codec ("h264"), and let encapp choose the actual encoder.

```
$ ./scripts/prepare_test_data.hd.sh
$ ls -Flasg /tmp/kristen_and_sara*
  1556K -rw-r--r--. 1 ...   1592348 Jan 18 14:18 /tmp/kristen_and_sara.1280x720.60.mp4
811356K -rw-r--r--. 1 ... 830826065 Jan 18 12:06 /tmp/kristen_and_sara.1280x720.60.y4m
811352K -rw-r--r--. 1 ... 830822400 Jan 18 14:18 /tmp/kristen_and_sara.1280x720.60.yuv
```

```
$ wget https://media.xiph.org/video/derf/y4m/KristenAndSara_1280x720_60.y4m -O /tmp/KristenAndSara_1280x720_60.y4m
$ ffmpeg -i /tmp/KristenAndSara_1280x720_60.y4m -f rawvideo -pix_fmt yuv420p /tmp/KristenAndSara_1280x720_60.yuv

```

```
$ ./scripts/encapp.py run tests/bitrate_buffer.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/kristen_and_sara.1280x720.60.y4m
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

As in the QCIF case, the json file is not the only result of the experiment copied to the directory specified using "--local-workdir":

```
$ ls -Flasg /tmp/test/
811352K -rw-r--r--.   1 ... 830822400 Jan 18 14:20 kristen_and_sara.1280x720.60.y4m.1280x720.60.yuv420p.yuv
     4K -rw-r--r--.   1 ...       343 Jan 18 14:20 bitrate_buffer.pbtxt
   148K -rw-r--r--.   1 ...    148167 Jan 18 14:21 encapp_6fee5a13-af68-4ed0-a077-15591c4ec4fa.json
     0K drwxr-xr-x.   2 ...       220 Jan 18 14:21 ./
   508K -rw-r--r--.   1 ...    519828 Jan 18 14:21 encapp_6fee5a13-af68-4ed0-a077-15591c4ec4fa.mp4
    48K -rw-r--r--.   1 ...     46820 Jan 18 14:21 device.json
   256K -rw-r--r--.   1 ...    261729 Jan 18 14:21 logcat.txt
```


## 3.3 Video encoding with implicit decoding

For usability reasons (allow testing with generic video files), encapp allows using an encoded video as a source, instead of raw (yuv) video. In this case, encapp will choose one of its decoders (hardware decoders are prioritized), and decode the video to raw (yuv) before testing the encoder.

```
$ ./scripts/encapp.py run tests/bitrate_transcoder.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.mp4
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

Note that, in this case, we get the encoded video (`encapp_<uuid>.mp4`) and the original source video (`akiyo_qcif.mp4`).
```
 56K -rw-r--r--.   1 ...  56604 Jan 18 11:10 akiyo_qcif.mp4
  4K -rw-r--r--.   1 ...    266 Jan 18 14:25 bitrate_transcoder_show.pbtxt
 48K -rw-r--r--.   1 ...  46820 Jan 18 14:26 device.json
136K -rw-r--r--.   1 ... 137470 Jan 18 14:26 encapp_5c776aa6-e4ba-4dd4-897f-f150a6341d34.json
244K -rw-r--r--.   1 ... 246351 Jan 18 14:26 encapp_5c776aa6-e4ba-4dd4-897f-f150a6341d34.mp4
248K -rw-r--r--.   1 ... 250741 Jan 18 14:26 logcat.txt
```


## 3.4 Video decoding/encoding in series, while showing

Encapp also allows visualizing the video being decoded. We can run the previous experiment (implicit decoding and encoding), but this time the encoded video will be shown in the device. Note that we are seeing the decoded video, not the one being re-encoded.

The parameter for showing the video is `show` in the `input` section.

```
$ ./scripts/encapp.py run tests/bitrate_transcoder_show.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.mp4
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

As an alternative, you can use the non-show version of the test, but request the `show` parameter in the CLI.

```
$ ./scripts/encapp.py run tests/bitrate_transcoder.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.mp4 -e input.show true
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```



## 3.5 Video encoding using camera source

Encapp also supports camera using the Camera2 API. The following example runs two encoders with the camera as source in parallel, while showing the camera video (using a viewfinder) in fullscreen.

```
$ ./scripts/encapp.py run tests/camera_parallel.pbtxt --local-workdir /tmp/test -e input.playout_frames 150
...
results collect: ['/tmp/test/encapp_<uuid>.json', '/tmp/test/encapp_<uuid>.json']
```

Currently there are no camera settings exposed. However, the resolution and frame rate will be determined by the first encoder which will cause Encapp to try to find a suitable setting (if possible).



## 3.6 Video decoding

Encapp also supports testing its decoders.

```
$ ./scripts/encapp.py run tests/bitrate_buffer_decoder.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.mp4
...
results collect: ['/tmp/test/encapp_7328c6f3-8fa5-42aa-ae1d-58e0a74cf289.json']
```

Note that this will use the default decoder for the input file, which for h264 will be "`OMX.google.h264.decoder`" If you want to actually use a hardware decoder (check those available using the list command), then you need to select it explicitly. For example, some Qualcomm devices offer an h264 HW decoder called "`OMX.qcom.video.decoder.avc`". In order to test it, use:

```
$ ./scripts/encapp.py run tests/bitrate_buffer_decoder.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.mp4 --codec OMX.qcom.video.decoder.avc -e configure.decode_dump true
...
results collect: ['/tmp/test/encapp_a59085f4-9bb8-4068-bbfd-91a1ecf5e2c8.json']
```

You should get a .yuv file at `/tmp/test/`, which you should be able to play using ffplay:
```
$ ffplay -f rawvideo -pixel_format nv12 -video_size 176x144 -i /tmp/test/encapp_<uuid>.yuv
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
```

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
