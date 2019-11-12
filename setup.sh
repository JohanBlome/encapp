#!/bin/bash
currentDir=$(pwd)
scriptDir=$(dirname "${BASH_SOURCE[0]}" )
echo $currentDir
export VMAF_FOLDER="${scriptDir}/scripts/"
export PATH="$PATH:${scriptDir}/scripts:$PATH:${scriptDir}"
export PYTHONPATH="$PYTHONPATH:${scriptDir}/scrips"
cd ${scriptDir}
#check ndk path
ndkpath=$(cat local.properties | grep -E 'ndk.dir' | grep -Eo '\/+*\/+.*')
if [[ ! $ndkpath ]]; then
	echo "ndk.dir is missing in local.properties"
	cd $currentDir
	return
else
	echo "ndk.dir=${ndkpath}"
fi
./gradlew installDebug
cd $currentDir
