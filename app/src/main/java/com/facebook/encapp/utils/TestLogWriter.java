package com.facebook.encapp.utils;

import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Per-test plain-text log file: {@code <workdir>/<test_id>.log}.
 *
 * One line per event; each line is fsync'd before the next, so a
 * process kill leaves a parseable partial file (same property as
 * {@link SessionManifest}). Thread-safe via instance-monitor.
 *
 * Lifecycle:
 *   open in MainActivity's per-test thread BEFORE the encoder starts;
 *   write a handful of high-value events
 *     ('test_start', 'encoder_init', 'encoder_done', 'test_end', etc.);
 *   close in the thread's finally; emit a {@code SessionManifest}
 *   artifact event with {@code kind:"log"} so the CLI pulls it
 *   alongside the encoded video + stats JSON.
 *
 * Why not just lean on logcat? Logcat is a ring buffer (typically
 * 64-256 KB per buffer). Long tests overrun it; another component's
 * spam can evict the lines you need. A per-test file is durable, sized
 * to the test, and survives reboot.
 *
 * What this is NOT: it does NOT intercept {@link Log#d}/{@link Log#w}
 * calls globally. The CLI also pulls a session-scoped logcat slice
 * (see Phase 8.2) for that.
 */
public class TestLogWriter implements Closeable {
    private static final String TAG = "encapp.testlog";

    public static String filename(String testId) {
        return testId + ".log";
    }

    private final String mTestId;
    private final File mFile;
    private final SimpleDateFormat mFmt;
    private FileOutputStream mFos;   // null after close()

    public TestLogWriter(String testId, String workdir) throws IOException {
        if (testId == null || testId.isEmpty()) {
            throw new IllegalArgumentException("testId required");
        }
        mTestId = testId;
        mFile = new File(workdir, filename(testId));
        mFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        mFos = new FileOutputStream(mFile, /*append=*/ true);
    }

    public String getPath() {
        return mFile.getAbsolutePath();
    }

    public long size() {
        return mFile.exists() ? mFile.length() : 0L;
    }

    /**
     * Log one event. Format: {@code <iso8601> <level> <event> <msg>}.
     * level: "INFO" | "WARN" | "ERROR" (recommended; not enforced).
     */
    public synchronized void log(String level, String event, String msg) {
        if (mFos == null) {
            Log.w(TAG, "log() after close() for " + mFile);
            return;
        }
        String line = mFmt.format(new Date()) + " " + level
                + " [" + event + "] " + (msg == null ? "" : msg) + "\n";
        try {
            mFos.write(line.getBytes(StandardCharsets.UTF_8));
            mFos.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "log() failed for " + mFile, e);
        }
    }

    public void info(String event, String msg)  { log("INFO",  event, msg); }
    public void warn(String event, String msg)  { log("WARN",  event, msg); }
    public void error(String event, String msg) { log("ERROR", event, msg); }

    @Override
    public synchronized void close() {
        if (mFos == null) return;
        try {
            mFos.close();
        } catch (IOException e) {
            Log.w(TAG, "close() failed for " + mFile, e);
        }
        mFos = null;
    }
}
