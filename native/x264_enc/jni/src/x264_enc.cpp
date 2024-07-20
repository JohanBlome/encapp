
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
using namespace std;

#include "x264.h"
#ifdef DEBUG
#define LOGD(...)                                                              \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.native", __VA_ARGS__)
#else
#define LOGD(...) ""
#endif

#define LOGI(...)                                                              \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.native", __VA_ARGS__)

#define LOGE(...)                                                              \
  __android_log_print(ANDROID_LOG_DEBUG, "encapp.native, error: ", __VA_ARGS__)

extern "C" {

x264_t *encoder = NULL;
x264_nal_t *nal;
int nnal;
int _width = -1;
int _height = -1;
int _colorformat = -1;
int _bitdepth = -1;

int mediacodec_colorformat_to_x264(int x264cf) {
  switch (x264cf) {
  case 0:
    return X264_CSP_I420;
  // nonexisting in x264
  case 1:
    return X264_CSP_I420;
  case 2:
    return X264_CSP_NV12;
  case 3:
    return X264_CSP_NV21;
  // not really but what can we do?
  case 4:
    return X264_CSP_BGRA;
  // p010le, i.e. 10-bit 4:2:0
  case 54:
    return X264_CSP_I420;
  default:
    return X264_CSP_I420;
  }
}

int set_param(x264_param_t *x264Params, const char *key, const char *value) {
  LOGD("Parameter: %s, val: %s", key, value);
  if (!strcasecmp(key, "preset")) {
    // Ignore, already did this
  } else if (!strcasecmp(key, "tune")) {
    // Ignore, already did this
  } else if (!strcasecmp(key, "i_threads")) {
    x264Params->i_threads = atoi(value);
  } else if (!strcasecmp(key, "bitrate_mode")) {
    // Hm... how to do the mapping.
    if (!strcasecmp(value, "cq") || !strcasecmp(value, "cqp")) {
      x264Params->rc.i_rc_method = X264_RC_CQP;
    } else if (!strcasecmp(value, "cbr") || !strcasecmp(value, "crf")) {
      x264Params->rc.i_rc_method = X264_RC_CRF;
    } else if (!strcasecmp(value, "vbr") || !strcasecmp(value, "abr")) {
      x264Params->rc.i_rc_method = X264_RC_ABR;
    } else {
      LOGE("Unknown bitrate_mode: %s - skip", value);
    }
  } else if (!strcasecmp(key, "i_frame_interval")) {
    // The unit in x264 is in frames and in mediacodec in seconds
    // TODO: handle framerate...
    x264Params->i_frame_reference = atoi(value) * x264Params->i_fps_num;

  } else if (!strcasecmp(key, "bitrate")) {
    // Need to highjack bitrate since the MediaCodec api definition is in bps
    // and x264 in kbps
    x264Params->rc.i_bitrate = atoi(value) / 1000;
  } else {
    int status = x264_param_parse(x264Params, key, value);

    if (status) {
      LOGE("Failed to set %s with value %s, status = %d", key, value, status);
    } else {
      LOGI("Set %s with value %s", key, value);
    }
  }

  return 0;
}

jint init_encoder(JNIEnv *env, jobject thiz, jobjectArray params, jint width,
                  jint height, jint colorformat, jint bitDepth) {
  LOGD("*** init_encoder, %d, %d, %d, %d ***", width, height, colorformat,
       bitDepth);
  assert(encoder == NULL);
  x264_param_t x264Params;
  int size_of_headers;

  if (encoder) {
    LOGE("Encoder already created");
    return false;
  }

  jsize len = env->GetArrayLength(params);
  jclass parameterClass = env->FindClass("com/facebook/encapp/proto/Parameter");
  jmethodID get_key =
      env->GetMethodID(parameterClass, "getKey", "()Ljava/lang/String;");
  jmethodID get_type = env->GetMethodID(
      parameterClass, "getType", "()Lcom/facebook/encapp/proto/DataValueType;");
  jmethodID get_value =
      env->GetMethodID(parameterClass, "getValue", "()Ljava/lang/String;");

  int index = 0;

  // Commands are the ones found here
  // http://www.chaneru.com/Roku/HLS/X264_Settings.htm
  //
  // preset and tune are special in that they are set simultanously
  // Preset needs to be set first... Let us do two passes.
  char *tune_val = NULL;
  char *preset_val = NULL;
  for (index = 0; index < len; index++) {
    jobject param = env->GetObjectArrayElement(params, index);
    jstring key_jstr = (jstring)env->CallObjectMethod(param, get_key);
    const char *key_char = env->GetStringUTFChars(key_jstr, 0);

    jstring value_jstr = (jstring)env->CallObjectMethod(param, get_value);
    const char *value_char = env->GetStringUTFChars(value_jstr, 0);

    if (!strcasecmp(key_char, "preset")) {
      int len = strlen(value_char);
      preset_val = (char *)malloc(len * sizeof(char));
      strcpy(preset_val, value_char);
    } else if (!strcasecmp(key_char, "tune")) {
      int len = strlen(value_char);
      tune_val = (char *)malloc(len * sizeof(char));
      strcpy(tune_val, value_char);
    }
    env->ReleaseStringUTFChars(key_jstr, key_char);
    env->ReleaseStringUTFChars(value_jstr, value_char);

    if (tune_val && preset_val)
      break;
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
    jstring key_jstr = (jstring)env->CallObjectMethod(param, get_key);
    const char *key_char = env->GetStringUTFChars(key_jstr, 0);

    jstring value_jstr = (jstring)env->CallObjectMethod(param, get_value);
    const char *value_char = env->GetStringUTFChars(value_jstr, 0);

    set_param(&x264Params, key_char, value_char);

    env->ReleaseStringUTFChars(key_jstr, key_char);
    env->ReleaseStringUTFChars(value_jstr, value_char);
  }

  _width = width;
  _height = height;
  _colorformat = mediacodec_colorformat_to_x264(colorformat);
  _bitdepth = bitDepth;

  x264Params.i_width = _width;
  x264Params.i_height = _height;
  // TODO: remove this, currenlty the encoder is broken without it
  x264Params.i_threads = 1;
  x264Params.i_csp = _colorformat;
  x264Params.i_bitdepth = _bitdepth;
  x264Params.i_fps_num = 30;
  x264Params.i_fps_den = 1;
  x264Params.i_timebase_num = 1;
  x264Params.i_timebase_den = 1000000; // Nanosecs
  LOGD("Open x264 encoder");
  encoder = x264_encoder_open(&x264Params);
  if (!encoder) {
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
  jbyte *buf = new jbyte[size_of_headers];
  memset(buf, 0, size_of_headers);
  jbyteArray ret = env->NewByteArray(size_of_headers);

  int offset = 0;
  if (buf) {
    for (int i = 0; i < nnal; i++) {
      if (nal[i].i_type == NAL_SPS || nal[i].i_type == NAL_PPS ||
          nal[i].i_type == NAL_SEI || nal[i].i_type == NAL_AUD ||
          nal[i].i_type == NAL_FILLER) {
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

  env->SetByteArrayRegion(ret, 0, size_of_headers, buf);
  delete[] buf;
  return ret;
}

jint encode(JNIEnv *env, jobject thiz, jbyteArray input, jbyteArray output,
            jobject frameInfo) {
  LOGD("Encoding frame");
  if (!encoder) {
    LOGI("Encoder is not initialized for encoding");
    return -1;
  }

  jclass infoClass = env->FindClass("com/facebook/encapp/utils/FrameInfo");
  jfieldID isIframeId = env->GetFieldID(infoClass, "mIsIframe", "Z");
  jfieldID ptsId = env->GetFieldID(infoClass, "mPts", "J");
  jfieldID dtsId = env->GetFieldID(infoClass, "mDts", "J");
  jfieldID sizeId = env->GetFieldID(infoClass, "mSize", "J");

  // All interaction must be done before locking java...
  x264_picture_t pic_in = {0};
  x264_picture_t pic_out = {0};

  // TODO: We are assuming yuv420p, add check...
  int ySize = _width * _height; // Stride?
  int uvSize = (int)(ySize / 4.0f);

  x264_picture_init(&pic_in);
  pic_in.img.i_csp = _colorformat;
  // TODO: hard code, really?
  pic_in.img.i_plane = 3;

  long pts = env->GetLongField(frameInfo, ptsId);
  LOGD("Set pts: %ld", pts);
  pic_in.i_pts = pts; // Convert to milliseconds

  // Now we are locking java
  jbyte *input_data = (jbyte *)env->GetPrimitiveArrayCritical(input, 0);
  jbyte *output_data = (jbyte *)env->GetPrimitiveArrayCritical(output, 0);

  pic_in.img.plane[0] = (uint8_t *)input_data;
  pic_in.img.plane[1] = (uint8_t *)(input_data + ySize);
  pic_in.img.plane[2] = (uint8_t *)(input_data + ySize + uvSize);

  pic_in.img.i_stride[0] = _width;
  pic_in.img.i_stride[1] = _width / 2;
  pic_in.img.i_stride[2] = _width / 2;

  int frame_size = x264_encoder_encode(encoder, &nal, &nnal, &pic_in, &pic_out);
  if (frame_size >= 0) {
    // TODO: Added total_size = 2 for debugging purpose
    int total_size = 2;
    for (int i = 0; i < nnal; i++) {
      total_size += nal[i].i_payload;
    }
    int offset = 2;
    for (int i = 0; i < nnal; i++) {
      if (nal[i].i_type == NAL_SPS || nal[i].i_type == NAL_PPS ||
          nal[i].i_type == NAL_SEI || nal[i].i_type == NAL_AUD ||
          nal[i].i_type == NAL_FILLER) {
        continue;
      }
      memcpy(output_data + offset, nal[i].p_payload, nal[i].i_payload);
      offset += nal[i].i_payload;
    }
    frame_size = total_size;
  }

  env->ReleasePrimitiveArrayCritical(input, input_data, 0);
  env->ReleasePrimitiveArrayCritical(output, output_data, 0);

  // Set data from the encoding process
  env->SetLongField(frameInfo, sizeId, frame_size);
  env->SetLongField(frameInfo, ptsId, pic_out.i_pts);
  env->SetLongField(frameInfo, dtsId, pic_out.i_dts);
  env->SetBooleanField(frameInfo, isIframeId, pic_out.b_keyframe);
  // Do we need additional info?
  // LOGD("Not saved: Pic type: %d", pic_out.i_type);
  // TODO: we also have a complete list of params. Maybe with a debug flag we
  // could push this to java?

  // x264_image_properties_t holds psnr and ssim as well (potentially, if
  // enabled)
  return frame_size;
}

void update_settings(JNIEnv *env, jobject thiz, jobjectArray params) {
  if (!encoder) {
    LOGI("encoder is not initialized, settings cannot be updated");
    return;
  }

  x264_param_t x264Params;

  jsize len = env->GetArrayLength(params);
  jclass parameterClass = env->FindClass("com/facebook/encapp/proto/Parameter");
  jmethodID get_key =
      env->GetMethodID(parameterClass, "getKey", "()Ljava/lang/String;");
  jmethodID get_type = env->GetMethodID(
      parameterClass, "getType", "()Lcom/facebook/encapp/proto/DataValueType;");
  jmethodID get_value =
      env->GetMethodID(parameterClass, "getValue", "()Ljava/lang/String;");

  for (int index = 0; index < len; index++) {
    jobject param = env->GetObjectArrayElement(params, index);
    jstring key_jstr = (jstring)env->CallObjectMethod(param, get_key);
    const char *key_char = env->GetStringUTFChars(key_jstr, 0);

    jstring value_jstr = (jstring)env->CallObjectMethod(param, get_value);
    const char *value_char = env->GetStringUTFChars(value_jstr, 0);

    set_param(&x264Params, key_char, value_char);

    env->ReleaseStringUTFChars(key_jstr, key_char);
    env->ReleaseStringUTFChars(value_jstr, value_char);
  }

  int status = x264_encoder_reconfig(encoder, &x264Params);
  if (!status) {
    LOGI("Settings updated successfully");
  } else {
    LOGE("Settings update failed");
  }
}

jobjectArray get_all_settings(JNIEnv *env, jobject thiz) {
  if (!encoder) {
    LOGI("encoder is not initialized, settings cannot be retrieved");
    return NULL;
  }

  jclass parameterClass =
      env->FindClass("com/facebook/encapp/utils/StringParameter");
  jfieldID keyId =
      env->GetFieldID(parameterClass, "mKey", "Ljava/lang/String;");
  jfieldID typeId =
      env->GetFieldID(parameterClass, "mType", "Ljava/lang/String;");
  jfieldID valueId =
      env->GetFieldID(parameterClass, "mValue", "Ljava/lang/String;");

  jmethodID paramConstructor = env->GetMethodID(
      parameterClass, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

  std::list<jobject> params;
  x264_param_t info;
  x264_encoder_parameters(encoder, &info);
  // Bit stream
  //
  int len = 128; // arbitrary
  char buffer[len];

  snprintf(buffer, len, "%d", info.i_frame_reference);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_frame_reference"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.b_cabac);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("b_cabac"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));

  // Rate Control
  //
  snprintf(buffer, len, "%d", info.rc.i_rc_method);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("rc.i_rc_method"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.rc.i_qp_constant);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("rc.i_qp_constant"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.rc.i_qp_min);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("rc.i_qp_min"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.rc.i_qp_max);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("rc.i_qp_max"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.rc.i_qp_step);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("rc.i_qp_step"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));

  // VUI
  //

  // Slicing
  //
  snprintf(buffer, len, "%d", info.i_slice_max_size);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_slice_max_size"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.i_slice_max_mbs);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_slice_max_mbs"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.i_slice_min_mbs);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_slice_min_mbs"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.i_slice_count);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_slice_count"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.i_slice_count_max);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_slice_count_max"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));

  // muxer
  //
  snprintf(buffer, len, "%d", info.i_fps_num);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_fps_num"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.i_fps_den);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_fps_den"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.i_timebase_num);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_timebase_num"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));
  snprintf(buffer, len, "%d", info.i_timebase_den);
  params.push_back(env->NewObject(
      parameterClass, paramConstructor, env->NewStringUTF("i_timebase_den"),
      env->NewStringUTF("intType"), env->NewStringUTF(buffer)));

  jobjectArray ret = env->NewObjectArray(params.size(), parameterClass, NULL);
  int index = 0;
  for (auto element : params) {
    env->SetObjectArrayElement(ret, index++, element);
  }

  return ret;
}

void close(JNIEnv *env, jobject thiz) {
  LOGI("Closing encoder");
  if (encoder) {
    x264_encoder_close(encoder);
    encoder = NULL;
  }
}

static JNINativeMethod methods[] = {
    {"initEncoder", "([Lcom/facebook/encapp/proto/Parameter;IIII)I",
     (void *)&init_encoder},
    {"getHeader", "()[B", (void *)&get_header},
    {"encode", "([B[BLcom/facebook/encapp/utils/FrameInfo;)I", (void *)&encode},
    {"close", "()V", (void *)&close},
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
