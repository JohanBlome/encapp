package com.facebook.encapp.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import java.util.Arrays;

import com.facebook.encapp.proto.Runtime;

import java.util.Collections;
import java.util.Random;

import org.junit.Test;

/** Tests the source-complexity contract used by encoder_rc_probe.py. */
public class FakeInputReaderTest {

    private static Runtime.FakeInputComplexityParameter highAtZero() {
        return Runtime.FakeInputComplexityParameter.newBuilder()
                .setFramenum(0)
                .setComplexity("HIGH")
                .build();
    }

    private static int resolvedHighSigma(String mime, boolean codecAdaptive) {
        FakeInputReader reader = new FakeInputReader();
        reader.setMediaType(mime);
        reader.setCodecAdaptiveNoise(codecAdaptive);
        reader.setComplexitySchedule(Collections.singletonList(highAtZero()));
        return reader.effectivePoint(0).sigma;
    }

    @Test
    public void rateControlProbeUsesSameNoiseForEveryCodec() {
        assertEquals(
                resolvedHighSigma("video/hevc", false),
                resolvedHighSigma("video/av1", false));
        assertEquals(17, resolvedHighSigma("video/hevc", false));
    }

    @Test
    public void noiseBytesAreStableAndTemporalPhasesDiffer() {
        byte[] base = new byte[1024];
        Arrays.fill(base, (byte) 128);
        byte[] first = FakeInputReader.deterministicNoisyVariant(
                base, 12345L, 17, 3, 0x13579BDF);
        byte[] repeated = FakeInputReader.deterministicNoisyVariant(
                base, 12345L, 17, 3, 0x13579BDF);
        byte[] nextPhase = FakeInputReader.deterministicNoisyVariant(
                base, 12345L, 17, 4, 0x13579BDF);
        assertArrayEquals(first, repeated);
        assertFalse(Arrays.equals(first, nextPhase));
    }

    @Test
    public void optimizedNoiseMatchesJavaRandomByteForByte() {
        byte[] base = new byte[4096];
        for (int index = 0; index < base.length; index++) {
            base[index] = (byte) index;
        }
        long seedBase = 12345L;
        int sigma = 17;
        int phase = 3;
        int planeSalt = 0x13579BDF;
        long seed = seedBase
                ^ ((long) sigma * 2654435761L)
                ^ ((long) phase * 0x9E3779B97F4A7C15L)
                ^ (long) planeSalt;
        Random reference = new Random(seed);
        byte[] expected = new byte[base.length];
        int range = 2 * sigma + 1;
        for (int index = 0; index < base.length; index++) {
            int value = (base[index] & 0xFF)
                    + reference.nextInt(range) - sigma;
            expected[index] = (byte) Math.max(0, Math.min(255, value));
        }

        assertArrayEquals(expected, FakeInputReader.deterministicNoisyVariant(
                base, seedBase, sigma, phase, planeSalt));
    }

    @Test
    public void interleavedChromaOffsetPreservesComponentOrder() {
        assertEquals(0, FakeInputReader.interleavedChromaOffset(0));
        assertEquals(0, FakeInputReader.interleavedChromaOffset(1));
        assertEquals(2, FakeInputReader.interleavedChromaOffset(2));
        assertEquals(2, FakeInputReader.interleavedChromaOffset(3));
    }

    @Test
    public void legacyFakeInputRetainsCodecAdaptiveScaling() {
        assertNotEquals(
                resolvedHighSigma("video/hevc", true),
                resolvedHighSigma("video/av1", true));
    }
}
