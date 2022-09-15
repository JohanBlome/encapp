#!/usr/bin/env python3
import os
import re
from typing import Optional, Tuple

VERSION_FILE = 'app/build.gradle'
VERSION_TAG = r'versionName "([0-9]*).([0-9]*)"'
SCRIPT_DIR = os.path.join(os.path.abspath(os.path.dirname(__file__)), '..')
ROOT_DIR = os.path.join(SCRIPT_DIR, '..')


def _lookup_expected_encapp_version() -> Optional[Tuple[str, str]]:
    """Get encapp apk expected version from local gradle

    Returns:
        A tuple with apk version (major_version, minor_version)
        if found, else None
    """
    with open(f'{ROOT_DIR}/{VERSION_FILE}', 'r') as fl:
        data = fl.read()

    m = re.search(VERSION_TAG, data)
    if m is not None and len(m.groups()) == 2:
        return m.group(1), m.group(2)
    return None


__version_info__ = _lookup_expected_encapp_version()
__version__ = '.'.join((str(i) for i in __version_info__))
