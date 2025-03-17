package com.facebook.encapp.utils;

public class PowerSnapshot {
    int mCurrentuA;
    long mCapacitynWh;
    long mTimestampUs;

    public PowerSnapshot(int currentuA, long capacitynWh) {
        mTimestampUs = ClockTimes.currentTimeUs();
        mCapacitynWh = capacitynWh;
        mCurrentuA = currentuA;
    }

    public long getTimestampUs() {
        return mTimestampUs;
    }

    public int getCurrentuA() {
        return mCurrentuA;
    }

    public long getCapacitynWh() {
        return mCapacitynWh;
    }
}
