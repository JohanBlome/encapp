package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.facebook.encapp.utils.Assert;
import com.facebook.encapp.utils.ConfigureParam;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.RuntimeParam;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;

import androidx.annotation.NonNull;

public abstract class Encoder {


    protected static final String TAG = "encapp";
    protected static final long VIDEO_CODEC_WAIT_TIME_US = 1000000;

    protected int mFrameRate = 30;
    protected float mKeepInterval = 1.0f;
    protected MediaCodec mCodec;
    protected MediaMuxer mMuxer;

    protected int mSkipped = 0;
    protected int mFramesAdded = 0;
    protected int mRefFramesizeInBytes = (int) (1280 * 720 * 1.5);

    protected long mFrameTime = 0;
    protected boolean mWriteFile = true;
    protected Statistics mStats;
    protected String mFilename;
    protected boolean mDropNext;
    protected HashMap<Integer, ArrayList<RuntimeParam>> mRuntimeParams;
    protected HashMap<Integer, ArrayList<RuntimeParam>> mDecoderRuntimeParams;
    boolean VP8_IS_BROKEN = false; // On some older hw vp did not generate key frames by itself
    protected FileReader mYuvReader;
    protected int mVideoTrack = -1;
    int mPts = 132;
    long mLastTime = -1;
    boolean mRealtime = false;


    public abstract String encode(
            TestParams vc,
            boolean writeFile);

    protected void sleepUntilNextFrame() {
        long now = System.nanoTime();
        if (mLastTime == -1) {
            mLastTime = System.nanoTime();
        } else {
            long diff = (now - mLastTime) / 1000000;
            long sleepTime = (mFrameTime / 1000 - diff);
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        mLastTime = now;
    }


    /**
     * Generates the presentation time for frameIndex, in microseconds.
     */
    protected long computePresentationTime(int frameIndex) {
        return mPts + frameIndex * mFrameTime;
    }

    protected void calculateFrameTiming() {
        mFrameTime = 1000000L / mFrameRate;
    }


    protected MediaMuxer createMuxer(MediaCodec encoder, MediaFormat format, boolean useStatId) {
        if (!useStatId) {
            Log.d(TAG, "Bitrate mode: " + (format.containsKey(MediaFormat.KEY_BITRATE_MODE) ? format.getInteger(MediaFormat.KEY_BITRATE_MODE) : 0));
            mFilename = String.format(Locale.US, "/sdcard/%s_%dfps_%dx%d_%dbps_iint%d_m%d.mp4",
                    encoder.getCodecInfo().getName().toLowerCase(Locale.US),
                    (format.containsKey(MediaFormat.KEY_FRAME_RATE) ? format.getInteger(MediaFormat.KEY_FRAME_RATE) : 0),
                    (format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0),
                    (format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0),
                    (format.containsKey(MediaFormat.KEY_BIT_RATE) ? format.getInteger(MediaFormat.KEY_BIT_RATE) : 0),
                    (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL) ? format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL) : 0),
                    (format.containsKey(MediaFormat.KEY_BITRATE_MODE) ? format.getInteger(MediaFormat.KEY_BITRATE_MODE) : 0));
        } else {
            mFilename = mStats.getId() + ".mp4";
        }
        int type = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        if (encoder.getCodecInfo().getName().toLowerCase(Locale.US).contains("vp")) {
            if (!useStatId) {
                mFilename = String.format(Locale.US, "/sdcard/%s_%dfps_%dx%d_%dbps_iint%d_m%d.webm",
                        encoder.getCodecInfo().getName().toLowerCase(Locale.US),
                        (format.containsKey(MediaFormat.KEY_FRAME_RATE) ? format.getInteger(MediaFormat.KEY_FRAME_RATE) : 0),
                        (format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0),
                        (format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0),
                        (format.containsKey(MediaFormat.KEY_BIT_RATE) ? format.getInteger(MediaFormat.KEY_BIT_RATE) : 0),
                        (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL) ? format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL) : 0),
                        (format.containsKey(MediaFormat.KEY_BITRATE_MODE) ? format.getInteger(MediaFormat.KEY_BITRATE_MODE) : 0));
            } else {
                mFilename = mStats.getId() + ".webm";
            }
            type = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
        }
        try {
            Log.d(TAG, "Create mMuxer with type " + type + " and filename: " + "/sdcard/" + mFilename);
            mMuxer = new MediaMuxer("/sdcard/" + mFilename, type);
        } catch (IOException e) {
            Log.d(TAG, "FAILED Create mMuxer with type " + type + " and filename: " + mFilename);
            e.printStackTrace();
        }

        mStats.setEncodedfile(mFilename);
        return mMuxer;
    }

    public String getOutputFilename() {
        return mFilename;
    }

    @NonNull
    protected Vector<MediaCodecInfo> getMediaCodecInfos(MediaCodecInfo[] codecInfos, String id) {
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

    public Statistics getStatistics() {
        return mStats;
    }

    protected String getCodecName(TestParams vc) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        String id = vc.getCodecName();
        String codecName = "";
        Vector<MediaCodecInfo> matching = getMediaCodecInfos(codecInfos, id);

        if (matching.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("\nAmbigous codecs for " + id + "\n" + matching.size() + " codecs matching.\n");
            for (MediaCodecInfo info : matching) {
                sb.append(info.getName() + "\n");
            }
            Assert.assertTrue(sb.toString(), false);
        } else if (matching.size() == 0) {
            Assert.assertTrue("\nNo matching codecs to : " + id, false);
        } else {
            vc.setVideoEncoderIdentifier(matching.elementAt(0).getSupportedTypes()[0]);
            codecName = matching.elementAt(0).getName();
        }
        return codecName;
    }

    protected void setConfigureParams(TestParams vc, ArrayList<ConfigureParam> params, MediaFormat format) {
        for (ConfigureParam param : params) {
            if (param.value instanceof Integer) {
                format.setInteger(param.name, (Integer) param.value);
            } else if (param.value instanceof String) {
                Log.d(TAG, "Set  " + param.name + " to " + param.value);
                format.setString(param.name, (String) param.value);
            }
        }
    }

    protected void checkConfigureParams(TestParams vc, MediaFormat format) {
        ArrayList<ConfigureParam> params = vc.getEncoderConfigure();
        Log.d(TAG, "checkConfigureParams: " + params.toString() + ", l = " + params.size());
        for (ConfigureParam param : params) {
            try {
                if (param.value instanceof Integer) {
                    int value = format.getInteger(param.name);
                    Log.d(TAG, "MediaFormat: " + param.name + " - integer: " + value);
                } else if (param.value instanceof String) {
                    String value = format.getString(param.name);
                    Log.d(TAG, "MediaFormat: " + param.name + " - string: " + value);
                } else if (param.value instanceof Float) {
                    float value = format.getFloat(param.name);
                    Log.d(TAG, "MediaFormat: " + param.name + " - float: " + value);
                }
            } catch (Exception ex) {
                Log.e(TAG, param.name + ", Bad behaving Mediaformat query: " + ex.getMessage());
            }
        }
    }

    protected void checkConfig(MediaFormat format) {
        Log.d(TAG, "Check config: version = "+ Build.VERSION.SDK_INT );
        if ( Build.VERSION.SDK_INT >= 29) {
            Set<String> features = format.getFeatures();
            for (String feature: features) {
                Log.d(TAG, "MediaFormat: " + feature);
            }

            Set<String> keys = format.getKeys();
            for (String key: keys) {
                int type = format.getValueTypeForKey(key);
                switch (type) {
                    case MediaFormat.TYPE_BYTE_BUFFER:
                        Log.d(TAG, "MediaFormat: " + key + " - bytebuffer: " + format.getByteBuffer(key));
                        break;
                    case MediaFormat.TYPE_FLOAT:
                        Log.d(TAG, "MediaFormat: " + key + " - float: " + format.getFloat(key));
                        break;
                    case MediaFormat.TYPE_INTEGER:
                        Log.d(TAG, "MediaFormat: " + key + " - integer: " + format.getInteger(key));
                        break;
                    case MediaFormat.TYPE_LONG:
                        Log.d(TAG, "MediaFormat: " + key + " - long: " + format.getLong(key));
                        break;
                    case MediaFormat.TYPE_NULL:
                        Log.d(TAG, "MediaFormat: " + key + " - null");
                        break;
                    case MediaFormat.TYPE_STRING:
                        Log.d(TAG, "MediaFormat: " + key + " - string: "+ format.getString(key));
                        break;
                }

            }
        }
    }

    protected void setRuntimeParameters(int frameCount,  MediaCodec codec, HashMap<Integer, ArrayList<RuntimeParam>> runtimeParamList) {
        if (runtimeParamList != null && !runtimeParamList.isEmpty()) {
            Bundle params = new Bundle();
            ArrayList<RuntimeParam> runtimeParams = runtimeParamList.get(Integer.valueOf(frameCount));
            if (runtimeParams != null) {
                for (RuntimeParam param : runtimeParams) {
                    Log.d("ltr", "Runtime: "+param.name + "@"+frameCount+", vl = "+ param.value.toString());
                    if (param.value == null) {
                        params.putInt(param.name, frameCount);
                    } else if (param.type.equals("int")) {
                        if (param.name.equals("fps")) {
                            mKeepInterval = (float) mFrameRate / (float) Integer.parseInt((String)param.value);
                        } else {
                            String sval =  param.value.toString();
                            int val = 0;
                            if (sval.endsWith("k")) {
                                val = Integer.parseInt(sval.substring(0, sval.lastIndexOf('k'))) * 1000;
                            } else if (sval.endsWith("M")) {
                                val = Integer.parseInt(sval.substring(0, sval.lastIndexOf('M'))) * 1000000;
                            } else {
                                val = Integer.parseInt(sval);
                            }
                            Log.d("ltr","put val " + val);
                            params.putInt(param.name, val);
                        }
                    } else if (param.type.equals("bundle")) {
                        params.putBundle(param.name, (Bundle)param.value);
                    } else {
                        Log.d(TAG, "Unknown type: " + param.type);
                    }
                }
                codec.setParameters(params);
            }
        }
    }

    boolean doneReading(TestParams vc, int loop, double time, int frame) {
        boolean done = false;
        if (vc.getDurationFrames() > 0) {
            if ( frame >= vc.getDurationFrames() ) {
                done = true;
            }
        } else if (vc.getDurationSec() > 0) {
            if ( time >= vc.getDurationSec() ) {
                done = true;
            }
        } else if (loop > vc.getLoopCount() ) {
            if (vc.getLoopCount() <= 1) {
                done = true;
            }
        }

        return done;
    }
}
