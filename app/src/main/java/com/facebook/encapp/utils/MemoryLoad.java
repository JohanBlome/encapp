package com.facebook.encapp.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.util.Log;

import java.util.ArrayList;
import java.util.Map;

public class MemoryLoad {
    protected static final String TAG = "MemoryLoad";
    int mFrequencyHz = 1;
    Boolean started = Boolean.FALSE;
    ArrayList<String> mUsedMemory = new ArrayList<>();
    Context mContext;
    public MemoryLoad(Context context) {
        mContext = context;
    }

    public void start() {
        synchronized (started) {

            ActivityManager am = (ActivityManager)mContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();

            am.getMemoryInfo(memInfo);
            Log.d(TAG, "avail: " + memInfo.availMem);
            Log.d(TAG, "low: " + memInfo.lowMemory);
            Log.d(TAG, "thrs: " + memInfo.threshold);
            Log.d(TAG, "total: " + memInfo.totalMem);

            int pid = android.os.Process.myPid();
            started = Boolean.TRUE;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (started ) {
                        String tmp;
                        long sleeptime = (long) (1000.0 / mFrequencyHz);
                        synchronized (started) {
                            Runtime info = Runtime.getRuntime();
                            long freeSize = info.freeMemory();
                            long totalSize = info.totalMemory();
                            long usedSize = totalSize - freeSize;
                            long nativeHeapAlloc = Debug.getNativeHeapAllocatedSize();
                            long nativeHeapaFree = Debug.getNativeHeapFreeSize();
                            long nativeHeapSize = Debug.getNativeHeapSize();
                            long pssCount = Debug.getPss();
                            //Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(new int[]{pid});
                            Map<String, String> stats = Debug.getRuntimeStats();// mi[0].getMemoryStats();

                            Log.d(TAG, "****");
                            Log.d(TAG, "nativeHeapAlloc: " + nativeHeapAlloc);
                            Log.d(TAG, "nativeHeapaFree: " + nativeHeapaFree);
                            Log.d(TAG, "nativeHeapSize: " + nativeHeapSize);
                            Log.d(TAG, "pssCount: " + pssCount);

                            Log.d(TAG, "free: " + freeSize);
                            Log.d(TAG, "total: " + totalSize);
                            Log.d(TAG, "used: " + usedSize);

                            for (String key: stats.keySet()) {
                                Log.d(TAG, key + ": " + stats.get(key));
                            }
                            Log.d(TAG, "****");
                        }

                        try {
                            Thread.sleep(sleeptime);
                        } catch (Exception ex) {
                            Log.e(TAG, "Sleep failed (" + sleeptime + " ms): " + ex.getMessage());
                        }
                    }
                }
            });
            t.start();
        }
    }

    public void stop() {
        synchronized (started) {
            started = Boolean.FALSE;
        }
    }
    /*

    val memoryInfo = ActivityManager.MemoryInfo()
(getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
val nativeHeapSize = memoryInfo.totalMem
val nativeHeapFreeSize = memoryInfo.availMem
val usedMemInBytes = nativeHeapSize - nativeHeapFreeSize
val usedMemInPercentage = usedMemInBytes * 100 / nativeHeapSize
Log.d("AppLog", "total:${Formatter.formatFileSize(this, nativeHeapSize)} " +
        "free:${Formatter.formatFileSize(this, nativeHeapFreeSize)} " +
        "used:${Formatter.formatFileSize(this, usedMemInBytes)} ($usedMemInPercentage%)")

    */

}
