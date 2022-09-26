package com.facebook.encapp.utils;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;


public class MediaCodecInfoHelper {
    final static int mIndentWidth = 2;

    public static String getIndentation(int indent) {
        String tab = "";
        if (indent > 0){
            tab = String.format("%" + (indent * mIndentWidth) + "s", ' ');
        }
        return tab;
    }

    final static List<Integer> mBitrateModeList = Arrays.asList(
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        //MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    );

    public static String bitrateModeToString(int bitrate_mode) {
        switch(bitrate_mode) {
            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR:
                return "MODE_CBR";
            //case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD:
                //return "MODE_CBR_FD";
            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ:
                return "MODE_CQ";
            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR:
                return "MODE_VBR";
            default:
                return bitrate_mode  + " is no bitrate mode";
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

    public static String encoderCapabilitiesToString(MediaCodecInfo.EncoderCapabilities encoder_capabilities, int indent) {
        if (encoder_capabilities == null) {
            return "";
        }

        String tab = getIndentation(indent);
        StringBuilder str = new StringBuilder();

        str.append(tab + "encoder_capabilities {\n");

        indent += 1;
        tab = getIndentation(indent);

        str.append(tab + "complexity_range: " + encoder_capabilities.getComplexityRange().toString() + "\n");
        str.append(tab + "quality_range: " + encoder_capabilities.getQualityRange().toString() + "\n");
        for (int bitrate_mode : mBitrateModeList) {
            str.append(tab + bitrateModeToString(bitrate_mode) + ": " + encoder_capabilities.isBitrateModeSupported(bitrate_mode) + "\n");
        }

        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();
    }

    public static String videoCapabilitiesToString(MediaCodecInfo.VideoCapabilities video_capabilities, int indent) {
        if (video_capabilities == null) {
            return "";
        }

        String tab = getIndentation(indent);
        StringBuilder str = new StringBuilder();

        str.append(tab + "video_capabilities {\n");

        indent += 1;
        tab = getIndentation(indent);

        str.append(tab + "bitrate_range: " + video_capabilities.getBitrateRange().toString() + "\n");
        str.append(tab + "height_alignment: " + video_capabilities.getHeightAlignment() + "\n");
        str.append(tab + "width_alignment: " + video_capabilities.getWidthAlignment() + "\n");
        str.append(tab + "supported_frame_rates: " + video_capabilities.getSupportedFrameRates().toString() + "\n");
        str.append(tab + "supported_heights: " + video_capabilities.getSupportedHeights().toString() + "\n");
        str.append(tab + "supported_widths: " + video_capabilities.getSupportedWidths().toString() + "\n");

        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();
    }

    public static String featuresToString(MediaCodecInfo.CodecCapabilities codec_capabilities, boolean required, int indent) {
        String tab = getIndentation(indent);
        StringBuilder str = new StringBuilder();

        if (required) {
            str.append(tab + "feature_required {\n");
        } else {
            str.append(tab + "feature_provided {\n");
        }

        indent += 1;
        tab = getIndentation(indent);

        for (String feature : mFeatureList) {
            if (required) {
                str.append(tab + feature + ": " + codec_capabilities.isFeatureRequired(feature) + "\n");
            } else {
                str.append(tab + feature + ": " + codec_capabilities.isFeatureSupported(feature) + "\n");
            }
        }

        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();
    }

    private static Map<Integer, String> createAndroidColorFormaNameTable() {
        Map<Integer, String> m = new HashMap<Integer,String>();
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
        Map<Integer, String> m = new HashMap<Integer,String>();
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

    public static String colorFormatsToString(int[] color_formats, int indent) {
        String tab = getIndentation(indent);
        StringBuilder str = new StringBuilder();

        str.append(tab + "color_formats {\n");
        indent += 1;
        tab = getIndentation(indent);
        for (int color_format : color_formats) {
            str.append(tab + "color {\n");
            indent += 1;
            tab = getIndentation(indent);
            str.append(tab + "format: " + color_format + "\n");
            if (androidColorFormatNameTable.containsKey(color_format)) {
                str.append(tab + "name: " + androidColorFormatNameTable.get(color_format) + "\n");
            } else if (altColorFormatNameTable.containsKey(color_format)) {
                str.append(tab + "name: " + altColorFormatNameTable.get(color_format) + "\n");
            }
            indent -= 1;
            tab = getIndentation(indent);
            str.append(tab + "}\n");
        }
        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();
    }

    public static String profileLevelsToString(MediaCodecInfo.CodecProfileLevel[] color_profile_level, int indent) {
        String tab = getIndentation(indent);
        StringBuilder str = new StringBuilder();

        str.append(tab + "profile_levels {\n");
        indent += 1;
        tab = getIndentation(indent);

        for (MediaCodecInfo.CodecProfileLevel profile_level : color_profile_level) {
            str.append(tab + "profile_level {\n");
            indent += 1;
            tab = getIndentation(indent);
            str.append(tab + "profile: " + profile_level.profile + "\n");
            str.append(tab + "level: " + profile_level.level + "\n");
            indent -= 1;
            tab = getIndentation(indent);
            str.append(tab + "}\n");
        }
        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();
    }

    public static String codecCapabilitiesToText(MediaCodecInfo media_codec_info, String media_type, int indent) {
        String tab = getIndentation(indent);
        StringBuilder str = new StringBuilder();

        MediaCodecInfo.CodecCapabilities codec_capabilities = media_codec_info.getCapabilitiesForType(media_type);

        str.append(tab + "media_type {\n");
        indent += 1;
        tab = getIndentation(indent);

        str.append(tab + "media_type: " + media_type + "\n");
        str.append(tab + "mime_type: " + codec_capabilities.getMimeType() + "\n");
        str.append(tab + "max_supported_instances: " + codec_capabilities.getMaxSupportedInstances() + "\n");

        // print fields
        str.append(colorFormatsToString(codec_capabilities.colorFormats, indent));
        str.append(profileLevelsToString(codec_capabilities.profileLevels, indent));

        MediaFormat format = codec_capabilities.getDefaultFormat();
        //Odds are that if there is no default profile - nothing else will have defaults anyway...
        if (format.getString(MediaFormat.KEY_PROFILE) != null) {
            str.append("\nDefault settings:");
            str.append(getFormatInfo(format));
        }

        // print encoder capabilities
        str.append(encoderCapabilitiesToString(codec_capabilities.getEncoderCapabilities(), indent));
        // print encoder capabilities
        str.append(videoCapabilitiesToString(codec_capabilities.getVideoCapabilities(), indent));

        // print features required and supported
        str.append(featuresToString(codec_capabilities, true, indent));
        str.append(featuresToString(codec_capabilities, false, indent));

        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();
    }


    public static String getFormatInfo(MediaFormat format) {

        StringBuilder str = new StringBuilder();
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
                MediaFormat.KEY_SLICE_HEIGHT,
                MediaFormat.KEY_TEMPORAL_LAYERING,
        };

        for (String key : keys) {
            if (format.containsKey(key)) {
                String val="";
                try {
                    val = format.getString(key);
                } catch (ClassCastException ex1) {
                    try {
                        val = Integer.toString(format.getInteger(key));
                    } catch (ClassCastException ex2) {
                        try {
                            val = Float.toString(format.getFloat(key));
                        } catch (ClassCastException ex3) {
                            try {
                                val = Long.toString(format.getLong(key));
                            } catch (ClassCastException ex4) {
                                continue;
                            }
                        }

                    }
                }
                if (val != null && val.length() > 0) {
                    str.append("\n" + key + ": " + val);
                }
            }
        }

        return str.toString();
    }


    public static String toText(MediaCodecInfo media_codec_info, int indent) {
        String tab = getIndentation(indent);

        // TODO(johan): from Android 10 (api 29) we can check
        // codec type (hw or sw codec)
        StringBuilder str = new StringBuilder();
        str.append(tab + "MediaCodec {\n");
        indent += 1;
        tab = getIndentation(indent);
        str.append(tab + "name: " + media_codec_info.getName() + "\n");
        if (Build.VERSION.SDK_INT >= 29) {
            str.append(tab + "canonical_name: " + media_codec_info.getCanonicalName() + "\n");
            str.append(tab + "is_alias: " + media_codec_info.isAlias() + "\n");
        }
        str.append(tab + "is_encoder: " + media_codec_info.isEncoder() + "\n");
        if (Build.VERSION.SDK_INT >= 29) {
            str.append(tab + "is_hardware_accelerated: " + media_codec_info.isHardwareAccelerated() + "\n");
            str.append(tab + "is_software_only: " + media_codec_info.isSoftwareOnly() + "\n");
            str.append(tab + "is_vendor: " + media_codec_info.isVendor() + "\n");
        }
        String[] media_types = media_codec_info.getSupportedTypes();
        for (String media_type : media_types) {
            str.append(codecCapabilitiesToText(media_codec_info, media_type, indent));
        }
        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();

    }
}
