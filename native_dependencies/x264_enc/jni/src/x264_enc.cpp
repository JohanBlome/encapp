
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


jint init_encoder(JNIEnv *env, jobject thiz, jobjectArray params, jint width, jint height, jint colorSpace, jint bitDepth) {
    LOGD("*** init_encoder, %d, %d, %d, %d ***", width, height, colorSpace, bitDepth);
    assert(encoder == NULL);
    x264_param_t x264Params;
    int size_of_headers;

    if (encoder) {
        LOGE("Encoder already created");
        return false;
    }

    jsize len = env->GetArrayLength(params);
    jclass parameterClass = env->FindClass("com/facebook/encapp/proto/Parameter");
    jmethodID get_key = env->GetMethodID(parameterClass, "getKey", "()Ljava/lang/String;");
    jmethodID get_type = env->GetMethodID(parameterClass, "getType", "()Lcom/facebook/encapp/proto/DataValueType;");
    jmethodID get_value = env->GetMethodID(parameterClass, "getValue", "()Ljava/lang/String;");

    //LOGD("Set defaults");
    //x264_param_default(&x264Params);
    int index = 0;
    //Params in common/base.c
    //The other we need to catch explicitly
    // preset and tune are special in that they are set simultanously

    //Preset needs to be set first... Let us do two passes.
    char *tune_val = NULL;
    char *preset_val = NULL;
    for (index = 0; index < len; index++) {
        jobject param = env->GetObjectArrayElement(params, index);
        jstring key_jstr = (jstring) env->CallObjectMethod(param, get_key);
        const char *key_char = env->GetStringUTFChars(key_jstr, 0);
        
        jstring value_jstr = (jstring) env->CallObjectMethod(param, get_value);
        const char *value_char = env->GetStringUTFChars(value_jstr, 0);
      
        if (!strcasecmp(key_char, "preset")) {
            int len = strlen(value_char);
            preset_val = (char*)malloc(len*sizeof(char));
            strcpy(preset_val, value_char);
        } else if (!strcasecmp(key_char, "tune")) {
            int len = strlen(value_char);
            tune_val = (char*)malloc(len*sizeof(char));
            strcpy(tune_val, value_char);
        }
        env->ReleaseStringUTFChars(key_jstr, key_char);
        env->ReleaseStringUTFChars(value_jstr, value_char);

        if (tune_val && preset_val) break;
    }


    if (preset_val) {
        LOGI("Set preset: %s", preset_val);
    }
    if (tune_val) {
        LOGI("Set tune: %s", tune_val);
    }
    if (x264_param_default_preset(&x264Params, preset_val, tune_val) != 0) {
        LOGE("Failed to set preset: %s, %s", preset_val, tune_val);
    }
    free(preset_val);
    free(tune_val);
    
    for (index = 0; index < len; index++) {
        jobject param = env->GetObjectArrayElement(params, index);
        jstring key_jstr = (jstring) env->CallObjectMethod(param, get_key);
        const char *key_char = env->GetStringUTFChars(key_jstr, 0);
        
        jstring value_jstr = (jstring) env->CallObjectMethod(param, get_value);
        const char *value_char = env->GetStringUTFChars(value_jstr, 0);
      
        LOGI("Trying to set %s to %s", key_char, value_char);
        if (!strcasecmp(key_char, "preset")) {
            // Ignore, already did this    
        } else if (!strcasecmp(key_char, "tune")) {
            // Ignore, already did this    
        } else if (!strcasecmp(key_char, "i_threads")) {
            x264Params.i_threads = atoi(value_char);
        } else if (!strcasecmp(key_char, "i_width")) {
            x264Params.i_width = atoi(value_char);
        }else if (!strcasecmp(key_char, "i_height")) {
            x264Params.i_height = atoi(value_char);
        }else if (!strcasecmp(key_char, "i_csp")) {
            x264Params.i_csp = atoi(value_char);
        }else if (!strcasecmp(key_char, "i_bitdepth")) {
            x264Params.i_bitdepth = atoi(value_char);
        } else {

            int status = x264_param_parse(&x264Params, key_char, value_char);

            if (status) {
                LOGE("Failed to set %s with value %s, status = %d", key_char, value_char, status);
            } else {
                LOGI("Set %s with value %s", key_char, value_char);
            }
        }


        env->ReleaseStringUTFChars(key_jstr, key_char);
        env->ReleaseStringUTFChars(value_jstr, value_char);
    }

    LOGD("Open x264 encoder");
    encoder = x264_encoder_open(&x264Params);
    if(!encoder)
    {
        LOGI("Failed encoder_open");
        return -1;
    } else {
        LOGD("Encoder opened");
    }
    
    LOGD("Set parameters");
    x264_encoder_parameters(encoder, &x264Params);
    LOGD("** Leaving init_encoder **");
    return 0;

}



jbyteArray get_header(JNIEnv *env, jobject thiz, jbyteArray headerArray) {
    if (!encoder) {
        LOGE("No encoder");
        return NULL;
    }


    int size_of_headers = x264_encoder_headers(encoder, &nal, &nnal);
    jbyte* buf = new jbyte[size_of_headers];
  	memset (buf, 0, size_of_headers);
  	jbyteArray ret = env->NewByteArray(size_of_headers);

    int offset = 0;
    if (buf) {
        for (int i = 0; i < nnal; i++) {
            if (nal[i].i_type == NAL_SPS || nal[i].i_type == NAL_PPS || nal[i].i_type == NAL_SEI ||
                nal[i].i_type == NAL_AUD || nal[i].i_type == NAL_FILLER) {
                // Check if there is enough space in the header array
                if (offset + nal[i].i_payload > size_of_headers) {
                    LOGE("ERROR: x264 wrong header size?");
                } else {
                    memcpy(buf + offset, nal[i].p_payload, nal[i].i_payload);
                    offset += nal[i].i_payload;
                }
            }
        }
    }


  	env->SetByteArrayRegion (ret, 0, size_of_headers, buf);
  	delete[] buf;
    return ret;
}


jint encode(JNIEnv *env, jobject thiz, jbyteArray input, jbyteArray output, jint width, jint height, jint colorSpace, jint bitDepth) {
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
    pic_in.img.i_csp = colorSpace;
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
    {"initEncoder",      "([Lcom/facebook/encapp/proto/Parameter;IIII)I", (void *)&init_encoder},
    {"getHeader",        "()[B",                                         (void *)&get_header},
    {"encode",           "([B[BIIII)I",                                   (void *)&encode},
    {"close",            "()V",                                          (void *)&close},
};



jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass("com/facebook/encapp/SwLibEncoder");
    if (clazz == NULL) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
}

