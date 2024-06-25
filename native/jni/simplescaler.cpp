#include <android/log.h>
#include <inttypes.h>
#include <jni.h>
#include <string.h>
#include <stdlib.h>

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




extern "C"

int input_width = 0;
int input_height = 0;
int input_stride = 0;
int input_y_plane_size = 0;
int input_chroma_plane_size = 0;
char *input_pix_fmt = NULL;

int output_width = 0;
int output_height = 0;
int output_stride = 0;
int output_y_plane_size = 0;
int output_chroma_plane_size = 0;
char *output_pix_fmt = NULL;

char* method_name = NULL;


jobjectArray available_methods(JNIEnv *env, jobject thiz) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray methods = env->NewObjectArray(1, stringClass, NULL);
    jstring method1 = env->NewStringUTF("neighbor");
    env->SetObjectArrayElement(methods, 0, method1);
    return methods;
}


jobjectArray supported_pixel_formats(JNIEnv *env, jobject thiz) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray fmts = env->NewObjectArray(1, stringClass, NULL);
    jstring fmt1 = env->NewStringUTF("yuv420p");
    env->SetObjectArrayElement(fmts, 0, fmt1);
    return fmts;
}

jobject version(JNIEnv *env, jobject thiz) {
    jstring version = env->NewStringUTF("0.1");
    return version;
}

jobject description(JNIEnv *env, jobject thiz) {
    jstring desc = env->NewStringUTF("SimpleScaler is a simple image scaler "\
            "that supports nearest neighbor scaling. Not to be used for anything "\
            "serious but as a template for other scalers or filters.");
    return desc;
}


void release(JNIEnv *env) {
    free((void*)input_pix_fmt);
    free((void*)output_pix_fmt);
    free((void*)method_name);
}

void set_raw_frame_definitions(JNIEnv *env, jobject thiz, jobject input, jobject output) {
    jclass bufferClass = env->FindClass("com/facebook/encapp/utils/RawFrameDefinition");
    
    LOGI("set_buffer_definitions");
    jmethodID get_width = env->GetMethodID(bufferClass, "getWidth", "()I");
    jmethodID get_height = env->GetMethodID(bufferClass, "getHeight", "()I");
    jmethodID get_stride = env->GetMethodID(bufferClass, "getStride", "()I");
    jmethodID get_pix_fmt = env->GetMethodID(bufferClass, "getPixFmtAsString", "()Ljava/lang/String;");
    jmethodID get_y_plane_size = env->GetMethodID(bufferClass, "getYPlaneSize", "()I");
    jmethodID get_chroma_plane_size = env->GetMethodID(bufferClass, "getChromaPlaneSize", "()I");

    // input
    input_width = env->CallIntMethod(input, get_width);
    input_height = env->CallIntMethod(input, get_height);
    input_stride = env->CallIntMethod(input, get_stride);
    
    jstring input_pix_fmt_jstr = (jstring) env->CallObjectMethod(input, get_pix_fmt);
    const char *input_pix_fmt_char = env->GetStringUTFChars(input_pix_fmt_jstr, 0);
    input_pix_fmt = strdup(input_pix_fmt_char);
    env->ReleaseStringUTFChars(input_pix_fmt_jstr, input_pix_fmt_char);

    input_y_plane_size = env->CallIntMethod(input, get_y_plane_size);
    input_chroma_plane_size = env->CallIntMethod(input, get_chroma_plane_size);
    // output
    output_width = env->CallIntMethod(output, get_width);
    output_height = env->CallIntMethod(output, get_height);
    output_stride = env->CallIntMethod(output, get_stride);
    jstring output_pix_fmt_jstr = (jstring) env->CallObjectMethod(output, get_pix_fmt);
    const char *output_pix_fmt_char = env->GetStringUTFChars(output_pix_fmt_jstr, 0);
    output_pix_fmt = strdup(output_pix_fmt_char);
    env->ReleaseStringUTFChars(output_pix_fmt_jstr, output_pix_fmt_char);

    output_y_plane_size = env->CallIntMethod(output, get_y_plane_size);
    output_chroma_plane_size = env->CallIntMethod(output, get_chroma_plane_size);
}


void set_method(JNIEnv *env, jobject thiz, jobject method) {
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID methodId = env->GetMethodID(stringClass, "toString", "()Ljava/lang/String;");
    jstring method_jstr = (jstring) env->CallObjectMethod(method, methodId);
    const char *method_char = env->GetStringUTFChars(method_jstr, 0);
    method_name = strdup(method_char);
    env->ReleaseStringUTFChars(method_jstr, method_char);
}

void set_params(JNIEnv *env, jobject thiz, jobjectArray params) {
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
        
        LOGI("%s - %s", key_char, value_char);
        env->ReleaseStringUTFChars(key_jstr, key_char);
        env->ReleaseStringUTFChars(value_jstr, value_char);
    }
}


/* Simple algo - Do not use for anything really - */
void nearest_neighbor_yuv420p_yuv420p(jbyte *input, jbyte *output) {
    float w_ratio = (float)input_width / (float)output_width;
    float h_ratio = (float)input_height / (float)output_height; 


    for (int y = 0; y < output_height; y++) {
        int yi = (int) (y * h_ratio);
        int yo_pos = y * output_stride;
        int yi_pos = yi * input_stride;

        for (int x = 0; x < output_width; x++) {
            int xi = (int) (x * w_ratio);
            int inpos = yi_pos + xi;
            int outpos = yo_pos + x;
            if (inpos < input_y_plane_size && outpos < output_y_plane_size) {
                output[outpos] = input[inpos];
            } else {
                LOGE("out of bounds, x/y = %d/%d, inpos: %d, outpos: %d", x, y, inpos, outpos);
            }


        }
    }
    
    for (int y = 0; y < (int)(output_height/2); y++) {
        int yi = (int)(y * h_ratio);
        int uo_pos = (int)(output_y_plane_size + y * output_width/2);
        int vo_pos = (int) (uo_pos + output_chroma_plane_size);
        int ui_pos = (int)(input_y_plane_size + yi * input_width/2);
        int vi_pos = (int) (ui_pos + input_chroma_plane_size);
        for (int x = 0; x < output_width/2; x++) {
            int xi = (int)(x * w_ratio);

            int inpos = ui_pos + xi;
            int outpos = uo_pos + x;

            output[outpos] = input[inpos];
            inpos = vi_pos + xi;
            outpos = vo_pos + x;
            
            output[outpos] = input[inpos];
        }
    }
}

void process_frame(JNIEnv *env, jobject thiz, jbyteArray input, jbyteArray output) {
    jbyte* input_data = (jbyte*)env->GetPrimitiveArrayCritical(input, 0);
    jbyte* output_data = (jbyte*)env->GetPrimitiveArrayCritical(output, 0);

    // process frame
    if (method_name == NULL || strcmp(method_name, "neighbor") == 0) {
        if (strcmp(input_pix_fmt, "yuv420p") == 0 && strcmp(output_pix_fmt, "yuv420p") == 0) {
            nearest_neighbor_yuv420p_yuv420p(input_data, output_data);
        } else {
            LOGE("unsupported pixel format");
        }
    } else {
        LOGE("unsupported method");
    }
    env->ReleasePrimitiveArrayCritical(input, input_data, 0);
    env->ReleasePrimitiveArrayCritical(output, output_data, 0);
}


static JNINativeMethod methods[] = {
    {"getAvailableMethodsNative",      "()[Ljava/lang/String;",     (void *)&available_methods},
    {"getSupportedPixelFormatsNative", "()[Ljava/lang/String;",     (void *)&supported_pixel_formats},
    {"setMethodNative",                "(Ljava/lang/String;)Z",     (void *)&set_method},
    {"setParametersNative",            "([Lcom/facebook/encapp/proto/Parameter;)V",   (void *)&set_params},
    {"versionNative",                  "()Ljava/lang/String;",      (void *)&version},
    {"descriptionNative",              "()Ljava/lang/String;",      (void *)&description},
    {"processFrameNative",             "([B[B)Z",                   (void *)&process_frame},
    {"setRawFrameDefinitionsNative",   "(Lcom/facebook/encapp/utils/RawFrameDefinition;Lcom/facebook/encapp/utils/RawFrameDefinition;)V", (void *)&set_raw_frame_definitions},
    {"releaseNative",                  "()V",                       (void *)&release},
};


jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass("com/facebook/encapp/utils/RawFrameFilterNative");
    if (clazz == NULL) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
