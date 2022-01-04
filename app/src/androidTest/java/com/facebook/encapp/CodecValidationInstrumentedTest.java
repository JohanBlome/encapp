package com.facebook.encapp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.SearchCondition;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertThat;

/**
 * Instrumentation test, which will execute on an Android device.
 * <p>
 * Run instrumentation test:
 * adb shell am instrument \
 *   -w -e class com.facebook.encapp.CodecValidationInstrumentedTest \
 *   com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner
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
@SdkSuppress(minSdkVersion = 25)
public class CodecValidationInstrumentedTest {
    private static final String TAG = "instrumentation";

    private static final int LAUNCH_TIMEOUT = 0;

    private static final String TARGET_PACKAGE =
            InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();


    private static long UI_TIMEOUT = 60 * 60 * 1000; //60 minutes in ms

    private UiDevice mDevice;
    private MainActivity mMainActivity;
    private HashMap<String, String> mExtraDataHashMap;



    @Before
    public void startMainActivityFromHomeScreen() {
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
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(TARGET_PACKAGE);

        Bundle bundle = InstrumentationRegistry.getArguments();
        intent.putExtras(bundle);
        ActivityTestRule<MainActivity> mainActivityTestRule =
                new ActivityTestRule<>(MainActivity.class);
        // Clear out any previous instances
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mMainActivity = mainActivityTestRule.launchActivity(intent);

        // Wait for the app to appear
        mDevice.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)),
                LAUNCH_TIMEOUT);        
    }

    @Test
    public void automateValidation() throws Exception {
        long startTime = System.nanoTime();
        Log.d(TAG, "Check search criteria: " + TARGET_PACKAGE);
        SearchCondition<Boolean> isGone =
                Until.gone(By.res(TARGET_PACKAGE, "tv_testrun"));
        Log.e(TAG, "Wait at most for : " + UI_TIMEOUT + " ms (" + (UI_TIMEOUT / (1000 * 60.0)) + " min)");
        mDevice.wait(isGone, UI_TIMEOUT);
        Log.e(TAG, "Done waiting: " + (System.nanoTime() - startTime)/1000000000 +" secs (maximum: " + UI_TIMEOUT + ") test: " + isGone);
    }
}
