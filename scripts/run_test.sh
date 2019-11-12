#!/bin/bash

source offline_transcode.sh

#-----------------------------------------------------------------------
#
# The script will create several directories with the result in the
# 'output' directory. A working installation of ffmpeg/ffprobe is
# also needed.
#
# Arguments, {choice}, [optional]
# input resolution length(in sec) output-name
# example: ./run_test.sh input.yuv 1280x720 60 ltr-single-iframe
#
#-----------------------------------------------------------------------

#static parameters
#'h265,vp8,h264'
codecs='h265,vp8'
resolutions='1280x720'
#,960x540,640x480'
#bitrates='200,300,400,500,1000,1500,2000,2500'
#bitrates='100,200,300,400,500,600,700,800,900,1000,1100,1200,1300,1400,1500,1600,1700,1800,1900,2000,2100,2200,2300,2400,2500'
bitrates='100,1000,2500'
#i_intervals='2,5,10,60'
i_intervals='10,60'
modes='cbr,vbr'
#hierplayers=5

#Dynamic change fps at frame 60, frame 120, frame 180 and frame 240
#dynamic="fps-60-15:fps-120-30:fps-180-5:fps-240-30"
#Dynamic change bitrate at frame 100 and frame 200
#dynamic="bit-100-500:bit-200-2000:"
#dynamic="bit-1-1200:bit-20-1000:bit-301-1200:bit-320-1000:"

rawfile=$1
raw_resolution=$2
test_length=$3
extra_descr=$4

collect_data
