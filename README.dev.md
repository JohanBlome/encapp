# encapp development

This page provides instructions on how to build and develop encapp, including using the tool without the encapp.py script.

For a description on how to use the tool, check [README.md](README.md).


# 1. Prerequisites

In order to do encapp development, you need:
* android sdk setup and environment variables set
* android ndk


# 2. Operation

## 2.1. set up the android SDK and NDK in the `local.properties` file.

Create a `local.properties` file with valid entries for the `ndk.dir` and
`sdk.dir` variables.

```
$ cat local.properties
ndk.dir: /opt/android_ndk/android-ndk-r19/
sdk.dir: /opt/android_sdk/
```

Note that this file should not be added to the repo.

## 2.2. build the encapp app

```
$ ./gradlew clean
$ ./gradlew build
...
BUILD SUCCESSFUL in 6s
61 actionable tasks: 5 executed, 56 up-to-date
```

## 2.3. run the `setup.sh` script to install encapp in your android device.

```
$ ./setup.sh
...
Installing APK 'com.facebook.encapp-v1.0-debug.apk' on 'Pixel - 10' for app:debug
Installed on 4 devices.

BUILD SUCCESSFUL in 14s
31 actionable tasks: 3 executed, 28 up-to-date
```

## 2.4. run a quick encoding experiment with the app

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
instrumentation:com.facebook.encapp.test/androidx.test.runner.AndroidJUnitRunner (target=com.facebook.encapp)
...
```

Run the `list_codecs` function.

Note that, for the very first time you run the instrumentation codecs, the
device will ask you for permission to access to `/sdcard/`.

Figure 1 shows ![an android device asking for permission to run encapp](doc/encapp_permission.jpeg)

```
$ adb shell am instrument -w -r -e list_codecs a -e test_timeout 20 -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/androidx.test.runner.AndroidJUnitRunneradb shell am instrument -w -r -e list_codecs a -e ui_hold_sec 20 -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/androidx.test.runner.AndroidJUnitRunner
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

Time: 28.422

OK (1 test)

INSTRUMENTATION_CODE: -1
```

```
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

# 3. run a quick encoding experiment with the app

## 3.1. small qcif encoding

First, choose one of the codecs from step 4. In this case, we will use `OMX.google.vp8.encoder`.

Push the (raw) video file to be encoded into the device. Note that we are using a QCIF video (176x144).
```
$ wget https://media.xiph.org/video/derf/y4m/akiyo_qcif.y4m -O /tmp/akiyo_qcif.y4m
$ ffmpeg -i /tmp/akiyo_qcif.y4m -f rawvideo -pix_fmt yuv420p /tmp/akiyo_qcif.yuv
$ adb push /tmp/akiyo_qcif.yuv /sdcard/
```

Now run the vp8 encoder (`OMX.google.vp8.encoder`):
```
$ adb shell am instrument -w -r -e key 10 -e enc OMX.google.vp8.encoder -e file /sdcard/akiyo_qcif.yuv -e test_timeout 20 -e video_timeout 3 -e res 176x144 -e ref_res 176x144 -e bit 100 -e mod cbr -e fps 30 -e ifsize unlimited -e skfr false -e debug false -e ltrc 1 -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/androidx.test.runner.AndroidJUnitRunner
...
```

And pull the encoded file:
```
$ adb pull /sdcard/omx.google.vp8.encoder_30fps_176x144_100000bps_iint10_m2.webm /tmp/
$ ffprobe -i /tmp/omx.google.vp8.encoder_30fps_176x144_100000bps_iint10_m2.webm
...
  Duration: 00:00:02.93, start: 0.000000, bitrate: 113 kb/s
    Stream #0:0: Video: vp8, yuv420p(tv, smpte170m/smpte170m/bt709, progressive), 176x144, SAR 1:1 DAR 11:9, 30 fps, 30 tbr, 1k tbn, 1k tbc (default)
```

## 3.2. hd encoding

Now, let's run the h264 encoder in an HD file. We will just select the codec ("h264"), and let encapp choose the actual encoder.

```
$ wget https://media.xiph.org/video/derf/y4m/KristenAndSara_1280x720_60.y4m
$ ffmpeg -i /tmp/KristenAndSara_1280x720_60.y4m -f rawvideo -pix_fmt yuv420p /tmp/KristenAndSara_1280x720_60.yuv
$ adb push /tmp/KristenAndSara_1280x720_60.yuv /sdcard/
```

```
$ adb shell am instrument -w -r -e key 10 -e enc h264 -e file /sdcard/KristenAndSara_1280x720_60.yuv -e test_timeout 20 -e video_timeout 3 -e res 1280x720 -e ref_res 1280x720 -e bit 100 -e mod cbr -e fps 60 -e ifsize unlimited -e skfr false -e debug false -e ltrc 1 -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/androidx.test.runner.AndroidJUnitRunner
...
```

