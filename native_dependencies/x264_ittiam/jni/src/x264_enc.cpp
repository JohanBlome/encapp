
#include <cstdint>
#include <float.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>


#include <android/log.h>
#include <inttypes.h>
#include <jni.h>
#include <string.h>
#include <stdlib.h>

#include "x264.h"
#ifdef DEBUG
#define LOGD(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.native", __VA_ARGS__)
#else
#define LOGD(...) ""
#endif

#define LOGI(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.native", __VA_ARGS__)

#define LOGE(...) \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.native, error: ", __VA_ARGS__)






extern "C" {

x264_t *encoder = NULL;

x264_nal_t *nal;
int nnal;


jint init_simple(JNIEnv *env, jobject thiz, jobjectArray params, jint width, jint height, jint colourSpace, jint bitDepth) {
    assert(encoder == NULL);
    x264_param_t x264Params;
    int size_of_headers;

    if (encoder) {
        return false;
    }

    jsize len = env->GetArrayLength(params);
    jclass parameterClass = env->FindClass("com/facebook/encapp/proto/Parameter");
    jmethodID get_key = env->GetMethodID(parameterClass, "getKey", "()Ljava/lang/String;");
    jmethodID get_type = env->GetMethodID(parameterClass, "getType", "()Lcom/facebook/encapp/proto/DataValueType;");
    jmethodID get_value = env->GetMethodID(parameterClass, "getValue", "()Ljava/lang/String;");

    int index = 0;
    for (index = 0; index < len; index++) {
        jobject param = env->GetObjectArrayElement(params, index);
        jstring key_jstr = (jstring) env->CallObjectMethod(param, get_key);
        const char *key_char = env->GetStringUTFChars(key_jstr, 0);
        
        jstring value_jstr = (jstring) env->CallObjectMethod(param, get_value);
        const char *value_char = env->GetStringUTFChars(value_jstr, 0);
       
        int status = x264_param_parse(&x264Params, key_char, value_char);

        if (status) {
            LOGE("Faild to set %s with value %s", key_char, value_char);
        } else {
            LOGI("Set %s with value %s", key_char, value_char);
        }
        env->ReleaseStringUTFChars(key_jstr, key_char);
        env->ReleaseStringUTFChars(value_jstr, value_char);
    }

    // Mapping to x264 structure members
    x264Params.i_threads = 1;
    x264Params.i_width = width;
    x264Params.i_height = height;
    x264Params.i_csp = colourSpace;
    x264Params.i_bitdepth = bitDepth;

    encoder = x264_encoder_open(&x264Params);
    if(!encoder)
    {
        LOGI("Failed encoder_open");
        return -1;
    }

    x264_encoder_parameters(encoder, &x264Params);
    return 0;

}



jint get_header(JNIEnv *env, jobject thiz, jbyteArray headerArray) {
    if (!encoder) {
        return -1;
    }


    int size_of_headers = x264_encoder_headers(encoder, &nal, &nnal);
    int offset = 0;
    if (headerArray != nullptr) {
        jsize headerArraySize = env->GetArrayLength(headerArray);
        jbyte* headerArrayBuffer = env->GetByteArrayElements(headerArray, NULL);

        for (int i = 0; i < nnal; i++) {
            if (nal[i].i_type == NAL_SPS || nal[i].i_type == NAL_PPS || nal[i].i_type == NAL_SEI ||
                nal[i].i_type == NAL_AUD || nal[i].i_type == NAL_FILLER) {
                // Check if there is enough space in the header array
                if (offset + nal[i].i_payload > headerArraySize) {
                    env->ReleaseByteArrayElements(headerArray, headerArrayBuffer, 0);
                    return -1;
                }

                memcpy(headerArrayBuffer + offset, nal[i].p_payload, nal[i].i_payload);
                offset += nal[i].i_payload;
            }
        }
        env->ReleaseByteArrayElements(headerArray, headerArrayBuffer, 0);
    }
    return size_of_headers;
}


jint encode_frame(JNIEnv *env, jobject thiz, jbyteArray input, jbyteArray output, jint width, jint height, jint colourSpace) {
    LOGI("Encoding frame");
    if (!encoder) {
        LOGI("Encoder is not initialized for encoding");
        return -1;
    }

    jbyte* input_data = (jbyte*)env->GetPrimitiveArrayCritical(input, 0);
    jbyte* output_data = (jbyte*)env->GetPrimitiveArrayCritical(output, 0);

    x264_picture_t pic_in = {0};
    x264_picture_t pic_out = {0};

    // TODO: We are assuming yuv420p, add check...
    int ySize = width * height; //Stride?
    int uvSize = (int)(ySize/4.0f);

    x264_picture_init(&pic_in);
    pic_in.img.i_csp = colourSpace;
    pic_in.img.i_plane = 3;

    //Assume memory pinned, no copying
    pic_in.img.plane[0] = (uint8_t*)input_data;
    pic_in.img.plane[1] = (uint8_t*)(input_data + ySize);
    pic_in.img.plane[2] = (uint8_t*)(input_data + ySize + uvSize);

    pic_in.img.i_stride[0] = width;
    pic_in.img.i_stride[1] = width / 2;
    pic_in.img.i_stride[2] = width / 2;


    int frame_size = x264_encoder_encode(encoder, &nal, &nnal, &pic_in, &pic_out);

    if (frame_size >= 0) {
        // TODO: Added total_size = 2 for debugging purpose
        int total_size = 2;
        for (int i = 0; i < nnal; i++) {
            total_size += nal[i].i_payload;
        }
        int offset = 2;
        for (int i = 0; i < nnal; i++) {
            if (nal[i].i_type == NAL_SPS || nal[i].i_type == NAL_PPS || nal[i].i_type == NAL_SEI ||
            nal[i].i_type == NAL_AUD || nal[i].i_type == NAL_FILLER) {
                continue;
            }
            memcpy(output_data + offset, nal[i].p_payload, nal[i].i_payload);
            offset += nal[i].i_payload;
        }
        frame_size = total_size;
    }


    env->ReleasePrimitiveArrayCritical(input, input_data, 0);
    env->ReleasePrimitiveArrayCritical(output, output_data, 0);

    return frame_size;
}


void close(JNIEnv *env, jobject thiz) {
    LOGI("Closing encoder");
    if (encoder) {
        x264_encoder_close(encoder);
        encoder = NULL;
    }    
}



static JNINativeMethod methods[] = {
    {"init",      "([Lcom/facebook/encapp/proto/Parameter;III)I",     (void *)&init_simple},
    {"getHeader", "([B)I",                                         (void *)&get_header},
    {"encode",    "([B[BIII)I",                                    (void *)&encode_frame},
    {"close",     "()V",                                           (void *)&close},
};



jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass("com/facebook/encapp/utils/SwLibEncoder");
    if (clazz == NULL) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
}

