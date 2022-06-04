#!/usr/bin/env bash

curl  https://media.xiph.org/video/derf/y4m/akiyo_qcif.y4m -o /tmp/akiyo_qcif.y4m
ffmpeg -y -i /tmp/akiyo_qcif.y4m  /tmp/akiyo_qcif.mp4
ffmpeg -y -i /tmp/akiyo_qcif.y4m -f rawvideo -pix_fmt nv21  /tmp/akiyo_qcif_nv21.yuv
ffmpeg -y -i /tmp/akiyo_qcif.y4m -f rawvideo -pix_fmt yuv420p  /tmp/akiyo_qcif.yuv
