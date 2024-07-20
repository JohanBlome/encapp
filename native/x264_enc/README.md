# x264 build
Set ndk path and build tools first, e.g.
```
# for MacOs
export HOST_TAG=darwin-x86_64
export NDK="/System/Volumes/Data/Users/XXX/Library/Android/sdk/ndk-bundle/"
```

Run build.sh

# Testing
The build library will be copied to /tmp/
Shared libraries in the codec name field will have similar behavior
as video files in the input section i.e. copied to device workdir

"
    configure {
        codec: "/tmp/libnativeencoder.so"
        bitrate: "500 kbps"
"

To verify run:
```
>python3 encapp.py run tests/bitrate_buffer_x264.pbtxt
```
