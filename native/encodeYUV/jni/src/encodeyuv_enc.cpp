
#include <assert.h>
#include <cstdint>
#include <float.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>
#include <inttypes.h>
#include <jni.h>
#include <list>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
using namespace std;

#ifdef DEBUG
#define LOGD(...)                                                              \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.encodeyuv", __VA_ARGS__)
#else
#define LOGD(...) ""
#endif

#define LOGI(...)                                                              \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.encodeyuv", __VA_ARGS__)

#define LOGE(...)                                                              \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.encodeyuv, error: ", __VA_ARGS__)

extern "C" {

int nnal;
int _width = -1;
int _height = -1;
int _pixelformat = -1;
int _bitdepth = -1;
int _bitrate = 0;
enum bitrate_mode {
    BITRATE_MODE_CQ = 0,
    BITRATE_MODE_VBR = 1,
    BITRATE_MODE_CBR = 2,
    BITRATE_MODE_JCQ = 10,
};
int _bitrate_mode = BITRATE_MODE_CBR;
char *_outputfile = NULL;
char *_inputfile = NULL;

jint init_encoder(JNIEnv *env, jobject thiz, jobjectArray params, jint width,
                  jint height, jint colorformat, jint bitDepth) {
  LOGD("*** init_encoder, %d, %d, %d, %d ***", width, height, colorformat,
       bitDepth);

  
  jsize len = env->GetArrayLength(params);
  jclass parameterClass = env->FindClass("com/facebook/encapp/proto/Parameter");
  jmethodID get_key =
      env->GetMethodID(parameterClass, "getKey", "()Ljava/lang/String;");
  jmethodID get_type = env->GetMethodID(
      parameterClass, "getType", "()Lcom/facebook/encapp/proto/DataValueType;");
  jmethodID get_value =
      env->GetMethodID(parameterClass, "getValue", "()Ljava/lang/String;");

  int index = 0;
  for (index = 0; index < len; index++) {
    jobject param = env->GetObjectArrayElement(params, index);
    jstring key_jstr = (jstring)env->CallObjectMethod(param, get_key);
    const char *key_char = env->GetStringUTFChars(key_jstr, 0);

    jstring value_jstr = (jstring)env->CallObjectMethod(param, get_value);
    const char *value_char = env->GetStringUTFChars(value_jstr, 0);

    if (strcmp(key_char, "bitrate") == 0) {
      _bitrate = atoi(value_char);
    } else if (strcmp(key_char, "encodeYUV.bitrate_mode") == 0) {
        if (strcmp(value_char, "CQ") == 0) {
            _bitrate_mode = BITRATE_MODE_CQ;
        } else if (strcmp(value_char, "VBR") == 0) {
            _bitrate_mode = BITRATE_MODE_VBR;
        } else if (strcmp(value_char, "CBR") == 0) {
            _bitrate_mode = BITRATE_MODE_CBR;
        } else if (strcmp(value_char, "JCQ") == 0) {
            _bitrate_mode = BITRATE_MODE_JCQ;
        }
    } else if (strcmp(key_char, "outputfile") == 0) {
      _outputfile = strdup(value_char);
    } else if (strcmp(key_char, "inputfile") == 0) {
      _inputfile = strdup(value_char);
    }

    env->ReleaseStringUTFChars(key_jstr, key_char);
    env->ReleaseStringUTFChars(value_jstr, value_char);
  }

  _width = width;
  _height = height;
  _pixelformat = colorformat;
  _bitdepth = bitDepth;

  LOGD("** Leaving init_encoder **");
  return 0;
}

jbyteArray get_header(JNIEnv *env, jobject thiz, jbyteArray headerArray) {
    return NULL;
}

jint encode(JNIEnv *env, jobject thiz, jbyteArray input, jbyteArray output,
            jobject frameInfo) {
    LOGD("** encode **");
    int status = 0;
    // Run execvp() to encode the input buffer and write the output buffer
    //
    // adb shell encodeYUV --format YUV420P -W 2304 -H 1728 -b 5000000 -i /data/data/com.facebook.encapp/walk1.3840x2160.29.97fps.mov.2304x1728.30fps.y4m_2304x1728p30.0_nv12.raw -o /data/data/com.facebook.encapp/enc.mp4
    // Ignore thigns we do not support
    const char *format = "YUV420P";
    if (_pixelformat == 54) {
        format = "P010";
    } else {
        LOGE("Unsupported pixel format %d", _pixelformat);
    }
    char width[32];
    char height[32];
    char bitrate[32];
    sprintf(width, "%d", _width);
    sprintf(height, "%d", _height);
    sprintf(bitrate, "%d", _bitrate);
    LOGI("encodeYUV --format %s -W %s -H %s -b %s -i %s -o %s", format, width, height, bitrate, _inputfile, _outputfile);
    status = execl("/data/data/com.facebook.encapp/encodeYUV", "encodeYUV", "--format", format, "-W", _width, "-H", _height, "-b", _bitrate, "-i", _inputfile, "-o", _outputfile, NULL);
    if (status == -1) {
        status = errno;
        LOGE("Failed to exec encodeYUV, %s", strerror(status));
    }

  LOGD( "** Leaving encode **");
  return status;
}

void update_settings(JNIEnv *env, jobject thiz, jobjectArray params) {
 }

jobjectArray get_all_settings(JNIEnv *env, jobject thiz) {
   return NULL;
}

void closeAndRelease(JNIEnv *env, jobject thiz) {
}

static JNINativeMethod methods[] = {
    {"initEncoder", "([Lcom/facebook/encapp/proto/Parameter;IIII)I",
     (void *)&init_encoder},
    {"getHeader", "()[B", (void *)&get_header},
    {"encode", "([B[BLcom/facebook/encapp/utils/FrameInfo;)I", (void *)&encode},
    {"close", "()V", (void *)&closeAndRelease},
    {"getAllEncoderSettings", "()[Lcom/facebook/encapp/utils/StringParameter;",
     (void *)&get_all_settings},
    {"updateSettings", "([Lcom/facebook/encapp/proto/Parameter;)V",
     (void *)&update_settings},
};

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env;
  if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }
  jclass clazz = env->FindClass("com/facebook/encapp/CustomEncoder");
  if (clazz == NULL) {
    return JNI_ERR;
  }
  if (env->RegisterNatives(clazz, methods,
                           sizeof(methods) / sizeof(methods[0])) < 0) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
}
