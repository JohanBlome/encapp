package com.facebook.encapp.utils;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.facebook.encapp.proto.Runtime.FakeInputComplexityParameter;
import com.facebook.encapp.proto.PixFmt;

/**
 * FakeInputReader generates synthetic YUV frames with a vertical pattern
 * that scrolls horizontally to avoid completely static content.
 *
 * <p>Content complexity is controlled by {@link Complexity}:
 * <ul>
 *   <li>LOW  — plain scrolling stripes (default; matches original behavior).</li>
 *   <li>MID  — stripes + moderate per-pixel uniform luma noise (±14).</li>
 *   <li>HIGH — stripes + heavy per-pixel uniform luma noise (±17).</li>
 * </ul>
 *
 * <p>The amplitudes are probe operating points rather than a quality
 * calibration. They were selected to span visibly different encoder loads;
 * every device run reports the achieved rate and observed QP so saturation is
 * detectable instead of assumed away.
 *
 * <p>Higher complexity gives the encoder real entropy per frame so
 * rate-control (CBR/VBR) actually has to work — a flat stripe pattern
 * compresses to almost nothing regardless of the target bitrate, which
 * makes mode_cbr / bitrate_range verifiers pass trivially without
 * exercising the encoder.
 *
 * <p>Noise is temporally decorrelated through eight deterministic luma
 * phases. The same (resolution, pixel format, tier, frame number) emits
 * byte-identical content across processes and devices.
 */
public class FakeInputReader {
    private static final String TAG = "encapp.fakeinput";

    public enum Complexity {
        LOW, MID, HIGH;

        /** Base luma noise amplitude per tier. These values are operating
         * points, not a cross-codec quality calibration; the RC probe reports
         * the resulting achieved rate for every device. */
        int baseNoiseStddev() {
            switch (this) {
                case HIGH: return 17;
                case MID:  return 14;
                default:   return 0;
            }
        }

        /** Effective noise stddev for a given codec mime — the base value
         * scaled by a per-codec efficiency modifier. More efficient codecs
         * compress noise better, so they need proportionally MORE noise
         * to hit the equivalent RC-stress operating point.
         *
         * Modifier lives here (not exposed in the proto) so pbtxts stay
         * codec-agnostic — the same recipe run against AVC vs HEVC vs AV1
         * produces effective sigmas tuned for each. Callers pass the mime
         * type from Configure.mime.
         *
         * Modifiers are pre-tune estimates for codecs other than HEVC —
         * re-calibrate on-device via a fixed-QP VBR sweep before trusting. */
        int noiseStddev(String mimeType) {
            double m;
            if (mimeType == null) {
                m = 1.0;
            } else {
                switch (mimeType) {
                    case "video/hevc":  m = 1.00; break;  // calibrated
                    case "video/avc":   m = 0.85; break;  // estimate (AVC less efficient)
                    case "video/av1":   m = 1.15; break;  // estimate (AV1 more efficient)
                    case "video/vp9":   m = 1.05; break;  // estimate
                    case "video/mpeg4": m = 0.65; break;  // estimate
                    default:            m = 1.00; break;
                }
            }
            return (int) Math.round(baseNoiseStddev() * m);
        }

        /** Legacy 1-arg form (no per-codec adjustment) — kept for
         * callers that don't know the mime type. */
        int noiseStddev() {
            return noiseStddev(null);
        }
    }

    private PixFmt mPixFmt;
    private int mWidth;
    private int mHeight;
    private int mFrameCount;
    private boolean mClosed;

    /** A scheduled complexity point — carries both the tier name (for
     * logging) and its resolved noise sigma. The sigma may be an
     * override set by the caller (per-codec efficiency modifier) or the
     * tier's built-in default; either way, applying it is uniform. */
    static class SchedulePoint {
        final Complexity tier;
        final int sigma;
        SchedulePoint(Complexity tier, int sigma) {
            this.tier = tier;
            this.sigma = sigma;
        }
        @Override public String toString() {
            return tier + "(sigma=" + sigma + ")";
        }
    }

    // Per-framenum complexity schedule (sorted by framenum). Empty map =
    // default (LOW). Consumed via floorEntry(frameIndex) so any gaps
    // between scheduled points inherit the preceding tier.
    private final TreeMap<Long, SchedulePoint> mComplexitySchedule = new TreeMap<>();
    // Configure.mime — informs Complexity.noiseStddev(mime) so per-codec
    // efficiency modifier can be applied without exposing sigma in the
    // proto. Encoder subclasses set this via setMediaType() before
    // installing the schedule.
    private String mMediaType = null;
    // Legacy fake_input may apply per-codec noise scaling. Rate-control probes
    // disable it so every codec receives byte-identical source frames.
    private boolean mCodecAdaptiveNoise = true;
    private long mNoiseSeedBase;

    // Pattern parameters
    private static final int PATTERN_EXTRA_WIDTH = 64; // Extra width for scrolling
    // Independent deterministic luma fields. Eight phases keep consecutive
    // P-frames decorrelated while bounding the Java heap at production 4 MP.
    private static final int TEMPORAL_NOISE_PHASES = 8;
    private byte[] mYPlanePattern;
    private byte[] mUPlanePattern;
    private byte[] mVPlanePattern;
    private byte[] mUVPlanePattern;
    private byte[] mVUPlanePattern;

    // Cache of pre-generated noisy luma variants, keyed by the stable plane
    // salt, sigma, and temporal phase. Populated in openFile before realtime
    // feeding starts; reused by arraycopy thereafter.
    private final java.util.Map<String, byte[]> mNoisyCache =
            new java.util.HashMap<>();


    public FakeInputReader() {
        mClosed = true;
        mFrameCount = 0;
    }

    /** Set the encoder mime type so Complexity.noiseStddev(mime) can
     * apply the per-codec efficiency modifier. Call BEFORE
     * setComplexitySchedule so newly-installed schedule points resolve
     * their sigma against the correct modifier. */
    public void setMediaType(String mediaType) {
        mMediaType = mediaType;
    }

    /**
     * Control the legacy per-codec noise scaling. Characterization runs must
     * disable this: changing the source with the codec would confound codec
     * comparisons. Call before {@link #setComplexitySchedule}.
     */
    public void setCodecAdaptiveNoise(boolean enabled) {
        mCodecAdaptiveNoise = enabled;
    }

    /**
     * Install a framenum-keyed complexity schedule from the runtime
     * proto. Consumed via floorEntry(frameIndex) so a schedule of
     * [(0, LOW), (45, HIGH)] means "frames 0-44 are LOW, frames 45+ are
     * HIGH". Empty or missing schedule leaves the reader at LOW.
     *
     * <p>Call BEFORE openFile so the pattern generator can log the schedule.
     */
    public void setComplexitySchedule(
            java.util.List<FakeInputComplexityParameter> schedule) {
        mComplexitySchedule.clear();
        if (schedule == null) return;
        for (FakeInputComplexityParameter p : schedule) {
            String name = p.hasComplexity() ? p.getComplexity() : "";
            if (name == null || name.isEmpty()) continue;
            Complexity c;
            try {
                c = Complexity.valueOf(name.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Skipping unknown complexity token '" + name + "'");
                continue;
            }
            // Sigma = tier's base × per-codec modifier (via mMediaType).
            // Complexity tier is the single source of truth exposed in
            // the proto; per-codec adjustment happens Java-side.
            int sigma = mCodecAdaptiveNoise
                    ? c.noiseStddev(mMediaType) : c.baseNoiseStddev();
            mComplexitySchedule.put(
                    p.hasFramenum() ? p.getFramenum() : 0L,
                    new SchedulePoint(c, sigma));
        }
    }

    SchedulePoint effectivePoint(int frameIndex) {
        if (mComplexitySchedule.isEmpty()) {
            return new SchedulePoint(Complexity.LOW, 0);
        }
        Map.Entry<Long, SchedulePoint> entry =
                mComplexitySchedule.floorEntry((long) frameIndex);
        return entry != null
                ? entry.getValue() : new SchedulePoint(Complexity.LOW, 0);
    }

    /**
     * Initialize the fake input reader with resolution and pixel format.
     * Creates a vertical stripe pattern that's wider than the actual frame.
     */
    public boolean openFile(String name, PixFmt pixFmt, int width, int height) {
        Log.i(TAG, "FakeInputReader.openFile: name: " + name + " pix_fmt: "
                + pixFmt + " resolution: " + width + "x" + height
                + " complexity_schedule: "
                + (mComplexitySchedule.isEmpty() ? "[default=LOW]"
                        : mComplexitySchedule.toString()));
        mPixFmt = pixFmt;
        mWidth = width;
        mHeight = height;
        mFrameCount = 0;
        mClosed = false;
        // Deterministic seed base — same (w, h, pixFmt) always produces
        // byte-identical frames across runs. Per-frame noise mixes in the
        // effective complexity tier at that frame (see copyPlaneData),
        // keeping the same-input-same-bytes invariant.
        mNoiseSeedBase = (long) width * 31 + height * 977L
                + (pixFmt != null ? pixFmt.getNumber() * 65521L : 0L);

        // Create pattern buffers with extra width for scrolling
        int patternWidth = width + PATTERN_EXTRA_WIDTH;
        int yPlaneSize = patternWidth * height;
        int uvWidth = patternWidth / 2;
        int uvHeight = height / 2;
        int uvPlaneSize = uvWidth * uvHeight;

        mYPlanePattern = new byte[yPlaneSize];
        mUPlanePattern = new byte[uvPlaneSize];
        mVPlanePattern = new byte[uvPlaneSize];
        // Pre-interleaved chroma avoids a per-pixel Java loop on every NV12/NV21
        // frame. Each row is patternWidth bytes (two bytes per chroma sample).
        // Callers align the luma scroll offset down to an even byte so UV/VU
        // component order never flips on odd luma offsets.
        mUVPlanePattern = new byte[patternWidth * uvHeight];
        mVUPlanePattern = new byte[patternWidth * uvHeight];
        mNoisyCache.clear();  // patterns reallocated → drop stale noisy variants

        // Generate vertical stripes pattern for Y plane (16 pixel wide stripes)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < patternWidth; x++) {
                int stripeIndex = (x / 16) % 4;
                byte luminance = (byte)(64 + stripeIndex * 48); // Values from 64 to 208
                mYPlanePattern[y * patternWidth + x] = luminance;
            }
        }

        // Generate pattern for U plane (blue gradient)
        for (int y = 0; y < uvHeight; y++) {
            for (int x = 0; x < uvWidth; x++) {
                int stripeIndex = (x / 8) % 4;
                byte chromaU = (byte)(64 + stripeIndex * 32);
                mUPlanePattern[y * uvWidth + x] = chromaU;
            }
        }

        // Generate pattern for V plane (red gradient)
        for (int y = 0; y < uvHeight; y++) {
            for (int x = 0; x < uvWidth; x++) {
                int stripeIndex = (x / 8) % 4;
                byte chromaV = (byte)(128 + stripeIndex * 24);
                mVPlanePattern[y * uvWidth + x] = chromaV;
            }
        }

        // Build byte-interleaved chroma rows once. The source row stride is
        // patternWidth bytes. Horizontal scrolling uses 2 * floor(offset / 2)
        // bytes so every row still starts on the first component of UV or VU.
        for (int y = 0; y < uvHeight; y++) {
            for (int x = 0; x < uvWidth; x++) {
                int planar = y * uvWidth + x;
                int interleaved = y * patternWidth + 2 * x;
                mUVPlanePattern[interleaved] = mUPlanePattern[planar];
                mUVPlanePattern[interleaved + 1] = mVPlanePattern[planar];
                mVUPlanePattern[interleaved] = mVPlanePattern[planar];
                mVUPlanePattern[interleaved + 1] = mUPlanePattern[planar];
            }
        }

        // Pre-generate every scheduled luma field before realtime feeding
        // starts. Generating Random values on the input thread would turn
        // source complexity into feeder latency and invalidate RC timing.
        java.util.Set<Integer> scheduledSigmas = new java.util.HashSet<>();
        for (SchedulePoint point : mComplexitySchedule.values()) {
            if (point.sigma > 0) scheduledSigmas.add(point.sigma);
        }
        for (int sigma : scheduledSigmas) {
            for (int phase = 0; phase < TEMPORAL_NOISE_PHASES; phase++) {
                long phaseStartNs = System.nanoTime();
                noisyVariant(mYPlanePattern, sigma, phase);
                long elapsedMs = (System.nanoTime() - phaseStartNs) / 1_000_000L;
                Log.i(TAG, "Prewarmed fake input noise: sigma=" + sigma
                        + " phase=" + (phase + 1) + "/" + TEMPORAL_NOISE_PHASES
                        + " elapsed_ms=" + elapsedMs);
            }
        }

        Log.i(TAG, "Generated fake input pattern: " + patternWidth + "x" + height);
        return true;
    }

    public boolean isClosed() {
        synchronized (this) {
            return mClosed;
        }
    }

    public void closeFile() {
        synchronized (this) {
            Log.i(TAG, "Close fake input (generated " + mFrameCount + " frames)");
            mClosed = true;
        }
    }

    /**
     * Fill the ByteBuffer with synthetic YUV data.
     * The pattern scrolls horizontally based on frame count.
     */
    public int fillBuffer(ByteBuffer byteBuffer, int size) {
        synchronized (this) {
            if (isClosed()) {
                return 0;
            }
        }

        // Calculate horizontal offset for scrolling (wraps around).
        int offset = mFrameCount % PATTERN_EXTRA_WIDTH;

        int patternWidth = mWidth + PATTERN_EXTRA_WIDTH;
        int lumaSize = mWidth * mHeight;
        int chromaWidth = mWidth / 2;
        int chromaHeight = mHeight / 2;
        int chromaSize = chromaWidth * chromaHeight;

        try {
            if (byteBuffer.hasArray()) {
                byte[] bytes = byteBuffer.array();
                copyPlaneData(bytes, 0, mYPlanePattern, patternWidth, mWidth, mHeight, offset);

                if (mPixFmt.getNumber() == PixFmt.yuv420p_VALUE) {
                    // Planar: Y, U, V
                    copyPlaneData(bytes, lumaSize, mUPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                    copyPlaneData(bytes, lumaSize + chromaSize, mVPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                } else if (mPixFmt.getNumber() == PixFmt.yvu420p_VALUE) {
                    // Planar: Y, V, U
                    copyPlaneData(bytes, lumaSize, mVPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                    copyPlaneData(bytes, lumaSize + chromaSize, mUPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                } else if (mPixFmt.getNumber() == PixFmt.nv12_VALUE) {
                    // Semi-planar: Y, interleaved UV
                    copyInterleavedPlaneData(bytes, lumaSize, mUVPlanePattern,
                            patternWidth, mWidth, chromaHeight,
                            interleavedChromaOffset(offset));
                } else if (mPixFmt.getNumber() == PixFmt.nv21_VALUE) {
                    // Semi-planar: Y, interleaved VU
                    copyInterleavedPlaneData(bytes, lumaSize, mVUPlanePattern,
                            patternWidth, mWidth, chromaHeight,
                            interleavedChromaOffset(offset));
                }
            } else {
                // MediaCodec input buffers are normally direct. Write rows
                // straight into the codec buffer: allocating and copying a
                // full 4 MP temporary frame here makes realtime 30 fps
                // impossible and creates gigabytes of short-lived garbage.
                copyPlaneData(byteBuffer, 0, mYPlanePattern,
                        patternWidth, mWidth, mHeight, offset);

                if (mPixFmt.getNumber() == PixFmt.yuv420p_VALUE) {
                    copyPlaneData(byteBuffer, lumaSize, mUPlanePattern,
                            patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                    copyPlaneData(byteBuffer, lumaSize + chromaSize, mVPlanePattern,
                            patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                } else if (mPixFmt.getNumber() == PixFmt.yvu420p_VALUE) {
                    copyPlaneData(byteBuffer, lumaSize, mVPlanePattern,
                            patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                    copyPlaneData(byteBuffer, lumaSize + chromaSize, mUPlanePattern,
                            patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                } else if (mPixFmt.getNumber() == PixFmt.nv12_VALUE) {
                    copyInterleavedPlaneData(byteBuffer, lumaSize, mUVPlanePattern,
                            patternWidth, mWidth, chromaHeight,
                            interleavedChromaOffset(offset));
                } else if (mPixFmt.getNumber() == PixFmt.nv21_VALUE) {
                    copyInterleavedPlaneData(byteBuffer, lumaSize, mVUPlanePattern,
                            patternWidth, mWidth, chromaHeight,
                            interleavedChromaOffset(offset));
                }
            }
        } catch (BufferOverflowException | ReadOnlyBufferException e) {
            Log.e(TAG, "Buffer error: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }

        mFrameCount++;
        return size;
    }

    /**
     * Copy plane data with horizontal offset for scrolling effect.
     *
     * Complexity/noise is applied via a PRE-GENERATED noisy variant of the
     * base pattern (see noisyVariant): the per-pixel Random pass runs once
     * per (plane, sigma, temporal phase) before the encoder starts. The
     * realtime hot path is row-wise arraycopy. Consecutive non-LOW frames use
     * independent deterministic luma fields, so complexity stresses temporal
     * prediction rather than only the I-frame spatial cost.
     */
    private byte[] sourceForCurrentFrame(byte[] srcPattern) {
        int sigma = effectivePoint(mFrameCount).sigma;
        int phase = Math.floorMod(mFrameCount, TEMPORAL_NOISE_PHASES);
        // The RC stimulus adds entropy to luma only. Chroma stays shared across
        // phases so prewarming is complete and the realtime feeder never
        // allocates or runs Random for U/V planes.
        return (sigma > 0 && srcPattern == mYPlanePattern)
                ? noisyVariant(srcPattern, sigma, phase) : srcPattern;
    }

    private void copyPlaneData(byte[] dest, int destOffset, byte[] srcPattern,
                               int srcWidth, int width, int height, int offset) {
        byte[] src = sourceForCurrentFrame(srcPattern);
        for (int y = 0; y < height; y++) {
            int srcRowStart = y * srcWidth + offset;
            int destRowStart = destOffset + y * width;
            System.arraycopy(src, srcRowStart, dest, destRowStart, width);
        }
    }

    /**
     * Return (creating+caching on first use) a copy of `base` with
     * deterministic per-pixel uniform noise in [-sigma, +sigma] baked in.
     * Keyed by stable plane salt, sigma, and temporal phase. Deterministic:
     * same (w,h,pixfmt,sigma,phase) produces the same bytes across processes.
     */
    private byte[] noisyVariant(byte[] base, int sigma, int phase) {
        int planeSalt;
        if (base == mYPlanePattern) {
            planeSalt = 0x13579BDF;
        } else if (base == mUPlanePattern) {
            planeSalt = 0x2468ACE0;
        } else if (base == mVPlanePattern) {
            planeSalt = 0x10293847;
        } else {
            throw new IllegalArgumentException("unknown fake-input plane");
        }
        String key = planeSalt + ":" + sigma + ":" + phase;
        byte[] cached = mNoisyCache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] out = deterministicNoisyVariant(
                base, mNoiseSeedBase, sigma, phase, planeSalt);
        mNoisyCache.put(key, out);
        return out;
    }

    static int interleavedChromaOffset(int lumaOffset) {
        return 2 * (lumaOffset / 2);
    }

    /**
     * Local-state implementation of {@link java.util.Random#nextInt(int)}.
     *
     * <p>It intentionally reproduces java.util.Random's 48-bit LCG and bounded
     * integer algorithm byte-for-byte, but avoids the atomic seed update paid by
     * java.util.Random for every pixel. A 4 MP, eight-phase probe otherwise
     * spends minutes in fake-input prewarming on lower-power devices before
     * MediaCodec creation is even attempted.
     */
    private static final class LocalJavaRandom {
        private static final long MULTIPLIER = 0x5DEECE66DL;
        private static final long ADDEND = 0xBL;
        private static final long MASK = (1L << 48) - 1;
        private long seed;

        LocalJavaRandom(long seed) {
            this.seed = (seed ^ MULTIPLIER) & MASK;
        }

        private int next(int bits) {
            seed = (seed * MULTIPLIER + ADDEND) & MASK;
            return (int) (seed >>> (48 - bits));
        }

        int nextInt(int bound) {
            if (bound <= 0) {
                throw new IllegalArgumentException("bound must be positive");
            }
            if ((bound & -bound) == bound) {
                return (int) ((bound * (long) next(31)) >> 31);
            }
            int bits;
            int value;
            do {
                bits = next(31);
                value = bits % bound;
            } while (bits - value + (bound - 1) < 0);
            return value;
        }
    }

    /** Pure helper so byte-level reproducibility is covered by JVM tests. */
    static byte[] deterministicNoisyVariant(
            byte[] base, long seedBase, int sigma, int phase, int planeSalt) {
        byte[] out = new byte[base.length];
        LocalJavaRandom rng = new LocalJavaRandom(seedBase
                ^ ((long) sigma * 2654435761L)
                ^ ((long) phase * 0x9E3779B97F4A7C15L)
                ^ (long) planeSalt);
        int range = 2 * sigma + 1;
        for (int i = 0; i < base.length; i++) {
            int v = (base[i] & 0xFF) + rng.nextInt(range) - sigma;
            out[i] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
        }
        return out;
    }

    private void copyPlaneData(ByteBuffer dest, int destOffset,
                               byte[] srcPattern, int srcWidth,
                               int width, int height, int offset) {
        byte[] src = sourceForCurrentFrame(srcPattern);
        for (int y = 0; y < height; y++) {
            dest.position(destOffset + y * width);
            dest.put(src, y * srcWidth + offset, width);
        }
    }

    private void copyInterleavedPlaneData(
            byte[] dest, int destOffset, byte[] src, int srcWidthBytes,
            int widthBytes, int height, int offsetBytes) {
        for (int y = 0; y < height; y++) {
            System.arraycopy(src, y * srcWidthBytes + offsetBytes,
                    dest, destOffset + y * widthBytes, widthBytes);
        }
    }

    private void copyInterleavedPlaneData(
            ByteBuffer dest, int destOffset, byte[] src, int srcWidthBytes,
            int widthBytes, int height, int offsetBytes) {
        for (int y = 0; y < height; y++) {
            dest.position(destOffset + y * widthBytes);
            dest.put(src, y * srcWidthBytes + offsetBytes, widthBytes);
        }
    }

    /**
     * Fill an Image with synthetic YUV data.
     * The pattern scrolls horizontally based on frame count.
     */
    public int fillImage(Image image) {
        synchronized (this) {
            if (isClosed()) {
                return 0;
            }
        }

        if (image.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid ImageFormat: " + image.getFormat());
            return 0;
        }

        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        // Calculate horizontal offset for scrolling
        int offset = mFrameCount % PATTERN_EXTRA_WIDTH;
        int patternWidth = mWidth + PATTERN_EXTRA_WIDTH;

        int bytesPerComponent = (ImageFormat.getBitsPerPixel(image.getFormat()) * 2) / (8 * 3);
        int lumaLength = imageWidth * imageHeight * bytesPerComponent;
        int chromaLength = imageWidth * imageHeight * bytesPerComponent / 4;

        // Determine plane order based on pixel format
        byte[][] planePatterns = new byte[3][];
        if (mPixFmt.getNumber() == PixFmt.yuv420p_VALUE || mPixFmt.getNumber() == PixFmt.nv12_VALUE) {
            planePatterns[0] = mYPlanePattern;
            planePatterns[1] = mUPlanePattern;
            planePatterns[2] = mVPlanePattern;
        } else {
            planePatterns[0] = mYPlanePattern;
            planePatterns[1] = mVPlanePattern;
            planePatterns[2] = mUPlanePattern;
        }

        int[] inputPixelStride = {bytesPerComponent, bytesPerComponent, bytesPerComponent};
        if (mPixFmt.getNumber() == PixFmt.nv12_VALUE || mPixFmt.getNumber() == PixFmt.nv21_VALUE) {
            inputPixelStride[1] = 2 * bytesPerComponent;
            inputPixelStride[2] = 2 * bytesPerComponent;
        }

        try {
            for (int planeid = 0; planeid < planes.length; ++planeid) {
                ByteBuffer buf = planes[planeid].getBuffer();
                int width = imageWidth;
                int height = imageHeight;
                int outputRowStride = planes[planeid].getRowStride();
                int outputPixelStride = planes[planeid].getPixelStride();
                int planePatternWidth = patternWidth;
                int planeOffset = offset;

                if (planeid != 0) {
                    width = imageWidth / 2;
                    height = imageHeight / 2;
                    planePatternWidth = patternWidth / 2;
                    planeOffset = offset / 2;
                }

                byte[] srcPattern = planePatterns[planeid];

                if ((inputPixelStride[planeid] == bytesPerComponent) &&
                    (outputPixelStride == bytesPerComponent) &&
                    (width == outputRowStride)) {
                    // Optimized: full plane copy
                    for (int y = 0; y < height; y++) {
                        int srcIdx = y * planePatternWidth + planeOffset;
                        buf.put(srcPattern, srcIdx, width * bytesPerComponent);
                    }
                } else if ((inputPixelStride[planeid] == bytesPerComponent) &&
                           (outputPixelStride == bytesPerComponent)) {
                    // Optimized: full row copy
                    for (int row = 0; row < height; row++) {
                        int srcIdx = row * planePatternWidth + planeOffset;
                        int outputOffset = row * outputRowStride * bytesPerComponent;
                        buf.position(outputOffset);
                        buf.put(srcPattern, srcIdx, width * bytesPerComponent);
                    }
                } else {
                    // Non-optimized: pixel-by-pixel
                    for (int row = 0; row < height; row++) {
                        for (int x = 0; x < width; x++) {
                            int srcIdx = row * planePatternWidth + x + planeOffset;
                            if ((planeid == 2 && mPixFmt.getNumber() == PixFmt.nv12_VALUE) ||
                                (planeid == 1 && mPixFmt.getNumber() == PixFmt.nv21_VALUE)) {
                                srcIdx += bytesPerComponent;
                            }
                            int outputOffset = (row * outputRowStride + x * outputPixelStride) * bytesPerComponent;
                            buf.position(outputOffset);
                            buf.put(srcPattern, srcIdx, bytesPerComponent);
                        }
                    }
                }
            }
        } catch (BufferOverflowException | ReadOnlyBufferException e) {
            Log.e(TAG, "Error filling image: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }

        mFrameCount++;
        int frameLength = lumaLength + 2 * chromaLength;
        return frameLength;
    }
}
