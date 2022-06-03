#!/usr/bin/env python3

"""A Python script to plot VMAF scores of every frame. Reads the json
file output from ffmpeg libvmaf with json format.
"""
import matplotlib.pyplot as plt
import argparse
import json
import os
import numpy as np


class VMAFPlot:
    def __init__(self):
        self.curve_index = 0
        self.colors = ['b', 'g', 'r', 'c', 'm', 'y', 'k']
        self.markers = [
            'o',
            'v',
            '^',
            '<',
            '>',
            '8',
            's',
            'p',
            '*',
            'h',
            'H',
            'D',
            'd',
            'P',
            'X',
        ]
        self.lines = ['-', '--', '-.']
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 100

    def get_style(self):
        index = self.curve_index
        color = self.colors[index % len(self.colors)]
        marker = self.markers[index % len(self.markers)]
        line = self.lines[index % len(self.lines)]
        self.curve_index += 1

        return color + marker + line

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
        plt.axis([self.x_min, self.x_max + 1, self.y_min - 5, self.y_max])
        plt.grid(True)
        plt.draw()

    def finish(self, fig_file):
        if fig_file is None:
            plt.show()
        else:
            plt.savefig(fig_file, format='png')

    def plot_rd_curve(self, vmaf_json, labels, fig_file):
        rd_results = None
        self.new_figure('VMAF')
        label_id = 0
        for file in vmaf_json:
            if labels is not None and len(labels) == len(vmaf_json):
                label = labels[label_id]
            else:
                label = file
            label_id += 1
            with open(file, 'r') as fp:
                rd_results = json.load(fp)
                fp.close()
                frame_nums = []
                vmaf_scores = []
                for frame in rd_results['frames']:
                    if 'VMAF_score' in frame:
                        val = frame['VMAF_score']
                    else:
                        val = frame['metrics']['vmaf']
                    vmaf_scores.append(val)
                    frame_nums.append(frame['frameNum'])
                self.draw(frame_nums, vmaf_scores, label)
        self.finish(fig_file)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('vfiles', nargs='+', help='VMAF Files', type=str)
    parser.add_argument('--labels', nargs='+', help='Curve labels', type=str)
    parser.add_argument(
        '--fig',
        help='Specify a file name to save figure',
        type=str)
    args = parser.parse_args()
    rd_plot = VMAFPlot()
    vmaf_files = []
    for file in args.vfiles:
        vmaf_files.append(file)
    if len(vmaf_files) > 0:
        rd_plot.plot_rd_curve(vmaf_files, args.labels, args.fig)
