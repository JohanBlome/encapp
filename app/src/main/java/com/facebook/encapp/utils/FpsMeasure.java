package com.facebook.encapp.utils;


import android.util.Log;

import java.util.Vector;

public class FpsMeasure extends Thread {
    private final String TAG = "encapp.fps";
    double mFps = 0;
    long[] mLatestPts;
    boolean mStable = false;
    float mTargetFps = 0;
    int mPtsIndex = 0;
    int STABLE_PERIOD_LIMIT = 4; // When we have four periods of stable fps
    long SLEEP_PERIOD_MS = 500; //every half second
    float STABLE_LIMIT = 2f; // +/-  fps
    String mId = "";
    boolean continuous = true;
    int mHistoryLength = 30;
    Vector<Float> mHistory = new Vector<>(mHistoryLength);
    float mCurrentHistorySum = 0;
    Object mLock = new Object();

    public FpsMeasure(float targetFps, String id) {
        // Target fps is used to set measurement length
        mTargetFps = targetFps;
        mId = id;
    }

    @Override
    public void run() {
        // The frame rate is calculated with a one period window
        mLatestPts = new long[(int) (mTargetFps + 1)];
        mHistoryLength = mLatestPts.length;
        double lastFps = 0;
        int stableCount = 0;
        while (true) {
            synchronized (mLock) {
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            int index = mPtsIndex;
            // points at the oldest value
            long s1 = mLatestPts[(index + 1) % mLatestPts.length];
            // current value
            long s2 = mLatestPts[index];
            if (s1 == 0) {
                continue;
            }
            double diff = (double) (s2 - s1) / 1000000000.0; // to sec

            mFps = mTargetFps / diff;
            if (mHistory.size() == mHistoryLength) {
                float old = mHistory.remove(0);
                mCurrentHistorySum -= old;
            }
            mHistory.add(new Float(mFps));
            mCurrentHistorySum += mFps;
            double fpsDiff = mFps - lastFps;
            if (Math.abs(fpsDiff) < STABLE_LIMIT && (mHistory.size() == mHistoryLength)) {
                stableCount++;
            } else {
                stableCount = 0;
            }

            lastFps = mFps;
            if (stableCount > STABLE_PERIOD_LIMIT && !mStable) {
                if ((int) mFps == 0) {
                    Log.w(TAG, "Too low framerate!");
                }
                mStable = true;
                if (!continuous)
                    return;
            }
        }
    }

    public void addPtsUsec(long ptsU) {
        addPtsNsec(ptsU * 1000);
    }

    public void addPtsNsec(long ptsN) {
        synchronized (mLock) {
            mPtsIndex = (mPtsIndex + 1) % mLatestPts.length;
            mLatestPts[mPtsIndex] = ptsN;
            mLock.notifyAll();
        }
    }

    public boolean isStable() {
        return mStable;
    }

    public double getFps() {
        return mFps;
    }

    public float getAverageFps() {
        return mCurrentHistorySum / mHistory.size();
    }
}
