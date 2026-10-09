#!/usr/bin/env python3
import os
import re
import typing

VERSION_FILE = "app/build.gradle"
VERSION_TAG = r'versionName "([0-9]+)\.([0-9]+)"'
SCRIPT_DIR = os.path.join(os.path.abspath(os.path.dirname(__file__)), "..")
ROOT_DIR = os.path.join(SCRIPT_DIR, "..")


def _parse_version(value: str) -> typing.Optional[typing.Tuple[str, str]]:
    match = re.fullmatch(r"([0-9]+)\.([0-9]+)", value.strip())
    if match is None:
        return None
    return match.group(1), match.group(2)


def _lookup_expected_encapp_version() -> typing.Optional[typing.Tuple[str, str]]:
    """Get the host script's expected Encapp version.

    Source checkouts read app/build.gradle. Standalone tool packages can set
    ENCAPP_EXPECTED_VERSION and do not need to bundle Gradle files or an APK.
    """
    override = os.environ.get("ENCAPP_EXPECTED_VERSION")
    if override:
        parsed = _parse_version(override)
        if parsed is not None:
            return parsed

    path = os.path.join(ROOT_DIR, VERSION_FILE)
    try:
        with open(path) as file:
            data = file.read()
    except FileNotFoundError:
        return None

    match = re.search(VERSION_TAG, data)
    if match is not None and len(match.groups()) == 2:
        return match.group(1), match.group(2)
    return None


__version_info__ = _lookup_expected_encapp_version() or ("unknown",)
__version__ = ".".join(str(item) for item in __version_info__)
