#!/usr/local/bin/python3

import matplotlib.pyplot as plt
import argparse
import sys
import json
import os
import numpy as np
import pandas as pd
import re

INDEX_FILE_NAME = "encapp_index.csv"

def getFilesInDir(directory, options):
    regexp = "^encapp_.*json$"
    files = []
    for path in os.listdir(directory):
        full_path = os.path.join(directory, path)
        if os.path.isfile(full_path):
            if re.match(regexp, path):
                files.append(full_path)
        else:
            if not options.no_rec:
                files = files + getFilesInDir(full_path, options)
    return files
    
def indexCurrentDir(options):
    current_dir = os.getcwd()
    files = getFilesInDir(current_dir, options)
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
            print("json " + df + ", load failed: "+str(exc))

    labels=['file', 'media', 'codec', 'gop', 'fps', 'width', 'height', 'bitrate', 'real_bitrate']
    pdata = pd.DataFrame.from_records(settings, columns=labels, coerce_float = True)  
    pdata.to_csv(INDEX_FILE_NAME, index=False)

def search(options):
    try:
        data = pd.read_csv(INDEX_FILE_NAME)
    except:
        sys.stderr.write("Error when reading index, reindex\n")
        indexCurrentDir(options)
        try:
            data = pd.read_csv(INDEX_FILE_NAME)
        except:
            sys.stderr.write("Failed to read index file")
            exit(-1)
    if options.codec:
        data = data.loc[data['codec'].str.contains(options.codec)]
    if options.bitrate:
        ranges=options.bitrate.split('-')
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
            data = data.loc[(data['bitrate'] >= vals[0]) & (data['bitrate'] <= vals[1])]
        else:
            data = data.loc[data['bitrate'] ==  vals[0]]
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
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('-s', '--size',  default=None) # WxH
    parser.add_argument('-c', '--codec',  default=None) 
    parser.add_argument('-b', '--bitrate',  default=None) 
    parser.add_argument('-g', '--gop',  type=int, default=None)
    parser.add_argument('-f', '--fps',  type=float, default=None)
    parser.add_argument('--no_rec',  action='store_true')
    parser.add_argument('--index',  action='store_true')
    parser.add_argument('-v', '--video',  action='store_true')
    parser.add_argument('-p', '--print_data',  action='store_true')

    options = parser.parse_args()

    if options.index:
        indexCurrentDir(options)

    data = search(options)
    
    data = data.sort_values(by=['codec','gop','fps', 'height' , 'bitrate' ])
    if options.print_data:
        for index, row in data.iterrows():
            print("{:s},{:s},{:s},{:d},{:d},{:d},{:d},{:d},{:d}".format(
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
            if options.video:
                directory, filename = os.path.split(fl)
                video = data.loc[data['file'] == fl]            
                name = directory + '/' + video['media'].values[0]
                print(name)
            else:
                print(fl)
if __name__ == '__main__':
    main()
