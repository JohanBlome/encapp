# encapp
Easy way to test video encoders in Android in large scale.

Facilitates an encoding mechanism for a large number of combinations in regards to
- codecs
- bitrate
- framerate
- i-frame interval
- coding mode

I also have support for dynamic changes with fps, bitrate, and ltr.
This is described in `scripts/offline_transcoding.sh`.


## 1. Prerequisites

- adb connection
- ffmpeg with decoding support for the codecs to be tested
- android sdk setup and environment variables set
- android ndk


## 2. Operation

(1) set up the android SDK and NDK in the `local.properties` file.

Create a `local.properties` file with valid entries for the `ndk.dir` and
`sdk.dir` variables.

```
$ cat local.properties
ndk.dir: /opt/android_ndk/android-ndk-r19/
sdk.dir: /opt/android_sdk/
```

Note that this file should not be added to the repo.

(2) build the encapp app

```
$ ./gradlew clean
$ ./gradlew build
...
BUILD SUCCESSFUL in 6s
61 actionable tasks: 5 executed, 56 up-to-date
```

(3) run the `setup.sh` script to install encapp in your android device.

```
$ ./setup.sh
...
Installing APK 'com.facebook.encapp-v1.0-debug.apk' on 'Pixel - 10' for app:debug
Installed on 4 devices.

BUILD SUCCESSFUL in 14s
31 actionable tasks: 3 executed, 28 up-to-date
```

(4) run a quick encoding experiment with the app

```
$ adb install ./app/build/outputs/apk/debug/com.facebook.encapp-v1.0-debug.apk
$ adb shell cmd package list package |grep encapp
package:com.facebook.encapp
$ ./gradlew installDebugAndroidTest

$ wget http://www.sunrayimage.com/download/image_examples/yuv420/tulips_yuv420_prog_planar_qcif.yuv
$ adb push tulips_yuv420_prog_planar_qcif.yuv /sdcard/
$ adb shell am instrument -w -r -e key 10 -e encl OMX.google.vp8.encoder -e file /sdcard/tulips_yuv420_prog_planar_qcif.yuv -e test_timeout 20 -e video_timeout 60 -e resl 128x96 -e bitl 100 -e skfr false -e debug false -e ltrc 1 -e mode cbr -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner
```

(5) run a multiple encoding experiment with the app

Copy the `run_test.sh` script to your local directory and edit it.

The example is using some command line arguments i.e.

```
$ ./local_copy.sh original.yuv 1280x720 xx_seconds functional_description
```

But those arguments could be set in the scripts as well (use bash, not sh).

