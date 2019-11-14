// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.encapp;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;

class VideoConstraints {

    // QCOM specific
    public final static int OMX_TI_COLOR_FormatYUV420PackedSemiPlanar = 0x7F000100;
    public final static int OMX_QCOM_COLOR_FormatYVU420SemiPlanar = 0x7FA30C00;
    public final static int OMX_QCOM_COLOR_FormatYUV420PackedSemiPlanar64x32Tile2m8ka = 0x7FA30C03;
    public final static int OMX_SEC_COLOR_FormatNV12Tiled = 0x7FC00002;
    public final static int OMX_QCOM_COLOR_FormatYUV420PackedSemiPlanar32m = 0x7FA30C04;

    private String mVideoEncoderIdentifier;
    private int mBitRate;
    private Size mVideoSize; // Used for resolution values
    private Size mVideoScaleSize; // Used for resolution values
    private int mKeyframeRate;
    private float mFPS;
    private float mReferenceFPS;
    private int mBitrateMode =  MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    private boolean mSkipFrames = false;
    private int mLtrCount = 4;
    private int mHierStructLayers = 0;

    //Bitrate mode 3,4 is
    //OMX_Video_ControlRateVariableSkipFrames,
    //OMX_Video_ControlRateConstantSkipFrames,
    public static int BITRATE_MODE_VBR_SKIP_FRAMES = 3;
    public static int BITRATE_MODE_CBR_SKIP_FRAMES = 4;

    private int mProfile = -1;
    private int mProfileLevel = -1;
    private int mColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    // Bit rate
    public void setBitRate(int bitRate) {
        this.mBitRate = bitRate;
    }

    private int getBitRate() {
        return mBitRate;
    }

    // Resolution
    public void setVideoSize(Size videoSize) {
        this.mVideoSize = videoSize;
    }

    public Size getVideoSize() {
        return mVideoSize;
    }

    public void setVideoScaleSize(Size videoSize) {
        this.mVideoScaleSize = videoSize;
    }

    public Size getVideoScalingSize() {
        return mVideoScaleSize;
    }


    // Keyframe rate
    public void setKeyframeRate(int keyframeRate) {
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

    public int getHierStructLayers() {
        return mHierStructLayers;
    }

    public String getVideoEncoderIdentifier() {
        Log.d("encapp", "Reading the encoder identifier: "+ mVideoEncoderIdentifier);
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

    public void setHierStructLayerCount(int count) {
        mHierStructLayers = count;
    }


    public int getLTRCount() {
        return mLtrCount;
    }

    private int getmBitrateMode() {
        return mBitrateMode;
    }

    public int getProfile() {
        return mProfile;
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

        Log.d("Transcoder", "Create mode: br="+getBitRate()+", mode="+getmBitrateMode()+", fps="+getFPS()+", i int="+getKeyframeRate());
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
        return this.getVideoEncoderIdentifier()+", "+mVideoSize+", "+ mBitRate+", "+mBitrateMode;
    }
}
