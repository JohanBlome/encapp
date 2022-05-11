package com.facebook.encapp.utils;


import android.util.Log;

public class FpsMeasure extends Thread{
    private String TAG = "encapp.fps";
    double mFps = 0;
    long mLatestPts[];
    boolean mStable = false;
    float mTargetFps = 0;
    int mPtsIndex = 0;
    int STABLE_PERIOD_LIMIT = 4; // When we have four periods of stable fps
    long SLEEP_PERIOD_MS = 500; //every half second
    float STABLE_LIMIT = 2f; // +/-  fps
    
    public FpsMeasure(float targetFps) {
        // Target fps is used to set measurement length
        mTargetFps = targetFps;
    }
    
    @Override
    public void run() {
        mLatestPts = new long[(int)(mTargetFps + 0.5)];

        double lastFps = 0;
        int stableCount = 0;
        while(true) {
            try {
                Thread.sleep(SLEEP_PERIOD_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }


            int index = mPtsIndex;
            // points at the oldest value
            long s1 = mLatestPts[(index + 1)%mLatestPts.length]; 
            // current value
            long s2 =  mLatestPts[index];
            double diff = (double)(s2 - s1)/1000000.0;

            mFps = mTargetFps/diff;
            double fpsDiff = mFps - lastFps;
            if (Math.abs(fpsDiff) < STABLE_LIMIT) {
                stableCount++;
            } else {
                stableCount = 0;
            }

            Log.d(TAG, "Fps: " + (int)(100 * mFps)/100.0 + ", stableCount " + stableCount);
            lastFps = mFps;
            if (stableCount > STABLE_PERIOD_LIMIT) {
                if ((int)mFps == 0) {
                    Log.w(TAG, "Too low framerate!");
                }
                mStable = true;
                return;
            }
        }
    }

    public void addPtsUsec(long ptsU) {
        addPtsNsec(ptsU / 1000);
    }

    public void addPtsNsec(long ptsU) {;
        mPtsIndex = (mPtsIndex + 1) % mLatestPts.length;
        mLatestPts[mPtsIndex] = ptsU / 1000;
    }
    
    public boolean isStable() {
        return mStable;
    }

}
