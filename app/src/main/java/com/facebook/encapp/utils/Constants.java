package com.facebook.encapp.utils;

import com.facebook.encapp.BuildConfig;

/**
 * Central log-tag + debug-gate constants for the encapp app.
 *
 * Recommended pattern for new code:
 * <pre>
 *   if (Constants.DEBUG) {
 *       Log.d(Constants.TAG, "[my_subtag] " + msg);
 *   }
 *   Log.w(Constants.TAG, "[my_subtag] " + msg);  // always-on
 *   Log.e(Constants.TAG, "[my_subtag] " + msg);  // always-on
 * </pre>
 *
 * Subtags live in the message body in square brackets so a single
 * {@code adb logcat -s encapp:V} shows the firehose while
 * {@code grep '\[buffer_encoder\]'} narrows to one subsystem.
 */
public final class Constants {
    private Constants() {}

    /**
     * Single logcat tag for the whole encapp app. Subtags go into the
     * message body in square brackets (e.g. "[buffer_encoder] frame
     * 42 encoded"). Reading the firehose:
     * <pre>
     *   adb logcat -s encapp:V
     * </pre>
     */
    public static final String TAG = "encapp";

    /**
     * Mirror of {@link BuildConfig#DEBUG} — true on debug builds,
     * false on release. Gate {@code Log.d} on this so release builds
     * don't pay the formatting + JNI cost for invisible debug lines.
     * {@code Log.w} / {@code Log.e} should stay always-on.
     */
    public static final boolean DEBUG = BuildConfig.DEBUG;
}
