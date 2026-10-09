package com.facebook.encapp.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link NalScanner}. Uses hand-crafted Annex-B fixtures
 * so we can assert the exact (nal_type, offset, data) triples the scanner
 * produces without needing an on-device MediaCodec.
 *
 * <p>These fixtures mirror the ones in
 * scripts/tests/unit/verify/parsers_test.py so the Python and Java parsers
 * are known to agree on the same reference bytes.
 */
public class NalScannerTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // ================== AVC ==================

    @Test
    public void avc_nullInputReturnsEmpty() {
        List<NalScanner.Nal> nals = NalScanner.scanAvc(null);
        assertNotNull(nals);
        assertTrue(nals.isEmpty());
    }

    @Test
    public void avc_bytesWithoutStartCodeReturnEmpty() {
        List<NalScanner.Nal> nals = NalScanner.scanAvc(hex("DEADBEEFCAFE1234"));
        assertTrue(nals.isEmpty());
    }

    @Test
    public void avc_singleSpsWith3ByteStartCode() {
        // Real x264 Baseline @ 3.0 SPS for 176x144.
        byte[] sps = hex("6742E01E898B605D4040405D4D40");
        byte[] stream = concat(hex("000001"), sps);
        List<NalScanner.Nal> nals = NalScanner.scanAvc(stream);
        assertEquals(1, nals.size());
        NalScanner.Nal n = nals.get(0);
        assertEquals("nal_type = SPS (7)", 7, n.nalType);
        assertEquals("offset points to first byte after start code", 3, n.offset);
        assertArrayEquals(sps, n.data);
    }

    @Test
    public void avc_singleSpsWith4ByteStartCode() {
        byte[] sps = hex("6742E01E898B605D4040405D4D40");
        byte[] stream = concat(hex("00000001"), sps);
        List<NalScanner.Nal> nals = NalScanner.scanAvc(stream);
        assertEquals(1, nals.size());
        NalScanner.Nal n = nals.get(0);
        assertEquals(7, n.nalType);
        assertEquals(4, n.offset);
        assertArrayEquals(sps, n.data);
    }

    @Test
    public void avc_multipleNalsSpsPlusPpsPlusSlice() {
        byte[] sps = hex("6742E01E898B605D4040405D4D40");
        byte[] pps = hex("68CE3C80");
        byte[] slice = hex("6588840013CDABCD");   // synthetic IDR-like payload, nal_type=5
        byte[] stream = concat(
            hex("00000001"), sps,
            hex("00000001"), pps,
            hex("00000001"), slice
        );
        List<NalScanner.Nal> nals = NalScanner.scanAvc(stream);
        assertEquals(3, nals.size());
        assertEquals(7, nals.get(0).nalType);
        assertEquals(8, nals.get(1).nalType);
        assertEquals(5, nals.get(2).nalType);
        assertArrayEquals(sps, nals.get(0).data);
        assertArrayEquals(pps, nals.get(1).data);
        assertArrayEquals(slice, nals.get(2).data);
    }

    @Test
    public void avc_mixed3ByteAnd4ByteStartCodes() {
        byte[] sps = hex("6742E01E898B605D4040405D4D40");
        byte[] pps = hex("68CE3C80");
        // First NAL: 3-byte start; second NAL: 4-byte start.
        byte[] stream = concat(
            hex("000001"), sps,
            hex("00000001"), pps
        );
        List<NalScanner.Nal> nals = NalScanner.scanAvc(stream);
        assertEquals(2, nals.size());
        assertEquals(7, nals.get(0).nalType);
        assertEquals(8, nals.get(1).nalType);
    }

    @Test
    public void avc_emulationPreventionBytesPassedThrough() {
        // Scanner captures raw bytes; RBSP-unescape happens downstream.
        byte[] nalWith00_00_03 = hex("6700000342");
        byte[] stream = concat(hex("00000001"), nalWith00_00_03);
        List<NalScanner.Nal> nals = NalScanner.scanAvc(stream);
        assertEquals(1, nals.size());
        assertArrayEquals(nalWith00_00_03, nals.get(0).data);
    }

    // ================== HEVC ==================

    @Test
    public void hevc_singleSpsExtractsNalType33() {
        // Real x265 Main @ Level 3.0 SPS (from parsers_test.py).
        byte[] sps = hex("42010101600000009000000000005aa00a080f165959a4932bc05a02000000020000003c10");
        byte[] stream = concat(hex("00000001"), sps);
        List<NalScanner.Nal> nals = NalScanner.scanHevc(stream);
        assertEquals(1, nals.size());
        NalScanner.Nal n = nals.get(0);
        assertEquals("nal_type = SPS (33)", 33, n.nalType);
        assertArrayEquals(sps, n.data);
    }

    @Test
    public void hevc_vpsSpsPpsSequence() {
        // Header first byte encodes nal_type in bits 1..6:
        // 0x40 = 0100 0000 → (0x40 >> 1) & 0x3F = 32 (VPS)
        // 0x42 = 0100 0010 → (0x42 >> 1) & 0x3F = 33 (SPS)
        // 0x44 = 0100 0100 → (0x44 >> 1) & 0x3F = 34 (PPS)
        byte[] vps = hex("40010CDEADBEEF");
        byte[] sps = hex("4201CAFEBABE");
        byte[] pps = hex("4401CC1234");
        byte[] stream = concat(
            hex("00000001"), vps,
            hex("00000001"), sps,
            hex("00000001"), pps
        );
        List<NalScanner.Nal> nals = NalScanner.scanHevc(stream);
        assertEquals(3, nals.size());
        assertEquals(32, nals.get(0).nalType);
        assertEquals(33, nals.get(1).nalType);
        assertEquals(34, nals.get(2).nalType);
    }

    @Test
    public void hevc_nullInputReturnsEmpty() {
        List<NalScanner.Nal> nals = NalScanner.scanHevc(null);
        assertNotNull(nals);
        assertTrue(nals.isEmpty());
    }

    // ================== Utility ==================

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
}
