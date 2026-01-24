package com.facebook.encapp;

import static com.facebook.encapp.utils.MediaCodecInfoHelper.mediaFormatComparison;

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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AsyncBufferEncoder uses MediaCodec's async callback API for buffer-based encoding.
 * 
 * Unlike BufferEncoder which uses synchronous polling, this encoder:
 * - Uses callbacks for output buffer handling (like SurfaceEncoder)
 * - Uses a separate input feeder thread for realtime pacing
 * - Tracks available input buffers in a queue
 * 
 * This allows copying data to an encoder buffer while another is in the encoding pipeline,
 * achieving better throughput while maintaining realtime pacing.
 */
public class AsyncBufferEncoder extends Encoder {
    private static final String TAG = "encapp.async_buffer_encoder";

    private Size mSourceResolution;
    private int mFrameSizeBytes;
    private boolean mUseImage = false;
    
    // Available input buffer indices
    private final ConcurrentLinkedQueue<Integer> mAvailableInputBuffers = new ConcurrentLinkedQueue<>();
    
    // Synchronization for completion
    private final Object mCompletionLock = new Object();
    private final AtomicBoolean mInputDone = new AtomicBoolean(false);
    private final AtomicBoolean mOutputDone = new AtomicBoolean(false);
    
    // Input feeder thread
    private InputFeederThread mInputFeeder;
    
    public AsyncBufferEncoder(Test test) {
        super(test);
        mStats = new Statistics("async buffer encoder", mTest);
    }

    @Override
    public String start() {
        Log.d(TAG, "** AsyncBufferEncoder - " + mTest.getCommon().getDescription() + " **");
        
        mTest = TestDefinitionHelper.updateBasicSettings(mTest);
        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();
        if (mTest.getInput().hasRealtime())
            mRealtime = mTest.getInput().getRealtime();

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();
        mSkipped = 0;
        mFramesAdded = 0;
        
        mSourceResolution = SizeUtils.parseXString(mTest.getInput().getResolution());
        int width = mSourceResolution.getWidth();
        int height = mSourceResolution.getHeight();
        
        PixFmt inputFmt = mTest.getInput().getPixFmt();
        mFrameSizeBytes = MediaCodecInfoHelper.frameSizeInBytes(inputFmt, width, height);
        mRefFramesizeInBytes = mFrameSizeBytes;

        // Initialize input reader
        String filepath = mTest.getInput().getFilepath();
        if (filepath.equals("fake_input")) {
            mFakeInputReader = new FakeInputReader();
            if (!mFakeInputReader.openFile(filepath, inputFmt, width, height)) {
                return "Could not initialize fake input";
            }
            mIsFakeInput = true;
            Log.d(TAG, "Using FakeInputReader for fake_input");
        } else {
            mYuvReader = new FileReader();
            String checkedPath = checkFilePath(filepath);
            if (!mYuvReader.openFile(checkedPath, inputFmt)) {
                return "Could not open file: " + checkedPath;
            }
            Log.d(TAG, "Using FileReader for: " + checkedPath);
        }

        MediaFormat mediaFormat;

        try {
            // Setup codec
            if (mTest.getConfigure().getMime().length() == 0) {
                try {
                    mTest = MediaCodecInfoHelper.setCodecNameAndIdentifier(mTest);
                } catch (Exception e) {
                    return e.getMessage();
                }
            }
            
            Log.d(TAG, "Create codec by name: " + mTest.getConfigure().getCodec());
            mStats.pushTimestamp("encoder.create");
            mCodec = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());
            mStats.pushTimestamp("encoder.create");

            mediaFormat = TestDefinitionHelper.buildMediaFormat(mTest);
            logMediaFormat(mediaFormat);
            setConfigureParams(mTest, mediaFormat);

            // Determine if we should use Image API
            int colorFormat = mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
            mUseImage = (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            Log.d(TAG, "useImage: " + mUseImage + ", colorFormat=" + colorFormat);

            // Set async callback handler BEFORE configure
            mCodec.setCallback(new AsyncEncoderCallbackHandler());
            
            mStats.pushTimestamp("encoder.configure");
            mCodec.configure(
                    mediaFormat,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            mStats.pushTimestamp("encoder.configure");
            
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

        // Setup timing
        mReferenceFrameRate = mTest.getInput().getFramerate();
        mKeepInterval = mReferenceFrameRate / mFrameRate;
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);
        mFrameTimeUsec = calculateFrameTimingUsec(mFrameRate);

        // Create muxer
        Log.d(TAG, "Create muxer");
        MediaFormat outputFormat = mCodec.getOutputFormat();
        mMuxerWrapper = createMuxerWrapper(mCodec, outputFormat);
        
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        if (isVP) {
            mVideoTrack = mMuxerWrapper.addTrack(outputFormat);
            mMuxerWrapper.start();
        }

        // Wait for synchronized start
        synchronized (this) {
            Log.d(TAG, "Wait for synchronized start");
            try {
                mInitDone = true;
                wait(WAIT_TIME_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Start encoding
        try {
            Log.d(TAG, "Start encoder (async mode)");
            mStats.pushTimestamp("encoder.start");
            mCodec.start();
            mStats.pushTimestamp("encoder.start");
        } catch (Exception ex) {
            Log.e(TAG, "Start failed: " + ex.getMessage());
            return "Start encoding failed";
        }

        mStats.start();

        // Start input feeder thread - handles realtime pacing
        mInputFeeder = new InputFeederThread();
        mInputFeeder.start();

        // Wait for encoding to complete
        synchronized (mCompletionLock) {
            while (!mOutputDone.get()) {
                try {
                    mCompletionLock.wait(100);
                    
                    // Log progress periodically
                    if (mFramesAdded % 100 == 0 && mFramesAdded > 0) {
                        Log.d(TAG, mTest.getCommon().getId() + " - AsyncBufferEncoder: frames: " + mFramesAdded +
                                " inframes: " + mInFramesCount +
                                " outframes: " + mOutFramesCount +
                                " current_time: " + mCurrentTimeSec);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        Log.d(TAG, "Encoding complete: " + mFramesAdded + " frames added, " + mOutFramesCount + " output");

        // Stop input feeder
        if (mInputFeeder != null) {
            mInputFeeder.stopFeeding();
            try {
                mInputFeeder.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted waiting for InputFeeder");
            }
        }

        // Stop DataWriter
        if (mDataWriter != null) {
            mDataWriter.stopWriter();
            try {
                mDataWriter.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted waiting for DataWriter");
            }
        }

        mStats.stop();

        // Cleanup
        if (mCodec != null) {
            try {
                mCodec.stop();
                mCodec.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping codec: " + e.getMessage());
            }
        }
        
        if (mMuxerWrapper != null) {
            try {
                mMuxerWrapper.release();
            } catch (IllegalStateException ise) {
                Log.e(TAG, "Error releasing muxer: " + ise.getMessage());
            }
        }

        if (mYuvReader != null) {
            mYuvReader.closeFile();
        }
        if (mFakeInputReader != null) {
            mFakeInputReader.closeFile();
        }

        return "";
    }

    /**
     * Input feeder thread - handles realtime pacing and buffer filling.
     * Waits for available input buffers AND the right time to submit frames.
     */
    private class InputFeederThread extends Thread {
        private volatile boolean mStopRequested = false;
        private int mCurrentLoop = 1;

        public void stopFeeding() {
            mStopRequested = true;
            interrupt();
        }

        @Override
        public void run() {
            Log.d(TAG, "InputFeeder started, realtime=" + mRealtime);
            
            while (!mStopRequested && !mInputDone.get()) {
                // Check if we're done
                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                    sendEndOfStream();
                    break;
                }

                // Wait for an available input buffer
                Integer bufferIndex = mAvailableInputBuffers.poll();
                if (bufferIndex == null) {
                    // No buffer available, wait a bit
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        if (mStopRequested) break;
                    }
                    continue;
                }

                // Realtime pacing - wait until it's time for the next frame
                if (mRealtime) {
                    sleepUntilNextFrame();
                }

                // Fill and queue the buffer
                int size = fillAndQueueBuffer(bufferIndex);
                
                if (size <= 0 && size != -2) {
                    // End of file or error - handle looping
                    if (mIsFakeInput) {
                        mFakeInputReader.closeFile();
                        mFakeInputReader.openFile(mTest.getInput().getFilepath(), 
                                mTest.getInput().getPixFmt(),
                                mSourceResolution.getWidth(), mSourceResolution.getHeight());
                    } else if (mYuvReader != null) {
                        mYuvReader.closeFile();
                        mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt());
                    }
                    mCurrentLoop++;
                    Log.d(TAG, "*** Loop ended start " + mCurrentLoop + " ***");
                    
                    if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, true)) {
                        sendEndOfStream();
                        break;
                    }
                    
                    // Return buffer to queue for retry
                    mAvailableInputBuffers.add(bufferIndex);
                }
            }
            
            Log.d(TAG, "InputFeeder stopped");
        }

        private void sendEndOfStream() {
            if (mInputDone.getAndSet(true)) {
                return; // Already sent EOS
            }
            
            // Wait for a buffer to send EOS - with timeout
            Integer bufferIndex = null;
            long startTime = System.currentTimeMillis();
            final long EOS_TIMEOUT_MS = 5000;
            
            while (!mStopRequested && bufferIndex == null) {
                bufferIndex = mAvailableInputBuffers.poll();
                if (bufferIndex == null) {
                    if (System.currentTimeMillis() - startTime > EOS_TIMEOUT_MS) {
                        Log.e(TAG, "Timeout waiting for input buffer to send EOS - forcing completion");
                        mOutputDone.set(true);
                        synchronized (mCompletionLock) {
                            mCompletionLock.notifyAll();
                        }
                        return;
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            
            if (bufferIndex != null) {
                long pts = computePresentationTimeUs(mPts, mInFramesCount, mRefFrameTime);
                try {
                    mCodec.queueInputBuffer(bufferIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    Log.d(TAG, "Queued EOS at frame " + mInFramesCount);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error queueing EOS: " + e.getMessage());
                    mOutputDone.set(true);
                    synchronized (mCompletionLock) {
                        mCompletionLock.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * Fill a buffer and queue it to the encoder.
     * @return size of data queued, -1 on error, -2 on skip
     */
    private int fillAndQueueBuffer(int index) {
        int read = 0;
        
        try {
            if (mUseImage) {
                android.media.Image image = mCodec.getInputImage(index);
                if (image != null) {
                    if (mIsFakeInput) {
                        read = mFakeInputReader.fillImage(image);
                    } else {
                        read = mYuvReader.fillImage(image);
                    }
                } else {
                    Log.e(TAG, "Failed to get input image");
                    return -1;
                }
            } else {
                ByteBuffer buffer = mCodec.getInputBuffer(index);
                if (buffer != null) {
                    buffer.clear();
                    if (mIsFakeInput) {
                        read = mFakeInputReader.fillBuffer(buffer, mFrameSizeBytes);
                    } else {
                        read = mYuvReader.fillBuffer(buffer, mFrameSizeBytes);
                    }
                } else {
                    Log.e(TAG, "Failed to get input buffer");
                    return -1;
                }
            }

            if (read <= 0) {
                return read;
            }

            long pts = computePresentationTimeUs(mPts, mInFramesCount, mRefFrameTime);
            mCurrentTimeSec = pts / 1000000.0f;
            
            // Runtime parameters
            setRuntimeParameters(mInFramesCount);
            
            // Frame dropping
            mDropNext = dropFrame(mInFramesCount);
            mDropNext |= dropFromDynamicFramerate(mInFramesCount);
            updateDynamicFramerate(mInFramesCount);
            
            if (mDropNext) {
                mSkipped++;
                mDropNext = false;
                mInFramesCount++;
                // Return buffer to queue
                mAvailableInputBuffers.add(index);
                return -2;
            }
            
            // Start encoding measurement
            mStats.startEncodingFrame(pts, mInFramesCount);
            
            // Queue the buffer
            mCodec.queueInputBuffer(index, 0, read, pts, 0);
            mFramesAdded++;
            mInFramesCount++;
            
            return read;
            
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error filling/queueing buffer: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Async callback handler for the encoder.
     */
    private class AsyncEncoderCallbackHandler extends MediaCodec.Callback {
        private MediaFormat mCurrentOutputFormat = null;
        private Dictionary<String, Object> mLatestFrameChanges = null;

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            // Always add buffers to the queue - InputFeeder needs them for EOS signaling
            // even after we stop adding frames
            mAvailableInputBuffers.add(index);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Codec config buffer
                    MediaFormat oformat = codec.getOutputFormat();
                    if (mWriteFile && mMuxerWrapper != null && mVideoTrack == -1) {
                        mVideoTrack = mMuxerWrapper.addTrack(oformat);
                        mMuxerWrapper.start();
                        Log.d(TAG, "Muxer started from codec config");
                    }
                    codec.releaseOutputBuffer(index, false);
                    
                } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // End of stream
                    Log.d(TAG, "Output EOS received");
                    codec.releaseOutputBuffer(index, false);
                    mOutputDone.set(true);
                    synchronized (mCompletionLock) {
                        mCompletionLock.notifyAll();
                    }
                    
                } else {
                    // Regular frame - stop encoding measurement
                    FrameInfo frameInfo = mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    ++mOutFramesCount;
                    
                    if (mLatestFrameChanges != null) {
                        frameInfo.addInfo(mLatestFrameChanges);
                        mLatestFrameChanges = null;
                    }

                    // Write to muxer
                    if (mMuxerWrapper != null && mVideoTrack != -1) {
                        ByteBuffer data = codec.getOutputBuffer(index);
                        if (data != null) {
                            mMuxerWrapper.writeSampleData(mVideoTrack, data, info);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "onOutputBufferAvailable error: " + e.getMessage());
                mOutputDone.set(true);
                synchronized (mCompletionLock) {
                    mCompletionLock.notifyAll();
                }
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "Codec error: " + e.getMessage());
            mInputDone.set(true);
            mOutputDone.set(true);
            synchronized (mCompletionLock) {
                mCompletionLock.notifyAll();
            }
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            Log.d(TAG, "Output format changed: " + format);
            
            if (mWriteFile && mMuxerWrapper != null && mVideoTrack == -1) {
                mVideoTrack = mMuxerWrapper.addTrack(format);
                mMuxerWrapper.start();
                Log.d(TAG, "Muxer started from format change");
            }
            
            if (Build.VERSION.SDK_INT >= 29 && mCurrentOutputFormat != null) {
                mLatestFrameChanges = mediaFormatComparison(mCurrentOutputFormat, format);
            }
            mCurrentOutputFormat = format;
        }
    }

    @Override
    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
        // Not used - we handle input via InputFeederThread
    }

    @Override
    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
        // Not used - we handle output in the callback directly
    }

    @Override
    public void release() {
        // Cleanup handled in start() method
    }

    @Override
    public void stopAllActivity() {
        mInputDone.set(true);
        mOutputDone.set(true);
        if (mInputFeeder != null) {
            mInputFeeder.stopFeeding();
        }
        synchronized (mCompletionLock) {
            mCompletionLock.notifyAll();
        }
    }
}
