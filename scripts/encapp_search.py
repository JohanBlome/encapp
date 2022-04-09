#!/usr/bin/env python3

"""Python script to for index and search in Encapp result json files.
It will search all directories below the specified one (unless --no_rec
option enabled).

Searchable properties are
* size (WxH)
* codec (partial name is fine)
* bitrate where bitrate can be
    - sctrict size, e.g. 200k
    - a range 200000-1M
* group of pictures (gop)
* frame rate

The output can either be the video source files or the json result.
"""

import argparse
from argparse import RawTextHelpFormatter
import sys
import json
import os
import pandas as pd
import re

INDEX_FILE_NAME = '.encapp_index'


def getProperties(options, json):
    data = getData(options, True)
    _, filename = os.path.split(json)
    row = data.loc[data['file'].str.contains(filename)]
    return row


def getFilesInDir(directory, recursive):
    regexp = '^encapp_.*json$'
    files = []
    for path in os.listdir(directory):
        full_path = os.path.join(directory, path)
        if os.path.isfile(full_path):
            if re.match(regexp, path):
                files.append(full_path)
        else:
            if recursive:
                files = files + getFilesInDir(full_path, recursive)
    return files


def indexDirectory(options, recursive):
    files = getFilesInDir(f'{options.path}', recursive)
    settings = []

    for df in files:
        try:
            with open(df) as f:
                data = json.load(f)
                settings.append([df,
                                 data['encodedfile'],
                                 data['settings']['codec'],
                                 data['settings']['gop'],
                                 data['settings']['fps'],
                                 data['settings']['width'],
                                 data['settings']['height'],
                                 data['settings']['bitrate'],
                                 data['settings']['meanbitrate']])
        except Exception as exc:
            print('json ' + df + ', load failed: ' + str(exc))

    labels = ['file', 'media', 'codec', 'gop', 'fps', 'width', 'height',
              'bitrate', 'real_bitrate']
    pdata = pd.DataFrame.from_records(settings, columns=labels,
                                      coerce_float=True)
    pdata.to_csv(f'{options.path}/{INDEX_FILE_NAME}', index=False)


def getData(options, recursive):
    try:
        data = pd.read_csv(f'{options.path}/{INDEX_FILE_NAME}')
    except Exception:
        sys.stderr.write('Error when reading index, reindex\n')
        indexDirectory(options, recursive)
        try:
            data = pd.read_csv(f'{options.path}/{INDEX_FILE_NAME}')
        except Exception:
            sys.stderr.write('Failed to read index file: '
                             f'{options.path}/{INDEX_FILE_NAME}')
            exit(-1)
    return data


def search(options):
    data = getData(options, not options.no_rec)

    if options.codec:
        data = data.loc[data['codec'].str.contains(options.codec)]
    if options.bitrate:
        ranges = options.bitrate.split('-')
        vals = []
        for val in ranges:
            bitrate = 0
            kb = val.split('k')
            if len(kb) == 2:
                bitrate = int(kb[0]) * 1000
            Mb = val.split('M')
            if len(Mb) == 2:
                bitrate = int(Mb[0]) * 1000000
            if bitrate == 0:
                bitrate = int(val)
            vals.append(bitrate)

        if len(vals) == 2:
            data = data.loc[(data['bitrate'] >= vals[0]) &
                            (data['bitrate'] <= vals[1])]
        else:
            data = data.loc[data['bitrate'] == vals[0]]
    if options.gop:
        data = data.loc[data['gop'] == options.gop]
    if options.fps:
        data = data.loc[data['fps'] == options.fps]
    if options.size:
        sizes = options.size.split('x')
        if len(sizes) == 2:
            data = data.loc[(data['width'] == int(sizes[0])) &
                            (data['height'] == int(sizes[1]))]
        else:
            data = data.loc[(data['width'] == int(sizes[0])) |
                            (data['height'] == int(sizes[0]))]

    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=RawTextHelpFormatter)
    parser.add_argument('path',
                        nargs='?',
                        help='Search path, default current')
    parser.add_argument('-s', '--size', default=None)  # WxH
    parser.add_argument('-c', '--codec', default=None)
    parser.add_argument('-b', '--bitrate', default=None)
    parser.add_argument('-g', '--gop', type=int, default=None)
    parser.add_argument('-f', '--fps', type=float, default=None)
    parser.add_argument('--no_rec', action='store_true')
    parser.add_argument('-i', '--index', action='store_true')
    parser.add_argument('-v', '--video', action='store_true')
    parser.add_argument('-p', '--print_data', action='store_true')

    options = parser.parse_args()
    if options.path is None:
        options.path = os.getcwd()

    if options.index:
        indexDirectory(options, not options.no_rec)

    data = search(options)

    data = data.sort_values(by=['codec', 'gop', 'fps', 'height', 'bitrate'])
    if options.print_data:
        for _index, row in data.iterrows():
            print('{:s},{:s},{:s},{:d},{:d},{:d},{:d},{:d},{:d}'.format(
                  row['file'],
                  row['media'],
                  row['codec'],
                  row['gop'],
                  row['fps'],
                  row['width'],
                  row['height'],
                  row['bitrate'],
                  row['real_bitrate']))
    else:
        files = data['file'].values
        for fl in files:
            directory, filename = os.path.split(fl)
            if options.video:
                video = data.loc[data['file'] == fl]
                name = directory + '/' + video['media'].values[0]
            else:
                name = fl
            print(name)


if __name__ == '__main__':
    main()
