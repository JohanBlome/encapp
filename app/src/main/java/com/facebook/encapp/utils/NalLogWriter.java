package com.facebook.encapp.utils;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Per-encode sidecar that records every NAL unit in every output frame.
 *
 * <p>Written to {@code <workdir>/<session_id>.nal.json} when the caller sets
 * {@code configure.nal_log = true}. Consumed by Python-side capability
 * verifiers (see verify/parsers/nal_sidecar.py) for per-slice QP, LTR
 * events, recovery_point SEI, temporal_id, and slice-type checks that the
 * bitstream alone (without decoding slice headers) can't answer.
 *
 * <p>Output shape:
 * <pre>{@code
 *   {
 *     "session_id":       "...",
 *     "codec_mime":       "video/avc",
 *     "nal_header_size":  1,             // 1 for AVC, 2 for HEVC
 *     "frames": [
 *       {
 *         "frame_index":  0,
 *         "pts_us":       132,
 *         "flags":        1,             // MediaCodec.BufferInfo.flags
 *         "total_size":   8945,
 *         "nals": [
 *           {"nal_type": 7,  "offset": 0,    "size": 24,   "first_bytes_hex": "..."},
 *           ...
 *         ]
 *       }, ...
 *     ]
 *   }
 * }</pre>
 *
 * <p>Bytes per NAL: {@code min(64, nal.size())} — enough to parse slice-
 * header fields (slice_type, slice_qp_delta, LTR flags) and SEI payload
 * types.
 */
public final class NalLogWriter {

    private static final String TAG = "encapp.nallog";
    private static final int FIRST_BYTES_LIMIT = 64;

    private final String mSessionId;
    private final String mOutputPath;
    private final JSONArray mFrames = new JSONArray();
    private final int mFrameCountLimit;

    private String mCodecMime = "";
    // AVC uses a 1-byte NAL header (H.264 §7.3.1); HEVC uses 2 bytes
    // (H.265 §7.3.1.2). Set from the mime when addTrack is called.
    private int mNalHeaderSize = 1;
    private boolean mIsHevc = false;
    private int mNextFrameIndex = 0;

    /** Sentinel to disable the safety cap. */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    /**
     * @param sessionId  identifier the caller uses in filenames
     * @param outputPath absolute path of the sidecar JSON to write on close
     * @param frameCountLimit  cap on frames recorded (defensive against
     *                         long encodes bloating the sidecar); use
     *                         {@link #NO_LIMIT} to disable.
     */
    public NalLogWriter(String sessionId, String outputPath, int frameCountLimit) {
        mSessionId = sessionId == null ? "" : sessionId;
        mOutputPath = outputPath;
        mFrameCountLimit = frameCountLimit;
    }

    public NalLogWriter(String sessionId, String outputPath) {
        this(sessionId, outputPath, NO_LIMIT);
    }

    /** Called from MuxerWrapper.addTrack once the MIME is known. */
    public void setCodecMime(String mime) {
        if (mime == null) {
            return;
        }
        mCodecMime = mime;
        String low = mime.toLowerCase();
        mIsHevc = low.contains("hevc") || low.contains("h265") || low.contains("video/x-h265");
        mNalHeaderSize = mIsHevc ? 2 : 1;
    }

    public String getOutputPath() {
        return mOutputPath;
    }

    public int getFrameCount() {
        return mFrames.length();
    }

    /**
     * Record one output frame. The frame data is scanned inline; nothing is
     * retained beyond the JSON metadata. Silently drops frames past the
     * configured limit (with one Log.w).
     */
    public synchronized void recordFrame(long ptsUs, int flags, byte[] frameData) {
        if (frameData == null) {
            return;
        }
        int idx = mNextFrameIndex++;
        if (idx >= mFrameCountLimit) {
            if (idx == mFrameCountLimit) {
                Log.w(TAG, "NAL log frame limit " + mFrameCountLimit
                        + " reached — later frames dropped");
            }
            return;
        }
        List<NalScanner.Nal> nals = mIsHevc
                ? NalScanner.scanHevc(frameData)
                : NalScanner.scanAvc(frameData);

        JSONArray nalsJson = new JSONArray();
        for (NalScanner.Nal n : nals) {
            JSONObject o = new JSONObject();
            try {
                o.put("nal_type", n.nalType);
                o.put("offset", n.offset);
                o.put("size", n.size());
                o.put("first_bytes_hex", firstBytesHex(n.data));
            } catch (JSONException e) {
                // JSONObject.put on int/long/String only throws for NaN.
                // Not possible here — swallow.
            }
            nalsJson.put(o);
        }

        JSONObject frame = new JSONObject();
        try {
            frame.put("frame_index", idx);
            frame.put("pts_us", ptsUs);
            frame.put("flags", flags);
            frame.put("total_size", frameData.length);
            frame.put("nals", nalsJson);
        } catch (JSONException e) {
            // unreachable
        }
        mFrames.put(frame);
    }

    /**
     * Flush the accumulated JSON to disk. Safe to call multiple times —
     * writes are idempotent per current state.
     */
    public synchronized void close() {
        JSONObject root = new JSONObject();
        try {
            root.put("session_id", mSessionId);
            root.put("codec_mime", mCodecMime);
            root.put("nal_header_size", mNalHeaderSize);
            root.put("frames", mFrames);
        } catch (JSONException e) {
            Log.e(TAG, "unexpected JSONException building NAL log", e);
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(mOutputPath);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(root.toString());
        } catch (IOException e) {
            Log.e(TAG, "failed to write NAL log to " + mOutputPath, e);
        }
    }

    private static String firstBytesHex(byte[] data) {
        int n = Math.min(FIRST_BYTES_LIMIT, data.length);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
