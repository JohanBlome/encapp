package com.facebook.encapp;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SdkSuppress;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.SearchCondition;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.*;

/**
 * Instrumentation test, which will execute on an Android device.
 * <p>
 * Run instrumentation test:
 * adb shell am instrument
 * -w
 * -e class com.facebook.codecvalidate.com.facebook.encapp.CodecValidationInstrumentedTest
 * com.facebook.codecvalidate.test/android.support.test.runner.AndroidJUnitRunner
 * <p>
 * Provide any of the following arguments for a custom test run. Examples below:
 * -e test_timeout 60 (Predicted test duration in minutes, default is 60 minutes.
 * If the test is predicted to run longer than 1 hour, increase this timeout.)
 * -e video_timeout 20 (In seconds, default is 20 seconds.
 * Each video combination will play as long as this timeout.)
 * -e key 2 (Keyframe interval. Default = 2)
 * -e fps 30 (Frame Rate. Default = 30)
 * -e file Download/video_name (Absolute path from sdcard/ to video file to be played)
 * -e run all (all = run all combinations of encoder, bitrate and resolution, default.
 * single = run one single video combination only.) //TODO: fix
 * <p>
 * Used for 'single' video run only in combination with above arguments:
 * -e enc H265 (Encoder)
 * -e bit 250 (Bit Rate)
 * -e res 480x384 (Video size)
 * <p>
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 26)
public class CodecValidationInstrumentedTest {
    private static final String TAG = "encapp";

    private static final int LAUNCH_TIMEOUT = 5000;

    private static final String TARGET_PACKAGE =
            InstrumentationRegistry.getTargetContext().getPackageName();
    private static final String MODE =
            InstrumentationRegistry.getArguments().getString("mod");
    private static final String ENCODER =
            InstrumentationRegistry.getArguments().getString("enc");
    private static final String BITRATE =
            InstrumentationRegistry.getArguments().getString("bit");
    private static final String REF_RESOLUTION =
            InstrumentationRegistry.getArguments().getString("ref_res");
    private static final String RESOLUTION =
            InstrumentationRegistry.getArguments().getString("res");
    private static final String KEYFRAME =
            InstrumentationRegistry.getArguments().getString("key");
    private static final String REF_FPS =
            InstrumentationRegistry.getArguments().getString("ref_fps");
    private static final String FPS =
            InstrumentationRegistry.getArguments().getString("fps");
    private static final String WRITE_FILE =
            InstrumentationRegistry.getArguments().getString("write");
    private static final String RUN =
            InstrumentationRegistry.getArguments().getString("run");
    private static final String FILE =
            InstrumentationRegistry.getArguments().getString("file");
    private static final String TEST_TIMEOUT =
            InstrumentationRegistry.getArguments().getString("test_timeout");
    private static final String VIDEO_TIMEOUT =
            InstrumentationRegistry.getArguments().getString("video_timeout");
    private static final String SKIPFRAMES =
            InstrumentationRegistry.getArguments().getString("skfr");
    private static final String DYNAMIC =
            InstrumentationRegistry.getArguments().getString("dyn");
    private static final String LTR_COUNT =
            InstrumentationRegistry.getArguments().getString("ltrc");
    private static final String HIER_STRUCT_LAYER_COUNT =
            InstrumentationRegistry.getArguments().getString("hierl");
    private static final String LIST_CODECS =
            InstrumentationRegistry.getArguments().getString("list_codecs");

    private static long UI_TIMEOUT = 60 * 60 * 1000; //60 minutes in ms

    private UiDevice mDevice;

    private HashMap<String, String> mExtraDataHashMap;

    /**
     * Populates the hashmap with content.
     */
    public void collectExtraData() {
        mExtraDataHashMap = new HashMap<String, String>();
        if (MODE != null) {
            mExtraDataHashMap.put("mod", MODE);
            Log.e(TAG, "MODE: " + MODE);
        }
        if (ENCODER != null) {
            mExtraDataHashMap.put("enc", ENCODER);
            Log.e(TAG, "ENCODER: " + ENCODER);
        }
        if (BITRATE != null) {
            mExtraDataHashMap.put("bit", BITRATE);
            Log.e(TAG, "BITRATE: " + BITRATE);
        }
        if (RESOLUTION != null) {
            mExtraDataHashMap.put("res", RESOLUTION);
            Log.e(TAG, "RESOLUTION: " + RESOLUTION);
        }
        if (REF_RESOLUTION != null) {
            mExtraDataHashMap.put("ref_res", REF_RESOLUTION);
            Log.e(TAG, "REF_RESOLUTION: " + REF_RESOLUTION);
        }
        if (KEYFRAME != null) {
            mExtraDataHashMap.put("key", KEYFRAME);
            Log.e(TAG, "KEYFRAME: " + KEYFRAME);
        }
        if (FPS != null) {
            mExtraDataHashMap.put("fps", FPS);
            Log.e(TAG, "FPS: " + FPS);
        }
        if (REF_FPS != null) {
            mExtraDataHashMap.put("ref_fps", FPS);
            Log.e(TAG, "REF_FPS: " + REF_FPS);
        }
        if (WRITE_FILE != null) {
            mExtraDataHashMap.put("write", WRITE_FILE);
            Log.e(TAG, "WRITE_FILE: " + WRITE_FILE);
        }
        if (RUN != null) {
            mExtraDataHashMap.put("run", RUN);
            Log.e(TAG, "RUN: " + RUN);
        }
        if (VIDEO_TIMEOUT != null) {
            mExtraDataHashMap.put("video_timeout", VIDEO_TIMEOUT);
            Log.e(TAG, "VIDEO_TIMEOUT: " + VIDEO_TIMEOUT);
        }
        if (SKIPFRAMES != null) {
            mExtraDataHashMap.put("skip_frames", SKIPFRAMES);
            Log.e(TAG, "SKIP FRAMES: " + SKIPFRAMES);
        }
        if (DYNAMIC != null) {
            mExtraDataHashMap.put("dyn", DYNAMIC);
            Log.e(TAG, "DYNAMIC data: " + DYNAMIC);
        }
        if (LTR_COUNT != null) {
            mExtraDataHashMap.put("ltrc", LTR_COUNT);
            Log.e(TAG, "Ltr count: " + LTR_COUNT);
        }
        if (HIER_STRUCT_LAYER_COUNT != null) {
            mExtraDataHashMap.put("hierl", HIER_STRUCT_LAYER_COUNT);
            Log.e(TAG, "Hier p struct layer count: " + HIER_STRUCT_LAYER_COUNT);
        }
        if (FILE != null) {
            if (!FILE.contains("/")) {
                Log.e("Path does not contain \"/\"", FILE);
                System.exit(0);
            }
            mExtraDataHashMap.put("file", FILE);
            Log.e(TAG, "FILE: " + FILE);
        }

        if (LIST_CODECS != null) {
            mExtraDataHashMap.put("list_codecs", LIST_CODECS);
            Log.e(TAG, "LIST_CODECS: " + LIST_CODECS);
        }
    }

    @Before
    public void startMainActivityFromHomeScreen() {
        if (TEST_TIMEOUT != null) {
            UI_TIMEOUT = Integer.parseInt(TEST_TIMEOUT) * 60 * 1000;
        }
        Log.e(TAG, "UI_TIMEOUT: " + UI_TIMEOUT);

        // Initialize UiDevice instance
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        // Start from the home screen
        mDevice.pressHome();

        // Wait for launcher
        final String launcherPackage = mDevice.getCurrentPackageName();
        assertThat(launcherPackage, notNullValue());
        mDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)),
                LAUNCH_TIMEOUT);

        // Launch the app
        Context context = InstrumentationRegistry.getContext();
        final Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(TARGET_PACKAGE);

        // Add extra data to intent
        Log.d(TAG, "Collect extra data");
        collectExtraData();
        intent.putExtra("map", mExtraDataHashMap);

        // Clear out any previous instances
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);

        // Wait for the app to appear
        mDevice.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)),
                LAUNCH_TIMEOUT);
    }

    @Test
    public void automateValidation() throws Exception {
        Thread.sleep(LAUNCH_TIMEOUT);
        Log.d(TAG, "Check search criteria: " + TARGET_PACKAGE);
        SearchCondition<Boolean> isGone =
                Until.gone(By.res(TARGET_PACKAGE, "tv_testrun"));
        Log.e(TAG, "Wait for : " + UI_TIMEOUT + " ms (" + (UI_TIMEOUT / (1000 * 60.0)) + " min)");
        mDevice.wait(isGone, UI_TIMEOUT);
        Log.e(TAG, "Done waiting for : " + UI_TIMEOUT + " test: " + isGone);
        Thread.sleep(LAUNCH_TIMEOUT);
    }
}
