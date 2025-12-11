package com.facebook.encapp;

import static com.facebook.encapp.utils.MediaCodecInfoHelper.getMediaFormatValueFromKey;
import static com.facebook.encapp.utils.MediaCodecInfoHelper.mediaFormatComparison;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.PixFmt;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FakeInputReader;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;
import com.facebook.encapp.utils.YuvSplitter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * Created by jobl on 2018-02-27.
 */

class BufferEncoder extends Encoder {
    private static final String TAG = "encapp.buffer_encoder";

    public BufferEncoder(Test test) {
        super(test);
        mStats = new Statistics("raw encoder", mTest);
    }

    public String start() {
        Log.d(TAG, "** Raw buffer encoding - " + mTest.getCommon().getDescription() + " **");
        mTest = TestDefinitionHelper.updateBasicSettings(mTest);
        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();
        if (mTest.getInput().hasRealtime())
            mRealtime = mTest.getInput().getRealtime();

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();
        mSkipped = 0;
        mFramesAdded = 0;
        Size sourceResolution = SizeUtils.parseXString(mTest.getInput().getResolution());
        PixFmt inputFmt = mTest.getInput().getPixFmt();
        mRefFramesizeInBytes = MediaCodecInfoHelper.frameSizeInBytes(inputFmt, sourceResolution.getWidth(), sourceResolution.getHeight());

        if (mTest.getInput().getFilepath().equals("fake_input")) {
            mIsFakeInput = true;
            Log.d(TAG, "Using fake input for performance testing");
            mFakeInputReader = new FakeInputReader();
            if (!mFakeInputReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt(),
                    sourceResolution.getWidth(), sourceResolution.getHeight())) {
                return "Could not initialize fake input";
            }
        } else {
            mYuvReader = new FileReader();
            if (!mYuvReader.openFile(checkFilePath(mTest.getInput().getFilepath()), mTest.getInput().getPixFmt())) {
                return "Could not open file";
            }
        }

        MediaFormat mediaFormat;
        boolean useImage = false; // Default to false

        // Check if this is image output based on original test configuration MIME
        // (BEFORE translation to video/* MIME type in TestDefinitionHelper)
        boolean isImageMime = false;
        if (mTest.hasConfigure() && mTest.getConfigure().hasMime()) {
            String configMime = mTest.getConfigure().getMime().toLowerCase(Locale.US);
            isImageMime = configMime.startsWith("image/");
        }
        Log.d(TAG, "isImageMime (from test config): " + isImageMime);

        // Check for tile configuration BEFORE configuring codec
        // When tiled encoding is enabled, we configure the codec for tile dimensions
        boolean useTiledEncoding = false;
        YuvSplitter yuvSplitter = null;
        int tileWidth = 0;
        int tileHeight = 0;

        if (mTest.hasConfigure()) {
            if (mTest.getConfigure().hasTileWidth()) {
                tileWidth = mTest.getConfigure().getTileWidth();
            }
            if (mTest.getConfigure().hasTileHeight()) {
                tileHeight = mTest.getConfigure().getTileHeight();
            }
            // If only one is set, use it for both (square tiles)
            if (tileWidth > 0 || tileHeight > 0) {
                useTiledEncoding = true;
                if (tileWidth <= 0) tileWidth = tileHeight;
                if (tileHeight <= 0) tileHeight = tileWidth;

                yuvSplitter = new YuvSplitter(
                        sourceResolution.getWidth(),
                        sourceResolution.getHeight(),
                        tileWidth,
                        tileHeight,
                        inputFmt);

                Log.d(TAG, String.format("Tiled encoding enabled: tile=%dx%d, grid=%dx%d (%d tiles total)",
                        yuvSplitter.getTileWidth(), yuvSplitter.getTileHeight(),
                        yuvSplitter.getTileColumns(), yuvSplitter.getTileRows(),
                        yuvSplitter.getTotalTiles()));
            }
        }

        try {
            // Unless we have a mime, do lookup
            if (mTest.getConfigure().getMime().length() == 0) {
                try {
                    mTest = MediaCodecInfoHelper.setCodecNameAndIdentifier(mTest);
                } catch (Exception e) {
                    return e.getMessage();
                }
                Log.d(TAG, "codec: " + mTest.getConfigure().getCodec() + " mime: " + mTest.getConfigure().getMime());
            }
            Log.d(TAG, "Create codec by name: " + mTest.getConfigure().getCodec());
            mStats.pushTimestamp("encoder.create");
            mCodec = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());
            mStats.pushTimestamp("encoder.create");

            mediaFormat = TestDefinitionHelper.buildMediaFormat(mTest);
            Log.d(TAG, "MediaFormat (mTest)");
            logMediaFormat(mediaFormat);
            setConfigureParams(mTest, mediaFormat);

            // If tiled encoding, override the resolution to tile dimensions
            if (useTiledEncoding && yuvSplitter != null) {
                Log.d(TAG, String.format("Overriding codec resolution from %dx%d to tile size %dx%d",
                        mediaFormat.getInteger(MediaFormat.KEY_WIDTH),
                        mediaFormat.getInteger(MediaFormat.KEY_HEIGHT),
                        yuvSplitter.getTileWidth(),
                        yuvSplitter.getTileHeight()));
                mediaFormat.setInteger(MediaFormat.KEY_WIDTH, yuvSplitter.getTileWidth());
                mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, yuvSplitter.getTileHeight());

                // =========================================================================
                // CRITICAL: For tiled image encoding, ALL frames MUST be I-frames (keyframes)
                // =========================================================================
                // Each tile must be independently decodable. If B-frames or P-frames are
                // used, the encoder will reorder frames internally for better compression,
                // which will SCRAMBLE THE TILE ORDER in the output HEIC file!
                // It is not really prohitioned by the spec, but it is a very bad idea and most
                // HEIC decoders will not be able to handle it.
                // =========================================================================

                // Check if user has set i_frame_interval to something other than 0
                int configuredIFrameInterval = -1;
                if (mTest.getConfigure().hasIFrameInterval()) {
                    configuredIFrameInterval = (int) mTest.getConfigure().getIFrameInterval();
                }

                if (configuredIFrameInterval != 0) {
                    Log.w(TAG, "========================================================================");
                    Log.w(TAG, "WARNING: TILED HEIC ENCODING REQUIRES i_frame_interval = 0");
                    Log.w(TAG, "========================================================================");
                    Log.w(TAG, "Your configuration has i_frame_interval = " + configuredIFrameInterval);
                    Log.w(TAG, "This WILL cause tiles to be scrambled in the output!");
                    Log.w(TAG, "Forcing i_frame_interval = 0 for correct tile ordering.");
                    Log.w(TAG, "========================================================================");
                }

                // Force all-intra mode regardless of user setting
                mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);
                Log.d(TAG, "Set KEY_I_FRAME_INTERVAL=0 for tiled encoding (all I-frames)");

                // Disable B-frames explicitly if supported
                try {
                    mediaFormat.setInteger("max-bframes", 0);
                    Log.d(TAG, "Disabled B-frames for tiled encoding (max-bframes=0)");
                } catch (Exception e) {
                    Log.d(TAG, "Could not set max-bframes, may not be supported");
                }

                // Set low latency mode to help ensure frame ordering
                try {
                    mediaFormat.setInteger(MediaFormat.KEY_LATENCY, 0);
                    Log.d(TAG, "Set KEY_LATENCY=0 for tiled encoding");
                } catch (Exception e) {
                    Log.d(TAG, "Could not set KEY_LATENCY, may not be supported");
                }
            }

            Log.d(TAG, "MediaFormat (configure)");
            logMediaFormat(mediaFormat);

            // useImage flag determines whether to use Image input buffers
            // For tiled encoding, we should prefer Image API as it handles format conversion properly
            int colorFormat = mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
            useImage = (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            // For tiled encoding, force flexible color format to use Image API
            // This avoids pixel format mismatches between input file format and encoder format
            if (useTiledEncoding && !useImage) {
                Log.d(TAG, "Tiled encoding: forcing COLOR_FormatYUV420Flexible for Image API support");
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                useImage = true;
            }

            Log.d(TAG, "useImage (for input buffers): " + useImage + ", colorFormat=" + colorFormat);
            Log.d(TAG, "Configure: " + mCodec.getName());
            mStats.pushTimestamp("encoder.configure");
            mCodec.configure(
                    mediaFormat,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            mStats.pushTimestamp("encoder.configure");
            Log.d(TAG, "MediaFormat (post-mTest)");
            logMediaFormat(mCodec.getInputFormat());
            mStats.setEncoderMediaFormat(mCodec.getInputFormat());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setCodec(mCodec.getCanonicalName());
            } else {
                mStats.setCodec(mCodec.getName());
            }
        } catch (IOException iox) {
            Log.e(TAG, "Failed to create codec: " + iox.getMessage());
            return "Failed to create codec";
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: " + cex.getMessage());
            return "Failed to create codec";
        }

        try {
            Log.d(TAG, "Start encoder");
            mStats.pushTimestamp("encoder.start");
            mCodec.start();
            mStats.pushTimestamp("encoder.start");
        } catch (Exception ex) {
            Log.e(TAG, "Start failed: " + ex.getMessage());
            return "Start encoding failed";
        }

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mKeepInterval = mReferenceFrameRate / mFrameRate;
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Log.d(TAG, "Create muxer");
        MediaFormat outputFormat = mCodec.getOutputFormat();
        // Log format
        Log.d(TAG, "Actual check of some formats after first mediaformat update.");
        Log.d(TAG, MediaCodecInfoHelper.mediaFormatToString(outputFormat));
        mMuxerWrapper = createMuxerWrapper(mCodec, outputFormat);
        // This is needed for VP codecs
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        if (isVP) {
            mVideoTrack = mMuxerWrapper.addTrack(outputFormat);
            mMuxerWrapper.start();
        }

        // Configure muxer for tiled output if supported
        if (useTiledEncoding && yuvSplitter != null && mMuxerWrapper != null) {
            mMuxerWrapper.setTileMode(yuvSplitter.getTileColumns(), yuvSplitter.getTileRows());
            // Set actual tile dimensions (512x512)
            mMuxerWrapper.setTileDimensions(yuvSplitter.getTileWidth(), yuvSplitter.getTileHeight());
            // Set the grid output dimensions to PADDED size (e.g., 1536x1536 for 3x3 grid of 512x512 tiles)
            mMuxerWrapper.setGridOutputDimensions(yuvSplitter.getPaddedWidth(), yuvSplitter.getPaddedHeight());
            // Set the clean aperture to the actual source dimensions (e.g., 1280x1280)
            // This tells the decoder to crop the padded image back to original size
            mMuxerWrapper.setCleanAperture(yuvSplitter.getSourceWidth(), yuvSplitter.getSourceHeight());
            Log.d(TAG, String.format("Muxer configured: grid=%dx%d, tile=%dx%d, padded=%dx%d, clap=%dx%d",
                    yuvSplitter.getTileColumns(), yuvSplitter.getTileRows(),
                    yuvSplitter.getTileWidth(), yuvSplitter.getTileHeight(),
                    yuvSplitter.getPaddedWidth(), yuvSplitter.getPaddedHeight(),
                    yuvSplitter.getSourceWidth(), yuvSplitter.getSourceHeight()));
        }

        // Route to appropriate encoding method
        if (useTiledEncoding && yuvSplitter != null) {
            return encodeTiled(yuvSplitter, sourceResolution, inputFmt, useImage);
        }

        int current_loop = 1;
        boolean input_done = false;
        boolean output_done = false;
        synchronized (this) {
            Log.d(TAG, "Wait for synchronized start");
            try {
                mInitDone = true;
                wait(WAIT_TIME_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mStats.start();
        int failures = 0;
        MediaFormat currentOutputFormat = mCodec.getOutputFormat();
        Dictionary<String, Object> latestFrameChanges = null;
        while (!input_done || !output_done) {
            int index;
            if (mFramesAdded % 100 == 0) {
                Log.d(TAG, mTest.getCommon().getId() + " - BufferEncoder: frames: " + mFramesAdded +
                        " inframes: " + mInFramesCount +
                        " current_loop: " + current_loop +
                        " current_time: " + mCurrentTimeSec);
            }
            // 1. process the encoder input
            try {
                long timeoutUs = VIDEO_CODEC_WAIT_TIME_US;
                index = mCodec.dequeueInputBuffer(timeoutUs);
                int flags = 0;

                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    input_done = true;
                }
                if (mRealtime) {
                    sleepUntilNextFrame();
                }
                if (index >= 0) {
                    failures = 0;
                    int size = -1;
                    // get the ByteBuffer where we will write the image to encode
                    ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
                    while (size < 0 && !input_done) {
                        try {
                            size = queueInputBufferEncoder(
                                    mYuvReader,
                                    mCodec,
                                    byteBuffer,
                                    index,
                                    mInFramesCount,
                                    flags,
                                    mRefFramesizeInBytes,
                                    useImage);

                            mInFramesCount++;
                        } catch (IllegalStateException isx) {
                            Log.e(TAG, "Queue encoder failed, " + index + ", mess: " + isx.getMessage());
                        }
                        Log.e(TAG, "Queued size = " + size);
                        if (size == -2) {
                            continue;
                        } else if (size <= 0) {
                            // restart the loop
                            mYuvReader.closeFile();
                            current_loop++;
                            if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, true)) {
                                input_done = true;
                                // Set EOS flag and call encoder
                                flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                                size = queueInputBufferEncoder(
                                     mYuvReader,
                                     mCodec,
                                     byteBuffer,
                                     index,
                                     mInFramesCount,
                                     flags,
                                     mRefFramesizeInBytes,
                                     useImage);
                            }

                            if (!input_done) {
                                Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                                if (mIsFakeInput) {
                                    mFakeInputReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt(),
                                            sourceResolution.getWidth(), sourceResolution.getHeight());
                                } else {
                                    mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt());
                                }
                                Log.d(TAG, "*** Loop ended start " + current_loop + "***");
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "dequeueInputBuffer, no index, " + index);
                    failures += 1;
                    if (failures >= VIDEO_CODEC_MAX_INPUT_SEC) {
                        // too many consecutive failures
                        return "dequeueInputBuffer(): Too many consecutive failures";
                    }
                }
            } catch (MediaCodec.CodecException ex) {
                Log.e(TAG, "dequeueInputBuffer: MediaCodec.CodecException error");
                ex.printStackTrace();
                return "dequeueInputBuffer: MediaCodec.CodecException error";
            } catch (IllegalStateException ex) {
                Log.e(TAG, "dequeueInputBuffer: IllegalStateException error");
                ex.printStackTrace();
                return "dequeueInputBuffer: IllegalStateException error";
            }

            // 2. process the encoder output
            while (!output_done) {
                try {
                    int outIndex = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US);
                    if (outIndex >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            MediaFormat oformat = mCodec.getOutputFormat();
                            Log.d(TAG, "Output format from codec config: " + oformat);

                            // Only start muxer if not already started
                            if (mWriteFile && mMuxerWrapper != null && mVideoTrack == -1) {
                                Log.d(TAG, "Starting muxer on codec config buffer");
                                mVideoTrack = mMuxerWrapper.addTrack(oformat);
                                mMuxerWrapper.start();
                                Log.d(TAG, "Muxer started, videoTrack=" + mVideoTrack);
                            } else {
                                Log.d(TAG, "Muxer already started, skipping codec config initialization");
                            }
                            mCodec.releaseOutputBuffer(outIndex, false);
                        } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "BUFFER_FLAG_END_OF_STREAM");
                            output_done = true;
                            mCodec.releaseOutputBuffer(outIndex, false);
                        } else {
                            // Regular frame
                            FrameInfo frameInfo = mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                                    (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                            ++mOutFramesCount;
                            frameInfo.addInfo(latestFrameChanges);
                            latestFrameChanges = null;

                            if (mMuxerWrapper != null && mVideoTrack != -1) {
                                ByteBuffer data = mCodec.getOutputBuffer(outIndex);
                                mMuxerWrapper.writeSampleData(mVideoTrack, data, info);
                            }
                            mCodec.releaseOutputBuffer(outIndex, false);
                        }
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // No output available yet - go back to input processing
                        if (input_done) {
                            // If input is done and no output, we're truly done
                            output_done = true;
                        }
                        break; // Exit output loop, go back to input processing
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat oformat = mCodec.getOutputFormat();
                        Log.d(TAG, "Output format: " + oformat);

                        // Start muxer if not already started
                        // Some codecs (like AV1) don't send BUFFER_FLAG_CODEC_CONFIG, only format change
                        if (mWriteFile && mMuxerWrapper != null && mVideoTrack == -1) {
                            Log.d(TAG, "Starting muxer on format change (no codec config buffer)");
                            mVideoTrack = mMuxerWrapper.addTrack(oformat);
                            mMuxerWrapper.start();
                            Log.d(TAG, "Muxer started, videoTrack=" + mVideoTrack);
                        } else {
                            Log.d(TAG, String.format("Not starting muxer: mWriteFile=%b, mMuxerWrapper=%s, mVideoTrack=%d",
                                mWriteFile, (mMuxerWrapper != null ? "not null" : "null"), mVideoTrack));
                        }

                        if (Build.VERSION.SDK_INT >= 29) {
                            latestFrameChanges = mediaFormatComparison(currentOutputFormat, oformat);
                            currentOutputFormat = oformat;
                        }
                    }
                } catch (MediaCodec.CodecException ex) {
                    Log.e(TAG, "dequeueOutputBuffer: MediaCodec.CodecException error");
                    ex.printStackTrace();
                    return "dequeueOutputBuffer: MediaCodec.CodecException error";
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "dequeueOutputBuffer: IllegalStateException error");
                    ex.printStackTrace();
                    return "dequeueOutputBuffer: IllegalStateException error";
                }
            }
        }

        mStats.stop();

        Log.d(TAG, "Close muxer and streams");
        if (mCodec != null) {
            mCodec.stop();
            mCodec.release();
        }
        if (mMuxerWrapper != null) {
            try {
                mMuxerWrapper.release();
            } catch (IllegalStateException ise) {
                Log.e(TAG, "Illegal state exception when trying to release the muxer");
            }
        }

        if (mIsFakeInput && mFakeInputReader != null) {
            mFakeInputReader.closeFile();
        } else if (mYuvReader != null) {
            mYuvReader.closeFile();
        }
        return "";
    }

    /**
     * Encode a frame using tiled encoding.
     * Reads full frames, splits them into tiles, and encodes each tile separately.
     */
    private String encodeTiled(YuvSplitter yuvSplitter, Size sourceResolution, PixFmt inputFmt, boolean useImage) {
        Log.d(TAG, "Starting tiled encoding: " + yuvSplitter.getTotalTiles() + " tiles");

        int frameSize = (int) (sourceResolution.getWidth() * sourceResolution.getHeight() * 1.5);
        byte[] frameBuffer = new byte[frameSize];

        synchronized (this) {
            Log.d(TAG, "Wait for synchronized start (tiled)");
            try {
                mInitDone = true;
                wait(WAIT_TIME_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mStats.start();

        boolean input_done = false;
        boolean output_done = false;
        int failures = 0;
        int current_loop = 1;
        int tilesEncodedCount = 0;

        ConcurrentLinkedQueue<YuvSplitter.Tile> pendingTiles = new ConcurrentLinkedQueue<>();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaFormat currentOutputFormat = mCodec.getOutputFormat();
        Dictionary<String, Object> latestFrameChanges = null;

        while (!input_done || !output_done || !pendingTiles.isEmpty()) {
            // Read and split a new frame if needed
            if (!input_done && pendingTiles.isEmpty()) {
                int read = readFullFrame(frameBuffer, frameSize);
                if (read < 0) {
                    current_loop++;
                    if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, true)) {
                        input_done = true;
                        Log.d(TAG, "Input done: " + mInFramesCount + " frames, " + tilesEncodedCount + " tiles");
                    } else {
                        mYuvReader.closeFile();
                        if (!mYuvReader.openFile(checkFilePath(mTest.getInput().getFilepath()), inputFmt)) {
                            return "Failed to reopen input file";
                        }
                        continue;
                    }
                } else {
                    List<YuvSplitter.Tile> tiles = yuvSplitter.splitFrame(frameBuffer);
                    pendingTiles.addAll(tiles);
                    mInFramesCount++;

                    if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                        input_done = true;
                    }
                }
            }

            // Process encoder input - queue tiles
            if (!pendingTiles.isEmpty() || input_done) {
                try {
                    int index = mCodec.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US);
                    if (index >= 0) {
                        failures = 0;

                        if (!pendingTiles.isEmpty()) {
                            YuvSplitter.Tile tile = pendingTiles.poll();
                            queueTileBuffer(index, tile, tilesEncodedCount, inputFmt, useImage);
                            tilesEncodedCount++;
                        } else if (input_done) {
                            long pts = computePresentationTimeUs(mPts, tilesEncodedCount, mRefFrameTime);
                            mCodec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            Log.d(TAG, "Queued EOS after " + tilesEncodedCount + " tiles");
                            pendingTiles.clear();
                        }
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        failures++;
                        if (failures >= VIDEO_CODEC_MAX_INPUT_SEC * 10 && !input_done) {
                            return "dequeueInputBuffer(): Too many consecutive failures";
                        }
                    }
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "dequeueInputBuffer error: " + ex.getMessage());
                    return "dequeueInputBuffer error: " + ex.getMessage();
                }
            }

            // Process encoder output
            try {
                int outIndex = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US);
                if (outIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        MediaFormat oformat = mCodec.getOutputFormat();
                        if (mWriteFile && mMuxerWrapper != null && mVideoTrack == -1) {
                            mVideoTrack = mMuxerWrapper.addTrack(oformat);
                            mMuxerWrapper.start();
                        }
                        mCodec.releaseOutputBuffer(outIndex, false);
                    } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "BUFFER_FLAG_END_OF_STREAM");
                        output_done = true;
                        mCodec.releaseOutputBuffer(outIndex, false);
                    } else {
                        FrameInfo frameInfo = mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                        ++mOutFramesCount;
                        frameInfo.addInfo(latestFrameChanges);
                        latestFrameChanges = null;

                        if (mMuxerWrapper != null && mVideoTrack != -1) {
                            ByteBuffer data = mCodec.getOutputBuffer(outIndex);
                            mMuxerWrapper.writeSampleData(mVideoTrack, data, info);
                        }
                        mCodec.releaseOutputBuffer(outIndex, false);
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (input_done && pendingTiles.isEmpty()) {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat oformat = mCodec.getOutputFormat();
                    if (mWriteFile && mMuxerWrapper != null && mVideoTrack == -1) {
                        mVideoTrack = mMuxerWrapper.addTrack(oformat);
                        mMuxerWrapper.start();
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        latestFrameChanges = mediaFormatComparison(currentOutputFormat, oformat);
                        currentOutputFormat = oformat;
                    }
                }
            } catch (IllegalStateException ex) {
                Log.e(TAG, "dequeueOutputBuffer error: " + ex.getMessage());
                return "dequeueOutputBuffer error: " + ex.getMessage();
            }
        }

        mStats.stop();
        Log.d(TAG, String.format("Tiled encoding complete: %d frames, %d tiles, %d output",
                mInFramesCount, tilesEncodedCount, mOutFramesCount));

        if (mCodec != null) {
            mCodec.stop();
            mCodec.release();
        }
        if (mMuxerWrapper != null) {
            try {
                mMuxerWrapper.release();
            } catch (IllegalStateException ise) {
                Log.e(TAG, "Error releasing muxer");
            }
        }

        mYuvReader.closeFile();
        return "";
    }

    /**
     * Read a full frame from the YUV file.
     */
    private int readFullFrame(byte[] buffer, int size) {
        if (mYuvReader.isClosed()) {
            return -1;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        int read = mYuvReader.fillBuffer(byteBuffer, size);
        return (read == size) ? read : -1;
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
    }

    /* *
     * Queue a tile to the encoder.
     *
     * @param index Encoder input buffer index
     * @param tile The tile to encode
     * @param tileCount Total tiles encoded so far (for PTS calculation)
     * @param inputFmt Pixel format
     * @param useImage Whether to use Image API
     */
    private void queueTileBuffer(int index, YuvSplitter.Tile tile, int tileCount, PixFmt inputFmt, boolean useImage) {
        long pts = computePresentationTimeUs(mPts, tileCount, mRefFrameTime);
        mStats.startEncodingFrame(pts, tileCount);

        if (useImage) {
            Image image = mCodec.getInputImage(index);
            if (image != null) {
                tile.fillImage(image);
            } else {
                Log.e(TAG, "Failed to get input image for index " + index);
            }
        } else {
            ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
            if (byteBuffer != null) {
                tile.writeToBuffer(byteBuffer, inputFmt);
            } else {
                Log.e(TAG, "Failed to get input buffer for index " + index);
            }
        }

        mCodec.queueInputBuffer(index, 0, tile.getSizeInBytes(), pts, 0);
        mFramesAdded++;
    }

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
    }

    public void stopAllActivity(){}

    public void release() {
    }
}
