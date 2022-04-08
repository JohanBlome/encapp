package com.facebook.encapp.utils;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Range;

import java.util.List;
import java.util.Arrays;


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

    final static List<String> mFeatureList = Arrays.asList(new String[]{
        MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,
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
        MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
    });

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
            switch (color_format) {
                case MediaCodecInfo.CodecCapabilities.COLOR_Format12bitRGB444:
                    str.append(tab + "name: COLOR_Format12bitRGB444\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitARGB1555:
                    str.append(tab + "name: COLOR_Format16bitARGB1555\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitARGB4444:
                    str.append(tab + "name: COLOR_Format16bitARGB4444\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitBGR565:
                    str.append(tab + "name: COLOR_Format16bitBGR565\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitRGB565:
                    str.append(tab + "name: COLOR_Format16bitRGB565\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format18BitBGR666:
                    str.append(tab + "name: COLOR_Format18BitBGR666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format18bitARGB1665:
                    str.append(tab + "name: COLOR_Format18bitARGB1665\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format18bitRGB666:
                    str.append(tab + "name: COLOR_Format18bitRGB666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format19bitARGB1666:
                    str.append(tab + "name: COLOR_Format19bitARGB1666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24BitABGR6666:
                    str.append(tab + "name: COLOR_Format24BitABGR6666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24BitARGB6666:
                    str.append(tab + "name: COLOR_Format24BitARGB6666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24bitARGB1887:
                    str.append(tab + "name: COLOR_Format24bitARGB1887\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24bitBGR888:
                    str.append(tab + "name: COLOR_Format24bitBGR888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24bitRGB888:
                    str.append(tab + "name: COLOR_Format24bitRGB888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format25bitARGB1888:
                    str.append(tab + "name: COLOR_Format25bitARGB1888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR8888:
                    str.append(tab + "name: COLOR_Format32bitABGR8888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888:
                    str.append(tab + "name: COLOR_Format32bitARGB8888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format32bitBGRA8888:
                    str.append(tab + "name: COLOR_Format32bitBGRA8888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format8bitRGB332:
                    str.append(tab + "name: COLOR_Format8bitRGB332\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatCbYCrY:
                    str.append(tab + "name: COLOR_FormatCbYCrY\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatCrYCbY:
                    str.append(tab + "name: COLOR_FormatCrYCbY\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL16:
                    str.append(tab + "name: COLOR_FormatL16\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL2:
                    str.append(tab + "name: COLOR_FormatL2\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL24:
                    str.append(tab + "name: COLOR_FormatL24\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL32:
                    str.append(tab + "name: COLOR_FormatL32\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL4:
                    str.append(tab + "name: COLOR_FormatL4\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL8:
                    str.append(tab + "name: COLOR_FormatL8\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatMonochrome:
                    str.append(tab + "name: COLOR_FormatMonochrome\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBAFlexible:
                    str.append(tab + "name: COLOR_FormatRGBAFlexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBFlexible:
                    str.append(tab + "name: COLOR_FormatRGBFlexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer10bit:
                    str.append(tab + "name: COLOR_FormatRawBayer10bit\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer8bit:
                    str.append(tab + "name: COLOR_FormatRawBayer8bit\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer8bitcompressed:
                    str.append(tab + "name: COLOR_FormatRawBayer8bitcompressed\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface:
                    str.append(tab + "name: COLOR_FormatSurface\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYCbYCr:
                    str.append(tab + "name: COLOR_FormatYCbYCr\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYCrYCb:
                    str.append(tab + "name: COLOR_FormatYCrYCb\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411PackedPlanar:
                    str.append(tab + "name: COLOR_FormatYUV411PackedPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411Planar:
                    str.append(tab + "name: COLOR_FormatYUV411Planar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible:
                    str.append(tab + "name: COLOR_FormatYUV420Flexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                    str.append(tab + "name: COLOR_FormatYUV420PackedPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
                    str.append(tab + "name: COLOR_FormatYUV420PackedSemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                    str.append(tab + "name: COLOR_FormatYUV420Planar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    str.append(tab + "name: COLOR_FormatYUV420SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Flexible:
                    str.append(tab + "name: COLOR_FormatYUV422Flexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedPlanar:
                    str.append(tab + "name: COLOR_FormatYUV422PackedPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedSemiPlanar:
                    str.append(tab + "name: COLOR_FormatYUV422PackedSemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar:
                    str.append(tab + "name: COLOR_FormatYUV422Planar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar:
                    str.append(tab + "name: COLOR_FormatYUV422SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible:
                    str.append(tab + "name: COLOR_FormatYUV444Flexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved:
                    str.append(tab + "name: COLOR_FormatYUV444Interleaved\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar:
                    str.append(tab + "name: COLOR_QCOM_FormatYUV420SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                    str.append(tab + "name: COLOR_TI_FormatYUV420PackedSemiPlanar\n");
                    break;
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
        str.append(tab + "canonical_name: " + media_codec_info.getCanonicalName() + "\n");
        str.append(tab + "is_alias: " + media_codec_info.isAlias() + "\n");
        str.append(tab + "is_encoder: " + media_codec_info.isEncoder() + "\n");
        str.append(tab + "is_hardware_accelerated: " + media_codec_info.isHardwareAccelerated() + "\n");
        str.append(tab + "is_software_only: " + media_codec_info.isSoftwareOnly() + "\n");
        str.append(tab + "is_vendor: " + media_codec_info.isVendor() + "\n");
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

