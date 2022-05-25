package com.facebook.encapp.utils;

import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import com.facebook.encapp.proto.Configure;
import com.facebook.encapp.proto.Test;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

public class Statistics {
    final static String TAG = "statistics";
    public static String NA = "na";
    private final String mId;
    private final String mDesc;
    private final HashMap<Long, FrameInfo> mEncodingFrames;
    private final HashMap<Long, FrameInfo> mDecodingFrames;
    int mEncodingProcessingFrames = 0;
    Test mTest;
    Date mStartDate;
    SystemLoad mLoad = new SystemLoad();
    private String mEncodedfile = "";
    private String mCodec;
    private long mStartTime = -1;
    private long mStopTime = -1;
    private MediaFormat mEncoderConfigFormat;
    private MediaFormat mEncoderMediaFormat;
    private MediaFormat mDecoderMediaFormat;
    private String mDecoderName = "";
    private String mAppVersion = "";

    public Statistics(String desc, Test test) {
        mDesc = desc;
        mEncodingFrames = new HashMap<>(20);
        mDecodingFrames = new HashMap<>(20);
        mTest = test;
        mStartDate = new Date();
        mId = "encapp_" + UUID.randomUUID().toString();

    }

    public void setAppVersion(String mAppVersion) {
        this.mAppVersion = mAppVersion;
    }

    public String getId() {
        return mId;
    }

    public String toString() {
        ArrayList<FrameInfo> allEncodingFrames = new ArrayList<>(mEncodingFrames.values());
        Comparator<FrameInfo> compareByPts = (FrameInfo o1, FrameInfo o2) -> Long.valueOf(o1.getPts()).compareTo(Long.valueOf(o2.getPts()));
        Collections.sort(allEncodingFrames, compareByPts);

        StringBuffer buffer = new StringBuffer();
        int counter = 0;
        for (FrameInfo info : allEncodingFrames) {
            buffer.append(mId + ", " +
                    counter + ", " +
                    info.isIFrame() + ", " +
                    info.getSize() + ", " +
                    info.getPts() + ", " +
                    info.getProcessingTime() + "\n");
            counter++;
        }

        return buffer.toString();
    }

    public void start() {
        mStartTime = System.nanoTime();
        mLoad.start();
    }

    public void stop() {
        mStopTime = System.nanoTime();
        mLoad.stop();
    }

    public void startEncodingFrame(long pts, int originalFrame) {
        FrameInfo frame = new FrameInfo(pts, originalFrame);
        frame.start();
        mEncodingFrames.put(Long.valueOf(pts), frame);
        mEncodingProcessingFrames += 1;
    }

    public void stopEncodingFrame(long pts, long size, boolean isIFrame) {
        FrameInfo frame = mEncodingFrames.get(Long.valueOf(pts));
        if (frame != null) {
            frame.stop();
            frame.setSize(size);
            frame.isIFrame(isIFrame);
            mEncodingFrames.put(frame.getPts(), frame);
        } else {
            Log.e(TAG, "No matching pts! Error in time handling. Pts = " + pts);
        }
        mEncodingProcessingFrames -= 1;
    }

    public void startDecodingFrame(long pts, long size, int flags) {
        FrameInfo frame = new FrameInfo(pts);
        frame.setSize(size);
        frame.setFlags(flags);
        frame.start();
        mDecodingFrames.put(Long.valueOf(pts), frame);
    }

    public void stopDecodingFrame(long pts) {
        FrameInfo frame = mDecodingFrames.get(Long.valueOf(pts));
        if (frame != null) {
            frame.stop();
        }
    }

    public long getProcessingTime() {
        return mStopTime - mStartTime;
    }

    public int getEncodedFrameCount() {
        return mEncodingFrames.size();
    }

    public void setCodec(String codec) {
        mCodec = codec;
    }

    public int getAverageBitrate() {
        ArrayList<FrameInfo> allFrames = new ArrayList<>(mEncodingFrames.values());
        Comparator<FrameInfo> compareByPts = (FrameInfo o1, FrameInfo o2) -> Long.valueOf(o1.getPts()).compareTo(Long.valueOf(o2.getPts()));
        Collections.sort(allFrames, compareByPts);
        int framecount = allFrames.size();
        if (framecount > 0) {
            long startPts = allFrames.get(0).mPts;
            //We just ignore the last frame, for the average does not mean much.
            long lastTime = allFrames.get(allFrames.size() - 1).mPts;
            double totalTime = ((double) (lastTime - startPts)) / 1000000.0;
            long totalSize = 0;
            for (FrameInfo info : allFrames) {
                totalSize += info.getSize();
            }
            totalSize -= allFrames.get(framecount - 1).mSize;
            return (int) (Math.round(8 * totalSize / (totalTime))); // bytes/Secs -> bit/sec
        } else {
            return 0;
        }
    }

    public void setEncodedfile(String filename) {
        mEncodedfile = filename;
    }

    public void setEncoderMediaFormat(MediaFormat format) {
        mEncoderMediaFormat = format;
    }

    public void setEncoderConfigMediaFormat(MediaFormat format) {
        mEncoderConfigFormat = format;
    }

    public void setDecoderMediaFormat(MediaFormat format) {
        mDecoderMediaFormat = format;
    }

    public void setDecoderName(String decoderName) {
        mDecoderName = decoderName;
    }


    private JSONObject getSettingsFromMediaFormat(MediaFormat format) {
        JSONObject mediaformat = new JSONObject();
        if (format == null) {
            return mediaformat;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            Set<String> features = format.getFeatures();
            for (String feature : features) {
                Log.d(TAG, "MediaFormat: " + feature);
            }

            Set<String> keys = format.getKeys();


            for (String key : keys) {
                int type = format.getValueTypeForKey(key);
                try {
                    switch (type) {
                        case MediaFormat.TYPE_BYTE_BUFFER:
                            mediaformat.put(key, "bytebuffer");
                            break;
                        case MediaFormat.TYPE_FLOAT:
                            mediaformat.put(key, format.getFloat(key));
                            break;
                        case MediaFormat.TYPE_INTEGER:
                            mediaformat.put(key, format.getInteger(key));
                            break;
                        case MediaFormat.TYPE_LONG:
                            mediaformat.put(key, format.getLong(key));
                            break;
                        case MediaFormat.TYPE_NULL:
                            mediaformat.put(key, "");
                            break;
                        case MediaFormat.TYPE_STRING:
                            mediaformat.put(key, format.getString(key));
                            break;
                    }
                } catch (JSONException jex) {
                    Log.d(TAG, key + ", Failed to parse MediaFormat: " + jex.getMessage());
                }
            }
        } else {
            // TODO: Go through the settings
            try {
                mediaformat.put(MediaFormat.KEY_FRAME_RATE, getVal(format, MediaFormat.KEY_FRAME_RATE, "unknown"));
                mediaformat.put(MediaFormat.KEY_BITRATE_MODE, getVal(format, MediaFormat.KEY_BITRATE_MODE, "unknown"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return mediaformat;
    }

    private String getVal(MediaFormat format, String key, String val) {
        if (format == null) {
            return val;
        }
        if (format.containsKey(key)) {
            return format.getString(key);
        }
        return val;
    }


    private JSONObject getConfigSettings(Configure config) throws JSONException {
        JSONObject settings = new JSONObject();
        settings.put("codec", mCodec);
        if (config.hasIFrameInterval()) {
            settings.put("gop", config.getIFrameInterval());
        }
        settings.put("fps", config.getFramerate());
        settings.put("bitrate", config.getBitrate());
        settings.put("meanbitrate", getAverageBitrate());
        if (config.hasResolution()) {
            Size s = Size.parseSize(mTest.getConfigure().getResolution());
            settings.put("width", s.getWidth());
            settings.put("height", s.getHeight());
        }
        if (mTest.getConfigure().hasBitrateMode()) {
            settings.put("encmode", mTest.getConfigure().getBitrateMode());
        }
        if (config.hasColorRange())
            settings.put(MediaFormat.KEY_COLOR_RANGE, config.getColorRange());
        if (config.hasColorStandard())
            settings.put(MediaFormat.KEY_COLOR_STANDARD, config.getColorStandard());
        if (config.hasColorTransfer())
            settings.put(MediaFormat.KEY_COLOR_TRANSFER, config.getColorTransfer());
        //TODO: more settings

        return settings;
    }


    public void writeJSON(Writer writer) throws IOException {
        Log.d(TAG, "Write stats for " + mId);
        try {
            JSONObject json = new JSONObject();

            json.put("id", mId);
            json.put("description", mDesc);
            json.put("test", mTest.getCommon().getDescription());
            json.put("testdefinition", mTest.toString());
            json.put("date", mStartDate.toString());
            Log.d(TAG, "log app version: " + mAppVersion);
            json.put("encapp_version", mAppVersion);
            json.put("proctime", getProcessingTime());
            json.put("framecount", getEncodedFrameCount());
            json.put("encodedfile", mEncodedfile);
            String[] tmp = mTest.getInput().getFilepath().split("/");
            json.put("sourcefile", tmp[tmp.length - 1]);

            json.put("settings", getConfigSettings(mTest.getConfigure()));
            json.put("encoder_media_format", getSettingsFromMediaFormat(mEncoderMediaFormat));
            if (mDecodingFrames.size() > 0) {
                json.put("decoder", mDecoderName);
                json.put("decoder_media_format", getSettingsFromMediaFormat(mDecoderMediaFormat));
            }

            ArrayList<FrameInfo> allFrames = new ArrayList<>(mEncodingFrames.values());
            Comparator<FrameInfo> compareByPts = (FrameInfo o1, FrameInfo o2) -> Long.valueOf(o1.getPts()).compareTo(Long.valueOf(o2.getPts()));
            Collections.sort(allFrames, compareByPts);
            int counter = 0;
            JSONArray jsonArray = new JSONArray();

            JSONObject obj = null;
            for (FrameInfo info : allFrames) {
                obj = new JSONObject();

                obj.put("frame", counter++);
                obj.put("iframe", (info.isIFrame()) ? 1 : 0);
                obj.put("size", info.getSize());
                obj.put("pts", info.getPts());
                if (info.getStopTime() == 0) {
                    Log.w(TAG, "Frame did not finish");
                    obj.put("proctime", 0);
                } else {
                    obj.put("proctime", info.getProcessingTime());
                }
                obj.put("starttime", info.getStartTime());
                obj.put("stoptime", info.getStopTime());
                jsonArray.put(obj);
            }
            json.put("frames", jsonArray);

            if (mDecodingFrames.size() > 0) {

                allFrames = new ArrayList<>(mDecodingFrames.values());
                Collections.sort(allFrames, compareByPts);
                counter = 0;
                jsonArray = new JSONArray();

                obj = null;
                for (FrameInfo info : allFrames) {
                    long proc_time = info.getProcessingTime();
                    if (proc_time > 0) {
                        obj = new JSONObject();

                        obj.put("frame", counter++);
                        obj.put("flags", info.getFlags());
                        obj.put("size", info.getSize());
                        obj.put("pts", info.getPts());
                        obj.put("proctime", info.getProcessingTime());
                        obj.put("starttime", info.getStartTime());
                        obj.put("stoptime", info.getStopTime());
                        jsonArray.put(obj);
                    }
                }
                json.put("decoded_frames", jsonArray);


            }

            //GPU info
            JSONObject gpuData = new JSONObject();
            HashMap<String, String> gpuInfo = mLoad.getGPUInfo();

            for (String key : gpuInfo.keySet()) {
                gpuData.put(key, gpuInfo.get(key));
            }

            counter = 0;
            jsonArray = new JSONArray();

            int[] gpuload = mLoad.getGPULoadPercentagePerTimeUnit();
            float timer = (float) (1.0 / mLoad.getSampleFrequency());
            obj = null;
            for (int load : gpuload) {
                obj = new JSONObject();
                int msec = Math.round(counter * timer * 1000);
                obj.put("time_sec", msec / 1000.0);
                obj.put("load_percentage", load);
                counter++;
                jsonArray.put(obj);
            }
            gpuData.put("gpu_load_percentage", jsonArray);
            obj = null;
            jsonArray = new JSONArray();
            counter = 0;
            for (String clock : mLoad.getGPUClockFreqPerTimeUnit()) {
                obj = new JSONObject();

                int msec = Math.round(counter * timer * 1000);
                obj.put("time_sec", msec / 1000.0);
                obj.put("clock_MHz", clock);
                counter++;
                jsonArray.put(obj);
            }
            gpuData.put("gpu_clock_freq", jsonArray);
            json.put("gpu_data", gpuData);

            writer.write(json.toString(2));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

}
