package com.facebook.encapp.utils;

import android.graphics.ImageFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import com.facebook.encapp.proto.Configure;
import com.facebook.encapp.proto.PixFmt;
import com.facebook.encapp.proto.Test;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class MediaCodecInfoHelper {
    final static int mIndentWidth = 2;
    protected final static String TAG = "MediaCodecInfoHelper";


    final static List<Integer> mBitrateModeList = Arrays.asList(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            //MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    );

    public static String bitrateModeToString(int bitrate_mode) {
        switch (bitrate_mode) {
            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR:
                return "MODE_CBR";
            //case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD:
            //return "MODE_CBR_FD";
            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ:
                return "MODE_CQ";
            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR:
                return "MODE_VBR";
            default:
                return bitrate_mode + " is no bitrate mode";
        }
    }

    final static List<String> mFeatureList = Arrays.asList(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,
            MediaCodecInfo.CodecCapabilities.FEATURE_DynamicTimestamp,
            //MediaCodecInfo.CodecCapabilities.FEATURE_EncodingStatistics,
            MediaCodecInfo.CodecCapabilities.FEATURE_FrameParsing,
            //MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing,
            MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh,
            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency,
            MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames,
            MediaCodecInfo.CodecCapabilities.FEATURE_PartialFrame,
            //MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds,
            MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
            MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback);

    public static JSONObject encoderCapabilitiesToJson(MediaCodecInfo.EncoderCapabilities encoder_capabilities) throws JSONException {
        JSONObject json = new JSONObject();
        if (encoder_capabilities == null) {
            return json;
        }


        //str.append(tab + "encoder_capabilities {\n");
        json.put("complexity_range", encoder_capabilities.getComplexityRange().toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            json.put("quality_range", encoder_capabilities.getQualityRange().toString());
        }
        for (int bitrate_mode : mBitrateModeList) {
            json.put(bitrateModeToString(bitrate_mode) + "", encoder_capabilities.isBitrateModeSupported(bitrate_mode));
        }
        return json;
    }

    public static JSONArray videoPerformancePointToJson(MediaCodecInfo.VideoCapabilities video_capabilities) throws JSONException {
        JSONArray json = new JSONArray();
        if (video_capabilities == null) {
            return json;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            List<MediaCodecInfo.VideoCapabilities.PerformancePoint> performancePoints = video_capabilities.getSupportedPerformancePoints();
            if (performancePoints != null) {
                for (MediaCodecInfo.VideoCapabilities.PerformancePoint perf: performancePoints) {
                    json.put(perf.toString());
                }
            }

        }
        return json;
    }

    public static JSONObject videoCapabilitiesToJson(MediaCodecInfo.VideoCapabilities video_capabilities) throws JSONException {
        JSONObject json = new JSONObject();
        if (video_capabilities == null) {
            return json;
        }

        json.put("bitrate_range", video_capabilities.getBitrateRange().toString());
        json.put("height_alignment", video_capabilities.getHeightAlignment());
        json.put("width_alignment", video_capabilities.getWidthAlignment());
        json.put("supported_frame_rates", video_capabilities.getSupportedFrameRates().toString());
        json.put("supported_heights", video_capabilities.getSupportedHeights().toString());
        json.put("supported_widths", video_capabilities.getSupportedWidths().toString());

        return json;
    }


    public static JSONObject featuresToJson(MediaCodecInfo.CodecCapabilities codec_capabilities, boolean required) throws JSONException{
        JSONObject json = new JSONObject();
        for (String feature : mFeatureList) {
            if (required) {
                json.put(feature, codec_capabilities.isFeatureRequired(feature));
            } else {
                json.put(feature, codec_capabilities.isFeatureSupported(feature));
            }
        }
        return json;
    }




    public static int mapEncappPixFmtToAndroidColorFormat(PixFmt pix_fmt) {
        switch (pix_fmt.getNumber()) {
            case PixFmt.yuv420p_VALUE:
                return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            case PixFmt.yvu420p_VALUE:
                return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;
            case PixFmt.nv12_VALUE:
                return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            case PixFmt.nv21_VALUE:
                return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar;
            case PixFmt.rgba_VALUE:
                return MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888;
            // Added in API level 33
            case PixFmt.p010le_VALUE:
                return 54;//MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010;
            default:
                throw new RuntimeException("unsupported pix_fmt: " + pix_fmt);
        }
    }

    private static Map<Integer, String> createAndroidColorFormaNameTable() {
        Map<Integer, String> m = new HashMap<Integer, String>();
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format12bitRGB444, "COLOR_Format12bitRGB444");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format16bitARGB1555, "COLOR_Format16bitARGB1555");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format16bitARGB4444, "COLOR_Format16bitARGB4444");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format16bitBGR565, "COLOR_Format16bitBGR565");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format16bitRGB565, "COLOR_Format16bitRGB565");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format18BitBGR666, "COLOR_Format18BitBGR666");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format18bitARGB1665, "COLOR_Format18bitARGB1665");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format18bitRGB666, "COLOR_Format18bitRGB666");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format19bitARGB1666, "COLOR_Format19bitARGB1666");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format24BitABGR6666, "COLOR_Format24BitABGR6666");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format24BitARGB6666, "COLOR_Format24BitARGB6666");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format24bitARGB1887, "COLOR_Format24bitARGB1887");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format24bitBGR888, "COLOR_Format24bitBGR888");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format24bitRGB888, "COLOR_Format24bitRGB888");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format25bitARGB1888, "COLOR_Format25bitARGB1888");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR8888, "COLOR_Format32bitABGR8888");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888, "COLOR_Format32bitARGB8888");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitBGRA8888, "COLOR_Format32bitBGRA8888");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_Format8bitRGB332, "COLOR_Format8bitRGB332");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatCbYCrY, "COLOR_FormatCbYCrY");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatCrYCbY, "COLOR_FormatCrYCbY");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatL16, "COLOR_FormatL16");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatL2, "COLOR_FormatL2");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatL24, "COLOR_FormatL24");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatL32, "COLOR_FormatL32");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatL4, "COLOR_FormatL4");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatL8, "COLOR_FormatL8");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatMonochrome, "COLOR_FormatMonochrome");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBAFlexible, "COLOR_FormatRGBAFlexible");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBFlexible, "COLOR_FormatRGBFlexible");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer10bit, "COLOR_FormatRawBayer10bit");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer8bit, "COLOR_FormatRawBayer8bit");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer8bitcompressed, "COLOR_FormatRawBayer8bitcompressed");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface, "COLOR_FormatSurface");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYCbYCr, "COLOR_FormatYCbYCr");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYCrYCb, "COLOR_FormatYCrYCb");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411PackedPlanar, "COLOR_FormatYUV411PackedPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411Planar, "COLOR_FormatYUV411Planar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, "COLOR_FormatYUV420Flexible");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar, "COLOR_FormatYUV420PackedPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar, "COLOR_FormatYUV420PackedSemiPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, "COLOR_FormatYUV420Planar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, "COLOR_FormatYUV420SemiPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Flexible, "COLOR_FormatYUV422Flexible");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedPlanar, "COLOR_FormatYUV422PackedPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedSemiPlanar, "COLOR_FormatYUV422PackedSemiPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar, "COLOR_FormatYUV422Planar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar, "COLOR_FormatYUV422SemiPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible, "COLOR_FormatYUV444Flexible");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved, "COLOR_FormatYUV444Interleaved");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar, "COLOR_QCOM_FormatYUV420SemiPlanar");
        m.put(MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar, "COLOR_TI_FormatYUV420PackedSemiPlanar");
        return m;
    }

    // https://jbit.net/Android_Colors/
    private static Map<Integer, String> createAltColorFormaNameTable() {
        Map<Integer, String> m = new HashMap<Integer, String>();
        m.put(0x00000000, "Java_UNKNOWN");
        m.put(0x00000001, "AImage_RGBA_8888/AHardwareBuffer_R8G8B8A8_UNORM/HAL_RGBA_8888");
        m.put(0x00000002, "AImage_RGBX_8888/AHardwareBuffer_R8G8B8X8_UNORM/HAL_RGBX_8888");
        m.put(0x00000003, "AImage_RGB_888/AHardwareBuffer_R8G8B8_UNORM/HAL_RGB_888");
        m.put(0x00000004, "Java_RGB_565/AImage_RGB_565/AHardwareBuffer_R5G6B5_UNORM/HAL_RGB_565");
        m.put(0x00000005, "HAL_BGRA_8888");
        m.put(0x00000006, "QCOM_RGBA_5551");
        m.put(0x00000007, "QCOM_RGBA_4444");
        m.put(0x00000010, "Java_NV16/HAL_YCBCR_422_SP");
        m.put(0x00000011, "Java_NV21/HAL_YCRCB_420_SP");
        m.put(0x00000014, "Java_YUY2/HAL_YCBCR_422_I");
        m.put(0x00000016, "AImage_RGBA_FP16/AHardwareBuffer_R16G16B16A16_FLOAT/HAL_RGBA_FP16");
        m.put(0x00000020, "Java_RAW_SENSOR/AImage_RAW16/HAL_RAW16");
        m.put(0x00000021, "AHardwareBuffer_BLOB/HAL_BLOB");
        m.put(0x00000022, "Java_PRIVATE/AImage_PRIVATE/HAL_IMPLEMENTATION_DEFINED");
        m.put(0x00000023, "Java_YUV_420_888/AImage_YUV_420_888/AHardwareBuffer_Y8Cb8Cr8_420/HAL_YCBCR_420_888");
        m.put(0x00000024, "Java_RAW_PRIVATE/AImage_RAW_PRIVATE/HAL_RAW_OPAQUE");
        m.put(0x00000025, "Java_RAW10/AImage_RAW10/HAL_RAW10");
        m.put(0x00000026, "Java_RAW12/AImage_RAW12/HAL_RAW12");
        m.put(0x00000027, "Java_YUV_422_888");
        m.put(0x00000028, "Java_YUV_444_888");
        m.put(0x00000029, "Java_FLEX_RGB_888");
        m.put(0x0000002a, "Java_FLEX_RGBA_8888");
        m.put(0x0000002b, "AHardwareBuffer_R10G10B10A2_UNORM/HAL_RGBA_1010102");
        m.put(0x00000030, "AHardwareBuffer_D16_UNORM/HAL_DEPTH_16");
        m.put(0x00000031, "AHardwareBuffer_D24_UNORM/HAL_DEPTH_24");
        m.put(0x00000032, "AHardwareBuffer_D24_UNORM_S8_UINT/HAL_DEPTH_24_STENCIL_8");
        m.put(0x00000033, "AHardwareBuffer_D32_FLOAT/HAL_DEPTH_32F");
        m.put(0x00000034, "AHardwareBuffer_D32_FLOAT_S8_UINT/HAL_DEPTH_32F_STENCIL_8");
        m.put(0x00000035, "AHardwareBuffer_S8_UINT/HAL_STENCIL_8");
        m.put(0x00000036, "HAL_YCBCR_P010");
        m.put(0x00000037, "HAL_HSV_888");
        m.put(0x00000100, "Java_JPEG/AImage_JPEG/Exynos_ANBYUV420SemiPlanar/Intel_NV12_Y_TILED");
        m.put(0x00000101, "Java_DEPTH_POINT_CLOUD/AImage_DEPTH_POINT_CLOUD/Exynos_YCbCr_420_P_M/Intel_NV12_LINEAR");
        m.put(0x00000102, "QCOM_NV12_ENCODEABLE/Exynos_YCbCr_420_I/Intel_YCrCb_422_H");
        m.put(0x00000103, "Exynos_CbYCrY_422_I/Intel_NV12_LINEAR_PACKED");
        m.put(0x00000104, "Exynos_CbYCrY_420_I/Intel_YCbCr_422_H");
        m.put(0x00000105, "Exynos_YCbCr_420_SP_M/Intel_NV12_X_TILED");
        m.put(0x00000106, "Exynos_YCrCb_422_SP/Intel_RGBA_5551");
        m.put(0x00000107, "Exynos_YCbCr_420_SP_M_TILED/Intel_RGBA_4444");
        m.put(0x00000108, "Exynos_ARGB_8888/Intel_GENERIC_8BIT");
        m.put(0x00000109, "QCOM_YCbCr_420_SP/Intel_YCbCr_411");
        m.put(0x0000010a, "Intel_YCbCr_420_H");
        m.put(0x0000010b, "QCOM_YCrCb_422_SP/Intel_YCbCr_422_V");
        m.put(0x0000010c, "Intel_YCbCr_444");
        m.put(0x0000010d, "QCOM_R_8/Intel_RGBP");
        m.put(0x0000010e, "QCOM_RG_88/Intel_BGRP");
        m.put(0x0000010f, "QCOM_YCbCr_444_SP/Intel_NV12");
        m.put(0x00000110, "QCOM_YCrCb_444_SP/Exynos_YCbCr_420_SP/Intel_P010");
        m.put(0x00000111, "QCOM_YCrCb_422_I/Exynos_YCrCb_420_SP/Intel_Z16");
        m.put(0x00000112, "QCOM_BGRX_8888/Exynos_YCbCr_420_SP_TILED/Intel_UVMAP64");
        m.put(0x00000113, "QCOM_NV21_ZSL/Exynos_YCbCr_422_SP/Intel_A2R10G10B10");
        m.put(0x00000114, "QCOM_YCrCb_420_SP_VENUS/Exynos_YCrCb_422_SP/Intel_A2B10G10R10");
        m.put(0x00000115, "QCOM_BGR_565/Exynos_YCbCr_422_I/Intel_YCrCb_NORMAL");
        m.put(0x00000116, "Exynos_YCrCb_422_I/Intel_YCrCb_SWAPUVY");
        m.put(0x00000117, "QCOM_ARGB_2101010/Exynos_CbYCrY_422_I/Intel_YCrCb_SWAPUV");
        m.put(0x00000118, "QCOM_RGBX_1010102/Exynos_CrYCbY_422_I/Intel_YCrCb_SWAPY");
        m.put(0x00000119, "QCOM_XRGB_2101010/Intel_X2R10G10B10");
        m.put(0x0000011a, "QCOM_BGRA_1010102/Intel_X2B10G10R10");
        m.put(0x0000011b, "QCOM_ABGR_2101010/Exynos_CbYCr_422_I");
        m.put(0x0000011c, "QCOM_BGRX_1010102/Exynos_YV12_M/Intel_P016");
        m.put(0x0000011d, "QCOM_XBGR_2101010/Exynos_YCrCb_420_SP_M/Intel_Y210");
        m.put(0x0000011e, "Exynos_YCrCb_420_SP_M_FULL/Intel_Y216");
        m.put(0x0000011f, "QCOM_YCbCr_420_P010/Exynos_YCbCr_420_P/Intel_Y410");
        m.put(0x00000120, "QCOM_CbYCrY_422_I/Exynos_YCbCr_420_SP/Intel_Y416");
        m.put(0x00000121, "QCOM_BGR_888/Exynos_YCbCr_420_SP_M_PRIV/Intel_Y8I");
        m.put(0x00000122, "Exynos_YCbCr_420_PN/Intel_Y12I");
        m.put(0x00000123, "QCOM_RAW8/Exynos_YCbCr_420_SPN");
        m.put(0x00000124, "QCOM_YCbCr_420_P010_UBWC/Exynos_YCbCr_420_SPN_TILED");
        m.put(0x00000125, "Exynos_YCbCr_420_SP_M_S10B");
        m.put(0x00000126, "Exynos_YCbCr_420_SPN_S10B");
        m.put(0x00000180, "QCOM_INTERLACE");
        m.put(0x00001002, "Java_RAW_DEPTH");
        m.put(0x000093b0, "QCOM_RGBA_ASTC_4x4");
        m.put(0x000093b1, "QCOM_RGBA_ASTC_5x4");
        m.put(0x000093b2, "QCOM_RGBA_ASTC_5x5");
        m.put(0x000093b3, "QCOM_RGBA_ASTC_6x5");
        m.put(0x000093b4, "QCOM_RGBA_ASTC_6x6");
        m.put(0x000093b5, "QCOM_RGBA_ASTC_8x5");
        m.put(0x000093b6, "QCOM_RGBA_ASTC_8x6");
        m.put(0x000093b7, "QCOM_RGBA_ASTC_8x8");
        m.put(0x000093b8, "QCOM_RGBA_ASTC_10x5");
        m.put(0x000093b9, "QCOM_RGBA_ASTC_10x6");
        m.put(0x000093ba, "QCOM_RGBA_ASTC_10x8");
        m.put(0x000093bb, "QCOM_RGBA_ASTC_10x10");
        m.put(0x000093bc, "QCOM_RGBA_ASTC_12x10");
        m.put(0x000093bd, "QCOM_RGBA_ASTC_12x12");
        m.put(0x000093d0, "QCOM_SRGB8_ALPHA8_ASTC_4x4");
        m.put(0x000093d1, "QCOM_SRGB8_ALPHA8_ASTC_5x4");
        m.put(0x000093d2, "QCOM_SRGB8_ALPHA8_ASTC_5x5");
        m.put(0x000093d3, "QCOM_SRGB8_ALPHA8_ASTC_6x5");
        m.put(0x000093d4, "QCOM_SRGB8_ALPHA8_ASTC_6x6");
        m.put(0x000093d5, "QCOM_SRGB8_ALPHA8_ASTC_8x5");
        m.put(0x000093d6, "QCOM_SRGB8_ALPHA8_ASTC_8x6");
        m.put(0x000093d7, "QCOM_SRGB8_ALPHA8_ASTC_8x8");
        m.put(0x000093d8, "QCOM_SRGB8_ALPHA8_ASTC_10x5");
        m.put(0x000093d9, "QCOM_SRGB8_ALPHA8_ASTC_10x6");
        m.put(0x000093da, "QCOM_SRGB8_ALPHA8_ASTC_10x8");
        m.put(0x000093db, "QCOM_SRGB8_ALPHA8_ASTC_10x10");
        m.put(0x000093dc, "QCOM_SRGB8_ALPHA8_ASTC_12x10");
        m.put(0x000093dd, "QCOM_SRGB8_ALPHA8_ASTC_12x12");
        m.put(0x20203859, "FourCC_Y8  /Java_Y8/AImage_Y8/HAL_Y8");
        m.put(0x20363159, "FourCC_Y16 /Java_Y16/HAL_Y16");
        m.put(0x32315659, "FourCC_YV12/Java_YV12/HAL_YV12");
        m.put(0x43574259, "FourCC_YBWC/QCOM_YCbCr_422_I_10BIT_COMPRESSED");
        m.put(0x44363159, "FourCC_Y16D/Java_DEPTH16/AImage_DEPTH16");
        m.put(0x48454946, "FourCC_FIEH/Java_HEIC/AImage_HEIC");
        m.put(0x4c595559, "FourCC_YUYL/QCOM_YCbCr_422_I_10BIT");
        m.put(0x69656963, "FourCC_ciei/Java_DEPTH_JPEG/AImage_DEPTH_JPEG");
        m.put(0x7f000001, "Exynos_NV12TPhysicalAddress");
        m.put(0x7f000002, "Exynos_NV12LPhysicalAddress");
        m.put(0x7f000003, "Exynos_NV12LVirtualAddress");
        m.put(0x7f000789, "Exynos_AndroidOpaque");
        m.put(0x7fa00e00, "Intel_YUV420PackedSemiPlanar");
        m.put(0x7fa00f00, "Intel_YUV420PackedSemiPlanar_Tiled");
        m.put(0x7fa30c00, "QCOM_NV21_ENCODEABLE");
        m.put(0x7fa30c01, "QCOM_YCrCb_420_SP_ADRENO");
        m.put(0x7fa30c03, "QCOM_YCbCr_420_SP_TILED");
        m.put(0x7fa30c04, "QCOM_YCbCr_420_SP_VENUS");
        m.put(0x7fa30c06, "QCOM_YCbCr_420_SP_VENUS_UBWC");
        m.put(0x7fa30c09, "QCOM_YCbCr_420_TP10_UBWC");
        m.put(0x7fa30c0a, "QCOM_YCbCr_420_P010_VENUS");
        m.put(0x7fc00002, "Exynos_NV12Tiled");
        m.put(0x7fc00003, "Exynos_NV12Tiled_SBS_LR");
        m.put(0x7fc00004, "Exynos_NV12Tiled_SBS_RL");
        m.put(0x7fc00005, "Exynos_NV12Tiled_TB_LR");
        m.put(0x7fc00006, "Exynos_NV12Tiled_TB_RL");
        m.put(0x7fc00007, "Exynos_YUV420SemiPlanar_SBS_LR");
        m.put(0x7fc00008, "Exynos_YUV420SemiPlanar_SBS_RL");
        m.put(0x7fc00009, "Exynos_YUV420SemiPlanar_TB_LR");
        m.put(0x7fc0000a, "Exynos_YUV420SemiPlanar_TB_RL");
        m.put(0x7fc0000b, "Exynos_YUV420Planar_SBS_LR");
        m.put(0x7fc0000c, "Exynos_YUV420Planar_SBS_RL");
        m.put(0x7fc0000d, "Exynos_YUV420Planar_TB_LR");
        m.put(0x7fc0000e, "Exynos_YUV420Planar_TB_RL");
        return m;
    }

    final static Map<Integer, String> androidColorFormatNameTable = createAndroidColorFormaNameTable();
    final static Map<Integer, String> altColorFormatNameTable = createAltColorFormaNameTable();

    public static JSONArray colorFormatsToJson(int[] color_formats) throws JSONException {
        JSONArray json = new JSONArray();
        for (int color_format : color_formats) {
            JSONObject jcol = new JSONObject();
        //    str.append(tab + "color {\n");
            jcol.put("format", color_format);
            if (androidColorFormatNameTable.containsKey(color_format)) {
                jcol.put("name", androidColorFormatNameTable.get(color_format));
            } else if (altColorFormatNameTable.containsKey(color_format)) {
                jcol.put("name", altColorFormatNameTable.get(color_format));
            }
            json.put(jcol);
        }
        return json;
    }


    public static JSONObject profileLevelsToJson(MediaCodecInfo.CodecProfileLevel[] color_profile_level) throws JSONException {
        JSONObject json = new JSONObject();
        for (MediaCodecInfo.CodecProfileLevel profile_level : color_profile_level) {
            json.put("profile" , profile_level.profile);
            json.put("level", profile_level.level);
        }
        return json;
    }


    public static JSONObject codecCapabilitiesToJson(MediaCodecInfo media_codec_info, String media_type) throws JSONException {
        JSONObject json = new JSONObject();
        MediaCodecInfo.CodecCapabilities codec_capabilities = media_codec_info.getCapabilitiesForType(media_type);


        json.put("media_type", media_type);
        json.put("mime_type", codec_capabilities.getMimeType());
        json.put("max_supported_instances", codec_capabilities.getMaxSupportedInstances());

        // print fields
        json.put("color_formats", colorFormatsToJson(codec_capabilities.colorFormats));
        json.put("profile_levels", profileLevelsToJson(codec_capabilities.profileLevels));

        MediaFormat mediaFormat = codec_capabilities.getDefaultFormat();
        //Odds are that if there is no default profile  nothing else will have defaults anyway...
        if (mediaFormat.getString(MediaFormat.KEY_PROFILE) != null) {
            json.put("default_settings", mediaFormatToJson(mediaFormat));
        }

        // print encoder capabilities

        json.put("encoder_capabilities",encoderCapabilitiesToJson(codec_capabilities.getEncoderCapabilities()));
        // print encoder capabilities
        json.put("video_capabilities", videoCapabilitiesToJson(codec_capabilities.getVideoCapabilities()));
        // print features required and supported
        json.put("feature_required", featuresToJson(codec_capabilities, true));
        json.put("feature_provided", featuresToJson(codec_capabilities, false));
        // print performance points if available
        json.put("performance_points", videoPerformancePointToJson(codec_capabilities.getVideoCapabilities()));
        return json;
    }


    public static JSONObject mediaFormatToJson(MediaFormat mediaFormat) throws JSONException {
        //str.append("MediaFormat {\n");
        JSONObject json = new JSONObject();
        if (Build.VERSION.SDK_INT >= 29) {
            // check the features
            JSONArray jfeatures = new JSONArray();
            Set<String> features = mediaFormat.getFeatures();
            //str.append("  features: [ ");
            for (String feature : features) {
                jfeatures.put(feature);
            }

            Set<String> keys = mediaFormat.getKeys();
            JSONObject jformats = new JSONObject();
            for (String key : keys) {
                int type = mediaFormat.getValueTypeForKey(key);
                switch (type) {
                    case MediaFormat.TYPE_BYTE_BUFFER:
                        jformats.put(key, "[bytebuffer] " + mediaFormat.getByteBuffer(key) );
                        break;
                    case MediaFormat.TYPE_FLOAT:
                        jformats.put(key, "[float] " + mediaFormat.getFloat(key) );
                        break;
                    case MediaFormat.TYPE_INTEGER:
                        jformats.put(key, "[integer] " + mediaFormat.getInteger(key) );
                        break;
                    case MediaFormat.TYPE_LONG:
                        jformats.put(key, "[long] " + mediaFormat.getLong(key) );
                        break;
                    case MediaFormat.TYPE_NULL:
                        jformats.put(key, "[null]");
                        break;
                    case MediaFormat.TYPE_STRING:
                        jformats.put(key, "[string] " + mediaFormat.getString(key) );
                        break;
                }

            }
            json.put("features", jfeatures);
            json.put("formats", jformats);

        } else {
            JSONObject jformats = new JSONObject();
            String[] keys = {
                    MediaFormat.KEY_BIT_RATE,
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaFormat.KEY_MIME,
                    MediaFormat.KEY_FRAME_RATE,
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaFormat.KEY_COLOR_RANGE,
                    MediaFormat.KEY_COLOR_STANDARD,
                    MediaFormat.KEY_COLOR_TRANSFER,
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    MediaFormat.KEY_LATENCY,
                    MediaFormat.KEY_LEVEL,
                    MediaFormat.KEY_PROFILE,
                    MediaFormat.KEY_SLICE_HEIGHT,
                    MediaFormat.KEY_TEMPORAL_LAYERING,
            };

            for (String key : keys) {
                if (!mediaFormat.containsKey(key)) {
                    continue;
                }
                String val = "";
                try {
                    val = mediaFormat.getString(key);
                } catch (ClassCastException ex1) {
                    try {
                        val = Integer.toString(mediaFormat.getInteger(key));
                    } catch (ClassCastException ex2) {
                        try {
                            val = Float.toString(mediaFormat.getFloat(key));
                        } catch (ClassCastException ex3) {
                            try {
                                val = Long.toString(mediaFormat.getLong(key));
                            } catch (ClassCastException ex4) {
                                continue;
                            }
                        }
                    }
                }
                if (val != null && val.length() > 0) {
                    jformats.put(key, val );
                }
            }
            json.put("formats", jformats);
        }
        return json;
    }


    public static String mediaFormatToString(MediaFormat mediaFormat) {
        StringBuilder str = new StringBuilder();
        str.append("MediaFormat {\n");
        if (Build.VERSION.SDK_INT >= 29) {
            // check the features
            Set<String> features = mediaFormat.getFeatures();
            str.append("  features: [ ");
            for (String feature : features) {
                str.append(feature + " ");
            }
            str.append("]\n");

            Set<String> keys = mediaFormat.getKeys();
            for (String key : keys) {
                int type = mediaFormat.getValueTypeForKey(key);
                switch (type) {
                    case MediaFormat.TYPE_BYTE_BUFFER:
                        str.append("  " + key + ": [bytebuffer] " + mediaFormat.getByteBuffer(key) + "\n");
                        break;
                    case MediaFormat.TYPE_FLOAT:
                        str.append("  " + key + ": [float] " + mediaFormat.getFloat(key) + "\n");
                        break;
                    case MediaFormat.TYPE_INTEGER:
                        str.append("  " + key + ": [integer] " + mediaFormat.getInteger(key) + "\n");
                        break;
                    case MediaFormat.TYPE_LONG:
                        str.append("  " + key + ": [long] " + mediaFormat.getLong(key) + "\n");
                        break;
                    case MediaFormat.TYPE_NULL:
                        str.append("  " + key + ": [null]\n");
                        break;
                    case MediaFormat.TYPE_STRING:
                        str.append("  " + key + ": [string] " + mediaFormat.getString(key) + "\n");
                        break;
                }

            }
        } else {
            String[] keys = {
                    MediaFormat.KEY_BIT_RATE,
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaFormat.KEY_MIME,
                    MediaFormat.KEY_FRAME_RATE,
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaFormat.KEY_COLOR_RANGE,
                    MediaFormat.KEY_COLOR_STANDARD,
                    MediaFormat.KEY_COLOR_TRANSFER,
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    MediaFormat.KEY_LATENCY,
                    MediaFormat.KEY_LEVEL,
                    MediaFormat.KEY_PROFILE,
                    MediaFormat.KEY_SLICE_HEIGHT,
                    MediaFormat.KEY_TEMPORAL_LAYERING,
            };

            for (String key : keys) {
                if (!mediaFormat.containsKey(key)) {
                    continue;
                }
                String val = "";
                try {
                    val = mediaFormat.getString(key);
                } catch (ClassCastException ex1) {
                    try {
                        val = Integer.toString(mediaFormat.getInteger(key));
                    } catch (ClassCastException ex2) {
                        try {
                            val = Float.toString(mediaFormat.getFloat(key));
                        } catch (ClassCastException ex3) {
                            try {
                                val = Long.toString(mediaFormat.getLong(key));
                            } catch (ClassCastException ex4) {
                                continue;
                            }
                        }
                    }
                }
                if (val != null && val.length() > 0) {
                    str.append("  " + key + ": " + val + "\n");
                }
            }
        }
        str.append("}");
        return str.toString();
    }

    private static String mediaFormatTypeToString(int type) {
        switch (type) {
            case MediaFormat.TYPE_BYTE_BUFFER:
                return "bytebuffer";
            case MediaFormat.TYPE_FLOAT:
                return "float";
            case MediaFormat.TYPE_INTEGER:
                return "integer";
            case MediaFormat.TYPE_LONG:
                return "long";
            case MediaFormat.TYPE_NULL:
                return "null";
            case MediaFormat.TYPE_STRING:
                return "string";
            default:
                return "Unknown: " + type;
        }
    }


    public static Dictionary<String, Object> mediaFormatComparison(MediaFormat
                                                                           current, MediaFormat newformat) {
        if (Build.VERSION.SDK_INT >= 29) {
            Dictionary<String, Object> formatChanges = new Hashtable<>();
            Set<String> keys = newformat.getKeys();
            for (String key : keys) {
                Object value = getMediaFormatValueFromKey(newformat, key);
                if (current == null) {
                    formatChanges.put(key, value);
                } else {
                    Object old = getMediaFormatValueFromKey(current, key);
                    if (!value.equals(old)) {
                        formatChanges.put(key, value);
                    }
                }
            }
            return formatChanges;
        }
        return new Hashtable<>();
    }


    public static Object getMediaFormatValueFromKey(MediaFormat format, String key) {
        if (Build.VERSION.SDK_INT >= 29) {
            int type = format.getValueTypeForKey(key);
            switch (type) {
                case MediaFormat.TYPE_BYTE_BUFFER:
                    return format.getByteBuffer(key);
                case MediaFormat.TYPE_FLOAT:
                    return format.getFloat(key, -1);
                case MediaFormat.TYPE_INTEGER:
                    return format.getInteger(key, -1);
                case MediaFormat.TYPE_LONG:
                    return format.getLong(key, -1);
                case MediaFormat.TYPE_NULL:
                    return null; //???
                case MediaFormat.TYPE_STRING:
                    return format.getString(key, "");
                default:
            }
        }
        return null;
    }

    public static JSONObject toJson(MediaCodecInfo media_codec_info) throws JSONException {
        JSONObject json = new JSONObject();
        StringBuilder str = new StringBuilder();
        json.put("name", media_codec_info.getName());
        if (Build.VERSION.SDK_INT >= 29) {
            json.put("canonical_name", media_codec_info.getCanonicalName());
            json.put("is_alias", media_codec_info.isAlias());
        }
        json.put("is_encoder", media_codec_info.isEncoder());
        if (Build.VERSION.SDK_INT >= 29) {
            json.put("is_hardware_accelerated", media_codec_info.isHardwareAccelerated());
            json.put("is_software_only", media_codec_info.isSoftwareOnly());
            json.put("is_vendor", media_codec_info.isVendor());
        }
        String[] media_types = media_codec_info.getSupportedTypes();
        for (String media_type : media_types) {
            json.put("media_type", codecCapabilitiesToJson(media_codec_info, media_type));
        }
        if (Build.VERSION.SDK_INT >= 31) {
                try {
                    MediaCodec codec = MediaCodec.createByCodecName(media_codec_info.getName());
                    List<String> params = codec.getSupportedVendorParameters();
                    JSONObject vendor_params = new JSONObject();
                    for (String param : params) {
                        MediaCodec.ParameterDescriptor descr = codec.getParameterDescriptor(param);
                        vendor_params.put(param, mediaFormatTypeToString(descr.getType()));
                    }
                    codec.release();
                    json.put("vendor_params", vendor_params);
                } catch (IOException iox) {
                }
        }
        return json;
    }



    private static Map<Integer, String> createAndroidImageFormatTable() {
        Map<Integer, String> m = new HashMap<Integer, String>();
        m.put(ImageFormat.DEPTH16, "DEPTH16");
        m.put(ImageFormat.DEPTH_JPEG, "DEPTH_JPEG");
        m.put(ImageFormat.DEPTH_POINT_CLOUD, "DEPTH_POINT_CLOUD");
        m.put(ImageFormat.FLEX_RGBA_8888, "FLEX_RGBA_8888");
        m.put(ImageFormat.FLEX_RGB_888, "FLEX_RGB_888");
        m.put(ImageFormat.HEIC, "HEIC");
        m.put(ImageFormat.JPEG, "JPEG");
        m.put(ImageFormat.NV16, "NV16");
        m.put(ImageFormat.NV21, "NV21");
        m.put(ImageFormat.PRIVATE, "PRIVATE");
        m.put(ImageFormat.RAW10, "RAW10");
        m.put(ImageFormat.RAW12, "RAW12");
        m.put(ImageFormat.RAW_PRIVATE, "RAW_PRIVATE");
        m.put(ImageFormat.RAW_SENSOR, "RAW_SENSOR");
        m.put(ImageFormat.RGB_565, "RGB_565");
        m.put(ImageFormat.UNKNOWN, "UNKNOWN");
        m.put(ImageFormat.Y8, "Y8");
        // Added in API level 31
        //m.put(ImageFormat.YCBCR_P010, "YCBCR_P010");
        m.put(ImageFormat.YUV_420_888, "YUV_420_888");
        m.put(ImageFormat.YUV_422_888, "YUV_422_888");
        m.put(ImageFormat.YUV_444_888, "YUV_444_888");
        m.put(ImageFormat.YUY2, "YUY2");
        m.put(ImageFormat.YV12, "YV12");
        return m;
    }

    final public static Map<Integer, String> androidImageFormatTable = createAndroidImageFormatTable();

    public static int frameSizeInBytes(PixFmt pix_fmt, int width, int height) {
        switch (pix_fmt.getNumber()) {
            case PixFmt.p010le_VALUE:
                // 24bit per pixel
                return width * height * 3;
            default:
                // 420 chroma compressed format
                return (int) (Math.ceil(width * height * 1.5));
        }
    }


    public static Test setCodecNameAndIdentifier(Test test) throws Exception {
        String partialName = test.getConfigure().getCodec();
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        Log.d(TAG, "Searching for partialName: \"" + partialName + "\" in codecList");
        Vector<MediaCodecInfo> matching = getMediaCodecInfos(codecInfos, partialName);

        if (matching.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("\nMultiple matching codecs for partialName: \"" + partialName + "\" codecs_matching: " + matching.size() + " ");
            sb.append("{");
            for (MediaCodecInfo info : matching) {
                sb.append(info.getName() + " ");
            }
            sb.append("}");
            Log.e(TAG, sb.toString());
            throw new Exception(sb.toString());
        } else if (matching.size() == 0) {
            Log.e(TAG, "No matching codecs for partialName: \"" + partialName + "\"");
            throw new Exception("No matching codecs for \"" + partialName + "\"");
        } else {
            Test.Builder builder = Test.newBuilder(test);
            // set the codec and mime types
            Configure configure = Configure.
                    newBuilder(test.
                            getConfigure())
                    .setCodec(matching.get(0).getName())
                    .setMime(matching.get(0).getSupportedTypes()[0]).build();
            builder.setConfigure(configure);
            return builder.build();
        }
    }

    @NonNull
    protected static Vector<MediaCodecInfo> getMediaCodecInfos(MediaCodecInfo[] codecInfos, String id) {
        Vector<MediaCodecInfo> matching = new Vector<>();
        for (MediaCodecInfo info : codecInfos) {
            //Handle special case of codecs with naming schemes consisting of substring of another

            if (info.isEncoder()) {
                if (info.getSupportedTypes().length > 0 &&
                        info.getSupportedTypes()[0].toLowerCase(Locale.US).contains("video")) {
                    if (info.getName().toLowerCase(Locale.US).equals(id.toLowerCase(Locale.US))) {
                        //Break on exact match
                        matching.clear();
                        matching.add(info);
                        break;
                    } else if (info.getName().toLowerCase(Locale.US).contains(id.toLowerCase(Locale.US))) {
                        matching.add(info);
                    }
                }
            }
        }
        return matching;
    }


}
