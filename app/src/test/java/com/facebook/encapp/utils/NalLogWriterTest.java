package com.facebook.encapp.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

/**
 * JVM unit tests for {@link NalLogWriter}.
 *
 * <p>Uses android.util.Log — not available in the pure-JVM unit test
 * classpath — so this test only exercises code paths that go through
 * {@code recordFrame} + {@code close}, both of which reach {@code Log.w} /
 * {@code Log.e} only in error paths. Happy-path calls avoid Log entirely
 * so this test runs under {@code ./gradlew :app:test} without needing an
 * Android runtime shim.
 */
public class NalLogWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] out = new byte[total];
        int p = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }

    private JSONObject readSidecar(File f) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(f.toPath())));
    }

    @Test
    public void avc_singleFrameSpsPlusIdr() throws Exception {
        File out = tmp.newFile("session1.nal.json");
        assertTrue(out.delete());

        NalLogWriter w = new NalLogWriter("session1", out.getAbsolutePath());
        w.setCodecMime("video/avc");

        // Frame 0: SPS + PPS + IDR (typical first frame)
        byte[] sps = hex("6742E01E898B605D4040405D4D40");
        byte[] pps = hex("68CE3C80");
        byte[] idr = hex("6588840013CDABCD");
        byte[] frame0 = concat(
            hex("00000001"), sps,
            hex("00000001"), pps,
            hex("00000001"), idr
        );
        w.recordFrame(132L, 1 /* KEY_FRAME */, frame0);
        w.close();

        JSONObject root = readSidecar(out);
        assertEquals("session1", root.getString("session_id"));
        assertEquals("video/avc", root.getString("codec_mime"));
        assertEquals(1, root.getInt("nal_header_size"));

        JSONArray frames = root.getJSONArray("frames");
        assertEquals(1, frames.length());
        JSONObject f0 = frames.getJSONObject(0);
        assertEquals(0, f0.getInt("frame_index"));
        assertEquals(132, f0.getLong("pts_us"));
        assertEquals(1, f0.getInt("flags"));
        assertEquals(frame0.length, f0.getInt("total_size"));

        JSONArray nals = f0.getJSONArray("nals");
        assertEquals(3, nals.length());
        assertEquals("SPS type", 7, nals.getJSONObject(0).getInt("nal_type"));
        assertEquals("PPS type", 8, nals.getJSONObject(1).getInt("nal_type"));
        assertEquals("IDR type", 5, nals.getJSONObject(2).getInt("nal_type"));
        assertEquals("SPS size", sps.length, nals.getJSONObject(0).getInt("size"));
        assertEquals("PPS size", pps.length, nals.getJSONObject(1).getInt("size"));
        assertEquals("IDR size", idr.length, nals.getJSONObject(2).getInt("size"));
        // first_bytes_hex is a prefix of the NAL body (up to 64 bytes)
        assertEquals(bytesToHex(sps),
                     nals.getJSONObject(0).getString("first_bytes_hex"));
        assertEquals(bytesToHex(idr),
                     nals.getJSONObject(2).getString("first_bytes_hex"));
    }

    @Test
    public void avc_multipleFramesMonotonicIndex() throws Exception {
        File out = tmp.newFile("session2.nal.json");
        assertTrue(out.delete());
        NalLogWriter w = new NalLogWriter("session2", out.getAbsolutePath());
        w.setCodecMime("video/avc");

        byte[] non_idr = concat(hex("00000001"), hex("419A0E14FA"));  // nal_type=1
        for (int i = 0; i < 5; i++) {
            w.recordFrame(i * 33333L, 0, non_idr);
        }
        w.close();

        JSONObject root = readSidecar(out);
        JSONArray frames = root.getJSONArray("frames");
        assertEquals(5, frames.length());
        for (int i = 0; i < 5; i++) {
            JSONObject f = frames.getJSONObject(i);
            assertEquals("frame index sequence", i, f.getInt("frame_index"));
            assertEquals("pts computed as i*33333", i * 33333L, f.getLong("pts_us"));
            assertEquals("nal_type=1 (non-IDR slice)",
                         1, f.getJSONArray("nals").getJSONObject(0).getInt("nal_type"));
        }
    }

    @Test
    public void hevc_setCodecMimeSelectsHevcScanner() throws Exception {
        File out = tmp.newFile("session3.nal.json");
        assertTrue(out.delete());
        NalLogWriter w = new NalLogWriter("session3", out.getAbsolutePath());
        w.setCodecMime("video/hevc");

        // HEVC IDR_W_RADL slice has nal_type=19 → first byte high bits = 19<<1 = 0x26 → 0x26...
        byte[] hevc_idr = concat(hex("00000001"), hex("26010A"));
        w.recordFrame(0L, 1, hevc_idr);
        w.close();

        JSONObject root = readSidecar(out);
        assertEquals(2, root.getInt("nal_header_size"));
        JSONObject nal = root.getJSONArray("frames")
                              .getJSONObject(0)
                              .getJSONArray("nals")
                              .getJSONObject(0);
        assertEquals(19, nal.getInt("nal_type"));
    }

    @Test
    public void firstBytesHexClampedTo64Bytes() throws Exception {
        File out = tmp.newFile("session4.nal.json");
        assertTrue(out.delete());
        NalLogWriter w = new NalLogWriter("session4", out.getAbsolutePath());
        w.setCodecMime("video/avc");

        // 100-byte NAL body — first_bytes_hex should be exactly 128 chars.
        StringBuilder longNal = new StringBuilder("41");  // nal_type=1 header
        for (int i = 0; i < 99; i++) {
            longNal.append("cd");
        }
        byte[] frame = concat(hex("00000001"), hex(longNal.toString()));
        w.recordFrame(0L, 0, frame);
        w.close();

        JSONObject nal = readSidecar(out).getJSONArray("frames")
                                          .getJSONObject(0)
                                          .getJSONArray("nals")
                                          .getJSONObject(0);
        assertEquals(100, nal.getInt("size"));
        assertEquals("first_bytes_hex clamped to 64 bytes = 128 hex chars",
                     128, nal.getString("first_bytes_hex").length());
    }

    @Test
    public void closeIsIdempotent() throws Exception {
        File out = tmp.newFile("session5.nal.json");
        assertTrue(out.delete());
        NalLogWriter w = new NalLogWriter("session5", out.getAbsolutePath());
        w.setCodecMime("video/avc");
        w.recordFrame(0L, 0, concat(hex("00000001"), hex("419A0E")));
        w.close();
        long len1 = out.length();
        w.close();  // second call should overwrite with same content
        assertEquals(len1, out.length());
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
}
