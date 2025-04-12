#!/usr/bin/env python3

import os
import subprocess
import pytest

PYTHON_ENV = "python3"
MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir)
VERIFY_SCRIPT_PATH = os.path.join(ENCAPP_SCRIPTS_DIR, "encapp_verify.py")
ANDROID_SERIAL = os.getenv("ANDROID_SERIAL")

assert ANDROID_SERIAL is not None, "ANDROID_SERIAL environment variable must be defined"


def test_is_verify_script_found():
    """Verify encapp_verify.py script is at expected path"""
    assert os.path.isfile(VERIFY_SCRIPT_PATH)


def test_help_option():
    """Verify encapp_verify.py --help do not throw any error"""
    try:
        subprocess.run(
            [f"{PYTHON_ENV} {VERIFY_SCRIPT_PATH} " f"--help"],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)
