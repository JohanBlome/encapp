TOP_LOCAL_PATH := $(call my-dir)

#
# Build Jni library
#

LOCAL_PATH:= $(TOP_LOCAL_PATH)
include $(CLEAR_VARS)

PRIVATE_JNI_SHARED_LIBRARIES_ABI := armeabi-v7a
PRIVATE_JNI_SHARED_LIBRARIES := libcodec-validate-core
LOCAL_PACKAGE_NAME := Encapp
LOCAL_CERTIFICATE := platform
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, app/src/main/java)
LOCAL_STATIC_JAVA_LIBRARIES := lib-camera-common
LOCAL_MANIFEST_FILE := app/src/main/AndroidManifest.xml
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/app/src/main/res

LOCAL_AAPT_FLAGS := --auto-add-overlay \
    --extra-packages android.support.compat \
    --extra-packages android.support.v7.appcompat \
    --extra-packages android.support.v7.recyclerview

LOCAL_RESOURCE_DIR += \
    frameworks/support/compat/res \
    frameworks/support/v7/appcompat/res \
    frameworks/support/v7/recyclerview/res

LOCAL_STATIC_JAVA_LIBRARIES += \
    android-support-v4 \
    android-support-annotations \
    android-support-compat \
    android-support-core-ui \
    android-support-v7-appcompat \
    android-support-v7-recyclerview

include $(BUILD_PACKAGE)

include $(CLEAR_VARS)

LOCAL_LDFLAGS += -L$(LOCAL_PATH)/lib
LOCAL_LDLIBS :=  -llog

LOCAL_MODULE    := libcodec-validate-core
LOCAL_SRC_FILES := $(addprefix app/src/main/jni/, CodecValidateCommon.cpp \
	SsimTask.cpp \
	ImageUtils.cpp)

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CodecValidateInstrumentedTest
LOCAL_MODULE_TAGS := tests optional
LOCAL_SRC_FILES := $(call all-java-files-under, app/src/androidTest/java)
LOCAL_MANIFEST_FILE := app/src/androidTest/AndroidManifest.xml
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_INSTRUMENTATION_FOR := CodecValidateApp
LOCAL_JAVA_LIBRARIES := android.test.runner
LOCAL_STATIC_JAVA_LIBRARIES := \
		junit \
		legacy-android-test \
		android-support-test \
    ub-uiautomator \
		uiautomator-instrumentation

TARGET_OUT_DATA_APPS_PRIVILEGED := $(TARGET_OUT_DATA_NATIVE_TESTS)
include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
