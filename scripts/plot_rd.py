#!/usr/bin/env python3

'''A Python script to plot Rate Distortion curves.
 Input is a csv file containing codec, vmaf, bitrate, and real_bitrate
'''

import matplotlib.pyplot as plt
import argparse
import json
import os
import numpy as np
import pandas as pd


class RDPlot:
    def __init__(self):
        self.curve_index = 0
        self.colors = ['b', 'g', 'r', 'c', 'm', 'y', 'k']
        self.markers = ['o', 'v', '^', '<', '>', '8', 's',
                        'p', '*', 'h', 'H', 'D', 'd', 'P', 'X']
        self.lines = ['-', '--', '-.']

    def get_style(self):
        index = self.curve_index
        color = self.colors[index % len(self.colors)]
        marker = self.markers[index % len(self.markers)]
        line = self.lines[index % len(self.lines)]
        self.curve_index += 1

        return color+marker+line

    def vmaf_figure(self, title):
        plt.figure()
        plt.title(os.path.basename(title))
        plt.xlabel('Bitrate (kbps)')
        plt.ylabel('VMAF Score')
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 0
    
    def bitrate_figure(self, title):
        plt.figure()
        plt.title(os.path.basename(title))
        plt.xlabel('Requested Bitrate (kbps)')
        plt.ylabel('Actual bitrate (kbps)')
        plt.ticklabel_format(style='plain')
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 0

    def draw(self, bitrates, scores, curve_label):
        self.x_max = max(self.x_max, np.amax(bitrates))
        self.y_max = max(self.y_max, np.amax(scores))
        self.y_min = min(self.y_min, np.amin(scores))
        plt.plot(bitrates, scores, self.get_style(), label=str(curve_label))
        plt.legend(loc='lower right')

    def finish(self):
        plt.axis([self.x_min, self.x_max+100, self.y_min-5, self.y_max])
        plt.grid()
        plt.draw()

    def plot_rd_curve(self, quality_csv):
        rd_results = None
        with open(quality_csv, "r") as fp:
            data = pd.read_csv(fp)
            fp.close()

            heights =  np.unique(data['height'])
            codecs =  np.unique(data['codec'])
           
            for height in heights:
                filtHeight = data.loc[data['height'] == height]
                filtHeight = filtHeight.apply(pd.to_numeric, errors='ignore')
                if len(filtHeight) <= 1:
                    continue
                self.vmaf_figure(f'VMAF for {height}p')
                for codec in codecs:
                    filtCodec = filtHeight.loc[filtHeight['height'] == height]
                    filtHeight = filtHeight.sort_values('real_bitrate')
                    if len(filtHeight) > 0:
                        self.draw(filtHeight['real_bitrate']/1000,
                              filtHeight['vmaf'],
                              f'{codec}')
                self.finish()
                self.bitrate_figure(f'Bitrate accuracy for {height}p')
                for codec in codecs:
                    filtCodec = filtHeight.loc[filtHeight['height'] == height]
                    filtHeight = filtHeight.sort_values('real_bitrate')
                    if len(filtHeight) > 0:
                        self.draw(filtHeight['real_bitrate']/1000,
                              filtHeight['bitrate']/1000,
                              f'{codec}')
                self.finish()
        plt.show()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', help='Quality csv file')
    args = parser.parse_args()
    rd_plot = RDPlot()
    rd_plot.plot_rd_curve(args.file)
