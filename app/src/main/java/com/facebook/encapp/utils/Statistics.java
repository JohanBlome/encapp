package com.facebook.encapp.utils;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Statistics {
    private String mId;
    private String mDesc;
    private String mEncodedfile = "";
    private String mCodec;
    private long mStartTime;
    private long mStopTime;
    private HashMap<Long,FrameInfo> mFrames;
    VideoConstraints mVc;
    Date mStartDate;

    public Statistics(String desc, VideoConstraints vc) {
        mDesc = desc;
        mFrames = new HashMap<>();
        mVc = vc;
        mStartDate = new Date();
        mId = "encapp_" + UUID.randomUUID().toString();
    }

    public String getId(){
        return mId;
    }

    public String toString() {
        ArrayList<FrameInfo> allFrames = new ArrayList<>(mFrames.values());
        Comparator<FrameInfo> compareByPts = (FrameInfo o1, FrameInfo o2) -> new Long(o1.getPts()).compareTo( new Long(o2.getPts() ));
        Collections.sort(allFrames, compareByPts);

        StringBuffer buffer = new StringBuffer();
        int counter = 0;
        for (FrameInfo info: allFrames) {
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

    public void start(){
        mStartTime = System.nanoTime();
    }

    public void stop(){
        mStopTime = System.nanoTime();
    }

    public void startFrame(long pts) {
        FrameInfo frame = new FrameInfo(pts);
        frame.start();
        mFrames.put(new Long(pts), frame);
    }

    public void stopFrame(long pts, long size, boolean isIFrame) {
        FrameInfo frame = mFrames.get(new Long(pts));
        if (frame != null) {
            frame.stop();
            frame.setSize(size);
            frame.isIFrame(isIFrame);
        }
    }

    public long getProcessingTime() {
        return mStopTime - mStartTime;
    }

    public int getFrameCount() {
        return mFrames.size();
    }

    public void setCodec(String codec) {
        mCodec = codec;
    }

    public int getAverageBitrate() {
        ArrayList<FrameInfo> allFrames = new ArrayList<>(mFrames.values());
        Comparator<FrameInfo> compareByPts = (FrameInfo o1, FrameInfo o2) -> new Long(o1.getPts()).compareTo( new Long(o2.getPts() ));
        Collections.sort(allFrames, compareByPts);
        int framecount = allFrames.size();
        if (framecount > 0) {
            long startPts = allFrames.get(0).mPts;
            //We just ignore the last frame, for the average does not mean much.
            long lastTime = allFrames.get(allFrames.size() - 1).mPts;
            double totalTime =  ((double)(lastTime - startPts)) / 1000000.0;
            long totalSize = 0;
            for (FrameInfo info: allFrames) {
                totalSize += info.getSize();
            }
            totalSize -= allFrames.get(framecount - 1).mSize;
            return (int)(Math.round(8 * totalSize/(totalTime))); // bytes/Secs -> bit/sec
        } else {
            return 0;
        }
    }

    public void  setEncodedfile(String filename) {
        mEncodedfile = filename;
    }

    public void writeJSON(Writer writer) throws IOException {
        try {
            JSONObject json = new JSONObject();

            json.put("id", mId);
            json.put("description", mDesc);
            json.put("date", mStartDate.toString());
            json.put("proctime", getProcessingTime());
            json.put("framecount", getFrameCount());
            json.put("encodedfile", mEncodedfile);

            JSONObject settings = new JSONObject();
            settings.put("codec", mCodec);
            settings.put("gop", mVc.getKeyframeRate());
            settings.put("fps", mVc.getFPS());
            settings.put("bitrate", mVc.getBitRate());
            settings.put("meanbitrate", getAverageBitrate());
            Size s = mVc.getVideoSize();
            settings.put("width", s.getWidth());
            settings.put("height", s.getHeight());
            settings.put("encmode",mVc.bitrateModeName());
            settings.put("keyrate",mVc.getKeyframeRate());
            settings.put("iframepreset",mVc.getIframeSizePreset());
            settings.put("colorformat",mVc.getColorFormat());
            settings.put("colorrange",mVc.getColorRange());
            settings.put("colorstandard",mVc.getColorStandard());
            settings.put("colortransfer",mVc.getColorTransfer());
            settings.put("ltrcount",mVc.getLTRCount());

            json.put("settings", settings);

            ArrayList<FrameInfo> allFrames = new ArrayList<>(mFrames.values());
            Comparator<FrameInfo> compareByPts = (FrameInfo o1, FrameInfo o2) -> new Long(o1.getPts()).compareTo( new Long(o2.getPts() ));
            Collections.sort(allFrames, compareByPts);
            int counter = 0;
            JSONArray jsonArray = new JSONArray();

            JSONObject obj = null;
            for (FrameInfo info: allFrames) {
                obj = new JSONObject();

                obj.put("frame", (int)(counter++));
                obj.put("iframe", (info.isIFrame())? 1: 0);
                obj.put("size", info.getSize());
                obj.put("pts", info.getPts());
                obj.put("proctime", (int)(info.getProcessingTime()));
                jsonArray.put(obj);
            }
            json.put("frames", jsonArray);

            writer.write(json.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
