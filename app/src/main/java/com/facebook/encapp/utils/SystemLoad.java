package com.facebook.encapp.utils;

import android.os.SystemClock;
import android.util.Log;

import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
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
    protected static final String CPU_DIR = "/sys/devices/system/cpu/";
    protected static final String TIME_IN_STATE = "/cpufreq/stats/time_in_state"; // ../cpu/cpuX/cpufreq/...
    protected static final String CPU_INFO = "/proc/cpuinfo";

    protected static final String FILE_QCOM_GPU_INFO_DIR = "/sys/class/kgsl/kgsl-3d0/";
    protected static final String FILE_QCOM_GPU_MODEL = FILE_QCOM_GPU_INFO_DIR + "gpu_model";
    protected static final String FILE_QCOM_GPU_MIN_CLOCK = FILE_QCOM_GPU_INFO_DIR + "min_clock_mhz";
    protected static final String FILE_QCOM_GPU_MAX_CLOCK = FILE_QCOM_GPU_INFO_DIR + "max_clock_mhz";
    protected static final String FILE_QCOM_GPU_BUSY_PERCENTAGE = FILE_QCOM_GPU_INFO_DIR + "gpu_busy_percentage";
    protected static final String FILE_QCOM_GPU_CLOCK_MHZ = FILE_QCOM_GPU_INFO_DIR + "clock_mhz";


    boolean mCaptureGpu = true;
    boolean mCaptureCpu = true;
    protected boolean mCannotRead = false;
    Boolean started = Boolean.FALSE;
    int mFrequencyHz = 10;
    HashMap<String, String> mGPUInfo = new HashMap<>();
    ArrayList<String> mGpuLoad = new ArrayList<>();
    ArrayList<String> mGpuClock = new ArrayList<>();
    //the cpu data is captured per cpu, crate a daa table with cpu and timestamp
    // and the available frequencies will form the columns
    ArrayList<Integer> mCpuNumList = new ArrayList<>();
    ArrayList<CPUInfo> mCpuList = new ArrayList<>();
    ArrayList<String> mCpuTimeInState = new ArrayList<>(); //cpu=X:ts=Y:data=Text
    public void start() {
        // check whether the directory exists
        if (! Files.exists(Paths.get(FILE_QCOM_GPU_INFO_DIR))) {
            Log.w(TAG, "QCOM GPU info dir does not exist: " + FILE_QCOM_GPU_INFO_DIR);
            mCaptureGpu = false;
        }

        if (mCaptureGpu) {
            String tmp = readSystemData(FILE_QCOM_GPU_MODEL);
            if (tmp.equals("")) {
                Log.e(TAG, "Could not read system data, \"adb root && adb shell setenforce 0\" needed");
                mCaptureGpu = false;
            }
            Log.d(TAG, "GPU model: " + tmp);
            mGPUInfo.put("gpu_model", tmp.trim());
            tmp = readSystemData(FILE_QCOM_GPU_MIN_CLOCK);
            mGPUInfo.put("gpu_min_clock", tmp.trim());
            tmp = readSystemData(FILE_QCOM_GPU_MAX_CLOCK);
            mGPUInfo.put("gpu_max_clock", tmp.trim());

            if (mCannotRead) {
                Log.e(TAG, "Could not read system files, quit SystemLoad");
                mCaptureGpu = false;
            }
        }
        if (Files.exists(Paths.get(CPU_INFO))) {
            String cpuInfo = readSystemData(CPU_INFO);
            /*
            processor	: 0
            BogoMIPS	: 49.15
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bti
            CPU implementer	: 0x41
            CPU architecture: 8
            CPU variant	: 0x1
            CPU part	: 0xd46
            CPU revision	: 1
             */
            int cpunum = -1;
            float performance = -1;
            String features = "";
            int cpu_implementer = -1;
            int cpu_arch = -1;
            int cpu_var = -1;
            int cpu_part = -1;
            int cpu_rev = -1;
            for (String line: cpuInfo.split("\n")) {
                String[] data = line.split(":");

                switch (data[0].trim()) {
                    case "processor":
                        cpunum = Integer.parseInt(data[1].trim());
                        performance = -1;
                        features = "";
                        cpu_implementer = -1;
                        cpu_arch = -1;
                        cpu_var = -1;
                        cpu_part = -1;
                        cpu_rev = -1;
                        break;
                    case "BogoMIPS":
                        performance = Float.parseFloat(data[1].trim());
                        break;
                    case "Features":
                        features = data[1];
                        break;
                    case "CPU implementer":
                        if (data[1].toLowerCase().contains("x")) {
                            data[1] = data[1].split("x")[1];
                            cpu_implementer = Integer.parseInt(data[1].trim(), 16);
                        } else {
                            cpu_implementer = Integer.parseInt(data[1].trim());
                        }
                        break;
                    case "CPU architecture":
                        cpu_arch = Integer.parseInt(data[1].trim());
                    case "CPU variant":
                        if (data[1].toLowerCase().contains("x")) {
                            data[1] = data[1].split("x")[1];
                            cpu_part = Integer.parseInt(data[1].trim(), 16);
                        } else {
                            cpu_part = Integer.parseInt(data[1].trim());
                        }
                        break;
                    case "CPU part":
                        if (data[1].toLowerCase().contains("x")) {
                            data[1] = data[1].split("x")[1];
                            cpu_part = Integer.parseInt(data[1].trim(), 16);
                        } else {
                            cpu_part = Integer.parseInt(data[1].trim());
                        }
                        break;
                    case "CPU revision":
                        if (data[1].toLowerCase().contains("x")) {
                            data[1] = data[1].split("x")[1];
                            cpu_rev = Integer.parseInt(data[1].trim(), 16);
                        } else {
                            cpu_rev = Integer.parseInt(data[1].trim());
                        }
                        break;
                    case "":
                        //Empty line signals eod?
                        Log.d(TAG, "Add cpu " + cpunum);
                        mCpuNumList.add(cpunum);
                        mCpuList.add(new CPUInfo(cpunum, performance, features,
                                            cpu_implementer, cpu_arch, cpu_var,
                                            cpu_part, cpu_rev));
                        break;
                    default:
                        Log.e(TAG, "Unhandled tem in /proc/cpuinfo");
                }
            }
        } else {
            mCaptureCpu = false;
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
                            // Snapshot not accumulated data
                            //tmp = readSystemData("/proc/stat");
                            //gpu
                            if (mCaptureGpu) {
                                tmp = readSystemData(FILE_QCOM_GPU_BUSY_PERCENTAGE);
                                mGpuLoad.add(tmp.trim());
                                tmp = readSystemData(FILE_QCOM_GPU_CLOCK_MHZ);
                                mGpuClock.add(tmp.trim());
                            }
                            if (mCaptureCpu) {
                                captureCpuStats();
                            }
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

    private void captureCpuStats() {
        long ts = SystemClock.elapsedRealtimeNanos();
        for (Integer cpunum: mCpuNumList) {
            String data = readSystemData(CPU_DIR + "/cpu"  + cpunum + TIME_IN_STATE);
            /*
            freq   time (10ms?), TODO: check that it adds up.
            "
            324000 5023977
            610000 567359
            820000 199683
            955000 99001
            1098000 84583V
            ...
            "
             */
            mCpuTimeInState.add("cpu=" + cpunum + ":ts=" + ts + ":data=" + data);
        }
    }

    public JSONArray getCPUInfo() throws org.json.JSONException {
        JSONArray root = new JSONArray();
        for (CPUInfo info: mCpuList) {
            JSONObject cpu = new JSONObject();
            cpu.put("id", info.mId);
            cpu.put("cpu_architecture", info.mCpuArchitecture);
            cpu.put("cpu_implementer", info.mCpuImplementer);
            cpu.put("cpu_part", info.mCpuPart);
            cpu.put("cpu_variant", info.mCpuVariant);
            cpu.put("cpu_revision", info.mCpuRevison);
            cpu.put("performance", info.mPerformance);
            cpu.put("features", info.mFeatures);
            root.put(cpu);
        }

        return root;
    }

    public JSONArray getCPUTimeInStateData() throws JSONException {
        JSONArray root = new JSONArray();
        String reg = "cpu=(?<cpu>[0-9]+):ts=(?<ts>[0-9]*):data=(?<data>[0-9 \n]*)";
        Pattern pattern = Pattern.compile(reg);

        for (String data: mCpuTimeInState) {
            Matcher matcher = pattern.matcher(data);
            if (matcher.find()) {
                JSONObject entry = new JSONObject();
                entry.put("cpu", matcher.group("cpu"));
                entry.put("timestamp_ns", matcher.group("ts"));
                // Then we add the frequencies and the load as is
                // That will make converting the json to a csv for plotting easy
                for (String line: matcher.group("data").split("\n")) {
                    // 324000 5023977
                    String[] perf = line.trim().split(" ");
                    // keep as string, we are not doing much with it anyways
                    entry.put(perf[0], perf[1]);
                }
                root.put(entry);
            }
        }
        return root;
    }
}
