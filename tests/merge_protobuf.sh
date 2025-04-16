#! /usr/bin/env bash

printf "** Testing merge of pbtxt files **\n"
cdir=$(dirname "$0")
python3 ${cdir}/../encapp/encapp.py run ${cdir}/surface_transcoder.pbtxt ${cdir}/output_name.pbtxt -w /tmp/colortest.original
printf "\nAdding color1.pbtxt containing updated settings for color encoding\n"
python3 ${cdir}/../encapp/encapp.py run ${cdir}/surface_transcoder.pbtxt ${cdir}/output_name.pbtxt ${cdir}/color1.pbtxt -w /tmp/colortest.color1

printf "\n\n * Original color information *\n"
ffprobe /tmp/colortest.original/akiyo_qcif.default.transcode.mp4 2>&1 | grep -iEo "yuv420p\([0-9a-zA-Z, -\/]*\)"
printf "\n * Updated color information *\n"
ffprobe /tmp/colortest.color1/akiyo_qcif.default.transcode.mp4 2>&1 | grep -iEo "yuvj420p\([0-9a-zA-Z, -\/]*\)"

