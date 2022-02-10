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

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.proto.Tests;
import com.facebook.encapp.utils.CameraSource;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.ParseData;
import com.facebook.encapp.utils.SessionParam;
import com.facebook.encapp.utils.Statistics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

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
            try {
                startActivity(intent);
            }
            catch( android.content.ActivityNotFoundException ex) {
                Log.e(TAG, "No activity found for handling the permission intent: " + ex.getLocalizedMessage());
                System.exit(-1);
            }
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
                    Log.d(TAG, "Permission NOT granted: " + permName);
                } else {
                    Log.d(TAG, "Permission granted: " + permName);
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
        if (mExtraData.containsKey(ParseData.ENCODER)) {
            sp.setOutputCodec(mExtraData.getString(ParseData.ENCODER));
        }

        /// Use json builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Tests vcCombinations = null;
                if (mExtraData.containsKey(ParseData.TEST_CONFIG)) {
                    Path path = FileSystems.getDefault().getPath("", mExtraData.getString(ParseData.TEST_CONFIG));
                    FileInputStream fis = new FileInputStream(path.toFile());
                    Log.d(TAG, "Test path = " + path.getFileName());
                    vcCombinations = Tests.parseFrom(fis);
                    Log.d(TAG, "Data: "+Files.readAllBytes(path));
                    // ERROR
                }

                int pursuit =  -1;// TODO: pursuit
                int cameraCount = 0;
                while (!mPursuitOver) {
                    Log.d(TAG,"** Starting tests, " + vcCombinations.getTestCount() + " number of combinations **");
                    for (Test test : vcCombinations.getTestList()) {
                        if (pursuit > 0) pursuit -= 1;
                        int vcConc = 1; // TODO: vc.getConcurrentCodings();
                        int concurrent = (vcConc > overrideConcurrent) ? vcConc : overrideConcurrent;
                        pursuit = test.getInput().getPursuit();
                        mPursuitOver = false;
                        Log.d(TAG, "Concurrent = " + concurrent);
                        Log.d(TAG, "Pursuit = " + pursuit);
                        while (!mPursuitOver) {
                            if (pursuit > 0) pursuit -= 1;

                            if (test.getInput().getFilepath().toLowerCase().equals("camera")) {
                                cameraCount += 1;
                            }
                            if (pursuit == 0) {
                                mPursuitOver = true;
                            }

                            increaseTestsInflight();
                            Log.d(TAG, "Start another threaded test " + mInstancesRunning);
                            (new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    Log.d(TAG, "Start threaded test");
                                    RunTestCase(test, logText, writeOutput);
                                    Log.d(TAG, "Done threaded test");
                                }

                            })).start();

                            Log.d(TAG, "pursuit = " + pursuit);


                            if (pursuit != 0) {
                                Log.d(TAG, "pursuit sleep 1 sec, instances: " + mInstancesRunning);
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }

                            }

                        }
                    }
                }
                Log.d(TAG, "All tests queued up, wait for finish");

                if (cameraCount > 0) {
                    do {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }  while (CameraSource.getClientCount() != cameraCount);
                    Log.d(TAG, "Start cameras");
                    CameraSource.start();
                }

                do {
                    try {
                        Thread.sleep(2000);
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


    private void RunTestCase(Test test, TextView logText, boolean fwriteOutput) {
        String filePath = test.getInput().getFilepath().toString();
        Log.d(TAG, "Run test case, source : " + filePath);
        Log.d(TAG, "test" + test.toString());

        try {

            final String description = test.getCommon().getDescription().toString();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (test.getConfigure().getEncode()) {
                        logText.append("\n\nStart Test: " + description);
                    } else {
                        logText.append("\n\nStart Test of decoder: " + description +
                                "(" + mInstancesRunning +")");
                    }
                }
            });

            final Encoder transcoder;

            Log.d(TAG, "Source file  = "+filePath.toLowerCase(Locale.US));
            if (filePath.toLowerCase(Locale.US).contains(".raw") ||
                    filePath.toLowerCase(Locale.US).contains(".yuv") ||
                    filePath.toLowerCase(Locale.US).contains(".rgba") ||
                    filePath.toLowerCase(Locale.US).equals("camera")) {
                if (test.getConfigure().getSurface()) {
                    transcoder = new SurfaceEncoder(this);
                } else {
                    transcoder = new BufferEncoder();
                }

            } else {
                transcoder = new SurfaceTranscoder();
            }

            final String status = transcoder.encode(test,
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
                        logText.append("\nTest failed: " + description);
                        logText.append("\n" + status);
                    //    if (test.getPursuit() == 0) { TODO: pursuit
                            Log.d(TAG, "Pursuit over");
                            mPursuitOver = true;
                      //  } else {
                      //      Assert.assertTrue(status, false);
                     //   }
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
                        logText.append("\nDone test: " + description);
                    }
                });
            }
        } finally {
            decreaseTestsInflight();
        }
    }


}

