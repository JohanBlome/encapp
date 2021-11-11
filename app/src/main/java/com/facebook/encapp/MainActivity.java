package com.facebook.encapp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.facebook.encapp.utils.JSONTestCaseBuilder;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;
import java.util.Locale;

import static junit.framework.Assert.assertTrue;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "encapp";
    private HashMap<String, String> mExtraDataHashMap;
    TextureView mTextureView;
    private int mEncodingsRunning = 0;
    private final Object mEncodingLockObject = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String[] permissions = retrieveNotGrantedPermissions(this);

        if (permissions != null && permissions.length > 0) {
            int REQUEST_ALL_PERMISSIONS = 0x4562;
            ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
        }

        TextView mTvTestRun = findViewById(R.id.tv_testrun);
        if (getInstrumentedTest()) {

            mTvTestRun.setVisibility(View.VISIBLE);
            (new Thread(new Runnable() {
                @Override
                public void run() {
                    performInstrumentedTest();
                }
            })).start();
        } else {
            listCodecs();
        }
    }

    String codecInfoTypeToText(String type, MediaCodecInfo.CodecCapabilities cap, int tab_length) {
        String tab = String.format("%" + (tab_length * 4) + "s", "");
        String tab2 = String.format("%" + ((tab_length + 1) * 4) + "s", "");

        StringBuilder str = new StringBuilder();
        str.append(tab + "mime_type: " + type + "\n");
        str.append(tab + "max_supported_instances: " + cap.getMaxSupportedInstances() + "\n");

        for (int col : cap.colorFormats) {
            str.append(tab + "color {\n");
            str.append(tab2 + "format: " + col + "\n");
            switch (col) {
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible:
                    str.append(tab2 + "name: COLOR_FormatYUV420Flexible\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                    str.append(tab2 + "name: COLOR_FormatYUV420SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar:
                    str.append(tab2 + "name: COLOR_QCOM_FormatYUV420SemiPlanar\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface:
                    str.append(tab2 + "name: COLOR_FormatSurface\n");
                    break;
                case MediaCodecInfo.CodecCapabilities.COLOR_Format24bitBGR888:
                    str.append(tab2 + "name: COLOR_Format24bitBGR888\n");
                    break;
            }
            str.append(tab + "}\n");
        }

        for (MediaCodecInfo.CodecProfileLevel prof : cap.profileLevels) {
            str.append(tab + "profile {\n");
            str.append(tab2 + "profile: " + prof.profile + "\n");
            str.append(tab2 + "level: " + prof.level + "\n");
            str.append(tab + "}\n");
        }

        MediaFormat format = cap.getDefaultFormat();
        //Odds are that if there is no default profile - nothing else will have defaults anyway...
        if (format.getString(MediaFormat.KEY_PROFILE) != null) {
            str.append("\nDefault settings:");
            str.append(TestParams.getFormatInfo(format));
        }
        return str.toString();
    }

    String codecInfoToText(MediaCodecInfo info, int tab_length) {
        String tab = String.format("%" + (tab_length * 4) + "s", "");
        String tab2 = String.format("%" + ((tab_length + 1) * 4) + "s", "");

        // TODO(johan): from Android 10 (api 29) we can check
        // codec type (hw or sw codec)
        StringBuilder str = new StringBuilder();
        str.append(tab + "MediaCodec {\n");
        str.append(tab2 + "name: " + info.getName() + "\n");
        String[] types = info.getSupportedTypes();
        for (String tp : types) {
            str.append(tab2 + "type {\n");
            str.append(codecInfoTypeToText(tp, info.getCapabilitiesForType(tp), tab_length + 2));
            str.append(tab2 + "}\n");
        }
        str.append(tab + "}\n");
        return str.toString();

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

    private void listCodecs() {
        TextView mTvTestRun = findViewById(R.id.tv_testrun);
        mTvTestRun.setVisibility(View.VISIBLE);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
                MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
                TextView logText = findViewById(R.id.logText);
                logText.append("codecs {\n");
                for (MediaCodecInfo info : codecInfos) {
                    if (info.isEncoder()) {
                        String str = codecInfoToText(info, 2);
                        if (str.toLowerCase(Locale.US).contains("video")) {
                            logText.append(str + "\n");
                            Log.d(TAG, str);
                        }
                    }
                }
                logText.append("}\n");
            }
        });
    }

    /**
     * Start automated test run.
     */
    private void performInstrumentedTest() {
        Log.d(TAG, "Instrumentation test - let us start!");
        final TextView logText = findViewById(R.id.logText);

        if (mExtraDataHashMap.containsKey("list_codecs")) {
            listCodecs();
            return;
        }

        int concurrent = 1;
        if (mExtraDataHashMap.containsKey("conc")) {
            concurrent = Integer.parseInt(mExtraDataHashMap.get("conc"));
        }

        boolean tmp = true; // By default always write encoded file
        if (mExtraDataHashMap.containsKey("write")) {
            tmp = !mExtraDataHashMap.get("write").equals("false");
        }

        final boolean writeOutput = tmp;
        String filename = null;
        // Override the filename in the json configure by adding cli "-e -file FILENAME"
        if (mExtraDataHashMap.containsKey("file")) {
            filename = mExtraDataHashMap.get("file");
        }
        Size refFrameSize = null;
        // A new input size is probably needed in that case
        if (mExtraDataHashMap.containsKey("ref_res")) {
            refFrameSize = SizeUtils.parseXString(mExtraDataHashMap.get("ref_res"));
        }

        Log.d(TAG, "file: " + filename);

        /// Use json builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Vector<TestParams> vcCombinations;
                if (mExtraDataHashMap.containsKey("test")) {
                    vcCombinations = JSONTestCaseBuilder.parseFile(mExtraDataHashMap.get("test"));
                } else {
                    vcCombinations = buildSettingsFromCommandline();
                }
                Log.d(TAG, "Test params collected - start # " + vcCombinations.size() + " of tests, concurrent = " + concurrent);
                for (TestParams vc : vcCombinations) {
                    Log.d(TAG, "filename is " + filename + " swap out " + vc.getInputfile());
                    if (filename != null) {
                        vc.setInputfile(filename);
                    }
                    Log.d(TAG, "Done? " + vc.getInputfile());
                    if (refFrameSize != null) {
                        vc.setReferenceSize(refFrameSize);
                    }
                    if (concurrent > 1) {
                        while (mEncodingsRunning >= concurrent) {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        increaseEncodingsInflight();

                        (new Thread(new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "Start threaded encoding");
                                RunEncoding(vc, logText, writeOutput);
                                Log.d(TAG, "Done threaded encoding");
                            }

                        })).start();
                    } else {
                        increaseEncodingsInflight();
                        Log.d(TAG, "start encoding, no sep thread");
                        RunEncoding(vc, logText, writeOutput);
                        Log.d(TAG, "Done encoding");
                    }
                }


                // Wait for all transcoding to be finished
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        do {
                            try {
                                Log.d(TAG, "Sleep for test check");
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            Log.d(TAG, "number of encodings running:" + mEncodingsRunning);

                        } while (mEncodingsRunning > 0);
                        Log.d(TAG, "Done with encodings");
                        TextView mTvTestRun = findViewById(R.id.tv_testrun);
                        mTvTestRun.setVisibility(View.GONE);
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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
                                constraints.setVideoEncoderIdentifier(encoders[eC]);
                                constraints.setBitrateMode(mod[mC]);

                                constraints.setIframeSizePreset(TestParams.IFRAME_SIZE_PRESETS.valueOf(iframesize.toUpperCase(Locale.US)));
                                if (mExtraDataHashMap.containsKey("tlc")) {
                                    constraints.setTemporalLayerCount(Integer.parseInt(mExtraDataHashMap.get("tlc")));
                                }
                                Log.e(TAG, constraints.getSettings());
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
                        assertTrue(status, false);
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Log.d(TAG, "Total time: " + stats.getProcessingTime());
                            Log.d(TAG, "Total frames: " + stats.getFrameCount());
                            Log.d(TAG, "Time per frame: " + (stats.getProcessingTime() / stats.getFrameCount()));
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

