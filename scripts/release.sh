#!/bin/bash

# Encapp Release Script
# This script automates the release process for new versions
# Usage: ./scripts/release.sh [new_version]

set -e  # Exit on error

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
        
        # Wait for emulator to boot
        echo "Waiting for emulator to boot..."
        adb wait-for-device
        
        # Wait for boot to complete
        while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
            sleep 2
            echo -n "."
        done
        echo ""
        
        # Get the serial of the emulator we just started
        STARTED_EMULATOR_SERIAL=$(adb devices | grep "emulator" | head -n 1 | awk '{print $1}')
        
        print_success "Emulator is ready"
        print_success "Emulator serial: $STARTED_EMULATOR_SERIAL"
        return 0
    else
        print_warning "Skipping emulator tests"
        return 1
    fi
}

# Run tests on emulator
run_tests() {
    print_header "Running Tests"
    
    # Check if emulator is available
    local run_emulator_tests=false
    
    if check_emulator; then
        print_success "Emulator detected"
        run_emulator_tests=true
        # Capture the emulator serial for already-running emulator
        STARTED_EMULATOR_SERIAL=$(adb devices | grep "emulator" | head -n 1 | awk '{print $1}')
        print_success "Detected emulator serial: $STARTED_EMULATOR_SERIAL"
    elif start_emulator; then
        run_emulator_tests=true
    fi
    
    if [ "$run_emulator_tests" = true ]; then
        # Use the emulator serial we captured when starting the emulator
        local android_serial="$STARTED_EMULATOR_SERIAL"
        
        if [ -z "$android_serial" ]; then
            print_error "Could not determine emulator serial"
            if ! confirm "Continue despite this issue?"; then
                exit 1
            fi
        else
            print_success "Using emulator: $android_serial"
            export ANDROID_SERIAL="$android_serial"
        fi
        
        # Run Python system tests
        if [ -d "$PROJECT_ROOT/scripts/tests" ]; then
            print_warning "Running Python system tests..."
            cd "$PROJECT_ROOT"
            ANDROID_SERIAL="$android_serial" python3 -m pytest scripts/tests/system/ || {
                print_error "Python system tests failed"
                if ! confirm "Continue despite test failures?"; then
                    exit 1
                fi
            }
            print_success "Python system tests passed"
        fi
          
        # Check if there are instrumented tests
        if [ -d "$PROJECT_ROOT/app/src/androidTest" ]; then
            print_warning "Running instrumented tests on emulator..."
            cd "$PROJECT_ROOT"
            ./gradlew connectedDebugAndroidTest || {
                print_error "Instrumented tests failed"
                if ! confirm "Continue despite test failures?"; then
                    exit 1
                fi
            }
            print_success "Instrumented tests passed"
        else
            print_warning "No androidTest directory found. Skipping instrumented tests."
        fi
        
        # Run basic smoke test - install and launch app
        print_warning "Running smoke test - installing and launching app..."
        local apk_path=$(find "$PROJECT_ROOT/app/build/outputs/apk/debug" -name "*.apk" | head -n 1)
        
        if [ -n "$apk_path" ]; then
            adb install -r "$apk_path" && print_success "APK installed successfully"
            
            # Try to launch the app
            adb shell am start -n com.facebook.encapp/.MainActivity && print_success "App launched successfully"
            
            sleep 3
            
            # Check if app is running
            if adb shell pidof com.facebook.encapp > /dev/null 2>&1; then
                print_success "Smoke test passed - app is running"
                
                # Stop the app
                adb shell am force-stop com.facebook.encapp
            else
                print_warning "App may have crashed. Check logcat for details."
                if ! confirm "Continue despite potential issues?"; then
                    exit 1
                fi
            fi
        fi
    else
        print_warning "Skipping emulator tests (no emulator available)"
          
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
            ./gradlew test || {
                print_error "Java unit tests failed"
                if ! confirm "Continue despite test failures?"; then
                    exit 1
                fi
            }
            print_success "Java unit tests passed"
        else
            print_warning "No Java unit tests found"
        fi
    fi
}

# Build APK
build_apk() {
    print_header "Building APK"
    
    cd "$PROJECT_ROOT"
    
    # Clean build
    print_warning "Running clean build..."
    ./gradlew clean assembleDebug || {
        print_error "Build failed"
        exit 1
    }
    
    print_success "Build completed successfully"
}

# Copy APK to releases folder
copy_to_releases() {
    print_header "Copying APK to Releases"
    
    local apk_path=$(find "$PROJECT_ROOT/app/build/outputs/apk/debug" -name "*.apk" | head -n 1)
    
    if [ -z "$apk_path" ]; then
        print_error "APK not found in build outputs"
        exit 1
    fi
    
    local apk_name=$(basename "$apk_path")
    
    # Create releases directory if it doesn't exist
    mkdir -p "$RELEASES_DIR"
    
    # Copy APK
    cp "$apk_path" "$RELEASES_DIR/"
    print_success "Copied $apk_name to releases folder"
    
    # Show APK info
    echo ""
    echo "APK Details:"
    echo "  Path: $RELEASES_DIR/$apk_name"
    echo "  Size: $(du -h "$RELEASES_DIR/$apk_name" | cut -f1)"
}

# Git operations
git_commit() {
    local version=$1
    
    print_header "Git Operations"
    
    # Check git status
    if ! git diff --quiet || ! git diff --cached --quiet; then
        print_warning "Uncommitted changes detected"
        
        # Show status
        git status --short
        echo ""
        
        if ! confirm "Stage and commit all changes for version $version?"; then
            print_warning "Skipping git commit"
            return
        fi
        
        # Add modified files
        git add app/build.gradle
        git add app/releases/
        
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
        
        # Show what will be committed
        echo ""
        echo "Files to be committed:"
        git diff --cached --name-status
        echo ""
        
        # Ask for commit message
        local default_message="Release version $version

- Updated version to $version
- Built and released APK"
        
        echo "Default commit message:"
        echo "$default_message"
        echo ""
        
        if confirm "Use default commit message?"; then
            git commit -m "$default_message"
        else
            echo "Please enter your commit message (Ctrl+D when done):"
            local custom_message=$(cat)
            git commit -m "$custom_message"
        fi
        
        print_success "Changes committed"
        
        # Ask about pushing
        if confirm "Push changes to remote?"; then
            git push
            print_success "Changes pushed to remote"
        fi
    else
        print_warning "No uncommitted changes to commit"
    fi
}

# Main script
main() {
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
    run_tests
    copy_to_releases
    git_commit "$new_version"
    
    print_header "Release Complete!"
    print_success "Version $new_version has been released successfully"
    echo ""
    echo "Next steps:"
    echo "  1. Test the APK in releases folder"
    echo "  2. Create a git tag: git tag v$new_version"
    echo "  3. Push tag: git push --tags"
}

# Run main function
main "$@"
