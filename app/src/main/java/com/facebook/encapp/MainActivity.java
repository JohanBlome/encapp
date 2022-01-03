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
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.facebook.encapp.utils.Assert;
import com.facebook.encapp.utils.JSONTestCaseBuilder;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.SessionParam;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Vector;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "encapp";
    private HashMap<String, String> mExtraDataHashMap;
    TextureView mTextureView;
    private int mEncodingsRunning = 0;
    private final Object mEncodingLockObject = new Object();
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
        getInstrumentedTest();
        boolean useNewMethod = true;
        if (mExtraDataHashMap != null && mExtraDataHashMap.size() > 0) {
            String old_method = mExtraDataHashMap.get("old_auth");
            if (old_method != null) {
                useNewMethod = (old_method.equals("true") || old_method.equals("1"))?false: true;
            }
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
        if (getInstrumentedTest()) {
            TextView mTvTestRun = findViewById(R.id.tv_testrun);

            mTvTestRun.setVisibility(View.VISIBLE);
            (new Thread(new Runnable() {
                @Override
                public void run() {
                    performInstrumentedTest();
                    finish();
                }
            })).start();
        } else {
            listCodecs();
        }

    }

    protected void listCodecs() {
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
        System.exit(0);
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
    private boolean getInstrumentedTest() {
        Intent intent = getIntent();
        mExtraDataHashMap = (HashMap<String, String>) intent.getSerializableExtra("map");

        return mExtraDataHashMap != null;
    }

    public void increaseEncodingsInflight() {
        synchronized (mEncodingLockObject) {
            mEncodingsRunning++;
        }
    }

    public void decreaseEncodingsInflight() {
        synchronized (mEncodingLockObject) {
            mEncodingsRunning--;
        }
    }

    /**
     * Start automated test run.
     */
    private void performInstrumentedTest() {
        Log.d(TAG, "ENTER: performInstrumentedTest");
        Log.d(TAG, "Instrumentation test - let us start!");
        final TextView logText = findViewById(R.id.logText);

        if (mExtraDataHashMap.containsKey("list_codecs")) {
            listCodecs();
            try {
                if (mUIHoldtimeSec > 0) {
                    Thread.sleep(mUIHoldtimeSec);
                }
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
        if (mExtraDataHashMap.containsKey("conc")) {
            overrideConcurrent = Integer.parseInt(mExtraDataHashMap.get("conc"));
        }

        boolean tmp = true; // By default always write encoded file
        if (mExtraDataHashMap.containsKey("write")) {
            tmp = !mExtraDataHashMap.get("write").equals("false");
        }

        SessionParam sp = new SessionParam();
        final boolean writeOutput = tmp;
        // Override the filename in the json configure by adding cli "-e -file FILENAME"
        if (mExtraDataHashMap.containsKey("file")) {
            sp.setInputFile(mExtraDataHashMap.get("file"));
        }

        // A new input size is probably needed in that case
        if (mExtraDataHashMap.containsKey("ref_res")) {
            sp.setInputResolution(mExtraDataHashMap.get("ref_res"));
        }

        if (mExtraDataHashMap.containsKey("ref_fps")) {
            sp.setInputFps(mExtraDataHashMap.get("ref_fps"));
        }

        if (mExtraDataHashMap.containsKey("fps")) {
            sp.setOutputFps(mExtraDataHashMap.get("fps"));
        }

        if (mExtraDataHashMap.containsKey("enc")) {
            sp.setOutputCodec(mExtraDataHashMap.get("enc"));
        }

        if (mExtraDataHashMap.containsKey("res")) {
            sp.setOutputResolution(mExtraDataHashMap.get("res"));
        }

        /// Use json builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Vector<TestParams> vcCombinations = null;
                if (mExtraDataHashMap.containsKey("test")) {
                    vcCombinations = new Vector<>();
                    if (!JSONTestCaseBuilder.parseFile(mExtraDataHashMap.get("test"), vcCombinations, sp)) {
                        Assert.assertTrue("Failed to parse tests", false);
                    }
                } else {
                    vcCombinations = buildSettingsFromCommandline();
                }

                while (!mPursuitOver) {
                    if (vcCombinations.size() == 0) {
                        Log.w(TAG, "warning: no test to run");
                        break;
                    }
                    Log.d(TAG,"** Starting tests, " + vcCombinations.size() + " number of combinations **");
                    for (TestParams vc : vcCombinations) {
                        boolean pursuit = vc.getPursuit().length() > 0;
                        int vcConc = vc.getConcurrentCodings();
                        int concurrent = (vcConc > overrideConcurrent) ? vcConc : overrideConcurrent;

                        if (!pursuit) {
                            mPursuitOver = true;
                        }
                        if (concurrent > 1 || pursuit) {
                            increaseEncodingsInflight();
                            Log.d(TAG, "Start another threaded encoding " + mEncodingsRunning );
                            (new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    Log.d(TAG, "Start threaded encoding");
                                    RunEncoding(vc, logText, writeOutput);
                                    Log.d(TAG, "Done threaded encoding");
                                }

                            })).start();

                            Log.d(TAG, "Concurrent or pursuit");
                            while (mEncodingsRunning >= concurrent && !pursuit) {
                                try {
                                    Log.d(TAG, "Sleep 200ms");
                                    Thread.sleep(200);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }

                            if (pursuit) {
                                Log.d(TAG, "pursuit sleep 1 sec, encodings: " + mEncodingsRunning );
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }

                            }
                        } else {
                            increaseEncodingsInflight();
                            Log.d(TAG, "start encoding, no sep thread");
                            RunEncoding(vc, logText, writeOutput);
                            Log.d(TAG, "Done encoding");
                        }
                    }

                    Log.d(TAG, "All tests queued up, check pursuit over: " + mPursuitOver);
                }
                Log.d(TAG, "All encodings queued up, wait for finish");
                // Wait for all transcoding to be finished
                do {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    Log.d(TAG, "number of encodings running:" + mEncodingsRunning);

                } while (mEncodingsRunning > 0);
                Log.d(TAG, "Done with encodings, encodings: "+mEncodingsRunning);
                try {
                    if (mUIHoldtimeSec > 0) {
                        Thread.sleep(mUIHoldtimeSec);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {

                        TextView mTvTestRun = findViewById(R.id.tv_testrun);
                        Log.d(TAG, "Hide view! - Do not hide!");
                        //mTvTestRun.setVisibility(View.GONE);
                    }
                });
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
        String[] keys = {"default"};
        String[] fps = {"30"};
        String[] mod = {"vbr"};


        //Check if there are settings
        if (mExtraDataHashMap.containsKey("enc")) {
            String data = mExtraDataHashMap.get("enc");
            encoders = data.split(",");
        }
        if (mExtraDataHashMap.containsKey("bit")) {
            String data = mExtraDataHashMap.get("bit");
            bitrates = data.split(",");
        }
        if (mExtraDataHashMap.containsKey("res")) {
            String data = mExtraDataHashMap.get("res");
            Log.d(TAG, "res data: " + data);
            resolutions = data.split(",");
        }
        if (mExtraDataHashMap.containsKey("key")) {
            String data = mExtraDataHashMap.get("key");
            keys = data.split(",");
        }
        if (mExtraDataHashMap.containsKey("fps")) {
            String data = mExtraDataHashMap.get("fps");
            fps = data.split(",");
        }
        if (mExtraDataHashMap.containsKey("mod")) {
            String data = mExtraDataHashMap.get("mod");
            mod = data.split(",");
        }

        mUIHoldtimeSec = mExtraDataHashMap.containsKey("ui_hold_sec") ? Integer.parseInt(mExtraDataHashMap.get("ui_hold_sec")): 0;

        String iframesize = (mExtraDataHashMap.get("ifsize") != null) ? mExtraDataHashMap.get("ifsize") : "DEFAULT";
        int ref_fps = (mExtraDataHashMap.get("ref_fps") != null) ? Integer.parseInt(mExtraDataHashMap.get("ref_fps")) : 30;
        String ref_resolution = (mExtraDataHashMap.get("ref_res") != null) ? mExtraDataHashMap.get("ref_res") : "1280x720";
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

                                constraints.setIframeSizePreset(TestParams.IFRAME_SIZE_PRESETS.valueOf(iframesize.toUpperCase(Locale.US)));
                                if (mExtraDataHashMap.containsKey("tlc")) {
                                    constraints.setTemporalLayerCount(Integer.parseInt(mExtraDataHashMap.get("tlc")));
                                }

                                boolean keySkipFrames = (mExtraDataHashMap.containsKey("skip_frames")) && Boolean.parseBoolean(mExtraDataHashMap.get("skip_frames"));
                                constraints.setSkipFrames(keySkipFrames);
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


    private void RunEncoding(TestParams vc, TextView logText, boolean fwriteOutput) {
        Log.d(TAG, "Run encoding, source : " + vc.getInputfile());
        try {
            final String settings = vc.getSettings();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    logText.append("\n\nStart encoding: " + settings);
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
                FileWriter fw = new FileWriter("/sdcard/" + stats.getId() + ".json", false);
                stats.writeJSON(fw);
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "Done one encoding: " + mEncodingsRunning);
            if (status.length() > 0) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        logText.append("\nEncoding failed: " + settings);
                        logText.append("\n" + status);
                        if (vc.getPursuit().length() > 0) {
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
                        logText.append("\nDone encoding: " + settings);
                    }
                });
            }
        } finally {
            decreaseEncodingsInflight();
        }
    }
}

