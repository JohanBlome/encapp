package com.facebook.encapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.PixFmt;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.CliSettings;
import com.facebook.encapp.utils.ClockTimes;
import com.facebook.encapp.utils.FakeGLRenderer;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FpsMeasure;
import com.facebook.encapp.utils.FrameswapControl;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;
import com.facebook.encapp.utils.VsyncHandler;
import com.facebook.encapp.utils.VsyncListener;
import com.facebook.encapp.utils.grafika.Texture2dProgram;

import java.io.IOException;
import java.lang.NullPointerException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;


/**
 * Created by jobl on 2018-02-27.
 */

class SurfaceEncoder extends Encoder implements VsyncListener {
    private static final String TAG = "encapp.surface_encoder";

    Bitmap mBitmap = null;
    Context mContext;
    SurfaceTexture mSurfaceTexture;
    boolean mIsRgbaSource = false;
    boolean mIsCameraSource = false;
    boolean mIsFakeInput = false;
    FakeGLRenderer mFakeGLRenderer;  // GL-based fake input (fast!)
    FakeGLRenderer.PatternType mFakeInputPatternType = FakeGLRenderer.PatternType.TEXTURE;  // Default pattern
    boolean mUseCameraTimestamp = true;
    OutputMultiplier mOutputMult;
    Bundle mKeyFrameBundle;
    private Allocation mYuvIn;
    private Allocation mYuvOut;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private FrameswapControl mFrameSwapSurface;
    protected VsyncHandler mVsyncHandler;
    Object mSyncLock = new Object();
    long mVsyncTimeNs = 0;
    long mFirstSynchNs = -1;

    Object mStopLock = new Object();

    public SurfaceEncoder(Test test, Context context, OutputMultiplier multiplier, VsyncHandler vsyncHandler) {
        super(test);
        mOutputMult = multiplier;
        mContext = context;
        mVsyncHandler = vsyncHandler;
        checkRealtime();
        if (mRealtime) {
            mVsyncHandler.addListener(this);
        }

    }

    public String start() {
        return encode(null);
    }

    public String encode(
            Object synchStart) {
        mStats = new Statistics("raw encoder", mTest);
        mStable = false;
        mKeyFrameBundle = new Bundle();
        mKeyFrameBundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        Log.d(TAG, "** Surface input encoding - " + mTest.getCommon().getDescription() + " **");
        try {
            mTest = TestDefinitionHelper.updateBasicSettings(mTest);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error: " + e.getMessage());
        }
        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();

        Size res = SizeUtils.parseXString(mTest.getInput().getResolution());
        int width = res.getWidth();
        int height = res.getHeight();
        // TODO(chema): this assumes 4:2:0 subsampling, and therefore YUV
        mRefFramesizeInBytes = (int) (width * height * 1.5);
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

        if (mTest.getInput().getPixFmt().getNumber() == PixFmt.rgba_VALUE) {
            mIsRgbaSource = true;
            mRefFramesizeInBytes = width * height * 4;
        } else if (mTest.getInput().getFilepath().startsWith("fake_input")) {
            mIsFakeInput = true;
            // Parse pattern type from "fake_input.type" notation
            mFakeInputPatternType = parseFakeInputPatternType(mTest.getInput().getFilepath());
            // Use GL rendering for fake input - ZERO CPU overhead!
            Log.d(TAG, "Using fake input with GL rendering, pattern: " + mFakeInputPatternType);
        } else if (mTest.getInput().getFilepath().equals("camera")) {
            mIsCameraSource = true;
            //TODO: handle other fps (i.e. try to set lower or higher fps)
            // Need to check what frame rate is actually set unless real frame time is being used
            mReferenceFrameRate = 30; //We strive for this at least
            mKeepInterval = mReferenceFrameRate / mFrameRate;

            if (!mTest.getInput().hasPlayoutFrames() && !mTest.getInput().hasStoptimeSec()) {
                // In case we do not have a limit, limit to 60 secs
                mTest = TestDefinitionHelper.updatePlayoutFrames(mTest, 1800);
                Log.d(TAG, "Set playout limit for camera to 1800 frames");
            }
        }


        if (!mIsRgbaSource && !mIsCameraSource && !mIsFakeInput) {
            // if we are getting a YUV source, we need to convert it to RGBA
            // This conversion routine assumes nv21. Let's make sure that
            // is the input pix_fmt.
            if (mTest.getInput().getPixFmt().getNumber() != PixFmt.nv21_VALUE) {
                return "Error: yuv->rgba conversion on surface encoder only supports nv21 (got " + mTest.getInput().getPixFmt() + ")";
            }

            try {
                RenderScript rs = RenderScript.create(mContext);
                yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
                Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(mRefFramesizeInBytes);
                mYuvIn = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
                Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height);
                mYuvOut = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
            } catch (NullPointerException npe) {
                Log.e(TAG, "Failed to access to RenderScript: " + npe.getMessage());
                return "Failed to access to RenderScript";
            }
        }

        if (!mIsCameraSource) {
            if (!mIsFakeInput) {
                // Only create bitmap for non-GL paths
                mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }

            if (mIsFakeInput) {
                // Initialize FakeGLRenderer (will be set up later on GL thread)
                mFakeGLRenderer = new FakeGLRenderer();
                mFakeGLRenderer.setPatternType(mFakeInputPatternType);
                mFakeGLRenderer.setDimensions(width, height);
                Log.d(TAG, "Created FakeGLRenderer for GL-based fake input with pattern: " + mFakeInputPatternType);
                // Initialize on GL thread after OutputMultiplier is ready
            } else {
                mYuvReader = new FileReader();
                if (!mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt())) {
                    return "Could not open file";
                }
            }
        }


        MediaFormat format;

        try {
            // Surface encoding requires OutputMultiplier - create one if not provided
            if (mOutputMult == null) {
                Log.d(TAG, "Creating OutputMultiplier for surface encoding (no display output)");
                mOutputMult = new OutputMultiplier(mVsyncHandler);
            }

            // Unless we have a mime, do lookup
            if (mTest.getConfigure().getMime().length() == 0) {
                Log.d(TAG, "codec id: " + mTest.getConfigure().getCodec());
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

            format = TestDefinitionHelper.buildMediaFormat(mTest);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            logMediaFormat(format);

            Log.d(TAG, "Format of encoder");
            logMediaFormat(format);

            mCodec.setCallback(new EncoderCallbackHandler());
            mStats.pushTimestamp("encoder.config");
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            mStats.pushTimestamp("encoder.config");
            logMediaFormat(mCodec.getInputFormat());
            mFrameSwapSurface = mOutputMult.addSurface(mCodec.createInputSurface());
            setupOutputMult(width, height);

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

        Log.d(TAG, "Create muxer");
        mMuxerWrapper = createMuxerWrapper(mCodec, mCodec.getOutputFormat());


        // This is needed.
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        if (isVP) {
            mVideoTrack = mMuxerWrapper.addTrack(mCodec.getOutputFormat());
            Log.d(TAG, "Start muxer, track = " + mVideoTrack);
            mMuxerWrapper.start();
        }

        Log.d(TAG, "Create fps measure: " + this);
        mFpsMeasure = new FpsMeasure(mFrameRate, this.toString());

        mStats.pushTimestamp("encoder.start");
        mFpsMeasure.start();
        mStats.pushTimestamp("encoder.start");
        mFrameTimeUsec = calculateFrameTimingUsec(mFrameRate);
        int current_loop = 1;
        ByteBuffer byteBuffer = ByteBuffer.allocate(mRefFramesizeInBytes);
        boolean done = false;

        // For file input, we're immediately stable (no warmup needed)
        // For fake input with GL, initialization will happen on first frame render
        if (!mIsCameraSource && mYuvReader != null) {
            mStable = true;
        } else if (mIsFakeInput && mFakeGLRenderer != null) {
            // GL renderer will be initialized on first frame (on GL thread)
            mStable = true;
            Log.i(TAG, "FakeGLRenderer ready (will init on GL thread)");
        }

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

        int errorCounter = 0;
        while (!done) {
            if (mFramesAdded % 100 == 0 && MainActivity.isStable()) {
                Log.d(TAG, mTest.getCommon().getId() + " - SurfaceEncoder: frames: " + mFramesAdded +
                        " inframes: " + mInFramesCount +
                        " current_loop: " + current_loop +
                        " current_time: " + mCurrentTimeSec +
                        " frame_rate: " + mFrameRate +
                        " calc_frame_rate: " + (int) (mFramesAdded / mCurrentTimeSec + .5f) +
                        " input_frame_rate: " + (int) (mInFramesCount / mCurrentTimeSec + .5f) +
                        " id: " + mStats.getId());
            }
            try {
                int flags = 0;
                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    Log.d(TAG, mTest.getCommon().getId() + " - Done with input, flag endof stream!");
                    done = true;
                }

                int size = -1;

                if (mIsCameraSource) {
                    try {
                        if (done) {
                            Log.e(TAG, mTest.getCommon().getId() + " - Oh no. We are done!");
                        }
                        long timestampUsec = mOutputMult.awaitNewImage() / 1000;  //To Usec
                        if (!MainActivity.isStable()) {
                            if (!mFpsMeasure.isStable()) {
                                mFpsMeasure.addPtsUsec(timestampUsec);
                                mCodec.setParameters(mKeyFrameBundle);
                                Log.d(TAG, "Not stable, current fps: " + mFpsMeasure.getFps() + "( " +
                                        mFpsMeasure.getAverageFps() + ")");
                            } else {
                                mStable = true;
                            }
                        } else {
                            if (mFirstFrameTimestampUsec < 0) {
                                mFirstFrameTimestampUsec = timestampUsec;
                                // Request key frame
                                Bundle bundle = new Bundle();
                                bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                                mCodec.setParameters(bundle);
                            }
                            setRuntimeParameters(mInFramesCount);
                            mDropNext = dropFrame(mInFramesCount);
                            mDropNext |= dropFromDynamicFramerate(mInFramesCount);
                            updateDynamicFramerate(mInFramesCount);
                            if (mDropNext) {
                                mFrameSwapSurface.dropNext(true);
                                mSkipped++;
                                mDropNext = false;
                            } else {
                                mFrameSwapSurface.dropNext(false);
                                long ptsUsec = 0;
                                if (mUseCameraTimestamp && mIsCameraSource) {
                                    // Use the camera provided timestamp
                                    ptsUsec = mPts + (long) (timestampUsec - mFirstFrameTimestampUsec);
                                } else {
                                    ptsUsec = computePresentationTimeUs(mPts, mInFramesCount, mRefFrameTime);
                                }
                                mStats.startEncodingFrame(ptsUsec, mInFramesCount);
                                mFramesAdded++;
                            }
                            mInFramesCount++;
                        }
                    } catch (Exception ex) {
                        //TODO: make a real fix for when a camera encoder quits before another
                        // and the surface is removed. The camera request needs to be updated.
                        errorCounter += 1;
                        if (errorCounter > 10) {
                            Log.e(TAG, "Failed to get next frame: " + ex.getMessage() + " errorCounter = " + errorCounter);
                            break;

                        }
                    }

                } else {
                    if (MainActivity.isStable()) {
                        while (size < 0 && !done) {
                            try {
                                if (mYuvReader != null || mIsFakeInput) {
                                    size = queueInputBufferEncoder(
                                            mYuvReader,
                                            mCodec,
                                            byteBuffer,
                                            mInFramesCount,
                                            flags,
                                            mRefFramesizeInBytes);
                                }
                            } catch (IllegalStateException isx) {
                                Log.e(TAG, "Queue encoder failed,mess: " + isx.getMessage());
                                return "Illegal state: " + isx.getMessage();
                            }
                            if (size == -2) {
                                continue;
                            } else if (size <= 0 && !mIsCameraSource) {
                                if (mYuvReader != null) {
                                    mYuvReader.closeFile();
                                }
                                current_loop++;
                                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, true)) {
                                    Log.d(TAG, "Done with input!");
                                    done = true;
                                }

                                if (!done) {
                                    if (mYuvReader != null) {
                                        Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                                        mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt());
                                        Log.d(TAG, "*** Loop ended start " + current_loop + "***");
                                    }
                                }
                            }
                        }
                    }
                }
                mStable = true;
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        Log.d(TAG, "Close muxer and streams, " + mTest.getCommon().getDescription());

        try {
            mCodec.signalEndOfInputStream();
            if (mInFramesCount > mOutFramesCount) {
                Log.d(TAG, "Give me a sec, waiting for last encodings input: " + mInFramesCount + " > output: " + mOutFramesCount);
                synchronized (mStopLock) {
                    try {

                        mStopLock.wait(WAIT_TIME_SHORT_MS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            mCodec.flush();
        } catch (MediaCodec.CodecException ex) {
            Log.e(TAG, "flush: MediaCodec.CodecException error");
            ex.printStackTrace();
        } catch (IllegalStateException ex) {
            Log.e(TAG, "flush: IllegalStateException error");
            ex.printStackTrace();
        }
        mStats.stop();

        if (mMuxerWrapper != null) {
            try {
                mMuxerWrapper.release(); //Release calls stop
            } catch (IllegalStateException ise) {
                //Most likely mean that the muxer is already released. Stupid API
                Log.e(TAG, "Illegal state exception when trying to release the muxer: " + ise.getMessage());
            }
            mMuxerWrapper = null;
        }
        if (mCodec != null) {
            try {
                mCodec.flush();
            } catch (MediaCodec.CodecException ex) {
                Log.e(TAG, "flush: MediaCodec.CodecException error");
                ex.printStackTrace();
            } catch (IllegalStateException ex) {
                Log.e(TAG, "flush: IllegalStateException error");
                ex.printStackTrace();
            }
            synchronized (this) {
                try {
                    this.wait(WAIT_TIME_SHORT_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            try {
                mCodec.stop();
            } catch (IllegalStateException ex) {
                Log.e(TAG, "stop: IllegalStateException error");
                ex.printStackTrace();
            }
            mCodec.release();
        }

        if (mFrameSwapSurface != null) {
            mOutputMult.removeFrameSwapControl(mFrameSwapSurface);
        }

        if (mYuvReader != null)
            mYuvReader.closeFile();

        if (mFakeGLRenderer != null) {
            mFakeGLRenderer.release();
        }

        if (mSurfaceTexture != null) {
            mSurfaceTexture.detachFromGLContext();
            mSurfaceTexture.releaseTexImage();
            mSurfaceTexture.release();
        }
        Log.d(TAG, "Stop writer");
        mDataWriter.stopWriter();
        //mOutputMult.stopAndRelease();
        return "";
    }

    protected void checkRealtime() {
        if (mTest.getInput().hasRealtime()) {
            if ( mTest.getInput().getRealtime()) {
                // Realtime will limit the read pace to fps speed
                // Without it the extractor will read as fast a possible
                // until no buffers are available.
                mRealtime = true;
            }
        }

        // Fake GL input doesn't need realtime throttling - it's synthetic data
        // Let it run as fast as the encoder can handle
        if (mIsFakeInput) {
            Log.d(TAG, "Fake GL input detected - disabling realtime throttling for max performance");
            mRealtime = false;
        }

        if (!mRealtime) {
            if (mOutputMult != null) {
                Log.d(TAG, "Outputmultiplier will work in non realtime mode");
                mOutputMult.setRealtime(false);
            }
        }

        Log.d(TAG, "Surface encoder will run in realtime mode:" + mRealtime);
    }

    private void setupOutputMult(int width, int height) {
        if (mOutputMult != null) {
            mOutputMult.setName("SE_" + mTest.getInput().getFilepath() + "_enc-" + mTest.getConfigure().getCodec());
            mOutputMult.confirmSize(width, height);

            // Need to update outputmulti and configure working mode.
            if (!mRealtime) {
                if (mOutputMult != null) {
                    Log.d(TAG, "Outputmultiplier will work in non realtime mode");
                    mOutputMult.setRealtime(false);
                }
            } else if (mIsFakeInput && !mTest.getInput().hasShow()) {
                // Fake input without UI display doesn't need vsync synchronization
                Log.d(TAG, "Fake input without display - disabling vsync synchronization");
                mOutputMult.setRealtime(false);
            }
        }
    }

    /**
     * Fills input buffer for encoder from YUV file or fake GL input.
     * OPTIMIZED: GL path has ZERO Java/CPU overhead.
     *
     * @return size of enqueued data.
     */
    private int queueInputBufferEncoder(
            FileReader fileReader, MediaCodec codec, ByteBuffer byteBuffer, int frameCount, int flags, int size) {

        int read;
        if (mIsFakeInput && mFakeGLRenderer != null) {
            // GL rendering path - ZERO CPU overhead!
            long ptsUsec = computePresentationTimeUs(mPts, mInFramesCount, mRefFrameTime);
            setRuntimeParameters(mInFramesCount);
            mDropNext = dropFrame(mInFramesCount);
            mDropNext |= dropFromDynamicFramerate(mInFramesCount);
            updateDynamicFramerate(mInFramesCount);

            if (mDropNext) {
                mSkipped++;
                mDropNext = false;
                read = -2;
            } else {
                mFramesAdded++;
                if (mFirstFrameTimestampUsec == -1) {
                    mFirstFrameTimestampUsec = ptsUsec;
                }

                // Direct GL rendering - no bitmap, no copying!
                mOutputMult.newGLPatternFrame(mFakeGLRenderer, ptsUsec, frameCount, mStats);

                // NOTE: startEncodingFrame is NOT called here. It will be called AFTER swapBuffers()
                // in OutputMultiplier to measure only the encoder time, not GL rendering time.
                read = size; // Success

                // Apply realtime throttling if enabled
                if (mRealtime) {
                    sleepUntilNextFrame();
                }
            }
        } else {
            // Original bitmap path for YUV from disk
            long t0 = 0, t1 = 0, t2 = 0, t3 = 0, t4 = 0, t5 = 0, t6 = 0, t7 = 0, t8 = 0, t9 = 0, t10 = 0, t11 = 0, t12 = 0;

            if (CliSettings.isTracingEnabled()) {
                t0 = ClockTimes.currentTimeMs();
            }
            byteBuffer.clear();

            if (CliSettings.isTracingEnabled()) {
                t1 = ClockTimes.currentTimeMs();
            }
            // Regular file input needs manual YUV->RGBA conversion
            read = fileReader.fillBuffer(byteBuffer, size);
            if (CliSettings.isTracingEnabled()) {
                t2 = ClockTimes.currentTimeMs();
            }
            if (read == size) {
                if (!mIsRgbaSource) {
                    if (CliSettings.isTracingEnabled()) {
                        t3 = ClockTimes.currentTimeMs();
                    }
                    mYuvIn.copyFrom(byteBuffer.array());
                    if (CliSettings.isTracingEnabled()) {
                        t4 = ClockTimes.currentTimeMs();
                    }
                    yuvToRgbIntrinsic.setInput(mYuvIn);
                    if (CliSettings.isTracingEnabled()) {
                        t5 = ClockTimes.currentTimeMs();
                    }
                    yuvToRgbIntrinsic.forEach(mYuvOut);
                    if (CliSettings.isTracingEnabled()) {
                        t6 = ClockTimes.currentTimeMs();
                    }
                    mYuvOut.copyTo(mBitmap);
                    if (CliSettings.isTracingEnabled()) {
                        t7 = ClockTimes.currentTimeMs();
                        if (frameCount < 10) {
                            Log.d(TAG, "Frame " + frameCount + " [YUV] fileRead: " + (t2-t1) + "ms, copyFrom: " + (t4-t3) + "ms, setInput: " + (t5-t4) + "ms, forEach: " + (t6-t5) + "ms, copyTo: " + (t7-t6) + "ms, TOTAL: " + (t7-t1) + "ms");
                        }
                    }
                } else {
                    mBitmap.copyPixelsFromBuffer(byteBuffer);
                    if (CliSettings.isTracingEnabled()) {
                        t3 = ClockTimes.currentTimeMs();
                        if (frameCount < 10) {
                            Log.d(TAG, "Frame " + frameCount + " [RGBA] fileRead: " + (t2-t1) + "ms, copyPixels: " + (t3-t2) + "ms");
                        }
                    }
                }
            }

            if (CliSettings.isTracingEnabled()) {
                t8 = ClockTimes.currentTimeMs();
            }
            long ptsUsec = computePresentationTimeUs(mPts, mInFramesCount, mRefFrameTime);
            setRuntimeParameters(mInFramesCount);
            mDropNext = dropFrame(mInFramesCount);
            mDropNext |= dropFromDynamicFramerate(mInFramesCount);
            updateDynamicFramerate(mInFramesCount);
            if (CliSettings.isTracingEnabled()) {
                t9 = ClockTimes.currentTimeMs();
            }

            if (mDropNext) {
                mSkipped++;
                mDropNext = false;
                read = -2;
            } else if (read == size) {
                mFramesAdded++;

                if (mFirstFrameTimestampUsec == -1) {
                    mFirstFrameTimestampUsec = ptsUsec;
                }

                if (CliSettings.isTracingEnabled()) {
                    t10 = ClockTimes.currentTimeMs();
                }
                mOutputMult.newBitmapAvailable(mBitmap, ptsUsec, frameCount, mStats);
                if (CliSettings.isTracingEnabled()) {
                    t11 = ClockTimes.currentTimeMs();
                }

                // NOTE: startEncodingFrame is NOT called here. It will be called AFTER swapBuffers()
                // in OutputMultiplier to measure only the encoder time, not YUV processing time.
                if (CliSettings.isTracingEnabled()) {
                    t12 = ClockTimes.currentTimeMs();
                    if (frameCount < 10) {
                        Log.d(TAG, "Frame " + frameCount + " paramCalc: " + (t9-t8) + "ms, newBitmapAvailable: " + (t11-t10) + "ms, TOTAL_QUEUE: " + (t12-t0) + "ms");
                    }
                }

                // Apply realtime throttling if enabled - AFTER all processing is done
                if (mRealtime) {
                    sleepUntilNextFrame();
                }
            } else {
                Log.d(TAG, "***************** FAILED READING SURFACE ENCODER ******************");
                return -1;
            }
        }

        mInFramesCount++;
        return read;
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
    }

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
    }

    public void stopAllActivity(){}

    public void release() {
        // Don't release the multiplier if it's a shared camera source -
        // MainActivity will release mCameraSourceMultiplier after all tests complete
        if (!mIsCameraSource) {
            mOutputMult.stopAndRelease();
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

    /**
     * Parse pattern type from fake_input filepath notation.
     * Supports: "fake_input", "fake_input.clock", "fake_input.texture", "fake_input.gradient", "fake_input.solid"
     *
     * @param filepath The input filepath (e.g., "fake_input.clock")
     * @return The parsed PatternType, defaults to TEXTURE if not specified or unknown
     */
    private FakeGLRenderer.PatternType parseFakeInputPatternType(String filepath) {
        if (filepath == null || !filepath.startsWith("fake_input")) {
            return FakeGLRenderer.PatternType.TEXTURE;
        }

        // Check for ".type" suffix
        if (filepath.contains(".")) {
            String suffix = filepath.substring(filepath.lastIndexOf('.') + 1).toLowerCase();
            switch (suffix) {
                case "clock":
                    Log.d(TAG, "Parsed fake_input pattern type: CLOCK");
                    return FakeGLRenderer.PatternType.CLOCK;
                case "texture":
                    Log.d(TAG, "Parsed fake_input pattern type: TEXTURE");
                    return FakeGLRenderer.PatternType.TEXTURE;
                case "gradient":
                    Log.d(TAG, "Parsed fake_input pattern type: GRADIENT");
                    return FakeGLRenderer.PatternType.GRADIENT;
                case "solid":
                    Log.d(TAG, "Parsed fake_input pattern type: SOLID");
                    return FakeGLRenderer.PatternType.SOLID;
                default:
                    Log.w(TAG, "Unknown fake_input pattern type: " + suffix + ", using TEXTURE");
                    return FakeGLRenderer.PatternType.TEXTURE;
            }
        }

        // No suffix, use default
        Log.d(TAG, "No pattern type specified, using default: TEXTURE");
        return FakeGLRenderer.PatternType.TEXTURE;
    }
}
