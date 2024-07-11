#include <jni.h>
#include <android/log.h>
#include "x264.h"

#ifndef JNIx264
#define JNIx264

class X264Encoder {
public:
    static X264Encoder& getInstance();

    static int init(JNIEnv *env, jobject thisObj,
                    jobject x264ConfigParamsObj, int width, int height,
                    int colourSpace, int bitDepth, jbyteArray headerArray);

    int encode(JNIEnv *env, jobject thisObj, jbyteArray yBuffer, jbyteArray uBuffer, jbyteArray vBuffer,
               jbyteArray outBuffer, jint width, jint height, jint colourSpace);
    
    int init_simple(JNIEnv *env, jobject thisObj, jobjectArray params, jint width, jint height, jint colourSpace, jint bitDepth);
    int encode_simple(jbyte *input, jbyte *output, int width, int height, int colourSpace);
    int get_header(JNIEnv *env, jobject thiz, jbyteArray headerArray);

    //int encode_simple(jbyte[] input, jbyte[] output, int width, int height, int colourSpace);
    void close();

    x264_t *encoder;
    x264_nal_t *nal;
    int nnal;

private:
    X264Encoder();
    ~X264Encoder();

    X264Encoder(const X264Encoder&) = delete;
    X264Encoder& operator=(const X264Encoder&) = delete;

    static X264Encoder* x264encoder;
};

#ifdef __cplusplus
extern "C" {
#endif

#define LOG_TAG "x264-encoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

    JNIEXPORT jint Java_com_facebook_encapp_BufferX264Encoder_x264Init(JNIEnv *env, jobject thisObj,
                                                                       jobject x264ConfigParamsObj, int width, int height,
                                                                       int colourSpace, int bitDepth, jbyteArray headerArray);

    JNIEXPORT jint Java_com_facebook_encapp_BufferX264Encoder_x264Encode(JNIEnv *env, jobject thisObj, jbyteArray yBuffer,
                                                                         jbyteArray uBuffer, jbyteArray vBuffer,
                                                                         jbyteArray outBuffer, jint width, jint height, jint colourSpace);

    JNIEXPORT void Java_com_facebook_encapp_BufferX264Encoder_x264Close(JNIEnv *env, jobject thisObj);

#ifdef __cplusplus
}
#endif
#endif
