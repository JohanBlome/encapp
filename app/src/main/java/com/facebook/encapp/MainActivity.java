package com.facebook.encapp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import java.util.Vector;
import android.view.View;
import android.widget.TextView;

import com.facebook.encapp.utils.SizeUtils;

import java.util.ArrayList;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "encapp";
    private HashMap<String, String> mExtraDataHashMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String[] permissions = retrieveNotGrantedPermissions(this);

        if (permissions != null && permissions.length > 0) {
            int REQUEST_ALL_PERMISSIONS = 0x4562;
            ActivityCompat.requestPermissions(this, permissions, REQUEST_ALL_PERMISSIONS);
        }

        TextView mTvTestRun = (TextView)findViewById(R.id.tv_testrun);
        if (getInstrumentedTest()) {

            mTvTestRun.setVisibility(View.VISIBLE);
            (new Thread(new Runnable() {
                @Override
                public void run() {
                    performInstrumentedTest();
                }
            })).start();
        }


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

    /**
     * Start automated test run.
     *
     *
     */
    private void performInstrumentedTest() {
        Log.d(TAG, "Instrumentation test - let us start!");



        if (mExtraDataHashMap.containsKey("list_codecs")) {
            TextView mTvTestRun = (TextView)findViewById(R.id.tv_testrun);
            mTvTestRun.setVisibility(View.VISIBLE);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
                    MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
                    TextView logText = (TextView)findViewById(R.id.logText);
                    logText.append("-- List supported codecs --\n\n");
                    for (MediaCodecInfo info: codecInfos) {
                        if (info.isEncoder()) {
                            // TODO: from Android 10 (api 29) we can check
                            // codec type (hw or sw codec)
                            StringBuilder str = new StringBuilder("Codec: ");
                            str.append(info.getName());
                            String[] types = info.getSupportedTypes();
                            for (String tp: types) {
                                str.append(" type: " + tp);
                            }
                            if (str.toString().toLowerCase().contains("video")) {
                                logText.append("\n" + str);
                                Log.d(TAG, str.toString());
                            }
                        }
                    }
                }
            });

            return;
        }
        // Need to set source
        if (!mExtraDataHashMap.containsKey("file")) {
           Log.e(TAG, "Need filename!");
           return;
        }
        String filename = mExtraDataHashMap.get("file");

        final VideoConstraints[] vcCombinations = gatherUserSelectionsOnAllResolutions();
        if (vcCombinations == null || vcCombinations.length <= 0) {
            Log.e(TAG, "Invalid Video parameters");
            return;
        }

        String dynamicData = null;
        if (mExtraDataHashMap.containsKey("dyn")) {
            dynamicData = mExtraDataHashMap.get("dyn");
        }

        int timeout = 20;
        if (mExtraDataHashMap.containsKey("video_timeout")) {
            timeout = Integer.parseInt(mExtraDataHashMap.get("video_timeout"));
        }

        Size refFrameSize = new Size(1280,720);
        if (mExtraDataHashMap.containsKey("ref_res")) {
            refFrameSize = SizeUtils.parseXString(mExtraDataHashMap.get("ref_res"));
        }

        final TextView logText = (TextView)findViewById(R.id.logText);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logText.append("\nNumber of encodings: "+vcCombinations.length);
            }
        });
        for (VideoConstraints vc: vcCombinations) {
            final String settings = vc.getSettings();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    logText.append("\n\nStart encoding: "+settings);
                }
            });

            int framesToDecode = (int)(timeout * vc.getFPS());
            Log.d(TAG, "frames to transcode: "+framesToDecode);
            Transcoder transcoder = new Transcoder();
            final String status = transcoder.transcode(vc, filename, refFrameSize, framesToDecode, dynamicData);
            if(status.length() > 0) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        logText.append("\nEncoding failed: "+settings);
                        logText.append("\n" + status);
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        logText.append("\nDone encoding: " + settings);
                    }
                });
            }
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView mTvTestRun = (TextView)findViewById(R.id.tv_testrun);
                mTvTestRun.setVisibility(View.GONE);
            }
        });
    }
    private VideoConstraints[] gatherUserSelectionsOnAllResolutions() {
        String[] bitrates = {};
        String[] resolutions = {};
        String[] encoders = {};
        String[] keys = {};
        String[] fps={};
        String[] mod={};
        if (getInstrumentedTest()) {
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

        }

        int index = 0;
        Vector<VideoConstraints> vc = new Vector<>();
        for (int eC = 0; eC < encoders.length; eC++) {
            for (int mC = 0; mC < mod.length; mC++) {
                for (int vC = 0; vC < resolutions.length; vC++) {
                    for (int fC = 0; fC < fps.length; fC++) {
                        for (int bC = 0; bC < bitrates.length; bC++) {
                            for (int kC = 0; kC < keys.length; kC++) {
                                VideoConstraints constraints = new VideoConstraints();
                                Size videoSize = SizeUtils.parseXString(resolutions[vC]);
                                constraints.setVideoSize(videoSize);
                                constraints.setBitRate(Math.round( Float.parseFloat(bitrates[bC]) * 1000));
                                constraints.setKeyframeRate(Integer.parseInt(keys[kC]));
                                constraints.setFPS(Integer.parseInt(fps[fC]));
                                int ref_fps = (mExtraDataHashMap.get("ref_fps") != null) ? Integer.parseInt(mExtraDataHashMap.get("ref_fps")) : 30;
                                constraints.setReferenceFPS(ref_fps);
                                int ltrCount = (mExtraDataHashMap.get("ltrc") != null) ? Integer.parseInt(mExtraDataHashMap.get("ltrc")) : 6;
                                constraints.setLTRCount(ltrCount);
                                int hierLayerCount = (mExtraDataHashMap.get("hierl") != null) ? Integer.parseInt(mExtraDataHashMap.get("hierl")) : 0;
                                constraints.setHierStructLayerCount(hierLayerCount);
                                constraints.setVideoEncoderIdentifier(encoders[eC]);
                                constraints.setConstantBitrate(false);
                                if (mod[mC].equals("cbr")) {
                                    constraints.setConstantBitrate(true);
                                }
                                Log.e(TAG, constraints.getSettings());
                                boolean keySkipFrames = (mExtraDataHashMap.containsKey("skip_frames")) ? Boolean.parseBoolean(mExtraDataHashMap.get("skip_frames")) : false;
                                constraints.setSkipFrames(keySkipFrames);
                                vc.add(constraints);
                            }
                        }
                    }
                }
            }
        }
        return vc.toArray(new VideoConstraints[0]);
    }

}
