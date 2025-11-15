# LCEVC-Integrated encapp Build and Run Instructions
--------------------------------

## 1. Prerequisites

* Android SDK 33 & NDK 21 (Android Studio Ladybug or newer recommended)
* Python >= 3.19.0
* GNU Make >= 3.81 
* CMake >= 3.19.0
* [VMAF](https://github.com/Netflix/vmaf/blob/master/libvmaf/README.md)

## 2. Install V-Nova’s LCEVC SDK

Get the following SDK packages from [V-Nova's download portal](https://download.v-nova.com) (sign-up required):
- LCEVC Encoder SDK (Android)
- Android x264 base plugin
- Android MediaCodec base plugin

Extract them into your local Maven repository:
- Linux/Mac: `${HOME}/.m2/repository`
- Windows: `C:\Users\<username>\.m2\repository`

Ensure the structure is preserved:
```
--- .m2/
    ---- repository/
        ---- com/
            ---- vnova/
                ---- lcevc/
                    ---- eil/
                    │   ---- 3.14.7/
                    │       ---- eil-3.14.7.aar
                    │       ---- eil-3.14.7.pom
                    │       ---- eil-3.14.7-javadoc.jar
                    ---- eilp/
                    │   ---- mediacodec/
                    │   │   ---- 3.14.7/
                    │   │       ---- mediacodec-3.14.7.aar
                    │   │       ---- mediacodec-3.14.7.pom
                    │   ---- x264/
                    │       ---- 3.14.7/
                    │           ---- x264-3.14.7.aar
                    │           ---- x264-3.14.7.pom
                    ---- jni/
                    │   ---- 3.14.7/
                    │       ---- jni-3.14.7.jar
                    │       ---- jni-3.14.7.pom
                    │       ---- jni-3.14.7.module
                    ---- util/
                        ---- 3.14.7/
                            ---- util-3.14.7.jar
                            ---- util-3.14.7.pom
                            ---- util-3.14.7.module
```

Note: Verify that versions in your `~/.m2` match those in `app/build.gradle`. If not, update them accordingly.


## 3. Setup and Build x264 Library:
Set ndk path and build tools first. Ensure your NDK version is 21.
```bash
# for MacOs
export HOST_TAG=darwin-x86_64
export NDK="/System/Volumes/Data/Users/XXX/Library/Android/sdk/ndk-bundle/"
```

Run script. Ensure you are in the root folder.
```bash
chmod +x app/src/lcevc/scripts/x264_build.sh && ./app/src/lcevc/scripts/x264_build.sh
```
Optional: Run with `--clean` to clean build (executes `make clean`).
The build library will be copied to lcevc/x264/libs


## 4. Build and Install LCEVC-supported encapp:

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT    # optional, older tools use it

./scripts/encappy.py uninstall          # optional, uninstalls older app

./gradlew clean
./gradlew :app:assembleLcevcDebug
./gradlew :app:installLcevcDebug
adb shell appops set --uid com.facebook.encapp MANAGE_EXTERNAL_STORAGE allow
```


## 5. Configure the Test Script:

Currently, only the `lcevc_h264` codec is supported. The supported base encoders are `mediacodec_h264` and `x264`.

```
test {
    common {
        id: "X"
        description: "Test description"
    }
    input {
        filepath: "mediaX.yuv"
        resolution: "WxH"
        framerate: XX
    }
    
    configure {
        codec: "lcevc_h264"
        bitrate: "XXX kbps"
        i_frame_interval: XX
        
        parameter {
            key: "base_encoder"
            type: stringType
            value: "x264" 
        }
        
        parameter {
            key: "encoding_debug_residuals"
            type: intType
            value: "1" 
        }
        
        parameter {
            key: "qp_max"
            type: intType
            value: "XX" 
        }
    }
}
```

## 6. Run Test:


```bash
./scripts/encapp.py run tests/lcevc_x264.pbtxt
```


## 7. Build FFmpeg with LCEVC Decoder 

The steps below describe how to build FFmpeg with LCEVC support.
This will enable decoding of LCEVC-enhanced video streams using FFmpeg and V-Nova’s LCEVCdec libraries.
Note: Integration of FFmpeg with LCEVCdec is available starting from FFmpeg version 7.1.

### 7.1. Clone and Checkout FFmpeg

```bash
git clone https://git.ffmpeg.org/ffmpeg.git
cd ffmpeg
git checkout release/7.1
```


### 7.2. Install LCEVC Decoder SDK

```bash
git clone https://github.com/v-novaltd/LCEVCdec.git
cd LCEVCdec
git checkout 3.3.9
mkdir build
cd build
cmake ..
cmake --build . --config Release
cmake --install .
```

Libraries, headers and licenses will be installed to the system (`/usr/local`) unless CMAKE_INSTALL_PREFIX is specified. 
A .pc pkg-config file is also installed to <install_prefix>/lib/pkgconfig to use LCEVCdec as a dependency in downstream projects.

```bash
export PKG_CONFIG_PATH=$PKG_CONFIG_PATH:<install_prefix>/lib/pkgconfig
export LD_LIBARY_PATH=$LD_LIBARY_PATH:<install_prefix>/lib
```


### 7.3. Configure and Build FFmpeg with LCEVC

Ensure VMAF has been added to `LD_LIBRARY_PATH` and `PKG_CONFIG_PATH`

```bash
./configure --enable-libvmaf --enable-liblcevc-dec --prefix=/path/to/install
```


### 7.4. Build FFmpeg

```bash
make -j$(nproc)
make install
```

Set the built ffmpeg as the default.

```bash
export PATH=/path/to/install/bin:$PATH
```

### 7.5. Verify FFmpeg

```bash
ffmpeg -codecs | grep lcevc
```

This command should list the LCEVC decoder, indicating that FFmpeg is successfully configured with LCEVC support. You should see the following line:

```
 ..D... lcevc                LCEVC (Low Complexity Enhancement Video Coding) / MPEG-5 LCEVC / MPEG-5 part 2
```


## 8. Verify Test:

FFmpeg with LCEVC support is required to run `encapp_quality.py`.
Make sure to set this FFmpeg build in your PATH. 

```bash
./scripts/encapp_quality.py --media /path/to/media/folder /path/to/json/file/in/media/folder --keep-quality-files --csv
```

This calculates the video quality properties (vmaf, ssim and psnr).
- `--csv` writes the calculated qualities to a csv file.
- `--keep-quality-files` stores the generated video quality files.
