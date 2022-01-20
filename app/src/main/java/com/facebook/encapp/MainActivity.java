package com.facebook.encapp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.facebook.encapp.utils.Assert;
import com.facebook.encapp.utils.JSONTestCaseBuilder;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.ParseData;
import com.facebook.encapp.utils.SessionParam;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Vector;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "encapp";
    private Bundle mExtraData;
    TextureView mTextureView;
    private int mInstancesRunning = 0;
    private final Object mTestLockObject = new Object();
    int mUIHoldtimeSec = 0;
    boolean mPursuitOver = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String[] permissions = retrieveNotGrantedPermissions(this);

        if (permissions != null && permissions.length > 0) {
            int REQUEST_ALL_PERMISSIONS = 0x4562;
            ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
        }
        // Need to check permission strategy
        getTestSettings();
        boolean useNewMethod = true;
        if (mExtraData != null && mExtraData.size() > 0) {
            useNewMethod = !mExtraData.getBoolean(ParseData.OLD_AUTH_METHOD, false);
        }

        if ( Build.VERSION.SDK_INT >= 30 && useNewMethod && ! Environment.isExternalStorageManager()) {
            Log.d(TAG, "Check ExternalStorageManager");
            //request for the permission
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
            Assert.assertTrue("Failed to get permission as ExternalStorageManager", Environment.isExternalStorageManager());
        }

        Log.d(TAG, "Passed all permission checks");
        if (getTestSettings()) {
            TextView mTvTestRun = findViewById(R.id.tv_testrun);

            mTvTestRun.setVisibility(View.VISIBLE);
            (new Thread(new Runnable() {
                @Override
                public void run() {
                    performInstrumentedTest();
                    exit();
                }
            })).start();
        } else {
            listCodecs();
        }

    }


    protected void listCodecs() {
        Log.d(TAG, "List codecs");
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();

        StringBuffer encoders = new StringBuffer("--- List of supported encoders  ---\n\n");
        StringBuffer decoders = new StringBuffer("--- List of supported decoders  ---\n\n");

        for (MediaCodecInfo info : codecInfos) {
            String str = MediaCodecInfoHelper.toText(info, 2);
            if (str.toLowerCase(Locale.US).contains("video")) {
                if (info.isEncoder()) {
                    encoders.append(str + "\n");
                }  else {
                    decoders.append(str + "\n");
                }

            }


        }
        final TextView logText = findViewById(R.id.logText);
        runOnUiThread(new Runnable() {
                          @Override
                          public void run() {

                              logText.append(encoders);
                              logText.append("\n" + decoders);
                              Log.d(TAG, encoders + "\n" + decoders);

                          }
                      });

        FileWriter writer = null;
        try {
            writer = new FileWriter(new File("/sdcard/codecs.txt"));
            Log.d(TAG, "Write to file");
            writer.write(encoders.toString());
            writer.write("\n*******\n");
            writer.write(decoders.toString());
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void exit() {
        Log.d(TAG, "Finish and remove");
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
        Log.d(TAG, "EXIT");
    }

    private static String[] retrieveNotGrantedPermissions(Context context) {
        ArrayList<String> nonGrantedPerms = new ArrayList<>();
        try {
            String[] manifestPerms = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;
            if (manifestPerms == null || manifestPerms.length == 0) {
                return null;
            }

            for (String permName : manifestPerms) {
                int permission = ActivityCompat.checkSelfPermission(context, permName);
                if (permission != PackageManager.PERMISSION_GRANTED) {
                    nonGrantedPerms.add(permName);
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        return nonGrantedPerms.toArray(new String[nonGrantedPerms.size()]);
    }

    /**
     * Check if a test has fired up this activity.
     *
     * @return
     */
    private boolean getTestSettings() {
        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            mExtraData = bundle;
            return true;
        }

        return false;
    }

    public void increaseTestsInflight() {
        synchronized (mTestLockObject) {
            mInstancesRunning++;
        }
    }

    public void decreaseTestsInflight() {
        synchronized (mTestLockObject) {
            mInstancesRunning--;
        }
    }

    /**
     * Start automated test run.
     */
    private void performInstrumentedTest() {
        Log.d(TAG, "ENTER: performInstrumentedTest");
        Log.d(TAG, "Instrumentation test - let us start!");
        final TextView logText = findViewById(R.id.logText);
        if (mExtraData.containsKey(ParseData.TEST_UI_HOLD_TIME_SEC)) {
            mUIHoldtimeSec = Integer.parseInt(mExtraData.getString(ParseData.TEST_UI_HOLD_TIME_SEC, "0"));
        }
        if (mExtraData.containsKey(ParseData.LIST_CODECS)) {
            listCodecs();
            try {
                Log.d(TAG, "codecs listed, hold time: " + mUIHoldtimeSec);
                if (mUIHoldtimeSec > 0) {
                    Thread.sleep(mUIHoldtimeSec * 1000);
                }
                Log.d(TAG, "Done sleeping");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    TextView mTvTestRun = findViewById(R.id.tv_testrun);
                    mTvTestRun.setVisibility(View.GONE);
                }
            });
            return;
        }

        int overrideConcurrent = 1;
        if (mExtraData.containsKey(ParseData.MULTIPLE_CONC_SESSIONS)) {
            overrideConcurrent = mExtraData.getInt(ParseData.MULTIPLE_CONC_SESSIONS, 0);
        }

        boolean tmp = true; // By default always write encoded file
        if (mExtraData.containsKey(ParseData.WRITE_FILE)) {
            tmp = mExtraData.getBoolean(ParseData.WRITE_FILE);
        }

        SessionParam sp = new SessionParam();
        final boolean writeOutput = tmp;
        // Override the filename in the json configure by adding cli "-e -file FILENAME"
        if (mExtraData.containsKey(ParseData.FILE)) {
            sp.setInputFile(mExtraData.getString(ParseData.FILE));
        }

        // A new input size is probably needed in that case
        if (mExtraData.containsKey(ParseData.REF_RESOLUTION)) {
            sp.setInputResolution(mExtraData.getString(ParseData.REF_RESOLUTION));
        }

        if (mExtraData.containsKey(ParseData.REF_FPS)) {
            sp.setInputFps(mExtraData.getString(ParseData.REF_FPS));
        }

        if (mExtraData.containsKey(ParseData.FPS)) {
            sp.setOutputFps(mExtraData.getString(ParseData.FPS));
        }

        if (mExtraData.containsKey(ParseData.RESOLUTION)) {
            sp.setOutputResolution(mExtraData.getString(ParseData.RESOLUTION));
        }

        /// Use json builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Vector<TestParams> vcCombinations = null;
                if (mExtraData.containsKey(ParseData.TEST_CONFIG)) {
                    vcCombinations = new Vector<>();
                    if (!JSONTestCaseBuilder.parseFile(mExtraData.getString(ParseData.TEST_CONFIG), vcCombinations, sp)) {
                        Assert.assertTrue("Failed to parse tests", false);
                    }
                } else {
                    vcCombinations = buildSettingsFromCommandline();
                }

                if (vcCombinations.size() == 0) {
                    Log.e(TAG, "No test cases created");
                    return;
                }
                int pursuit = vcCombinations.firstElement().getPursuit();
                while (!mPursuitOver) {
                    if (vcCombinations.size() == 0) {
                        Log.w(TAG, "warning: no test to run");
                        break;
                    }
                    Log.d(TAG,"** Starting tests, " + vcCombinations.size() + " number of combinations **");
                    for (TestParams vc : vcCombinations) {
                        if (pursuit > 0) pursuit -= 1;
                        int vcConc = vc.getConcurrentCodings();
                        int concurrent = (vcConc > overrideConcurrent) ? vcConc : overrideConcurrent;

                        if (pursuit == 0) {
                            mPursuitOver = true;
                        }
                        if (concurrent > 1 || pursuit != 0) {
                            increaseTestsInflight();
                            Log.d(TAG, "Start another threaded test " + mInstancesRunning);
                            (new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    Log.d(TAG, "Start threaded test");
                                    RunTestCase(vc, logText, writeOutput);
                                    Log.d(TAG, "Done threaded test");
                                }

                            })).start();

                            Log.d(TAG, "Concurrent or pursuit");
                            while (mInstancesRunning >= concurrent && pursuit == 0) {
                                try {
                                    Log.d(TAG, "Sleep 200ms");
                                    Thread.sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }

                            if (pursuit != 0) {
                                Log.d(TAG, "pursuit sleep 1 sec, instances: " + mInstancesRunning);
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }

                            }
                        } else {
                            increaseTestsInflight();
                            Log.d(TAG, "start test, no sep thread");
                            RunTestCase(vc, logText, writeOutput);
                            Log.d(TAG, "Done test");
                        }
                    }

                    Log.d(TAG, "All tests queued up, check pursuit over: " + mPursuitOver);
                }
                Log.d(TAG, "All tests queued up, wait for finish");
                // Wait for all transcoding to be finished
                do {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    Log.d(TAG, "number of instances running:" + mInstancesRunning);

                } while (mInstancesRunning > 0);
                Log.d(TAG, "Done with tests, instances: "+ mInstancesRunning);
                try {
                    if (mUIHoldtimeSec > 0) {
                        Thread.sleep(mUIHoldtimeSec);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch(IOException e){
                Log.e(TAG, "@Exception when running tests: "+e.getMessage());
                e.printStackTrace();
            }
        }

        Log.d(TAG, "EXIT: performInstrumentedTest");
    }

    private Vector<TestParams> buildSettingsFromCommandline() {
        String[] bitrates = {"1000"};
        String[] resolutions = null;
        String[] encoders = {"hevc"};
        String[] keys = {"10"};
        String[] fps = {"30"};
        String[] mod = {"vbr"};


        //Check if there are settings
        if (mExtraData.containsKey(ParseData.ENCODER)) {
            String data = mExtraData.getString(ParseData.ENCODER);
            encoders = data.split(",");
        }
        if (mExtraData.containsKey(ParseData.BITRATE)) {
            String data = mExtraData.getString(ParseData.BITRATE);
            bitrates = data.split(",");
        }
        if (mExtraData.containsKey(ParseData.RESOLUTION)) {
            String data = mExtraData.getString(ParseData.RESOLUTION);
            Log.d(TAG, "res data: " + data);
            resolutions = data.split(",");
        }
        if (mExtraData.containsKey(ParseData.KEYFRAME)) {
            String data = mExtraData.getString(ParseData.KEYFRAME);
            keys = data.split(",");
        }
        if (mExtraData.containsKey(ParseData.FPS)) {
            String data = mExtraData.getString(ParseData.FPS);
            fps = data.split(",");
        }
        if (mExtraData.containsKey(ParseData.MODE)) {
            String data = mExtraData.getString(ParseData.MODE);
            mod = data.split(",");
        }

        String iframesize = mExtraData.getString(ParseData.IFRAME_SIZE_PRESET, "DEFAULT");
        int ref_fps = mExtraData.getInt(ParseData.REF_FPS, 30);
        String ref_resolution = mExtraData.getString(ParseData.REF_RESOLUTION,"1280x720");
        if (resolutions == null) {
            resolutions = new String[]{ref_resolution};
        }

        int index = 0;
        Vector<TestParams> vc = new Vector<>();
        for (int eC = 0; eC < encoders.length; eC++) {
            for (int mC = 0; mC < mod.length; mC++) {
                for (int vC = 0; vC < resolutions.length; vC++) {
                    for (int fC = 0; fC < fps.length; fC++) {
                        for (int bC = 0; bC < bitrates.length; bC++) {
                            for (int kC = 0; kC < keys.length; kC++) {
                                TestParams constraints = new TestParams();
                                constraints.setVideoSize(SizeUtils.parseXString(resolutions[vC]));
                                constraints.setBitRate(Math.round(Float.parseFloat(bitrates[bC]) * 1000));
                                constraints.setKeyframeInterval(Integer.parseInt(keys[kC]));
                                constraints.setFPS(Integer.parseInt(fps[fC]));
                                constraints.setReferenceFPS(ref_fps);
                                constraints.setReferenceSize(SizeUtils.parseXString(ref_resolution));
                                constraints.setCodecName(encoders[eC]);
                                constraints.setBitrateMode(mod[mC]);
                                constraints.setInputfile(mExtraData.getString(ParseData.FILE));

                                constraints.setIframeSizePreset(TestParams.IFRAME_SIZE_PRESETS.valueOf(iframesize.toUpperCase(Locale.US)));
                                if (mExtraData.containsKey(ParseData.TEMPORAL_LAYER_COUNT)) {
                                    constraints.setTemporalLayerCount(mExtraData.getInt(ParseData.TEMPORAL_LAYER_COUNT,1));
                                }

                                constraints.setSkipFrames(mExtraData.getBoolean(ParseData.SKIPFRAMES, false));
                                vc.add(constraints);
                            }
                        }
                    }
                }
            }
        }
        if (vc.size() == 0) {
            Log.d(TAG, "No test created");
            Log.d(TAG, "encoders: " + encoders.length);
            Log.d(TAG, "mod: " + mod.length);
            Log.d(TAG, "resolutions: " + resolutions.length);
            Log.d(TAG, "fps: " + fps.length);
            Log.d(TAG, "bitrates: " + bitrates.length);
            Log.d(TAG, "keys: " + keys.length);
        }
        return vc;
    }


    private void RunTestCase(TestParams vc, TextView logText, boolean fwriteOutput) {
        Log.d(TAG, "Run test case, source : " + vc.getInputfile());
        try {
            final String settings = vc.getSettings();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (vc.noEncoding()) {
                        logText.append("\n\nStart Test of decoder: " + vc.getDescription() +
                                       "(" + mInstancesRunning +")");
                    } else {
                        logText.append("\n\nStart Test: " + settings);
                    }
                }
            });

            final BufferEncoder transcoder;

            if (vc.getInputfile().toLowerCase(Locale.US).contains(".raw") ||
                    vc.getInputfile().toLowerCase(Locale.US).contains(".yuv")) {
                transcoder = new BufferEncoder();
            } else {
                transcoder = new SurfaceTranscoder();
            }

            final String status = transcoder.encode(vc,
                    fwriteOutput);
            Log.d(TAG, "Get stats");
            final Statistics stats = transcoder.getStatistics();
            try {
                Log.d(TAG, "Write stats for " + stats.getId() + " to /sdcard/" + stats.getId() + ".json");
                FileWriter fw = new FileWriter("/sdcard/" + stats.getId() + ".json", false);
                stats.writeJSON(fw);
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "On test done, instances running: " + mInstancesRunning);
            mPursuitOver = true;
            if (status.length() > 0) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        logText.append("\nTest failed: " + settings);
                        logText.append("\n" + status);
                        if (vc.getPursuit() == 0) {
                            Log.d(TAG, "Pursuit over");
                            mPursuitOver = true;
                        } else {
                            Assert.assertTrue(status, false);
                        }
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Log.d(TAG, "Total time: " + stats.getProcessingTime());
                            Log.d(TAG, "Total frames: " + stats.getEncodedFrameCount());
                            Log.d(TAG, "Time per frame: " + (stats.getProcessingTime() / stats.getEncodedFrameCount()));
                        } catch (ArithmeticException aex) {
                            Log.e(TAG, aex.getMessage());
                        }
                        logText.append("\nDone test: " + settings);
                    }
                });
            }
        } finally {
            decreaseTestsInflight();
        }
    }
}

