package com.facebook.encapp;

import static com.facebook.encapp.utils.MediaCodecInfoHelper.mediaFormatComparison;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.DecoderRuntime;
import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.ClockTimes;
import com.facebook.encapp.utils.CodecCache;
import com.facebook.encapp.utils.Demuxer;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FrameBuffer;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.FrameswapControl;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;
import com.facebook.encapp.utils.VsyncHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BufferTranscoder extends Encoder  {
    private static final String TAG = "encapp.buffer_transcoder";

    private final SourceReader mSourceReader;
    private final EncoderWriter mEncoderWriter;
    private final Stack<Integer> mEncoderInputBuffers = new Stack<>();
    MediaExtractor mExtractor;
    Demuxer mDemuxer;
    MediaCodec mDecoder;
    boolean mUseInternalDemux = false;
    DecoderRuntime mDecoderRuntimeParams;
    double mLoopTime = 0;
    int mCurrentLoop = 1;
    long mPtsOffset = 0;
    long mLastPtsUs = -1;
    private FrameswapControl mFrameSwapSurface;
    private VsyncHandler mVsyncHandler;
    Object mSyncLock = new Object();
    long mVsyncTimeUs = 0;
    long mFirstSynchUs = -1;
    boolean mDone = false;
    Object mStopLock = new Object();

    int mWidth = -1;
    int mHeight = -1;
    int mInputWidth = -1;
    int mInputHeight = -1;
    int mXStride = -1;
    int mYStride = -1; // Plane alignment

    public BufferTranscoder(Test test) {
        super(test);
        mSourceReader = new SourceReader();
        mEncoderWriter = new EncoderWriter();

        mStats = new Statistics("buffer encoder", mTest);
    }


    public String start() {
        Log.d(TAG, "**** Buffer Transcode - " + mTest.getCommon().getDescription() + " ***");

        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();
        if (mTest.hasDecoderRuntime())
            mDecoderRuntimeParams = mTest.getDecoderRuntime();

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = true; // No point in being here unless we write...

        mYuvReader = new FileReader();
        if (!mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt())) {
            return "Could not open file";
        }

        mUseInternalDemux = mTest.hasTestSetup() && mTest.getTestSetup().hasInternalDemuxer() &&
                            mTest.getTestSetup().getInternalDemuxer();
        Log.d(TAG, "BufferTranscoder - Use internal demux: " + mUseInternalDemux);

        // Get source track
        MediaFormat inputFormat = null;

        if (mUseInternalDemux) {
            String filepath = mTest.getInput().getFilepath();
            Log.d(TAG, "BufferTranscoder - Creating internal demuxer for file: " + filepath);
            mDemuxer = new Demuxer(filepath);
            Log.d(TAG, "BufferTranscoder - Demuxer object created, calling initialize()");

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
                int tracks = mExtractor.getTrackCount();
                int sourceTrack = 0;
                for (int track = 0; track < tracks; track++) {
                    inputFormat = mExtractor.getTrackFormat(track);
                    if (inputFormat.containsKey(MediaFormat.KEY_MIME) &&
                            inputFormat.getString(MediaFormat.KEY_MIME).toLowerCase(Locale.US).contains("video")) {
                        sourceTrack = track;
                    }
                }
                mExtractor.selectTrack(sourceTrack);
                inputFormat = mExtractor.getTrackFormat(sourceTrack);
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

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

        if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                mReferenceFrameRate = inputFormat.getFloat(MediaFormat.KEY_FRAME_RATE);
            } catch (ClassCastException e) {
                mReferenceFrameRate = (float) inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            }
        }
        if (inputFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            mInputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        }
        if (inputFormat.containsKey(MediaFormat.KEY_WIDTH)) {
            mInputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        }
        Size res = SizeUtils.parseXString(mTest.getInput().getResolution());
        mWidth = res.getWidth();
        mHeight = res.getHeight();
        Log.d(TAG, "mWidht x mHeight " + mWidth + " x " + mHeight);
        if (mWidth <= 0) {
            mWidth = mInputWidth;
        }
        if (mHeight <= 0) {
            mHeight = mInputHeight;
        }
        // TODO(chema): this assumes 4:2:0 subsampling, and therefore YUV
        mRefFramesizeInBytes = (int) (mWidth * mHeight * 1.5);

        int align = 32;
        mXStride = (mWidth % align != 0)? (mWidth/align + 1) * align: mWidth;
        mYStride = (mHeight % align != 0)? (mHeight/align + 1) * align: mHeight;

        if (mFrameRate <= 0) {
            mFrameRate = mReferenceFrameRate;
        }
        mKeepInterval = mReferenceFrameRate / mFrameRate;


        // If needed set the output
        Log.d(TAG, "Start decoder, output size is WxH = " + mWidth + "x" + mHeight);
        MediaFormat format;
        if (mTest.getConfigure().getMime().length() == 0) {
            Log.d(TAG, "codec id: " + mTest.getConfigure().getCodec());
            try {
                mTest = MediaCodecInfoHelper.setCodecNameAndIdentifier(mTest);
            } catch (Exception e) {
                return e.getMessage();
            }
            Log.d(TAG, "codec: " + mTest.getConfigure().getCodec() + " mime: " + mTest.getConfigure().getMime());
        }
        Log.d(TAG, "Create encoder by name: " + mTest.getConfigure().getCodec());
        try {
            mStats.pushTimestamp("encoder.create");
            mCodec = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());
            mStats.pushTimestamp("encoder.create");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        //Use same color settings as the input
        Log.d(TAG, "Check decoder settings");
        format = TestDefinitionHelper.buildMediaFormat(mTest);
        format = TestDefinitionHelper.maybeUpdateBitrateFromDecoder(format, inputFormat);
        Log.d(TAG, "Check created encoder format");
        logMediaFormat(format);

        int defaultColor = format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
        Log.d(TAG, "Decoder default color: " + defaultColor);
        setConfigureParams(mTest, format);
        //TODO: color handling

        String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        int matchingColor = getMatchingColor(mCodec, mDecoder, defaultColor);
        inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, matchingColor);
        Log.d(TAG, "Check input format before config decoder");
        TestDefinitionHelper.setDecoderConfigureParams(mTest, inputFormat);
        mCodec.setCallback(new EncoderCallbackHandler());
        mDecoder.setCallback(new DecoderCallbackHandler());

        mStats.pushTimestamp("decoder.configure");
        mDecoder.configure(inputFormat, null, null, 0);
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
        //mStats.setEncoderMediaFormat(mCodec.getInputFormat());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mStats.setCodec(mCodec.getCanonicalName());
        } else {
            mStats.setCodec(mCodec.getName());
        }

    try {
        MediaFormat mediaFormat = mDecoder.getOutputFormat();
        Log.d(TAG, "Decoder output format");
        logMediaFormat(mediaFormat);
        mediaFormat = TestDefinitionHelper.mergeEncoderSettings(mTest, mediaFormat);
        //TODO: color handling
        // The decoder must output same colorformat as input. QC hw encoder does not take same pix fmt android sw decoder.
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, matchingColor);
        mStats.pushTimestamp("encoder.configure");
        mCodec.configure(
                mediaFormat,
                null /* surface */,
                null /* crypto */,
                MediaCodec.CONFIGURE_FLAG_ENCODE);
        mStats.pushTimestamp("encoder.configure");
        Log.d(TAG, "Check input format after encoder is configured");
        logMediaFormat(mCodec.getInputFormat());

        mMuxerWrapper = createMuxerWrapper(mCodec, mediaFormat);
        try {
            Log.d(TAG, "Start encoder");
            mStats.pushTimestamp("encoder.start");
            mCodec.start();
            mStats.pushTimestamp("encoder.start");
        } catch (Exception ex) {
            Log.e(TAG, "Start failed: " + ex.getMessage());
            //return "Start encoding failed";
        }
            // This is needed.
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: " + cex.getMessage());
            //return "Failed to create codec";
        }
        mFrameTimeUsec = calculateFrameTimingUsec(mFrameRate);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
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
        mEncoderWriter.start();
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


    public int getMatchingColor(MediaCodec first, MediaCodec second, int preferred) {
        MediaCodecInfo encInfo = mCodec.getCodecInfo();
        MediaCodecInfo decInfo = mDecoder.getCodecInfo();
        MediaCodecInfo.CodecCapabilities encCaps;
        MediaCodecInfo.CodecCapabilities decCaps;
        String encType = encInfo.getSupportedTypes()[0];
        String decType = decInfo.getSupportedTypes()[0];
        encCaps = encInfo.getCapabilitiesForType(encType);
        decCaps = decInfo.getCapabilitiesForType(decType);

        int match = -1;
        for (int col:encCaps.colorFormats) {
            for (int decCol:decCaps.colorFormats) {
                if (( col == decCol)) {
                    match = col;
                }
                if (match == preferred) {
                    return match;
                }
            }
        }

        if (match == -1)
            Log.e(TAG, "Failed to find a matching pixel format");
        return match;
    }


    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
        try {
            if (encoder) {
                mEncoderInputBuffers.push(index);
                synchronized (mEncoderInputBuffers) {
                    mEncoderInputBuffers.notifyAll();
                }
            } else {
                mSourceReader.addBuffer(index);
            }
        } catch (IllegalStateException iex) {
            //Not important
        }
    }

    long mFirstFrameSystemTimeUsec = 0;
    long mDropcount = 0;
    MediaFormat currentMediaFormat;
    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
        if (encoder) {
            // Should not get here _ever_. Handled by data writer
        } else {
            long timestamp = info.presentationTimeUs;

            if (MainActivity.isStable()) {
                FrameInfo frameInfo = mStats.stopDecodingFrame(timestamp);
                if (mFirstFrameTimestampUsec < 0) {
                    mFirstFrameTimestampUsec = timestamp;
                    mFirstFrameSystemTimeUsec = ClockTimes.currentTimeUs();
                }
                // Buffer will be released when drawn
                MediaFormat newFormat = codec.getOutputFormat();
                Dictionary<String, Object> mediaFormatInfo = mediaFormatComparison(currentMediaFormat, newFormat);
                if (frameInfo != null) {
                    frameInfo.addInfo(mediaFormatInfo);
                }
                currentMediaFormat = newFormat;
                mInFramesCount++;
                long diffUsec = ClockTimes.currentTimeUs() - mFirstFrameSystemTimeUsec;
                setRuntimeParameters(mInFramesCount);
                mDropNext = dropFrame(mInFramesCount);
                mDropNext |= dropFromDynamicFramerate(mInFramesCount);
                updateDynamicFramerate(mInFramesCount);
                //Check time, if to far off drop frame, minimize the drops so something is show.
                long ptsUsec = mPts + (timestamp - (long) mFirstFrameTimestampUsec);

                if (mRealtime &&  mFirstFrameSystemTimeUsec > 0 && (diffUsec - ptsUsec) > mFrameTimeUsec * 2) {
                    if (mDropcount < mFrameRate) {
                        Log.d(TAG, mTest.getCommon().getId() + " - drop frame caused by slow decoder");
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

                    ByteBuffer outputBuf = mDecoder.getOutputBuffer(index);
                    if (index >= 0 && outputBuf != null) {

                        // add buffer to encoderwriter
                        mEncoderWriter.addBuffer(new FrameBuffer(mDecoder, index, info));
                    }
                }
            } else {
                codec.releaseOutputBuffer(index, false);
            }

        }
    }

    private class SourceReader extends Thread {
        ConcurrentLinkedQueue<Integer> mDecoderBuffers = new ConcurrentLinkedQueue<>();

        @Override
        public void run() {
            Dictionary<String, Object> latestFrameChanges;
            while (!mDone) {
                while (mDecoderBuffers.size() > 0 && !mDone) {
                    if (mInFramesCount % 100 == 0 && MainActivity.isStable()) {
                        Log.d(TAG, mTest.getCommon().getId()  +
                                " frames: " + mFramesAdded +
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

                    if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec /* runtime*/, false)) {
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
                    if (mRealtime) {
                        // Limit the pace of incoming frames to the framerate
                        sleepUntilNextFrameSynched();
                    }
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
                    /*
                    if (mFirstFrameTimestampUsec > 0) {
                        runtime -= mFirstFrameTimestampUsec/1000000.0;
                    }*/
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
                        if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec /*runtime*/, true)) {
                            mDone = true;
                        }
                    }
                    mLastPtsUs = ptsUsec;
                }
            }
        }

        public void addBuffer(int id) {
            mDecoderBuffers.add(id);
            synchronized (mDecoderBuffers) {
                mDecoderBuffers.notifyAll();
            }
        }
    }


    private class EncoderWriter extends Thread {
        ConcurrentLinkedQueue<FrameBuffer> mDecoderBuffers = new ConcurrentLinkedQueue<>();
        boolean mDone = false;

        @Override
        public void run() {
            while (!mDone) {
                if (mDecoderBuffers.size() == 0) {
                    synchronized (mDecoderBuffers) {
                        try {
                            mDecoderBuffers.wait();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                FrameBuffer frameBuffer = mDecoderBuffers.remove();
                Integer encBufferIndex = -1;
                synchronized (mEncoderInputBuffers) {
                    if (mEncoderInputBuffers.size() == 0) {
                        try {
                            mEncoderInputBuffers.wait();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    if (mEncoderInputBuffers.size() > 0) {
                        encBufferIndex = mEncoderInputBuffers.pop();
                    } else {
                        encBufferIndex = -1;
                    }

                    if (encBufferIndex >= 0) {
                        ByteBuffer inputBuffer = mCodec.getInputBuffer(encBufferIndex);

                        // We proably cannot change the size if it is wrong (needs to be done when configuring the encoder
                        ByteBuffer decoderBuffer = mDecoder.getOutputBuffer(frameBuffer.mBufferId);
                        if ( decoderBuffer.limit() == inputBuffer.limit()) {
                            inputBuffer.put(decoderBuffer);
                        } else {
                            // We need to align the planes if Qualcomm, worst case copy line by line
                            //inputBuffer.put(decoderBuffer);
                            if (mXStride != mWidth) {
                                // Copy per line
                                Log.d(TAG, "X stride not implemented (yet)");
                            }

                            // If the buffer sizes are the same stringin is taken car of elsewhere
                            if (mYStride != mHeight) {
                                // Align luma plane
                                int lumaAlignedSize = mXStride * mYStride;
                                int lumaSize = mWidth * mHeight;
                                int limit = decoderBuffer.limit();
                                decoderBuffer.limit(lumaSize);
                                inputBuffer.put(decoderBuffer);
                                decoderBuffer.position(lumaSize);
                                decoderBuffer.limit(limit);
                                inputBuffer.position(lumaAlignedSize);
                                inputBuffer.put(decoderBuffer);
                            }
                        }
                        mCodec.queueInputBuffer(encBufferIndex, 0 /* offset */, decoderBuffer.limit(), frameBuffer.getTimestampUs() /* timeUs */, frameBuffer.mInfo.flags);

                        mDecoder.releaseOutputBuffer(frameBuffer.mBufferId, false);
                    }
                }
            }
        }

        public void addBuffer(FrameBuffer frameBuffer) {
            synchronized (mDecoderBuffers) {
                mDecoderBuffers.add(frameBuffer);
                mDecoderBuffers.notifyAll();
            }
        }

        public void stopWriter() {
            mDone = true;
        }
    }




    public  long sleepUntilNextFrameSynched() {
        if (mFirstSynchUs == -1) {
            Log.d(TAG, "Set first sync: "+ mVsyncTimeUs);
            mFirstSynchUs = mVsyncTimeUs;
        }
        if (mLastPtsUs == -1) {
            Log.d(TAG,"First time - no wait");
        } else {
            synchronized (mSyncLock) {
                long startTime = mVsyncTimeUs;

                try {
                    long videoDiffMs = (long) (mLastPtsUs - mCurrentTimeSec * 1000000)/1000;
                    while (videoDiffMs > 0) {
                        // Wait for next vsync and check time difference again.
                        mSyncLock.wait(WAIT_TIME_MS);
                        videoDiffMs = (long) (mLastPtsUs - mCurrentTimeSec * 1000000)/1000;
                    }
                    mLastTimeMs = mVsyncTimeUs;

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        return mVsyncTimeUs;
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
            mEncoderWriter.stopWriter();
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
                    mCodec.release();
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
                    mDecoder.release();
                    mDecoder = null;
                }
            } catch (IllegalStateException iex) {
                Log.e(TAG, "Illegal state Failed to shut down:" + iex.getStackTrace().toString());
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
        Log.d(TAG,"\nRelease output for " + mTest.getCommon().getId() + " " +
                "frames: " + mFramesAdded +
                " inframes: " + mInFramesCount +
                " current_loop: " + mCurrentLoop +
                " current_time: " + mCurrentTimeSec);

    }
}
