# x264 Native Encoder Build

## Prerequisites
- Android NDK installed (e.g., `~/Library/Android/sdk/ndk/29.0.14206865`)
- x264 library already built for Android at `modules/x264/android/arm64-v8a/`

## Quick Build (Recommended)

Set NDK path and host tag, then build:

```bash
cd native/x264_enc

# For macOS
export HOST_TAG=darwin-x86_64
export NDK=~/Library/Android/sdk/ndk/29.0.14206865
export PATH=$NDK:$PATH

# Build the library
make all
```

This will:
1. Compile `x264_enc.cpp` against the x264 static library
2. Create `libnativeencoder.so` in `libs/arm64-v8a/`
3. Copy the library to `/tmp/libnativeencoder.so`

## Deploy to Device

```bash
adb push /tmp/libnativeencoder.so /sdcard/
```

## Full Build (including x264 library)

If you need to rebuild the x264 library from source:

```bash
# Set environment
export HOST_TAG=darwin-x86_64  # For macOS (use linux-x86_64 for Linux)
export NDK=~/Library/Android/sdk/ndk/29.0.14206865

# Run the full build script (builds x264 + native encoder)
./build.sh
```

# Testing

The library will be copied to `/tmp/`. Shared libraries in the codec name field 
will behave similar to video files in the input section, i.e., they are copied 
to the device workdir automatically.

Example test configuration:
```
configure {
    codec: "/tmp/libnativeencoder.so"
    bitrate: "500 kbps"
}
```

To verify, run:
```bash
python3 scripts/encapp.py run tests/bitrate_buffer_x264.pbtxt
```

## Available x264 Parameters

Common parameters you can set in the test configuration:

- `preset`: ultrafast, superfast, veryfast, faster, fast, medium, slow, slower, veryslow, placebo
- `tune`: film, animation, grain, stillimage, psnr, ssim, fastdecode, zerolatency
- `i_threads`: number of encoding threads (1 for single-threaded)
- `i_bframe`: number of B-frames between I and P frames (0-16)
- `bitrate`: target bitrate (will be converted from bps to kbps internally)
- `bitrate_mode`: cq/cqp (constant QP), cbr/crf (constant rate factor), vbr/abr (average bitrate)

Example with B-frames:
```
parameter {
    key: "i_bframe"
    type: intType
    value: "3"
}
```

**Note:** Using `tune: "zerolatency"` disables B-frames.
