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

## Prerequisites

- adb connection
- ffmpeg with decoding support for the codecs to be tested
- android sdk setup and environment variables set
- android ndk


## Operation

(1) set up the android SDK and NDK in the `local.properties` file.

Create a `local.properties` file with valid entries for the `ndk.dir` and
`sdk.dir` variables.

```
$ cat local.properties
ndk.dir: /opt/android_ndk/android-ndk-r19/
sdk.dir: /opt/android_sdk/
```

Note that this file should not be added to the repo.

(2) run the `setup.sh` script to install encapp in your android device.

```
$ ./setup.sh
...
Installing APK 'app-debug.apk' on 'Pixel 2 - 10' for app:debug
Installed on 4 devices.

BUILD SUCCESSFUL in 14s
31 actionable tasks: 3 executed, 28 up-to-date
```

(3) run an encoding experiment with the app


Copy the `run_test.sh` script to your local directory and edit it.

The example is using some command line arguments i.e.

```
$ ./local_copy.sh original.yuv 1280x720 xx_seconds functional_description
```

But those arguments could be set in the scripts as well (use bash, not sh).

