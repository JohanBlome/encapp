package com.facebook.encapp.utils;

public class FrameInfo {
    long mPts;
    long mSize;
    long mProcessTime;
    long mStartTime;
    long mStopTime;
    boolean mIsIframe;
    int mFlags;

    public FrameInfo(long pts) {
        mPts = pts;
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
        mStartTime = System.nanoTime();
    }

    public void stop(){
        mStopTime = System.nanoTime();
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
