#!/bin/bash
#
# encapp_run.sh - Lightweight encapp test runner
#
# Runs encapp codec tests on an Android device using only bash, adb, jq, and ffmpeg.
# No Python dependency required.
#
# Usage: encapp_run.sh [OPTIONS] <test.pbtxt | test_dir>
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Constants
PACKAGE_NAME="com.facebook.encapp"
ACTIVITY="${PACKAGE_NAME}/.MainActivity"
DEVICE_WORKDIR=""  # Auto-detected from app
POLL_INTERVAL=2
MAX_WAIT=600  # 10 minutes timeout

# Session temp directory (cleaned up on exit)
SESSION_TMPDIR=""
_cleanup_session() {
    [ -n "$SESSION_TMPDIR" ] && rm -rf "$SESSION_TMPDIR"
}
_ensure_tmpdir() {
    if [ -z "$SESSION_TMPDIR" ]; then
        SESSION_TMPDIR=$(mktemp -d "${TMPDIR:-/tmp}/encapp_run.XXXXXX")
        trap _cleanup_session EXIT
    fi
}

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

# Defaults
SERIAL=""
INPUT_PATH=""
OUTPUT_DIR=""
CODEC_OVERRIDE=""
MIME_TYPE="video/hevc"
HW_ONLY=true
BITRATE_OVERRIDE=""
DRY_RUN=false
DEBUG=false

# Results tracking
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0
declare -a FAILED_TESTS=()
declare -a FAILED_ERRORS=()

# --- Output helpers ---

log_info()    { echo -e "${BLUE}[INFO]${NC} $*" >&2; }
log_success() { echo -e "${GREEN}[PASS]${NC} $*" >&2; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $*" >&2; }
log_error()   { echo -e "${RED}[FAIL]${NC} $*" >&2; }
log_debug()   { $DEBUG && echo -e "[DEBUG] $*" >&2 || true; }

die() { log_error "$*"; exit 1; }

# --- ADB wrapper ---

adb_cmd() {
    if [ -n "$SERIAL" ]; then
        adb -s "$SERIAL" "$@"
    else
        adb "$@"
    fi
}

# --- Prerequisites ---

check_prerequisites() {
    local missing=()
    command -v adb   >/dev/null 2>&1 || missing+=("adb")
    command -v jq    >/dev/null 2>&1 || missing+=("jq")
    command -v ffmpeg >/dev/null 2>&1 || missing+=("ffmpeg")
    command -v ffprobe >/dev/null 2>&1 || missing+=("ffprobe")

    if [ ${#missing[@]} -gt 0 ]; then
        die "Missing required tools: ${missing[*]}"
    fi
    log_debug "All prerequisites found"
}

check_device() {
    if ! adb_cmd get-state >/dev/null 2>&1; then
        die "No device connected${SERIAL:+ (serial: $SERIAL)}"
    fi

    if ! adb_cmd shell pm list packages 2>/dev/null | grep -q "$PACKAGE_NAME"; then
        # Try to install from releases
        local apk
        apk=$(find "$PROJECT_ROOT/app/releases" -name "*.apk" 2>/dev/null | sort -V | tail -1)
        if [ -n "$apk" ]; then
            log_info "App not installed — installing $(basename "$apk")..."
            if adb_cmd install -r "$apk" >/dev/null 2>&1; then
                log_success "Installed $(basename "$apk")"
            else
                die "Failed to install APK: $apk"
            fi
        else
            die "encapp app not installed and no APK found in app/releases/"
        fi
    fi

    # Grant required permissions
    ensure_permissions

    log_debug "Device connected and app installed"
}

ensure_permissions() {
    # Grant MANAGE_EXTERNAL_STORAGE
    if ! adb_cmd shell appops get "$PACKAGE_NAME" MANAGE_EXTERNAL_STORAGE 2>/dev/null | grep -q "allow"; then
        log_info "Granting storage permission..."
        adb_cmd shell appops set --uid "$PACKAGE_NAME" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
    fi

    # Grant CAMERA permission
    adb_cmd shell pm grant "$PACKAGE_NAME" android.permission.CAMERA 2>/dev/null || true

    log_debug "Permissions granted"
}

# --- Device workdir detection ---

detect_workdir() {
    if [ -n "$DEVICE_WORKDIR" ]; then
        return
    fi

    # Use the codec list fetch as a write-probe for the workdir.
    # Always fetch fresh (ignore cache) so we actually test the path.
    DEVICE_WORKDIR="/sdcard"
    log_debug "Probing workdir via codec list fetch..."
    fetch_codec_list --no-cache >/dev/null

    # If fetch_codec_list succeeded, DEVICE_WORKDIR is now set correctly
    # (either /sdcard or the app-private fallback).
}

# Returns true if workdir is the app's private directory (needs run-as)
is_private_workdir() {
    [[ "$DEVICE_WORKDIR" == /data/* ]]
}

# Push a local file to the device workdir, handling private dirs via run-as
device_push() {
    local local_file="$1"
    local dest_dir="${2:-$DEVICE_WORKDIR}"
    local basename_f
    basename_f=$(basename "$local_file")

    if is_private_workdir; then
        # Push to /sdcard/ first, then move via run-as
        adb_cmd push "$local_file" "/sdcard/${basename_f}" >/dev/null 2>&1
        adb_cmd shell "run-as $PACKAGE_NAME sh -c 'cp /sdcard/${basename_f} ${dest_dir}/${basename_f}'" 2>/dev/null
        adb_cmd shell "rm -f /sdcard/${basename_f}" 2>/dev/null || true
    else
        adb_cmd push "$local_file" "${dest_dir}/" >/dev/null 2>&1
    fi
}

# Pull a file from the device workdir to a local directory
device_pull() {
    local remote_file="$1"
    local local_dir="$2"

    # If it's a relative name, prepend the workdir
    if [[ "$remote_file" != /* ]]; then
        remote_file="${DEVICE_WORKDIR}/${remote_file}"
    fi

    if is_private_workdir; then
        local basename_f
        basename_f=$(basename "$remote_file")
        # Copy via run-as to /sdcard/, then pull
        adb_cmd shell "run-as $PACKAGE_NAME sh -c 'cat ${remote_file}' > /sdcard/_encapp_pull_${basename_f}" 2>/dev/null \
            || adb_cmd shell "run-as $PACKAGE_NAME cp ${remote_file} /sdcard/_encapp_pull_${basename_f}" 2>/dev/null
        adb_cmd pull "/sdcard/_encapp_pull_${basename_f}" "${local_dir}/${basename_f}" >/dev/null 2>&1
        adb_cmd shell "rm -f /sdcard/_encapp_pull_${basename_f}" 2>/dev/null || true
    else
        adb_cmd pull "$remote_file" "${local_dir}/" >/dev/null 2>&1
    fi
}

# Remove a file from the device workdir
device_rm() {
    local remote_file="$1"
    if [[ "$remote_file" != /* ]]; then
        remote_file="${DEVICE_WORKDIR}/${remote_file}"
    fi

    if is_private_workdir; then
        adb_cmd shell "run-as $PACKAGE_NAME rm -f ${remote_file}" 2>/dev/null || true
    else
        adb_cmd shell "rm -f ${remote_file}" 2>/dev/null || true
    fi
}

# List files in the device workdir matching a pattern
device_ls() {
    local pattern="${1:-*}"
    if is_private_workdir; then
        adb_cmd shell "run-as $PACKAGE_NAME sh -c 'ls ${DEVICE_WORKDIR}/${pattern} 2>/dev/null'" 2>/dev/null | tr -d '\r'
    else
        adb_cmd shell "ls ${DEVICE_WORKDIR}/${pattern} 2>/dev/null" 2>/dev/null | tr -d '\r'
    fi
}

# --- Device info ---

get_device_model() {
    adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' | tr ' ' '_'
}

auto_detect_serial() {
    if [ -n "$SERIAL" ]; then
        return
    fi
    local devices
    devices=$(adb devices 2>/dev/null | grep -w "device" | grep -v "List" | awk '{print $1}')
    local count
    count=$(echo "$devices" | grep -c . || true)

    if [ "$count" -eq 0 ]; then
        die "No devices connected"
    elif [ "$count" -eq 1 ]; then
        SERIAL="$devices"
        log_debug "Auto-detected device: $SERIAL"
    else
        die "Multiple devices connected. Use -s to specify serial:\n$devices"
    fi
}

# --- Codec listing ---

fetch_codec_list() {
    local no_cache=false
    if [ "${1:-}" = "--no-cache" ]; then
        no_cache=true
    fi

    _ensure_tmpdir
    local model
    model=$(get_device_model)
    local cache_file="${SESSION_TMPDIR}/codecs_${model}.txt"

    # Use cached file within this session (unless --no-cache)
    if ! $no_cache && [ -f "$cache_file" ] && [ -s "$cache_file" ]; then
        log_debug "Using cached codec list: $cache_file"
        echo "$cache_file"
        return
    fi

    log_info "Fetching codec list from device..."

    # Try to fetch codec list — this also validates that DEVICE_WORKDIR is writable.
    # If it fails with /sdcard/, retry with the app-private directory.
    if ! _fetch_codec_list_once "$cache_file"; then
        if [ "$DEVICE_WORKDIR" = "/sdcard" ]; then
            log_warn "/sdcard/ not writable, trying app-private directory..."
            DEVICE_WORKDIR="/data/data/${PACKAGE_NAME}/files"
            if ! _fetch_codec_list_once "$cache_file"; then
                die "Failed to fetch codec list from device (tried /sdcard/ and $DEVICE_WORKDIR)"
            fi
        else
            die "Failed to fetch codec list from device"
        fi
    fi

    log_info "Device workdir: $DEVICE_WORKDIR"
    log_info "Codec list saved to: $cache_file"
    echo "$cache_file"
}

# Internal helper: launch app, wait, pull codecs.txt. Returns 1 on failure.
_fetch_codec_list_once() {
    local cache_file="$1"

    # Force stop to ensure clean state
    adb_cmd shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
    sleep 1

    # Clear logcat
    adb_cmd logcat -c 2>/dev/null || true

    # Launch app to list codecs using current DEVICE_WORKDIR
    adb_cmd shell am start \
        -e workdir "$DEVICE_WORKDIR" \
        -e ui_hold_sec 3 \
        -e list_codecs a \
        "$ACTIVITY" >/dev/null 2>&1

    # Wait for app to exit
    wait_for_app_exit

    # Check if codecs.txt exists on the device at DEVICE_WORKDIR
    if ! device_ls "codecs.txt" 2>/dev/null | grep -q "codecs.txt"; then
        log_debug "No codecs.txt found at $DEVICE_WORKDIR"
        return 1
    fi

    # Pull codecs.txt
    local pull_dir
    pull_dir=$(dirname "$cache_file")
    device_pull "codecs.txt" "$pull_dir"
    # Rename to cache filename if needed
    if [ "$pull_dir/codecs.txt" != "$cache_file" ]; then
        mv "$pull_dir/codecs.txt" "$cache_file" 2>/dev/null || true
    fi

    # Verify we got a valid file locally
    if [ -f "$cache_file" ] && [ -s "$cache_file" ]; then
        # Clean up codecs.txt from device
        device_rm "codecs.txt"
        return 0
    fi
    return 1
}

# --- Codec selection ---

find_codec() {
    local codec_file="$1"
    local mime="$2"
    local hw_only="$3"

    local jq_filter
    if $hw_only; then
        jq_filter='.encoders[] | select(.is_hardware_accelerated == true and .media_type.mime_type == "'"$mime"'") | .name'
    else
        jq_filter='.encoders[] | select(.is_encoder == true and .media_type.mime_type == "'"$mime"'") | .name'
    fi

    local codec
    codec=$(jq -r "$jq_filter" "$codec_file" 2>/dev/null | head -1)

    if [ -z "$codec" ] || [ "$codec" = "null" ]; then
        return 1
    fi

    echo "$codec"
}

get_supported_color_formats() {
    local codec_file="$1"
    local codec_name="$2"

    jq -r '.encoders[] | select(.name == "'"$codec_name"'") | .media_type.color_formats[]?.name // empty' "$codec_file" 2>/dev/null
}

get_codec_mime() {
    local codec_file="$1"
    local codec_name="$2"

    jq -r '.encoders[] | select(.name == "'"$codec_name"'") | .media_type.mime_type' "$codec_file" 2>/dev/null
}

# Map Android color format names to ffmpeg pixel formats
android_to_ffmpeg_pixfmt() {
    local color_format="$1"
    case "$color_format" in
        COLOR_FormatYUV420SemiPlanar) echo "nv12" ;;
        COLOR_FormatYUV420Planar)     echo "yuv420p" ;;
        COLOR_FormatYUV420PackedSemiPlanar) echo "nv12" ;;
        COLOR_FormatYUV420PackedPlanar)     echo "yuv420p" ;;
        HAL_YCBCR_P010)              echo "p010le" ;;
        COLOR_FormatYUV420Flexible)  echo "nv12" ;;  # flexible maps to nv12 typically
        *)                           echo "" ;;
    esac
}

select_best_pixfmt() {
    local codec_file="$1"
    local codec_name="$2"

    local formats
    formats=$(get_supported_color_formats "$codec_file" "$codec_name")

    # Prefer nv12, then yuv420p
    if echo "$formats" | grep -q "COLOR_FormatYUV420SemiPlanar"; then
        echo "nv12"
        return
    fi
    if echo "$formats" | grep -q "COLOR_FormatYUV420Planar"; then
        echo "yuv420p"
        return
    fi
    # Default
    echo "nv12"
}

# --- Input preparation ---

is_raw_video() {
    local filepath="$1"
    local ext="${filepath##*.}"
    case "$ext" in
        yuv|y4m|raw) return 0 ;;
        *) return 1 ;;
    esac
}

get_video_info() {
    local filepath="$1"
    ffprobe -v quiet -print_format json -show_format -show_streams "$filepath" 2>/dev/null
}

prepare_input() {
    local input_file="$1"
    local test_file="$2"
    local pixfmt="$3"
    local work_dir="$4"

    # Check if test uses surface mode (device_decode + surface = transcode on device)
    local uses_surface=false
    if grep -q 'surface:[[:space:]]*true' "$test_file" 2>/dev/null; then
        uses_surface=true
    fi
    local uses_device_decode=false
    if grep -q 'device_decode:[[:space:]]*true' "$test_file" 2>/dev/null; then
        uses_device_decode=true
    fi

    # If test uses surface+device_decode, the device will decode — push the encoded file as-is
    if $uses_surface && $uses_device_decode; then
        log_debug "Test uses surface+device_decode, pushing encoded file as-is"
        echo "$input_file"
        return
    fi

    # If input is already raw, use as-is
    if is_raw_video "$input_file"; then
        log_debug "Input is already raw video"
        echo "$input_file"
        return
    fi

    # If test uses surface mode (without device_decode), still push encoded file
    if $uses_surface; then
        log_debug "Test uses surface mode, pushing encoded file as-is"
        echo "$input_file"
        return
    fi

    # Need to decode to raw — buffer mode with encoded input
    local basename
    basename=$(basename "$input_file")
    local raw_file="${work_dir}/${basename%.*}.${pixfmt}.yuv"

    # Get resolution from input
    local width height
    width=$(ffprobe -v quiet -select_streams v:0 -show_entries stream=width -of csv=p=0 "$input_file" 2>/dev/null)
    height=$(ffprobe -v quiet -select_streams v:0 -show_entries stream=height -of csv=p=0 "$input_file" 2>/dev/null)

    if [ -z "$width" ] || [ -z "$height" ]; then
        log_warn "Could not determine input resolution, skipping decode"
        echo "$input_file"
        return
    fi

    if [ ! -f "$raw_file" ]; then
        log_info "Decoding input to raw $pixfmt: $(basename "$input_file") (${width}x${height})"
        ffmpeg -y -i "$input_file" -pix_fmt "$pixfmt" -f rawvideo "$raw_file" 2>/dev/null

        if [ ! -f "$raw_file" ]; then
            die "Failed to decode input file: $input_file"
        fi
    else
        log_debug "Raw file already exists: $raw_file"
    fi

    # Output: file_path resolution pixfmt (space-separated, caller splits)
    echo "${raw_file} ${width}x${height} ${pixfmt}"
}

# --- Test config patching ---

patch_test_config() {
    local test_file="$1"
    local work_dir="$2"
    local codec_name="${3:-}"
    local input_filepath="${4:-}"
    local bitrate="${5:-}"
    local mime_type="${6:-}"
    local resolution="${7:-}"
    local pix_fmt="${8:-}"

    local patched_file="${work_dir}/$(basename "$test_file")"

    # If source and destination are the same file, make a temp copy first
    if [ "$(cd "$(dirname "$test_file")" && pwd)/$(basename "$test_file")" = \
         "$(cd "$(dirname "$patched_file")" && pwd)/$(basename "$patched_file")" ]; then
        local tmp_copy="${patched_file}.orig"
        cp "$test_file" "$tmp_copy"
        mv "$tmp_copy" "$patched_file"
    else
        cp "$test_file" "$patched_file"
    fi

    # Patch codec name
    if [ -n "$codec_name" ]; then
        sed -i.bak 's|codec:[[:space:]]*"[^"]*"|codec: "'"$codec_name"'"|g' "$patched_file"
        rm -f "${patched_file}.bak"
        log_debug "Patched codec to: $codec_name"
    fi

    # Patch mime type (must match codec)
    if [ -n "$mime_type" ]; then
        if grep -q 'mime:' "$patched_file" 2>/dev/null; then
            sed -i.bak 's|mime:[[:space:]]*"[^"]*"|mime: "'"$mime_type"'"|g' "$patched_file"
            rm -f "${patched_file}.bak"
            log_debug "Patched mime to: $mime_type"
        fi
    fi

    # Patch input filepath — use device path
    if [ -n "$input_filepath" ]; then
        local device_path="${DEVICE_WORKDIR}/$(basename "$input_filepath")"
        sed -i.bak 's|filepath:[[:space:]]*"[^"]*"|filepath: "'"$device_path"'"|' "$patched_file"
        rm -f "${patched_file}.bak"
        log_debug "Patched filepath to: $device_path"
    fi

    # Patch bitrate
    if [ -n "$bitrate" ]; then
        sed -i.bak 's|bitrate:[[:space:]]*"[^"]*"|bitrate: "'"$bitrate"'"|g' "$patched_file"
        rm -f "${patched_file}.bak"
        log_debug "Patched bitrate to: $bitrate"
    fi

    # Patch input resolution
    if [ -n "$resolution" ]; then
        if grep -q 'resolution:' "$patched_file" 2>/dev/null; then
            sed -i.bak 's|resolution:[[:space:]]*"[^"]*"|resolution: "'"$resolution"'"|' "$patched_file"
            rm -f "${patched_file}.bak"
            log_debug "Patched resolution to: $resolution"
        fi
    fi

    # Patch pixel format
    if [ -n "$pix_fmt" ]; then
        if grep -q 'pix_fmt:' "$patched_file" 2>/dev/null; then
            sed -i.bak 's|pix_fmt:[[:space:]]*[a-z0-9_]*|pix_fmt: '"$pix_fmt"'|' "$patched_file"
            rm -f "${patched_file}.bak"
            log_debug "Patched pix_fmt to: $pix_fmt"
        fi
    fi

    echo "$patched_file"
}

# --- App lifecycle ---

wait_for_app_exit() {
    local elapsed=0
    log_debug "Waiting for app to exit..."

    # Wait a moment for the app to start
    sleep 2

    while [ $elapsed -lt $MAX_WAIT ]; do
        if ! adb_cmd shell pidof "$PACKAGE_NAME" >/dev/null 2>&1; then
            log_debug "App exited after ${elapsed}s"
            return 0
        fi
        sleep $POLL_INTERVAL
        elapsed=$((elapsed + POLL_INTERVAL))
    done

    log_error "App did not exit within ${MAX_WAIT}s — force stopping"
    adb_cmd shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
    return 1
}

clear_device_files() {
    log_debug "Clearing encapp output files from device"
    local files
    files=$(device_ls "encapp_*" || true)
    if [ -n "$files" ]; then
        while IFS= read -r f; do
            [ -n "$f" ] && device_rm "$(basename "$f")"
        done <<< "$files"
    fi
}

# --- Test execution ---

run_single_test() {
    local test_file="$1"
    local input_file="${2:-}"
    local result_dir="$3"
    local codec_name="${4:-}"
    local pixfmt="${5:-nv12}"
    local mime_type="${6:-}"

    local test_name
    test_name=$(basename "$test_file" .pbtxt)
    local test_result_dir="${result_dir}/${test_name}"
    mkdir -p "$test_result_dir"

    TESTS_RUN=$((TESTS_RUN + 1))

    local label="$test_name"
    [ -n "$input_file" ] && label="${test_name} + $(basename "$input_file")"
    echo -e "\n${BOLD}--- Test ${TESTS_RUN}: ${label} ---${NC}"

    # Check if test uses fake_input or camera (no input file needed)
    local needs_input=true
    if grep -q 'filepath:[[:space:]]*"fake_input"' "$test_file" 2>/dev/null; then
        needs_input=false
        log_debug "Test uses fake_input, no input file needed"
    fi
    if grep -q 'filepath:[[:space:]]*"camera"' "$test_file" 2>/dev/null; then
        needs_input=false
        log_debug "Test uses camera, no input file needed"
    fi
    if grep -q 'filepath:[[:space:]]*"\[generate\]"' "$test_file" 2>/dev/null; then
        needs_input=false
        log_debug "Test uses generated input, no input file needed"
    fi

    if $needs_input && [ -z "$input_file" ]; then
        log_warn "Test requires input file but none provided — skipping"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        FAILED_TESTS+=("$label")
        FAILED_ERRORS+=("No input file provided")
        return 1
    fi

    # Prepare input if needed
    local prepared_input=""
    local input_resolution=""
    local input_pixfmt=""
    if $needs_input && [ -n "$input_file" ]; then
        local prep_result
        prep_result=$(prepare_input "$input_file" "$test_file" "$pixfmt" "$test_result_dir")
        # prepare_input returns "filepath [resolution pixfmt]" when it decodes
        prepared_input=$(echo "$prep_result" | awk '{print $1}')
        input_resolution=$(echo "$prep_result" | awk '{print $2}')
        input_pixfmt=$(echo "$prep_result" | awk '{print $3}')
    fi

    # Patch the test config
    local patched_test
    patched_test=$(patch_test_config "$test_file" "$test_result_dir" "$codec_name" "$prepared_input" "$BITRATE_OVERRIDE" "$mime_type" "$input_resolution" "$input_pixfmt")

    if $DRY_RUN; then
        log_info "[DRY RUN] Would run test:"
        echo "  Test config: $patched_test"
        echo "  Codec: ${codec_name:-<from config>}"
        [ -n "$prepared_input" ] && echo "  Input: $prepared_input"
        echo "  Results dir: $test_result_dir"
        cat "$patched_test"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    fi

    # Clear previous results from device
    clear_device_files

    # Push input file to device
    if [ -n "$prepared_input" ]; then
        log_info "Pushing input to device: $(basename "$prepared_input")"
        device_push "$prepared_input"
    fi

    # Push test config to device
    local device_test_path="${DEVICE_WORKDIR}/$(basename "$patched_test")"
    device_push "$patched_test"

    # Force stop app to ensure clean state
    adb_cmd shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
    sleep 1

    # Clear logcat
    adb_cmd logcat -c 2>/dev/null || true

    # Generate a session_id so the app writes the JSONL manifest at
    # <DEVICE_WORKDIR>/<SESSION_ID>.session.jsonl. The CLI uses that
    # as the success oracle, replacing the silent-pass-on-logcat-
    # rollover bug the legacy grep had. Older apps (pre-Phase-2) ignore
    # the extra and run as before; collect_test_results falls back to
    # the legacy logcat oracle if no manifest appears.
    SESSION_ID="R$(date +%s)_$(
        openssl rand -hex 3 2>/dev/null \
        || head -c3 /dev/urandom 2>/dev/null | xxd -p 2>/dev/null \
        || printf '%06x' $$
    )"
    log_debug "session_id: $SESSION_ID"

    # Launch the test
    log_info "Running test on device..."
    adb_cmd shell am start \
        -e workdir "$DEVICE_WORKDIR" \
        -e test "$device_test_path" \
        -e session_id "$SESSION_ID" \
        "$ACTIVITY" >/dev/null 2>&1

    # Wait for completion
    if ! wait_for_app_exit; then
        TESTS_FAILED=$((TESTS_FAILED + 1))
        FAILED_TESTS+=("$label")
        FAILED_ERRORS+=("Timeout waiting for app to exit")
        # Collect logcat anyway
        collect_test_results "$test_result_dir" "$label"
        return 1
    fi

    # Collect results
    collect_test_results "$test_result_dir" "$label"
}

collect_test_results() {
    local result_dir="$1"
    local label="$2"

    local json_count=0
    local media_count=0

    # Manifest-first oracle: when SESSION_ID is set, pull the JSONL
    # manifest the app wrote at <DEVICE_WORKDIR>/<SESSION_ID>.session.jsonl
    # and use it as the source of truth for verdict + artifact discovery.
    # Falls back to the legacy logcat-grep + device_ls regex pull when
    # the manifest isn't present (older app build, app crashed before
    # writing session_start, etc.).
    local manifest_local=""
    if [ -n "${SESSION_ID:-}" ]; then
        local manifest_remote="${DEVICE_WORKDIR}/${SESSION_ID}.session.jsonl"
        device_pull "$manifest_remote" "$result_dir" 2>/dev/null || true
        if [ -f "${result_dir}/${SESSION_ID}.session.jsonl" ]; then
            manifest_local="${result_dir}/${SESSION_ID}.session.jsonl"
        fi
    fi

    if [ -n "$manifest_local" ]; then
        # Pull every artifact the manifest declared. Iterate via array,
        # not via 'while read <<< "$arts"' — adb shell inside device_rm
        # consumes the while-loop's stdin and silently kills iteration
        # after the first item.
        local arts_arr=()
        while IFS= read -r line; do
            [ -n "$line" ] && arts_arr+=("$line")
        done < <(jq -r 'select(.event=="artifact") | .path' "$manifest_local" 2>/dev/null)
        for art in "${arts_arr[@]}"; do
            device_pull "${DEVICE_WORKDIR}/${art}" "$result_dir" 2>/dev/null || true
            device_rm "$art" 2>/dev/null || true
            if [[ "$art" == *.json ]]; then
                json_count=$((json_count + 1))
            elif [[ "$art" == *.log ]] || [[ "$art" == *.jsonl ]]; then
                : # log files don't count as media
            else
                media_count=$((media_count + 1))
            fi
        done
    else
        # Legacy artifact discovery — what the script always did when no
        # manifest is available.
        local files
        files=$(device_ls "encapp_*" || true)
        if [ -n "$files" ]; then
            while IFS= read -r f; do
                [ -z "$f" ] && continue
                local basename_f
                basename_f=$(basename "$f")
                device_pull "$basename_f" "$result_dir" || true
                device_rm "$basename_f"
                if [[ "$basename_f" == *.json ]]; then
                    json_count=$((json_count + 1))
                else
                    media_count=$((media_count + 1))
                fi
            done <<< "$files"
        fi
    fi

    # Save logcat (always — useful for diagnostics regardless of which
    # oracle classified the verdict).
    local logcat_file="${result_dir}/logcat.txt"
    adb_cmd logcat -d > "$logcat_file" 2>/dev/null || true

    # Verdict.
    local test_ok=true
    local error_msg=""

    if [ -n "$manifest_local" ]; then
        # Manifest oracle: every test_end entry tells us the verdict
        # outright. test_start without a matching test_end → CRASH.
        # No test_start at all → app didn't start the test → NEVER_STARTED.
        local n_end n_start
        n_end=$(jq -r 'select(.event=="test_end") | .test_id' "$manifest_local" 2>/dev/null | wc -l | tr -d ' ')
        n_start=$(jq -r 'select(.event=="test_start") | .test_id' "$manifest_local" 2>/dev/null | wc -l | tr -d ' ')

        if [ "$n_end" -gt 0 ]; then
            # Check every test_end for non-ok status.
            local end_status
            end_status=$(jq -r 'select(.event=="test_end") | select(.status != "ok") | .status + ": " + (.error.code // "unknown") + ": " + (.error.message // "")' "$manifest_local" 2>/dev/null | head -1)
            if [ -n "$end_status" ]; then
                test_ok=false
                error_msg="$end_status"
            fi
        elif [ "$n_start" -gt 0 ]; then
            test_ok=false
            error_msg="CRASH: test_start emitted but no test_end (app died mid-test)"
        else
            test_ok=false
            error_msg="NEVER_STARTED: no test_start in manifest"
        fi
    elif [ -f "$logcat_file" ]; then
        # Legacy logcat oracle (unchanged from pre-Phase-2 behavior).
        local result_lines
        result_lines=$(grep -E 'Test finished id:.*result:' "$logcat_file" 2>/dev/null || true)

        if [ -n "$result_lines" ]; then
            while IFS= read -r line; do
                if echo "$line" | grep -q 'result: "error"' || echo "$line" | grep -q 'result: "Error"' || echo "$line" | grep -qi 'result:.*error'; then
                    test_ok=false
                    local err_code
                    err_code=$(echo "$line" | sed -n 's/.*error: "\([^"]*\)".*/\1/p')
                    [ -z "$err_code" ] && err_code="unknown error"
                    error_msg="$err_code"
                fi
            done <<< "$result_lines"
        else
            if grep -q "AndroidRuntime.*FATAL EXCEPTION" "$logcat_file" 2>/dev/null; then
                test_ok=false
                error_msg="App crashed (FATAL EXCEPTION)"
            elif [ "$json_count" -eq 0 ]; then
                test_ok=false
                error_msg="No result JSON produced"
            fi
        fi
    fi

    # Save device info
    adb_cmd shell getprop > "${result_dir}/device_props.txt" 2>/dev/null || true

    # Report result
    if $test_ok; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "$label — ${json_count} result(s), ${media_count} media file(s)"
        # Print brief stats from JSON
        for jf in "$result_dir"/encapp_*.json; do
            [ -f "$jf" ] || continue
            local mean_br
            mean_br=$(jq -r '.meanbitrate // "N/A"' "$jf" 2>/dev/null)
            local codec_used
            codec_used=$(jq -r '.test.configure.codec // "N/A"' "$jf" 2>/dev/null)
            local frame_count
            frame_count=$(jq -r '.frames | length // 0' "$jf" 2>/dev/null)
            echo "  $(basename "$jf"): codec=$codec_used bitrate=$mean_br frames=$frame_count"
        done
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        FAILED_TESTS+=("$label")
        FAILED_ERRORS+=("$error_msg")
        log_error "$label — $error_msg"
        if $DEBUG && [ -f "$logcat_file" ]; then
            echo -e "${YELLOW}  Last 20 lines of logcat:${NC}"
            tail -20 "$logcat_file" | sed 's/^/    /'
        fi
    fi
}

# --- Test matrix ---

collect_tests() {
    local path="$1"
    if [ -f "$path" ]; then
        echo "$path"
    elif [ -d "$path" ]; then
        find "$path" -maxdepth 1 -name "*.pbtxt" -type f | sort
    else
        die "Test path not found: $path"
    fi
}

collect_inputs() {
    local path="$1"
    if [ -f "$path" ]; then
        echo "$path"
    elif [ -d "$path" ]; then
        find "$path" -maxdepth 1 -type f \( -name "*.mp4" -o -name "*.mkv" -o -name "*.y4m" -o -name "*.yuv" -o -name "*.webm" -o -name "*.ivf" -o -name "*.264" -o -name "*.265" -o -name "*.hevc" \) | sort
    else
        die "Input path not found: $path"
    fi
}

# --- Print summary ---

print_summary() {
    echo ""
    echo -e "${BOLD}========================================${NC}"
    echo -e "${BOLD}  Test Summary${NC}"
    echo -e "${BOLD}========================================${NC}"
    echo ""
    echo "  Total:  $TESTS_RUN"
    echo -e "  ${GREEN}Passed: $TESTS_PASSED${NC}"
    echo -e "  ${RED}Failed: $TESTS_FAILED${NC}"

    if [ $TESTS_FAILED -gt 0 ]; then
        echo ""
        echo -e "${RED}${BOLD}Failed tests:${NC}"
        for i in "${!FAILED_TESTS[@]}"; do
            echo -e "  ${RED}x${NC} ${FAILED_TESTS[$i]}"
            echo -e "    ${YELLOW}Error: ${FAILED_ERRORS[$i]}${NC}"
        done
    fi

    echo ""
    [ -n "$OUTPUT_DIR" ] && echo "Results saved to: $OUTPUT_DIR"
    echo ""

    return $TESTS_FAILED
}

# --- Usage ---

usage() {
    cat <<'EOF'
Usage: encapp_run.sh [OPTIONS] <test.pbtxt | test_dir>

Lightweight encapp test runner. Runs codec tests on an Android device
using only bash, adb, jq, and ffmpeg.

Options:
  -s, --serial SERIAL    Device serial (default: auto-detect)
  -i, --input FILE|DIR   Input media file or directory
  -o, --output DIR       Output directory (default: ./encapp_results_<timestamp>)
  -c, --codec CODEC      Override codec name (e.g. c2.qti.hevc.encoder)
  -m, --mime MIME         Filter codec by mime type (default: video/hevc)
      --hw-only           Only select HW-accelerated codecs (default)
      --sw                Allow software codecs
  -r, --bitrate RATE     Override bitrate (e.g. "5 Mbps")
      --dry-run           Show what would be run without executing
  -d, --debug            Verbose output
  -h, --help             Show this help

Examples:
  # Run a single test (fake input, no media needed)
  encapp_run.sh tests/fake_input_buffer.pbtxt

  # Run a test with specific input
  encapp_run.sh -i video.mp4 tests/bitrate_buffer.pbtxt

  # Run all tests in a directory
  encapp_run.sh -o /tmp/results tests/

  # Test matrix: all tests x all inputs
  encapp_run.sh -i /path/to/videos/ tests/

  # Override codec and bitrate
  encapp_run.sh -c c2.qti.hevc.encoder -r "5 Mbps" tests/bitrate_buffer.pbtxt
EOF
}

# --- CLI parsing ---

parse_args() {
    local positional=()

    while [ $# -gt 0 ]; do
        case "$1" in
            -s|--serial)  SERIAL="$2"; shift 2 ;;
            -i|--input)   INPUT_PATH="$2"; shift 2 ;;
            -o|--output)  OUTPUT_DIR="$2"; shift 2 ;;
            -c|--codec)   CODEC_OVERRIDE="$2"; shift 2 ;;
            -m|--mime)    MIME_TYPE="$2"; shift 2 ;;
            --hw-only)    HW_ONLY=true; shift ;;
            --sw)         HW_ONLY=false; shift ;;
            -r|--bitrate) BITRATE_OVERRIDE="$2"; shift 2 ;;
            --dry-run)    DRY_RUN=true; shift ;;
            -d|--debug)   DEBUG=true; shift ;;
            -h|--help)    usage; exit 0 ;;
            -*)           die "Unknown option: $1" ;;
            *)            positional+=("$1"); shift ;;
        esac
    done

    if [ ${#positional[@]} -eq 0 ]; then
        usage
        exit 1
    fi

    TEST_PATH="${positional[0]}"
}

# --- Main ---

main() {
    parse_args "$@"

    check_prerequisites

    # Auto-detect device serial if not specified
    auto_detect_serial

    # Verify device connection and app installation (skip for dry-run without device)
    if ! $DRY_RUN; then
        check_device
        detect_workdir
    fi

    # Set up output directory
    if [ -z "$OUTPUT_DIR" ]; then
        OUTPUT_DIR="./encapp_results_$(date +%Y%m%d_%H%M%S)"
    fi
    mkdir -p "$OUTPUT_DIR"

    # Determine codec to use
    local codec_name="$CODEC_OVERRIDE"
    local pixfmt="nv12"
    local codec_file=""

    if [ -z "$codec_name" ] && ! $DRY_RUN; then
        # Fetch codec list and auto-select
        codec_file=$(fetch_codec_list)

        codec_name=$(find_codec "$codec_file" "$MIME_TYPE" "$HW_ONLY" || true)
        if [ -z "$codec_name" ]; then
            if $HW_ONLY; then
                log_warn "No HW codec found for $MIME_TYPE, trying SW..."
                codec_name=$(find_codec "$codec_file" "$MIME_TYPE" false || true)
            fi
        fi

        if [ -z "$codec_name" ]; then
            log_warn "No codec found for $MIME_TYPE — using codec from test config"
        else
            log_info "Selected codec: $codec_name"
            pixfmt=$(select_best_pixfmt "$codec_file" "$codec_name")
            log_debug "Selected pixel format: $pixfmt"
        fi
    fi

    # Look up the mime type for the selected codec
    local codec_mime=""
    if [ -n "$codec_name" ] && [ -n "$codec_file" ]; then
        codec_mime=$(get_codec_mime "$codec_file" "$codec_name")
        if [ -n "$codec_mime" ]; then
            log_debug "Codec mime type: $codec_mime"
        fi
    fi

    # Collect test files
    local tests
    tests=$(collect_tests "$TEST_PATH")
    local test_count
    test_count=$(echo "$tests" | grep -c . || true)
    log_info "Found $test_count test(s)"

    # Collect input files (if provided)
    local inputs=""
    local input_count=0
    if [ -n "$INPUT_PATH" ]; then
        inputs=$(collect_inputs "$INPUT_PATH")
        input_count=$(echo "$inputs" | grep -c . || true)
        log_info "Found $input_count input(s)"
    fi

    # Run the test matrix
    if [ "$input_count" -gt 0 ]; then
        local total=$((test_count * input_count))
        log_info "Running test matrix: $test_count tests x $input_count inputs = $total combinations"
        echo ""

        while IFS= read -r test_file; do
            [ -z "$test_file" ] && continue
            while IFS= read -r input_file; do
                [ -z "$input_file" ] && continue
                run_single_test "$test_file" "$input_file" "$OUTPUT_DIR" "$codec_name" "$pixfmt" "$codec_mime"
            done <<< "$inputs"
        done <<< "$tests"
    else
        log_info "Running $test_count test(s)"
        echo ""

        while IFS= read -r test_file; do
            [ -z "$test_file" ] && continue
            run_single_test "$test_file" "" "$OUTPUT_DIR" "$codec_name" "$pixfmt" "$codec_mime"
        done <<< "$tests"
    fi

    # Summary
    print_summary
    exit $?
}

# Run main unless sourced (for testing)
if [[ "${BASH_SOURCE[0]:-}" == "${0}" ]]; then
    main "$@"
fi
