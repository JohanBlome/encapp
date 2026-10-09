package com.facebook.encapp.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an Annex-B byte buffer into NAL units.
 *
 * Extracted verbatim from the AVC/HEVC scan loops inside Muxer.java
 * (writeAvccFromAnnexB / writeHvccFromAnnexB). The scan algorithm and
 * bounds checks are preserved unchanged so downstream avcC/hvcC bytes
 * remain byte-identical.
 *
 * <p>Callers:
 * <ul>
 *   <li>Muxer.java — filters by nal_type to build SPS/PPS/VPS arrays for
 *       avcC/hvcC extradata. Existing behavior.</li>
 *   <li>NalLogWriter (Tier-2a) — records every NAL from every output frame
 *       into a per-session sidecar for Python-side capability verifiers.</li>
 * </ul>
 */
public final class NalScanner {

    private NalScanner() {
        // utility class
    }

    /**
     * A single NAL unit inside an Annex-B buffer.
     * <p>
     * offset: byte offset of the NAL body (first byte AFTER the start code)
     *         within the input buffer.
     * data:   raw NAL body bytes (post-start-code, pre-next-start-code),
     *         copied out for caller ownership.
     */
    public static final class Nal {
        public final int nalType;
        public final int offset;
        public final byte[] data;

        public Nal(int nalType, int offset, byte[] data) {
            this.nalType = nalType;
            this.offset = offset;
            this.data = data;
        }

        public int size() {
            return data.length;
        }
    }

    /**
     * Scan an Annex-B buffer as an H.264/AVC stream. nal_type is the low 5
     * bits of the first NAL byte (H.264 §7.3.1).
     */
    public static List<Nal> scanAvc(byte[] annexB) {
        List<Nal> out = new ArrayList<>();
        if (annexB == null) {
            return out;
        }

        int offset = 0;
        while (offset < annexB.length) {
            int startCodeLen = 0;
            if (offset + 3 < annexB.length &&
                annexB[offset] == 0 && annexB[offset + 1] == 0) {
                if (annexB[offset + 2] == 1) {
                    startCodeLen = 3;
                } else if (offset + 4 < annexB.length &&
                          annexB[offset + 2] == 0 && annexB[offset + 3] == 1) {
                    startCodeLen = 4;
                }
            }

            if (startCodeLen == 0) {
                offset++;
                continue;
            }

            int nalStart = offset + startCodeLen;
            if (nalStart >= annexB.length) {
                break;
            }

            int nalType = annexB[nalStart] & 0x1F;

            int nalEnd = annexB.length;
            for (int i = nalStart + 1; i + 2 < annexB.length; i++) {
                if (annexB[i] == 0 && annexB[i + 1] == 0 &&
                    (annexB[i + 2] == 1 ||
                     (i + 3 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            byte[] nalData = new byte[nalEnd - nalStart];
            System.arraycopy(annexB, nalStart, nalData, 0, nalData.length);
            out.add(new Nal(nalType, nalStart, nalData));

            offset = nalEnd;
        }
        return out;
    }

    /**
     * Scan an Annex-B buffer as an H.265/HEVC stream. nal_type is bits 1..6
     * of the first NAL byte (H.265 §7.3.1.2).
     */
    public static List<Nal> scanHevc(byte[] annexB) {
        List<Nal> out = new ArrayList<>();
        if (annexB == null) {
            return out;
        }

        int offset = 0;
        while (offset < annexB.length) {
            int startCodeLen = 0;
            if (offset + 3 < annexB.length &&
                annexB[offset] == 0 && annexB[offset + 1] == 0) {
                if (annexB[offset + 2] == 1) {
                    startCodeLen = 3;
                } else if (offset + 4 < annexB.length &&
                          annexB[offset + 2] == 0 && annexB[offset + 3] == 1) {
                    startCodeLen = 4;
                }
            }

            if (startCodeLen == 0) {
                offset++;
                continue;
            }

            int nalStart = offset + startCodeLen;
            if (nalStart >= annexB.length) {
                break;
            }

            int nalHeader = annexB[nalStart] & 0xFF;
            int nalType = (nalHeader >> 1) & 0x3F;

            int nalEnd = annexB.length;
            for (int i = nalStart + 1; i + 2 < annexB.length; i++) {
                if (annexB[i] == 0 && annexB[i + 1] == 0 &&
                    (annexB[i + 2] == 1 ||
                     (i + 3 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            byte[] nalData = new byte[nalEnd - nalStart];
            System.arraycopy(annexB, nalStart, nalData, 0, nalData.length);
            out.add(new Nal(nalType, nalStart, nalData));

            offset = nalEnd;
        }
        return out;
    }
}
