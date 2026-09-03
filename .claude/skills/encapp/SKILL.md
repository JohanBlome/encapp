---
name: encapp
description: This skill should be used when the user asks about "encapp", "video encoding tests", "codec testing", "Android encoder", "transcode test", "camera encoding", "video quality metrics", "VMAF", "rate-distortion", or running video encoding experiments on Android devices.
---

# Encapp - Android Video Codec Testing Tool

Encapp is a tool for testing and characterizing video encoders and decoders on Android devices. It measures encoding/decoding performance and calculates video quality metrics (VMAF, PSNR, SSIM).

**Repository:** `/home/chemag/proj/encapp`

## Quick Reference

### Installation

```bash
# Install on device
./scripts/encapp.py install --serial <SERIAL>

# Grant permissions (Android 11+)
adb -s <SERIAL> shell appops set --uid com.facebook.encapp MANAGE_EXTERNAL_STORAGE allow
```

### List Codecs

```bash
./scripts/encapp.py list --serial <SERIAL>
./scripts/encapp.py list --serial <SERIAL> -enc  # encoders only
./scripts/encapp.py list --serial <SERIAL> -hw   # hardware only
```

### Run Tests

```bash
./scripts/encapp.py run <config.pbtxt> --serial <SERIAL> -w /tmp/results
```

## Known Devices and Hardware Codecs

| Device               | Serial         | HW H264                | HW HEVC                | HW AV1                 |
|----------------------|----------------|------------------------|------------------------|------------------------|
| S25+ (Qualcomm)      | R5CXC2ZH3DR    | c2.qti.avc.encoder     | c2.qti.hevc.encoder    | -                      |
| Pixel 9 Pro (Tensor) | 48031FDAS000F6 | c2.exynos.h264.encoder | c2.exynos.hevc.encoder | -                      |
| Pixel 10 Pro         | 57080DLCH001A2 | c2.android.avc.encoder | c2.google.hevc.encoder | c2.android.av1.encoder |

## Test Configuration Format (.pbtxt)

Tests use Protocol Buffer text format. Key sections:

```protobuf
test {
    input {
        filepath: "/path/to/video.mp4"  # or "camera" for camera input
        resolution: "1280x720"
        framerate: 30
        playout_frames: 600             # total frames (loops if > video length)
        realtime: true                  # feed at realtime speed (e.g., 30fps)
        device_decode: true             # for transcoding (decode on device)
        show: true                      # display on screen
    }
    common {
        id: "test_id"
        description: "Human readable description"
    }
    configure {
        codec: "c2.qti.hevc.encoder"
        bitrate: "5 Mbps"
        surface: true                   # GPU surface mode (required for transcode/camera)
        bitrate_mode: cbr               # cbr, vbr, cq
        i_frame_interval: 2             # keyframe interval in seconds
    }
}
```

## Common Experiment Types

### 1. Transcode Encoded File to HEVC (Realtime)

Decodes input on device, re-encodes to HEVC at realtime speed.

**Config:** `tests/exp1_transcode_realtime.pbtxt`

```bash
./scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt \
  -i /path/to/input.mp4 \
  -c c2.qti.hevc.encoder \
  --serial R5CXC2ZH3DR \
  -w /tmp/results
```

Key settings:
- `device_decode: true` - decode on device
- `realtime: true` - feed at framerate speed
- `surface: true` - use GPU surface for decode-to-encode

### 2. Encode Raw YUV/Y4M to HEVC (Realtime)

Encodes raw video at realtime speed.

**Config:** `tests/exp2_raw_encode_realtime.pbtxt`

```bash
./scripts/encapp.py run tests/exp2_raw_encode_realtime.pbtxt \
  -i /path/to/input.y4m \
  -e input.resolution 1280x720 \
  -c c2.qti.hevc.encoder \
  --serial R5CXC2ZH3DR \
  -w /tmp/results
```

Key settings:
- `realtime: true` - feed at framerate speed
- `pix_fmt: yuv420p` - pixel format for raw YUV (Y4M auto-detected)

### 3. Camera Capture and Encode

Captures from device camera and encodes.

**Config:** `tests/exp3_camera_encode.pbtxt`

```bash
./scripts/encapp.py run tests/exp3_camera_encode.pbtxt \
  -c c2.qti.hevc.encoder \
  --serial R5CXC2ZH3DR \
  -w /tmp/results
```

Key settings:
- `filepath: "camera"` - use camera as input
- `surface: true` - required for camera
- `show: true` - display preview on screen

## Common CLI Options

| Option | Description |
|--------|-------------|
| `--serial SERIAL` | Device serial number |
| `-i FILE` | Override input file |
| `-c CODEC` | Override codec name |
| `-r BITRATE` | Override bitrate (e.g., `1M`, `100k-5M-500k`, `1M,2M,5M`) |
| `-s SIZE` | Override resolution (e.g., `1280x720`) |
| `-fps RATE` | Override framerate |
| `-w DIR` | Local working directory for results |
| `-e KEY VALUE` | Override any config parameter (e.g., `-e input.realtime true`) |
| `--quality` | Calculate quality metrics after encoding |
| `--multiply N` | Run test N times in parallel |
| `--split` | Run tests serially with resume capability |
| `--dry-run` | Validate config without executing |

## Quality Analysis

```bash
# Calculate VMAF/PSNR/SSIM
./scripts/encapp_quality.py --media /path/to/source/videos result.json -o quality.csv

# With multiple results
./scripts/encapp_quality.py --media /videos /tmp/results/*.json -o quality.csv
```

## Test Videos

Common test videos location: `~/work/video/power/batteryexp.2026/vid/`

| File | Format | Resolution |
|------|--------|------------|
| johnny_1280x720_30.y4m | Raw Y4M | 1280x720 @ 30fps |
| johnny_1280x720_30.x264.mp4 | H.264 | 1280x720 @ 30fps |
| johnny_1280x720_30.x265.mp4 | HEVC | 1280x720 @ 30fps |
| johnny_1280x720_30.av1.mp4 | AV1 | 1280x720 @ 30fps |

## Looping Short Videos

Set `playout_frames` higher than video frame count to loop:
- 10-second video at 30fps = 300 frames
- For 20 seconds: `playout_frames: 600` (loops twice)

## Output Files

Each test produces:
- `encapp_<UUID>.json` - Test metadata, config, and performance metrics
- `encapp_<UUID>.mp4` - Encoded video output

For every encapp experiment, preserve the Android logcat slice from the run.
This is mandatory for debugging device-specific codec issues. Encapp emits
important details only in logcat, including selected codec, configured
`MediaFormat`, color format, stride, slice height, crop rectangle, muxer state,
and asynchronous buffer encoder progress. Keep the CLI-generated
`*.android_logcat.txt` file with the output artifacts. If the CLI does not pull
one, collect it manually with a filtered logcat command, for example:

```bash
adb -s <SERIAL> logcat -d -t 5000 \
  | grep -Ei 'encapp|MediaCodec|codec|mux|stride|slice-height|crop|format|error|exception' \
  > <local-workdir>/<test-id>.android_logcat.txt
```

When an output video looks corrupt, inspect logcat before changing encoder
settings. Common clues are `stride`, `slice-height`, `crop-right`,
`crop-bottom`, `color-format`, and the file reader pixel format. For example,
a tightly packed 1080-wide NV12 input can be corrupted if the selected encoder
expects stride 1088 and encapp reads the input without padding.

## Batch Mode vs Realtime Mode

| Mode | Setting | Behavior |
|------|---------|----------|
| Batch | `realtime: false` (default) | Encode as fast as possible |
| Realtime | `realtime: true` | Feed frames at specified framerate |

## Example: Rate-Distortion Curve

```bash
./scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt \
  -r 100k,500k,1M,2M,5M,10M \
  --quality \
  -c c2.qti.hevc.encoder \
  --serial R5CXC2ZH3DR \
  -w /tmp/rd_curve
```

## Troubleshooting

### Python/protobuf import failure on newer system Python

Symptom:

```text
ImportError: cannot import name 'builder' from 'google.protobuf.internal'
```

This can happen when the host system Python/protobuf package is incompatible
with the generated `scripts/proto/tests_pb2.py` checked into the encapp repo.
On maggie this was observed with system Python 3.14 and the distro protobuf
package: `encapp.py` failed before it could talk to adb or the device.

Root cause: the generated protobuf bindings expect `google.protobuf.internal.builder`,
but the protobuf module found on `sys.path` does not provide it. This is a
host Python environment problem, not an Android device or encapp APK problem.

Fix: run encapp from a repo-local or temporary virtualenv with a compatible
protobuf package, then invoke encapp through that venv. Example:

```bash
cd ~/proj/encapp
python3 -m venv /tmp/encapp-venv
. /tmp/encapp-venv/bin/activate
python -m pip install --upgrade pip
python -m pip install 'protobuf>=6.30' humanfriendly argparse-formatter numpy pandas scipy matplotlib seaborn

# Verify import path/version
python -c 'import google.protobuf; print(google.protobuf.__version__, google.protobuf.__file__)'
python scripts/encapp.py --help

# Device wrapper aliases can still be used by calling the venv python explicitly
mtk1 /tmp/encapp-venv/bin/python ~/proj/encapp/scripts/encapp.py list
pixel8pro.fb2 /tmp/encapp-venv/bin/python ~/proj/encapp/scripts/encapp.py list
```

If `encapp.py list` still hangs after the venv import fix, continue with the
APK/script version-coherence checks above; that is a separate issue from the
Python import failure.

```bash
# Check if encapp is installed
adb -s <SERIAL> shell pm list packages | grep encapp

# Kill stuck app
./scripts/encapp.py kill --serial <SERIAL>

# Clear device files
./scripts/encapp.py clear --serial <SERIAL>

# Check device logs
adb -s <SERIAL> logcat -s encapp
```
