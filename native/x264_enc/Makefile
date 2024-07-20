clean:
	ndk-build -C jni clean

debug:
	make -C jni debug
	cp libs/arm64-v8a/libnativeencoder.so ../../app/src/main/jniLibs/arm64-v8a

all:
	make -C jni all
	cp libs/arm64-v8a/libnativeencoder.so ../../app/src/main/jniLibs/arm64-v8a
