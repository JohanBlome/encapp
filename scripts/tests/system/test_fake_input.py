#!/usr/bin/env python3
"""
System tests for fake input encoding.

These tests verify fake input buffer encoding - uses synthetic data generation
instead of file input for performance testing without filesystem overhead.

Requires:
- ANDROID_SERIAL environment variable to be set
- encapp app installed on the device
"""

import os
import subprocess
import pytest

PYTHON_ENV = "python3"
MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir)
ENCAPP_SCRIPT_PATH = os.path.join(ENCAPP_SCRIPTS_DIR, "encapp.py")
TEST_SCRIPTS_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir, os.pardir, "tests")
ANDROID_SERIAL = os.getenv("ANDROID_SERIAL")
ENCAPP_ALWAYS_INSTALL = os.getenv("ENCAPP_ALWAYS_INSTALL", "True") in [
    "True",
    "true",
    "1",
]

assert ANDROID_SERIAL is not None, "ANDROID_SERIAL environment variable must be defined"


def uninstall():
    """Uninstall encapp from the device."""
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
    """Install encapp on the device."""
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


@pytest.fixture
def setup_encapp():
    """Setup fixture that installs encapp if needed."""
    if ENCAPP_ALWAYS_INSTALL:
        uninstall()
        install()
    yield


def test_fake_input_buffer_encoding(tmp_path, setup_encapp):
    """Verify fake input buffer encoding works without file input."""
    try:
        # Get a software H.264 encoder
        result = subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} list --codec '.*h264.*' --sw --encoder"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        codec = result.stdout.strip()
        assert len(codec) > 0, "No software H.264 encoder found"

        output_path = f"{tmp_path}/encapp_fake_input_test/"
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} run "
                f"{TEST_SCRIPTS_DIR}/system_test_fake_input.pbtxt "
                f"--codec {codec} --local-workdir {output_path}"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        # Verify output file was created
        output_files = os.listdir(output_path) if os.path.exists(output_path) else []
        mp4_files = [f for f in output_files if f.endswith('.mp4')]
        assert len(mp4_files) > 0, f"No output MP4 files found in {output_path}"

    except subprocess.CalledProcessError as err:
        pytest.fail(f"Fake input encoding failed: {err.stdout}")


def test_fake_input_produces_valid_output(tmp_path, setup_encapp):
    """Verify fake input produces a valid, non-empty output file."""
    try:
        # Get a software H.264 encoder
        result = subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} list --codec '.*h264.*' --sw --encoder"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        codec = result.stdout.strip()
        assert len(codec) > 0, "No software H.264 encoder found"

        output_path = f"{tmp_path}/encapp_fake_input_validate/"
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} run "
                f"{TEST_SCRIPTS_DIR}/system_test_fake_input.pbtxt "
                f"--codec {codec} --local-workdir {output_path}"
            ],
            shell=True,
            check=True,
            text=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )

        # Check output file size is reasonable (at least 1KB for 100 frames)
        output_files = os.listdir(output_path) if os.path.exists(output_path) else []
        mp4_files = [f for f in output_files if f.endswith('.mp4')]
        assert len(mp4_files) > 0, "No output MP4 files found"

        for mp4_file in mp4_files:
            file_path = os.path.join(output_path, mp4_file)
            file_size = os.path.getsize(file_path)
            assert file_size > 1024, f"Output file {mp4_file} is too small ({file_size} bytes)"

    except subprocess.CalledProcessError as err:
        pytest.fail(f"Fake input validation failed: {err.stdout}")
