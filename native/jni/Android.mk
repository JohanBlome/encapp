LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := simplescaler
LOCAL_SRC_FILES := simplescaler.cpp
LOCAL_CPPFLAGS := -std=gnu++0x -Wall -fPIE -fPIC 
LOCAL_LDLIBS := -L$(SYSROOT)/usr/lib -llog -fPIE -pie 

include $(BUILD_SHARED_LIBRARY) 
