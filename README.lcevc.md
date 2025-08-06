LCEVC-Integrated encapp Build and Run Instructions
--------------------------------

1. Install V-Nova’s LCEVC SDK

Get the following SDK packages from [V-Nova's download portal](https://download.v-nova.com) (sign-up required):
- LCEVC Encoder SDK (Android)
- Android x264 base plugin
- Android MediaCodec base plugin

Extract them into your local Maven repository:
- Linux/Mac: `${HOME}/.m2/repository`
- Windows: `C:\Users\<username>\.m2\repository`

Ensure the structure is preserved:
```
com/vnova/lcevc/eil/
com/vnova/lcevc/eilp/
com/vnova/lcevc/jni/
```

Note: Verify that versions in your `~/.m2` match those in `app/build.gradle`. If not, update them accordingly.

2. Setup and Build:

The instructions for setting up your system environment and building x264 are at [x264 Build README](lcevc/x264/README.md)

3. Build and Install encapp:

```
./scripts/encapp.py install
adb shell appops set --uid com.facebook.encapp MANAGE_EXTERNAL_STORAGE allow
```

4. Configure the Test Script:

In the test files (`.pbtxt`), parameters _codec_, _frameRate_, _i_frame_interval_ and _bitrate_ should be set in `test.configure`.

Currently, only the `lcevc_h264` codec is supported. The supported base encoders are `mediacodec_h264` and `x264`.
LCEVC EIL parameters can be passed in 2 ways.

- As _direct parameters_ using the `parameter` object:
```
test {
    common {
        id: "X"
        description: "Test description"
    }
    input {
        filepath: "mediaX.yuv"
        resolution: "WxH"
    }
    
    configure {
        codec: "lcevc_h264"
        bitrate: "XXX kbps"
        framerate: XX
    }
    parameter {
        key: "base_encoder"
        type: stringType
        value: "x264" 
    }
    parameter {
        key: "encoding_debug_residuals"
        type: stringType
        value: "true" 
    }
    parameter {
        key: "qp_max"
        type: intType
        value: 40 
    }
}
```

- Using a _json config_ file passed to the `eil_params_path` parameter key:
```
test {
    common {
        id: "X"
        description: "Test description"
    }
    input {
        filepath: "mediaX.yuv"
        resolution: "WxH"
    }
    
    configure {
        codec: "lcevc_h264"
        bitrate: "XXX kbps"
        framerate: XX
    }
    parameter {
         key: "eil_params_path"
         type: stringType
         value: "/tmp/eilConfig.json" 
    }
}
```

NOTE: If both _json config_ and _direct parameter_ methods are used, only _direct parameter_ will be used.

5. Run Test:

The `lcevc` flag must be used when running lcevc encodes.
```
./scripts/encapp.py run tests/lcevc_x264.pbtxt --lcevc
```

6. Verify Test:

FFmpeg and LCEVC Decoder SDK are required to run `encapp_quality.py`.
The LCEVCDec binaries can also be found at [V-Nova's download portal](https://download.v-nova.com) under _LCEVC Decoder SDK (Android)_.

```
./scripts/encapp_quality.py --header --media /path/to/media/folder /path/to/json/file/in/media/folder --keep-quality-files --csv
```
This calculates the video quality properties (vmaf, ssim and psnr).
- `--csv` writes the calculated qualities to a csv file.
- `--keep-quality-files` stores the generated video quality files.