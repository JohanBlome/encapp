package com.facebook.encapp.utils;
import android.os.SystemClock;
public class FrameInfo {
    long mPts;
    long mSize;
    long mProcessTime;
    long mStartTime;
    long mStopTime;
    boolean mIsIframe;
    int mFlags;
    int mOriginalFrame;

    public FrameInfo(long pts) {
        mPts = pts;
        mOriginalFrame = -1; // When this does not make sense
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
        mStartTime = SystemClock.elapsedRealtimeNanos();
    }

    public void stop(){
        mStopTime = SystemClock.elapsedRealtimeNanos();
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


}
