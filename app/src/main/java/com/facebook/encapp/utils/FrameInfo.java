package com.facebook.encapp.utils;
import android.os.Build;
import android.os.Trace;
import android.util.Log;

import java.util.Dictionary;

public class FrameInfo {
    private static String TAG = "FrameInfo";
    long mPts;
    long mDts;
    long mSize;
    long mStartTime;
    long mStopTime;
    boolean mIsIframe;
    int mFlags;
    int mOriginalFrame;
    int mUUID = -1;
    static Integer mIdCounter = 0;
    Dictionary<String, Object> mInfo;

    public FrameInfo(long pts) {
        mPts = pts;
        mOriginalFrame = -1; // When this does not make sense
        synchronized (mIdCounter) {
            mUUID = mIdCounter++;
        }
    }

    public FrameInfo(long pts, int originalFrame) {
        mPts = pts;
        mOriginalFrame = originalFrame;
    }

    public void setSize(long size) {
        mSize = size;
    }

    public long getSize() {
        return mSize;
    }

    public long getPts(){
        return mPts;
    }

    public int getOriginalFrame() {return mOriginalFrame;}

    public void isIFrame(boolean isIFrame) {
        mIsIframe = isIFrame;
    }

    public boolean isIFrame() {
        return mIsIframe;
    }

    public int getFlags() {return mFlags;}

    public void setFlags(int flags) {
        mFlags = flags;
    }
    public void start(){
        mStartTime = ClockTimes.currentTimeNs();
        // Trace disabled for performance - adds ~1-2ms overhead per frame
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        //     Trace.beginAsyncSection("Process frame", mUUID);
        // }
    }

    public void stop(){
        // Trace disabled for performance - adds ~1-2ms overhead per frame
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        //     Trace.endAsyncSection("Process frame", mUUID);
        // }
        mStopTime = ClockTimes.currentTimeNs();
        if (mStopTime < mStartTime) {
            mStopTime = -1;
            mStartTime = 0;
        }
    }

    public long getProcessingTime() {
        return mStopTime - mStartTime;
    }

    public long getStartTime() { return mStartTime;}
    public long getStopTime() { return mStopTime;}

    public Dictionary getInfo() {
        return mInfo;
    }
    public void addInfo(Dictionary<String, Object> info) {
        mInfo = info;
    }

}
