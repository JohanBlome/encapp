#!/bin/bash
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/$HOST_TAG

export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export CXX=$TOOLCHAIN/bin/aarch64-linux-android21-clang++

cdir=$(pwd)
cd ../../modules/x264
function build_arm64-v8a
{
  ./configure \
  --prefix=./android/arm64-v8a \
  --enable-static \
  --enable-pic \
  --disable-asm \
  --disable-opencl \
  --disable-cli \
  --host=aarch64-linux \
  --cross-prefix=$TOOLCHAIN/bin/aarch64-linux-android- \
  --sysroot=$TOOLCHAIN/sysroot \

  make clean
  make
  make install
}

build_arm64-v8a
cd ${cdir}
echo build_arm64-v8a finished
# build encapp wrapper
make all

# for testing
cp libs/arm64-v8a/libnativeencoder.so /tmp/
