package com.facebook.encapp.utils;

public class ClockTimes {
    public static long currentTimeNs() {
        return System.nanoTime();
    }

    public static long currentTimeUs() {
        return System.nanoTime()/1000;
    }

    public static long currentTimeMs() {
        return System.nanoTime()/1000000;
    }

}
