# A Formalization of encapp Test Definitions
By Chema Gonzalez and Johan Blome, 2022-01-25 (Updated 2025)

This document describes the formalization of the encapp configuration test file format and its actual operation. The goal is to simplify encapp's operation and prepare it for further evolution.


# 1. Introduction

[encapp](https://github.com/JohanBlome/encapp) is an open-source tool for characterizing hardware codecs (encoders and decoders) in Android-based SoCs. It is implemented as 2 main parts:
1. An APK that runs inside the SoC (aka DUT aka device-under-test), which executes encoding/decoding jobs
2. An external system that both controls the APK and analyzes its results (aka host script)

The main interfaces between APK and host script are:
1. A configuration file where the host script specifies the exact test to be run by the APK
2. A set of files where the APK reports the results of the test

This document discusses the encapp APK operation and all the parameters that can be used in the configuration files. It formalizes the configuration file format.

## 1.1. Configuration File Format

The configuration file format uses **Protocol Buffers (protobuf)** text format (.pbtxt files). While the underlying structure can be represented as JSON-like dictionaries, the actual files use protobuf syntax for several reasons:
* Easy to parse and create on both the host (Python script) and the DUT (Java APK via protobuf libraries)
* Type safety and schema validation
* Human-readable text format with support for comments
* Efficient binary representation when needed

A test configuration consists of a protobuf message containing a set of fields that specify the full behavior of the codecs.

## 1.2. Design Goals

* **Move complexity from DUT to host**: The DUT (Java APK) should be as simple as possible. The host (Python script) allows for more flexibility and easier development.
* **Clean API boundary**: The protobuf file is the API. Host copies media file to DUT and passes protobuf file. DUT runs the test and returns results, including error code, encoded/decoded file, logcat, performance measurements, and others. Host then analyzes the results.
* **Single vs. Composed tests**: We call a test "single" when it involves only one encode/decode run. We call a test "composed" when it involves multiple encoders and decoders (e.g., to test for multiple codec support and performance).
* **Forward-looking API design**: Ensure the configuration format can evolve without breaking existing tests.
* **Fail fast at the host**: Make sure the host script checks tests as much as possible and fails fast if there are issues.

## 1.3. Design Decisions

* Remove human-friendly features from configuration files sent to DUT (though protobuf text format remains readable)
* Use "different" formats for host inputs and DUT input: Host-side can use human-readable values (e.g., "300 Mbps") which are converted to numeric values (e.g., "300000000") before sending to DUT
* Protobuf schema provides validation and type checking


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
* (4.4) fill it up from a file (`mYuvReader`). The function `queueInputBufferEncoder()` reads the input image into the ByteBuffer or Image it got in the previous step, and then tells the encoder to go ahead by calling `codec.queueInputBuffer()`. The realtime operation mode is implemented by making the last call wait.
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

encapp test configurations are structured using Protocol Buffer messages. The main `Test` message contains the following parameter types:

* **common** - Test identity and metadata
* **input** - Input source configuration
* **configure** - Encoder/decoder configuration
* **runtime** - Dynamic runtime parameter changes [optional]
* **decoder_configure** - Decoder-specific configuration [optional]
* **decoder_runtime** - Dynamic decoder runtime parameters [optional]
* **parallel** - Parallel test composition [optional]
* **serial** - Serial test composition [optional]
* **test_setup** - Test environment and execution settings [optional]

Each of these parameter types is described in detail in the following sections.

Example: Single, simple test.
```protobuf
$ cat simple.pbtxt
test {
    common {
        id: "simple"
        description: "Simple Test"
    }
    input {
        filepath: "foo.yuv"
        resolution: "176x144"
        pix_fmt: "nv12"
        framerate: 30
    }
    configure {
        codec: "OMX.google.h264.encoder"
        encode: true
        bitrate: "100000"  # 100 kbps
    }
}
```

Example: Parallel encoding.
```protobuf
$ cat parallel.pbtxt
test {
    common {
        id: "parallel"
        description: "Parallel Test"
    }
    parallel {
        input {
            filepath: "foo.yuv"
            resolution: "176x144"
            pix_fmt: "nv12"
            framerate: 30
        }
        configure {
            codec: "OMX.google.h264.encoder"
            encode: true
            bitrate: "100000"  # 100 kbps
        }
    }
    parallel {
        input {
            filepath: "bar.yuv"
            resolution: "176x144"
            pix_fmt: "nv12"
            framerate: 30
        }
        configure {
            codec: "OMX.google.h264.encoder"
            encode: true
            bitrate: "200000"  # 200 kbps
        }
    }
}
```

## 3.2. Common Parameters

The "common" key includes generic information about the test.

Format is `dictionary("key", "value")` for well-known keys.

### Fields

* **"id"** (string)
  * **Required**
  * Unique identifier (no spaces)
  * Example: `"bitrate_test"`

* **"description"** (string)
  * **Required**
  * Human-readable description (anything can go here)
  * Example: `"Test H264 encoder at various bitrates"`

* **"operation"** (string)
  * optional
  * Values: `"batch"`, `"realtime"`
  * Default: `"batch"`
  * Whether the test must be run in "batch" or "realtime" mode

* **"start"** (int)
  * optional
  * Starting time for this test - causes the thread to be started only after a number of frames have already happened
  * Requires "realtime" mode
  * Default unit is frames
    - Unit will be frames in the Java world
    - Python world will allow time units too
  * Example: `100`  // wait until 100 frames have been processed (in a different encoder or decoder) before starting this encoder

* **"output_filename"** (string)
  * optional
  * The default output naming is a UUID with no connection to the actual test being run. However, "output_filename" can be defined with placeholders to force a different naming scheme.
  * Example: `"[input.filepath].[configure.bitrate].[xxxx]-[xx]"`

## 3.3. Input Information

The "input" key includes information about the input file. Input file must always be raw for encoder testing, and encoded for decoder testing.

Format is `dictionary("key", "value")` for well-known keys.

### Fields

* **"filepath"** (string)
  * **Required**
  * Full file path *at the DUT*
  * Example: `"/sdcard/foo.yuv"` for encoder, or `"/sdcard/bar.mp4"` for decoder tests

* **"width"** (int)
  * **Required** for raw files (i.e. for the encoder)
  * Optional for files with self-describing format (including .y4m)
  * Input width in pixels
  * Example: `1280`

* **"height"** (int)
  * **Required** for raw files (i.e. for the encoder)
  * Optional for files with self-describing format (including .y4m)
  * Input height in pixels
  * Example: `720`

* **"pix-fmt"** (string)
  * **Required** for raw files (i.e. for the encoder)
  * Optional for files with self-describing format (including .y4m)
  * Pixel format, using ffmpeg format
  * Example: `"nv12"`, `"yuv420p"`, `"rgba"`

* **"framerate"** (int)
  * **Required** for raw files (i.e. for the encoder)
  * Optional for files with self-describing format (including .y4m)
  * Input frame rate, in fps (frames/second) units
  * Example: `30`

* **"playout-frames"** (int)
  * optional
  * If added, it will cause the file to be played continuously until the exact number of frames happen
  * Default unit is frames
    - Unit will be frames in the Java world
    - Python world will allow time units too
  * Example: `300`  // play 300 frames at the original file framerate, then quit

* **"pursuit"** (int)
  * optional
  * encapp will try to start this many codecs with the same configuration
  * Useful for testing parallel codec instances with identical settings
  * Example: `4`  // start 4 encoders with the same configuration

* **"realtime"** (bool)
  * optional
  * Unless realtime is set or video is displayed, encoding will run as fast as possible
  * Default: `false` (batch mode)

* **"stoptime_sec"** (int)
  * optional
  * Same as playout-frames but specified in seconds instead of frame count
  * Example: `10`  // run for 10 seconds

* **"show"** (bool)
  * optional
  * If possible, show the video on the device screen during encoding/decoding
  * Useful for visual verification
  * Default: `false`

* **"device_decode"** (bool)
  * optional
  * Decode a compressed file on the device instead of on the host
  * Default: `false` (decode on host)

* **"crop_area"** (string)
  * optional
  * The area containing actual video data, in "WxH" format
  * Useful when source video has padding or borders
  * Example: `"1920x1080"`  // actual video area within a larger frame

* **"restamp"** (bool)
  * optional
  * Restamp input frame timestamps according to the framerate set in the input or dynamic updates
  * Useful when input file has incorrect or inconsistent timestamps
  * Default: `false`

### Shortcuts (host side only)

* **"resolution"** (string)
  * Compat way to express both "width" and "height"
  * Example: `"1280x720"`


## 3.4. Configure: One-Off Configuration

The "configure" key includes a list of parameters that are used to call [`MediaCodec.configure()`](https://developer.android.com/reference/android/media/MediaCodec#configure(android.media.MediaFormat,%20android.view.Surface,%20android.media.MediaCrypto,%20int)), including the dictionary values passed into a [`MediaFormat`](https://developer.android.com/reference/android/media/MediaFormat) object.

Format is `dictionary("key", value)` for well-known keys (str), and `dictionary("key", ["type", value])` for unknown keys, where:
* "type" can be "int", "float", "long", "str", "bool", or "null"
* "value" depends on the value of "key" (for well-known keys) or the value of "type" (for unknown keys).

Note that, by using a dictionary keyed by MediaFormat key, we effectively force a single use of each key. This is fine as the same limitation is imposed by `MediaFormat` itself.  Note also that parameters will be set in the MediaFormat in the exact order they are declared.

### Well-Known Fields

* **"codec"** (string)
  * **Required**
  * Value that will be passed to [`MediaCodec.createByCodecName()`](https://developer.android.com/reference/android/media/MediaCodec#createByCodecName(java.lang.String))
  * This allows fully specifying the encoder
  * Example: `"OMX.google.h264.encoder"` or `"c2.exynos.h264.encoder"`

* **"encode"** (bool)
  * optional
  * Whether to run an encoder (otherwise it will run a decoder)
  * Default: `true`

* **"surface"** (bool)
  * optional
  * Whether to use Surface instead of ByteBuffer as encoder input or decoder output
  * Default: `false`

### MediaFormat Parameters

Any key that can be set in the `MediaFormat` dictionary can be passed here (all optional):

* **"mime"** - See `KEY_MIME` in MediaFormat
  * Example: `"video/avc"`

* **"max-input-size"** - See `KEY_MAX_INPUT_SIZE` in MediaFormat
  * Type: int
  * Maximum size of a buffer of input data

* **"bitrate"** - See `KEY_BIT_RATE` in MediaFormat
  * Type: int
  * Desired bitrate in bits/second
  * Example: `2000000` (2 Mbps)

* **"bitrate-mode"** - See `KEY_BITRATE_MODE` in MediaFormat
  * Type: int or string
  * Bitrate mode (aka `rc_mode`)
  * Values: any of the `BITRATE_MODE_*` values in [`MediaCodecInfo.EncoderCapabilities`](https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities)
  * See shortcuts section below for string aliases

* **"durationUs"** - See `KEY_DURATION` in MediaFormat
  * Type: long
  * Duration of the content (in microseconds)

* **"width"** - See `KEY_WIDTH` in MediaFormat
  * Type: int
  * Value will be automatically obtained from the input file

* **"height"** - See `KEY_HEIGHT` in MediaFormat
  * Type: int
  * Value will be automatically obtained from the input file

* **"color-format"** - See `KEY_COLOR_FORMAT` in MediaFormat [encoder]
  * Type: int
  * Any of the `COLOR_Format*` values in [`MediaCodecInfo.CodecCapabilities`](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities)
  * Value will be automatically obtained from the input file

* **"color-standard"** - See `KEY_COLOR_STANDARD` in MediaFormat
  * Type: int

* **"color-range"** - See `KEY_COLOR_RANGE` in MediaFormat
  * Type: int
  * Value will be automatically obtained from the input file

* **"color-transfer"** - See `KEY_COLOR_TRANSFER` in MediaFormat
  * Type: int
  * Value will be automatically obtained from the input file

* **"color-transfer-request"** - See `KEY_COLOR_TRANSFER_REQUEST` in MediaFormat
  * Type: int

* **"frame-rate"** - See `KEY_FRAME_RATE` in MediaFormat
  * Type: int
  * Value will be automatically obtained from the input file

* **"i-frame-interval"** - See `KEY_I_FRAME_INTERVAL` in MediaFormat
  * Type: int
  * Also known as `i_intervals` or `keyframeinterval`
  * Time interval between key frames (in seconds)

* **"intra-refresh-period"** - See `KEY_INTRA_REFRESH_PERIOD` in MediaFormat
  * Type: int

* **"latency"** - See `KEY_LATENCY` in MediaFormat
  * Type: int
  * Encoder latency, in frame units

* **"repeat-previous-frame-after"** - See `KEY_REPEAT_PREVIOUS_FRAME_AFTER` in MediaFormat
  * Type: long
  * Surface mode only
  * Time in microseconds after which frame previously submitted to the encoder will be repeated

* **"ts-schema"** - See `KEY_TEMPORAL_LAYERING` in MediaFormat
  * Type: string
  * Temporal layering schema

* **"quality"** - See `KEY_QUALITY` in MediaFormat [encoder]
  * Type: int
  * Encoder quality level (0-100)
  * Use with constant quality (CQ) bitrate mode
  * Higher values indicate higher quality

* **"complexity"** - See `KEY_COMPLEXITY` in MediaFormat [encoder]
  * Type: int
  * Encoder complexity/speed setting (codec-specific range)
  * Trade-off between encoding speed and compression efficiency
  * Lower values = faster encoding, higher values = better compression

* **"decode-dump"** (bool)
  * optional
  * Dump decoded frames to file for verification
  * Default: `false`
  * Useful for debugging decoder output

### Codec-Proprietary Parameters

Any codec-proprietary value can also be passed here. Some examples:

* **"vendor.qti-ext-enc-caps-ltr.max-count"** - Used in Qualcomm encoders to specify LTR size
* **"vendor.qti-ext-enc-ltr-count.num-ltr-frames"** - Used in Qualcomm encoders to specify LTR size

### Shortcuts (host side only)

We provide some shortcuts for readability *in the Python side only*:

* **"resolution"** (string)
  * Format: width "x" height
  * Will be used to set `KEY_WIDTH` and `KEY_HEIGHT`
  * Example: `"1280x720"`

* **"bitrate"** (string)
  * Allows for human-readable units
  * Corresponding to `KEY_BIT_RATE`
  * Example: `"1 Mbps"`, `"500 kbps"`

* **"bitrate-mode"** (string)
  * Corresponding to `KEY_BITRATE_MODE`
  * `"cq"` (`BITRATE_MODE_CQ`, 0)
  * `"vbr"` (`BITRATE_MODE_VBR`, 1)
  * `"cbr"` (`BITRATE_MODE_CBR`, 2)
  * `"cbr_fd"` (`BITRATE_MODE_CBR_FD`, 3)

### Example

```protobuf
configure {
    codec: "OMX.google.h264.encoder"
    bitrate: "300000"
    i_frame_interval: 2
}
```

## 3.5. Runtime Configuration

The "runtime" key includes a list of parameters that are packed into a Bundle and sent to [`MediaCodec.setParameters()`](https://developer.android.com/reference/android/media/MediaCodec#setParameters(android.os.Bundle)) at runtime.

Format is `list([framenum, "key", "type", value])`. Note that the list should be sorted by framenum.

Any key that can be passed in the `param` `Bundle` dictionary (i.e. any `PARAMETER_KEY_*` key) can be passed here:
* "drops": null
  * optional: if added, it will cause a frame to be dropped
  * refers to absolute frames being sent to the encoder
    * if combined with "playout-frames", it will cause only 1 frame drop per entry
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


## 3.6. Parallel and Serial Test Composition

encapp supports composing multiple tests to run either in parallel (simultaneously) or in serial (one after another).

### Parallel Configuration

The `parallel` field includes a list of test configurations that will be run using parallel threads. This is useful for testing multiple codecs simultaneously or testing codec performance under load.

**Format:** Repeated `Test` messages within a `parallel` block.

**Note:** The `common` section in parallel tests may be simplified or omitted as individual test IDs are generated.

**Example:**
```protobuf
test {
    common {
        id: "parallel_test"
        description: "Run two H264 encodings in parallel"
    }
    parallel {
        test {
            input {
                filepath: "foo.yuv"
                resolution: "176x144"
                pix_fmt: nv12
                framerate: 30
            }
            configure {
                codec: "OMX.google.h264.encoder"
                encode: true
                bitrate: "100000"
            }
        }
        test {
            input {
                filepath: "bar.yuv"
                resolution: "176x144"
                pix_fmt: nv12
                framerate: 30
            }
            configure {
                codec: "OMX.google.h264.encoder"
                encode: true
                bitrate: "200000"
            }
        }
    }
}
```

This test will encode both "foo.yuv" and "bar.yuv" files simultaneously using the H264 encoder at different bitrates.

### Serial Configuration

The `serial` field includes a list of test configurations that will be run sequentially. This is useful for running a sequence of different encoding operations or for comparing encoder settings in sequence.

**Format:** Repeated `Test` messages within a `serial` block, executed in order.

**Example:**
```protobuf
test {
    common {
        id: "serial_test"
        description: "Run two H264 encodings sequentially"
    }
    serial {
        test {
            input {
                filepath: "foo.yuv"
                resolution: "176x144"
                pix_fmt: nv12
                framerate: 30
            }
            configure {
                codec: "OMX.google.h264.encoder"
                encode: true
                bitrate: "100000"
            }
        }
        test {
            input {
                filepath: "bar.yuv"
                resolution: "176x144"
                pix_fmt: nv12
                framerate: 30
            }
            configure {
                codec: "OMX.google.h264.encoder"
                encode: true
                bitrate: "200000"
            }
        }
    }
}
```

This test will encode the "foo.yuv" file using the H264 encoder first. Once it finishes, it will encode the "bar.yuv" file.

### Combining Parallel and Serial

You can nest `parallel` within `serial` blocks (or vice versa) for more complex test scenarios:

```protobuf
test {
    common {
        id: "complex_test"
        description: "Serial of parallel encodings"
    }
    serial {
        test {
            common { id: "phase1" }
            parallel {
                test {
                    input { filepath: "video1.yuv" resolution: "1280x720" framerate: 30 }
                    configure { codec: "c2.exynos.h264.encoder" bitrate: "1000000" }
                }
                test {
                    input { filepath: "video2.yuv" resolution: "1280x720" framerate: 30 }
                    configure { codec: "c2.exynos.h264.encoder" bitrate: "2000000" }
                }
            }
        }
        test {
            common { id: "phase2" }
            input { filepath: "video3.yuv" resolution: "1920x1080" framerate: 60 }
            configure { codec: "c2.exynos.hevc.encoder" bitrate: "5000000" }
        }
    }
}
```

## 3.7. Decoder Configuration

For decoding tests, you can specify decoder-specific parameters using the `decoder_configure` message. This is similar to the main `configure` section but specifically for decoder settings.

### DecoderConfigure Fields

* **"codec"** (string)
  * Decoder codec name
  * Example: `"c2.exynos.h264.decoder"`

* **"parameter"** (repeated Parameter)
  * Decoder-specific parameters in key-value-type format
  * Same format as encoder parameters

### Example

```protobuf
test {
    common {
        id: "decoder_test"
        description: "Test H264 decoder"
    }
    input {
        filepath: "encoded_video.mp4"
    }
    configure {
        encode: false  # This is a decoder test
    }
    decoder_configure {
        codec: "c2.exynos.h264.decoder"
        parameter {
            key: "low-latency"
            type: intType
            value: "1"
        }
    }
}
```

## 3.8. Decoder Runtime Configuration

Similar to encoder runtime configuration, you can specify runtime parameters for the decoder using the `decoder_runtime` message.

### DecoderRuntime Fields

* **"parameter"** (repeated Parameter)
  * Runtime parameters for decoder
  * Applied during decoding at specified frame numbers

### Example

```protobuf
decoder_runtime {
    parameter {
        key: "low-latency"
        type: intType
        value: "0"
        framenum: 100  # Change at frame 100
    }
}
```

## 3.9. Test Setup Configuration

The `TestSetup` message contains settings related to the setup of the test environment, separate from the actual test parameters. These settings control how tests are executed and where data is stored.

For detailed information about TestSetup parameters, see [README.md Section 5.4](README.md#54-test-setup).

### Summary of TestSetup Fields

* **"device_workdir"** (string)
  * Where data can be stored on the device
  * Example: `"/sdcard"` or `"/data/local/tmp"`

* **"local_workdir"** (string)
  * Where to store output from the test on the host machine
  * Example: `"/tmp/test_results"`

* **"serial"** (string)
  * Serial ID of the device to be tested
  * Alternative to using `--serial` flag or `ANDROID_SERIAL` environment variable

* **"device_cmd"** (string)
  * Device communication command
  * Default is `"adb"`, set to `"idb"` for iOS/Apple devices

* **"run_cmd"** (string)
  * Custom command to start the device app if needed
  * Example: `"appXYZ -r DEF.pbtxt"`
  * Must be self-contained with all paths defined in the protobuf

* **"separate_sources"** (bool)
  * Split tests containing multiple source video files
  * Files are run sequentially and removed before the next file
  * Helpful for memory-constrained devices

* **"mediastore"** (string)
  * Directory to store temporary files
  * Optimizes storage and transfer of source files

* **"source_dir"** (string)
  * Root directory for input files
  * Makes it possible to use only filenames (not full paths) in `input.filepath`
  * Example: `"/mnt/video_library"`

* **"first_frame_fast_read"** (bool)
  * Optimize first frame reading
  * Optional, default: false

* **"ignore_power_status"** (bool)
  * Ignore the 20%-80% power level rules
  * Test will run until power is exhausted
  * Useful for devices with problematic power reporting

* **"uihold_sec"** (int32)
  - Add a delay (in seconds) before exiting the app
  - Useful for identifying back-to-back test runs

* **"internal_demuxer"** (bool)
  - Use encapp's internal Java-based MP4 demuxer instead of Android's MediaExtractor
  - Provides a pure Java fallback for parsing MP4/MPEG4 video files
  - Supports H.264 (AVC) and H.265 (HEVC) codecs
  - Useful for devices with MediaExtractor compatibility issues or when more control over demuxing is needed
  - Default: `false` (use Android's MediaExtractor)
  - Example: `internal_demuxer: true`

### Example TestSetup

```protobuf
test {
    test_setup {
        device_workdir: "/sdcard"
        local_workdir: "/tmp/my_tests"
        serial: "ABC123XYZ"
        device_cmd: "adb"
        separate_sources: true
        mediastore: "/tmp/media_cache"
        source_dir: "/home/user/videos"
        ignore_power_status: false
        uihold_sec: 3
    }
    common {
        id: "my_test"
        description: "Test with custom setup"
    }
    input {
        filepath: "test.yuv"  # Relative to source_dir
        resolution: "1280x720"
        framerate: 30
    }
    configure {
        codec: "c2.exynos.h264.encoder"
        bitrate: "2000000"
    }
}
```
