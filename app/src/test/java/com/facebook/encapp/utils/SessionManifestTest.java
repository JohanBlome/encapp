package com.facebook.encapp.utils;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionManifestTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static List<JSONObject> readLines(File f) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    out.add(new JSONObject(line));
                }
            }
        }
        return out;
    }

    @Test
    public void filenameIsSessionIdDotSessionJsonl() {
        assertEquals("S123.session.jsonl", SessionManifest.filename("S123"));
    }

    @Test
    public void writesHappyPathSequence() throws Exception {
        try (SessionManifest m = new SessionManifest("S1", tmp.getRoot().getAbsolutePath())) {
            m.sessionStart("1.31", "/sdcard");
            m.testStart("johnny_hevc_1Mbps_720p", 4321);
            m.artifact("johnny_hevc_1Mbps_720p", "video", "johnny_hevc_1Mbps_720p.mp4", 12345678L);
            m.artifact("johnny_hevc_1Mbps_720p", "stats", "johnny_hevc_1Mbps_720p.json", 4096L);
            m.testEnd("johnny_hevc_1Mbps_720p", "ok", null, null);
            m.sessionEnd("complete");
        }

        File f = new File(tmp.getRoot(), "S1.session.jsonl");
        assertTrue(f.exists());
        List<JSONObject> lines = readLines(f);
        assertEquals(6, lines.size());

        // Each line carries the common shape.
        for (JSONObject line : lines) {
            assertEquals("S1", line.getString("session_id"));
            assertTrue(line.has("ts"));
            assertTrue(line.has("event"));
        }

        assertEquals("session_start", lines.get(0).getString("event"));
        assertEquals("1.31",          lines.get(0).getString("app_version"));
        assertEquals(1,               lines.get(0).getInt("schema_version"));

        assertEquals("test_start",                  lines.get(1).getString("event"));
        assertEquals("johnny_hevc_1Mbps_720p",      lines.get(1).getString("test_id"));
        assertEquals(4321,                          lines.get(1).getInt("pid"));

        assertEquals("artifact",                    lines.get(2).getString("event"));
        assertEquals("video",                       lines.get(2).getString("kind"));
        assertEquals("johnny_hevc_1Mbps_720p.mp4",  lines.get(2).getString("path"));
        assertEquals(12345678L,                     lines.get(2).getLong("bytes"));

        assertEquals("test_end",                    lines.get(4).getString("event"));
        assertEquals("ok",                          lines.get(4).getString("status"));

        assertEquals("session_end",                 lines.get(5).getString("event"));
        assertEquals("complete",                    lines.get(5).getString("status"));
    }

    @Test
    public void writesErrorWithErrorBlockAndExtras() throws Exception {
        JSONObject err = new JSONObject()
                .put("code", "configure_failed")
                .put("message", "MediaCodec.configure threw")
                .put("stack", "");
        JSONObject extras = new JSONObject()
                .put("frames_encoded", 42)
                .put("actual_codec", "c2.qti.hevc.encoder");

        try (SessionManifest m = new SessionManifest("S2", tmp.getRoot().getAbsolutePath())) {
            m.testStart("t1", 9999);
            m.testEnd("t1", "error", err, extras);
        }

        List<JSONObject> lines = readLines(new File(tmp.getRoot(), "S2.session.jsonl"));
        assertEquals(2, lines.size());
        JSONObject end = lines.get(1);
        assertEquals("error", end.getString("status"));
        assertEquals("configure_failed", end.getJSONObject("error").getString("code"));
        assertEquals(42, end.getInt("frames_encoded"));
        assertEquals("c2.qti.hevc.encoder", end.getString("actual_codec"));
    }

    @Test
    public void multipleArtifactsPerTestSupported() throws Exception {
        // Exhaustive-test shape: one test_start, N artifacts, one test_end.
        try (SessionManifest m = new SessionManifest("S3", tmp.getRoot().getAbsolutePath())) {
            m.testStart("exhaust_001", 1234);
            for (int i = 0; i < 5; i++) {
                m.artifact("exhaust_001", "video", "exhaust_001_iter" + i + ".mp4", 1024L * (i + 1));
            }
            m.testEnd("exhaust_001", "ok", null, new JSONObject().put("iterations", 5));
        }

        List<JSONObject> lines = readLines(new File(tmp.getRoot(), "S3.session.jsonl"));
        assertEquals(7, lines.size());
        int artifactCount = 0;
        for (JSONObject l : lines) {
            if ("artifact".equals(l.getString("event"))) artifactCount++;
        }
        assertEquals(5, artifactCount);
    }

    @Test
    public void partialFileIsReadableAfterCloseSimulatingCrashAfterNLines() throws Exception {
        // Each writeLine fsyncs. If the process were killed after line N,
        // the file on disk would have exactly N parseable lines. We simulate
        // by closing after N writes and inspecting.
        try (SessionManifest m = new SessionManifest("S4", tmp.getRoot().getAbsolutePath())) {
            m.sessionStart("1.31", "/sdcard");
            m.testStart("t1", 1);
            m.artifact("t1", "video", "t1.mp4", 100);
            // simulated crash: close() without sessionEnd / testEnd
        }

        List<JSONObject> lines = readLines(new File(tmp.getRoot(), "S4.session.jsonl"));
        assertEquals(3, lines.size());
        assertEquals("session_start", lines.get(0).getString("event"));
        assertEquals("test_start",    lines.get(1).getString("event"));
        assertEquals("artifact",      lines.get(2).getString("event"));
        // No test_end / session_end → CLI side will classify t1 as CRASH.
    }

    @Test
    public void appendModeContinuesAcrossReopens() throws Exception {
        // Verifies the underlying open-append semantics: a second handle
        // to the same session_id keeps adding lines instead of truncating.
        // (Not the expected runtime flow, but a useful invariant.)
        String wd = tmp.getRoot().getAbsolutePath();
        try (SessionManifest m = new SessionManifest("S5", wd)) {
            m.sessionStart("1.31", "/sdcard");
        }
        try (SessionManifest m = new SessionManifest("S5", wd)) {
            m.sessionEnd("complete");
        }
        List<JSONObject> lines = readLines(new File(wd, "S5.session.jsonl"));
        assertEquals(2, lines.size());
        assertEquals("session_start", lines.get(0).getString("event"));
        assertEquals("session_end",   lines.get(1).getString("event"));
    }

    @Test(timeout = 5000)
    public void concurrentWritesProduceWellFormedLines() throws Exception {
        final int threads = 8;
        final int eventsPerThread = 50;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);

        try (final SessionManifest m = new SessionManifest("S6", tmp.getRoot().getAbsolutePath())) {
            for (int i = 0; i < threads; i++) {
                final String testId = "t" + i;
                new Thread(() -> {
                    try {
                        start.await();
                        m.testStart(testId, 1000 + (int) Thread.currentThread().getId());
                        for (int j = 0; j < eventsPerThread; j++) {
                            m.artifact(testId, "video", testId + "_" + j + ".mp4", j);
                        }
                        m.testEnd(testId, "ok", null, null);
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                }, "writer-" + i).start();
            }
            start.countDown();
            if (!done.await(4, TimeUnit.SECONDS)) {
                fail("writers did not finish in time");
            }
        }

        // Every line should be parseable JSON; total line count is deterministic.
        List<JSONObject> lines = readLines(new File(tmp.getRoot(), "S6.session.jsonl"));
        int expected = threads * (1 + eventsPerThread + 1);
        assertEquals(expected, lines.size());
        for (JSONObject l : lines) {
            assertNotNull(l.getString("event"));
            assertNotNull(l.getString("session_id"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptySessionId() throws Exception {
        new SessionManifest("", tmp.getRoot().getAbsolutePath());
    }
}
