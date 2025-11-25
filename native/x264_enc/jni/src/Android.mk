LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# Set the name of the static library
LOCAL_MODULE := libx264

# List the source files
LOCAL_SRC_FILES :=  ../../../../modules/x264/libx264.a

include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)

# Set the name of the shared library
LOCAL_MODULE := nativeencoder
LOCAL_CFLAGS := -g -O3 -fexceptions -fPIC ${EXTERNAL_CFLAGS} #-DDEBUG
LOCAL_CFLAGS += -v
# List JNI wrapper source files
LOCAL_SRC_FILES = x264_enc.cpp

# Include header files from the static library
LOCAL_C_INCLUDES += $(LOCAL_PATH)
LOCAL_C_INCLUDES += ../../../modules/x264/

# Link against the static library
LOCAL_STATIC_LIBRARIES += libx264

LOCAL_LDLIBS += \
    -ldl -llog -landroid -lz -lm

include $(BUILD_SHARED_LIBRARY)
