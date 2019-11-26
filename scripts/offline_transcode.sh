#!/bin/bash

#-----------------------------------------------------------------------
#
# The script will create several directories with the result in the
# 'output' directory. A working installation of ffmpeg/ffprobe is
# also needed.
#
# Include this in the test script (e.g. 'source offline_transcode.sh')
# and override suitable parameters
#-----------------------------------------------------------------------


mime_hevc='omx.qcom.video.encoder.hevc'
mime_vp8='omx.qcom.video.encoder.vp8'
mime_avc='omx.qcom.video.encoder.avc'
time=$(date +'%F_%H-%M-%S')


# Settings
resolution="" #The resolution of the source - 1280x720
codecs="" #codecs to test - 'h265,vp9'
modes="" #vbr or cbr - 'cbr, vbr'
resolutions="" #resolutions to test - '1280x720, 960x540'
bitrates="" #bitrates to test - '200,500,2500'
i_intervals="" #key frame interval - '2,10,60'
dynamic="" #Used to create dynamic changes
hierplayers="" #
test_length="" #test length in secs
rawfile="" #raw file to be used
extra_descr="" #A description used to name the directory to place data
#Some codecs need nv12 and some yuv420p
pix_fmt="nv12"
fps='30'

#dynamic parameters
#key-x request key frame at x
#fps-x-y request fps at rate y from frame x
#bit-x-y reuqest bitrate y from frame x
#ltrm-x-y request ltr frame x to be marked with id y
#ltru-x-y request that frame x use ltr mark id y

#Examples:
#First segment at 0, second at 254
#Set keyframes at 0, 254, 847, 1379 and 1644
#dynamic="key-0-0:key-254-0:key-847-0:key-1164-0:key-1379-0:key-1644-0"

#Dynamic ltr with only one singel (potential) iframe
#dynamic="ltrm-0-3:ltrm-90-4:ltru-180-3"

#Dynamic ltr with only two iframes
#  dynamic="ltrm-10-1:ltru-11-1:key-30-0:ltrm-60-0:ltru-61-0:ltrm-90-0:ltru-91-0"

function find_files {
  file_list=$(adb shell ls /sdcard/ | grep -E ".*_[0-9]+fps_[0-9]+x[0-9]+_[0-9]+bps_iint[0-9]+_m[0-9]\." | grep -E "\.mp4$|\.webm")
}

function collect_data {
  find_files
  for file in $file_list; do
     adb shell rm "/sdcard/${file}"
  done
    echo"" > silent.log
    device_path="/sdcard/${rawfile##*/}"
    if [ "${extra_desc}" ] ; then
        workdir="${extra_descr}_${time}"
    else
        workdir="${extra_descr}_${time}"
    fi
    echo "*****"
    echo "${workdir}"
    mkdir "${workdir}"
    video_path="${workdir}/video"
    tmp_path="${workdir}/tmp"
    output_path="${workdir}/output"

    adb push ${rawfile} ${device_path}

    gen="-e file ${device_path} -e test_timeout 20 "
    gen+="-e video_timeout ${test_length} "
    gen+="-e res ${resolutions} "
    gen+="-e ref_res ${raw_resolution} "
    gen+="-e bit ${bitrates} "
    gen+="-e fps ${fps} "
    gen+="-e enc ${codecs} "
    gen+="-e modl ${modes} "
    gen+="-e key ${i_intervals} "
    gen+="-e skfr false "
    gen+="-e debug false "
    args=" -w -r ${gen} -e ltrc 1"

    if [[ $vbr_cbr == "cbr" ]] ; then
        args="${args} -e mode cbr "
    fi

    if [ ${dynamic} ] ; then
        args="${args} -e dyn ${dynamic}"
    fi

    if [ ${hierplayers} ] ; then
        args="${args} -e hierl ${hierplayers}"
    fi

    echo "adb shell am instrument ${args} -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner"
    adb shell am instrument $args -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner >> silent.log

    find_files
    nbr_files=$(echo $file_list | awk '{print NF}')
    echo "- Number of files transcoded: $nbr_files - "
    mkdir ${video_path}
    for file in $file_list; do
        adb pull "/sdcard/${file}" ${video_path}/.
    done

    echo " - done -"
}
