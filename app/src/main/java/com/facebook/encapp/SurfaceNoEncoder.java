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
import com.facebook.encapp.utils.VsyncHandler;
import com.facebook.encapp.utils.VsyncListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SurfaceNoEncoder extends SurfaceEncoder implements VsyncListener {
    private final String TAG = "encapp.surfacenoencoder";
    MediaExtractor mExtractor;
    MediaCodec mDecoder;
    DecoderRuntime mDecoderRuntimeParams;
    OutputMultiplier mOutputMult = null;
    double mLoopTime = 0;
    int mCurrentLoop = 1;
    long mPtsOffset = 0;
    long mLastPts = -1;
    boolean mNoEncoding = false;
    private FrameswapControl mFrameSwapSurface;
    private VsyncHandler mVsyncHandler;
    Object mSyncLock = new Object();
    long mVsyncTimeNs = 0;
    long mFirstSynchNs = -1;

    public SurfaceNoEncoder(Test test, OutputMultiplier multiplier ) {
        super(test);
        mOutputMult = multiplier;
    }


    public String start() {
        Surface surface = null;
        SurfaceTexture surfaceTexture = null;

        mNoEncoding = true;
        Log.d(TAG, "**** Surface Decode, no encode ***");
        mStable = true;

        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();
        if (mTest.hasDecoderRuntime())
            mDecoderRuntimeParams = mTest.getDecoderRuntime();

        checkRealtime();
        if (mRealtime) {
            mVsyncHandler.addListener(this);
        }

        mFrameRate = mTest.getConfigure().getFramerate();
        Log.d(TAG, "Realtime = " + mRealtime + ", encoding to " + mFrameRate + " fps");
        try {
            if (TestDefinitionHelper.checkBasicSettings(mTest)) {
                mTest = TestDefinitionHelper.updateBasicSettings(mTest);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error: " + e.getMessage());
        }

        mStats = new Statistics("surface no encoder", mTest);

        Size res = SizeUtils.parseXString(mTest.getInput().getResolution());
        int width = res.getWidth();
        int height = res.getHeight();
        // TODO(chema): this assumes 4:2:0 subsampling, and therefore YUV
        mRefFramesizeInBytes = (int) (width * height * 1.5);

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

        if (mFrameRate <= 0) {
            mFrameRate = mReferenceFrameRate;
        }
        mKeepInterval = mReferenceFrameRate / mFrameRate;

        Log.d(TAG, "Start vizualisation size is WxH = " + width + "x" + height);
        mOutputMult.confirmSize(width, height);
        mOutputMult.setName("ST_" + mTest.getInput().getFilepath());

        surface = mOutputMult.getInputSurface();
        if (surface == null) {
            surfaceTexture = new SurfaceTexture(false);
            surface = new Surface(surfaceTexture);
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
        mStats.start();
        boolean done = false;

        long lastPtsUsec = -1;
        int errorCounter = 0;
        while(!done) {
                if (mInFramesCount % 100 == 0 && MainActivity.isStable()) {
                    Log.d(TAG, "SurfaceEncoder: frames: " + mFramesAdded +
                            " inframes: " + mInFramesCount +
                            " current_time: " + mCurrentTimeSec +
                            " frame_rate: " + mFrameRate +
                            " input_frame_rate: " + (int) (mInFramesCount / mCurrentTimeSec + .5f) +
                            " id: " + mStats.getId());
                }
                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                    //mOutputMult.stopAndRelease();
                    done = true;
                }

                try {
                    long time = lastPtsUsec - (long)mFirstFrameTimestampUsec;
                    if (mFirstFrameTimestampUsec == -1) {
                        time = 0;
                        if (lastPtsUsec > 0) {
                            mFirstFrameTimestampUsec = lastPtsUsec;
                        }
                    }

                    if (MainActivity.isStable()) {
                        mStats.startDecodingFrame(time, 0, 0);
                    }
                    long timestampUsec = mOutputMult.awaitNewImage() / 1000;  //To Usec
                    mStable = true;
                    if (lastPtsUsec >= 0)
                        mStats.stopDecodingFrame(time);
                    lastPtsUsec = timestampUsec;
                    setRuntimeParameters(mInFramesCount);
                    mCurrentTimeSec = time/1000000;
                    mInFramesCount++;

                } catch (Exception ex) {
                    //TODO: make a real fix for when a camera encoder quits before another
                    // and the surface is removed. The camera request needs to be updated.
                    errorCounter += 1;
                    if (errorCounter > 10) {
                        Log.e(TAG, "Failed to get next frame: " + ex.getMessage() + " errorCounter = " + errorCounter);
                        break;

                    }

                }


        }

        mStats.stop();
        Log.d(TAG, mTest.getCommon().getId() + " - SurfaceNoEncode done");
        if (mVsyncHandler != null)
            mVsyncHandler.removeListener(this);

        if (mOutputMult != null) {
            //mOutputMult.stopAndRelease();
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

        return "";
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

    public void release() {
        mOutputMult.stopAndRelease();
    }
}
