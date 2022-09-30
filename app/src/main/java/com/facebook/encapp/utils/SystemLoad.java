package com.facebook.encapp.utils;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SystemLoad {
    protected static final String TAG = "encapp.systemload";
    protected static final String FILE_QCOM_GPU_INFO_DIR = "/sys/class/kgsl/kgsl-3d0/";
    protected static final String FILE_QCOM_GPU_MODEL = FILE_QCOM_GPU_INFO_DIR + "gpu_model";
    protected static final String FILE_QCOM_GPU_MIN_CLOCK = FILE_QCOM_GPU_INFO_DIR + "min_clock_mhz";
    protected static final String FILE_QCOM_GPU_MAX_CLOCK = FILE_QCOM_GPU_INFO_DIR + "max_clock_mhz";
    protected static final String FILE_QCOM_GPU_BUSY_PERCENTAGE = FILE_QCOM_GPU_INFO_DIR + "gpu_busy_percentage";
    protected static final String FILE_QCOM_GPU_CLOCK_MHZ = FILE_QCOM_GPU_INFO_DIR + "clock_mhz";


    protected boolean mCannotRead = false;
    Boolean started = Boolean.FALSE;
    int mFrequencyHz = 10;
    HashMap<String, String> mGPUInfo = new HashMap<>();
    ArrayList<String> mGpuLoad = new ArrayList<>();
    ArrayList<String> mGpuClock = new ArrayList<>();
    public void start() {
        // check whether the directory exists
        if (! Files.exists(Paths.get(FILE_QCOM_GPU_INFO_DIR))) {
            Log.w(TAG, "QCOM GPU info dir does not exist: " + FILE_QCOM_GPU_INFO_DIR);
            return;
        }

        String tmp = readSystemData(FILE_QCOM_GPU_MODEL);
        if (tmp.equals("")) {
            Log.e(TAG, "Could not read system data, \"adb root && adb shell setenforce 0\" needed");
            return;
        }
        Log.d(TAG, "GPU model: " + tmp);
        mGPUInfo.put("gpu_model", tmp.trim());
        tmp = readSystemData(FILE_QCOM_GPU_MIN_CLOCK);
        mGPUInfo.put("gpu_min_clock", tmp.trim());
        tmp = readSystemData(FILE_QCOM_GPU_MAX_CLOCK);
        mGPUInfo.put("gpu_max_clock", tmp.trim());

        if (mCannotRead) {
            Log.e(TAG, "Could not read system files, quit SystemLoad");
            return;
        }
        synchronized (started) {
            started = Boolean.TRUE;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (started & !mCannotRead) {
                        String tmp;
                        long sleeptime = (long) (1000.0 / mFrequencyHz);
                        synchronized (started) {
                            //Read cpu
                            //tmp = readSystemData("/proc/stat");
                            //gpu
                            tmp = readSystemData(FILE_QCOM_GPU_BUSY_PERCENTAGE);
                            mGpuLoad.add(tmp.trim());
                            tmp = readSystemData(FILE_QCOM_GPU_CLOCK_MHZ);
                            mGpuClock.add(tmp.trim());
                        }

                        try {
                            Thread.sleep(sleeptime);
                        } catch (Exception ex) {
                            Log.e(TAG, "Sleep failed (" + sleeptime + " ms): " + ex.getMessage());
                        }
                    }
                }
            }, "system_load_thread");
            t.start();
        }
    }

    public void stop() {
        synchronized (started) {
            started = Boolean.FALSE;
        }
    }

    private String readSystemData(String path) {
        Path realPath = FileSystems.getDefault().getPath("", path);

        BufferedReader reader = null;
        StringBuffer value = new StringBuffer();
        try {
            reader = new BufferedReader(new FileReader(realPath.toFile()));
            if (reader == null) {
                Log.e(TAG, "failed to create reader");
                return "";
            }

            Stream<String> data = reader.lines();
            Object[] lines = data.toArray();
            for (Object str: lines) {
                value.append(str);
                value.append('\n');
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read: " + path);
            mCannotRead = true;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return value.toString();
    }

    public int[] getGPULoadPercentagePerTimeUnit() {
        Iterator<String> iter = mGpuLoad.iterator();
        int[] ret = new int[mGpuLoad.size()];
        Pattern p = Pattern.compile("([0-9]*)");

        int counter = 0;
        //2 %
        while(iter.hasNext()) {
            String line = iter.next();
            Matcher m = p.matcher(line);

            if (m.find()) {
                String tmp = m.group(0);
                if (tmp.length() > 0) {
                    int val = Integer.parseInt(m.group(0));
                    ret[counter++] = val;
                }
            } else {
                Log.d(TAG, "Failed to parse: " + line);
            }
        }

        return ret;
    }

    public ArrayList<String> getGPUClockFreqPerTimeUnit() { return mGpuClock; }

    public HashMap<String, String> getGPUInfo() { return mGPUInfo; }

    public float getSampleFrequency() {
        return mFrequencyHz;
    }
}
