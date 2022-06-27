import os
import subprocess
import pytest

PYTHON_ENV = "python3"
MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_DIR = os.path.join(MODULE_PATH, os.pardir, os.pardir)
ENCAPP_SCRIPT_PATH = os.path.join(ENCAPP_SCRIPTS_DIR, "encapp.py")
ANDROID_SERIAL = os.getenv("ANDROID_SERIAL")

assert ANDROID_SERIAL is not None, "ANDROID_SERIAL environment variable must be defined"


def test_is_encapp_script_found():
    """Verify encapp.py script is at expected path"""
    assert os.path.isfile(ENCAPP_SCRIPT_PATH)


def test_help_option():
    """Verify encapp.py --help do not throw any error"""
    try:
        subprocess.run(
            [f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
             f"--help"],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)

def test_install():
    """Verify installation work on specified android device"""
    try:
        subprocess.run(
            [f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
             f"--serial {ANDROID_SERIAL} install"],
            shell=True,
            check=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def test_uninstall():
    """Verify uninstall work on specified android device"""
    try:
        subprocess.run(
            [f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
             f"--serial {ANDROID_SERIAL} uninstall"],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)


def test_list(tmp_path):
    """Verify list work on specified android device"""
    try:
        subprocess.run(
            [f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
             f"--serial {ANDROID_SERIAL} install"],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
        )
        subprocess.run(
            [f"{PYTHON_ENV} {ENCAPP_SCRIPT_PATH} "
             f"--serial {ANDROID_SERIAL} list"],
            shell=True,
            check=True,
            universal_newlines=True,
            stderr=subprocess.STDOUT,
            stdout=subprocess.PIPE,
            cwd=tmp_path
        )
    except subprocess.CalledProcessError as err:
        pytest.fail(err.stdout)
