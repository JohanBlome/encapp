#!/usr/bin/env python3
import re
import os

VERSION_FILE = 'app/build.gradle'
VERSION_TAG = 'versionName \"([0-9]*).([0-9]*)\"'
SCRIPT_DIR = os.path.abspath(os.path.dirname(__file__))
ROOT_DIR = os.path.join(SCRIPT_DIR, '..')


def lookupVersion():
    with open(f'{ROOT_DIR}/{VERSION_FILE}', 'r') as fl:
        data = fl.read()

    m = re.search(VERSION_TAG, data)
    if m is not None and len(m.groups()) == 2:
        return (m.group(1), m.group(2))


__version_info__ = lookupVersion()
__version__ = '.'.join((str(i) for i in __version_info__))
