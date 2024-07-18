package com.facebook.encapp.utils;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Size;

import com.facebook.encapp.proto.Configure;
import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.DecoderConfigure;
import com.facebook.encapp.proto.Input;
import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.PixFmt;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.MediaCodecInfoHelper;

import java.util.List;


public class TestDefinitionHelper {
    private static final String TAG = "encapp";
    public static MediaFormat buildMediaFormat(Test test) {
        Configure config = test.getConfigure();
        Input input = test.getInput();
        Size targetResolution = (config.hasResolution()) ?
                SizeUtils.parseXString(config.getResolution()):
                SizeUtils.parseXString(test.getInput().getResolution());
        // start with the default MediaFormat
        Log.d(TAG, "mime: " +  config.getMime()  + ", res = " + targetResolution);
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(
                config.getMime(), targetResolution.getWidth(), targetResolution.getHeight());

        // optional config parameters
        if (config.hasBitrate()) {
            String bitrate  = config.getBitrate();
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, magnitudeToInt(bitrate));
        }
        if (input.hasFramerate()) {
            float framerate  = input.getFramerate();
            mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, framerate);
        }
        // good default: MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        if (config.hasBitrateMode()) {
            int bitrateMode = config.getBitrateMode().getNumber();
            mediaFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode);
        }
        // check if there is a durationUs value
        if (config.hasDurationUs()) {
            long duration_us = config.getDurationUs();
            mediaFormat.setLong(MediaFormat.KEY_DURATION, duration_us);
        }
        // check if there is a quality value
        if (config.hasQuality()) {
            int quality = config.getQuality();
            mediaFormat.setInteger(MediaFormat.KEY_QUALITY, quality);
        }
        // check if there is a complexity value
        if (config.hasComplexity()) {
            int complexity = config.getComplexity();
            mediaFormat.setInteger(MediaFormat.KEY_COMPLEXITY, complexity);
        }
        // check if there is an i-frame-interval value
        if (config.hasIFrameInterval()) {
            int iFrameInterval  = config.getIFrameInterval();
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        }
        // color parameters
        if (config.hasColorFormat()) {
            int colorFormat  = config.getColorFormat();
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        }
        // good default: MediaFormat.COLOR_RANGE_LIMITED
        if (config.hasColorRange()) {
            int colorRange  = config.getColorRange().getNumber();
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, colorRange);
        }
        // good default: MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        if (config.hasColorTransfer()) {
            int colorTransfer  = config.getColorTransfer().getNumber();
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer);
        }
        // good default: MediaFormat.COLOR_STANDARD_BT709
        if (config.hasColorStandard()) {
            int colorStandard  = config.getColorStandard().getNumber();
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard);
        }

        // set all the available values
        for (Parameter param : config.getParameterList()) {
            switch (param.getType().getNumber()) {
                case DataValueType.floatType_VALUE:
                    float fval = Float.parseFloat(param.getValue());
                    mediaFormat.setFloat(param.getKey(), fval);
                    break;
                case DataValueType.intType_VALUE:
                    int ival = TestDefinitionHelper.magnitudeToInt(param.getValue());
                    mediaFormat.setInteger(param.getKey(), ival);
                    break;
                case DataValueType.longType_VALUE:
                    long lval = Long.parseLong(param.getValue());
                    mediaFormat.setLong(param.getKey(), lval);
                    break;
                case DataValueType.stringType_VALUE:
                    mediaFormat.setString(param.getKey(), param.getValue());
                    break;
                default:
                    ///Should not be here
            }
        }
        return mediaFormat;
    }


    public static int magnitudeToInt(String text) {
        int index = text.indexOf("bps");

        if (index > 0) {
            text = text.substring(0, index).trim();
        } else {
            text = text.trim();
        }

        int val = 0;
        if (text == null) {
            return 0;
        } else if (text.endsWith("k")) {
                val = Integer.parseInt(text.substring(0, text.lastIndexOf('k')).trim()) * 1000;
        } else if (text.endsWith("M")) {
            val = Integer.parseInt(text.substring(0, text.lastIndexOf('M')).trim()) * 1000000;
        } else if (text.length() > 0){
            val = Integer.parseInt(text);
        }

        return val;
    }

    public static Test updateEncoderResolution(Test test, int width, int height) {
        Test.Builder builder = Test.newBuilder(test);
        builder.setConfigure(Configure.newBuilder(test.getConfigure()).setResolution(width + "x" + height));
        return builder.build();
    }

    public static Test updateInputSettings(Test test, MediaFormat mediaFormat) {
        Test.Builder builder = test.toBuilder();
        Input.Builder input = builder.getInput().toBuilder();
        input.setResolution(mediaFormat.getInteger(MediaFormat.KEY_WIDTH) + "x"  + mediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
        float framerate = 30.0f;
        try {
            framerate = mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE);
        } catch (Exception ex) {
            try {
                Log.e(TAG, "Failed to grab framerate as float.");
                framerate = (float) mediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
                Log.e(TAG, "framerate as int: " + framerate);
            } catch (Exception ex2) {
                Log.e(TAG, "Failed to grab framerate as int - just set 30 fps");
            }
        }
        input.setFramerate(framerate);
        builder.setInput(input);
        return builder.build();
    }

    public static Test updatePlayoutFrames(Test test, int frames) {
        Test.Builder builder = Test.newBuilder(test);
        builder.setInput(Input.newBuilder(test.getInput()).setPlayoutFrames(frames));
        return builder.build();
    }

    public static boolean checkBasicSettings(Test test) {
        // Make sure we have the most basic settings well defined
        Size res;
        Configure.Builder config = test.getConfigure().toBuilder();
        // make sure the input is well-defined
        Input.Builder input = test.getInput().toBuilder();
        // default configure.encode value is True
        if (!config.hasEncode() || (config.getEncode() == true)) {
            if (!input.hasResolution()) {
                throw new RuntimeException("No valid resolution on input settings");
            }
            if (!input.hasPixFmt()) {
                throw new RuntimeException("No valid pixel format on input settings");
            }
            if (!input.hasFramerate()) {
                throw new RuntimeException("No valid framerate on input settings");
            }
        }
        return true;
    }

    public static Test updateBasicSettings(Test test) {
        // get derived values
        Input.Builder input = test.getInput().toBuilder();
        Configure.Builder config = test.getConfigure().toBuilder();
        if (test.getConfigure().getEncode() && !config.hasBitrate()) {
            throw new RuntimeException("No valid bitrate on configuration settings");
        }

        if (!config.hasFramerate()) {
            config.setFramerate(input.getFramerate());
        }

        if (!config.hasIFrameInterval()) {
            config.setIFrameInterval(10);
        }
        if (!config.hasResolution()) {
            config.setResolution(input.getResolution());
        }
        if (!config.hasColorFormat()) {
            PixFmt pix_fmt = input.getPixFmt();
            int color_format = MediaCodecInfoHelper.mapEncappPixFmtToAndroidColorFormat(pix_fmt);
            config.setColorFormat(color_format);
        }

        Test.Builder builder = test.toBuilder();
        builder.setInput(input);
        builder.setConfigure(config);

        return builder.build();
    }


    public static void setDecoderConfigureParams(Test mTest, MediaFormat format) {
        DecoderConfigure config = mTest.getDecoderConfigure();

        List<Parameter> params = config.getParameterList();
        Log.d(TAG, "Set decoder config: " + params);
        for (Parameter param : params) {
            switch (param.getType().getNumber()) {
                case DataValueType.intType_VALUE:
                    format.setInteger(param.getKey(), Integer.parseInt(param.getValue()));
                    break;
                case DataValueType.stringType_VALUE:
                    format.setString(param.getKey(), param.getValue());
                    break;
            }
        }
    }
}
