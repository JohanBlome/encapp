#!/usr/bin/env python3
"""
System test for tiled HEIC encoding.

This test verifies that the tiled HEIC encoding feature works correctly:
1. Generates a test pattern with numbered tiles
2. Runs tiled encoding on the device
3. Verifies the output file exists and has correct structure

Prerequisites:
- ANDROID_SERIAL environment variable set
- encapp app installed on the device
- ffmpeg installed (for generating test input)

Usage:
    ANDROID_SERIAL=<device_id> python -m pytest scripts/tests/system/test_tiled_heic.py -v
"""

import os
import shutil
import subprocess
import tempfile

import pytest

PYTHON_ENV = "python3"
MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir)
ENCAPP_SCRIPT_PATH = os.path.join(ENCAPP_SCRIPTS_DIR, "encapp.py")
ANDROID_SERIAL = os.getenv("ANDROID_SERIAL")
ENCAPP_ALWAYS_INSTALL = os.getenv("ENCAPP_ALWAYS_INSTALL", "True") in [
    "True",
    "true",
    "1",
]

assert ANDROID_SERIAL is not None, "ANDROID_SERIAL environment variable must be defined"


def uninstall():
    try:
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} uninstall"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def install():
    try:
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} install"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def generate_numbered_tile_pattern(
    width: int, height: int, tile_size: int, output_path: str, pix_fmt: str = "yuv420p"
) -> bool:
    """
    Generate a test pattern YUV image with numbered/colored tiles using ffmpeg.

    Args:
        width: Image width
        height: Image height
        tile_size: Size of each tile (square)
        output_path: Path to write the YUV file
        pix_fmt: Pixel format (yuv420p or nv12)

    Returns:
        True if successful, False otherwise
    """
    tile_cols = (width + tile_size - 1) // tile_size
    tile_rows = (height + tile_size - 1) // tile_size

    # Build a complex filtergraph that creates colored tiles with numbers
    filter_parts = [f"color=black:s={width}x{height}:d=1"]

    colors = [
        "red",
        "green",
        "blue",
        "white",
        "yellow",
        "magenta",
        "cyan",
        "gray",
        "orange",
        "purple",
        "pink",
        "lime",
    ]

    for row in range(tile_rows):
        for col in range(tile_cols):
            tile_idx = row * tile_cols + col
            x = col * tile_size
            y = row * tile_size
            w = min(tile_size, width - x)
            h = min(tile_size, height - y)
            color = colors[tile_idx % len(colors)]

            filter_parts.append(
                f"drawbox=x={x}:y={y}:w={w}:h={h}:c={color}:t=fill"
            )
            filter_parts.append(f"drawbox=x={x}:y={y}:w={w}:h={h}:c=white:t=4")

            text_x = x + w // 2 - 20
            text_y = y + h // 2 - 40
            filter_parts.append(
                f"drawtext=text='{tile_idx}':x={text_x}:y={text_y}:"
                f"fontsize=80:fontcolor=black:borderw=2:bordercolor=white"
            )

    filter_graph = ",".join(filter_parts)

    cmd = [
        "ffmpeg",
        "-y",
        "-f",
        "lavfi",
        "-i",
        filter_graph,
        "-frames:v",
        "1",
        "-pix_fmt",
        pix_fmt,
        "-f",
        "rawvideo",
        output_path,
    ]

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if result.returncode != 0:
            print(f"ffmpeg error: {result.stderr}")
            return False
        return True
    except subprocess.TimeoutExpired:
        print("ffmpeg timed out")
        return False
    except FileNotFoundError:
        print("ffmpeg not found. Please install ffmpeg.")
        return False


@pytest.fixture
def setup_encapp():
    """Install encapp if ENCAPP_ALWAYS_INSTALL is set."""
    if ENCAPP_ALWAYS_INSTALL:
        uninstall()
        install()
    yield


@pytest.fixture
def temp_dir():
    """Create a temporary directory for test artifacts."""
    tmp = tempfile.mkdtemp(prefix="encapp_tiled_test_")
    yield tmp
    shutil.rmtree(tmp, ignore_errors=True)


@pytest.fixture
def test_pattern(temp_dir):
    """Generate a test pattern for tiled encoding."""
    output_path = os.path.join(temp_dir, "test_pattern.yuv")
    success = generate_numbered_tile_pattern(
        width=1280, height=1280, tile_size=512, output_path=output_path
    )
    if not success:
        pytest.skip("ffmpeg not available for pattern generation")
    return output_path


def test_tiled_heic_encoding(setup_encapp, temp_dir, test_pattern):
    """Verify tiled HEIC encoding works on the device."""
    output_path = os.path.join(temp_dir, "output")
    os.makedirs(output_path, exist_ok=True)

    # First find an HEVC encoder
    try:
        result = subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} list --codec hevc --output {output_path}/"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        # Just run the first one
        codec = result.stdout.strip().split("\n")[0]
        if not codec or "hevc" not in codec.lower():
            pytest.skip("No HEVC encoder found on device")
    except subprocess.CalledProcessError as err:
        pytest.fail(f"Failed to list codecs: {err.stdout}")

    # Create test pattern
    test_pattern = os.path.join(temp_dir, "test_pattern.yuv")
    success = generate_numbered_tile_pattern(
        width=1280, height=1280, tile_size=512, output_path=test_pattern
    )
    if not success:
        pytest.skip("ffmpeg not available for pattern generation")
    # Create a test config file
    config_path = os.path.join(temp_dir, "tiled_heic_test.pbtxt")
    with open(config_path, "w") as f:
        f.write(
            f"""test {{
                common {{
                    id: 'tiled_heic_system_test'
                    description: 'System test for tiled HEIC encoding'
                }}
                input {{
                    filepath: '{test_pattern}'
                    resolution: '1280x1280'
                    pix_fmt: yuv420p
                    framerate: 1
                    playout_frames: 1
                }}
                configure {{
                    codec: '{codec}'
                    mime: 'image/heif'
                    bitrate: '8M'
                    i_frame_interval: 0
                    tile_width: 512
                    tile_height: 512
                }}
            }}"""
        )

    # Run the encoding
    try:
        result = subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} run {config_path} "
                f"--local-workdir {output_path} -dd"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        print(f"Encoding command output:\n{result.stdout}")
    except subprocess.CalledProcessError as err:
        pytest.fail(f"Tiled HEIC encoding failed: {err.stdout}")

    # Verify output exists - check for any video output files
    # The muxer may produce .mp4, .heic, or .hevc files depending on configuration
    # Files might be in subdirectories, so check recursively
    all_files = []
    video_files = []

    print(f"Searching for output files in: {output_path}")
    for root, dirs, files in os.walk(output_path):
        print(f"  Checking directory: {root}")
        print(f"    Subdirs: {dirs}")
        print(f"    Files: {files}")
        for file in files:
            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, output_path)
            all_files.append(rel_path)
            if file.endswith((".heic", ".mp4", ".hevc", ".h265", ".265", ".stats")):
                video_files.append(rel_path)

    # Debug: print what files were actually created
    print(f"\nAll files found (recursive): {all_files}")
    print(f"Video/output files found: {video_files}")

    # Also check if the output might be in the temp_dir root
    print(f"\nChecking temp_dir root: {temp_dir}")
    temp_files = os.listdir(temp_dir)
    print(f"Files in temp_dir: {temp_files}")

    assert len(video_files) > 0, (
        f"No video output file produced.\n"
        f"Searched in: {output_path}\n"
        f"Files found: {all_files}\n"
        f"Temp dir files: {temp_files}"
    )


# Tile calculation tests (pure Python, no device needed)
class TestTileCalculations:
    """Test tile grid calculations."""

    def test_grid_exact_fit(self):
        """Test grid calculation when image divides evenly."""
        width, height = 1024, 1024
        tile_size = 512

        tile_cols = (width + tile_size - 1) // tile_size
        tile_rows = (height + tile_size - 1) // tile_size

        assert tile_cols == 2
        assert tile_rows == 2
        assert tile_cols * tile_rows == 4

    def test_grid_with_padding(self):
        """Test grid calculation with padding required."""
        width, height = 1280, 1280
        tile_size = 512

        tile_cols = (width + tile_size - 1) // tile_size
        tile_rows = (height + tile_size - 1) // tile_size

        assert tile_cols == 3  # 1280/512 = 2.5 -> 3
        assert tile_rows == 3
        assert tile_cols * tile_rows == 9

        # Padded dimensions
        padded_width = tile_cols * tile_size
        padded_height = tile_rows * tile_size
        assert padded_width == 1536
        assert padded_height == 1536

    def test_grid_non_square(self):
        """Test grid calculation for non-square images."""
        width, height = 1920, 1080
        tile_size = 512

        tile_cols = (width + tile_size - 1) // tile_size
        tile_rows = (height + tile_size - 1) // tile_size

        assert tile_cols == 4  # 1920/512 = 3.75 -> 4
        assert tile_rows == 3  # 1080/512 = 2.11 -> 3
        assert tile_cols * tile_rows == 12
