LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# Set the name of the static library
LOCAL_MODULE := libx264

# List the source files
LOCAL_SRC_FILES := $(LOCAL_PATH)/libx264.a

include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)

# Set the name of the shared library
LOCAL_MODULE := nativeencoder
LOCAL_CFLAGS := -g -O3 -fexceptions -fPIC -DDEBUG

# List JNI wrapper source files
LOCAL_SRC_FILES = x264_enc.cpp

# Include header files from the static library
LOCAL_C_INCLUDES += $(LOCAL_PATH)

# Link against the static library
LOCAL_STATIC_LIBRARIES += libx264

LOCAL_LDLIBS += \
    -ldl -llog -landroid -lz -lm

include $(BUILD_SHARED_LIBRARY)
