# encapp development

This page provides instructions on how to build and develop encapp, including using the tool without the encapp.py script.

For a description on how to use the tool, check [README.md](README.md).


# 1. Prerequisites

## 1.1. Required Tools

To develop encapp, you need the following tools installed:

### Android Development Tools
adb push out/target/product/diamond/testcases/libmc2_tests/arm64/libmc2_tests /data/

* **Android SDK** - Android Software Development Kit with environment variables properly set
  - Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` environment variable
  - Add platform-tools to your PATH
* **Android NDK** - Native Development Kit for C/C++ code compilation
  - Required for building native components
* **Gradle** - Build automation tool (version 7.0 or higher)
  - Bundled with the project via Gradle Wrapper (`gradlew`)

### Java Development Kit

* **JDK 16 or higher** - Required for building with Gradle 7+
  - **Important:** Gradle versions < 7 are broken with JDK 16+
  - Verify installation: `java -version`

### Additional Tools

* **Protocol Buffer Compiler (protoc)** - Optional, only needed if modifying `.proto` files
  - Installation: See [main README section 1.1](README.md#11-external-tool-dependencies)
* **Python 3.9+** - For running scripts and tests
  - Installation: See [main README section 1.2](README.md#12-python-dependencies)

## 1.2. iOS Development (Optional)

For iOS development, you additionally need:

* **Xcode** - Apple's IDE with iOS SDK
* **Command Line Tools** - Install via `xcode-select --install`
* **Valid iOS provisioning profile** - For device deployment


# 2. Building encapp

## 2.1. Set up Android SDK and NDK

Create a `local.properties` file in the project root with valid entries for the `ndk.dir` and `sdk.dir` variables:

```properties
ndk.dir=/opt/android_ndk/android-ndk-r19/
sdk.dir=/opt/android_sdk/
```

**Note:** This file should not be added to version control (it's already in `.gitignore`).

## 2.2. Build the encapp App

Build the Android application using Gradle:

```bash
$ ./gradlew clean
$ ./gradlew build
...
BUILD SUCCESSFUL in 6s
61 actionable tasks: 5 executed, 56 up-to-date
```

The APK will be generated in `app/build/outputs/apk/debug/`.

## 2.3. Install Using setup.sh Script

The `setup.sh` script builds and installs encapp on all connected devices:

```bash
$ ./setup.sh
...
Installing APK 'com.facebook.encapp-v1.0-debug.apk' on 'Pixel - 10' for app:debug
Installed on 4 devices.

BUILD SUCCESSFUL in 14s
31 actionable tasks: 3 executed, 28 up-to-date
```

## 2.4. Manual Installation and Testing

Alternatively, manually install and test the app:

```bash
# Install the APK
$ adb install ./app/build/outputs/apk/debug/com.facebook.encapp-v1.0-debug.apk

# Verify installation
$ adb shell cmd package list package | grep encapp
package:com.facebook.encapp
```

### Running Direct Tests

You can test encapp functionality directly using `adb` commands without the Python script.

**List Available Codecs:**

```bash
$ adb shell am start -W -e list_codecs a -e ui_hold_sec 3 com.facebook.encapp/.MainActivity
```

**Note:** The first time you run encapp, the device will ask for permission to access `/sdcard/`.

![Android device asking for permission to run encapp](doc/encapp_permission.jpeg)
*Figure 1: Permission dialog on first run*

```
$ adb shell am start -W -e list_codecs a -e ui_hold_sec 3 com.facebook.encapp/.MainActivity
Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] cmp=com.facebook.encapp/.MainActivity (has extras) }
Status: ok
LaunchState: COLD
Activity: com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeActivity
TotalTime: 1479
WaitTime: 1482
Complete
```

```
$ adb shell cat /sdcard/codecs.txt
--- List of supported encoders  ---

        MediaCodec {
            name: OMX.qcom.video.encoder.avc
            type {
                mime_type: video/avc
                max_supported_instances: 24
                color {
                    format: 2141391878
                }
                color {
                    format: 2141391876
                }
                color {
                    format: 2141391872
                    name: COLOR_QCOM_FormatYUV420SemiPlanar
                }
                color {
                    format: 2141391881
                }
                color {
                    format: 2141391882
                }
                color {
                    format: 2141391880
                }
                color {
                    format: 2141391879
                }
                color {
                    format: 2130708361
                    name: COLOR_FormatSurface
                }
                color {
                    format: 2135033992
                    name: COLOR_FormatYUV420Flexible
                }
                color {
                    format: 21
                    name: COLOR_FormatYUV420SemiPlanar
                }
                profile {
                    profile: 65536
                    level: 65536
                }
                profile {
                    profile: 1
                    level: 65536
                }
                profile {
                    profile: 2
                    level: 65536
                }
                profile {
                    profile: 524288
                    level: 65536
                }
                profile {
                    profile: 8
                    level: 65536
                }
            }
        }

        MediaCodec {
            name: OMX.qcom.video.encoder.h263sw
            type {
...
```

# 3. Regression Testing

**NOTE: This is currently not working properly and will be addressed in a future update.**

The `encapp_verify.py` script runs through tests defined in the `tests/` folder and verifies:
* Bitrate conformance
* Key frame intervals
* Temporal layer configuration
* Long term references (LTR) - Qualcomm specific

## 3.1. Basic Usage

Run all tests with default configuration:

```bash
$ ./scripts/encapp_verify.py
```

## 3.2. Override Options

Override input, encoding resolution, and codec:

```bash
$ ./scripts/encapp_verify.py \
  -i /media/data/media_encapp/<encoded>.mp4 \
  -os 1920x1080 \
  -c encoder.avc
```

**Note:** This currently only works for encoded files, not raw files.

## 3.3. Run Specific Test

Override input and run a specific test:

```bash
$ ./scripts/encapp_verify.py \
  -i /tmp/KristenAndSara_1280x720_60.yuv \
  -is 1280x720 \
  -if 30 \
  -os 1280x720 \
  -of 30 \
  -t <PATH>/encapp/tests/simple.qcif.json
```

**Important:** For raw input, both input and output resolution and fps must be specified, even though raw buffer encoding doesn't allow scaling (surface encoding does support scaling).


# 4. System and Unit Testing

encapp includes both unit tests and system tests to ensure code quality and functionality.

## 4.1. Running All Tests

Run all tests using pytest:

```bash
$ python3 -m pytest PATH_TO_REPO/encapp/scripts/tests/
```

## 4.2. Test Types

### Unit Tests
* Can be run without a device connected
* Test individual components and functions in isolation
* Fast execution

### System Tests
* **Require a device connected**
* Test end-to-end functionality with actual hardware
* Requirements:
  - H.264 encoder and decoder support
  - Surface texture support

Set the target device:
```bash
$ export ANDROID_SERIAL=XXX
```

## 4.3. Test Configuration

### Automatic Installation

By default, the latest build will be automatically installed on the device before running tests.

### Using Existing Installation

To run tests using the currently installed application without reinstalling:

```bash
$ export ENCAPP_ALWAYS_INSTALL=0
```

This is useful for:
- Faster test iterations during development
- Testing a specific installed version
- Avoiding repeated installations on slow devices

## 4.4. Running Specific Tests

Run tests from a specific file:
```bash
$ python3 -m pytest scripts/tests/test_specific.py
```

Run a specific test function:
```bash
$ python3 -m pytest scripts/tests/test_specific.py::test_function_name
```

Run with verbose output:
```bash
$ python3 -m pytest -v scripts/tests/
```
