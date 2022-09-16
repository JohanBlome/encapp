package com.facebook.encapp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.proto.Tests;
import com.facebook.encapp.utils.CameraSource;
import com.facebook.encapp.utils.CliSettings;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.MemoryLoad;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.VsyncHandler;
import com.facebook.encapp.utils.grafika.Texture2dProgram;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Stack;
import java.util.Vector;


public class MainActivity extends AppCompatActivity {
    private final static String TAG = "encapp.main";
    private static boolean mStable = false;
    private final Object mTestLockObject = new Object();
    int mUIHoldtimeSec = 0;
    boolean mPursuitOver = false;
    MemoryLoad mMemLoad;
    Stack<Encoder> mEncoderList = new Stack<>();
    CameraSource mCameraSource = null;
    OutputMultiplier mCameraSourceMultiplier;
    Vector<OutputAndTexture> mViewsToDraw = new Vector<>();
    int mCameraMaxWidth = -1;
    int mCameraMaxHeight = -1;
    boolean mLayoutDone = false;
    TableLayout mTable;
    TextView mLogText = null;
    int mCameraCount = 0;
    private Bundle mExtraData;
    private int mInstancesRunning = 0;
    VsyncHandler mVsyncHandler;
    final static int WAIT_TIME_MS = 5000;  // 5 secs

    public static boolean isStable() {
        return mStable;
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

    private String getCurrentAppVersion() {
        PackageManager pm = this.getPackageManager();
        PackageInfo pInfo = null;
        String currentVersion = "";
        try {
            pInfo = pm.getPackageInfo(this.getPackageName(), 0);
            currentVersion = pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e1) {
            e1.printStackTrace();
        }

        return currentVersion;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mVsyncHandler = new VsyncHandler();
        mVsyncHandler.start();
        //setContentView(R.layout.activity_main);
        setContentView(R.layout.activity_visualize);

        String[] permissions = retrieveNotGrantedPermissions(this);

        if (permissions != null && permissions.length > 0) {
            int REQUEST_ALL_PERMISSIONS = 0x4562;
            ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
        }
        // Need to check permission strategy
        getTestSettings();
        CliSettings.setWorkDir(this, mExtraData);
        boolean useNewMethod = true;
        if (mExtraData != null && mExtraData.size() > 0) {
            useNewMethod = !mExtraData.getBoolean(CliSettings.OLD_AUTH_METHOD, false);
        }

        if (Build.VERSION.SDK_INT >= 30 && useNewMethod && !Environment.isExternalStorageManager()) {
            Log.d(TAG, "Check ExternalStorageManager");
            //request for the permission

            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException ex) {
                Log.e(TAG, "No activity found for handling the permission intent: " + ex.getLocalizedMessage());
                // System.exit(-1);
                Toast.makeText(this, "Missing MANAGE_APP_ALL_FILES_ACCESS_PERMISSION request,", Toast.LENGTH_LONG).show();
            }
        }

        mTable = findViewById(R.id.viewTable);

        Log.d(TAG, "Passed all permission checks");
        if (getTestSettings()) {
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
                } else {
                    decoders.append(str);
                }

            }


        }
        encoders.append("}\n");
        decoders.append("}\n");

        log(encoders.toString());
        log("\n" + decoders);
        Log.d(TAG, encoders + "\n" + decoders);

        FileWriter writer = null;
        try {
            writer = new FileWriter(CliSettings.getWorkDir() + "/codecs.txt");
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

    /**
     * Check if a test has fired up this activity.
     *
     * @return true if extra settings are available
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
     * Run everything found in the bundle data
     * and exit.
     */
    private void performAllTests() {
        mMemLoad = new MemoryLoad(this);
        mMemLoad.start();
        if (mExtraData.containsKey(CliSettings.TEST_UI_HOLD_TIME_SEC)) {
            mUIHoldtimeSec = Integer.parseInt(mExtraData.getString(CliSettings.TEST_UI_HOLD_TIME_SEC, "0"));
        }

        if (mExtraData.containsKey(CliSettings.LIST_CODECS)) {
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
            return;
        }


        Tests testcases = null;
        try {
            if (mExtraData.containsKey(CliSettings.TEST_CONFIG)) {
                Log.d(TAG, "test_path: " + mExtraData.getString(CliSettings.TEST_CONFIG));
                // get the basename
                Path basename = FileSystems.getDefault().getPath("", mExtraData.getString(CliSettings.TEST_CONFIG));
                FileInputStream fis = new FileInputStream(basename.toFile());
                testcases = Tests.parseFrom(fis);
                // Log.d(TAG, "data: " + Files.readAllBytes(basename));
                // ERROR
                if (testcases.getTestList().size() <= 0) {
                    Log.e(TAG, "Failed to read test");
                    return;
                }
                if (testcases == null) {
                    Log.d(TAG, "No test case");
                    return;
                }

                int nbrViews = 0;
                boolean hasCameraPreview = false;
                // Prepare views for visualization
                for (Test test : testcases.getTestList()) {
                    nbrViews += countInputPreviews(test);
                    showCameraPreview(test);
                }
                final int tmp = nbrViews;
                //nbrViews = 0;
                if (nbrViews > 0) {
                    runOnUiThread(() -> {
                        Log.d(TAG, "Prepare views");
                        prepareViews(tmp, mViewsToDraw, mTable);
                    });
                } else {
                    runOnUiThread(() -> {
                        Log.d(TAG, "Prepare text");
                        setContentView(R.layout.activity_main);
                        mLogText = findViewById(R.id.logText);
                    });
                }

                while (nbrViews > 0 && mViewsToDraw.size() < nbrViews) {
                    synchronized (mViewsToDraw) {
                        try {
                            mViewsToDraw.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                boolean cameraStarted = false;
                int pursuit = -1;// TODO: pursuit
                while (!mPursuitOver) {
                    Log.d(TAG, "** Starting tests, " + testcases.getTestCount() +
                            " number of combinations (parallels not counted) **");
                    for (Test test : testcases.getTestList()) {
                        mCameraCount = 0; // All used should have been closed already
                        if (pursuit > 0) pursuit -= 1;
                        pursuit = test.getInput().getPursuit();
                        mPursuitOver = false;
                        Log.d(TAG, "pursuit: " + pursuit);
                        while (!mPursuitOver) {
                            if (pursuit > 0) pursuit -= 1;

                            if (pursuit == 0) {
                                mPursuitOver = true;
                            }

                            Stack<Thread> threads = new Stack<>();
                            Thread t = startTest(test, threads);

                            Log.d(TAG, "Started the test, check camera: " + mCameraCount);
                            if (mCameraCount > 0 && !cameraStarted) {
                                Log.d(TAG, "Start cameras");
                                Surface outputSurface = null;
                                while (outputSurface == null) {
                                    Log.d(TAG, "Wait for input surface");
                                    outputSurface = mCameraSourceMultiplier.getInputSurface();
                                    try {
                                        Thread.sleep(50);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                //Use max size, get from camera or test
                                mCameraSource.registerSurface(outputSurface, 1280, 720);
                                if (test.getInput().hasFramerate())
                                    mCameraSource.setFps(test.getInput().getFramerate());
                                CameraSource.start();
                                cameraStarted = true;
                            }

                            // Start them all
                            for (Encoder enc : mEncoderList) {
                                synchronized (enc) {
                                    Log.d(TAG, "Start encoder: " + enc);
                                    enc.notifyAll();
                                }
                            }

                            // Wait for stable conditions
                            int stableCounter = 0;
                            while (stableCounter < mEncoderList.size()) {
                                int count = 0;

                                for (Encoder enc : mEncoderList) {
                                    if (enc.isStable() || !enc.mTest.getInput().getShow()) {
                                        count++;
                                    }
                                }
                                stableCounter = count;
                            }
                            Log.d(TAG, "Check views and layout done: " + mViewsToDraw.size() + " - " + mLayoutDone);
                            while (mViewsToDraw.size() > 0 && !mLayoutDone) {
                                try {
                                    Log.d(TAG, "Wait for layout to be made");
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                            mStable = true;
                            Log.d(TAG, "\n\n*** All inputs stable - go on!   ***\n\n");
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
                                        try {
                                            for (Thread t_: threads) {
                                                Log.d(TAG, "Thread: " + t_.getName() + " - state: " + t_.getState());
                                            }
                                            Thread p = threads.pop();
                                            Log.d(TAG, "Join " + p.getName() + ", state = " + p.getState() + ", threads: " + threads.size());
                                            // Most of the time all parallel tests are run the same time length, assuming this we can catch
                                            // some errors here
                                            p.join(WAIT_TIME_MS);
                                            if (p.getState() == Thread.State.WAITING ||
                                                p.getState() == Thread.State.TIMED_WAITING ||
                                                p.getState() == Thread.State.BLOCKED) {
                                                Log.d(TAG, p.getName() + " is still waiting. \nThis is probably not correct.");
                                                p.interrupt();
                                                decreaseTestsInflight(); //????
                                            }
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
            }
        }
        catch (IOException iox) {
                Log.e(TAG, "Test failed: " + iox.getMessage());
        }

    }



    /**
     * Traverse list of test cases below this and starts them keeping
     * track of all threads.
     *
     * @param test
     * @param threads a lit of started test case, each has its own thread
     * @return the thread that belong to the Test test
     */
    public Thread startTest(Test test, Stack<Thread> threads) {
        Log.d(TAG, "Start test: " + test.getCommon().getDescription());

        increaseTestsInflight();
        Thread t = PerformTest(test);

        if (test.hasParallel()) {
            for (Test parallell : test.getParallel().getTestList()) {
                Log.d(TAG, "Sleep before other parallel");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Log.d(TAG, "Start parallel");
                threads.push(startTest(parallell, threads));
            }
        }

        if (test.getInput().getFilepath().equalsIgnoreCase("camera")) {
            mCameraCount += 1;
        }
        return t;
    }


    /**
     * This is the starting point for the actual test
     * In here a processing unit (encoder/decode/transcoder) is created
     * and if needed a surface/surfacetexture is attached
     *
     * @param test
     * @return the thread belonging to Test test
     */
    private Thread PerformTest(Test test) {
        String filePath = test.getInput().getFilepath();
        Log.d(TAG, "Run test case, source : " + filePath);
        Log.d(TAG, "test" + test.toString());
        Thread t;

        final String description = test.getCommon().getDescription();

        if (test.getConfigure().getEncode()) {
            log("\n\nStart Test: " + description);
        } else {
            log("\n\nStart Test of decoder: " + description +
                    "(" + mInstancesRunning + ")");
        }


        Encoder coder;
        synchronized (mEncoderList) {
            Log.d(TAG, "Source file  = " + filePath.toLowerCase(Locale.US));
            OutputAndTexture ot = null;
            if (test.getConfigure().getSurface()) {

                if (mViewsToDraw.size() > 0 &&
                        test.getInput().hasShow() &&
                        test.getInput().getShow()) {
                    ot = getFirstFreeTextureView();
                }

            }
            if (filePath.toLowerCase(Locale.US).equals("camera")) {
                setupCamera(ot);
            }

            if (filePath.toLowerCase(Locale.US).contains(".mp4") ||
                    filePath.toLowerCase(Locale.US).contains(".webm")) {
                // A decoder is needed
                if (ot != null) {
                    ot.mMult = new OutputMultiplier(mVsyncHandler);
                    coder = new SurfaceTranscoder(ot.mMult, mVsyncHandler);
                    ot.mEncoder = coder;
                    if (!test.getConfigure().getEncode() &&
                            ot.mMult != null &&
                            ot.mView != null) {
                        Log.d(TAG, "Decode only, use view size");
                        ot.mMult.confirmSize(ot.mView.getWidth(), ot.mView.getHeight());
                    }
                } else {
                    coder = new SurfaceTranscoder(new OutputMultiplier(mVsyncHandler), mVsyncHandler);
                }
            } else if (test.getConfigure().getSurface()) {
                OutputMultiplier mult = null;
                if (filePath.toLowerCase(Locale.US).contains(".raw") ||
                        filePath.toLowerCase(Locale.US).contains(".yuv") ||
                        filePath.toLowerCase(Locale.US).contains(".rgba")) {
                    mult = new OutputMultiplier(Texture2dProgram.ProgramType.TEXTURE_2D, mVsyncHandler);
                } else if (filePath.toLowerCase(Locale.US).contains("camera")) {
                    mult = mCameraSourceMultiplier;
                }
                if (ot != null) {
                    ot.mMult = mult;
                }
                coder = new SurfaceEncoder(this, mult);

            } else {
                coder = new BufferEncoder();
            }


            if (ot != null) {
                ot.mEncoder = coder;
                ot.mMult.addSurfaceTexture(ot.mView.getSurfaceTexture());
            }
            Log.d(TAG, "Add encoder to list");
            mEncoderList.add(coder);
        }


        t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Start test");
                    final String status = coder.start(test);
                    Log.d(TAG, "Test done: " + coder.mFilename + " - " + coder.getStatistics().getId());
                    Log.d(TAG, "Get stats");
                    final Statistics stats = coder.getStatistics();
                    stats.setAppVersion(getCurrentAppVersion());
                    try {
                        String fullFilename = CliSettings.getWorkDir() + "/" + stats.getId() + ".json";
                        Log.d(TAG, "Write stats for " + stats.getId() + " to " + fullFilename);
                        FileWriter fw = new FileWriter(fullFilename, false);
                        stats.writeJSON(fw);
                        fw.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    Log.d(TAG, "One test done, instances running: " + mInstancesRunning);
                    if (status.length() > 0) {
                        log("\nTest failed: " + description);
                        log("\n" + status);
                        //    if (test.getPursuit() == 0) { TODO: pursuit
                        Log.d(TAG, "Pursuit over");
                        mPursuitOver = true;
                        //  } else {
                        //      Assert.assertTrue(status, false);
                        //   }

                    } else {

                        try {
                            Log.d(TAG, "Total time: " + stats.getProcessingTime());
                            Log.d(TAG, "Total frames: " + stats.getEncodedFrameCount());
                            if (stats.getEncodedFrameCount() > 0) {
                                Log.d(TAG, "Time per frame: " + (stats.getProcessingTime() / stats.getEncodedFrameCount()));
                            }
                        } catch (ArithmeticException aex) {
                            Log.e(TAG, aex.getMessage());
                        }
                    }
                } finally {
                    decreaseTestsInflight();
                    log("\nDone test: " + description);
                    Log.d(TAG, "Done test: " + coder.getStatistics().getId() + ", to go: " + mInstancesRunning);
                }
            }
        }, "TestRunner_" + coder.getOutputFilename());
        t.start();

        int waitTime = 10000; //ms
        while (!coder.initDone() && t.isAlive()) {
            try {
                // If we do not wait for the init to de done before starting next
                // transcoder there may be issue in the surface handling on lower levels
                // on certain hw (not thread safe)
                Log.d(TAG, "Sleep while waiting for init to be done");
                Thread.sleep(200);
                waitTime -= 200;
                if (waitTime < 0) {
                    Log.e(TAG, "Init not ready within " + waitTime + " ms, probably failure");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return t;
    }


    public void createTable(int nbrViews, TableLayout layout) {
        //int count = nbrViews;/mViewsToDraw.size();
        int cols = 1;
        int rows = 1;

        rows = (int) (Math.sqrt(nbrViews));
        cols = (int) ((float) nbrViews / (float) rows + 0.5f);
        int extra = nbrViews - rows * cols;
        if (extra > 0) {
            cols += extra;
        }
        int counter = 0;

        for (int row = 0; row < rows; row++) {
            TableRow tr = new TableRow(this);
            for (int col = 0; col < cols; col++) {
                try {
                    if (counter >= nbrViews)
                        break;
                    OutputAndTexture item = mViewsToDraw.elementAt(counter++);
                    item.mView.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT,
                            TableRow.LayoutParams.WRAP_CONTENT));
                    tr.addView(item.mView);
                    Log.d(TAG, "Add to: " + tr + ", count = " + tr.getChildCount());
                } catch (IndexOutOfBoundsException iox) {
                    iox.printStackTrace();
                }
            }
            Log.d(TAG, "Table row, " + tr + ", children: " + tr.getChildCount());
            tr.setLayoutParams(new TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, 0, 0.25f));
            layout.addView(tr);
        }
        layout.setShrinkAllColumns(true);
        layout.setStretchAllColumns(true);
        Log.d(TAG, "Request layout: " + layout.getChildCount());
        layout.requestLayout();
        layout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                Log.d(TAG, "Table layed out.");
                mLayoutDone = true;
            }
        });
    }

    public OutputAndTexture getFirstFreeTextureView() {
        for (OutputAndTexture ot : mViewsToDraw) {
            if (ot.mEncoder == null) {
                return ot;
            }
        }

        return null;
    }

    public void log(String text) {
        if (mLogText != null) {
            runOnUiThread(() -> {
                mLogText.append(text);
                Log.d(TAG, text);
            });
        }
    }

    public void setupCamera(OutputAndTexture ot) {
        mCameraSource = CameraSource.getCamera(this);
        if (mCameraSourceMultiplier == null) {
            mCameraSourceMultiplier = new OutputMultiplier(mVsyncHandler);
            mCameraSourceMultiplier.confirmSize(mCameraMaxWidth, mCameraMaxHeight);
        }
        if (ot != null) {
            ot.mMult = mCameraSourceMultiplier;
            //ot.mMult.addSurfaceTexture(ot.mView.getSurfaceTexture());
            mCameraSourceMultiplier = ot.mMult;
            mCameraSourceMultiplier.setHighPrio();
            // This is for camera, if mounted at an angle
            Size previewSize = new Size(mCameraMaxWidth, mCameraMaxHeight);
            configureTextureViewTransform(ot.mView, previewSize, ot.mView.getWidth(), ot.mView.getHeight());
        }

    }

    public boolean showCameraPreview(Test test) {
        boolean hasPreview = false;
        if (test.hasParallel()) {
            for (Test par_test : test.getParallel().getTestList()) {
                hasPreview |= showCameraPreview(par_test);
            }
        }
        if (test.getInput().hasShow() && test.getInput().getShow()) {
            if (test.getInput().getFilepath().toLowerCase(Locale.US).equals("camera")) {
                // Check max camera size
                if (test.getInput().hasResolution()) {
                    Size res = SizeUtils.parseXString(test.getInput().getResolution());
                    if (res.getWidth() > mCameraMaxWidth) {
                        mCameraMaxWidth = res.getWidth();
                    }
                    if (res.getHeight() > mCameraMaxHeight) {
                        mCameraMaxHeight = res.getHeight();
                    }
                }
            }
            hasPreview = true;
        }
        return hasPreview;
    }


    public int countInputPreviews(Test test) {
        int nbr = 0;
        if (test.hasParallel()) {
            for (Test par_test : test.getParallel().getTestList()) {
                nbr += countInputPreviews(par_test);
            }
        }

        String filePath = test.getInput().getFilepath();
        if (test.getInput().hasShow() && test.getInput().getShow()) {
            nbr++;
        }
        return nbr;
    }

    public void prepareViews(int count, Vector<OutputAndTexture> views, TableLayout layout) {
        Log.d(TAG, "Prepare views, count = " + count);
        for (int i = 0; i < count; i++) {

            TextureView view = new TextureView(this);
            OutputAndTexture item = new OutputAndTexture(null, view, null);
            views.add(item);

            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "onSurfaceTextureAvailable, item = " + item + ", view = " + view + ", surface = " + surface + ", w, h = " + width + ", " + height);
                    //item.mMult.addSurfaceTexture(surface);
                    synchronized (mViewsToDraw) {
                        views.notifyAll();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

                }
            });
        }

        createTable(views.size(), layout);
    }


    private void configureTextureViewTransform(TextureView view, Size previewSize, int viewWidth, int viewHeight) {
        if (null == view) {
            return;
        }
        int rotation = this.getWindowManager().getDefaultDisplay().getRotation();
        Log.d(TAG, "Rotation = " + rotation);
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        view.setTransform(matrix);
    }

    public void hideSystemUI() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private class OutputAndTexture {
        OutputMultiplier mMult;
        TextureView mView;
        Encoder mEncoder;

        public OutputAndTexture(OutputMultiplier mul, TextureView view, Encoder encoder) {
            mMult = mul;
            mView = view;
            mEncoder = encoder;
        }
    }
}
