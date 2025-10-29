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
* for details on test configuration, check [README.tests.md](README.tests.md).


# 1. Prerequisites

## 1.1. External Tool Dependencies

encapp requires the following external tools to be installed and available in your system PATH:

### adb (Android Debug Bridge)
**Purpose:** Required for communicating with Android devices

**Installation:**
- Linux: `sudo apt install adb` (Debian/Ubuntu)
- macOS: `brew install android-platform-tools`
- Windows: Download from [Android SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools)

**Verification:** Run `adb version` to confirm installation

**Notes:** Ensure your Android device has USB debugging enabled and is properly connected

### idb (iOS Debug Bridge) - Optional
**Purpose:** Required for testing on iOS devices (alternative to adb)

**Installation:**
- Follow instructions at [https://fbidb.io/](https://fbidb.io/)
- Or install via Homebrew: `brew tap facebook/fb && brew install idb-companion`

**Usage:** Use `--idb` flag or `--bundleid` option to enable iOS mode

**Notes:** Requires Xcode and iOS development setup

### ffmpeg
**Purpose:** Required for video transcoding, format conversion, and decoding

**Installation:**
- Linux: `sudo apt install ffmpeg` (Debian/Ubuntu)
- macOS: `brew install ffmpeg`
- Windows: Download from [ffmpeg.org](https://ffmpeg.org/download.html)

**Verification:** Run `ffmpeg -version` and `ffprobe -version`

**Required features:**
- Decoding support for h264, h265, vp8, vp9, and other codecs you plan to test
- Support for raw video formats (yuv420p, nv12, nv21, rgba, etc.)

### libvmaf (for quality metrics) - Optional
**Purpose:** Required if using `--quality` flag for VMAF quality calculations

**Verification:** First check if your ffmpeg already has VMAF support:
```bash
ffmpeg -filters | grep vmaf
```
If the command returns results showing vmaf filters, you're all set!

**Installation (only if VMAF filter is missing):**
- Linux: `sudo apt install libvmaf-dev ffmpeg` or build from source
- macOS: `brew install libvmaf ffmpeg`
- Build ffmpeg with VMAF: Only needed if your ffmpeg lacks VMAF support. Rebuild ffmpeg with `--enable-libvmaf`

**Environment variable:** Set `VMAF_MODEL_PATH` to specify custom VMAF model location

**Notes:** The default VMAF model used is `vmaf_v0.6.1neg`

### protoc (Protocol Buffer Compiler) - Optional
**Purpose:** Only needed if modifying `.proto` files (development)

**Installation:**
- Linux: `sudo apt install protobuf-compiler`
- macOS: `brew install protobuf`
- Windows: Download from [protobuf releases](https://github.com/protocolbuffers/protobuf/releases)

**Verification:** Run `protoc --version`

## 1.2. Python Dependencies

encapp requires Python 3.9 or later. Install all required Python packages using:

```bash
pip install -r requirements.txt
```

List of required python packages (see `requirements.txt` for specific versions):
* `argparse-formatter` - Better command-line help formatting
* `humanfriendly` - Human-friendly parsing of time spans and file sizes
* `matplotlib` - Plotting and visualization
* `numpy` - Numerical computing
* `pandas` - Data manipulation and analysis
* `protobuf` - Protocol buffer support (Google protocol buffers)
* `scipy` - Scientific computing
* `seaborn` - Statistical data visualization
* `pytest` - Testing framework (for development)

**Quick installation:**
```bash
# Install all dependencies
pip3 install -r requirements.txt

# Or install individual packages
pip3 install humanfriendly argparse-formatter numpy pandas seaborn protobuf scipy matplotlib
```



# 2. Command Line Interface

encapp.py provides several commands for different operations. The basic syntax is:

```bash
./scripts/encapp.py [COMMAND] [OPTIONS] [ARGUMENTS]
```

## 2.1. Available Commands

* `help` - Show help options and exit
* `install` - Install APKs to the device
* `uninstall` - Uninstall APKs from the device
* `list` - List codecs and devices supported. Can be used to search and filter
* `run` - Run codec test case(s)
* `kill` - Force stop the application
* `clear` - Remove all encapp-associated files from device
* `reset` - Remove all encapp-created output files (for debugging)
* `pull_result` - Pull results to current directory

## 2.2. Global Options (Available for all commands)

### Device Selection
* `--serial SERIAL` - Device serial number (or set `ANDROID_SERIAL` environment variable)
* `--idb` - Run on iOS using idb (iOS Debug Bridge)
* `--bundleid BUNDLEID` - Sets the bundle ID to be used (implicitly enables `--idb`)
* `--device-workdir PATH` - Work (storage) directory on device (default: `/sdcard` for Android, `Documents` for iOS)

### General Options
* `-v`, `--version` - Print version and exit
* `-d`, `--debug` - Increase verbosity (can be used multiple times for more detail)
* `-q`, `--quiet` - Zero verbosity
* `--install` - Install APK before running command
* `--run-cmd CMD` - Explicitly specify a command to be run

## 2.3. `run` Command Options

The `run` command is the most commonly used and has the most options.

### Basic Usage
```bash
./scripts/encapp.py run [OPTIONS] CONFIGFILE [CONFIGFILE ...]
```

### Input/Output Options

* `-i`, `--videofile FILE` - Input video file (overrides config file)
* `-w`, `--local-workdir DIR` - Work (storage) directory on local host
* `--mediastore DIR` - Store all input and generated files in one folder
* `--source-dir DIR` - Root directory for sources (makes input.filepath relative to this)
* `--filter-input REGEXP` - Regexp filter on input files when using a folder as input

### Codec Options

* `-c`, `--codec NAME` - Override encoder/decoder in config
* `-r`, `--bitrate SPEC` - Input video bitrate. Can be:
  - A single number (e.g., `"100k"`, `"1M"`, `"500000"`)
  - A list (e.g., `"100k,200k,500k"`)
  - A range (e.g., `"100k-1M-100k"`) with format `start-stop-step`
* `-fps`, `--framerate SPEC` - Input video framerate (same format as bitrate)
* `-s`, `--size SPEC` - Input video resolution. Can be:
  - A single size (e.g., `"1280x720"`)
  - A list (e.g., `"320x240,1280x720"`)
  - A range (e.g., `"320x240-4000x4000-2"`) where step is a multiplier
* `--pix-fmt FORMAT` - Wanted pixel format for encoder (e.g., `yuv420p`, `nv12`, `nv21`, `rgba`)

### Advanced Configuration

* `-e`, `--replace KEY VALUE` - Generic replacement mechanism. Format: `-e section.key value`
  - Examples:
    - `-e configure.bitrate_mode cbr`
    - `-e input.filepath /path/to/video.yuv`
    - `-e configure.i_frame_interval 60`

### Test Execution Options

* `--split` - Run serial tests individually (one at a time). When using this option, a file containing all performed tests will be written in the workdir: `tests_run.log`. This file can be used to resume tests later, which is especially useful if a single test faults since a resumed test will continue on the next test. However, if a rerun is performed for a complete run, no tests will be executed.
* `--separate-sources` - Run each source separately, clearing space in between
* `--multiply SPEC` - Multiply a test input. Can be:
  - A single number (e.g., `"3"` to run test 3 times in parallel)
  - An array format: `"[nbr,source][nbr,source]..."` (e.g., `"[2,][3,'test1.pbtxt']"`)
* `--shuffle` - Shuffle multi-tests so each run executes in a different order
* `--dry-run` - Do not execute tests, just prepare them
* `--ignore-results` - Ignore results from an experiment

### File Transfer Options

* `--fast-copy` - Minimize file interaction
* `--file-transfer-limit SIZE` - Limit maximum file size for direct transfer (default: system max). Files above this size will be split and transferred incrementally. Supports units: `10M`, `1G`, etc.
* `--file-split-size SIZE` - Split large files into chunks (default: `20M`). Note: using too small sizes will fail with a "too many files" error.

### Video Processing Options

* `--raw` - Always decode to raw format before pushing to device
* `--width-align PIXELS` - Horizontal width alignment for stride calculation (padding for raw YUV)
* `--height-align PIXELS` - Vertical height alignment for padding (raw YUV)
* `--dim-align PIXELS` - Set both width and height alignment

### Quality Measurement

* `--quality` - Run quality calculations as part of the session (same as running `encapp_quality` after encoding). Calculates VMAF, SSIM, PSNR metrics. Requires ffmpeg with libvmaf support.

### Advanced Test Options

* `--expand-all` - Expand all parameters where value matches `x,y,z` or `x-y-z` pattern

### Example Commands

```bash
# Basic encoding test
./scripts/encapp.py run tests/bitrate_buffer.pbtxt

# Encode with specific bitrate and resolution
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -r 1M -s 1280x720

# Test multiple bitrates
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -r 100k-1M-100k

# Use hardware encoder with quality measurement
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -c c2.exynos.h264.encoder --quality

# Override multiple settings
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -e configure.bitrate_mode cbr -e configure.i_frame_interval 60

# Dry run to see what would be executed
./scripts/encapp.py run tests/bitrate_buffer.pbtxt --dry-run --local-workdir /tmp/preview
```

## 2.4. `list` Command Options

The `list` command shows available codecs on the device.

### Basic Usage
```bash
./scripts/encapp.py list [OPTIONS]
```

### Filter Options

* `-enc`, `--encoders` - Show only encoders
* `-dec`, `--decoders` - Show only decoders
* `-hw`, `--hw` - Show only hardware-accelerated codecs
* `--sw` - Show only software codecs
* `--audio` - Show only audio codecs
* `-c`, `--codec PATTERN` - Regexp filter on codec name

### Output Options

* `-l`, `--info-level LEVEL` - How much information to show
  - `0` (default): Only codec names
  - `> 0`: Filtered information (more compact)
  - `-1`: Show all available information
* `-o`, `--output PATH` - Output folder or filename
  - Folder: ends with `/` or is an existing directory
  - File: specific filename for output
* `-f`, `--codecs-file FILE` - Read from file instead of fetching from device

### Cache Options

* `-nc`, `--no-cache` - No cache, refresh from device (default is to cache for 1 hour)

### Example Commands

```bash
# List all encoders
./scripts/encapp.py list --encoders

# List hardware h264 encoders
./scripts/encapp.py list -enc -hw -c h264

# Show detailed info about VP9 codecs
./scripts/encapp.py list -c vp9 -l -1

# Save codec list to file
./scripts/encapp.py list -o /tmp/my_codecs.txt

# List decoders with medium detail
./scripts/encapp.py list -dec -l 2
```

# 3. Quick Start

## 3.1. Installation

First, install the encapp Android app on your device and grant necessary permissions:

```bash
# Install the app
./scripts/encapp.py install

The installation is done with the "-g" options so no extra permissions should be needed.
There may be permission dialoges appearing on the device though.
# Grant storage permissions (Android 11+)
adb shell appops set --uid com.facebook.encapp MANAGE_EXTERNAL_STORAGE allow
```

If you have multiple devices connected, specify the device serial:
```bash
# Set device serial via environment variable
export ANDROID_SERIAL=<device_serial>

# Or use the --serial flag
./scripts/encapp.py install --serial <device_serial>
```

## 3.2. List Available Codecs

Run the list command to see all available encoders:
```
$ ./scripts/encapp.py list
c2.exynos.h263.encoder
c2.exynos.h264.encoder
c2.exynos.hevc.encoder
....
```

By default is will show all encoders in a list.
To see the deoders, use:


```
$ ./scripts/encapp.py list --decoders
c2.exynos.h263.decoder
c2.exynos.h264.decoder
c2.exynos.h264.decoder.secure
....
```

By default will be a json compatible file in the current directory called, codecs_XXX.
**Important** the result will be cached. To force refresh use the "-nc" or "--no-cache" argument.
More infor can be obtained by increasing the information level or setting it to "-1".


Note: The `scripts/encapp.py` scripts will install a prebuild apk before running the test. If you have several devices attached to your host, you can use either the "`ANDROID_SERIAL`" environment variable or the "`--serial <serial>`" CLI option.


# 4. Operation: Run an Encoding Experiment Using encapp

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


## 4.1. Small QCIF Encoding

First, select one of the codecs from step 4. In this case, we will use `OMX.google.h264.encoder`.

Push the (raw) video file to be encoded into the device. Note that we are using a QCIF video (176x144).
In this case the raw format is yuv420p (which is stated in the codec list from (2.2), `COLOR_FormatYUV420Planar`).0
For sw codecs in most case it is yuv420p. For hw codecs e.g. Qcom: `COLOR_QCOM_FormatYUV420SemiPlanar` this is nv12.
In the case of surface encoder from raw the source should be nv21 regardless of codec.

Now run the h264 encoder (`OMX.google.h264.encoder`):
```
$ ./scripts/encapp.py run tests/bitrate_buffer.pbtxt --local-workdir /tmp/test [ -e input.filepath /tmp/akiyo_qcif.y4m ]
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

Note that the json file is not the only result of the experiment copied to the directory specified using "--local-workdir":
Also, the [-e ...] is already defined in the test description, this would override the test specified input.
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
$ ./scripts/encapp.py run tests/bitrate_surface.pbtxt --local-workdir /tmp/test --codec OMX.qcom.video.encoder.avc
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

Note that we are changing the test from "`tests/bitrate_buffer.pbtxt`" to "`tests/bitrate_surface.pbtxt`". This encoding will use a txture as input.
Also we used a hw encoder. If for example running on a Pixel8/9 try: "-c c2.exynos.h264.encoder" instead.

## 4.2. HD Video Encoding

Now, let's run the h264 encoder in an HD file. We will just select the codec ("h264"), and let encapp choose the actual encoder.

```
$ ./scripts/prepare_test_data.hd.sh
$ ls -Flasg /tmp/kristen_and_sara*
  1556K -rw-r--r--. 1 ...   1592348 Jan 18 14:18 /tmp/kristen_and_sara.1280x720.60.mp4
811356K -rw-r--r--. 1 ... 830826065 Jan 18 12:06 /tmp/kristen_and_sara.1280x720.60.y4m
811352K -rw-r--r--. 1 ... 830822400 Jan 18 14:18 /tmp/kristen_and_sara.1280x720.60.yuv
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


## 4.3. Video encoding with implicit decoding

For usability reasons (allow testing with generic video files), encapp allows using an encoded video as a source, instead of raw (yuv) video. In this case, encapp will choose one of its decoders (hardware decoders are prioritized), and decode the video to raw (yuv) before testing the encoder.

```
$ ./scripts/encapp.py run tests/bitrate_buffer_transcoder.pbtxt
```

Or using a surface texture:
```
$ ./scripts/encapp.py run tests/bitrate_surface_transcoder.pbtxt
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


## 4.4. Video decoding/encoding in series, while showing

Encapp also allows visualizing the video being decoded. We can run the previous experiment (implicit decoding and encoding), but this time the encoded video will be shown in the device. Note that we are seeing the decoded video, not the one being re-encoded.

The parameter for showing the video is `show` in the `input` section.

```
$ ./scripts/encapp.py run tests/bitrate_surface_transcoder_show.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.mp4
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```

As an alternative, you can use the non-show version of the test, but request the `show` parameter in the CLI.

```
$ ./scripts/encapp.py run tests/bitrate_transcoder.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.mp4 -e input.show true
...
results collect: ['/tmp/test/encapp_<uuid>.json']
```



## 4.5. Video encoding using camera source

Encapp also supports camera using the Camera2 API. The following example runs two encoders with the camera as source in parallel, while showing the camera video (using a viewfinder) in fullscreen.

```
$ ./scripts/encapp.py run tests/camera_parallel.pbtxt --local-workdir /tmp/test -e input.playout_frames 150
...
results collect: ['/tmp/test/encapp_<uuid>.json', '/tmp/test/encapp_<uuid>.json']
```

Currently there are no camera settings exposed. However, the resolution and frame rate will be determined by the first encoder which will cause Encapp to try to find a suitable setting (if possible).



## 4.6. Video decoding

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


## 4.7. Troubleshooting

* Encapp uses /sdcard/ to send files back and forth between the python script and the java app. Some devices have problems with writing in that directory. In that case, you can request a different directory to be used. Typically "`/data/data/com.facebook.encapp`" is available for encapp. In order to do this, append "`--device-workdir /data/data/com.facebook.encapp`" to any of the encapp commands.

For example:
```
$ ./scripts/encapp.py list --device-workdir /data/data/com.facebook.encapp
...
  MediaCodec {
    name: OMX.google.vp9.decoder
    canonical_name: c2.android.vp9.decoder
...
File is available in current dir as codecs_<id>.txt
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

## 5.3 Combining test definitions

Multiple test definitions can be set on the command line i.e.
```
$ ./scripts/encapp.py run tests/bitrate_buffer.pbtxt qcom_hevc_360p_vbr.pbtxt --local-workdir /tmp/test -e input.filepath /tmp/akiyo_qcif.y4m
...
```

Where the bitrate_buffer.pbtxt have the following definition:
```protobuf
test {
    input {
        filepath: "/tmp/akiyo_qcif.y4m"
    }
    common {
        id: "bitrate_buffer"
        description: "Verify encoding bitrate - buffer"
    }
    configure {
        codec: "OMX.google.h264.encoder"
        bitrate: "200 kbps"
    }
}
```

And the qcom_hevc_360p_vbr.pbtxt
```protobuf
test {
    common {
        id: "Android.hevc.360p"
        description: "Bitrate buffer qc 360p"
    }
    configure {
        codec: "c2.qti.hevc.encoder"
        resolution: "640x360"
        bitrate_mode: vbr
    }
}
```

When running the definitions will merge:
1) leftmost will have all its test definition merged with the topmost test in subsequent definition
2. rightmost has precedence and overwrite settings

The output in the above example will be:
```protobuf
test {
  common {
    id: "Android.hevc.360p"
    description: "Bitrate buffer qc 360p"
  }
  input {
    filepath: "/tmp/akiyo_qcif.y4m"
  }
  configure {
    codec: "c2.qti.hevc.encoder"
    bitrate: "200 kbps"
    resolution: "640x360"
    bitrate_mode: vbr
  }
}
```

Command line argument will have precedence.

## 5.4 Test Setup

Many parameters available on the command line are also available in the protobuf description via the `TestSetup` message. This allows you to create device-specific configuration files, reducing the need for command line arguments.

### Usage Example

```bash
$ ./scripts/encapp.py run important_test.pbtxt my_special_device.pbtxt
```

### Available TestSetup Parameters

The `TestSetup` message can contain the following parameters:

#### Device Configuration

* **`device_workdir`** (string)
  - Work (storage) directory on the device
  - Instead of using `--device-workdir` on CLI
  - Example: `"/sdcard"` or `"/data/local/tmp"`

* **`serial`** (string)
  - Device serial number
  - Instead of using `--serial` on CLI or `ANDROID_SERIAL` environment variable
  - Example: `"R9WR20E3XYZ"`

* **`device_cmd`** (string)
  - Specify device command type
  - Default is `"adb"`, set to `"idb"` for iOS/Apple devices
  - Example: `device_cmd: "idb"`

* **`run_cmd`** (string)
  - Custom command to start the device app
  - Useful for alternative execution methods
  - Must be self-contained with paths defined in the protobuf
  - Example: `"appXYZ -r DEF.pbtxt"`

#### Local Host Configuration

* **`local_workdir`** (string)
  - Work (storage) directory on local host
  - Instead of using `--local-workdir` on CLI
  - Example: `"/tmp/encapp_results"`

* **`mediastore`** (string)
  - Location to store temporary files
  - Instead of using `--mediastore` on CLI
  - Example: `"/tmp/media_cache"`

* **`source_dir`** (string)
  - Root directory for source video files
  - Makes `input.filepath` relative to this directory
  - Allows using just filenames instead of full paths
  - Example: `"/mnt/video_library"`

#### Test Execution Options

* **`separate_sources`** (bool)
  - Run each source video file sequentially
  - Removes files before processing the next one
  - Helps memory-constrained devices
  - Instead of using `--separate-sources` on CLI
  - Example: `separate_sources: true`

* **`first_frame_fast_read`** (bool)
  - Optimize first frame reading
  - Example: `first_frame_fast_read: true`

* **`ignore_power_status`** (bool)
  - Ignore the 20%-80% power level rules
  - Test will run until power is exhausted
  - Useful for devices with problematic power reporting
  - Example: `ignore_power_status: true`

* **`uihold_sec`** (int32)
  - Add a delay (in seconds) before exiting the app
  - Useful for identifying back-to-back test runs
  - Example: `uihold_sec: 5`

### Complete Example

```protobuf
test {
    test_setup {
        device_workdir: "/sdcard"
        local_workdir: "/tmp/pixel8_tests"
        serial: "ABC123XYZ"
        device_cmd: "adb"
        separate_sources: true
        mediastore: "/tmp/media_cache"
        source_dir: "/home/user/videos"
        ignore_power_status: false
        uihold_sec: 3
    }
    common {
        id: "my_device_test"
        description: "Test configured for specific device"
    }
    input {
        filepath: "test_video.yuv"  # Relative to source_dir
        resolution: "1280x720"
        framerate: 30
    }
    configure {
        codec: "c2.exynos.h264.encoder"
        bitrate: "2000000"
    }
}
```

Save as `my_special_device.pbtxt` and run:
```bash
$ ./scripts/encapp.py run my_special_device.pbtxt
```

# 6. Advanced Usage Examples

## 6.1. Testing Multiple Bitrates

Test a codec across a range of bitrates:
```
# Test bitrates from 100kbps to 5Mbps in 100kbps increments
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -r 100k-5M-100k

# Test specific bitrates
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -r 500k,1M,2M,5M
```

## 6.2. Testing Multiple Resolutions

Test different resolutions with the same codec:
```bash
# Test multiple resolutions
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -s 320x240,640x480,1280x720,1920x1080

# Test with resolution range (experimental - multiplier-based)
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -s 320x240-1920x1080-2
```

## 6.3. Rate-Distortion Analysis

Run encoding tests with quality metrics for rate-distortion analysis:
```bash
# Single bitrate with quality measurement
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -r 1M --quality

# Multiple bitrates for RD curve generation
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -r 500k-5M-500k --quality -w /tmp/rd_test

# The quality.csv file will contain VMAF, SSIM, and PSNR metrics
```

## 6.4. Testing with Custom Parameters

Use vendor-specific parameters:
```bash
# Example for Qualcomm devices - set LTR frames
./scripts/encapp.py run tests/bitrate_buffer.pbtxt \
  -e configure.parameter.key "vendor.qti-ext-enc-ltr-count.num-ltr-frames" \
  -e configure.parameter.type "intType" \
  -e configure.parameter.value "3"

# Set bitrate mode and I-frame interval
./scripts/encapp.py run tests/bitrate_buffer.pbtxt \
  -e configure.bitrate_mode cbr \
  -e configure.i_frame_interval 60
```

## 6.5. Large File Handling

When working with large video files:
```bash
# Limit file transfer size and set chunk size
./scripts/encapp.py run tests/bitrate_buffer.pbtxt \
  -i /path/to/large_video.yuv \
  --file-transfer-limit 100M \
  --file-split-size 10M

# Use fast copy to minimize file operations
./scripts/encapp.py run tests/bitrate_buffer.pbtxt \
  -i /path/to/large_video.yuv \
  --fast-copy
```

## 6.6. Testing with Alignment Requirements

Some devices require specific alignment for stride:
```bash
# Set both width and height alignment to 16 pixels
./scripts/encapp.py run tests/bitrate_buffer.pbtxt --dim-align 16

# Or set them independently
./scripts/encapp.py run tests/bitrate_buffer.pbtxt --width-align 32 --height-align 16
```

## 6.7. iOS Testing

Test on iOS devices using the `--idb` flag or `--bundleid` option:
```bash
# Basic iOS test
./scripts/encapp.py run tests/bitrate_buffer.pbtxt --idb

# iOS test with specific bundle ID
./scripts/encapp.py run tests/bitrate_buffer.pbtxt --bundleid com.facebook.encapp.ios
```

**Important Notes for iOS:**

* **Installation:** The `install` command does not work for iOS devices. The encapp iOS app must be built and installed through Xcode. Open the iOS project in Xcode, build the app, and deploy it to your device before running tests.

* **Communication mechanism:** While the flag is named `--idb` (iOS Debug Bridge), encapp has switched to using `xcrun` as the underlying communication mechanism. The `--idb` flag name is maintained for compatibility, but the actual implementation uses `xcrun` device commands.

* **Requirements:** Ensure you have Xcode installed and properly configured with your iOS device. The device must be connected and trusted for development.

## 6.8. Parallel Testing

Run multiple encoding tests in parallel:
```bash
# Run test 3 times in parallel
./scripts/encapp.py run tests/bitrate_buffer.pbtxt --multiply 3

# Mix parallel tests from different configs
./scripts/encapp.py run tests/bitrate_buffer.pbtxt \
  --multiply "[2,][3,'tests/hevc_test.pbtxt']"
```

# 7. Navigating Results

The names of json result files do not give any clues as to what settings have been used.
To figure that out run:
```bash
$ ./scripts/encapp_search.py
```

Running it without any arguments will index all parsable json files in the current folder and below.

To find all 720p files run:
```bash
$ ./scripts/encapp_search.py -s 1280x720
```

## 7.1. Output naming

The default output naming is a uuid with not connection to the actual test being run.
However, "Common.output_filename" can be defined with placeholders to force a different naming scheme.
All settings can in theory be used, e.g.

```protobuf
 common {
        id: "bitrate_buffer"
        description: "Verify encoding bitrate - buffer"
        output_filename: "[input.filepath].[configure.bitrate].[xxxx]-[xx]"
    }
    configure {
        codec: "OMX.google.h264.encoder"
        bitrate: "100kbps,200kbps"
    }
```

The 'X' will give a random hex value (0-f), but of course any number of random numbers can be added. They can be used to minimize potential naming conflicts.

```bash
$ ./scripts/encapp.py run tests/bitrate_buffer_naming.pbtxt
ok: test id: "bitrate_buffer" run_id: akiyo_qcif.100kbps.1af5-c9 result: ok
ok: test id: "bitrate_buffer" run_id: akiyo_qcif.200kbps.6809-90 result: ok
```


# 8. Quality Analysis with encapp_quality

The `encapp_quality.py` script is used to calculate video quality metrics (VMAF, PSNR, SSIM) by comparing encoded video outputs against their source files. This is essential for evaluating encoder performance and generating rate-distortion (RD) curves.

## 8.1. Overview

### How it Works

The `encapp_quality` script processes encapp test results to calculate quality metrics:

1. **Source Information Parsing**: The script reads the output JSON file generated by encapp (e.g., `encapp_<uuid>.json`). This JSON file contains:
   - The input source filename (without folder path)
   - Encoding parameters (codec, bitrate, resolution, framerate, etc.)
   - Output video file location
   - Timing and frame information

2. **Device Under Test (DUT) Information**: The DUT information (device model, manufacturer, Android version, etc.) is also extracted from the same JSON file. This is stored in the `device.json` file that encapp creates alongside the test results.

3. **Source File Location**: Since the JSON file only contains the input filename (e.g., `akiyo_qcif.yuv`) without the full folder path, you must provide the directory where the source files are located using the `--media` option. The script will search for the source file in the specified directory. If the source file is in the same directory as the JSON file, this parameter may be omitted.

4. **Quality Calculation**: The script uses ffmpeg with libvmaf to calculate:
   - **VMAF** (Video Multimethod Assessment Fusion) - perceptual quality metric
   - **PSNR** (Peak Signal-to-Noise Ratio) - objective quality metric
   - **SSIM** (Structural Similarity Index) - structural comparison metric

5. **Output**: Results are saved to a CSV file (default: `quality.csv`) containing all metrics plus encoding metadata.

## 8.2. Basic Usage

```bash
# Calculate quality for a single test
./scripts/encapp_quality.py test_result.json --media /path/to/source/videos

# Calculate quality for multiple tests with CSV output
./scripts/encapp_quality.py --header --media /path/to/videos test1.json test2.json test3.json -o results.csv

# Use with encapp_search to process all results in a directory
./scripts/encapp_quality.py --header --media /path/to/videos $(./scripts/encapp_search.py)
```

## 8.3. Command Line Options

### Required Arguments

* `test` - One or more test result JSON files to process

### Output Options

* `-o`, `--output FILE` - Output CSV filename (default: `quality.csv`)
* `--header` - Print CSV header to output file
* `--csv` - Output CSV data from calculated results not already in CSV format

### Source File Options

* `--media DIR` - Directory where reference video files are located. Required if source files are not in the same directory as the JSON files.
* `-ref`, `--override_reference FILE` - Override the reference file path. Used when source was downsampled prior to encoding.
* `-s`, `--resolution WxH` - Override reference resolution (e.g., `1280x720`)
* `-fps`, `--framerate FPS` - Override reference framerate (e.g., `30`, `60`)
* `--pix_fmt FORMAT` - Pixel format override (e.g., `yuv420p`, `nv12`, `nv21`)

### VMAF Calculation Options

* `--vmaf_model MODEL` - Override VMAF model. Available models: `vmaf_4k_v0.6.1`, `vmaf_v0.6.1`, `vmaf_v0.6.1neg` (default: `vmaf_v0.6.1neg`)
* `--vmaf-crop WxH` - Use crop for calculating VMAF (e.g., `1920x1080`)
* `--vmaf-offset HxV` - Padding offset to use with crop (e.g., `0x0`)
* `--vmaf-scale WxH` - Use specific resolution for calculation. Scales both reference and distorted video to this resolution before comparison (e.g., `1920x1080`)
* `--vmaf-scaler SCALER` - Scaler algorithm for scaling (default: `lanczos`). Options: `bilinear`, `bicubic`, `lanczos`, `spline`

### Color Range Options

When comparing videos with different color ranges, use these options to force range conversions:

* `--fr_fr` - Force full range to full range on distorted file
* `--fr_lr` - Force full range to limited range on distorted file
* `--lr_fr` - Force limited range to full range on distorted file
* `--lr_lr` - Force limited range to limited range on distorted file

### Processing Options

* `-l`, `--limit_length SECONDS` - Limit verification length in seconds. Both reference and source must be same length (e.g., `10.0`)
* `--ignore-timing BOOL` - Ignore timing information and compare frame-by-frame (default: `True`). Creates temporary file without framerate info. Slower but helps when timing is broken.
* `--max-parallel N` - Maximum number of parallel processes (default: `1`)
* `--recalc` - Recalculate quality metrics regardless of cached status
* `--keep-quality-files` - Keep intermediate quality calculation files. Can also be set via `ENCAPP_KEEP_QUALITY_FILES` environment variable.

### Additional Metrics

* `--cvvdp` - Calculate ColorVideoVDP value
  - **Purpose:** Alternative to VMAF that also works on chroma planes, providing more comprehensive perceptual quality assessment
  - **Source:** [ColorVideoVDP](https://github.com/gfxdisp/ColorVideoVDP)
  - **Requirements:** Very resource demanding and in practice requires GPU resources for reasonable performance
  - **Note:** Requires additional dependencies to be installed separately

* `--qpextract` - Calculate QP (Quantization Parameter) distribution
  - **Purpose:** Analyzes quantization parameter values for each frame in the video stream
  - **Source:** Available in this fork: [libde265](https://github.com/chemag/libde265)
  - **Limitation:** Only works with HEVC streams
  - **Output:** Shows QP values for each frame in multiple different representations
  - **Note:** The qpextract tool must be installed and available in your PATH

* `--siti` - Run SI/TI (Spatial Information / Temporal Information) analysis
  - **Purpose:** Characterizes video complexity in terms of spatial (within frame) and temporal (between frames) information
  - **Source:** [siti](https://github.com/slhck/siti)
  - **Verification:** Check if already available: `ffmpeg -filters | grep siti`
  - **Performance:** Done on the source video to minimize runtime impact
  - **Use case:** Useful for categorizing video content complexity (e.g., low motion vs. high motion, simple vs. complex scenes)

### Metadata Options

* `--model MODEL` - Override device model from `device.json`
* `--mark_complexity LEVEL` - Set complexity marker for the collection (e.g., `low`, `mid`, `high`)
* `--mark_motion LEVEL` - Set motion marker for the collection (e.g., `low`, `mid`, `high`)

### Other Options

* `-d`, `--debug` - Increase verbosity (use multiple times for more detail)
* `--quiet` - Zero verbosity
* `--guess_original` - Assume source is transcoded in encapp
* `--info` - Print extra information during processing

## 8.4. Examples

### Basic Quality Calculation

Calculate quality for a single encoding test:
```bash
./scripts/encapp_quality.py \
  --media /tmp/test_videos \
  --header \
  /tmp/results/encapp_abc123.json
```

### Batch Processing with encapp_search

Process all test results in a directory:
```bash
# Find all 1080p test results and calculate quality
./scripts/encapp_quality.py \
  --header \
  --media /mnt/video_sources \
  -o quality_1080p.csv \
  $(./scripts/encapp_search.py -s 1920x1080)
```

### Rate-Distortion Analysis

Generate RD curve data by processing multiple bitrate tests:
```bash
# After running tests with multiple bitrates
./scripts/encapp.py run tests/bitrate_buffer.pbtxt -r 500k-5M-500k -w /tmp/rd_test

# Calculate quality for all results
./scripts/encapp_quality.py \
  --header \
  --media /tmp/test_videos \
  -o rd_curve.csv \
  /tmp/rd_test/*.json
```

### Advanced VMAF Calculation

Calculate VMAF with scaling and cropping:
```bash
# Scale to 1080p and crop to remove padding
./scripts/encapp_quality.py \
  --media /tmp/videos \
  --vmaf-scale 1920x1080 \
  --vmaf-crop 1920x1080 \
  --vmaf-scaler lanczos \
  --header \
  test_result.json
```

### With Custom VMAF Model

Use a specific VMAF model and keep intermediate files:
```bash
./scripts/encapp_quality.py \
  --media /tmp/videos \
  --vmaf_model vmaf_4k_v0.6.1 \
  --keep-quality-files \
  --header \
  test_4k.json
```

### Parallel Processing

Process multiple tests in parallel for faster results:
```bash
./scripts/encapp_quality.py \
  --media /tmp/videos \
  --max-parallel 4 \
  --header \
  -o batch_quality.csv \
  /tmp/results/*.json
```

### Video Characterization

Analyze spatial and temporal complexity of source videos:
```bash
./scripts/encapp_quality.py \
  --media /tmp/videos \
  --siti \
  --qpextract \
  --header \
  test_result.json
```

## 8.5. Understanding the Output

The output CSV file contains columns including:

**Test Information:**
- `test_id` - Test identifier from the JSON
- `run_id` - Unique run identifier
- `model` - Device model

**Encoding Parameters:**
- `codec` - Encoder name
- `bitrate` - Target bitrate
- `resolution` - Video resolution
- `framerate` - Video framerate

**Quality Metrics:**
- `vmaf_mean` - Mean VMAF score (0-100, higher is better)
- `vmaf_min` - Minimum VMAF score
- `vmaf_harmonic_mean` - Harmonic mean of VMAF scores
- `psnr_mean` - Mean PSNR in dB (higher is better)
- `ssim_mean` - Mean SSIM (0-1, higher is better)

**File Information:**
- `input_filepath` - Source video file
- `output_filepath` - Encoded video file
- `filesize` - Output file size in bytes

## 8.6. Tips and Best Practices

1. **Use mediastore for Source Files**: When running `encapp.py`, use the `--mediastore PATH` option to specify a directory where source files will be copied or transcoded. This is the directory you should reference with `--media` when running `encapp_quality.py`. Benefits of using a common mediastore:
   - **Speed**: If multiple tests share the same source, it only needs to be transcoded once
   - **Disk space**: Avoids duplicate source files across different test directories
   - **Device optimization**: On the device, an MD5 sum is used to verify if the source already exists, avoiding redundant transfers (unless `--separate-sources` is used)

2. **Use Consistent Naming**: When defining tests with `common.output_filename`, use meaningful names that include test parameters to make results easier to identify.

3. **VMAF Model Selection**:
   - Use `vmaf_v0.6.1` for standard HD content
   - Use `vmaf_4k_v0.6.1` for 4K/UHD content
   - Use `vmaf_v0.6.1neg` (default) to counteract attempts to fool VMAF by using methods like sharpening or other processing

4. **Performance**: Use `--max-parallel` to speed up batch processing if you have multiple CPU cores available.

5. **Debugging**: Use `--keep-quality-files` and `-d` for debugging when calculations fail or produce unexpected results.

6. **Integration with encapp run**: Use `--quality` flag with `encapp.py run` to automatically calculate quality metrics as part of the encoding workflow.

# 9. Requirements

## 8.1. Linux

Just install the pip packages.

```
$ sudo pip install humanfriendly numpy pandas seaborn protobuf
```


## 8.2. OSX

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


# 9. License

encapp is BSD licensed, as found in the [LICENSE](LICENSE) file.
