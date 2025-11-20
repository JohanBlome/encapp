package com.facebook.encapp;

import static com.facebook.encapp.utils.MediaCodecInfoHelper.mediaFormatComparison;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;

import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.DecoderRuntime;
import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.ClockTimes;
import com.facebook.encapp.utils.CodecCache;
import com.facebook.encapp.utils.Demuxer;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.FrameswapControl;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;
import com.facebook.encapp.utils.VsyncHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SurfaceTranscoder extends SurfaceEncoder {
    private static final String TAG = "encapp.surface_transcoder";

    private final SourceReader mSourceReader;
    MediaExtractor mExtractor;
    Demuxer mDemuxer;
    MediaCodec mDecoder;
    boolean mUseInternalDemux = false;
    DecoderRuntime mDecoderRuntimeParams;
    double mLoopTime = 0;
    int mCurrentLoop = 1;
    long mPtsOffset = 0;
    long mLastPtsUs = -1;
    boolean mNoEncoding = false;
    private FrameswapControl mFrameSwapSurface;
    Surface mSurface = null;
    boolean mDone = false;
    boolean mFirstDecodedFrame = true; // If false read ahead on the first frame

    public SurfaceTranscoder(Test test, OutputMultiplier multiplier, VsyncHandler vsyncHandler) {
        super(test, null, multiplier, vsyncHandler);
        mSourceReader = new SourceReader();
    }

    public String start() {
        mStats = new Statistics("surface encoder", mTest);
        if (mTest.getConfigure().hasEncode()) {
            mNoEncoding = !mTest.getConfigure().getEncode();
        }
        if (mNoEncoding) {
            Log.d(TAG, "**** Surface Decode, no encode ***");
            mStable = true;
        } else {
            Log.d(TAG, "**** Surface Transcode - " + mTest.getCommon().getDescription() + " ***");
        }

        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();
        if (mTest.hasDecoderRuntime())
            mDecoderRuntimeParams = mTest.getDecoderRuntime();
        if (mTest.hasTestSetup()) {
            // Default is that we should throttle from the start. Setting this to false means that
            // we are waiting for the first frame to arrive before throttling.
            mFirstDecodedFrame = !mTest.getTestSetup().getFirstFrameFastRead();
        }

        mFrameRate = mTest.getConfigure().getFramerate();
        Log.d(TAG, "Realtime = " + mRealtime + ", encoding to " + mFrameRate + " fps");
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();

        mYuvReader = new FileReader();
        if (!mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt())) {
            return "Could not open file";
        }

        mUseInternalDemux = mTest.hasTestSetup() && mTest.getTestSetup().hasInternalDemuxer() &&
                            mTest.getTestSetup().getInternalDemuxer();
        Log.d(TAG, "SurfaceTranscoder - Use internal demux: " + mUseInternalDemux);

        MediaFormat inputFormat = null;

        if (mUseInternalDemux) {
            String filepath = mTest.getInput().getFilepath();
            Log.d(TAG, "SurfaceTranscoder - Creating internal demuxer for file: " + filepath);
            mDemuxer = new Demuxer(filepath);
            Log.d(TAG, "SurfaceTranscoder - Demuxer object created, calling initialize()");

            try {
                if (!mDemuxer.initialize()) {
                    Log.e(TAG, "Failed to initialize internal demuxer");
                    return "Failed to initialize internal demuxer";
                }

                inputFormat = MediaFormat.createVideoFormat(
                        mDemuxer.isHEVC() ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC,
                        mDemuxer.getWidth(),
                        mDemuxer.getHeight());

                byte[] csd = mDemuxer.getCodecSpecificData();
                if (csd != null && csd.length > 0) {
                    inputFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                }

                if (mDemuxer.getFrameRate() > 0) {
                    inputFormat.setFloat(MediaFormat.KEY_FRAME_RATE, mDemuxer.getFrameRate());
                }

                Log.d(TAG, "Internal demuxer initialized: " + mDemuxer.getWidth() + "x" + mDemuxer.getHeight() +
                        ", " + mDemuxer.getFrameRate() + " fps, " + (mDemuxer.isHEVC() ? "HEVC" : "AVC"));
                Log.d(TAG, "Check parsed input format:");
                logMediaFormat(inputFormat);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize internal demuxer: " + e.getMessage());
                e.printStackTrace();
                if (mDemuxer != null) {
                    mDemuxer.close();
                }
                return "Failed to initialize internal demuxer";
            }
        } else {
            mExtractor = new MediaExtractor();
            try {
                mExtractor.setDataSource(mTest.getInput().getFilepath());
                int trackNum = 0;
                int tracks = mExtractor.getTrackCount();
                for (int track = 0; track < tracks; track++) {
                    inputFormat = mExtractor.getTrackFormat(track);
                    if (inputFormat.containsKey(MediaFormat.KEY_MIME) &&
                            inputFormat.getString(MediaFormat.KEY_MIME).toLowerCase(Locale.US).contains("video")) {
                        trackNum = track;
                    }
                }
                mExtractor.selectTrack(trackNum);
                inputFormat = mExtractor.getTrackFormat(trackNum);
                Log.d(TAG, "Extractor input format");
                if (inputFormat == null) {
                    Log.e(TAG, "no input format");
                    return "no input format";
                }
                Log.d(TAG, "Check parsed input format:");
                logMediaFormat(inputFormat);
            } catch (IOException e) {
                if (mExtractor != null) {
                    mExtractor.release();
                }
                Log.e(TAG, "Failed to initialize extractor: " + e.getMessage());
                e.printStackTrace();
                return "Failed to initialize extractor";
            }
        }

        try {
            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }
            // Allow explicit decoder only for non encoding tests (!?)
            String description = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mTest.getDecoderConfigure().hasCodec()) {
                description = mTest.getDecoderConfigure().getCodec();
            }
            mStats.pushTimestamp("decoder.create");
            // TODO: check decoder settings
            mDecoder = CodecCache.getCache().getDecoder(description);
            if (mDecoder == null) {
                mDecoder = CodecCache.getCache().createDecoder(mTest, inputFormat);
            }
            mStats.pushTimestamp("decoder.create");

        } catch (IOException e) {
            mExtractor.release();
            e.printStackTrace();
            return "Failed to create decoder";
        }
        mTest = TestDefinitionHelper.updateInputSettings(mTest, inputFormat);
        mTest = TestDefinitionHelper.updateBasicSettings(mTest);

        Size res = SizeUtils.parseXString(mTest.getInput().getResolution());
        int width = res.getWidth();
        int height = res.getHeight();
        // TODO(chema): this assumes 4:2:0 subsampling, and therefore YUV
        mRefFramesizeInBytes = (int) (width * height * 1.5);

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

        if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                mReferenceFrameRate = inputFormat.getFloat(MediaFormat.KEY_FRAME_RATE);
            } catch (ClassCastException e) {
                mReferenceFrameRate = (float) inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            }
        }
        if (mFrameRate <= 0) {
            mFrameRate = mReferenceFrameRate;
        }
        mKeepInterval = mReferenceFrameRate / mFrameRate;

        if (inputFormat.containsKey(MediaFormat.KEY_WIDTH)) {
            width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        }
        if (inputFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        }
        // If needed set the output
        Log.d(TAG, "Start decoder, output size is WxH = " + width + "x" + height);
        mOutputMult.confirmSize(width, height);
        MediaFormat format;
        try {
            if (!mNoEncoding) {
                mStats.pushTimestamp("encoder.create");
                //TODO: check settings
                mCodec = CodecCache.getCache().getEncoder(mTest.getConfigure().getCodec());
                if (mCodec == null) {
                    mCodec = CodecCache.getCache().createEncoder(mTest);
                }
                mStats.pushTimestamp("encoder.create");
            } else {
                mStats.setCodec(Statistics.NA);
            }
            // Makes the analysis easier by adding the resolution
            mTest = TestDefinitionHelper.updateEncoderResolution(mTest, width, height);
            format = TestDefinitionHelper.buildMediaFormat(mTest);
            format = TestDefinitionHelper.maybeUpdateBitrateFromDecoder(format, inputFormat);

            Log.d(TAG, "Check created encoder format");
            logMediaFormat(format);
            Log.d(TAG, "Set color format");
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            if (!mNoEncoding) {
                mOutputMult.setName("ST_" + mTest.getInput().getFilepath() + "_enc-" + mTest.getConfigure().getCodec());
            } else {
                mOutputMult.setName("ST_" + mTest.getInput().getFilepath() + "_dec-" + inputFormat.getString(MediaFormat.KEY_MIME));
            }

            if (!mNoEncoding) {
                setConfigureParams(mTest, format);
                mCodec.setCallback(new EncoderCallbackHandler());

                mStats.pushTimestamp("encoder.configure");
                mCodec.configure(
                        format,
                        null /* surface */,
                        null /* crypto */,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                mStats.pushTimestamp("encoder.configure");
                Log.d(TAG, "Check input format after encoder is configured");
                logMediaFormat(mCodec.getInputFormat());
                mFrameSwapSurface = mOutputMult.addSurface(mCodec.createInputSurface());
            }

            Log.d(TAG, "Check input format before config decoder");
            TestDefinitionHelper.setDecoderConfigureParams(mTest, inputFormat);
            mDecoder.setCallback(new DecoderCallbackHandler());
            mSurface = mOutputMult.getInputSurface();
            if (mSurface == null) {
                mSurfaceTexture = new SurfaceTexture(false);
                mSurface = new Surface(mSurfaceTexture);
            }
            mStats.pushTimestamp("decoder.configure");
            mDecoder.configure(inputFormat, mSurface, null, 0);
            mStats.pushTimestamp("decoder.configure");

            Log.d(TAG, "Start decoder");
            mStats.pushTimestamp("decoder.start");
            mDecoder.start();
            mStats.pushTimestamp("decoder.start");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setDecoder(mDecoder.getCodecInfo().getCanonicalName());
            } else {
                mStats.setDecoder(mDecoder.getCodecInfo().getName());
            }

            mStats.setDecoderMediaFormat(mDecoder.getInputFormat());
            if (!mNoEncoding) {
                mStats.setEncoderMediaFormat(mCodec.getInputFormat());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mStats.setCodec(mCodec.getCanonicalName());
                } else {
                    mStats.setCodec(mCodec.getName());
                }
            }


            if (!mNoEncoding) {
                Log.d(TAG, "Create muxer");
                mMuxerWrapper = createMuxerWrapper(mCodec, format);

                // This is needed.
                boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
                if (isVP) {
                    mVideoTrack = mMuxerWrapper.addTrack(mCodec.getOutputFormat());
                    mMuxerWrapper.start();
                }
            }

            if (!mNoEncoding) {
                try {
                    Log.d(TAG, "Start encoder");
                    mStats.pushTimestamp("encoder.start");
                    mCodec.start();
                    mStats.pushTimestamp("encoder.start");
                } catch (Exception ex) {
                    Log.e(TAG, "Start failed: " + ex.getMessage());
                    return "Start encoding failed";
                }
            }

        } catch (IOException iox) {
            Log.e(TAG, "Failed to create codec: " + iox.getMessage());
            return "Failed to create codec";
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: " + cex.getMessage());
            return "Failed to create codec";
        }
        mFrameTimeUsec = calculateFrameTimingUsec(mFrameRate);
        synchronized (this) {
            Log.d(TAG, "Wait for synchronized start");
            try {
                mInitDone = true;
                wait(WAIT_TIME_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Log.d(TAG, mTest.getCommon().getId() + " - Start source reader");
        mSourceReader.start();
        mStats.start();
        try {
            mSourceReader.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return "Interrupted exception.";
        } finally {
            Log.d(TAG, mTest.getCommon().getId() + " - Stop activity before ending");
            stopAllActivity();
        }
        return "";
    }



    public void setDecoderRuntimeParameters(Test mTest, int frame) {
        // go through all runtime settings and see which are due
        if (mDecoderRuntimeParams == null) return;
        Bundle bundle = new Bundle();

        for (Parameter param : mDecoderRuntimeParams.getParameterList()) {
            if (param.getFramenum() == frame) {
                switch (param.getType().getNumber()) {
                    case DataValueType.floatType_VALUE:
                        float fval = Float.parseFloat(param.getValue());
                        bundle.putFloat(param.getKey(), fval);
                        break;
                    case DataValueType.intType_VALUE:
                        int ival = TestDefinitionHelper.magnitudeToInt(param.getValue());
                        bundle.putInt(param.getKey(), ival);
                        break;
                    case DataValueType.longType_VALUE:
                        long lval = Long.parseLong(param.getValue());
                        bundle.putLong(param.getKey(), lval);
                        break;
                    case DataValueType.stringType_VALUE:
                        bundle.putString(param.getKey(), param.getValue());
                        break;
                    default:
                        ///Should not be here
                }
            }
        }

        if (bundle.keySet().size() > 0 && mDecoder != null) {
            mDecoder.setParameters(bundle);
        }
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
        try {
            if (encoder) {
                codec.releaseOutputBuffer(index, true);
            } else {
                mSourceReader.addBuffer(index);
            }
        } catch (IllegalStateException iex) {
            //Not important
        }
    }

    long mFirstFrameSystemTimeNsec = 0;
    long mDropcount = 0;
    MediaFormat currentMediaFormat;
    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
        if (encoder) {
            codec.releaseOutputBuffer(index, true);
        } else {
            long timestamp = info.presentationTimeUs;

            if (MainActivity.isStable()) {
                FrameInfo frameInfo = mStats.stopDecodingFrame(timestamp);
                if (mFirstFrameTimestampUsec < 0) {
                    mFirstFrameTimestampUsec = timestamp;
                    mFirstFrameSystemTimeNsec = ClockTimes.currentTimeNs();
                }
                mFirstDecodedFrame = true;
                // Buffer will be released when drawn
                MediaFormat newFormat = codec.getOutputFormat();
                Dictionary<String, Object> mediaFormatInfo = mediaFormatComparison(currentMediaFormat, newFormat);
                if (frameInfo != null) {
                    frameInfo.addInfo(mediaFormatInfo);
                }
                currentMediaFormat = newFormat;
                mInFramesCount++;
                long diffUsec = (long)(ClockTimes.currentTimeUs()- mFirstFrameSystemTimeNsec/1000);
                if (!mNoEncoding) {
                    if (mFirstFrameTimestampUsec == timestamp ) {
                        // Request key frame
                        Log.d(TAG, "Request key frame");
                        Bundle bundle = new Bundle();
                        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        if (mCodec != null) {
                            mCodec.setParameters(bundle);
                        }
                    }
                    setRuntimeParameters(mInFramesCount);
                    mDropNext = dropFrame(mInFramesCount);
                    mDropNext |= dropFromDynamicFramerate(mInFramesCount);
                    updateDynamicFramerate(mInFramesCount);
                    //Check time, if to far off drop frame, minimize the drops so something is show (when show is true)
                    long ptsUsec = mPts + (timestamp - (long) mFirstFrameTimestampUsec);

                    mCurrentTimeSec = diffUsec/1000000.0f;
                    if (mRealtime &&  mFirstFrameSystemTimeNsec > 0 && (diffUsec - ptsUsec) > mFrameTimeUsec * 2) {
                        if (mDropcount < mFrameRate) {
                            if (!mNoEncoding) {
                                Log.d(TAG, mTest.getCommon().getId() + " - drop frame caused by slow decoder: " + (diffUsec - ptsUsec)/1000 + " ms"  );
                            }
                            mDropNext = true;
                            mDropcount++;
                        } else {
                            mDropcount = 0;
                        }
                    }
                    if (mDropNext) {
                        mSkipped++;
                        mDropNext = false;
                        codec.releaseOutputBuffer(index, false);
                    } else {
                        mStats.startEncodingFrame(ptsUsec, mInFramesCount);
                        mFramesAdded++;
                        mOutputMult.newFrameAvailableInBuffer(codec, index, info);
                    }
                } else {
                    mCurrentTimeSec = diffUsec/1000000.0f;
                    //info.presentationTimeUs = (long)(mCurrentTimeSec * 1000000);
                    long diff =(diffUsec - timestamp);
                    if (mFirstFrameSystemTimeNsec > 0 && (diffUsec - timestamp) > mFrameTimeUsec * 2) {
                        if (mDropcount < mFrameRate) {
                            if (!mNoEncoding) {
                                Log.d(TAG, mTest.getCommon().getId() + " - drop frame caused by slow decoder");
                            }
                            mDropNext = false;//true;
                            mDropcount++;
                        } else {
                            mDropcount = 0;
                            info.presentationTimeUs = (long)(mCurrentTimeSec * 1e6);
                        }
                    }
                    //mCurrentTimeSec = timestamp/1000000.0;
                    if (mDropNext) {
                        codec.releaseOutputBuffer(index, false);
                    } else {
                        mOutputMult.newFrameAvailableInBuffer(codec, index, info);
                    }
                }
            } else {
                codec.releaseOutputBuffer(index, false);
            }

        }
    }

    public OutputMultiplier getOutputMultiplier() {
        return mOutputMult;
    }

    @Override
    public void vsync(long frameTimeNs) {
        synchronized (mSyncLock) {
            mVsyncTimeNs = frameTimeNs;
            mSyncLock.notifyAll();
        }
    }

    private class SourceReader extends Thread {
        ConcurrentLinkedQueue<Integer> mDecoderBuffers = new ConcurrentLinkedQueue<>();

        @Override
        public void run() {
            Dictionary<String, Object> latestFrameChanges;
            Log.d(TAG, "Start Source reader.");
            while (!mDone) {
                while (mDecoderBuffers.size() > 0 && !mDone) {
                    if (mInFramesCount % 100 == 0 && MainActivity.isStable()) {
                        Log.d(TAG, mTest.getCommon().getId() + " - "  + (mNoEncoding ? "Decoder: " : "Transcoder: ") +
                                "frames: " + mFramesAdded +
                                " inframes: " + mInFramesCount +
                                " current_loop: " + mCurrentLoop +
                                " current_time: " + mCurrentTimeSec);
                    }

                    Integer index = mDecoderBuffers.poll();
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    ByteBuffer buffer = mDecoder.getInputBuffer(index);
                    int size = -1;
                    int flags = 0;
                    long ptsUsec = 0;

                    if (mUseInternalDemux) {
                        Demuxer.Frame frame = new Demuxer.Frame();
                        if (mDemuxer.getNextFrame(frame)) {
                            buffer.clear();
                            buffer.put(frame.data);
                            size = frame.size;
                            ptsUsec = frame.timestamp + mPtsOffset;
                            flags = frame.isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        } else {
                            size = -1;
                        }
                    } else {
                        size = mExtractor.readSampleData(buffer, 0);
                        flags = mExtractor.getSampleFlags();
                        ptsUsec = mExtractor.getSampleTime() + mPtsOffset;
                    }

                    double runtime = mCurrentTimeSec;
                    if (mFirstFrameTimestampUsec > 0) {
                        runtime -= mFirstFrameTimestampUsec/1000000.0;
                    }

                    if (doneReading(mTest, mYuvReader, mInFramesCount, runtime, false)) {
                        flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        mDone = true;
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Decoder eos!!!");
                        mDone = true;
                    }

                    if (mDone) {
                        flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    }

                    if (mDone) {
                        continue;
                    }
                    setDecoderRuntimeParameters(mTest, mInFramesCount);
                    if (size > 0) {
                        mStats.startDecodingFrame(ptsUsec, size, flags);
                        try {
                            mDecoder.queueInputBuffer(index, 0, size, ptsUsec, flags);
                        } catch (IllegalStateException ise) {
                            // Ignore this
                        }
                    } else {
                        mDecoderBuffers.add(index);
                    }
                    if (mFirstFrameTimestampUsec > 0) {
                        runtime -= mFirstFrameTimestampUsec/1000000.0;
                    }
                    boolean eof = false;
                    if (mUseInternalDemux) {
                        eof = mDemuxer.isEOS();
                    } else {
                        eof = !mExtractor.advance();
                    }
                    if (eof) {
                        if (mUseInternalDemux) {
                            mDemuxer.reset();
                        } else {
                            mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        }
                        mCurrentLoop++;
                        if (ptsUsec > mLastPtsUs) {
                            mPtsOffset = ptsUsec;
                        } else {
                            mPtsOffset = mLastPtsUs;
                            ptsUsec = mPtsOffset;
                        }

                        mLoopTime = mPtsOffset /1000000.0;
                        Log.d(TAG, "*** Loop ended starting " + mCurrentLoop + " - currentTime " + mCurrentTimeSec + " ***");
                        if (doneReading(mTest, mYuvReader, mInFramesCount, runtime, true)) {
                            mDone = true;
                        }
                    }
                    mLastPtsUs = ptsUsec;
                    if (mRealtime && mFirstDecodedFrame) sleepUntilNextFrame();
                }
            }
        }


        protected void sleepUntilNextFrame() {
            long now = ClockTimes.currentTimeMs();
            long sleepTimeMs = (long)(mFrameTimeUsec/1000 - (now - mLastTimeMs));
            if (sleepTimeMs < 0) {
                // We have been delayed. Run forward.
                sleepTimeMs = 0;
            }
            if (sleepTimeMs > 0) {
                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mLastTimeMs = ClockTimes.currentTimeMs();
        }

        public void addBuffer(int id) {
            mDecoderBuffers.add(id);
            synchronized (mDecoderBuffers) {
                mDecoderBuffers.notifyAll();
            }
        }
    }

    public void stopAllActivity(){
        synchronized (mStopLock) {
            if (mStats.getDecodedFrameCount() > mStats.getEncodedFrameCount()) {
                Log.d(TAG, "Give me a sec, waiting for last encodings dec: " + mStats.getDecodedFrameCount() + " > enc: " + mStats.getEncodedFrameCount());
                try {
                    mStopLock.wait(WAIT_TIME_SHORT_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mDone = true;
            mStats.stop();
            Log.d(TAG, mTest.getCommon().getId() + " - SurfaceTranscoder done - close down");
            Log.d(TAG, "Close muxer and streams: " + getStatistics().getId());
            if (mMuxerWrapper != null) {
                try {
                    mMuxerWrapper.release(); //Release calls stop
                } catch (IllegalStateException ise) {
                    //Most likely mean that the muxer is already released. Stupid API
                    Log.e(TAG, "Illegal state exception when trying to release the muxer: " + ise.getMessage());
                }
                mMuxerWrapper = null;
            }

            try {
                if (mCodec != null) {
                    mCodec.flush();
                    // Give it some time
                    synchronized (this) {
                        try {
                            this.wait(WAIT_TIME_SHORT_MS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    mCodec.stop();
                    mCodec.reset();
                    CodecCache.getCache().returnEncoder(mCodec);
                    mCodec = null;
                }
                if (mDecoder != null) {
                    Log.d(TAG, mTest.getCommon().getId() + " - FLUSH DECODER");
                    mDecoder.flush();
                    // Give it some time
                    synchronized (this) {
                        try {
                            this.wait(WAIT_TIME_SHORT_MS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    mDecoder.stop();
                    mDecoder.reset();
                    CodecCache.getCache().returnDecoder(mDecoder);
                    mDecoder = null;
                }
            } catch (IllegalStateException iex) {
                Log.e(TAG, "Illegal state Failed to shut down:" + iex.getStackTrace().toString());
            }

            if (mVsyncHandler != null)
                mVsyncHandler.removeListener(this);

            if (mFrameSwapSurface != null && mOutputMult != null) {
                mOutputMult.removeFrameSwapControl(mFrameSwapSurface);
                mOutputMult.stopAndRelease();
            }

            if (mSurfaceTexture != null) {
                mSurfaceTexture.release();
            }
            if (mSurface != null) {
                mSurface.release();
            }

            if (mExtractor != null)
                mExtractor.release();
            if (mDemuxer != null)
                mDemuxer.close();
            Log.d(TAG, "Stop writer");
            mDataWriter.stopWriter();
        }
    }

    public void release() {
        Log.d(TAG, (mNoEncoding ? "Decoder: " : "Transcoder: ") +
                "\nRelease output for " + mTest.getCommon().getId() + " " +
                "frames: " + mFramesAdded +
                " inframes: " + mInFramesCount +
                " current_loop: " + mCurrentLoop +
                " current_time: " + mCurrentTimeSec);

        mOutputMult.stopAndRelease();
    }


}
