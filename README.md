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

Install the app.
```
$ adb install ./app/build/outputs/apk/debug/com.facebook.encapp-v1.0-debug.apk
$ adb shell cmd package list package |grep encapp
package:com.facebook.encapp
```

Install the instrumented test infra.
```
$ ./gradlew installDebugAndroidTest
$ adb shell pm list instrumentation
...
instrumentation:com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner (target=com.facebook.encapp)
...
```

Run the `list_codecs` function.

Note that, for the very first time you run the instrumentation codecs, the
device will ask you for permission to access to `/sdcard/`.

Figure 1 shows ![an android device asking for permission to run encapp](encapp_permission.jpeg)

```
$ adb shell am instrument -w -r -e list_codecs a -e test_timeout 20 -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner
INSTRUMENTATION_STATUS: class=com.facebook.encapp.CodecValidationInstrumentedTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
com.facebook.encapp.CodecValidationInstrumentedTest:
INSTRUMENTATION_STATUS: test=automateValidation
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.facebook.encapp.CodecValidationInstrumentedTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test=automateValidation
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 17.285

OK (1 test)


INSTRUMENTATION_CODE: -1

$ adb logcat |grep encapp |grep Codec:
...
11-13 12:06:41.004  2789  2789 D encapp  : Codec:c2.android.aac.encoder type: audio/mp4a-latm
11-13 12:06:41.004  2789  2789 D encapp  : Codec:OMX.google.aac.encoder type: audio/mp4a-latm
11-13 12:06:41.004  2789  2789 D encapp  : Codec:c2.android.amrnb.encoder type: audio/3gpp
11-13 12:06:41.005  2789  2789 D encapp  : Codec:OMX.google.amrnb.encoder type: audio/3gpp
11-13 12:06:41.005  2789  2789 D encapp  : Codec:c2.android.amrwb.encoder type: audio/amr-wb
11-13 12:06:41.005  2789  2789 D encapp  : Codec:OMX.google.amrwb.encoder type: audio/amr-wb
11-13 12:06:41.005  2789  2789 D encapp  : Codec:c2.android.flac.encoder type: audio/flac
11-13 12:06:41.005  2789  2789 D encapp  : Codec:OMX.google.flac.encoder type: audio/flac
11-13 12:06:41.006  2789  2789 D encapp  : Codec:c2.android.opus.encoder type: audio/opus
11-13 12:06:41.006  2789  2789 D encapp  : Codec:c2.qti.avc.encoder type: video/avc
11-13 12:06:41.006  2789  2789 D encapp  : Codec:OMX.qcom.video.encoder.avc type: video/avc
11-13 12:06:41.006  2789  2789 D encapp  : Codec:c2.qti.hevc.encoder type: video/hevc
11-13 12:06:41.006  2789  2789 D encapp  : Codec:OMX.qcom.video.encoder.hevc type: video/hevc
11-13 12:06:41.006  2789  2789 D encapp  : Codec:c2.qti.vp8.encoder type: video/x-vnd.on2.vp8
11-13 12:06:41.007  2789  2789 D encapp  : Codec:OMX.qcom.video.encoder.vp8 type: video/x-vnd.on2.vp8
11-13 12:06:41.007  2789  2789 D encapp  : Codec:c2.android.avc.encoder type: video/avc
11-13 12:06:41.007  2789  2789 D encapp  : Codec:OMX.google.h264.encoder type: video/avc
11-13 12:06:41.007  2789  2789 D encapp  : Codec:c2.android.h263.encoder type: video/3gpp
11-13 12:06:41.007  2789  2789 D encapp  : Codec:OMX.google.h263.encoder type: video/3gpp
11-13 12:06:41.007  2789  2789 D encapp  : Codec:c2.android.hevc.encoder type: video/hevc
11-13 12:06:41.008  2789  2789 D encapp  : Codec:c2.android.mpeg4.encoder type: video/mp4v-es
11-13 12:06:41.008  2789  2789 D encapp  : Codec:OMX.google.mpeg4.encoder type: video/mp4v-es
11-13 12:06:41.008  2789  2789 D encapp  : Codec:c2.android.vp8.encoder type: video/x-vnd.on2.vp8
11-13 12:06:41.008  2789  2789 D encapp  : Codec:OMX.google.vp8.encoder type: video/x-vnd.on2.vp8
11-13 12:06:41.008  2789  2789 D encapp  : Codec:c2.android.vp9.encoder type: video/x-vnd.on2.vp9
11-13 12:06:41.009  2789  2789 D encapp  : Codec:OMX.google.vp9.encoder type: video/x-vnd.on2.vp9
...
```

(5) run a quick encoding experiment with the app

First, choose one of the codecs from step 4.

Prepare the device with an actual file:
```
$ wget http://www.sunrayimage.com/download/image_examples/yuv420/tulips_yuv420_prog_planar_qcif.yuv
$ adb push tulips_yuv420_prog_planar_qcif.yuv /sdcard/
```

Now run the vp8 encoder (`OMX.google.vp8.encoder`):
```
$ adb shell am instrument -w -r -e key 10 -e encl OMX.google.vp8.encoder -e file /sdcard/tulips_yuv420_prog_planar_qcif.yuv -e test_timeout 20 -e resl 176x144 -e bitl 100 -e skfr false -e debug false -e ltrc 1 -e mode cbr -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner
...
```

And pull the encoded file:

```
$ adb pull /sdcard/omx.google.vp8.encoder_30fps_176x144_100000bps_iint10.webm /tmp/
$ ffmpeg -i /tmp/omx.google.vp8.encoder_30fps_176x144_100000bps_iint10.webm
...
    Stream #0:0: Video: vp8, yuv420p(progressive), 176x144, SAR 1:1 DAR 11:9, 1k tbr, 1k tbn, 1k tbc (default)
```


(6) run a multiple encoding experiment with the app

Copy the `run_test.sh` script to your local directory and edit it.

The example is using some command line arguments i.e.

```
$ ./local_copy.sh original.yuv 1280x720 xx_seconds functional_description
```

But those arguments could be set in the scripts as well (use bash, not sh).

