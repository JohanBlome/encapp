#!/usr/local/bin/python3

import matplotlib.pyplot as plt
import argparse
import json
import os
import numpy as np


class VMAFPlot:
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
        plt.xlabel('Frame Number')
        plt.ylabel('VMAF Score')
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 100

    def draw(self, frame_nums, scores, curve_label):
        self.x_max = max(self.x_max, np.amax(frame_nums))
        self.y_min = min(self.y_min, np.amin(scores))
        plt.plot(frame_nums, scores, self.get_style(), label=str(curve_label))
        plt.legend()

    def finish(self):
        plt.axis([self.x_min, self.x_max+1, self.y_min-5, self.y_max])
        plt.grid()
        plt.draw()

    def plot_rd_curve(self, rd_results_json_files):
        rd_results = None
        self.new_figure('VMAF')
        for file in rd_results_json_files:
            with open(file, "r") as fp:
                rd_results = json.load(fp)
                fp.close()
                frame_nums = []
                vmaf_scores = []
                for frame in rd_results['frames']:
                    vmaf_scores.append(frame['VMAF_score'])
                    frame_nums.append(frame['frameNum'])
                self.draw(frame_nums, vmaf_scores, file)
                self.finish()
        plt.show()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='A Python script to plot VMAF scores of every frame')
    parser.add_argument('--vfiles', required=True, nargs='+',
                        help='VMAF Files', type=str)
    args = parser.parse_args()
    rd_plot = VMAFPlot()
    vmaf_files = []
    for file in args.vfiles:
        vmaf_files.append(file)
    if len(vmaf_files) > 0:
        rd_plot.plot_rd_curve(vmaf_files)
