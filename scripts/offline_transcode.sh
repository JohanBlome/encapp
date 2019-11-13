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
time=$(date +'%F_%T')


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


function collect_data {
	IFS=',' read -ra resolutions_b <<< "${resolutions}"
	IFS=',' read -ra i_intervals_b <<< "${i_intervals}"
	IFS=',' read -ra modes_b <<< "${modes}"
	IFS=',' read -ra codecs_b <<< "${codecs}"
	adb shell rm /sdcard/omx.*

	echo"" > silent.log
	device_path='/sdcard/'
	for encoding in "${codecs_b[@]}"; do
		for vbr_cbr in "${modes_b[@]}"; do
			for i_int in "${i_intervals_b[@]}"; do
				if [ "${extra_desc}" ] ; then
					workdir="${extra_descr}_${encoding}_${vbr_cbr}_${time}_${i_int}s"
				else
					workdir="${extra_descr}_${encoding}_${vbr_cbr}_${time}_${i_int}s"
				fi
				echo "*****"

				mkdir "${workdir}"
				video_path="${workdir}/video"
				tmp_path="${workdir}/tmp"
				output_path="${workdir}/output"

				for loc_res in "${resolutions_b[@]}"; do
					if [[ $loc_res == $raw_resolution ]]; then
						if [[ $rawfile == *.mp4 ]]; then
							ref="${device_path}ref.mp4"
							adb push $rawfile ${ref}
						else
							ref="${device_path}ref.yuv"
							adb push $rawfile ${ref}
						fi
					else
						echo "Do resize"
						if [[ $rawfile == *.mp4 ]]; then
							ffmpeg -nostats -loglevel 0 -y -i ${rawfile} -s ${loc_res} \
									resized.yuv
							ref="${device_path}ref.mp4"
							adb push resized.yuv ${ref}
						else
							ffmpeg -nostats -loglevel 0 -y -f rawvideo -pix_fmt nv12 \
									-s ${raw_resolution} -framerate 30 -i ${rawfile} \
									-f rawvideo -pix_fmt nv12 -s ${loc_res} \
									-framerate 30 resized.yuv
							ref="${device_path}ref.yuv"
							adb push resized.yuv ${ref}
						fi
					fi
					gen="-e file ${ref} -e test_timeout 20 -e video_timeout ${test_length} -e res ${loc_res} -e resl ${loc_res} -e bitl ${bitrates} -e skfr false -e debug false"
					args=" -w -r -e key ${i_int} -e encl ${encoding} ${gen} -e ltrc 1"
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

				done
        file_list=$(adb shell ls /sdcard/omx.*)
				nbr_files=$(echo $file_list | awk '{print NF}')
				echo "- Number of files transcoded: $nbr_files - "
        mkdir ${video_path}
        for file in $file_list; do
           echo "Pull $file"
				   adb pull $file ${video_path}/.
        done
			done
		done
	done
	#rm resized.yuv
	#adb shell rm /sdcard/ref.yuv
	echo " - done -"
}
