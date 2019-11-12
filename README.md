# encapp
Easy way to test video encoders in Android in large scale.

Facilitates an encoding mechanism for a large number of combinations in regards to
- codecs
- bitrate
- framerat
- i-frame interval
- coding mode

I also have support for dynamic changes with fps, bitrate, and ltr.
This is described in 'scripts/offline_transcoding.sh'.

PREREQUISITES
- adb connection
- ffmpeg with decoding support for the codecs to be tested
- android sdk setup and environment variables set
- android ndk

The android ndk path need to be set in local.properties.
This file should not be updated in the repo.


RUNNING:
The tool will create a number of sub directories below the active one
Calling the setup script will build and install the test framework on
the connected android device


> source '.../rtcat/tool/codecs_offline_validation_tool/setup.sh'

Copy the run_test.sh to your local directory and edit it
The example is using some command line arguments i.e.

> bash local_copy.sh original.yuv 1280x720 xx_seconds functional_description

But those arguments could be set in the scipts aswell (use bash not sh).
