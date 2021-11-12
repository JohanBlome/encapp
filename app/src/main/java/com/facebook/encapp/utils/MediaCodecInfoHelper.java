package com.facebook.encapp;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import com.facebook.encapp.utils.TestParams;


public class MediaCodecInfoHelper {
    public static String codecCapabilitiesToText(String type, MediaCodecInfo.CodecCapabilities cap, int tab_length) {
        String tab = String.format("%" + (tab_length * 4) + "s", "");
        String tab2 = String.format("%" + ((tab_length + 1) * 4) + "s", "");

        StringBuilder str = new StringBuilder();
        str.append(tab + "mime_type: " + type + "\n");
        str.append(tab + "max_supported_instances: " + cap.getMaxSupportedInstances() + "\n");

        for (int col : cap.colorFormats) {
            str.append(tab + "color {\n");
            str.append(tab2 + "format: " + col + "\n");
            switch (col) {
                case MediaCodecInfo.CodecCapabilities.COLOR_Format12bitRGB444:
                    str.append(tab2 + "name: COLOR_Format12bitRGB444\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitARGB1555:
                    str.append(tab2 + "name: COLOR_Format16bitARGB1555\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitARGB4444:
                    str.append(tab2 + "name: COLOR_Format16bitARGB4444\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitBGR565:
                    str.append(tab2 + "name: COLOR_Format16bitBGR565\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format16bitRGB565:
                    str.append(tab2 + "name: COLOR_Format16bitRGB565\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format18BitBGR666:
                    str.append(tab2 + "name: COLOR_Format18BitBGR666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format18bitARGB1665:
                    str.append(tab2 + "name: COLOR_Format18bitARGB1665\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format18bitRGB666:
                    str.append(tab2 + "name: COLOR_Format18bitRGB666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format19bitARGB1666:
                    str.append(tab2 + "name: COLOR_Format19bitARGB1666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24BitABGR6666:
                    str.append(tab2 + "name: COLOR_Format24BitABGR6666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24BitARGB6666:
                    str.append(tab2 + "name: COLOR_Format24BitARGB6666\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24bitARGB1887:
                    str.append(tab2 + "name: COLOR_Format24bitARGB1887\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24bitBGR888:
                    str.append(tab2 + "name: COLOR_Format24bitBGR888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24bitRGB888:
                    str.append(tab2 + "name: COLOR_Format24bitRGB888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format25bitARGB1888:
                    str.append(tab2 + "name: COLOR_Format25bitARGB1888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR8888:
                    str.append(tab2 + "name: COLOR_Format32bitABGR8888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888:
                    str.append(tab2 + "name: COLOR_Format32bitARGB8888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format32bitBGRA8888:
                    str.append(tab2 + "name: COLOR_Format32bitBGRA8888\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format8bitRGB332:
                    str.append(tab2 + "name: COLOR_Format8bitRGB332\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatCbYCrY:
                    str.append(tab2 + "name: COLOR_FormatCbYCrY\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatCrYCbY:
                    str.append(tab2 + "name: COLOR_FormatCrYCbY\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL16:
                    str.append(tab2 + "name: COLOR_FormatL16\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL2:
                    str.append(tab2 + "name: COLOR_FormatL2\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL24:
                    str.append(tab2 + "name: COLOR_FormatL24\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL32:
                    str.append(tab2 + "name: COLOR_FormatL32\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL4:
                    str.append(tab2 + "name: COLOR_FormatL4\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatL8:
                    str.append(tab2 + "name: COLOR_FormatL8\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatMonochrome:
                    str.append(tab2 + "name: COLOR_FormatMonochrome\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBAFlexible:
                    str.append(tab2 + "name: COLOR_FormatRGBAFlexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBFlexible:
                    str.append(tab2 + "name: COLOR_FormatRGBFlexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer10bit:
                    str.append(tab2 + "name: COLOR_FormatRawBayer10bit\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer8bit:
                    str.append(tab2 + "name: COLOR_FormatRawBayer8bit\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatRawBayer8bitcompressed:
                    str.append(tab2 + "name: COLOR_FormatRawBayer8bitcompressed\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface:
                    str.append(tab2 + "name: COLOR_FormatSurface\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYCbYCr:
                    str.append(tab2 + "name: COLOR_FormatYCbYCr\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYCrYCb:
                    str.append(tab2 + "name: COLOR_FormatYCrYCb\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411PackedPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV411PackedPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV411Planar:
                    str.append(tab2 + "name: COLOR_FormatYUV411Planar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible:
                    str.append(tab2 + "name: COLOR_FormatYUV420Flexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV420PackedPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV420PackedSemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                    str.append(tab2 + "name: COLOR_FormatYUV420Planar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV420SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Flexible:
                    str.append(tab2 + "name: COLOR_FormatYUV422Flexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV422PackedPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedSemiPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV422PackedSemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar:
                    str.append(tab2 + "name: COLOR_FormatYUV422Planar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV422SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible:
                    str.append(tab2 + "name: COLOR_FormatYUV444Flexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved:
                    str.append(tab2 + "name: COLOR_FormatYUV444Interleaved\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar:
                    str.append(tab2 + "name: COLOR_QCOM_FormatYUV420SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                    str.append(tab2 + "name: COLOR_TI_FormatYUV420PackedSemiPlanar\n");
                    break;
            }
            str.append(tab + "}\n");
        }

        for (MediaCodecInfo.CodecProfileLevel prof : cap.profileLevels) {
            str.append(tab + "profile {\n");
            str.append(tab2 + "profile: " + prof.profile + "\n");
            str.append(tab2 + "level: " + prof.level + "\n");
            str.append(tab + "}\n");
        }

        MediaFormat format = cap.getDefaultFormat();
        //Odds are that if there is no default profile - nothing else will have defaults anyway...
        if (format.getString(MediaFormat.KEY_PROFILE) != null) {
            str.append("\nDefault settings:");
            str.append(TestParams.getFormatInfo(format));
        }
        return str.toString();
    }

    public static String toText(MediaCodecInfo media_codec_info, int tab_length) {
        String tab = String.format("%" + (tab_length * 4) + "s", "");
        String tab2 = String.format("%" + ((tab_length + 1) * 4) + "s", "");

        // TODO(johan): from Android 10 (api 29) we can check
        // codec type (hw or sw codec)
        StringBuilder str = new StringBuilder();
        str.append(tab + "MediaCodec {\n");
        str.append(tab2 + "name: " + media_codec_info.getName() + "\n");
        String[] types = media_codec_info.getSupportedTypes();
        for (String tp : types) {
            str.append(tab2 + "type {\n");
            str.append(codecCapabilitiesToText(tp, media_codec_info.getCapabilitiesForType(tp), tab_length + 2));
            str.append(tab2 + "}\n");
        }
        str.append(tab + "}\n");
        return str.toString();
    }
}

