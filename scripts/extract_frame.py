#!/usr/bin/python
"""Extract specified video frames
"""

import argparse
import csv
import cv2
import math
import numpy as np
import os
import re
import sys
import tempfile
import timeit
import pandas as pd
import os

def get_options(argv):
    description = f"Extract frames"
    parser = argparse.ArgumentParser(
        description=description,
        formatter_class=argparse.RawTextHelpFormatter,
    )
    parser.add_argument("-s", "--reference", default="")
    parser.add_argument("-r", "--encoded", default="Normally the encoded file will be scaled to the reference")
    parser.add_argument("-x", "--extract", default="", help="Extract frames. Format: frame1-frame2,frame3,frame4")


    options = parser.parse_args()
    return options

def parse_range(range_str):
    limits = [frame for frame in range_str.split("-")]
    print(limits)
    if len(limits) == 1:
        return [int(limits[0])]
    else:
        return range(int(limits[0]), int(limits[1])+1)


def extract_frame(cap, frame):
    cap.set(cv2.CAP_PROP_POS_FRAMES, frame)
    status, img = cap.read()
    if status:
        return img
    return None


def extra_frames(encoded, reference, extract):
   
    frames = [frame for descr in extract.split(",") for frame in parse_range(descr)]
    print(f"Extracting frames: {frames}")

    cap_enc = cv2.VideoCapture(encoded)

    for frame in frames:
        img_enc = extract_frame(cap_encoded, frame)
        if img is not None:
               
            #TODO: rotate option
            img_enc = cv2.rotate(img_enc, cv2.ROTATE_90_COUNTERCLOCKWISE)
            cv2.imwrite(
                    f"{encoded}.fr{frame}.{vtype}.{value}.{img.shape[1]}x{img.shape[0]}.png",
                img,
            )
    cap.release()


    cap = cv2.VideoCapture(y4m)
    cap.set(cv2.CAP_PROP_POS_FRAMES, frame)
    status, ref_img = cap.read()
    if status:
        ref_img = cv2.rotate(ref_img, cv2.ROTATE_90_COUNTERCLOCKWISE)
        cv2.imwrite(
            f"{filename}.{bitrate}bps.fr{frame}.reference.{img.shape[1]}x{img.shape[0]}.png",
            img,
        )
    else:
        print("Failed to capture ref")
        exit(0)
    cap.release()

    # create combined image
    # Scale up/down transcoded

    
    print(f"Concat: {ref_img.shape=} -> {img.shape=}, {bitrate=}")
    img = cv2.resize(img, (ref_img.shape[1], ref_img.shape[0]), interpolation = cv2.INTER_CUBIC)
    combined = cv2.hconcat([ref_img, img])
    ref_w = ref_img.shape[1]
    ref_h = ref_img.shape[0]
    print(f"{ref_w}x{ref_h}, {img.shape=}")
    outfile = f"{filename}.{bitrate}bps.fr{frame}.{vtype}.{value}.combined.{ref_w}x{ref_h}.png"
    print(f"Outfile = {outfile}")
    cv2.imwrite(
        outfile,
        combined,
    )
    '''


def main(argv):
    # parse options
    options = get_options(argv) 

    # extract frames
    extra_frames(options.encoded, options.reference, options.extract)


if __name__ == "__main__":
    # at least the CLI program name: (CLI) execution
    main(sys.argv)


