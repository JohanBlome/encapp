LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_LDFLAGS += -L$(LOCAL_PATH)/lib
LOCAL_LDLIBS :=  -llog

LOCAL_MODULE    := libvq-encapp
LOCAL_SRC_FILES := readdata.cpp

LOCAL_C_INCLUDES := $(LOCAL_PATH)/include  \

LOCAL_CPPFLAGS := -std=c++11 -Wall -Wno-unused-parameter -Wno-unused-variable -fexceptions

include $(BUILD_SHARED_LIBRARY)
