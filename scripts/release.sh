#!/bin/bash

# Encapp Release Script
# This script automates the release process for new versions
# Usage: ./scripts/release.sh [new_version]
#        ./scripts/release.sh --dry-run    (test mode: runs tests without release operations)

set -e  # Exit on error

# Global flags
DRY_RUN=false
NO_CLEAN=false
SCRIPT_ARGS=()

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_GRADLE="$PROJECT_ROOT/app/build.gradle"
RELEASES_DIR="$PROJECT_ROOT/app/releases"
DOC_DIR="$PROJECT_ROOT/doc"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check Java version compatibility
check_java_version() {
    local java_version=$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')

    if [ -n "$java_version" ] && [ "$java_version" -ge 25 ] 2>/dev/null; then
        echo ""
        print_error "═══════════════════════════════════════════════════════════"
        print_error "  INCOMPATIBLE JAVA VERSION DETECTED: Java $java_version"
        print_error "═══════════════════════════════════════════════════════════"
        echo ""
        print_warning "Gradle 8.x does not support Java 25 or later."
        print_warning "Please switch to Java 17-21 before running this script."
        echo ""
        echo "Your current JAVA_HOME: ${JAVA_HOME:-<not set>}"
        echo ""
        echo "To switch Java versions:"
        echo "  macOS:   export JAVA_HOME=\$(/usr/libexec/java_home -v 21)"
        echo "  Linux:   update-alternatives --config java"
        echo ""
        exit 1
    fi

    if [ -n "$java_version" ]; then
        print_success "Java version: $java_version (compatible)"
    fi
}

# Helper functions
print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

confirm() {
    while true; do
        read -p "$1 (y/n): " yn
        case $yn in
            [Yy]* ) return 0;;
            [Nn]* ) return 1;;
            * ) echo "Please answer yes or no.";;
        esac
    done
}

# Show help message
show_help() {
    echo -e "${BLUE}Encapp Release Script${NC}"
    echo ""
    echo -e "${GREEN}USAGE:${NC}"
    echo "    ./scripts/release.sh [OPTIONS] [VERSION]"
    echo ""
    echo -e "${GREEN}DESCRIPTION:${NC}"
    echo "    Automates the release process for Encapp, including version bumping,"
    echo "    building, testing, and git operations. Can also be used to run tests"
    echo "    without performing a release."
    echo ""
    echo -e "${GREEN}OPTIONS:${NC}"
    echo "    -h, --help      Show this help message and exit"
    echo "    --dry-run       Test mode: Run all tests on any connected device"
    echo "                    without performing release operations (no version"
    echo "                    bumping, no git commits, no releases folder updates)"
    echo ""
    echo -e "${GREEN}ARGUMENTS:${NC}"
    echo "    VERSION         New version number for release (e.g., 1.29)"
    echo "                    If omitted in release mode, you will be prompted"
    echo ""
    echo -e "${GREEN}EXAMPLES:${NC}"
    echo -e "    ${YELLOW}# Run tests on connected device (no release)${NC}"
    echo "    ./scripts/release.sh --dry-run"
    echo ""
    echo -e "    ${YELLOW}# Perform a release with version 1.29${NC}"
    echo "    ./scripts/release.sh 1.29"
    echo ""
    echo -e "    ${YELLOW}# Perform a release (will prompt for version)${NC}"
    echo "    ./scripts/release.sh"
    echo ""
    echo -e "    ${YELLOW}# Release without clean build (faster, uses cached build)${NC}"
    echo "    ./scripts/release.sh --no-clean 1.29"
    echo ""
    echo -e "${GREEN}MODES:${NC}"
    echo -e "    ${BLUE}Release Mode (default):${NC}"
    echo "        - Checks for documentation updates"
    echo "        - Bumps version number in build.gradle"
    echo "        - Builds APK (assembleDefaultDebug)"
    echo "        - Copies APK to releases folder"
    echo "        - Runs tests on emulator (starts one if needed)"
    echo "        - Commits changes to git"
    echo "        - Prompts to push changes"
    echo ""
    echo -e "    ${BLUE}Dry-Run Mode (--dry-run):${NC}"
    echo "        - Builds APK (assembleDefaultDebug)"
    echo "        - Runs tests on any connected device (emulator or physical)"
    echo "        - No version changes"
    echo "        - No git operations"
    echo "        - No releases folder updates"
    echo "        - Useful for testing before release"
    echo ""
    echo -e "${GREEN}REQUIREMENTS:${NC}"
    echo "    - Android SDK with adb in PATH"
    echo "    - Gradle wrapper in project root"
    echo "    - Python 3 with pytest for system tests"
    echo "    - Java 17-21 (Gradle 8.x does not support Java 25+)"
    echo "    - For release mode: an emulator must be available"
    echo "    - For dry-run mode: any device connected via adb"
    echo ""
    echo -e "${GREEN}ENVIRONMENT VARIABLES:${NC}"
    echo "    ANDROID_SERIAL  Device serial to use (auto-detected if only one device)"
    echo ""
}

# Get current version from build.gradle
get_current_version() {
    grep 'versionName "' "$BUILD_GRADLE" | head -n 1 | sed 's/.*"\(.*\)".*/\1/'
}

# Update version in build.gradle
update_version() {
    local new_version=$1
    # macOS/BSD sed requires different syntax than GNU sed
    sed -i '' 's/versionName ".*"/versionName "'$new_version'"/' "$BUILD_GRADLE"
    print_success "Updated version to $new_version in build.gradle"
}

# Check for documentation updates
check_documentation() {
    print_header "Checking Documentation"

    # Check if any .md files were modified recently (last 7 days) in root and doc folder
    local modified_docs=$(find "$PROJECT_ROOT" -maxdepth 1 -name "*.md" -mtime -7 2>/dev/null)
    local modified_docs_doc=$(find "$DOC_DIR" -name "*.md" -mtime -7 2>/dev/null)

    if [ -n "$modified_docs" ] || [ -n "$modified_docs_doc" ]; then
        print_success "Found recently modified documentation files:"
        [ -n "$modified_docs" ] && echo "$modified_docs"
        [ -n "$modified_docs_doc" ] && echo "$modified_docs_doc"
        echo ""
    else
        print_warning "No documentation files modified in the last 7 days"
    fi

    # List all documentation files for reference
    echo "Documentation files to consider:"
    find "$PROJECT_ROOT" -maxdepth 1 -name "*.md" -type f | sort
    [ -d "$DOC_DIR" ] && find "$DOC_DIR" -name "*.md" -type f | sort
    echo ""

    # Ask user if documentation is complete
    if ! confirm "Have you updated all relevant documentation?"; then
        print_error "Please update documentation before releasing"
        exit 1
    fi

    print_success "Documentation check passed"
}

# Check if emulator is running
check_emulator() {
    if adb devices | grep -q "emulator"; then
        return 0
    else
        return 1
    fi
}

# Check if any device (emulator or physical) is connected
check_any_device() {
    if adb devices | grep -E "device$" | grep -v "List of devices" > /dev/null; then
        return 0
    else
        return 1
    fi
}

# Get device serial for testing
get_device_serial() {
    # Get list of connected devices (exclude offline/unauthorized)
    local devices=$(adb devices | grep -E "device$" | awk '{print $1}')
    local device_count=$(echo "$devices" | grep -v '^$' | wc -l | tr -d ' ')

    if [ "$device_count" -eq 0 ]; then
        return 1
    elif [ "$device_count" -eq 1 ]; then
        echo "$devices"
        return 0
    else
        # Multiple devices - check ANDROID_SERIAL env var
        if [ -n "$ANDROID_SERIAL" ]; then
            echo "$ANDROID_SERIAL"
            return 0
        fi

        # Prompt user to select
        print_warning "Multiple devices detected:"
        local i=1
        while IFS= read -r device; do
            [ -z "$device" ] && continue
            echo "  $i) $device"
            i=$((i + 1))
        done <<< "$devices"

        echo ""
        read -p "Select device (1-$device_count) or set ANDROID_SERIAL: " selection

        if [ -n "$selection" ] && [ "$selection" -ge 1 ] && [ "$selection" -le "$device_count" ]; then
            echo "$devices" | sed -n "${selection}p"
            return 0
        else
            print_error "Invalid selection"
            return 1
        fi
    fi
}

# Start emulator if not running
start_emulator() {
    print_header "Starting Android Emulator"

    # List available emulators
    local emulators=$(emulator -list-avds 2>/dev/null)

    if [ -z "$emulators" ]; then
        print_error "No emulators found. Please create an AVD first."
        print_warning "Skipping emulator tests."
        return 1
    fi

    # Get first available emulator
    local first_emulator=$(echo "$emulators" | head -n 1)

    print_warning "Available emulators:"
    echo "$emulators"
    echo ""

    if confirm "Start emulator '$first_emulator' for testing?"; then
        echo "Starting emulator in background..."
        emulator -avd "$first_emulator" -no-snapshot-load > /dev/null 2>&1 &
        local emulator_pid=$!

        # Wait for device to be detected by adb
        echo "Waiting for emulator to be detected..."
        local wait_count=0
        local max_wait=60  # Wait up to 60 seconds for device detection

        while [ $wait_count -lt $max_wait ]; do
            if adb devices | grep -q "emulator"; then
                break
            fi
            sleep 1
            echo -n "."
            wait_count=$((wait_count + 1))
        done
        echo ""

        if [ $wait_count -ge $max_wait ]; then
            print_error "Emulator failed to start (timeout after ${max_wait}s)"
            kill $emulator_pid 2>/dev/null || true
            return 1
        fi

        # Get the emulator serial
        local emulator_serial=$(adb devices | grep "emulator" | head -n 1 | awk '{print $1}')

        if [ -z "$emulator_serial" ]; then
            print_error "Could not determine emulator serial"
            kill $emulator_pid 2>/dev/null || true
            return 1
        fi

        print_success "Emulator detected: $emulator_serial"

        # Wait for boot to complete (with timeout)
        echo "Waiting for emulator to boot (this may take 2-3 minutes)..."
        wait_count=0
        max_wait=180  # Wait up to 3 minutes for boot

        while [ $wait_count -lt $max_wait ]; do
            local boot_completed=$(adb -s "$emulator_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')

            if [ "$boot_completed" = "1" ]; then
                echo ""
                print_success "Emulator is ready"
                break
            fi

            # Show progress every 10 seconds
            if [ $((wait_count % 10)) -eq 0 ]; then
                echo -n " [${wait_count}s]"
            else
                echo -n "."
            fi

            sleep 2
            wait_count=$((wait_count + 2))
        done

        if [ $wait_count -ge $max_wait ]; then
            echo ""
            print_error "Emulator boot timeout (${max_wait}s)"
            print_warning "The emulator may still be starting. You can:"
            echo "  1. Wait longer and check manually: adb -s $emulator_serial shell getprop sys.boot_completed"
            echo "  2. Kill and restart: adb -s $emulator_serial emu kill"

            if ! confirm "Continue anyway?"; then
                kill $emulator_pid 2>/dev/null || true
                return 1
            fi
        fi

        # Store the emulator serial for later use
        STARTED_EMULATOR_SERIAL="$emulator_serial"

        print_success "Emulator serial: $STARTED_EMULATOR_SERIAL"
        return 0
    else
        print_warning "Skipping emulator tests"
        return 1
    fi
}

# Run tests on emulator or device
run_tests() {
    print_header "Running Tests"

    local run_device_tests=false
    local android_serial=""

    # Different device detection based on mode
    if [ "$DRY_RUN" = true ]; then
        # Dry-run mode: accept any connected device, but offer to start emulator if none found
        if check_any_device; then
            print_success "Device detected"
            android_serial=$(get_device_serial)
            if [ -n "$android_serial" ]; then
                run_device_tests=true
                print_success "Using device: $android_serial"
                export ANDROID_SERIAL="$android_serial"
            else
                print_error "Could not determine device serial"
                if ! confirm "Continue despite this issue?"; then
                    exit 1
                fi
            fi
        else
            print_warning "No devices connected"
            # Offer to start an emulator
            if start_emulator; then
                run_device_tests=true
                android_serial="$STARTED_EMULATOR_SERIAL"
                print_success "Using device: $android_serial"
                export ANDROID_SERIAL="$android_serial"
            else
                print_warning "No emulator started"
            fi
        fi
    else
        # Release mode: accept any connected device, but offer to start emulator if none found
        if check_any_device; then
            print_success "Device detected"
            android_serial=$(get_device_serial)
            if [ -n "$android_serial" ]; then
                run_device_tests=true
                print_success "Using device: $android_serial"
                export ANDROID_SERIAL="$android_serial"
            else
                print_error "Could not determine device serial"
                if ! confirm "Continue despite this issue?"; then
                    exit 1
                fi
            fi
        else
            print_warning "No devices connected"
            # Offer to start an emulator
            if start_emulator; then
                run_device_tests=true
                android_serial="$STARTED_EMULATOR_SERIAL"
                print_success "Using device: $android_serial"
                export ANDROID_SERIAL="$android_serial"
            else
                print_warning "No emulator started"
            fi
        fi
    fi

    if [ "$run_device_tests" = true ]; then

        # Install the freshly built APK before running tests
        print_warning "Installing freshly built APK..."
        local apk_path=$(find "$PROJECT_ROOT/app/build/outputs/apk/Default/debug" -name "*.apk" | head -n 1)

        if [ -n "$apk_path" ]; then
            echo "APK path: $apk_path"
            echo "Installing to device: $android_serial"

            # Install APK and capture output
            local install_output=$(adb -s "$android_serial" install -r -g "$apk_path" 2>&1)
            local install_status=$?

            echo "Install output: $install_output"

            if [ $install_status -eq 0 ]; then
                print_success "APK installed successfully"

                # Small delay to ensure package manager registers the app
                sleep 2

                # Verify installation
                echo "Verifying installation..."
                echo "Running: adb -s $android_serial shell pm list packages | grep facebook"
                adb -s "$android_serial" shell pm list packages | grep facebook || echo "(No facebook packages found)"

                if adb -s "$android_serial" shell pm list packages | grep -q "com.facebook.encapp"; then
                    print_success "Verified: com.facebook.encapp is installed"
                else
                    print_error "Installation verification failed: package not found"
                    echo "All packages:"
                    adb -s "$android_serial" shell pm list packages
                    if ! confirm "Continue despite installation verification failure?"; then
                        exit 1
                    fi
                fi

                # Grant special "All files access" permission (MANAGE_EXTERNAL_STORAGE)
                # This cannot be granted with -g flag, requires appops
                print_warning "Granting 'All files access' permission..."
                adb -s "$android_serial" shell appops set com.facebook.encapp MANAGE_EXTERNAL_STORAGE allow 2>&1
                print_success "All files access permission granted"
            else
                print_error "APK installation failed: $install_output"
                if ! confirm "Continue despite installation failure?"; then
                    exit 1
                fi
            fi
        else
            print_error "APK not found in build outputs"
            if ! confirm "Continue without installing APK?"; then
                exit 1
            fi
        fi

        # Run Python system tests
        if [ -d "$PROJECT_ROOT/scripts/tests" ]; then
            print_warning "Running Python system tests..."
            cd "$PROJECT_ROOT"

            # Set ENCAPP_APK_PATH to point to the freshly built APK
            local apk_path=$(find "$PROJECT_ROOT/app/build/outputs/apk/Default/debug" -name "*.apk" | head -n 1)

            if [ -z "$apk_path" ]; then
                print_error "APK not found in build outputs"
                if ! confirm "Continue without setting ENCAPP_APK_PATH?"; then
                    exit 1
                fi
                apk_path=""
            else
                print_success "Using APK for tests: $apk_path"
            fi

            # In dry-run mode, skip app deployment tests since we already installed the app manually
            if [ "$DRY_RUN" = true ]; then
                echo "Skipping test_encapp_app_deploy tests (using pre-installed app)"
                # Set ENCAPP_ALWAYS_INSTALL=False since we already installed the APK manually
                ANDROID_SERIAL="$android_serial" ENCAPP_ALWAYS_INSTALL=False ENCAPP_APK_PATH="$apk_path" python3 -m pytest -k "not test_encapp_app_deploy" scripts/tests/system/ || {
                    print_error "Python system tests failed"
                    if ! confirm "Continue despite test failures?"; then
                        exit 1
                    fi
                }
            else
                # Release mode: Let tests handle installation themselves
                # Explicitly set ENCAPP_ALWAYS_INSTALL=True to ensure tests install the app
                echo "Running ALL system tests (including deployment tests)"
                ANDROID_SERIAL="$android_serial" ENCAPP_ALWAYS_INSTALL=True ENCAPP_APK_PATH="$apk_path" python3 -m pytest scripts/tests/system/ || {
                    print_error "Python system tests failed"
                    if ! confirm "Continue despite test failures?"; then
                        exit 1
                    fi
                }
            fi
            print_success "Python system tests passed"
        fi

    else
        print_warning "Skipping device tests (no device available)"

        echo ""
        print_error "═══════════════════════════════════════════════════════════"
        print_error "  WARNING: NO DEVICE CONNECTED - RUNNING LIMITED TESTS ONLY"
        print_error "═══════════════════════════════════════════════════════════"
        echo ""
        print_warning "The following tests will be SKIPPED:"
        echo "  ✗ Python system tests (require device)"
        echo "  ✗ Android instrumented tests (require device)"
        echo "  ✗ Smoke test (require device)"
        echo ""
        print_warning "Only running:"
        echo "  ✓ Python unit tests (no device needed)"
        echo "  ✓ Java unit tests (no device needed)"
        echo ""

        if ! confirm "Continue with limited testing?"; then
            print_error "Testing cancelled. Please connect a device and try again."
            exit 1
        fi
        echo ""

        # Run Python unit tests even without emulator
        if [ -d "$PROJECT_ROOT/scripts/tests/unit" ]; then
            print_warning "Running Python unit tests..."
            cd "$PROJECT_ROOT/scripts/tests/unit"
            python3 -m pytest . || {
                print_error "Python unit tests failed"
                if ! confirm "Continue despite test failures?"; then
                    exit 1
                fi
            }
            print_success "Python unit tests passed"
        fi

        # Run Java unit tests at minimum
        if [ -d "$PROJECT_ROOT/app/src/test" ]; then
            print_warning "Running Java unit tests..."
            cd "$PROJECT_ROOT"
            # Use testDefaultDebugUnitTest to avoid building Lcevc flavor which requires additional dependencies
            ./gradlew testDefaultDebugUnitTest || {
                print_error "Java unit tests failed"
                if ! confirm "Continue despite test failures?"; then
                    exit 1
                fi
            }
            print_success "Java unit tests passed"
        else
            print_warning "No Java unit tests found"
        fi

        echo ""
        print_error "═══════════════════════════════════════════════════════════"
        print_error "  REMINDER: Device tests were SKIPPED - limited test coverage"
        print_error "═══════════════════════════════════════════════════════════"
        echo ""
        print_warning "To run full tests, connect a device and run again."
    fi
}

# Build APK
build_apk() {
    print_header "Building APK"

    cd "$PROJECT_ROOT"

    # Build (with optional clean)
    if [ "$NO_CLEAN" = true ]; then
        print_warning "Running incremental build (--no-clean)..."
        ./gradlew assembleDefaultDebug || {
            print_error "Build failed"
            exit 1
        }
    else
        print_warning "Running clean build..."
        # Always use assembleDefaultDebug (Lcevc flavor requires additional dependencies)
        ./gradlew clean assembleDefaultDebug || {
            print_error "Build failed"
            exit 1
        }
    fi

    print_success "Build completed successfully"
}

# Copy APK to releases folder
copy_to_releases() {
    print_header "Copying APK to Releases"

    local apk_path=$(find "$PROJECT_ROOT/app/build/outputs/apk/Default/debug" -name "*.apk" | head -n 1)

    if [ -z "$apk_path" ]; then
        print_error "APK not found in build outputs"
        exit 1
    fi

    local apk_name=$(basename "$apk_path")
    # Remove the "-Default" flavor suffix from the name so tests can find it
    # e.g., com.facebook.encapp-v1.28-Default-debug.apk -> com.facebook.encapp-v1.28-debug.apk
    local release_apk_name=$(echo "$apk_name" | sed 's/-Default//')

    # Create releases directory if it doesn't exist
    mkdir -p "$RELEASES_DIR"

    # Copy APK with the renamed filename (without flavor)
    cp "$apk_path" "$RELEASES_DIR/$release_apk_name"
    print_success "Copied $apk_name as $release_apk_name to releases folder"

    # Show APK info
    echo ""
    echo "APK Details:"
    echo "  Original: $apk_name"
    echo "  Released: $release_apk_name"
    echo "  Path: $RELEASES_DIR/$release_apk_name"
    echo "  Size: $(du -h "$RELEASES_DIR/$release_apk_name" | cut -f1)"
}

# Git operations
git_commit() {
    local version=$1

    print_header "Git Operations"

    # Stage modified files first
    print_warning "Staging files..."

    # Add modified build.gradle
    git add app/build.gradle
    print_success "Staged app/build.gradle"

    # Force add the APK file (in case it's in .gitignore)
    local apk_file="$RELEASES_DIR/com.facebook.encapp-v${version}-debug.apk"
    if [ -f "$apk_file" ]; then
        git add -f "$apk_file"
        print_success "Force-added APK: com.facebook.encapp-v${version}-debug.apk"
    else
        print_error "APK file not found: $apk_file"
        if ! confirm "Continue without adding APK?"; then
            exit 1
        fi
    fi

    # Add releases directory (for any other changes)
    git add app/releases/ 2>/dev/null || true

    # Check if proto files changed
    if ! git diff --quiet proto/ 2>/dev/null; then
        git add proto/
        print_success "Added proto files to commit"
    fi

    # Check if documentation changed
    if ! git diff --quiet doc/ README*.md 2>/dev/null; then
        git add doc/ README*.md 2>/dev/null || true
        print_success "Added documentation to commit"
    fi

    # Check if there are staged changes
    if git diff --cached --quiet; then
        print_warning "No staged changes to commit"
        return
    fi

    # Show what will be committed
    echo ""
    echo "Files to be committed:"
    git diff --cached --name-status
    echo ""

    # Check if the last commit is already for this version (to offer amend)
    local last_commit_msg=$(git log -1 --pretty=%B 2>/dev/null || echo "")
    local should_amend=false

    if echo "$last_commit_msg" | grep -q "Release version $version"; then
        print_warning "Last commit appears to be for version $version already"
        echo ""
        echo "Last commit message:"
        echo "$last_commit_msg"
        echo ""

        if confirm "Amend the existing commit instead of creating a new one?"; then
            should_amend=true
        fi
    fi

    if ! confirm "Commit staged changes for version $version?"; then
        print_warning "Skipping git commit"
        return
    fi

    # Prepare commit message
    local default_message="Release version $version

- Updated version to $version
- Built and released APK"

    if [ "$should_amend" = true ]; then
        # Amend existing commit
        echo "Amending existing commit..."
        echo ""

        if confirm "Keep the existing commit message?"; then
            git commit --amend --no-edit
            print_success "Commit amended (message unchanged)"
        else
            echo "Default commit message:"
            echo "$default_message"
            echo ""

            if confirm "Use default commit message?"; then
                git commit --amend -m "$default_message"
                print_success "Commit amended with new message"
            else
                echo "Please enter your commit message (Ctrl+D when done):"
                local custom_message=$(cat)
                git commit --amend -m "$custom_message"
                print_success "Commit amended with custom message"
            fi
        fi
    else
        # Create new commit
        echo "Default commit message:"
        echo "$default_message"
        echo ""

        if confirm "Use default commit message?"; then
            git commit -m "$default_message"
            print_success "Changes committed"
        else
            echo "Please enter your commit message (Ctrl+D when done):"
            local custom_message=$(cat)
            git commit -m "$custom_message"
            print_success "Changes committed"
        fi
    fi

    # Ask about pushing
    if confirm "Push changes to remote?"; then
        if [ "$should_amend" = true ]; then
            print_warning "Amended commit will require force push"
            if confirm "Force push to remote?"; then
                git push --force-with-lease
                print_success "Changes force-pushed to remote"
            fi
        else
            git push
            print_success "Changes pushed to remote"
        fi
    fi
}

# Main script
main() {
    # Check Java version compatibility first
    check_java_version

    if [ "$DRY_RUN" = true ]; then
        # Dry-run mode: test only, no release operations
        print_header "Encapp Test Script (Dry-Run Mode)"

        local current_version=$(get_current_version)
        echo "Current version: $current_version"
        echo ""
        print_warning "DRY-RUN MODE: Testing only, no release operations will be performed"
        echo ""

        if ! confirm "Proceed with test run?"; then
            print_warning "Test run cancelled"
            exit 0
        fi

        # Execute test steps only
        build_apk
        # Don't copy to releases in dry-run mode - we want to preserve any existing release
        run_tests

        print_header "Test Run Complete!"
        print_success "All tests completed"
        echo ""
        echo "No release operations were performed (dry-run mode)"
        echo "To perform a release, run without --dry-run flag"
    else
        # Normal release mode
        print_header "Encapp Release Script"

        # Get current version
        local current_version=$(get_current_version)
        echo "Current version: $current_version"

        # Get new version
        local new_version=$1

        if [ -z "$new_version" ]; then
            # Auto-increment minor version
            local major=$(echo "$current_version" | cut -d. -f1)
            local minor=$(echo "$current_version" | cut -d. -f2)
            local suggested_version="$major.$((minor + 1))"

            read -p "Enter new version [$suggested_version]: " new_version
            new_version=${new_version:-$suggested_version}
        fi

        echo ""
        echo "Release Summary:"
        echo "  Current version: $current_version"
        echo "  New version:     $new_version"
        echo ""

        if ! confirm "Proceed with release?"; then
            print_warning "Release cancelled"
            exit 0
        fi

        # Execute release steps
        check_documentation
        update_version "$new_version"
        build_apk
        copy_to_releases  # Copy APK to releases BEFORE running tests (tests expect it there)
        run_tests
        git_commit "$new_version"

        print_header "Release Complete!"
        print_success "Version $new_version has been released successfully"
        echo ""
        echo "Next steps:"
        echo "  1. Test the APK in releases folder"
        echo "  2. Create a git tag: git tag v$new_version"
        echo "  3. Push tag: git push --tags"
    fi
}

# Parse command line arguments
for arg in "$@"; do
    case "$arg" in
        -h|--help)
            show_help
            exit 0
            ;;
        --dry-run)
            DRY_RUN=true
            ;;
        --no-clean)
            NO_CLEAN=true
            ;;
        *)
            SCRIPT_ARGS+=("$arg")
            ;;
    esac
done

# Run main function
main "${SCRIPT_ARGS[@]}"
