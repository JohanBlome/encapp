#!/bin/bash

currentDir=$(pwd)
scriptDir=$(dirname "${BASH_SOURCE[0]}" )
echo $currentDir
export PATH="$PATH:${scriptDir}/scripts:$PATH:${scriptDir}"
export PYTHONPATH="$PYTHONPATH:${scriptDir}/scripts"
cd ${scriptDir}

# first clean up old code
./gradlew uninstallAll
./gradlew uninstallRelease
./gradlew uninstallDebugAndroidTest

#check ndk path
ndkpath=$(cat local.properties | grep -E 'ndk.dir' | grep -Eo '\/+*\/+.*')
if [[ ! $ndkpath ]]; then
	echo "ndk.dir is missing in local.properties"
	cd $currentDir
	return
else
	echo "ndk.dir=${ndkpath}"
fi
./gradlew installDebugAndroidTest
./gradlew installDebug
cd $currentDir
