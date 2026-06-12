package com.facebook.encapp.utils;

import android.content.Context;
import android.util.Log;
import android.os.Bundle;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

public class CliSettings {
    private final static String TAG = "encapp.clisettings";

    public static final String LIST_CODECS = "list_codecs";
    public static final String TEST_CONFIG = "test";
    public static final String TEST_UI_HOLD_TIME_SEC = "ui_hold_sec";
    public static final String OLD_AUTH_METHOD = "old_auth";
    public static final String WORKDIR = "workdir";
    // Either /sdcard/ or /data/data/com.facebook.encapp
    public static final String CHECK_WORKDIR = "check_workdir";
    public static final String ENABLE_TRACING = "enable_tracing";
    // CLI-generated session identifier. When set, the app writes an
    // append-only JSONL manifest at <workdir>/<session_id>.session.jsonl
    // declaring every test boundary and output artifact. The CLI uses
    // this manifest as the success oracle. If absent, the app runs in
    // legacy mode (no manifest written).
    public static final String SESSION_ID = "session_id";

    private static String mWorkDir = "/sdcard/";
    private static boolean mEnableTracing = false;

    /**
     * Try to actually write a file to the given path.
     * Files.isWritable() is unreliable on Android — it checks POSIX
     * permissions but not the Android permission model
     * (MANAGE_EXTERNAL_STORAGE). This method does a real write+delete
     * to confirm the path is usable.
     */
    private static boolean isActuallyWritable(String path) {
        File probe = new File(path, "_encapp_probe.tmp");
        try {
            FileOutputStream fos = new FileOutputStream(probe);
            fos.write(42);
            fos.close();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            probe.delete();
        }
    }

    public static void setWorkDir(Context context, Bundle mExtraData) {
        if (mExtraData != null && mExtraData.containsKey(CliSettings.WORKDIR)) {
            // 1. if user requested something, that is it
            mWorkDir = mExtraData.getString(CliSettings.WORKDIR);
        } else if (isActuallyWritable(Environment.getExternalStorageDirectory().getPath())) {
            // 2. use Environment.getExternalStorageDirectory().getPath() if writable
            mWorkDir = Environment.getExternalStorageDirectory().getPath();
        } else if (isActuallyWritable(context.getFilesDir().getPath())) {
            // 3. use Context.getFilesDir() if writable
            mWorkDir = context.getFilesDir().getPath();
        } else {
            // 4. no valid path to write: use the default
            ;
        }
        Log.d(TAG, "workdir: " + mWorkDir);
    }

    public static String getWorkDir() {
        return mWorkDir;
    }
    
    public static void setEnableTracing(boolean enable) {
        mEnableTracing = enable;
        Log.d(TAG, "Performance tracing: " + (enable ? "enabled" : "disabled"));
    }
    
    public static boolean isTracingEnabled() {
        return mEnableTracing;
    }
}
