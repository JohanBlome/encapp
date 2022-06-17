package com.facebook.encapp;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.Configure;
import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.DecoderConfigure;
import com.facebook.encapp.proto.DecoderRuntime;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FrameswapControl;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SurfaceTranscoder extends SurfaceEncoder {
    private final String TAG = "encapp.surfacetranscoder";
    private final SourceReader mSourceReader;
    MediaExtractor mExtractor;
    MediaCodec mDecoder;
    DecoderRuntime mDecoderRuntimeParams;
    OutputMultiplier mOutputMult = null;
    double mLoopTime = 0;
    int mCurrent_loop = 1;
    long mPts_offset = 0;
    long mLast_pts = 0;
    boolean mNoEncoding = false;
    private FrameswapControl mFrameSwapSurface;

    public SurfaceTranscoder(OutputMultiplier multiplier) {
        mOutputMult = multiplier;
        mSourceReader = new SourceReader();
    }

    public SurfaceTranscoder() {
        mOutputMult = new OutputMultiplier();
        mSourceReader = new SourceReader();
    }


    public String start(Test test) {
        Surface surface = null;
        SurfaceTexture surfaceTexture = null;
        mTest = test;
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

        checkRealtime();

        mFrameRate = mTest.getConfigure().getFramerate();
        Log.d(TAG, "Realtime = " + mRealtime + ", encoding to " + mFrameRate + " fps");
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();

        mYuvReader = new FileReader();
        if (!mYuvReader.openFile(mTest.getInput().getFilepath())) {
            return "\nCould not open file";
        }
        mExtractor = new MediaExtractor();
        MediaFormat inputFormat = null;
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
            checkMediaFormat(inputFormat);
            // Allow explicit decoder only for non encoding tests (!?)
         /*   if (noEncoding) {
                //TODO: throw error on failed lookup
                //TODO: fix decoder lookup
                test = setCodecNameAndIdentifier(test);
                Log.d(TAG, "Create codec by name: " + test.getConfigure().getCodec());
                mDecoder = MediaCodec.createByCodecName(test.getDecoderConfigure().getCodec());

            } else {*/
            Log.d(TAG, "Create decoder by type: " + inputFormat.getString(MediaFormat.KEY_MIME));
            mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            //}
        } catch (IOException e) {
            mExtractor.release();
            e.printStackTrace();
            return "Failed to create decoder";
        }
        mTest = TestDefinitionHelper.updateInputSettings(mTest, inputFormat);
        mTest = TestDefinitionHelper.checkAnUpdateBasicSettings(mTest);
        mStats = new Statistics("surface encoder", mTest);

        Size res = SizeUtils.parseXString(mTest.getInput().getResolution());
        int width = res.getWidth();
        int height = res.getHeight();
        mRefFramesizeInBytes = (int) (width * height * 1.5);

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

        if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            mReferenceFrameRate = (float) (inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
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
                if (mTest.getConfigure().getMime().length() == 0) {
                    Log.d(TAG, "codec id: " + mTest.getConfigure().getCodec());
                    //TODO: throw error on failed lookup
                    mTest = setCodecNameAndIdentifier(mTest);
                }
                Log.d(TAG, "Create encoder by name: " + mTest.getConfigure().getCodec());
                mCodec = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());
            } else {
                mStats.setCodec(Statistics.NA);
            }

            //Use same color settings as the input
            Log.d(TAG, "Check decoder settings");
            format = TestDefinitionHelper.buildMediaFormat(mTest);
            Log.d(TAG, "Check created encoder format");
            checkMediaFormat(format);
            Log.d(TAG, "Set color format");
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            if (!mNoEncoding) {
                mOutputMult.setName("ST_" + mTest.getInput().getFilepath() + "_enc-" + mTest.getConfigure().getCodec());
            } else {
                mOutputMult.setName("ST_" + mTest.getInput().getFilepath() + "_dec-" + inputFormat.getString(MediaFormat.KEY_MIME));
            }
            Size encodeResolution = Size.parseSize(mTest.getConfigure().getResolution());
            if (!mNoEncoding) {

                setConfigureParams(mTest, format);
                mCodec.setCallback(new EncoderCallbackHandler());
                mCodec.configure(
                        format,
                        null /* surface */,
                        null /* crypto */,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                Log.d(TAG, "Check input format after encoder is configured");
                checkMediaFormat(mCodec.getInputFormat());
                mFrameSwapSurface = mOutputMult.addSurface(mCodec.createInputSurface());
            }

            Log.d(TAG, "Check input format before config decoder");
            setDecoderConfigureParams(mTest, inputFormat);
            mDecoder.setCallback(new DecoderCallbackHandler());
            surface = mOutputMult.getInputSurface();
            if (surface == null) {
                surfaceTexture = new SurfaceTexture(false);
                surface = new Surface(surfaceTexture);
            }
            mDecoder.configure(inputFormat, surface, null, 0);

            Log.d(TAG, "Start decoder");
            mDecoder.start();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setDecoderName(mDecoder.getCodecInfo().getCanonicalName());
            } else {
                mStats.setDecoderName(mDecoder.getCodecInfo().getName());
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
                mMuxer = createMuxer(mCodec, format, true);

                // This is needed.
                boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
                if (isVP) {
                    mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
                    mMuxer.start();
                }
            }

            if (!mNoEncoding) {
                try {
                    Log.d(TAG, "Start encoder");
                    mCodec.start();
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
        mSourceReader.start();
        mStats.start();
        try {
            mSourceReader.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mStats.stop();
        Log.d(TAG, "Close muxer and streams");
        if (mMuxer != null) {
            try {
                mMuxer.release(); //Release calls stop
            } catch (IllegalStateException ise) {
                //Most likely mean that the muxer is already released. Stupid API
                Log.e(TAG, "Illegal state exception when trying to release the muxer: " + ise.getMessage());
            }
            mMuxer = null;
        }

        if (mOutputMult != null) {
            mOutputMult.stopAndRelease();
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
            }
            if (mDecoder != null) {
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
            }
        } catch (IllegalStateException iex) {
            Log.e(TAG, "Failed to shut down:" + iex.getLocalizedMessage());
        }

        if (mFrameSwapSurface != null && mOutputMult != null) {
            mOutputMult.removeFrameSwapControl(mFrameSwapSurface);
        }

        if (surfaceTexture != null) {
            surfaceTexture.release();
        }
        if (surface != null) {
            surface.release();
        }

        if (mExtractor != null)
            mExtractor.release();
        Log.d(TAG, "Stop writer");
        mDataWriter.stopWriter();
        return "";
    }


    public void setDecoderConfigureParams(Test test, MediaFormat format) {
        DecoderConfigure config = test.getDecoderConfigure();


        List<Configure.Parameter> params = test.getConfigure().getParameterList();
        for (Configure.Parameter param : params) {
            switch (param.getType().getNumber()) {
                case DataValueType.intType_VALUE:
                    format.setInteger(param.getKey(), Integer.parseInt(param.getValue()));
                    break;
                case DataValueType.stringType_VALUE:
                    format.setString(param.getKey(), param.getValue());
                    break;
            }
        }
    }

    public void setDecoderRuntimeParameters(Test test, int frame) {
        // go through all runtime settings and see which are due
        if (mDecoderRuntimeParams == null) return;
        Bundle bundle = new Bundle();

        for (DecoderRuntime.Parameter param : mDecoderRuntimeParams.getParameterList()) {
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

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
        if (encoder) {
            codec.releaseOutputBuffer(index, true);
        } else {
            long timestamp = info.presentationTimeUs;
            if (MainActivity.isStable()) {
                if (mFirstFrameTimestampUsec < 0) {
                    mFirstFrameTimestampUsec = timestamp;
                }
                // Buffer will be released when drawn
                mStats.stopDecodingFrame(timestamp);
                mInFramesCount++;
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
                    if (mDropNext) {
                        mSkipped++;
                        mDropNext = false;
                        codec.releaseOutputBuffer(index, false);
                    } else {
                        long ptsUsec = mPts + (timestamp - (long) mFirstFrameTimestampUsec);
                        mStats.startEncodingFrame(ptsUsec, mInFramesCount);
                        mFramesAdded++;
                        mOutputMult.newFrameAvailableInBuffer(codec, index, info);
                    }
                } else {
                    mCurrentTimeSec = timestamp/1000000.0;
                    mOutputMult.newFrameAvailableInBuffer(codec, index, info);
                }
            } else {
                codec.releaseOutputBuffer(index, false);
            }

        }
    }

    public OutputMultiplier getOutputMultiplier() {
        return mOutputMult;
    }

    private class SourceReader extends Thread {
        ConcurrentLinkedQueue<Integer> mDecoderBuffers = new ConcurrentLinkedQueue<>();
        boolean mDone = false;

        @Override
        public void run() {
            Log.d(TAG, "Start Source reader.");
            while (!mDone) {
                while (mDecoderBuffers.size() > 0 && !mDone) {
                    if (mInFramesCount % 100 == 0) {
                        if (mNoEncoding) {
                            Log.d(TAG, "Decoder, Frames: " + mFramesAdded + " - inframes: " + mInFramesCount +
                                    ", current loop: " + mCurrent_loop + ", current time: " + mCurrentTimeSec + " sec");
                        } else {
                            Log.d(TAG, "Transcoder, Frames: " + mFramesAdded + " - inframes: " + mInFramesCount +
                                    ", current loop: " + mCurrent_loop + ", current time: " + mCurrentTimeSec + " sec");
                        }
                    }

                    Integer index = mDecoderBuffers.poll();
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    ByteBuffer buffer = mDecoder.getInputBuffer(index);
                    int size = mExtractor.readSampleData(buffer, 0);
                    int flags = mExtractor.getSampleFlags();

                    double runtime = mCurrentTimeSec;
                    if (mFirstFrameTimestampUsec > 0) {
                        runtime -= mFirstFrameTimestampUsec/1000000.0;
                    }
                    if (doneReading(mTest, mInFramesCount, runtime, false)) {
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

                    setDecoderRuntimeParameters(mTest, mInFramesCount);
                    long pts = mExtractor.getSampleTime() + mPts_offset;
                    if (mRealtime) {
                        sleepUntilNextFrame();
                    }
                    mStats.startDecodingFrame(pts, size, flags);
                    if (size > 0) {
                        mDecoder.queueInputBuffer(index, 0, size, pts, flags);
                    }
                    if (mFirstFrameTimestampUsec > 0) {
                        runtime -= mFirstFrameTimestampUsec/1000000.0;
                    }
                    boolean eof = !mExtractor.advance();
                    if (eof) {
                        mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        mCurrent_loop++;
                        if (pts > mLast_pts) {
                            mPts_offset = pts;
                        } else {
                            mPts_offset = mLast_pts;
                            pts = mPts_offset;
                        }

                        mLoopTime = mPts_offset/1000000.0;
                        Log.d(TAG, "*** Loop ended starting " + mCurrent_loop + " - currentTime " + mCurrentTimeSec + " ***");
                        if (doneReading(mTest, mInFramesCount, runtime, true)) {
                            mDone = true;
                        }
                    }
                    mLast_pts = pts;
                }

                synchronized (mDecoderBuffers) {
                    if (mDecoderBuffers.size() == 0 && !mDone) {
                        try {
                            mDecoderBuffers.wait(WAIT_TIME_SHORT_MS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
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
}
