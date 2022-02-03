# A Formalization of encapp Test Definitions
By Chema Gonzalez and Johan Blome, 2022-01-25

This document describes a formalization of the encapp configuration test file format and its actual operation. Goal is to simplify encapp's operation and prepare it for further evolution.


# 1. Introduction

[encapp](https://github.com/JohanBlome/encapp) is an open-source tool for characterizing hardware codecs (encoders and decoders) in android-based SoCs. It is implemented as 2 main parts:
* 1. an apk that can be run inside the SoC (aka DUT aka device-under-testing), and which runs some encoding/decoding jobs
* 2. an external system that both controls the apk and analyzes its results (aka host script).

The main interfaces between apk and host script are:
* 1. a configuration file where the host script specifies the exact test to be run by the apk
* 2. a set of files where the apk reports the results of the test

This document discusses the encapp apk operation, and all the parameters that can be used in the JSON configuration files. It formalizes the configuration file format.

The configuration file format selected is JSON, as it is easy to parse and create in both the host (python script) and the DUT (java apk). A test configuration consists of a JSON dictionary containing a set of keys that specify the full behavior of the codecs.

Design Goals:
* move complexity from the DUT (java apk) to the host (the python script). The host allows for more flexibility and easier development.
* the API is the JSON file: Host copies media file to DUT, and passes JSON file. DUT runs the test and returns results, including error code, encoded/decoded file, logcat, performance measurement, and others. Host then analyzes the results.
* "single" vs. "composed" tests: We call a test "single" when it involves only one encode/decode run. We call a test "composed" when it involves multiple encoders and decoders (e.g. to test for multiple codec support and performance)
* forward-looking API (JSON file) design
* fail fast at the host: Make sure the host script checks tests as much as possible, and fails fast if there are issus

Design decisions:
* remove human-friendly features from configuration file sent to DUT
* use "different" format for host inputs and DUT input (JSON files): DUT JSON files do not need to be human-friendly (e.g. they will require "300000000" instead of "300 Mbps")


# 2. encapp Operation

The operation of encapp for a single test is relatively simple: It opens a codec (encoder or decoder), and configures it. Then it loops through each frame, setting runtime configuration when needed.


## 2.1. ByteBuffer Operation (ByteEncoder.java)

Quick question on encapp: I was looking at the `BufferEncoder::encode()` method. I can see that the method:

* (1) creates an encoder (`MediaCodec.createByCodecName(...)`)
* (2) configures it (`mCodec.configure(...)`)
* (3) starts it (`mCodec.start()`)
* (4) loops around each frame
* (5) clean up

Step 4 is interesting:
* (4.1) get the frame index (`mCodec.dequeueInputBuffer()`)
* (4.2) decide whether it needs to do runtime parameters (`mCodec.setParameters()`)
* (4.3) get the actual `ByteBuffer` where the frame-to-encode will be copied (`mCodec.getInputBuffer()`). I assume this works for the both the encoder and the decoder side because both are `ByteBuffer` objects
* (4.4) fill it up from a file (`mYuvReader`). The function `queueInputBufferEncoder()` reads the input image into the ByteBuffer it got in the previous step, and then tells the encoder to go ahead by calling `codec.queueInputBuffer()`. The realtime operation mode is implemented by making the last call wait.
* (4.5) wait until there is a buffer in the output buffer (`mCodec.dequeueOutputBuffer()`)
* (4.6) read the output buffer (`mCodec.getOutputBuffer()`)
* (4.7) release output buffer (`mCodec.releaseOutputBuffer()`)

Also:
* encapp uses a MediaMuxer in the encoder case to write encoder outputs to mp4 (h264/h265) or webm (vp8 or vp9) containers.


## 2.2. Surface Operation

Very similar to ByteBuffer operation


## 2.3. Time Operation

encapp has 2x methods of operation:
* (1) "batch": In batch mode, we send frames as fast as possible to the codec, allowing pre-codec frames to be buffered at the codec input. This allows testing features that do not depend on time influence. It is faster than the realtime mode. There are some scenarios where batch mode is not appropriate, including:
  * (a) performance tests, where we want to understand the CPU/GPU/memory usage and bandwidth of the device, or even know if it can actually support the resources we are asking for
  * (b) tests where the timing is important. E.g. sending a "request-sync" parameter at runtime, which requests the encoder to produce a keyframe soon, will affect to the first frame in the pre-codec queue
* (2) "realtime": in this mode, we will emulate actual time. Test is slower than batch mode.


# 3. Configuration Formalization

The main features needed in HW codec tests are:
* allow setting all possible parameters at configuration time (initial) and at runtime, for encoding and decoding media
* support test composition: we want to test running multiple codecs in parallel (at the same time) and in serial (one after another)

# 3.1. encapp Test Definition

There are 6 major encoder/decoder parameter types:
* (1) common: common test information
* (2) input: input information
* (3) configure: one-off configuration
* (4) runtime: runtime parameters [optional]
* (5) parallel: used to compose tests in parallel [optional]
* (6) serial: used to compose tests in serial [optional]

Example: Single, simple test.
```
$ cat simple.json
{
    "common": {
        "id": "simple",
        "description": "Simple Test"
    },
    "input": {
        "filename": "foo.yuv",
        "resolution": "176x144",
        "pix-fmt": "nv12",
        "framerate": 30
    },
    "configure": {
        "codec": "OMX.google.h264.encoder",
        "encode": "true",
        "bitrate": "100 kbps"
    }
}
```

Example: Parallel encoding.
```
$ cat parallel.json
{
    "common": {
        "id": "parallel",
        "description": "Parallel Test"
    },
    "parallel": [
        {
            "input": {
                "filename": "foo.yuv",
                "resolution": "176x144",
                "pix-fmt": "nv12",
                "framerate": 30
            },
            "configure": {
                "codec": "OMX.google.h264.encoder",
                "encode": "true",
                "bitrate": "100 kbps"
            }
        },
        {
            "input": {
                "filename": "bar.yuv",
                "resolution": "176x144",
                "pix-fmt": "nv12",
                "framerate": 30
            },
            "configure": {
                "codec": "OMX.google.h264.encoder",
                "encode": "true",
                "bitrate": "200 kbps"
            }
        }
    ]
}
```

## 3.2. Common Parameters

The "common" key includes generic information about the test.

Format is `dictionary("key", "value")` for well-known keys.

List of valid keys:

* "id": str
  * unique id (no spaces)
  * required
* "description": str
  * human-readable description (anything can go here)
  * required

* "operation": str
  * values: "batch", "realtime"
  * optional (default is "batch")
  * whether the test must be run in "batch" or "realtime" mode

* "start": int
  * starting time for this test: Causes the thread to be started only after a number of frames have already happened
  * optional
  * requires "realtime" mode
  * default unit is frames
    * unit will be frames in the java world
    * but python world will allow time too
  * example: "100"  // wait until 100 frames have been processed (in a different encoder or decoder) before starting this encoder


## 3.3. Input Information

The "input" key includes information about the input file. Input file must always be raw for encoder testing, and encoded for decoder testing.

Format is `dictionary("key", "value")` for well-known keys.

List of valid keys:

* "filepath": str
  * full file path *at the DUT*
  * required
  * example: "/sdcard/foo.yuv" for encoder, or "/sdcard/bar.mp4" for decoder tests
* "width": int
  * input width
  * required for raw files (i.e. for the encoder), optional for files with self-describing format (including .y4m)
  * example: "1280"

* "height": int
  * input height
  * required for raw files (i.e. for the encoder), optional for files with self-describing format (including .y4m)
  * example: "720"

* "pix-fmt": str
  * pixel format, using ffmpeg format (e.g. "nv12", "yuv420p", ...)
  * required for raw files (i.e. for the encoder), optional for files with self-describing format (including .y4m)
  * example: "nv12"

* "framerate": int
  * input frame rate, in fps (frames/second) units
  * required for raw files (i.e. for the encoder), optional for files with self-describing format (including .y4m)
  * example: 30

* "duration": int
  * optional: if added, it will cause the file to be played continuously until the exact number of frames happen
  * default unit is frames
    * unit will be frames in the java world
    * python world will allow time units too
  * example: 300  // play 300 frames at the original file framerate, then quit

Shortcuts (host side only):
* "resolution": str
  * compat way to express both "width" and "height"
  * example: "1280x720"


## 3.4. Configure: One-Off Configuration

The "configure" key includes a list of parameters that are used to call [`MediaCodec.configure()`](https://developer.android.com/reference/android/media/MediaCodec#configure(android.media.MediaFormat,%20android.view.Surface,%20android.media.MediaCrypto,%20int)), including the dictionary values passed into a [`MediaFormat`](https://developer.android.com/reference/android/media/MediaFormat) object.

Format is `dictionary("key", value)` for well-known keys (str), and `dictionary("key", ["type", value])` for unknown keys, where:
* "type" can be "int", "float", "long", "str", "bool", or "null"
* "value" depends on the value of "key" (for well-known keys) or the value of "type" (for unknown keys).

Note that, by using a dictionary keyed by MediaFormat key, we effectively force a single use of each key. This is fine as the same limitation is imposed by `MediaFormat` itself.  Note also that parameters will be set in the MediaFormat in the exact order they are declared.

List of well-known keys:
* "codec": "name" [str]
  * required
  * value that will be passed to [`MediaCodec.createByCodecName()`](https://developer.android.com/reference/android/media/MediaCodec#createByCodecName(java.lang.String))
  * this allows fully specifying the encoder, e.g., selecting "avc" vs. "OMX.google.h264.encoder" or "OMX.qcom.video.encoder.avc"

* "encode": "`use_encoder`" [boolean]
  * whether to run an encoder (otherwise it will run a decoder)
  * optional (default is "true")

* "surface": "true" [boolean]
  * whether to use Surface instead of ByteBuffer as encoder input or decoder output
  * optional (default is "false")

Any key that can be set in the `MediaFormat` dictionary can be passed here (all optional):
* "mime": see `KEY_MIME` in MediaFormat
* "max-input-size": `KEY_MAX_INPUT_SIZE` in MediaFormat
  * int
  * maximum size of a buffer of input data
* "bitrate": `KEY_BIT_RATE` in MediaFormat
  * str (to allow human-readable bitrates as "250 kbps" vs. "250000")
  * desired bitrate in bits/second
* "bitrate-mode": `KEY_BITRATE_MODE` in MediaFormat
  * str
  * bitrate-mode aka `rc_mode`
  * any of the `BITRATE_MODE_*` values in [`MediaCodecInfo.EncoderCapabilities`](https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities)
* "durationUs": `KEY_DURATION` in MediaFormat
  * long
  * duration of the content (in microseconds)
* "width": see `KEY_WIDTH`
  * int
  * value will be automatically obtained from the input  file
* "height": see `KEY_HEIGHT`
  * int
  * value will be automatically obtained from the input  file
* "color-format": see `KEY_COLOR_FORMAT` in MediaFormat [encoder]
  * int
  * any of the `COLOR_Format*` values in [`MediaCodecInfo.CodecCapabilities`](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities)
  * value will be automatically obtained from the input  file
* "color-standard": see `KEY_COLOR_STANDARD` in MediaFormat
  * int
* "color-range": see `KEY_COLOR_RANGE` in MediaFormat
  * int
  * value will be automatically obtained from the input  file
* "color-transfer": see `KEY_COLOR_TRANSFER` in MediaFormat
  * int
  * value will be automatically obtained from the input  file
* "color-transfer-request": see `KEY_COLOR_TRANSFER_REQUEST` in MediaFormat
  * int
* "frame-rate": see `KEY_FRAME_RATE` in MediaFormat
  * int
  * value will be automatically obtained from the input  file
* "i-frame-interval": see `KEY_I_FRAME_INTERVAL` in MediaFormat
  * "i-frame-interval" aka `i_intervals` aka keyframeinterval
  * int
  * time-interval between key frames (seconds)
* "intra-refresh-period": see `KEY_INTRA_REFRESH_PERIOD` in MediaFormat
  * int
* "latency": see `KEY_LATENCY` in MediaFormat
  * int
  * encoder-latency, in frame units
* "repeat-previous-frame-after": see `KEY_REPEAT_PREVIOUS_FRAME_AFTER` in MediaFormat
  * long
  * surface-mode only
  * time in us after which frame previously submitted to the encoder will be repeated
* "ts-schema": see `KEY_TEMPORAL_LAYERING` in MediaFormat
  * str
  * TL schema

Any codec-proprietary value can also be passed here. Some examples
* "vendor.qti-ext-enc-caps-ltr.max-count": used in QCOM encoders to specify LTR size
* "vendor.qti-ext-enc-ltr-count.num-ltr-frames": used in QCOM encoders to specify LTR size

We provide some shortcuts for readability *in the python side only*.
* "resolution": str (width "x" height)
  * will be used to set `KEY_WIDTH` and `KEY_HEIGHT`
  * example: 1280x720

* "bitrate": str (to allow for human-readable units)
  * corresponding to `KEY_BIT_RATE`
  * example: "1 Mbps"

* "bitrate-mode": str
  * corresponding to `KEY_BITRATE_MODE`
  * "`cq`" (`BITRATE_MODE_CQ`, 0)
  * "`vbr`" (`BITRATE_MODE_VBR`, 1)
  * "`cbr`" (`BITRATE_MODE_CBR`, 2)
  * "`cbr_fd`" (`BITRATE_MODE_CBR_FD`, 3)

Example
```
"configure": {
  "codec": "OMX.google.h264.encoder",
  "bitrate": "300000",
  "i-frame-interval": 2
},
```

## 3.5. Runtime Configuration

The "runtime" key includes a list of parameters that are packed into a Bundle and sent to [`MediaCodec.setParameters()`](https://developer.android.com/reference/android/media/MediaCodec#setParameters(android.os.Bundle)) at runtime.

Format is `list([framenum, "key", "type", value])`. Note that the list should be sorted by framenum.

Any key that can be passed in the `param` `Bundle` dictionary (i.e. any `PARAMETER_KEY_*` key) can be passed here:
* "drops": null
  * optional: if added, it will cause a frame to be dropped
  * refers to absolute frames being sent to the encoder
    * if combined with "duration", it will cause only 1 frame drop per entry
* "dynamic-framerate": int
  * optional: if added, it will adjust the frame rate passed to the encoder, effectively dropping frames
  * refers to absolute frames being sent to the encoder
* "video-bitrate": `PARAMETER_KEY_VIDEO_BITRATE` in MediaCodec
  * change a video encoder's target bitrate on the fly
* "request-sync": `PARAMETER_KEY_REQUEST_SYNC_FRAME` in MediaCodec
  * request that the encoder produce a sync frame (keyframe) "soon"
  * note that proper behavior requires using the realtime mode. Running "request-sync" in batch mode will likely cause an IDR in the first frame in the encoder queue
* "low-latency": `PARAMETER_KEY_LOW_LATENCY` in MediaCodec
  * enable/disable low latency decoding mode
* "hdr10-plus-info": `PARAMETER_KEY_HDR10_PLUS_INFO` in MediaCodec
  * set the HDR10+ metadata on the next queued input frame
* "drop-input-frames": `PARAMETER_KEY_SUSPEND` in MediaCodec
  * temporarily suspend/resume encoding of input data
* "drop-start-time-us": `PARAMETER_KEY_SUSPEND_TIME` in MediaCodec
  * specify timestamp (in microseconds) at which the suspend/resume operation takes effect
* "time-offset-us": `PARAMETER_KEY_OFFSET_TIME` in MediaCodec
  * specify an offset (in micro-second) to be added on top of the timestamps onward
* "tunnel-peek": `PARAMETER_KEY_TUNNEL_PEEK` in MediaCodec
  * control video peek of the first frame when a codec is configured for tunnel mode

Any codec-proprietary value can also be passed here. Some examples
* "vendor.qti-ext-enc-ltr.mark-frame": used in QCOM encoders to mark frames for LTR
* "vendor.qti-ext-enc-ltr.use-frame": used in QCOM encoders to use frames for LTR

We provide some shortcuts for readability in the python side.
* "video-bitrate": str (to allow for human-readable units)
  * corresponding to `video-bitrate`
  * example: "1 Mbps"


Example:
```
"runtime": [
  [100, "request-sync"],
  [450, "video-bitrate", "1 Mbps"]
]
```


## 3.6. Parallel Configuration

The "parallel" key includes a list of configurations that will be run using parallel threads.

Format is `list(dictionary)`, where each dictionary is a valid configuration (note that the "common" key may be removed, as it will be ignored).

Example:
```
"parallel": [
    {
        "input": {
            "filename": "foo.yuv",
            "resolution": "176x144",
            "pix-fmt": "nv12",
            "framerate": 30
        },
        "configure": {
            "codec": "OMX.google.h264.encoder",
            "encode": "true",
            "bitrate": "100 kbps"
        }
    },
    {
        "input": {
            "filename": "bar.yuv",
            "resolution": "176x144",
            "pix-fmt": "nv12",
            "framerate": 30
        },
        "configure": {
            "codec": "OMX.google.h264.encoder",
            "encode": "true",
            "bitrate": "200 kbps"
        }
    }
]
```

This test will encode the "foo.yuv" file and the "bar.yuv" file using the h264 encoder, in parallel.

## 3.7. Serial Configuration

The "serial" key includes a list of configurations that will be run using serial threads.

Format is `list(dictionary)`, where each dictionary is a valid configuration (note that the "common" key may be removed, as it will be ignored).

Example:
```
"serial": [
    {
        "input": {
            "filename": "foo.yuv",
            "resolution": "176x144",
            "pix-fmt": "nv12",
            "framerate": 30
        },
        "configure": {
            "codec": "OMX.google.h264.encoder",
            "encode": "true",
            "bitrate": "100 kbps"
        }
    },
    {
        "input": {
            "filename": "bar.yuv",
            "resolution": "176x144",
            "pix-fmt": "nv12",
            "framerate": 30
        },
        "configure": {
            "codec": "OMX.google.h264.encoder",
            "encode": "true",
            "bitrate": "200 kbps"
        }
    }
]
```

This test will encode the "foo.yuv" file using the h264 encoder. Once it is finished, it will do the same with the "bar.yuv" file.


# 4. References

* encapp


# 5. Some Examples
* check number of encoders
* check number of decoder
* check performance while using $N$ encoders and $M$ decoders


# 6. TODO list

* [ ] encoder always uses raw video (remove transcode to surface conversion)


