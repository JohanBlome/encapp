#!/usr/bin/env bash

curl https://media.xiph.org/video/derf/y4m/KristenAndSara_1280x720_60.y4m -o /tmp/kristen_and_sara.1280x720.60.y4m
ffmpeg -i /tmp/kristen_and_sara.1280x720.60.y4m -f rawvideo -pix_fmt yuv420p /tmp/kristen_and_sara.1280x720.60.yuv
ffmpeg -y -i /tmp/kristen_and_sara.1280x720.60.y4m -c:v libx264 -bf 0 /tmp/kristen_and_sara.1280x720.60.mp4
