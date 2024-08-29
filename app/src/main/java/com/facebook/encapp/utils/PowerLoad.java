package com.facebook.encapp.utils;

import android.content.Context;
import android.os.BatteryManager;
import android.util.Log;

import java.util.ArrayList;

public class PowerLoad {
    protected static final String TAG = "PowerLoad";
    int mFrequencyHz = 1;
    Boolean started = Boolean.FALSE;
    ArrayList<String> mCapacity = new ArrayList<>();
    ArrayList<String> mCurrentPower = new ArrayList<>();
    Context mContext;
    public PowerLoad(Context context) {
        mContext = context;
    }

    public void start() {
        synchronized (started) {
            Log.d(TAG, "Start power measurement");

            BatteryManager mBatteryManager =
                    (BatteryManager)mContext.getSystemService(Context.BATTERY_SERVICE);

            started = Boolean.TRUE;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (started ) {
                        String tmp;
                        long sleeptime = (long) (1000.0 / mFrequencyHz);
                        synchronized (started) {
                            int power = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                            int capacity = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

                            mCapacity.add(String.valueOf(capacity));
                            mCurrentPower.add(String.valueOf(power));

                        }

                        try {
                            Thread.sleep(sleeptime);
                        } catch (Exception ex) {
                            Log.e(TAG, "Sleep failed (" + sleeptime + " ms): " + ex.getMessage());
                        }
                    }
                }
            }, "power_load_thread");
            t.start();
        }
    }

    public void stop() {
        synchronized (started) {
            started = Boolean.FALSE;
        }
        Log.d(TAG, "Capacity");
        for (String val: mCapacity) {
            Log.d(TAG, val);
        }
        Log.d(TAG, "Current power");
        for (String val: mCurrentPower) {
            Log.d(TAG, val);
        }
    }
}
