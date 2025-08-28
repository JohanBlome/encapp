#!/usr/bin/env python3

"""A Python script to plot VMAF scores of every frame. Reads the json
file output from ffmpeg libvmaf with json format.
"""
import matplotlib.pyplot as plt
import argparse
import json
import os
import numpy as np
import re


class BasePlot:
    def __init__(self):
        self.curve_index = 0
        self.colors = ["b", "g", "r", "c", "m", "y", "k"]
        self.markers = [
            "o",
            "v",
            "^",
            "<",
            ">",
            "8",
            "s",
            "p",
            "*",
            "h",
            "H",
            "D",
            "d",
            "P",
            "X",
        ]
        self.lines = ["-", "--", "-."]
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

    def draw(self, frame_nums, scores, curve_label, mean, minval):
        self.x_max = max(self.x_max, np.amax(frame_nums))
        self.y_min = min(self.y_min, np.amin(scores))
        p = plt.plot(frame_nums, scores, self.get_style(), label=str(curve_label))
        color = p[0].get_color()
        plt.axhline(mean, color=color)
        plt.axhline(minval, color=color)
        plt.legend()
        plt.axis([self.x_min, self.x_max + 1, self.y_min - 5, self.y_max])
        plt.grid(True)
        plt.draw()

    def finish(self, fig_file):
        if fig_file is None:
            plt.show()
        else:
            plt.savefig(fig_file, format="png")

    def plot_rd_curve(self, data, labels, fig_file):
        pass


class PSNRPlot(BasePlot):
    def new_figure(self, title):
        super().new_figure(title)
        plt.xlabel("Frame Number")
        plt.ylabel("PSNR Score")
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 100

    def parse_line(self, line):
        regex = r"^n:(?P<frame>[0-9]*) .*psnr_y:(?P<psnr_avg>[0-9.]*)"
        frame = -1
        score = -1

        m = re.search(regex, line)

        if m:
            return int(m.group("frame")), float(m.group("psnr_avg"))

        return frame, score

    def plot_rd_curve(self, data_files, labels, fig_file):
        self.new_figure("PSNR")
        label_id = 0
        for file in data_files:
            # n:1 mse_avg:19.58 mse_y:27.04 mse_u:6.43 mse_v:2.91 psnr_avg:35.21 psnr_y:33.81 psnr_u:40.05 psnr_v:43.49
            # n:2 mse_avg:22.34 mse_y:31.12 mse_u:6.63 mse_v:2.93 psnr_avg:34.64 psnr_y:33.20 psnr_u:39.92 psnr_v:43.46
            if labels is not None and len(labels) == len(data_files):
                label = labels[label_id]
            else:
                label = file
            label_id += 1
            with open(file, "r") as fp:
                frame_nums = []
                psnr_scores = []
                for line in fp.readlines():
                    frame, score = self.parse_line(line)
                    if frame > -1:
                        psnr_scores.append(score)
                        frame_nums.append(frame)

                average = np.mean(psnr_scores)
                minval = np.min(psnr_scores)
                maxval = np.max(psnr_scores)
                self.y_min = minval // 10 * 10
                self.y_max = (maxval // 10 + 1) * 10
                self.draw(
                    frame_nums,
                    psnr_scores,
                    label + f", avg: {average:.2f}, min: {minval:.2f}",
                    average,
                    minval,
                )

        self.finish(fig_file)


class SSIMPlot(BasePlot):
    def new_figure(self, title):
        super().new_figure(title)
        plt.xlabel("Frame Number")
        plt.ylabel("SSIM Score (x100)")
        self.x_min = 0
        self.x_max = 0
        self.y_min = 0
        self.y_max = 1

    def parse_line(self, line):

        # n:1 Y:0.938221 U:0.946291 V:0.970905 All:0.945013 (12.597431)
        # n:2 Y:0.933821 U:0.945150 V:0.970634 All:0.941844 (12.354085)
        regex = r"^n:(?P<frame>[0-9]*) .*All:(?P<ssim_avg>[0-9.]*)"
        frame = -1
        score = -1

        m = re.search(regex, line)

        if m:
            return int(m.group("frame")), float(m.group("ssim_avg"))

        return frame, score

    def plot_rd_curve(self, data_files, labels, fig_file):
        self.new_figure("SSIM (x100)")
        label_id = 0
        for file in data_files:
            if labels is not None and len(labels) == len(data_files):
                label = labels[label_id]
            else:
                label = file
            label_id += 1
            with open(file, "r") as fp:
                frame_nums = []
                ssim_scores = []
                for line in fp.readlines():
                    frame, score = self.parse_line(line)
                    if frame > -1:
                        ssim_scores.append(score * 100)
                        frame_nums.append(frame)

                average = np.mean(ssim_scores)
                minval = np.min(ssim_scores)
                maxval = np.max(ssim_scores)
                self.y_min = minval // 10 * 10
                self.y_max = (maxval // 10 + 1) * 10
                self.draw(
                    frame_nums,
                    ssim_scores,
                    label + f", avg: {average:.2f}, min: {minval:.2f}",
                    average,
                    minval,
                )

        self.finish(fig_file)


class VMAFPlot(BasePlot):
    def plot_rd_curve(self, vmaf_json, labels, fig_file):
        self.new_figure("VMAF")
        label_id = 0
        for file in vmaf_json:
            if labels is not None and len(labels) == len(vmaf_json):
                label = labels[label_id]
            else:
                label = file
            label_id += 1
            with open(file, "r") as fp:
                rd_results = json.load(fp)
                fp.close()
                frame_nums = []
                vmaf_scores = []
                for frame in rd_results["frames"]:
                    if "VMAF_score" in frame:
                        val = frame["VMAF_score"]
                    else:
                        val = frame["metrics"]["vmaf"]
                    vmaf_scores.append(val)
                    frame_nums.append(frame["frameNum"])

                # pooled mean
                average = rd_results["pooled_metrics"]["vmaf"]["mean"]
                minval = rd_results["pooled_metrics"]["vmaf"]["min"]
                self.draw(
                    frame_nums,
                    vmaf_scores,
                    label + f", avg: {average:.2f}, min: {minval:.2f}",
                    average,
                    minval,
                )

        self.finish(fig_file)

    def new_figure(self, title):
        super().new_figure(title)
        plt.xlabel("Frame Number")
        plt.ylabel("VMAF Score")
        self.x_min = 0
        self.x_max = 0
        self.y_min = 100
        self.y_max = 100


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vfiles", nargs="+", help="VMAF Files", type=str)
    parser.add_argument("--labels", nargs="+", help="Curve labels", type=str)
    parser.add_argument("--fig", help="Specify a file name to save figure", type=str)
    parser.add_argument(
        "--type",
        type=str,
        choices=["vmaf", "psnr", "ssim"],
        default="vmaf",
        help="Vmaf should use files with *.vmaf.json ending. Psnr should use *.psnr.all and ssim *.ssim.all",
    )

    args = parser.parse_args()
    rd_plot = None
    if args.type == "vmaf":
        rd_plot = VMAFPlot()
    elif args.type == "psnr":
        rd_plot = PSNRPlot()
    elif args.type == "ssim":
        rd_plot = SSIMPlot()

    data_files = []
    for file in args.vfiles:
        data_files.append(file)

    if len(data_files) > 0:
        rd_plot.plot_rd_curve(data_files, args.labels, args.fig)
