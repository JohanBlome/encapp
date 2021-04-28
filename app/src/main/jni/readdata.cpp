// Copyright 2004-present Facebook. All Rights Reserved.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <errno.h>
#include <stdio.h>
#include <android/trace.h>

#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, "encapp", __VA_ARGS__)




JNIEXPORT long JNICALL openFile(JNIEnv *env, jobject obj_this,  jstring javaString) {
    const char *fileName = env->GetStringUTFChars(javaString, 0);
    LOGD("opening input file: \"%s\"", fileName);
    FILE *dataFile = fopen(fileName, "r");
    if (dataFile == NULL) {
        LOGD("Failed to open the reference file, error: %s\n", strerror(errno));
        return 0;
    }

    env->ReleaseStringUTFChars(javaString, fileName);
    if (dataFile) {
        return (jlong)dataFile;
    } else {
        return 0;
    }
}

JNIEXPORT void JNICALL closeFile(JNIEnv *env, jobject obj_this, jlong fid) {
    FILE *dataFile = (FILE*)fid;
    LOGD("Close ref file: %ld", fid);
    if (dataFile != 0) {
        fclose(dataFile);
    }
}

JNIEXPORT jint JNICALL fillBuffer(JNIEnv *env, jobject obj_this, jobject outputData, jint size, jlong fid) {
    (void) obj_this;
    size_t read = 0;
    FILE *dataFile = (FILE*)fid;

    uint8_t *outputBuffer = 0;

    outputBuffer = (uint8_t *) env->GetDirectBufferAddress(outputData);
    LOGD("Fill buffer %ld", fid);
    if (dataFile == NULL) {
       LOGD("Forgot to open file?");
        goto out;
    }


    read = fread(outputBuffer, sizeof(char), size, dataFile);
    out:
    return read;
}

static const JNINativeMethod methods[] = {
        {"nativeFillBuffer", "(Ljava/nio/ByteBuffer;IJ)I", (void *) fillBuffer},
        {"nativeOpenFile", "(Ljava/lang/String;)J", (void *) openFile},
        {"nativeCloseFile", "(J)V", (void *) closeFile},
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
            env->FindClass("com/facebook/encapp/Transcoder"));
    env->RegisterNatives(g_Transcoder, methods, sizeof(methods) / sizeof(methods[0]));

    LOGD("Classes registred? %p", g_Transcoder);
    return JNI_VERSION_1_4;
}
