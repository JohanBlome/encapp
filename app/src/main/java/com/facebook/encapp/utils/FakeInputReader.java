package com.facebook.encapp.utils;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import com.facebook.encapp.proto.PixFmt;

/**
 * FakeInputReader generates synthetic YUV frames with a vertical pattern
 * that scrolls horizontally to avoid completely static content.
 * This is useful for performance testing without filesystem or camera overhead.
 */
public class FakeInputReader {
    private static final String TAG = "encapp.fakeinput";

    private PixFmt mPixFmt;
    private int mWidth;
    private int mHeight;
    private int mFrameCount;
    private boolean mClosed;

    // Pattern parameters
    private static final int PATTERN_EXTRA_WIDTH = 64; // Extra width for scrolling
    private byte[] mYPlanePattern;
    private byte[] mUPlanePattern;
    private byte[] mVPlanePattern;

    public FakeInputReader() {
        mClosed = true;
        mFrameCount = 0;
    }

    /**
     * Initialize the fake input reader with resolution and pixel format.
     * Creates a vertical stripe pattern that's wider than the actual frame.
     */
    public boolean openFile(String name, PixFmt pixFmt, int width, int height) {
        Log.i(TAG, "FakeInputReader.openFile: name: " + name + " pix_fmt: " + pixFmt + " resolution: " + width + "x" + height);
        mPixFmt = pixFmt;
        mWidth = width;
        mHeight = height;
        mFrameCount = 0;
        mClosed = false;

        // Create pattern buffers with extra width for scrolling
        int patternWidth = width + PATTERN_EXTRA_WIDTH;
        int yPlaneSize = patternWidth * height;
        int uvWidth = patternWidth / 2;
        int uvHeight = height / 2;
        int uvPlaneSize = uvWidth * uvHeight;

        mYPlanePattern = new byte[yPlaneSize];
        mUPlanePattern = new byte[uvPlaneSize];
        mVPlanePattern = new byte[uvPlaneSize];

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

        // Calculate horizontal offset for scrolling (wraps around)
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
                    copyPlaneDataInterleaved(bytes, lumaSize, mUPlanePattern, mVPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2, false);
                } else if (mPixFmt.getNumber() == PixFmt.nv21_VALUE) {
                    // Semi-planar: Y, interleaved VU
                    copyPlaneDataInterleaved(bytes, lumaSize, mVPlanePattern, mUPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2, false);
                }
            } else {
                // Non-array backed buffer
                byte[] tempBuffer = new byte[size];
                copyPlaneData(tempBuffer, 0, mYPlanePattern, patternWidth, mWidth, mHeight, offset);

                if (mPixFmt.getNumber() == PixFmt.yuv420p_VALUE) {
                    copyPlaneData(tempBuffer, lumaSize, mUPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                    copyPlaneData(tempBuffer, lumaSize + chromaSize, mVPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                } else if (mPixFmt.getNumber() == PixFmt.yvu420p_VALUE) {
                    copyPlaneData(tempBuffer, lumaSize, mVPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                    copyPlaneData(tempBuffer, lumaSize + chromaSize, mUPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2);
                } else if (mPixFmt.getNumber() == PixFmt.nv12_VALUE) {
                    copyPlaneDataInterleaved(tempBuffer, lumaSize, mUPlanePattern, mVPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2, false);
                } else if (mPixFmt.getNumber() == PixFmt.nv21_VALUE) {
                    copyPlaneDataInterleaved(tempBuffer, lumaSize, mVPlanePattern, mUPlanePattern, patternWidth / 2, chromaWidth, chromaHeight, offset / 2, false);
                }

                byteBuffer.put(tempBuffer);
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
     */
    private void copyPlaneData(byte[] dest, int destOffset, byte[] srcPattern,
                               int srcWidth, int width, int height, int offset) {
        for (int y = 0; y < height; y++) {
            int srcRowStart = y * srcWidth + offset;
            int destRowStart = destOffset + y * width;
            System.arraycopy(srcPattern, srcRowStart, dest, destRowStart, width);
        }
    }

    /**
     * Copy interleaved chroma data (for NV12/NV21 formats).
     */
    private void copyPlaneDataInterleaved(byte[] dest, int destOffset,
                                         byte[] srcPattern1, byte[] srcPattern2,
                                         int srcWidth, int width, int height,
                                         int offset, boolean swapOrder) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcIdx = y * srcWidth + x + offset;
                int destIdx = destOffset + (y * width + x) * 2;
                dest[destIdx] = srcPattern1[srcIdx];
                dest[destIdx + 1] = srcPattern2[srcIdx];
            }
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
