package com.facebook.encapp.utils;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import android.util.JsonWriter;
import android.util.Size;

public class Statistics {
    private String mId;
    private long mStartTime;
    private long mStopTime;
    private HashMap<Long,FrameInfo> mFrames;
    VideoConstraints mVc;
    Date mStartDate;

    public Statistics(String id, VideoConstraints vc) {
        mId = id;
        mFrames = new HashMap<>();
        mVc = vc;
        mStartDate = new Date();
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

    public void writeJSON(Writer writer) throws IOException {
        JsonWriter json = new JsonWriter(writer);
        json.setIndent("  ");
        json.beginObject();
        json.name("id");
        json.value(mId);
        json.name("date");
        json.value(mStartDate.toString());
        json.name("proctime");
        json.value(getProcessingTime());
        json.name("framecount");
        json.value(getFrameCount());

        json.name("settings");
        json.beginObject();
        json.name("codec");
        json.value(mVc.getVideoEncoderIdentifier());
        json.name("gop");
        json.value(mVc.getKeyframeRate());
        json.name("fps");
        json.value(mVc.getFPS());

        Size s = mVc.getVideoSize();
        json.name("width");
        json.value(s.getWidth());
        json.name("height");
        json.value(s.getHeight());
        json.endObject();

        json.name("frames");
        json.beginObject();

        ArrayList<FrameInfo> allFrames = new ArrayList<>(mFrames.values());
        Comparator<FrameInfo> compareByPts = (FrameInfo o1, FrameInfo o2) -> new Long(o1.getPts()).compareTo( new Long(o2.getPts() ));
        Collections.sort(allFrames, compareByPts);
        int counter = 0;
        for (FrameInfo info: allFrames) {
            json.name("frame");
            json.beginObject();
            json.name("id");
            json.value(counter++);
            json.name("iframe");
            json.value(info.isIFrame());
            json.name("size");
            json.value(info.getSize());
            json.name("pts");
            json.value(info.getPts());
            json.name("proctime");
            json.value((int)(info.getProcessingTime()));
            json.endObject();
        }
        json.endObject();
        json.endObject();
    }
}
