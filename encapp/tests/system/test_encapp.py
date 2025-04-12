#!/usr/bin/env python3

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
    try:
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} uninstall"
            ],
            shell=True,
            check=True,
            universal_newlines=True,
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
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


@pytest.fixture
def setup_data():
    if ENCAPP_ALWAYS_INSTALL:
        # Normally we always uninstall/install. This can be avoided by setting:
        # ENCAPP_ALWAYS_INSTALL to False.
        uninstall()
        install()

    # Download video
    subprocess.run(
        [f"{ENCAPP_SCRIPTS_DIR}/prepare_test_data.sh"],
        shell=True,
        check=True,
        universal_newlines=True,
        stderr=subprocess.STDOUT,
        stdout=subprocess.PIPE,
    )
    yield
    # remove stuff if needed


def test_is_encapp_script_found():
    """Verify encapp.py script is at expected path"""
    assert os.path.isfile(ENCAPP_SCRIPT_PATH)


def test_help_option():
    """Verify encapp.py --help do not throw any error"""
    try:
        subprocess.run(
            [f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} " f"--help"],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def test_list(tmp_path, setup_data):
    """Verify list work on specified android device"""
    try:
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} list -o {tmp_path} -nc"
            ],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
            cwd=tmp_path,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def test_find_sw_h264_codec(tmp_path, setup_data):
    """Verify software h264 codec is found"""
    try:
        result = subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} list --codec '.*h264.*' --sw -o tmp_path/"
            ],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        codec = result.stdout
        assert len(codec) > 0 and "h264" in codec, print("No h264 codec found")
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def test_surface_transcoding(tmp_path, setup_data):
    """Verify surface transcoding work a device"""
    try:
        # Get codec list and lookup a sw h264 codec
        result = subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} list --codec '.*h264.*' --sw --output tmp_path/"
            ],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        codec = result.stdout.strip()
        assert len(codec) > 0, print("No h264 codec found")

        output_path = f"{tmp_path}/encapp_surface_test/"
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} run {TEST_SCRIPTS_DIR}/system_test_surface_transcode.pbtxt --codec {codec} --local-workdir {output_path}"
            ],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def test_buffer_encoding(tmp_path, setup_data):
    """Verify raw buffer encoding work on test device"""
    output_path = f"{tmp_path}/encapp_surface_test/"
    try:
        # Get codec list and lookup a sw h264 codec
        result = subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} list --codec '.*h264.*' --sw --output {output_path}"
            ],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        codec = result.stdout.strip()
        assert len(codec) > 0, print("No h264 codec found")
        # Download video
        subprocess.run(
            [f"{ENCAPP_SCRIPTS_DIR}/prepare_test_data.sh"],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        output_path = "/tmp/encapp_surface_test/"
        subprocess.run(
            [
                f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
                f"--serial {ANDROID_SERIAL} run {TEST_SCRIPTS_DIR}/system_test_buffer_encode.pbtxt --codec {codec} --local-workdir {output_path}"
            ],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)
