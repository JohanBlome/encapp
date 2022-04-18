package com.facebook.encapp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.proto.Tests;
import com.facebook.encapp.utils.CameraSource;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.MemoryLoad;
import com.facebook.encapp.utils.ParseData;
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
import java.util.Stack;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "encapp.main";
    private Bundle mExtraData;
    TextureView mTextureView;
    private int mInstancesRunning = 0;
    private final Object mTestLockObject = new Object();
    int mUIHoldtimeSec = 0;
    boolean mPursuitOver = false;
    MemoryLoad mMemLoad;
    Stack<Encoder> mEncoderList = new Stack<>();


    private String getCurrentAppVersion() {
        PackageManager pm = this.getPackageManager();
        PackageInfo pInfo = null;

        try {
            pInfo = pm.getPackageInfo(this.getPackageName(), 0);

        } catch (PackageManager.NameNotFoundException e1) {
            e1.printStackTrace();
        }
        String currentVersion = pInfo.versionName;

        return currentVersion;
    }


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
                    performAllTests();
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

        StringBuffer encoders = new StringBuffer("encoders {\n");
        StringBuffer decoders = new StringBuffer("decoders {\n");

        for (MediaCodecInfo info : codecInfos) {
            String str = MediaCodecInfoHelper.toText(info, 1);
            if (str.toLowerCase(Locale.US).contains("video")) {
                if (info.isEncoder()) {
                    encoders.append(str);
                }  else {
                    decoders.append(str);
                }

            }


        }
        encoders.append("}\n");
        decoders.append("}\n");
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
            writer.write(decoders.toString());
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void exit() {
        Log.d(TAG, "Finish and remove");
        mMemLoad.stop();
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

    int mCameraCount = 0;
    public Thread startTest(Test test, TextView logText, Stack<Thread> threads) {
        Log.d(TAG, "Start test: " + test.getCommon().getDescription());

        increaseTestsInflight();
        Thread t = RunTestCase(test, logText);

        if (test.hasParallel()) {
            for (Test parallell: test.getParallel().getTestList()) {
                Log.d(TAG, "Sleep before other parallel");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Log.d(TAG, "Start parallel");
                threads.push(startTest(parallell, logText, threads));
            }
        }

        if (test.getInput().getFilepath().toLowerCase().equals("camera")) {
            mCameraCount += 1;
        }
        return t;
    }


    private void performAllTests() {
        mMemLoad = new MemoryLoad(this);
        mMemLoad.start();
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


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Tests testcases = null;
            try {
                if (mExtraData.containsKey(ParseData.TEST_CONFIG)) {
                    Path path = FileSystems.getDefault().getPath("", mExtraData.getString(ParseData.TEST_CONFIG));
                    FileInputStream fis = new FileInputStream(path.toFile());
                    Log.d(TAG, "Test path = " + path.getFileName());
                    testcases = Tests.parseFrom(fis);
                    Log.d(TAG, "Data: " + Files.readAllBytes(path));
                    // ERROR
                    if (testcases.getTestList().size() <= 0) {
                        Log.e(TAG, "Failed to read test");
                        return;
                    }
                }

                int pursuit = -1;// TODO: pursuit
                while (!mPursuitOver) {
                    Log.d(TAG, "** Starting tests, " + testcases.getTestCount() +
                                     " number of combinations (parallels not counted) **");
                    for (Test test : testcases.getTestList()) {
                        mCameraCount = 0; // All used should have been closed already
                        if (pursuit > 0) pursuit -= 1;
                        pursuit = test.getInput().getPursuit();
                        mPursuitOver = false;
                        Log.d(TAG, "Pursuit = " + pursuit);
                        while (!mPursuitOver) {
                            if (pursuit > 0) pursuit -= 1;

                            if (pursuit == 0) {
                                mPursuitOver = true;
                            }

                            Stack<Thread> threads = new Stack<>();
                            Thread t = startTest(test, logText, threads);

                            Log.d(TAG, "Started the test, check camera: " + mCameraCount);
                            if (mCameraCount > 0) {
                                while (CameraSource.getClientCount() != mCameraCount) {
                                    try {
                                        Thread.sleep(50);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                Log.d(TAG, "Start cameras");
                                CameraSource.start();
                            }

                            // Start them all
                            for (Encoder enc : mEncoderList) {
                                synchronized (enc) {
                                    Log.d(TAG, "Start encoder: " + enc);
                                    enc.notifyAll();
                                }
                            }

                            Log.d(TAG, "pursuit = " + pursuit);
                            if (pursuit != 0) {
                                Log.d(TAG, "pursuit sleep, instances: " + mInstancesRunning);
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }

                            } else {
                                try {
                                    // Run next test serially so wait for this test
                                    // and all parallels to be finished first
                                    Log.d(TAG, "Join " + t.getName() + ", state = " + t.getState());
                                    t.join();

                                    while (!threads.empty()) {
                                        Thread p = threads.pop();
                                        try {
                                            Log.d(TAG, "Join " + p.getName() + ", state = " + p.getState());
                                            p.join();
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }

                        }
                    }
                }
                Log.d(TAG, "All tests queued up, wait for finish");
                do {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    Log.d(TAG, "number of instances running:" + mInstancesRunning);

                } while (mInstancesRunning > 0);
                Log.d(TAG, "Done with tests, instances: " + mInstancesRunning);
                try {
                    if (mUIHoldtimeSec > 0) {
                        Thread.sleep(mUIHoldtimeSec);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (IOException iox) {
                Log.e(TAG, "Test failed: " + iox.getMessage());
            }
        }
    }


    private Thread RunTestCase(Test test, TextView logText) {
        String filePath = test.getInput().getFilepath().toString();
        Log.d(TAG, "Run test case, source : " + filePath);
        Log.d(TAG, "test" + test.toString());
        Thread t;

        final String description = test.getCommon().getDescription().toString();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (test.getConfigure().getEncode()) {
                    logText.append("\n\nStart Test: " + description);
                } else {
                    logText.append("\n\nStart Test of decoder: " + description +
                            "(" + mInstancesRunning + ")");
                }
            }
        });

        final Encoder transcoder;
        synchronized (mEncoderList) {
            Log.d(TAG, "Source file  = " + filePath.toLowerCase(Locale.US));
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


            Log.d(TAG, "Add encoder to list");
            mEncoderList.add(transcoder);
        }


        t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    Log.d(TAG, "Start transcoder");
                    final String status = transcoder.start(test);
                    Log.d(TAG, "Transcoder done!");
                    Log.d(TAG, "Get stats");
                    final Statistics stats = transcoder.getStatistics();
                    stats.setAppVersion(getCurrentAppVersion());
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
        });
        t.start();

        int waitime = 2000; //ms
        while(!transcoder.initDone()) {
            try {
                // If we do not wait for the init to de done before starting next
                // transcoder there may be issue in the surface handling on lower levels
                // on certain hw (not thread safe)
                Log.d(TAG, "Sleep while waiting for init to be done");
                Thread.sleep(200);
                waitime -= 200;
                if (waitime < 0) {
                    Log.e(TAG, "Init not ready within " + waitime + " ms, probably failure");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return t;
    }


}

