# x264 build
Set ndk path and build tools first, e.g.
```
# for MacOs
export HOST_TAG=darwin-x86_64
export NDK="/System/Volumes/Data/Users/XXX/Library/Android/sdk/ndk-bundle/"
```

Run script
```
./build.sh 
```
Optional: Run with `--clean` to clean build (executes `make clean`).


# Testing
The build library will be copied to lcevc/x264/libs

```
python3 encapp.py run tests/lcevc_x264.pbtxt --lcevc
```
