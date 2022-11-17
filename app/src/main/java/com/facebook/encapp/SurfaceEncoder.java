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

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FpsMeasure;
import com.facebook.encapp.utils.FrameswapControl;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;
import com.facebook.encapp.utils.grafika.Texture2dProgram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;


/**
 * Created by jobl on 2018-02-27.
 */

class SurfaceEncoder extends Encoder {
    Bitmap mBitmap = null;
    Context mContext;
    SurfaceTexture mSurfaceTexture;
    boolean mIsRgbaSource = false;
    boolean mIsCameraSource = false;
    boolean mUseCameraTimestamp = true;
    OutputMultiplier mOutputMult;
    Bundle mKeyFrameBundle;
    private Allocation mYuvIn;
    private Allocation mYuvOut;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private FrameswapControl mFrameSwapSurface;

    public SurfaceEncoder(Test test, Context context, OutputMultiplier multiplier) {
        super(test);
        mOutputMult = multiplier;
        mContext = context;
        mStats = new Statistics("raw encoder", mTest);
    }

    public SurfaceEncoder(Test test, Context context) {
        super(test);
        mContext = context;
        mStats = new Statistics("raw encoder", mTest);
    }

    public SurfaceEncoder(Test test){
        super(test);
    }
    public String start() {
        return encode(null);
    }

    public String encode(
            Object synchStart) {
        mStable = false;
        mKeyFrameBundle = new Bundle();
        mKeyFrameBundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        Log.d(TAG, "** Surface input encoding - " + mTest.getCommon().getDescription() + " **");
        mTest = TestDefinitionHelper.checkAnUpdateBasicSettings(mTest);
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

        if (mTest.getInput().getFilepath().endsWith("rgba")) {
            mIsRgbaSource = true;
            mRefFramesizeInBytes = width * height * 4;
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

        checkRealtime();

        if (!mIsRgbaSource && !mIsCameraSource) {
            RenderScript rs = RenderScript.create(mContext);
            yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));

            Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(mRefFramesizeInBytes);
            mYuvIn = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
            Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height);
            mYuvOut = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
        }

        if (!mIsCameraSource) {
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            mYuvReader = new FileReader();
            if (!mYuvReader.openFile(mTest.getInput().getFilepath())) {
                return "\nCould not open file";
            }

        }


        MediaFormat format;

        try {

            //Unless we have a mime, do lookup
            if (mTest.getConfigure().getMime().length() == 0) {
                Log.d(TAG, "codec id: " + mTest.getConfigure().getCodec());
                //TODO: throw error on failed lookup
                mTest = setCodecNameAndIdentifier(mTest);
            }
            Log.d(TAG, "Create codec by name: " + mTest.getConfigure().getCodec());
            mCodec = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());

            format = TestDefinitionHelper.buildMediaFormat(mTest);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            logMediaFormat(format);

            Log.d(TAG, "Format of encoder");
            logMediaFormat(format);

            mCodec.setCallback(new EncoderCallbackHandler());
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
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
            mCodec.start();
        } catch (Exception ex) {
            Log.e(TAG, "Start failed: " + ex.getMessage());
            return "Start encoding failed";
        }

        Log.d(TAG, "Create muxer");
        mMuxer = createMuxer(mCodec, mCodec.getOutputFormat(), true);


        // This is needed.
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        if (isVP) {
            mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
            Log.d(TAG, "Start muxer, track = " + mVideoTrack);
            mMuxer.start();
        }

        Log.d(TAG, "Create fps measure: " + this);
        mFpsMeasure = new FpsMeasure(mFrameRate, this.toString());
        mFpsMeasure.start();
        mFrameTimeUsec = calculateFrameTimingUsec(mFrameRate);
        int current_loop = 1;
        ByteBuffer byteBuffer = ByteBuffer.allocate(mRefFramesizeInBytes);
        boolean done = false;
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
                    //mOutputMult.stopAndRelease();
                    done = true;
                    continue;
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
                                if (mUseCameraTimestamp) {
                                    // Use the camera provided timestamp
                                    ptsUsec = mPts + (long) (timestampUsec - mFirstFrameTimestampUsec);
                                } else {
                                    ptsUsec = computePresentationTimeUsec(mInFramesCount, mRefFrameTime);
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
                                if (mYuvReader != null) {
                                    size = queueInputBufferEncoder(
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
                                        mYuvReader.openFile(mTest.getInput().getFilepath());
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
        mStats.stop();
        mCodec.flush();

        if (mMuxer != null) {
            try {
                mMuxer.release(); //Release calls stop
            } catch (IllegalStateException ise) {
                //Most likely mean that the muxer is already released. Stupid API
                Log.e(TAG, "Illegal state exception when trying to release the muxer: " + ise.getMessage());
            }
            mMuxer = null;
        }
        if (mCodec != null) {
            mCodec.flush();
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

        if (mFrameSwapSurface != null) {
            mOutputMult.removeFrameSwapControl(mFrameSwapSurface);
        }

        if (mYuvReader != null)
            mYuvReader.closeFile();

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
            } else {
                if (mOutputMult != null)
                    mOutputMult.setRealtime(false);
            }
        }
    }

    private void setupOutputMult(int width, int height) {
        if (mOutputMult != null) {
            mOutputMult.setName("SE_" + mTest.getInput().getFilepath() + "_enc-" + mTest.getConfigure().getCodec());
            mOutputMult.confirmSize(width, height);
        }
    }

    /**
     * Fills input buffer for encoder from YUV buffers.
     *
     * @return size of enqueued data.
     */
    private int queueInputBufferEncoder(
            MediaCodec codec, ByteBuffer byteBuffer, int frameCount, int flags, int size) {
        byteBuffer.clear();
        int read = mYuvReader.fillBuffer(byteBuffer, size);
        long ptsUsec = computePresentationTimeUsec(mInFramesCount, mRefFrameTime);
        setRuntimeParameters(mInFramesCount);
        mDropNext = dropFrame(mInFramesCount);
        mDropNext |= dropFromDynamicFramerate(mInFramesCount);
        updateDynamicFramerate(mInFramesCount);
        if (mDropNext) {
            mSkipped++;
            mDropNext = false;
            read = -2;
        } else if (read == size) {
            mFramesAdded++;
            if (!mIsRgbaSource) {
                mYuvIn.copyFrom(byteBuffer.array());
                yuvToRgbIntrinsic.setInput(mYuvIn);
                yuvToRgbIntrinsic.forEach(mYuvOut);

                mYuvOut.copyTo(mBitmap);
            } else {
                mBitmap.copyPixelsFromBuffer(byteBuffer);
            }

            if (mFirstFrameTimestampUsec == -1) {
                mFirstFrameTimestampUsec = ptsUsec;
            }
            mOutputMult.newBitmapAvailable(mBitmap, ptsUsec);
            mStats.startEncodingFrame(ptsUsec, frameCount);
        } else {
            Log.d(TAG, "***************** FAILED READING SURFACE ENCODER ******************");
            return -1;
        }
       /* if (mRealtime) {
            sleepUntilNextFrame(mRefFrameTime);
        }*/
        mInFramesCount++;
        return read;
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
    }

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
    }

    public void stopAllActivity(){}

    public void release() {
        mOutputMult.stopAndRelease();
    }
}
