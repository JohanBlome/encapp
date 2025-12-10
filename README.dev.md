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

# 5. Release Management

The `scripts/release.sh` script automates the release process for new versions of encapp, including version bumping, building, testing, and git operations.

## 5.1. Usage

```bash
# Show help
./scripts/release.sh --help

# Run tests only (no release)
./scripts/release.sh --dry-run

# Perform a release with version number
./scripts/release.sh 1.29

# Perform a release (will prompt for version)
./scripts/release.sh
```

## 5.2. Release Mode (Default)

The default release mode performs a complete release workflow:

### Steps Performed

1. **Documentation Check** - Verifies documentation has been updated
2. **Version Bump** - Updates version number in `app/build.gradle`
3. **Build APK** - Runs `./gradlew clean assembleDefaultDebug`
4. **Copy to Releases** - Copies APK to `app/releases/` folder
5. **Run Tests** - Executes comprehensive test suite:
   - Python system tests (device required)
   - Android instrumented tests
   - Smoke test (install and launch app)
6. **Git Operations** - Commits changes and optionally pushes to remote

### Example Release Flow

```bash
$ ./scripts/release.sh 1.29

========================================
Encapp Release Script
========================================

Current version: 1.28

Release Summary:
  Current version: 1.28
  New version:     1.29

Proceed with release? (y/n): y

========================================
Checking Documentation
========================================
✓ Documentation check passed

========================================
Building APK
========================================
✓ Build completed successfully

========================================
Running Tests
========================================
✓ Device detected
✓ Emulator serial: emulator-5554
✓ APK installed successfully
✓ All files access permission granted
✓ Python system tests passed
✓ Instrumented tests passed
✓ Smoke test passed

========================================
Git Operations
========================================
✓ Changes committed
✓ Changes pushed to remote

========================================
Release Complete!
========================================
✓ Version 1.29 has been released successfully

Next steps:
  1. Test the APK in releases folder
  2. Create a git tag: git tag v1.29
  3. Push tag: git push --tags
```

## 5.3. Dry-Run Mode

Dry-run mode allows you to run all tests without performing any release operations. This is useful for:

- Pre-release validation
- Testing changes before committing
- Continuous integration testing
- Development testing

### What Dry-Run Does

✅ **Performs:**
- Builds APK (`assembleDefaultDebug`)
- Runs all tests on any connected device (emulator or physical)
- Installs APK with all permissions granted
- Executes Python system tests (except deployment tests)
- Runs instrumented tests
- Smoke test (launch and verify app)

❌ **Skips:**
- Version number changes
- Documentation checks
- Copying APK to releases folder
- Git commits and pushes

### Example Dry-Run

```bash
$ ./scripts/release.sh --dry-run

========================================
Encapp Test Script (Dry-Run Mode)
========================================

Current version: 1.28

⚠ DRY-RUN MODE: Testing only, no release operations will be performed

Proceed with test run? (y/n): y

========================================
Building APK
========================================
✓ Build completed successfully

========================================
Running Tests
========================================
✓ Device detected
✓ Using device: emulator-5554
✓ APK installed successfully
✓ All files access permission granted
Skipping test_encapp_app_deploy tests (using pre-installed app)
✓ Python system tests passed

========================================
Test Run Complete!
========================================
✓ All tests completed

No release operations were performed (dry-run mode)
To perform a release, run without --dry-run flag
```

## 5.4. Device Handling

### Automatic Device Detection

The script intelligently detects and handles devices:

**Release Mode:**
- Prefers emulators
- Offers to start an emulator if none is running
- Will not use physical devices automatically

**Dry-Run Mode:**
- Accepts any connected device (emulator or physical)
- Offers to start an emulator if no device is connected
- Automatically detects and uses available devices

### Multiple Devices

When multiple devices are connected:

```bash
⚠ Multiple devices detected:
  1) emulator-5554
  2) R9WR20E3XYZ
  3) ABC123DEF

Select device (1-3) or set ANDROID_SERIAL:
```

Alternatively, set the device beforehand:
```bash
export ANDROID_SERIAL=emulator-5554
./scripts/release.sh --dry-run
```

### Starting Emulator

If no device is connected, the script offers to start an emulator:

```bash
========================================
Starting Android Emulator
========================================

⚠ Available emulators:
Pixel_5_API_30
Pixel_8_API_36

Start emulator 'Pixel_5_API_30' for testing? (y/n): y
Starting emulator in background...
Waiting for emulator to be detected...
✓ Emulator detected: emulator-5554
Waiting for emulator to boot (this may take 2-3 minutes)...
. [0s].......... [10s].......... [20s].......... [30s]...
✓ Emulator is ready
✓ Emulator serial: emulator-5554
```

The script includes:
- 60-second timeout for emulator detection
- 180-second timeout for boot completion
- Progress indicators showing elapsed time
- Helpful error messages on timeout

## 5.5. Test Execution

### Tests Run in Device Mode

When a device is connected:

1. **APK Installation**
   - Installs freshly built APK with `-r -g` flags
   - Grants all runtime permissions automatically
   - Grants MANAGE_EXTERNAL_STORAGE permission via appops
   - Verifies installation with package manager

2. **Python System Tests**
   - Runs all tests in `scripts/tests/system/`
   - Uses `ENCAPP_APK_PATH` to point to fresh build
   - Sets `ENCAPP_ALWAYS_INSTALL=False` to skip reinstallation
   - In dry-run mode, skips `test_encapp_app_deploy` tests

3. **Android Instrumented Tests**
   - Runs `./gradlew connectedDefaultDebugAndroidTest`
   - Tests Android-specific functionality
   - Requires device with API level support

4. **Smoke Test**
   - Installs and launches the app
   - Verifies app is running via `pidof`
   - Force-stops app after verification

### Tests Run in No-Device Mode

If no device is available and user declines emulator:

```
═══════════════════════════════════════════════════════════
  WARNING: NO DEVICE CONNECTED - RUNNING LIMITED TESTS ONLY
═══════════════════════════════════════════════════════════

The following tests will be SKIPPED:
  ✗ Python system tests (require device)
  ✗ Android instrumented tests (require device)
  ✗ Smoke test (require device)

Only running:
  ✓ Python unit tests (no device needed)
  ✓ Java unit tests (no device needed)

Continue with limited testing? (y/n):
```

Limited tests include:
- Python unit tests (from `scripts/tests/unit/`)
- Java unit tests (`./gradlew testDefaultDebugUnitTest`)

## 5.6. Options and Flags

### Global Options

* `-h, --help` - Show help message and exit
* `--dry-run` - Test mode without release operations

### Arguments

* `VERSION` - New version number (e.g., `1.29`)
  - Optional in release mode (will prompt if omitted)
  - Not used in dry-run mode

### Environment Variables

* `ANDROID_SERIAL` - Device serial to use (auto-detected if only one device)
* `ENCAPP_APK_PATH` - Override APK path for testing (set automatically by script)
* `ENCAPP_ALWAYS_INSTALL` - Set to `False` by script to skip reinstallation in tests

## 5.7. Build Configuration

The script always builds the **Default** flavor to avoid dependency issues:

```bash
./gradlew clean assembleDefaultDebug
```

**Why Default flavor:**
- The `Lcevc` flavor requires additional V-Nova SDK dependencies
- Default flavor works on all development environments
- Ensures reliable builds without external dependencies

**Output location:**
```
app/build/outputs/apk/Default/debug/com.facebook.encapp-v1.28-Default-debug.apk
```

**Releases folder:**
The APK is copied to `app/releases/` with flavor suffix removed:
```
app/releases/com.facebook.encapp-v1.28-debug.apk
```

This allows tests to find the APK using the standard naming convention.

## 5.8. Git Integration

### Automatic Commit

In release mode, the script commits changes:

**Files added:**
- `app/build.gradle` (version change)
- `app/releases/com.facebook.encapp-v1.XX-debug.apk` (force-added, even if in .gitignore)
- `proto/` (if modified)
- `doc/` and `README*.md` (if modified)

**Default commit message:**
```
Release version 1.29

- Updated version to 1.29
- Built and released APK
```

You can customize the message when prompted.

### Push to Remote

After committing, the script asks:
```
Push changes to remote? (y/n):
```

This allows you to review changes before pushing.

### Creating Tags

After a successful release, create and push a git tag:

```bash
git tag v1.29
git push --tags
```

## 5.9. Error Handling

The script includes comprehensive error handling:

### Build Failures

```
✗ Build failed
```
Script exits with error. Check Gradle output for details.

### Installation Failures

```
✗ APK installation failed: [error details]
Continue despite installation failure? (y/n):
```
You can choose to continue or abort.

### Test Failures

```
✗ Python system tests failed
Continue despite test failures? (y/n):
```
Allows continuing despite failures (useful for debugging).

### Timeouts

**Emulator detection timeout (60s):**
```
✗ Emulator failed to start (timeout after 60s)
```

**Boot timeout (180s):**
```
✗ Emulator boot timeout (180s)
⚠ The emulator may still be starting. You can:
  1. Wait longer and check manually: adb -s emulator-5554 shell getprop sys.boot_completed
  2. Kill and restart: adb -s emulator-5554 emu kill

Continue anyway? (y/n):
```

## 5.10. Best Practices

### Before Releasing

1. **Update Documentation**
   - Update README.md with new features
   - Update CHANGELOG or release notes
   - Ensure all docs reflect current functionality

2. **Test Locally**
   - Run `./scripts/release.sh --dry-run` first
   - Verify all tests pass
   - Check for any warnings or issues

3. **Clean Build**
   - Script automatically runs clean build
   - Ensures fresh compilation of all code

### During Release

1. **Review Changes**
   - Check git status before committing
   - Review files being committed
   - Verify commit message is accurate

2. **Test APK**
   - Manually test the generated APK
   - Verify on multiple devices if possible
   - Check key functionality works

### After Release

1. **Create Tag**
   ```bash
   git tag v1.29
   git push --tags
   ```

2. **Update Release Notes**
   - Document new features
   - List bug fixes
   - Note any breaking changes

3. **Distribute APK**
   - APK is in `app/releases/` folder
   - Can be distributed to testers
   - Consider uploading to distribution platform

## 5.11. Troubleshooting

### "No emulators found"

**Problem:** No Android Virtual Devices (AVDs) configured

**Solution:**
```bash
# Create an AVD using Android Studio or command line
avdmanager create avd -n Pixel_5_API_30 -k "system-images;android-30;google_apis;x86_64"
```

### "APK not found in build outputs"

**Problem:** Build succeeded but APK is missing

**Solution:**
```bash
# Check build output directory
ls -la app/build/outputs/apk/Default/debug/

# Manually build
./gradlew assembleDefaultDebug

# Check for build errors
./gradlew clean assembleDefaultDebug --info
```

### "Package not found" after installation

**Problem:** Installation succeeded but app not visible

**Solution:**
```bash
# Wait a few seconds for package manager to register
sleep 2

# Manually verify
adb shell pm list packages | grep encapp

# Check logcat for errors
adb logcat | grep encapp
```

### "Permission denied" errors

**Problem:** App lacks required permissions

**Solution:**
```bash
# Grant storage permission
adb shell appops set com.facebook.encapp MANAGE_EXTERNAL_STORAGE allow

# Grant all runtime permissions
adb shell pm grant com.facebook.encapp android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.facebook.encapp android.permission.WRITE_EXTERNAL_STORAGE
```

### Tests fail intermittently

**Problem:** Flaky tests or device issues

**Solutions:**
- Reboot device/emulator
- Clear app data: `adb shell pm clear com.facebook.encapp`
- Try dry-run mode to skip deployment tests
- Check device has sufficient storage space
- Verify device API level is supported

## 5.12. Integration with CI/CD

The release script can be integrated into CI/CD pipelines:

### GitHub Actions Example

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          distribution: 'adopt'
          java-version: '17'
      
      - name: Run dry-run tests
        run: |
          export ANDROID_SERIAL=${{ secrets.DEVICE_SERIAL }}
          ./scripts/release.sh --dry-run
      
      - name: Upload APK
        uses: actions/upload-artifact@v2
        with:
          name: encapp-apk
          path: app/build/outputs/apk/Default/debug/*.apk
```

### Jenkins Pipeline Example

```groovy
pipeline {
    agent any
    
    environment {
        ANDROID_SERIAL = credentials('android-device-serial')
    }
    
    stages {
        stage('Test') {
            steps {
                sh './scripts/release.sh --dry-run'
            }
        }
        
        stage('Build Release') {
            when {
                branch 'main'
            }
            steps {
                sh './scripts/release.sh ${VERSION}'
            }
        }
    }
    
    post {
        success {
            archiveArtifacts 'app/releases/*.apk'
        }
    }
}
```

## 5.13. Quick Reference

**Common Commands:**

```bash
# Show help
./scripts/release.sh -h

# Test everything without releasing
./scripts/release.sh --dry-run

# Release with specific version
./scripts/release.sh 1.29

# Release and let script prompt for version
./scripts/release.sh

# Set device and dry-run
export ANDROID_SERIAL=emulator-5554
./scripts/release.sh --dry-run
```

**Key Files:**

- `scripts/release.sh` - Main release script
- `app/build.gradle` - Contains version number
- `app/releases/` - APK output directory
- `scripts/tests/system/` - System test directory
- `.encapp_run_history` - Command history log

**Exit Codes:**

- `0` - Success
- `1` - Error (build failure, test failure, user abort, etc.)
