// Copyright 2004-present Facebook. All Rights Reserved.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <errno.h>
#include <stdio.h>
#include <android/trace.h>

#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, "Aloha", __VA_ARGS__)


static const char *filename = "/sdcard/ref.yuv";
FILE *dataFile = NULL;

JNIEXPORT void JNICALL openFile(JNIEnv *env, jobject obj_this,  jstring javaString) {
    const char *fileName = env->GetStringUTFChars(javaString, 0);

    LOGD("Open ref file...%s ", fileName);
    dataFile = fopen(filename, "r");
    LOGD("...OK? %p", dataFile);

    if (dataFile == NULL) {
        LOGD("Failed to open the reference file, error:%s\n", strerror(errno));
    }

    env->ReleaseStringUTFChars(javaString, fileName);
}
JNIEXPORT void JNICALL closeFile() {
    LOGD("Close ref file");
    fclose(dataFile);
}

JNIEXPORT jint JNICALL fillBuffer(JNIEnv *env, jobject obj_this, jobject outputData, jint size) {
    (void) obj_this;
    size_t read = 0;

    uint8_t *outputBuffer = 0;

    outputBuffer = (uint8_t *) env->GetDirectBufferAddress(outputData);

    if (dataFile == NULL) {
       LOGD("Forgot to open file?");
        goto out;
    }


    read = fread(outputBuffer, sizeof(char), size, dataFile);
    out:
    return read;
}

static const JNINativeMethod methods[] = {
        {"nativeFillBuffer", "(Ljava/nio/ByteBuffer;I)I", (void *) fillBuffer},
        {"nativeOpenFile", "(Ljava/lang/String;)V", (void *) openFile},
        {"nativeCloseFile", "()V", (void *) closeFile},
};

jclass g_Transcoder;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {

    JNIEnv *env = 0L;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGD("Failed to get environment");
        return -1;
    }

    int l_iGetJvmStatus = env->GetJavaVM(&vm);
    g_Transcoder = (jclass) env->NewGlobalRef(
            env->FindClass("com/facebook/vq/encapp/Transcoder"));
    env->RegisterNatives(g_Transcoder, methods, sizeof(methods) / sizeof(methods[0]));

    LOGD("Classes registred? %p", g_Transcoder);
    return JNI_VERSION_1_4;
}
