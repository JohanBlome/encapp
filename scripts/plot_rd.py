#!/usr/local/bin/python3

import matplotlib.pyplot as plt
import argparse
import json
import os
import numpy as np


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

    def new_figure(self, title):
        plt.figure()
        plt.title(os.path.basename(title))
        plt.xlabel('Bitrate (kbps)')
        plt.ylabel('VMAF Score')
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 100

    def draw(self, bitrates, scores, curve_label):
        self.x_max = max(self.x_max, np.amax(bitrates))
        self.y_min = min(self.y_min, np.amin(scores))
        plt.plot(bitrates, scores, self.get_style(), label=str(curve_label))
        plt.legend(loc='lower right')

    def finish(self):
        plt.axis([self.x_min, self.x_max+100, self.y_min-5, self.y_max])
        plt.grid()
        plt.draw()

    def plot_rd_curve(self, rd_results_json_file):
        rd_results = None
        with open(rd_results_json_file, "r") as fp:
            rd_results = json.load(fp)
            fp.close()

            for key in rd_results:
                self.new_figure(key)
                for result in rd_results[key]:
                    self.draw(result['bitrates'],
                              result['vmaf_scores'],
                              result['description'])
                self.finish()
        plt.show()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='A Python script \
        to plot Rate Distortion curves')
    parser.add_argument('--file', required=True, help='Rate Distortion file')
    args = parser.parse_args()
    rd_plot = RDPlot()
    rd_plot.plot_rd_curve(args.file)
