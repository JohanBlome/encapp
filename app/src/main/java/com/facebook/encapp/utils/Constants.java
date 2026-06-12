package com.facebook.encapp.utils;

import com.facebook.encapp.BuildConfig;

/**
 * Central log-tag + debug-gate constants for the encapp app.
 *
 * Today every class declares its own {@code String TAG = "encapp.<subtag>"}.
 * That works (grep with {@code adb logcat -s "encapp*"}) but makes the
 * subtag space unmanaged and per-class debug gating impossible.
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
 * The 37 existing files that declare their own TAG are unchanged — a
 * mechanical sweep is queued as a follow-up task. New code should use
 * this class directly; old code can migrate incrementally.
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
