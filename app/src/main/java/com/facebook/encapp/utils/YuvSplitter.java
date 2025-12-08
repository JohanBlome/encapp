package com.facebook.encapp.utils;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;
import android.util.Size;

import com.facebook.encapp.proto.PixFmt;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for splitting YUV frames into tiles.
 * Used for tiled HEIF image encoding where large images are split into
 * smaller tiles (typically 512x512) for more efficient encoding.
 *
 * All tiles are the same size (full tile dimensions). For images that
 * don't divide evenly by tile size, edge/corner areas are padded with
 * black (Y=0, UV=128). The clap (clean aperture) box in the HEIF
 * container specifies the actual visible area.
 */
public class YuvSplitter {
    private static final String TAG = "encapp.yuv_splitter";

    private final int mSourceWidth;
    private final int mSourceHeight;
    private final int mTileWidth;
    private final int mTileHeight;
    private final PixFmt mPixFmt;
    private final int mTileColumns;
    private final int mTileRows;
    private final int mTotalTiles;
    private final int mPaddedWidth;
    private final int mPaddedHeight;

    /**
     * Creates a YUV splitter for the given source resolution and tile size.
     *
     * @param sourceWidth  Width of the source image
     * @param sourceHeight Height of the source image
     * @param tileWidth    Width of each tile (if 0, uses tileHeight for square tiles)
     * @param tileHeight   Height of each tile (if 0, uses tileWidth for square tiles)
     * @param pixFmt       Pixel format of the YUV data
     */
    public YuvSplitter(int sourceWidth, int sourceHeight, int tileWidth, int tileHeight, PixFmt pixFmt) {
        mSourceWidth = sourceWidth;
        mSourceHeight = sourceHeight;
        mPixFmt = pixFmt;

        if (tileWidth <= 0 && tileHeight > 0) {
            tileWidth = tileHeight;
        } else if (tileHeight <= 0 && tileWidth > 0) {
            tileHeight = tileWidth;
        } else if (tileWidth <= 0 && tileHeight <= 0) {
            throw new IllegalArgumentException("At least one tile dimension must be positive");
        }

        mTileWidth = tileWidth;
        mTileHeight = tileHeight;

        mTileColumns = (sourceWidth + tileWidth - 1) / tileWidth;
        mTileRows = (sourceHeight + tileHeight - 1) / tileHeight;
        mTotalTiles = mTileColumns * mTileRows;

        mPaddedWidth = mTileColumns * tileWidth;
        mPaddedHeight = mTileRows * tileHeight;

        Log.d(TAG, String.format("YuvSplitter: source=%dx%d, tile=%dx%d, grid=%dx%d, padded=%dx%d",
                sourceWidth, sourceHeight, mTileWidth, mTileHeight, 
                mTileColumns, mTileRows, mPaddedWidth, mPaddedHeight));
    }

    public int getTileColumns() { return mTileColumns; }
    public int getTileRows() { return mTileRows; }
    public int getTotalTiles() { return mTotalTiles; }
    public int getTileWidth() { return mTileWidth; }
    public int getTileHeight() { return mTileHeight; }
    public int getSourceWidth() { return mSourceWidth; }
    public int getSourceHeight() { return mSourceHeight; }
    public int getPaddedWidth() { return mPaddedWidth; }
    public int getPaddedHeight() { return mPaddedHeight; }

    public int getTileSizeInBytes() {
        return (int) (mTileWidth * mTileHeight * 1.5);
    }

    public Size getTileSize() {
        return new Size(mTileWidth, mTileHeight);
    }

    public Size getGridSize() {
        return new Size(mTileColumns, mTileRows);
    }

    /**
     * Represents a single tile extracted from a YUV frame.
     */
    public static class Tile {
        private final int mTileIndex;
        private final int mRow;
        private final int mColumn;
        private final int mWidth;
        private final int mHeight;
        private final byte[] mYData;
        private final byte[] mUData;
        private final byte[] mVData;

        public Tile(int tileIndex, int row, int column, int width, int height,
                    byte[] yData, byte[] uData, byte[] vData) {
            mTileIndex = tileIndex;
            mRow = row;
            mColumn = column;
            mWidth = width;
            mHeight = height;
            mYData = yData;
            mUData = uData;
            mVData = vData;
        }

        public int getTileIndex() { return mTileIndex; }
        public int getRow() { return mRow; }
        public int getColumn() { return mColumn; }
        public int getWidth() { return mWidth; }
        public int getHeight() { return mHeight; }
        public byte[] getYData() { return mYData; }
        public byte[] getUData() { return mUData; }
        public byte[] getVData() { return mVData; }

        public int getSizeInBytes() {
            return (int) (mWidth * mHeight * 1.5);
        }

        /**
         * Write the tile data to a ByteBuffer in the specified pixel format.
         */
        public void writeToBuffer(ByteBuffer buffer, PixFmt pixFmt) {
            buffer.clear();

            switch (pixFmt.getNumber()) {
                case PixFmt.yuv420p_VALUE:
                    buffer.put(mYData);
                    buffer.put(mUData);
                    buffer.put(mVData);
                    break;

                case PixFmt.yvu420p_VALUE:
                    buffer.put(mYData);
                    buffer.put(mVData);
                    buffer.put(mUData);
                    break;

                case PixFmt.nv12_VALUE:
                    buffer.put(mYData);
                    for (int i = 0; i < mUData.length; i++) {
                        buffer.put(mUData[i]);
                        buffer.put(mVData[i]);
                    }
                    break;

                case PixFmt.nv21_VALUE:
                    buffer.put(mYData);
                    for (int i = 0; i < mVData.length; i++) {
                        buffer.put(mVData[i]);
                        buffer.put(mUData[i]);
                    }
                    break;

                default:
                    throw new IllegalArgumentException("Unsupported pixel format: " + pixFmt);
            }
        }

        /**
         * Fill an Image with the tile data.
         * Handles both planar (I420) and semi-planar (NV12/NV21) Image layouts.
         */
        public void fillImage(Image image) {
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                throw new IllegalArgumentException("Image must be YUV_420_888 format");
            }

            Image.Plane[] planes = image.getPlanes();
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();

            ByteBuffer yBuffer = planes[0].getBuffer();
            int yRowStride = planes[0].getRowStride();
            int yPixelStride = planes[0].getPixelStride();

            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();

            boolean ySimpleLayout = (yRowStride == imageWidth && yPixelStride == 1);

            // Write Y plane
            if (ySimpleLayout && imageWidth == mWidth && imageHeight == mHeight) {
                yBuffer.rewind();
                yBuffer.put(mYData, 0, mYData.length);
            } else {
                for (int row = 0; row < imageHeight; row++) {
                    int dstRowStart = row * yRowStride;
                    int srcRowStart = row * mWidth;

                    for (int col = 0; col < imageWidth; col++) {
                        byte yValue;
                        if (row < mHeight && col < mWidth) {
                            int srcIdx = srcRowStart + col;
                            yValue = (srcIdx < mYData.length) ? mYData[srcIdx] : 0;
                        } else {
                            yValue = 0;
                        }

                        int dstIdx = dstRowStart + col * yPixelStride;
                        if (dstIdx < yBuffer.capacity()) {
                            yBuffer.put(dstIdx, yValue);
                        }
                    }
                }
            }

            // Chroma dimensions
            int chromaWidth = imageWidth / 2;
            int chromaHeight = imageHeight / 2;
            int srcChromaWidth = mWidth / 2;
            int srcChromaHeight = mHeight / 2;

            boolean isSemiPlanar = (uvPixelStride == 2);

            // Write UV planes
            if (isSemiPlanar) {
                for (int row = 0; row < chromaHeight; row++) {
                    for (int col = 0; col < chromaWidth; col++) {
                        byte uValue, vValue;
                        if (row < srcChromaHeight && col < srcChromaWidth) {
                            int srcIdx = row * srcChromaWidth + col;
                            uValue = (srcIdx < mUData.length) ? mUData[srcIdx] : (byte) 128;
                            vValue = (srcIdx < mVData.length) ? mVData[srcIdx] : (byte) 128;
                        } else {
                            uValue = (byte) 128;
                            vValue = (byte) 128;
                        }

                        int uvPos = row * uvRowStride + col * 2;
                        if (uvPos + 1 < uBuffer.capacity()) {
                            uBuffer.put(uvPos, uValue);
                            uBuffer.put(uvPos + 1, vValue);
                        }
                    }
                }
            } else {
                for (int row = 0; row < chromaHeight; row++) {
                    int dstRowStart = row * uvRowStride;
                    int srcRowStart = row * srcChromaWidth;

                    for (int col = 0; col < chromaWidth; col++) {
                        byte uValue, vValue;
                        if (row < srcChromaHeight && col < srcChromaWidth) {
                            int srcIdx = srcRowStart + col;
                            uValue = (srcIdx < mUData.length) ? mUData[srcIdx] : (byte) 128;
                            vValue = (srcIdx < mVData.length) ? mVData[srcIdx] : (byte) 128;
                        } else {
                            uValue = (byte) 128;
                            vValue = (byte) 128;
                        }

                        int dstIdx = dstRowStart + col * uvPixelStride;

                        if (dstIdx < uBuffer.capacity()) {
                            uBuffer.put(dstIdx, uValue);
                        }
                        if (dstIdx < vBuffer.capacity()) {
                            vBuffer.put(dstIdx, vValue);
                        }
                    }
                }
            }
        }
    }

    /**
     * Split a YUV frame into tiles.
     * All tiles are the same size (mTileWidth x mTileHeight).
     * Edge tiles that extend beyond the source image are padded with black.
     */
    public List<Tile> splitFrame(byte[] frameData) {
        int lumaSize = mSourceWidth * mSourceHeight;
        int chromaSize = lumaSize / 4;
        int expectedSize = lumaSize + 2 * chromaSize;

        if (frameData.length < expectedSize) {
            throw new IllegalArgumentException(
                    String.format("Frame data too small: got %d bytes, expected %d",
                            frameData.length, expectedSize));
        }

        byte[] yPlane = new byte[lumaSize];
        byte[] uPlane = new byte[chromaSize];
        byte[] vPlane = new byte[chromaSize];

        parseFrame(frameData, yPlane, uPlane, vPlane);

        int fullTileYSize = mTileWidth * mTileHeight;
        int fullTileChromaSize = fullTileYSize / 4;
        int chromaTileWidth = mTileWidth / 2;
        int chromaSourceWidth = mSourceWidth / 2;

        List<Tile> tiles = new ArrayList<>(mTotalTiles);

        for (int tileRow = 0; tileRow < mTileRows; tileRow++) {
            for (int tileCol = 0; tileCol < mTileColumns; tileCol++) {
                int tileIndex = tileRow * mTileColumns + tileCol;

                int tileStartX = tileCol * mTileWidth;
                int tileStartY = tileRow * mTileHeight;
                int availableWidth = Math.max(0, Math.min(mTileWidth, mSourceWidth - tileStartX));
                int availableHeight = Math.max(0, Math.min(mTileHeight, mSourceHeight - tileStartY));

                // Create full-size Y tile (pad with 0 for black)
                byte[] tileY = new byte[fullTileYSize];

                for (int y = 0; y < availableHeight; y++) {
                    int srcOffset = (tileStartY + y) * mSourceWidth + tileStartX;
                    int dstOffset = y * mTileWidth;
                    System.arraycopy(yPlane, srcOffset, tileY, dstOffset, availableWidth);
                }

                // Create full-size U and V tiles (pad with 128 for neutral gray)
                byte[] tileU = new byte[fullTileChromaSize];
                byte[] tileV = new byte[fullTileChromaSize];
                java.util.Arrays.fill(tileU, (byte) 128);
                java.util.Arrays.fill(tileV, (byte) 128);

                int chromaStartX = tileStartX / 2;
                int chromaStartY = tileStartY / 2;
                int availableChromaWidth = availableWidth / 2;
                int availableChromaHeight = availableHeight / 2;

                for (int y = 0; y < availableChromaHeight; y++) {
                    int srcOffset = (chromaStartY + y) * chromaSourceWidth + chromaStartX;
                    int dstOffset = y * chromaTileWidth;
                    System.arraycopy(uPlane, srcOffset, tileU, dstOffset, availableChromaWidth);
                    System.arraycopy(vPlane, srcOffset, tileV, dstOffset, availableChromaWidth);
                }

                tiles.add(new Tile(tileIndex, tileRow, tileCol, mTileWidth, mTileHeight,
                        tileY, tileU, tileV));
            }
        }

        return tiles;
    }

    /**
     * Parse a frame from the input format into separate Y, U, V planes.
     */
    private void parseFrame(byte[] frameData, byte[] yPlane, byte[] uPlane, byte[] vPlane) {
        int lumaSize = mSourceWidth * mSourceHeight;
        int chromaSize = lumaSize / 4;

        System.arraycopy(frameData, 0, yPlane, 0, lumaSize);

        switch (mPixFmt.getNumber()) {
            case PixFmt.yuv420p_VALUE:
                System.arraycopy(frameData, lumaSize, uPlane, 0, chromaSize);
                System.arraycopy(frameData, lumaSize + chromaSize, vPlane, 0, chromaSize);
                break;

            case PixFmt.yvu420p_VALUE:
                System.arraycopy(frameData, lumaSize, vPlane, 0, chromaSize);
                System.arraycopy(frameData, lumaSize + chromaSize, uPlane, 0, chromaSize);
                break;

            case PixFmt.nv12_VALUE:
                for (int i = 0; i < chromaSize; i++) {
                    uPlane[i] = frameData[lumaSize + i * 2];
                    vPlane[i] = frameData[lumaSize + i * 2 + 1];
                }
                break;

            case PixFmt.nv21_VALUE:
                for (int i = 0; i < chromaSize; i++) {
                    vPlane[i] = frameData[lumaSize + i * 2];
                    uPlane[i] = frameData[lumaSize + i * 2 + 1];
                }
                break;

            default:
                throw new IllegalArgumentException("Unsupported pixel format: " + mPixFmt);
        }
    }
}
