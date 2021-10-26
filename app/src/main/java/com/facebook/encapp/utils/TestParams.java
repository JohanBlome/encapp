// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.encapp.utils;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

public class TestParams {
    final static String TAG = "encapp";
    // QCOM specific
    public final static int OMX_TI_COLOR_FormatYUV420PackedSemiPlanar = 0x7F000100;
    public final static int OMX_QCOM_COLOR_FormatYVU420SemiPlanar = 0x7FA30C00;
    public final static int OMX_QCOM_COLOR_FormatYUV420PackedSemiPlanar64x32Tile2m8ka = 0x7FA30C03;
    public final static int OMX_SEC_COLOR_FormatNV12Tiled = 0x7FC00002;
    public final static int OMX_QCOM_COLOR_FormatYUV420PackedSemiPlanar32m = 0x7FA30C04;

    public enum IFRAME_SIZE_PRESETS{
        DEFAULT,
        MEDIUM,
        HUGE,
        UNLIMITED,
    }

    private String mVideoEncoderIdentifier;
    private int mBitRate;
    private Size mVideoSize; // Used for resolution values
    private Size mVideoScaleSize; // Used for resolution values
    private int mKeyframeRate;
    private float mFPS;
    private float mReferenceFPS;
    private int mBitrateMode =  MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    private boolean mSkipFrames = false;
    private int mLtrCount = 1;
    private int mColorRange = MediaFormat.COLOR_RANGE_LIMITED;
    private int mColorStandard = MediaFormat.COLOR_STANDARD_BT601_NTSC;
    private int mColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
    private IFRAME_SIZE_PRESETS mIframeSize = IFRAME_SIZE_PRESETS.DEFAULT;
    //Bitrate mode 3,4 is
    //OMX_Video_ControlRateVariableSkipFrames,
    //OMX_Video_ControlRateConstantSkipFrames,
    public static final int BITRATE_MODE_VBR_SKIP_FRAMES = 3;
    public static final int BITRATE_MODE_CBR_SKIP_FRAMES = 4;

    private int mProfile = -1;
    private int mProfileLevel = -1;
    private int mColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    private int mTemporalLayerCount = 1;
    private String mInputfile = null;
    private Size mRefSize = null;
    private String mDynamic = "";
    private int mLoopCount = 0;
    private String mDescription = "";
    private ArrayList<ConfigureParam> mExtraConfigure = new ArrayList<>();
    ArrayList<RuntimeParam> mRuntimeParams;

    // Bit rate
    public void setBitRate(int bitRate) {
        this.mBitRate = bitRate;
    }

    public int getBitRate() {
        return mBitRate;
    }

    // Resolution
    public void setVideoSize(Size videoSize) {
        this.mVideoSize = videoSize;
    }

    public Size getVideoSize() {
        return mVideoSize;
    }

    // Keyframe rate
    public void setKeyframeInterval(int keyframeRate) {
        this.mKeyframeRate = keyframeRate;
    }

    public int getKeyframeRate() {
        return mKeyframeRate;
    }

    // FPS
    public void setFPS(float fps) {
        this.mFPS = fps;
    }

    public void setReferenceFPS(float fps) {
        this.mReferenceFPS = fps;
    }

    public float getFPS() {
        return mFPS;
    }

    public float getmReferenceFPS() {
        return mReferenceFPS;
    }

    public String getVideoEncoderIdentifier() {
        return mVideoEncoderIdentifier;
    }

    public void setVideoEncoderIdentifier(String videoCodecIdentifier){
        mVideoEncoderIdentifier = videoCodecIdentifier;

    }

    public void setConstantBitrate(boolean setCBR) {
        mBitrateMode = (setCBR)?  MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR:
                                  MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
        if(mSkipFrames) mBitrateMode += 2;
    }

    public void setSkipFrames(boolean setSkipFrames) {
        mSkipFrames = setSkipFrames;
        Log.d("Transcoder", "setSkipFrames " + mSkipFrames);
        mBitrateMode = (mBitrateMode % 3); //There are three values in the api and qualcomm
                                           // is using value 1,2 + 2 for corresponding skipframe alternative
        if(mSkipFrames) mBitrateMode += 2;
        Log.d("Transcoder", "setBitrate " + mBitrateMode);
    }

    public void setLTRCount(int count) {
        mLtrCount = count;
    }


    public int getLTRCount() {
        return mLtrCount;
    }

    public int getmBitrateMode() {
        return mBitrateMode;
    }

    public int getProfile() {
        return mProfile;
    }

    public void setColorRange(int colorRange) {
        mColorFormat = colorRange;
    }

    public int getColorRange() {
        return mColorRange;
    }

    public void setColorTransfer(int colorTransfer) {
        mColorTransfer = colorTransfer;
    }

    public int getColorTransfer() {
        return mColorTransfer;
    }

    public void setColorStandard(int colorStandard) {
        mColorStandard = colorStandard;
    }

    public int getColorStandard() {
        return mColorStandard;
    }

    /**
     * Sets encoder profile. Can be one of constants declared in
     * {@link MediaCodecInfo.CodecProfileLevel}.
     * @param profile
     */
    public void setProfile(int profile) {
        mProfile = profile;
    }

    public int getProfileLevel() {
        return mProfileLevel;
    }

    public void setProfileLevel(int profileLevel) {
        this.mProfileLevel = profileLevel;
    }

    public int getColorFormat() {
        return mColorFormat;
    }

    public void setColorFormat(int colorFormat) {
        this.mColorFormat = colorFormat;
    }


    public MediaFormat createEncoderMediaFormat(int width, int height) {
        MediaFormat encoderFormat = MediaFormat.createVideoFormat(
                getVideoEncoderIdentifier(), width, height);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, getBitRate());
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, (int)getFPS());
        encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, mBitrateMode);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, getKeyframeRate());
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, mColorRange);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, mColorStandard);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, mColorTransfer);
        Log.d(TAG, "Create mode: br="+getBitRate() +
                ", mode=" + getmBitrateMode() +
                ", fps=" + getFPS() +
                ", i int=" + getKeyframeRate() + " sec" +
                ", color range: " + mColorRange +
                ", color format: " + mColorFormat +
                ", color standard: " + mColorStandard +
                ", color transfer: " + mColorTransfer);
        return encoderFormat;
    }

    public MediaFormat createDecoderMediaFormat(int width, int height, ByteBuffer codecCsd0) {
        MediaFormat decoderFormat = MediaFormat.createVideoFormat(
                getVideoEncoderIdentifier(), width, height);
        if (codecCsd0.limit() > 0) {
            decoderFormat.setByteBuffer("csd-0", codecCsd0);
        }

        return decoderFormat;
    }

    public String getSettings() {
        return this.getVideoEncoderIdentifier() + ", " +
                    mVideoSize + ", " +
                    mBitRate + " kbps, " +
                    bitrateModeName() + ", " +
                    mFPS + " fps, key int:" +
                    mKeyframeRate;
    }

    String bitrateModeName() {
        switch(mBitrateMode) {
            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ:
                return "BITRATE_MODE_CQ";

            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR:
                return "BITRATE_MODE_VBR";

            case MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR:
                return "BITRATE_MODE_CBR";

            case BITRATE_MODE_VBR_SKIP_FRAMES:
                return "BITRATE_MODE_VBR_SKIP_FRAMES";

            case BITRATE_MODE_CBR_SKIP_FRAMES:
                return "BITRATE_MODE_CBR_SKIP_FRAMES";
        }
        return "Unknown bitrate mode: " + mBitrateMode;
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
                                    Log.d(TAG, "Failed to get key: " + key);
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


    public void setIframeSizePreset(IFRAME_SIZE_PRESETS preset) {
        mIframeSize = preset;
    }

    public IFRAME_SIZE_PRESETS getIframeSizePreset() {
        return mIframeSize;
    }

    public void setTemporalLayerCount( int temporalLayerCount ) {
        mTemporalLayerCount = temporalLayerCount;
    }
    public int getTemporalLayerCount() {
        return mTemporalLayerCount;
    }

    public void setRuntimeParameters(ArrayList<RuntimeParam> params) {
        mRuntimeParams = params;
    }

    public HashMap<Integer, ArrayList<RuntimeParam>> getRuntimeParameters() {
        //Sort and return a HashMap
        HashMap<Integer, ArrayList<RuntimeParam>> map = new HashMap<>();
        if (mRuntimeParams == null) {
            Log.w(TAG, "Runtime parameters are null. No cli runtime parameters supported.");
            return map;
        }
        for (RuntimeParam param: mRuntimeParams) {
            Integer frame = Integer.valueOf(param.frame);
            ArrayList<RuntimeParam> list = map.get(frame);
            if (list == null) {
                list = new ArrayList<RuntimeParam>();
                map.put(frame, list);
            }
            Log.d(TAG, "Add " + param.name + " @ "+frame + " val: " + (Integer)param.value);
            list.add(param);
        }
        return map;
    }

    public void setInputfile(String inputfile) {
        // Assume that the file is in the root of /sdcard/
        String[] splits = inputfile.split("/");
        mInputfile = "/sdcard/" + splits[splits.length-1];
    }

    public String getInputfile() {
        return mInputfile;
    }


    public void setReferenceSize(Size ref) {
        mRefSize = ref;
    }

    public Size getReferenceSize() {
        return mRefSize;
    }

    public void setDynamic(String dynamic) {
        mDynamic = dynamic;
    }

    public String getDynamic() {
        return mDynamic;
    }

    public void setLoopCount(int count) {
        mLoopCount = count;
    }
    public int getLoopCount() {
        return mLoopCount;
    }

    public void setDescription(String description) {
        mDescription = description;
    }

    public String getDescription(){
        return mDescription;
    }

    public void setExtraConfigure(ArrayList<ConfigureParam> extra) {
        mExtraConfigure = extra;
    }

    public ArrayList<ConfigureParam> getExtraConfigure() {
        return mExtraConfigure;
    }
}


