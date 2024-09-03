LOCAL_PATH := $(call my-dir)


include $(CLEAR_VARS)

# Set the name of the shared library
LOCAL_MODULE := encodeyuv
LOCAL_CFLAGS := -g -O3 -fexceptions -fPIC ${EXTERNAL_CFLAGS} #-DDEBUG

# List JNI wrapper source files
LOCAL_SRC_FILES = encodeyuv_enc.cpp

# Include header files from the static library
LOCAL_C_INCLUDES += $(LOCAL_PATH)

LOCAL_LDLIBS += \
    -ldl -llog -landroid -lz -lm

include $(BUILD_SHARED_LIBRARY)
