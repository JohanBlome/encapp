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
./gradlew clean

#check ndk path
ndkpath=$(cat local.properties | grep -E 'ndk.dir' | grep -Eo '\/+*\/+.*')
if [[ ! $ndkpath ]]; then
	echo "ndk.dir is missing in local.properties"
	cd $currentDir
	return
else
	echo "ndk.dir=${ndkpath}"
fi
./gradlew build
./gradlew installDebugAndroidTest
./gradlew installDebug
cd $currentDir
#Run a list of codecs so user can set permissions
adb shell am instrument  -w -r -e list_codecs a -e class com.facebook.encapp.CodecValidationInstrumentedTest com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner

