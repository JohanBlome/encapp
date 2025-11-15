#!/bin/bash

# 1. Setup environments
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/$HOST_TAG
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export CXX=$TOOLCHAIN/bin/aarch64-linux-android21-clang++

# 2. Set paths to x264_enc submodule, jniLibs, shared lib output and v163 commit hash.
CURR_DIR=$(pwd)
MODULES_DIR=$CURR_DIR/modules/x264
JNI_LIBS_DIR=$CURR_DIR/app/src/main/jniLibs
OUTPUT_DIR=$MODULES_DIR/android/arm64-v8a
TARGET_COMMIT=5db6aa6c

# 3. Navigate to modules/x264_enc, update gitmodule and switch to HEAD with v163
cd $MODULES_DIR
echo "Updating x264 submodule..."

# initialize the submodule.
git submodule update --init

# Check if the submodule has any commits
if ! git rev-parse HEAD &>/dev/null; then
    echo "x264 submodule is empty. Cloning the correct commit..."
    git fetch origin --quiet || { echo "Failed to fetch x264 submodule."; exit 1; }
fi

# Store the current HEAD of the submodule
CURRENT_HEAD=$(git rev-parse HEAD)

git checkout $TARGET_COMMIT --quiet || { echo "Failed to switch to commit $TARGET_COMMIT"; exit 1; }

# 4. Build shared x264_enc library.
function build_libx264_arm64-v8a
{
  ./configure \
  --prefix=$OUTPUT_DIR \
  --enable-shared \
  --enable-pic \
  --disable-asm \
  --disable-opencl \
  --disable-cli \
  --host=aarch64-linux \
  --cross-prefix=$TOOLCHAIN/bin/aarch64-linux-android- \
  --sysroot=$TOOLCHAIN/sysroot \

  if [ "$1" == "--clean" ]; then
      echo "Flag '--clean' detected. Running 'make clean'..."
      make clean
  fi

  make
  make install
}
echo "Building libx264 version 163..."
build_libx264_arm64-v8a "$1"

# 5. Switch back to x264 version 164.
git checkout $CURRENT_HEAD --quiet || { echo "Failed to switch to commit $CURRENT_HEAD"; exit 1; }

# 6. Copy shared x264 library to lcevc/jniLibs to make accessible when building lcevc.
mkdir -p $CURR_DIR/app/src/lcevc/jniLibs/arm64-v8a
cp $OUTPUT_DIR/lib/libx264.so* $CURR_DIR/app/src/lcevc/jniLibs/arm64-v8a
