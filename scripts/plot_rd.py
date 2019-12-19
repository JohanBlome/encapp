#!/usr/local/bin/ python3

import matplotlib.pyplot as plt
import argparse
import json
import numpy as np


class RDPlot:
    def __init__(self, title):
        self.curve_index = 0
        self.colors = ['b', 'g', 'r', 'c', 'm', 'y', 'k']
        self.markers = ['o', 'v', '^', '<', '>', '8', 's',
                        'p', '*', 'h', 'H', 'D', 'd', 'P', 'X']
        self.lines = ['-', '--', '-.']
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 100
        plt.figure()
        plt.title(title)
        plt.xlabel('Bitrate (kbps)')
        plt.ylabel('VMAF Score')

    def get_style(self):
        index = self.curve_index
        color = self.colors[index % len(self.colors)]
        marker = self.markers[index % len(self.markers)]
        line = self.lines[index % len(self.lines)]
        self.curve_index += 1

        return color+marker+line

    def draw(self, bitrates, scores, curve_label):
        self.x_max = max(self.x_max, np.amax(bitrates))
        self.y_min = min(self.y_min, np.amin(scores))
        plt.plot(bitrates, scores, self.get_style(), label=str(curve_label))
        plt.legend()

    def finish(self):
        plt.axis([self.x_min, self.x_max+100, self.y_min-5, self.y_max])
        print ('y min=' +str(self.y_min))
        plt.grid()
        plt.show()

    def plot_rd_curve(self, rd_results_json_file):
        rd_results = None
        with open(rd_results_json_file, "r") as fp:
            rd_results = json.load(fp)
            fp.close()

            for result in rd_results:
                self.draw(result['bitrates'],
                          result['vmaf_scores'],
                          result['description'])
            self.finish()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='A Python script \
        to plot Rate Distortion curves')
    parser.add_argument('--file', required=True, help='Rate Distortion file')
    args = parser.parse_args()
    rd_plot = RDPlot('Test RD Curve')
    rd_plot.plot_rd_curve(args.file)
