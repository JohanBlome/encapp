package com.facebook.encapp.utils;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Append-only JSONL session manifest written by the app, read by the CLI.
 *
 * One line per event. Each line is fsync'd before the next write, so a
 * process kill leaves a parseable partial manifest.
 *
 * Filename: {@code <workdir>/<sessionId>.session.jsonl}
 *
 * Thread-safe: writes are guarded by an instance monitor.
 *
 * Failure mode: I/O errors during a write are logged via {@link Log#e} but
 * do not throw. Losing one event line is preferable to killing a
 * running test.
 */
public class SessionManifest implements Closeable {
    private static final String TAG = "encapp.manifest";
    public static final int SCHEMA_VERSION = 1;

    public static String filename(String sessionId) {
        return sessionId + ".session.jsonl";
    }

    private final String mSessionId;
    private final File mFile;
    private FileOutputStream mFos;  // null after close()

    public SessionManifest(String sessionId, String workdir) throws IOException {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId required");
        }
        mSessionId = sessionId;
        mFile = new File(workdir, filename(sessionId));
        mFos = new FileOutputStream(mFile, /*append=*/ true);
    }

    public String getPath() {
        return mFile.getAbsolutePath();
    }

    public String getSessionId() {
        return mSessionId;
    }

    public synchronized void sessionStart(String appVersion, String deviceWorkdir) {
        JSONObject o = baseLine("session_start");
        putSafe(o, "schema_version", SCHEMA_VERSION);
        putSafe(o, "app_version", appVersion);
        putSafe(o, "device_workdir", deviceWorkdir);
        writeLine(o);
    }

    public synchronized void testStart(String testId, int pid) {
        JSONObject o = baseLine("test_start");
        putSafe(o, "test_id", testId);
        putSafe(o, "pid", pid);
        writeLine(o);
    }

    /**
     * @param kind    short tag — "video", "stats", "log", "decoded", ...
     * @param path    basename written into the workdir (CLI prefixes with
     *                its mapped local workdir when pulling)
     * @param bytes   file size in bytes, or -1 if not yet known
     */
    public synchronized void artifact(String testId, String kind, String path, long bytes) {
        JSONObject o = baseLine("artifact");
        putSafe(o, "test_id", testId);
        putSafe(o, "kind", kind);
        putSafe(o, "path", path);
        putSafe(o, "bytes", bytes);
        writeLine(o);
    }

    /**
     * @param status        "ok" | "error" | "timeout" | "skipped"
     * @param errorOrNull   {code, message, stack} JSONObject, or null
     * @param extrasOrNull  additional flat fields to merge into the event
     *                      (e.g. frames_encoded, actual_codec)
     */
    public synchronized void testEnd(String testId, String status,
                                     JSONObject errorOrNull, JSONObject extrasOrNull) {
        JSONObject o = baseLine("test_end");
        putSafe(o, "test_id", testId);
        putSafe(o, "status", status);
        try {
            o.put("error", errorOrNull == null ? JSONObject.NULL : errorOrNull);
        } catch (JSONException ignored) {}
        if (extrasOrNull != null) {
            Iterator<String> it = extrasOrNull.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    o.put(k, extrasOrNull.get(k));
                } catch (JSONException ignored) {}
            }
        }
        writeLine(o);
    }

    /**
     * @param status  "complete" | "aborted" | "error"
     */
    public synchronized void sessionEnd(String status) {
        JSONObject o = baseLine("session_end");
        putSafe(o, "status", status);
        writeLine(o);
    }

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

    private JSONObject baseLine(String event) {
        JSONObject o = new JSONObject();
        putSafe(o, "ts", System.currentTimeMillis());
        putSafe(o, "event", event);
        putSafe(o, "session_id", mSessionId);
        return o;
    }

    private static void putSafe(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException ignored) {}
    }

    private void writeLine(JSONObject obj) {
        if (mFos == null) {
            Log.w(TAG, "writeLine after close() for " + mFile);
            return;
        }
        try {
            mFos.write((obj.toString() + "\n").getBytes("UTF-8"));
            mFos.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "writeLine failed for " + mFile, e);
        }
    }
}
