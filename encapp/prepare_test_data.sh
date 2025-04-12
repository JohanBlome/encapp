#!/usr/bin/env bash

curl https://media.xiph.org/video/derf/y4m/akiyo_qcif.y4m -o /tmp/akiyo_qcif.y4m
ffmpeg -y -i /tmp/akiyo_qcif.y4m -f rawvideo -pix_fmt yuv420p /tmp/akiyo_qcif.yuv
ffmpeg -y -i /tmp/akiyo_qcif.y4m -c:v libx264 -bf 0 /tmp/akiyo_qcif.mp4
ffmpeg -y -i /tmp/akiyo_qcif.mp4 -f rawvideo -pix_fmt p010le -s 192x160 /tmp/akiyo_192x160.p010le.yuv

