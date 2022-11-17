#!/usr/bin/env bash

curl https://media.xiph.org/video/derf/y4m/akiyo_qcif.y4m -o /tmp/akiyo_qcif.y4m
ffmpeg -y -i /tmp/akiyo_qcif.y4m -c:v libx264 -bf 0 /tmp/akiyo_qcif.mp4
