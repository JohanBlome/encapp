package com.facebook.encapp.utils;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper that abstracts Android MediaMuxer and internal Java Muxer.
 * Provides a unified interface for both implementations.
 */
public class MuxerWrapper {
    private static final String TAG = "encapp.muxerwrapper";

    private MediaMuxer mMediaMuxer;
    private Muxer mInternalMuxer;
    private boolean mUseInternalMuxer;
    private boolean mStarted;
    private int mVideoTrack = -1;

    /**
     * Create a muxer wrapper.
     *
     * @param filename Output file path
     * @param useInternalMuxer true to use internal Java muxer, false for Android MediaMuxer
     * @param width Video width (for internal muxer)
     * @param height Video height (for internal muxer)
     * @param frameRate Frame rate (for internal muxer)
     * @param isHEVC Legacy parameter (ignored - codec detected from MediaFormat)
     * @param isImageOutput true for image output (HEIF container)
     */
    public MuxerWrapper(String filename, boolean useInternalMuxer, int width, int height,
                       float frameRate, boolean isHEVC, boolean isImageOutput) {
        // Image output always requires internal muxer (MediaMuxer doesn't support HEIF)
        mUseInternalMuxer = useInternalMuxer || isImageOutput;

        if (mUseInternalMuxer) {
            Log.d(TAG, "Creating internal Java muxer: " + filename);
            mInternalMuxer = new Muxer(filename, width, height, 90000, frameRate);

            if (isImageOutput) {
                mInternalMuxer.setContainerFormat(Muxer.ContainerFormat.HEIC_IMAGE);
                Log.d(TAG, "Configured for image output (HEIF container)");
            }
        } else {
            Log.d(TAG, "Creating Android MediaMuxer: " + filename);
            try {
                int outputFormat = isHEVC ?
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 :
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

                // Check if codec is VP8/VP9 for WebM
                if (filename.toLowerCase().endsWith(".webm")) {
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
                }

                mMediaMuxer = new MediaMuxer(filename, outputFormat);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create MediaMuxer", e);
                throw new RuntimeException("Failed to create MediaMuxer", e);
            }
        }

        mStarted = false;
    }

    /**
     * Add a video track with the specified format.
     *
     * @param format MediaFormat from MediaCodec
     * @return track index (0 for internal muxer)
     */
    /**
     * Add a video track with the specified format.
     *
     * @param format MediaFormat from MediaCodec
     * @return track index (0 for internal muxer)
     */
    public int addTrack(MediaFormat format) {
        Log.d(TAG, "=== addTrack called ===");
        Log.d(TAG, "mUseInternalMuxer: " + mUseInternalMuxer);
        Log.d(TAG, "mInternalMuxer: " + (mInternalMuxer != null ? "not null" : "null"));
        Log.d(TAG, "MediaFormat: " + format);

        if (mUseInternalMuxer && mInternalMuxer != null) {
            // Get codec-specific data from MediaFormat
            ByteBuffer csdBuffer = format.getByteBuffer("csd-0");
            byte[] codecData = null;
            if (csdBuffer != null) {
                codecData = new byte[csdBuffer.remaining()];
                csdBuffer.get(codecData);
                Log.d(TAG, "Got codec config data from MediaFormat: " + codecData.length + " bytes");

                // Log first few bytes
                if (codecData.length > 0) {
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < Math.min(16, codecData.length); i++) {
                        hex.append(String.format("%02x ", codecData[i] & 0xFF));
                    }
                }
            } else {
                Log.w(TAG, "No csd-0 buffer in MediaFormat");
            }

            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.d(TAG, "MediaFormat MIME type: " + mime);

            // Auto-detect tile grid from MediaFormat using standard Android keys (API 31+)
            // BUT ONLY if tile mode was not already explicitly set via setTileMode()
            // This prevents the codec's 1x1 grid from overwriting our actual tile configuration
            boolean tileDetected = false;

            if (!mInternalMuxer.isTileModeEnabled()) {
                // Check for standard Android tile keys (MediaFormat.KEY_GRID_COLUMNS, KEY_GRID_ROWS)
                if (format.containsKey(MediaFormat.KEY_GRID_COLUMNS) && format.containsKey(MediaFormat.KEY_GRID_ROWS)) {
                    int gridCols = format.getInteger(MediaFormat.KEY_GRID_COLUMNS);
                    int gridRows = format.getInteger(MediaFormat.KEY_GRID_ROWS);
                    Log.d(TAG, String.format("Auto-detected tile grid from MediaFormat (standard keys): %dx%d", gridCols, gridRows));
                    mInternalMuxer.setTileMode(gridCols, gridRows);
                    tileDetected = true;
                }
                // Fall back to custom keys for older implementations
                else if (format.containsKey("grid-cols") && format.containsKey("grid-rows")) {
                    int gridCols = format.getInteger("grid-cols");
                    int gridRows = format.getInteger("grid-rows");
                    Log.d(TAG, String.format("Auto-detected tile grid from MediaFormat (custom keys): %dx%d", gridCols, gridRows));
                    mInternalMuxer.setTileMode(gridCols, gridRows);
                    tileDetected = true;
                }
            } else {
                Log.d(TAG, "Tile mode already explicitly set, skipping auto-detection from MediaFormat");
            }

            // Auto-detect tile dimensions from MediaFormat
            // Try standard keys first (MediaFormat.KEY_TILE_WIDTH, KEY_TILE_HEIGHT)
            if (format.containsKey(MediaFormat.KEY_TILE_WIDTH) && format.containsKey(MediaFormat.KEY_TILE_HEIGHT)) {
                int tileWidth = format.getInteger(MediaFormat.KEY_TILE_WIDTH);
                int tileHeight = format.getInteger(MediaFormat.KEY_TILE_HEIGHT);
                Log.d(TAG, String.format("Auto-detected tile dimensions from MediaFormat (standard keys): %dx%d", tileWidth, tileHeight));
                mInternalMuxer.setTileDimensions(tileWidth, tileHeight);
            }
            // Fall back to custom keys
            else if (format.containsKey("tile-width") && format.containsKey("tile-height")) {
                int tileWidth = format.getInteger("tile-width");
                int tileHeight = format.getInteger("tile-height");
                Log.d(TAG, String.format("Auto-detected tile dimensions from MediaFormat (custom keys): %dx%d", tileWidth, tileHeight));
                mInternalMuxer.setTileDimensions(tileWidth, tileHeight);
            }

            if (tileDetected) {
                Log.d(TAG, "Tile mode auto-detected and enabled from MediaFormat!");
            }

            if (codecData != null && codecData.length > 0) {
                // Use MIME type auto-detection instead of isHEVC flag
                Log.d(TAG, "Calling initializeFromMimeType with mime: " + mime);
                if (!mInternalMuxer.initializeFromMimeType(codecData, mime)) {
                    Log.e(TAG, "Failed to initialize internal muxer");
                    return -1;
                }
                Log.d(TAG, "Internal muxer initialized successfully");
            } else {
                Log.w(TAG, "No codec config data in MediaFormat");
                // Try to initialize without codec data using MIME type
                Log.d(TAG, "Calling initializeFromMimeType without codec data, mime: " + mime);
                boolean result = mInternalMuxer.initializeFromMimeType(null, mime);
                Log.d(TAG, "initializeFromMimeType result: " + result);
            }

            return 0; // Internal muxer uses track ID 0
        } else if (mMediaMuxer != null) {
            Log.d(TAG, "Using MediaMuxer.addTrack");
            int trackId = mMediaMuxer.addTrack(format);
            Log.d(TAG, "MediaMuxer.addTrack returned: " + trackId);
            return trackId;
        }

        Log.e(TAG, "=== addTrack returning -1 (no muxer available) ===");
        return -1;
    }

    /**
     * Start the muxer.
     */
    public void start() {
        if (mStarted) {
            Log.w(TAG, "Muxer already started");
            return;
        }

        if (mUseInternalMuxer) {
            // Internal muxer doesn't have explicit start (starts on initialize)
            Log.d(TAG, "Internal muxer ready");
        } else {
            mMediaMuxer.start();
            Log.d(TAG, "MediaMuxer started");
        }

        mStarted = true;
    }

    /**
     * Write sample data.
     *
     * @param trackIndex Track index (from addTrack)
     * @param buffer Data buffer
     * @param bufferInfo Buffer info with size, timestamp, flags
     */
    public void writeSampleData(int trackIndex, ByteBuffer encodedData, MediaCodec.BufferInfo bufferInfo) {
        if (mUseInternalMuxer && mInternalMuxer != null) {
            // Extract frame data
            byte[] frameData = new byte[bufferInfo.size];
            encodedData.position(bufferInfo.offset);
            encodedData.get(frameData);

            // Check if this is a keyframe
            boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

            // Add frame to internal muxer
            boolean success = mInternalMuxer.addFrame(frameData, bufferInfo.presentationTimeUs, isKeyFrame);
        } else if (mMediaMuxer != null) {
            mMediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo);
        } else {
            Log.e(TAG, "No muxer available to write sample!");
        }
    }

    /**
     * Stop the muxer (finalize for internal muxer).
     */
    public void stop() {
        if (!mStarted) {
            return;
        }

        if (mUseInternalMuxer) {
            mInternalMuxer.finalizeMux();
            Log.d(TAG, "Internal muxer finalized");
        } else {
            mMediaMuxer.stop();
            Log.d(TAG, "MediaMuxer stopped");
        }

        mStarted = false;
    }

    /**
     * Release muxer resources and finalize the file.
     */
    public void release() {
        if (mUseInternalMuxer && mInternalMuxer != null) {
            Log.d(TAG, "Finalizing internal muxer");
            mInternalMuxer.finalizeMux();
            mInternalMuxer.close();
            Log.d(TAG, "Internal muxer finalized and closed");
            mInternalMuxer = null;
        } else if (mMediaMuxer != null) {
            Log.d(TAG, "Releasing MediaMuxer");
            mMediaMuxer.release();
            Log.d(TAG, "MediaMuxer released");
            mMediaMuxer = null;
        }
    }

    /**
     * Set clean aperture for the internal muxer (for cropping).
     */
    public void setCleanAperture(int cleanWidth, int cleanHeight) {
        if (mUseInternalMuxer) {
            mInternalMuxer.setCleanAperture(cleanWidth, cleanHeight);
        } else {
            Log.w(TAG, "Clean aperture not supported by MediaMuxer");
        }
    }

    /**
     * Enable tile mode for HEIC images.
     * When enabled, muxer will accept multiple frames/tiles and create a tiled HEIC image.
    /**
     * Set tile mode for tiled HEIC images.
     * Call this after creating the muxer wrapper to enable tile mode.
     *
     * @param tileColumns Number of tile columns
     * @param tileRows Number of tile rows
     */
    public void setTileMode(int tileColumns, int tileRows) {
        if (mUseInternalMuxer && mInternalMuxer != null) {
            mInternalMuxer.setTileMode(tileColumns, tileRows);
            Log.d(TAG, String.format("Tile mode enabled: %dx%d grid", tileColumns, tileRows));
        } else {
            Log.w(TAG, "Tile mode only supported with internal muxer");
        }
    }

    /**
     * Set actual tile dimensions (from MediaFormat or encoder).
     * Call this to provide the actual tile size from the encoder.
     *
     * @param tileWidth Actual tile width in pixels
     * @param tileHeight Actual tile height in pixels
     */
    public void setTileDimensions(int tileWidth, int tileHeight) {
        if (mUseInternalMuxer && mInternalMuxer != null) {
            mInternalMuxer.setTileDimensions(tileWidth, tileHeight);
            Log.d(TAG, String.format("Tile dimensions set: %dx%d", tileWidth, tileHeight));
        } else {
            Log.w(TAG, "Tile dimensions only supported with internal muxer");
        }
    }

    /**
     * Set the grid output dimensions (total encoded size after tiling).
     * This should be the padded dimensions (tile_width * columns, tile_height * rows).
     * Call this for tiled encoding when the padded size differs from the source size.
     *
     * @param paddedWidth Total padded width (tile_width * tile_columns)
     * @param paddedHeight Total padded height (tile_height * tile_rows)
     */
    public void setGridOutputDimensions(int paddedWidth, int paddedHeight) {
        if (mUseInternalMuxer && mInternalMuxer != null) {
            mInternalMuxer.setGridOutputDimensions(paddedWidth, paddedHeight);
            Log.d(TAG, String.format("Grid output dimensions set: %dx%d", paddedWidth, paddedHeight));
        } else {
            Log.w(TAG, "Grid output dimensions only supported with internal muxer");
        }
    }

    /**
     * Check if using internal muxer.
     */
    public boolean isUsingInternalMuxer() {
        return mUseInternalMuxer;
    }
}
