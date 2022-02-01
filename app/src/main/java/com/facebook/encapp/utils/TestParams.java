// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.encapp.utils;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

public class TestParams {
    final static String TAG = "TestParams";

    public enum IFRAME_SIZE_PRESETS{
        DEFAULT,
        MEDIUM,
        HUGE,
        UNLIMITED,
    }

    private String mVideoEncoderIdentifier;
    private int mBitRate;
    private Size mVideoSize = null; // Used for resolution values
    private int mKeyframeRate;
    private float mFPS;
    private float mReferenceFPS;
    private int mBitrateMode =  MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    private boolean mSkipFrames = false;
    private int mLtrCount = 1;
    private String mCodecName = "";

    //Bitrate mode 3,4 is
    //OMX_Video_ControlRateVariableSkipFrames,
    //OMX_Video_ControlRateConstantSkipFrames,
    public static final int BITRATE_MODE_VBR_SKIP_FRAMES = 3;
    public static final int BITRATE_MODE_CBR_SKIP_FRAMES = 4;

    private int mProfile = -1;
    private int mProfileLevel = -1;
    private int mTemporalLayerCount = 1;
    private String mInputfile = null;
    private Size mRefSize = null;
    private String mDynamic = "";
    private int mLoopCount = 0;
    private int mDurationSec = -1;
    private int mDurationFrames = -1;
    private String mDescription = "";
    private ArrayList<ConfigureParam> mEncoderConfigure = new ArrayList<>();
    private ArrayList<ConfigureParam> mDecoderConfigure = new ArrayList<>();
    private ArrayList<Object> mRuntimeParams;
    private ArrayList<Object> mDecoderRuntimeParams;
    private int mConcurrentCodings = 1;
    private String mDecoder = "";
    private boolean mRealtime = false;
    private boolean mUseSurfaceencoding = false;

    private int mPursuit = 0;
    private boolean mNoEncoding = false;

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
        if (mVideoSize == null) {
            return mRefSize;
        }

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
        Log.d(TAG, "Get video codec ident: " +mVideoEncoderIdentifier );
        return mVideoEncoderIdentifier;
    }

    public void setVideoEncoderIdentifier(String videoCodecIdentifier){
        Log.d(TAG, "Set video codec ident: " +videoCodecIdentifier );
        mVideoEncoderIdentifier = videoCodecIdentifier;
    }

    public String getCodecName() {
        return mCodecName;
    }

    public void setCodecName(String codecName) {
        this.mCodecName = codecName;
    }

    /* vbr, cbr or cq */
    public void setBitrateMode(String mode) {
        mBitrateMode = findBitrateMode(mode);
    }

    public void setSkipFrames(boolean setSkipFrames) {
        mSkipFrames = setSkipFrames;
        mBitrateMode = (mBitrateMode % 3); //There are three values in the api and qualcomm
                                           // is using value 1,2 + 2 for corresponding skipframe alternative
        if(mSkipFrames) mBitrateMode += 2;
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

    public MediaFormat createEncoderMediaFormat(int width, int height) {
        MediaFormat encoderFormat = MediaFormat.createVideoFormat(
                getVideoEncoderIdentifier(), width, height);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, getBitRate());
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, (int)getFPS());
        encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, mBitrateMode);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, getKeyframeRate());

        Log.d(TAG, "Create mode: br="+getBitRate() +
                ", mode=" + getmBitrateMode() +
                ", fps=" + getFPS() +
                ", i int=" + getKeyframeRate() + " sec");
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
        return getCodecName() + ", " +
                mVideoSize + ", " +
                mBitRate + " kbps, " +
                bitrateModeName() + ", " +
                mFPS + " fps, key int:" +
                mKeyframeRate;
    }

    int findBitrateMode(String name) {
        if (name.toLowerCase().contains("vbr_skip")) {
            return BITRATE_MODE_VBR_SKIP_FRAMES;
        } else if (name.toLowerCase().contains("cbr_skip")) {
            return BITRATE_MODE_CBR_SKIP_FRAMES;
        } else if (name.toLowerCase().contains("vbr")) {
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
        } else if (name.toLowerCase().contains("cbr")) {
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
        } else if (name.toLowerCase().contains("cq")) {
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ;
        } else {
            Log.e(TAG, "Unknown bitrate mode: "  + name + " use vbr");
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
        }
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


    public void setTemporalLayerCount( int temporalLayerCount ) {
        mTemporalLayerCount = temporalLayerCount;
    }
    public int getTemporalLayerCount() {
        return mTemporalLayerCount;
    }

    public void setEncoderRuntimeParameters(ArrayList<Object> params) {
        mRuntimeParams = params;
    }

    public void setDecoderRuntimeParameters(ArrayList<Object> params) {
        mDecoderRuntimeParams = params;
    }

    private void createBundles(String name, ArrayList<Object> datalist, HashMap<Integer, ArrayList<RuntimeParam>> runtimes) {
        HashMap<Integer, Bundle> map = new HashMap<>();
        for (Object obj: datalist) {
            RuntimeParam param = (RuntimeParam)obj;
            Bundle bundle = map.get(param.frame);
            if (bundle == null) {
                bundle = new Bundle();
                map.put(param.frame, bundle);
            }
            if (param.type.equals("int")) {
                bundle.putInt(param.name, Integer.parseInt(param.value.toString()));
            }


        }

        for (Integer key: map.keySet()) {
            Bundle bundle = map.get(key);
            ArrayList<RuntimeParam> list = runtimes.get(String.valueOf(key));
            if (list == null) {
                list = new ArrayList<RuntimeParam>();
                runtimes.put(key, list);
            }
            list.add(new RuntimeParam(name, key, "bundle", bundle));
        }
    }

    public HashMap<Integer, ArrayList<RuntimeParam>> getRuntimeParameters() {
        //Sort and return a HashMap
        HashMap<Integer, ArrayList<RuntimeParam>> map = new HashMap<>();
        if (mRuntimeParams == null) {
            Log.w(TAG, "Runtime parameters are null. No cli runtime parameters supported.");
            return map;
        }
        for (Object obj: mRuntimeParams) {
            if (obj instanceof RuntimeParam) {
                RuntimeParam param = (RuntimeParam)obj;
                Integer frame = Integer.valueOf(param.frame);
                ArrayList<RuntimeParam> list = map.get(frame);
                if (list == null) {
                    list = new ArrayList<RuntimeParam>();
                    map.put(frame, list);
                }
                if (param.value instanceof ArrayList) {
                    createBundles(param.name, (ArrayList<Object>)param.value, map);
                } else {
                    list.add(param);
                }
            } else {
                Log.d(TAG, "object is " + obj);
            }
        }

        return map;
    }

    public ArrayList<Object> getRuntimeParametersList() {
        return mRuntimeParams;
    }

    public ArrayList<Object> getDecoderRuntimeParametersList() {
        return mDecoderRuntimeParams;
    }

    public HashMap<Integer, ArrayList<RuntimeParam>> getDecoderRuntimeParameters() {
        //Sort and return a HashMap
        HashMap<Integer, ArrayList<RuntimeParam>> map = new HashMap<>();
        if (mDecoderRuntimeParams == null) {
            Log.w(TAG, "Runtime parameters are null. No cli decoder runtime parameters set.");
            return map;
        }
        for (Object obj: mDecoderRuntimeParams) {
            if (obj instanceof RuntimeParam) {
                RuntimeParam param = (RuntimeParam)obj;
                Integer frame = Integer.valueOf(param.frame);
                ArrayList<RuntimeParam> list = map.get(frame);
                if (list == null) {
                    list = new ArrayList<RuntimeParam>();
                    map.put(frame, list);
                }
                if (param.value instanceof ArrayList) {
                    createBundles(param.name, (ArrayList<Object>)param.value, map);
                } else {
                    list.add(param);
                }
            } else {
                Log.d(TAG, "object is " + obj);
            }
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

    public void setEncoderConfigure(ArrayList<ConfigureParam> param) {
        mEncoderConfigure = param;
    }

    public void addEncoderConfigureSetting(ConfigureParam param) {
        mEncoderConfigure.add(param);
    }

    public ArrayList<ConfigureParam> getEncoderConfigure() {
        return mEncoderConfigure;
    }

    public void setDecoderConfigure(ArrayList<ConfigureParam> param) {
        mDecoderConfigure = param;
    }

    public void addDecoderConfigureSetting(ConfigureParam param) {
        mDecoderConfigure.add(param);
    }

    public ArrayList<ConfigureParam> getDecoderConfigure() {
        Log.d(TAG, "getdecoderconfigure size: "+ mDecoderConfigure.size());
        return mDecoderConfigure;
    }


    public void setConcurrentCodings(int count) {
        this.mConcurrentCodings = count;
    }

    public int getConcurrentCodings() {
        return mConcurrentCodings;
    }

    public int getPursuit() {
        return mPursuit;
    }

    public void setPursuit(int pursuit) {
        this.mPursuit = pursuit;
    }

    public boolean isRealtime() {return mRealtime;
    }

    public void setRealtime(boolean realtime) {this.mRealtime = realtime;}

    public boolean noEncoding() {return mNoEncoding; }

    public void setNoEncoding(boolean noEncoding) {mNoEncoding = noEncoding;}

    public String getDecoder() {return mDecoder;}

    public void setDecoder(String decoder) {mDecoder = decoder;}

    public int getDurationSec() {return mDurationSec;}

    public void setDurationSec(int duration) {mDurationSec = duration;};

    public int getDurationFrames() {return mDurationFrames;}

    public void setDurationFrames(int frames) {mDurationFrames = frames;}

    public boolean doSurfaceEncoding() {return mUseSurfaceencoding;}

    public void setUseSurfaceEncoding(boolean surfaceencoding) {
        mUseSurfaceencoding = surfaceencoding;};
}


