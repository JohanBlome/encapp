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
import android.view.WindowInsetsController;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.proto.Tests;
import com.facebook.encapp.utils.CameraSource;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.MemoryLoad;
import com.facebook.encapp.utils.OutputMultiplier;
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
import java.util.Vector;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "encapp.main";
    private Bundle mExtraData;
    private int mInstancesRunning = 0;
    private final Object mTestLockObject = new Object();
    int mUIHoldtimeSec = 0;
    boolean mPursuitOver = false;
    MemoryLoad mMemLoad;
    Stack<Encoder> mEncoderList = new Stack<>();
    CameraSource mCameraSource = null;
    OutputMultiplier mCameraSourceMultiplier;

    TableLayout mTable;
    private static boolean mStable = false;

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
        //setContentView(R.layout.activity_main);
        setContentView(R.layout.activity_visualize);

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
    
    public static boolean isStable() {
        return mStable;
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

        log(encoders.toString());
        log("\n" + decoders);
        Log.d(TAG, encoders + "\n" + decoders);

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
    public Thread startTest(Test test, Stack<Thread> threads) {
        Log.d(TAG, "Start test: " + test.getCommon().getDescription());

        increaseTestsInflight();
        Thread t = RunTestCase(test);

        if (test.hasParallel()) {
            for (Test parallell: test.getParallel().getTestList()) {
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

        if (test.getInput().getFilepath().toLowerCase().equals("camera")) {
            mCameraCount += 1;
        }
        return t;
    }


    public int countPotentialViews(Test test) {
        int nbr = 0;
        if (test.hasParallel()) {
            for (Test par_test:test.getParallel().getTestList()){
                nbr += countPotentialViews(par_test);
            }
        }

        String filePath = test.getInput().getFilepath();
        if (filePath.toLowerCase(Locale.US).contains(".raw") ||
                filePath.toLowerCase(Locale.US).contains(".yuv") ||
                filePath.toLowerCase(Locale.US).contains(".rgba") ||
                filePath.toLowerCase(Locale.US).equals("camera")) {
            if (filePath.toLowerCase(Locale.US).equals("camera")) {
                //Count the camera in 100s
                if (test.getInput().hasShow() && test.getInput().hasShow() == true) {
                    nbr += 100;
                }
            } else {
                //Ignore
                Log.d(TAG, filePath + " is will not give a visualization view");
            }
        } else {
            nbr++;
        }
        return nbr;
    }

    public void prepareViews(int count) {
        Log.d(TAG, "Prepare views, count = " + count);
        for (int i = 0; i < count; i++) {

            TextureView view = new TextureView(this);
            OutputAndTexture item = new OutputAndTexture(new OutputMultiplier(), view, null);
            mViewsToDraw.add(item);
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "onSurfaceTextureAvailable, item = " + item + ", view = " + view+", surface = " + surface + ", w, h = " + width + ", " + height);
                    item.mMult.addSurfaceTexture(surface);

                    synchronized (mViewsToDraw) {
                        mViewsToDraw.notifyAll();
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

        createTable();

    }


    private void performAllTests() {
        mMemLoad = new MemoryLoad(this);
        mMemLoad.start();
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

                int nbrViews = 0;
                // Prepare views for visualization
                for (Test test : testcases.getTestList()) {
                    nbrViews += countPotentialViews(test);
                }
                // Only one view for camera
                if (nbrViews >= 100) {
                    nbrViews = nbrViews%100 + 1;
                }
                final int tmp = nbrViews;
                //nbrViews = 0;
                if (nbrViews > 0) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "Prepare views");
                            prepareViews(tmp);
                        }
                    });
                }

                while(nbrViews > 0 && mViewsToDraw.size() < nbrViews) {
                    synchronized (mViewsToDraw) {
                        try {
                            mViewsToDraw.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
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
                            Thread t = startTest(test, threads);

                            Log.d(TAG, "Started the test, check camera: " + mCameraCount);
                            if (mCameraCount > 0) {
                                Log.d(TAG, "Start cameras");
                                Surface outputSurface = null;
                                while(outputSurface == null) {
                                    outputSurface = mCameraSourceMultiplier.getInputSurface();
                                    try {
                                        Thread.sleep(50);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                //Use max size, get from camera or test
                                mCameraSource.registerSurface(outputSurface, 1280, 720);
                                CameraSource.start();

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
                            while(stableCounter < mEncoderList.size()) {
                                int count = 0;
                                for (Encoder enc : mEncoderList) {
                                    if(enc.isStable()) {
                                        count++;
                                    }
                                }
                                stableCounter = count;
                            }
                            mStable = true;
                            Log.d(TAG, "\nAll inputs stable - go on!");
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

    Vector<OutputAndTexture> mViewsToDraw = new Vector<>();
    public void createTable() {
        //hideSystemUI();

        int count = mViewsToDraw.size();
        int cols = 1;
        int rows = 1;

        rows = (int)(Math.sqrt(count));
        cols = (int)((float)count / (float)rows + 0.5f);
        int extra = count - rows * cols;
        if (extra > 0){
            cols += extra;
        }
        int counter = 0;

        for (int row = 0; row < rows; row++) {
            TableRow tr = new TableRow(this);
            for (int col = 0; col < cols; col++) {
                try {
                    if (counter >= count)
                        break;
                    OutputAndTexture item = mViewsToDraw.elementAt(counter++);
                   // item.mView.setMinimumHeight(100);
                   // item.mView.setMinimumWidth(100);
                    item.mView.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.FILL_PARENT,
                            TableRow.LayoutParams.WRAP_CONTENT));
                    tr.addView(item.mView);
                    Log.d(TAG, "Add to: " + tr + ", count = " + tr.getChildCount());
                } catch (IndexOutOfBoundsException iox) {
                    iox.printStackTrace();
                }
            }
            Log.d(TAG, "Table row, " + tr + ", children: " + tr.getChildCount());
            tr.setLayoutParams(new TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, 0, 0.25f));
            mTable.addView(tr);
        }
        mTable.setShrinkAllColumns(true);
        mTable.setStretchAllColumns(true);
        Log.d(TAG, "Request layout: "+mTable.getChildCount());
        mTable.requestLayout();
    }

    public OutputAndTexture getFirstFreeTextureView() {
        for(OutputAndTexture ot: mViewsToDraw) {
            if (ot.mEncoder == null) {
                return ot;
            }
        }

        return null;
    }

    public void log(String text) {

    }
    
    public void setupCamera(OutputAndTexture ot) {
        mCameraSource = CameraSource.getCamera(this);
        if (ot != null) {
            mCameraSourceMultiplier = ot.mMult;
            // This is for camera, if mounted at an angle
            Size previewSize = new Size(1280, 720);
            configureTextureViewTransform(ot.mView, previewSize, ot.mView.getWidth(), ot.mView.getHeight());
        } else {
            mCameraSourceMultiplier = new OutputMultiplier();
        }
    }

    private Thread RunTestCase(Test test) {
        String filePath = test.getInput().getFilepath().toString();
        Log.d(TAG, "Run test case, source : " + filePath);
        Log.d(TAG, "test" + test.toString());
        Thread t;

        final String description = test.getCommon().getDescription().toString();

        if (test.getConfigure().getEncode()) {
            log("\n\nStart Test: " + description);
        } else {
            log("\n\nStart Test of decoder: " + description +
                    "(" + mInstancesRunning + ")");
        }



        final Encoder transcoder;
        synchronized (mEncoderList) {
            Log.d(TAG, "Source file  = " + filePath.toLowerCase(Locale.US));


            if (filePath.toLowerCase(Locale.US).contains(".raw") ||
                    filePath.toLowerCase(Locale.US).contains(".yuv") ||
                    filePath.toLowerCase(Locale.US).contains(".rgba") ||
                    filePath.toLowerCase(Locale.US).equals("camera")) {
                if (test.getConfigure().getSurface()) {
                    // If camera we need to share egl context
                    OutputAndTexture ot = null;
                    if (mCameraSourceMultiplier == null) {
                        if ( mViewsToDraw.size() > 0 ) {
                            ot = getFirstFreeTextureView();
                        }
                        setupCamera(ot);
                    }
                    transcoder = new SurfaceEncoder(this, mCameraSourceMultiplier);
                    if (ot != null)
                        ot.mEncoder = transcoder;
                } else {
                    transcoder = new BufferEncoder();
                }

            } else {
                OutputAndTexture ot = null;
                if (test.getInput().hasShow() && test.getInput().getShow() == true) {
                    ot = getFirstFreeTextureView();
                }
                if (ot != null) {
                    transcoder = new SurfaceTranscoder(ot.mMult);
                    ot.mEncoder = transcoder;
                } else {
                    Log.e(TAG, "No view found");
                    transcoder = new SurfaceTranscoder(new OutputMultiplier());
                }
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
                            Log.d(TAG, "Time per frame: " + (stats.getProcessingTime() / stats.getEncodedFrameCount()));
                        } catch (ArithmeticException aex) {
                            Log.e(TAG, aex.getMessage());
                        }
                        log("\nDone test: " + description);

                    }
                } finally {
                    decreaseTestsInflight();
                }
            }
        }, "TestRunner_" + test.getInput().getFilepath());
        t.start();

        int waitTime = 10000; //ms
        while(!transcoder.initDone()) {
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
        }else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        view.setTransform(matrix);
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
}

