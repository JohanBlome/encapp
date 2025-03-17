package com.facebook.encapp.utils;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import java.util.ArrayList;

public class PowerLoad {
    protected static final String TAG = "PowerLoad";
    int mFrequencyHz = 1;
    Boolean started = Boolean.FALSE;
    ArrayList<String> mEnergynWh = new ArrayList<>();
    ArrayList<String> mCurrentuA = new ArrayList<>();
    BatteryManager mBatteryManager;
    ArrayList<BatteryStatusListener> mStatusListeners = new ArrayList<>();
    static Context mContext;
    int mLowPowerThreshold = 20;
    int mHighPowerThreshold = 80;
    int mCurrentVoltagemV = -1;
    private boolean mLowPowerState = false;

    private static PowerLoad me = null;

    // Can be null...
    public static PowerLoad getPowerLoad() {
        if (me == null) {
            Log.w(TAG, "PowerLoad is requested before created");
        }
        return me;
    }

    public static PowerLoad getPowerLoad(Context context) {
        me = new PowerLoad(context);
        mContext = context;
        return me;
    }

    private PowerLoad(Context context) {
        mBatteryManager =
                (BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);
    }

    public void start() {
        synchronized (started) {
            Log.d(TAG, "Start power measurement");

            started = Boolean.TRUE;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (started) {
                        String tmp;
                        long sleeptime = (long) (1000.0 / mFrequencyHz);
                        synchronized (started) {
                            // Monitor power level
                            //int overheat = mBatteryManager.getIntProperty(BatteryManager.BATTERY_HEALTH_OVERHEAT);
                            //int overvoltage = mBatteryManager.getIntProperty(BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE);
                            int capacity = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

                            // Are we charging / charged?
                            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                            Intent batteryStatus = mContext.registerReceiver(null, ifilter);

                            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                    status == BatteryManager.BATTERY_STATUS_FULL;
                            mCurrentVoltagemV = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                            // How are we charging?
                            // TODO: I see some strange values.
                            //  Charging is claimed even though charging is not active in some cases...
                            int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                            boolean usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
                            boolean acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;
                            if (capacity <= mLowPowerThreshold && !mLowPowerState) {
                                mLowPowerState = true;
                                Log.d(TAG, "Power capacity is low: " + capacity + "%");
                                for (BatteryStatusListener listener: mStatusListeners) {
                                    if (isCharging) {
                                        Log.d(TAG, "Is charging but low battery. usb charge: "+ usbCharge + ", ac charge: " + acCharge);
                                        listener.lowCapacity();
                                    } else {
                                        Log.d(TAG, "Is not charging with low battery.");
                                        listener.shutdown();
                                    }
                                }
                            } else if (capacity >  mHighPowerThreshold && mLowPowerState) {
                                mLowPowerState = false;

                                for(BatteryStatusListener listener: mStatusListeners) {
                                    listener.recovered();
                                }
                            }


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

    private PowerSnapshot takeSnapshot() {
        int capuAh = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long powernAh =  mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);

        if (powernAh < 0 &&  mCurrentVoltagemV > 0) {
            powernAh = mCurrentVoltagemV * capuAh; //V * A
        } else {
            powernAh = -1;
        }
        PowerSnapshot snapshot = new PowerSnapshot(mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
              powernAh );
        return snapshot;
    }


    public void stop() {
        synchronized (started) {
            started = Boolean.FALSE;
        }
        for (String val: mEnergynWh) {
            Log.d(TAG, val);
        }
        for (String val: mCurrentuA) {
            Log.d(TAG, val);
        }
    }

    public PowerSnapshot getSnapshot(){
        return takeSnapshot();
    }

    public int getCurrentPowerPercentage() {
        return mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    public void addStatusListener(BatteryStatusListener listener) {
        mStatusListeners.add(listener);
    }

}
