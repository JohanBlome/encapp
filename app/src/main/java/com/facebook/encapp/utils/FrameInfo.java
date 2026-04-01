package com.facebook.encapp.utils;
import android.os.Build;
import android.os.Trace;
import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.facebook.encapp.MainActivity;

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
    int mBatteryVoltage = -1;
    int mAverageCurrent = -1;
    int mBatteryCapacity = -1;
    int mBatteryChargeCounter = -1;
    int mBatteryCurrentNow = -1;
    long mBatteryEnergyCounter = -1;
    int mOutputOrder = -1;
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

    public void setOutputOrder(int order) { mOutputOrder = order; }
    public int getOutputOrder() { return mOutputOrder; }

    public Dictionary getInfo() {
        return mInfo;
    }
    public void addInfo(Dictionary<String, Object> info) {
        mInfo = info;
    }

    public void setAverageCurrent() {
        Context ctx = MainActivity.getAppContext();
        if (ctx == null) { mAverageCurrent = -1; return; }

        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        mAverageCurrent = (bm != null)
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                : -1;
    }

    public void setBatteryVoltage() {
        Context ctx = MainActivity.getAppContext();
        if (ctx == null) { mBatteryVoltage = -1; return; }

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent status = ctx.registerReceiver(null, filter);
        mBatteryVoltage = (status != null)
                ? status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                : -1;
    }

    public void setBatteryCapacity() {
        Context ctx = MainActivity.getAppContext();
        if (ctx == null) { mBatteryCapacity = -1; return; }

        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        mBatteryCapacity = (bm != null)
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                : -1;
    }

    public void setBatteryChargeCounter() {
        Context ctx = MainActivity.getAppContext();
        if (ctx == null) { mBatteryChargeCounter = -1; return; }

        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        mBatteryChargeCounter = (bm != null)
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                : -1;
    }

    public void setBatteryCurrentNow() {
        Context ctx = MainActivity.getAppContext();
        if (ctx == null) { mBatteryCurrentNow = -1; return; }

        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        mBatteryCurrentNow = (bm != null)
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                : -1;
    }

    public void setBatteryEnergyCounter() {
        Context ctx = MainActivity.getAppContext();
        if (ctx == null) { mBatteryEnergyCounter = -1; return; }

        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        mBatteryEnergyCounter = (bm != null)
                ? bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                : -1;
    }

    public int getBatteryVoltage() { return mBatteryVoltage; }
    public int getAverageCurrent() { return mAverageCurrent; }
    public int getBatteryCapacity() { return mBatteryCapacity; }
    public int getBatteryChargeCounter() { return mBatteryChargeCounter; }
    public int getBatteryCurrentNow() { return mBatteryCurrentNow; }
    public long getBatteryEnergyCounter() { return mBatteryEnergyCounter; }

}
