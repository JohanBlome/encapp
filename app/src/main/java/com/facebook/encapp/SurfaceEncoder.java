package com.facebook.encapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.util.Size;

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;
import com.facebook.encapp.utils.grafika.EglSurfaceBase;
import com.facebook.encapp.utils.grafika.Texture2dProgram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

import androidx.annotation.NonNull;


/**
 * Created by jobl on 2018-02-27.
 */

class SurfaceEncoder extends Encoder {
    Bitmap mBitmap = null;
    Context mContext;

    private Allocation mYuvIn;
    private Allocation mYuvOut;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    SurfaceTexture mSurfaceTexture;
    boolean mIsRgbaSource = false;
    boolean mIsCameraSource = false;

    boolean mUseCameraTimestamp = true;
    OutputMultiplier mOutputMult;
    private EglSurfaceBase mEglSurface;


    public SurfaceEncoder(Context context,  OutputMultiplier multiplier) {
        mOutputMult = multiplier;
        mContext = context;
    }

    public String start(Test test) {
        return encode(test, null);
    }

    public String encode(
            Test test,
            Object synchStart) {
        Log.d(TAG, "** Surface input encoding - " + test.getCommon().getDescription() + " **");
        test = TestDefinitionHelper.checkAnUpdateBasicSettings(test);
        if (test.hasRuntime())
            mRuntimeParams = test.getRuntime();
        if (test.getInput().hasRealtime())
            mRealtime = test.getInput().getRealtime();

        mFrameRate = test.getConfigure().getFramerate();
        mWriteFile = (test.getConfigure().hasEncode())?test.getConfigure().getEncode():true;
        mStats = new Statistics("raw encoder", test);

        Size res = SizeUtils.parseXString(test.getInput().getResolution());
        int width = res.getWidth();
        int height = res.getHeight();
        mRefFramesizeInBytes = (int) (width * height * 1.5);
        mRefFrameTime = calculateFrameTiming(mReferenceFrameRate);

        if (test.getInput().getFilepath().endsWith("rgba")) {
            mIsRgbaSource = true;
            mRefFramesizeInBytes = (int) (width * height * 4);
        } else if (test.getInput().getFilepath().equals("camera")) {
            mIsCameraSource = true;
            //TODO: handle other fps (i.e. try to set lower or higher fps)
            // Need to check what frame rate is actually set unless real frame time is being used
            mReferenceFrameRate = 30; //We strive for this at least
            mKeepInterval = mReferenceFrameRate / (float) mFrameRate;

            if (!test.getInput().hasPlayoutFrames() && !test.getInput().hasStoptimeSec()) {
                // In case we do not have a limit, limit to 60 secs
                test = TestDefinitionHelper.updatePlayoutFrames(test, 1800);
                Log.d(TAG, "Set playout limit for camera to 1800 frames");
            }
        }

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
            if (!mYuvReader.openFile(test.getInput().getFilepath())) {
                return "\nCould not open file";
            }

        }


        MediaFormat format;

        try {

            //Unless we have a mime, do lookup
            if (test.getConfigure().getMime().length() == 0) {
                Log.d(TAG, "codec id: " + test.getConfigure().getCodec());
                //TODO: throw error on failed lookup
                test = setCodecNameAndIdentifier(test);
            }
            Log.d(TAG, "Create codec by name: " + test.getConfigure().getCodec());
            mCodec = MediaCodec.createByCodecName(test.getConfigure().getCodec());

            format = TestDefinitionHelper.buildMediaFormat(test);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            checkMediaFormat(format);

            Log.d(TAG, "Format of encoder");
            checkMediaFormat(format);

            mCodec.setCallback(new EncoderCallbackHandler());
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            checkMediaFormat(mCodec.getInputFormat());
            mEglSurface = mOutputMult.addSurface( mCodec.createInputSurface());

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
            mMuxer.start();
        }


        int current_loop = 1;
        ByteBuffer buffer = ByteBuffer.allocate(mRefFramesizeInBytes);
        boolean done = false;
        long firstTimestamp = -1;
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
            int index;

            if (mFramesAdded % 100 == 0) {
                Log.d(TAG, "SurfaceEncoder, Frames: " + mFramesAdded + " - inframes: " + mInFramesCount +
                        ", current loop: " + current_loop  + ", current time: " + mCurrentTime + " sec");
            }
            try {
                int flags = 0;
                if (doneReading(test, mInFramesCount, mCurrentTime, false)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    done = true;
                }

                int size = -1;

                if (mIsCameraSource) {
                    try {
                        long timestamp = mOutputMult.awaitNewImage();
                        if (firstTimestamp <= 0) {
                            firstTimestamp = timestamp;
                        }
                        int currentFrameNbr = (int) ((float) (mInFramesCount) / mKeepInterval);
                        int nextFrameNbr = (int) ((float) ((mInFramesCount + 1)) / mKeepInterval);
                        setRuntimeParameters(mInFramesCount);
                        mDropNext = dropFrame(mInFramesCount);
                        updateDynamicFramerate(mInFramesCount);
                        if (currentFrameNbr == nextFrameNbr || mDropNext) {
                            mSkipped++;
                            mDropNext = false;
                        } else {
                            long ptsNsec = 0;
                            if (mUseCameraTimestamp) {
                                // Use the camera provided timestamp
                                ptsNsec = mPts * 1000 + (timestamp - firstTimestamp);
                            } else {
                                ptsNsec = computePresentationTime(mInFramesCount, mRefFrameTime) * 1000;
                            }

                            mStats.startEncodingFrame(ptsNsec / 1000, mInFramesCount);
                            mFramesAdded++;
                        }
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

                } else {
                    while (size < 0 && !done) {
                        try {
                            if (mYuvReader != null) {
                                size = queueInputBufferEncoder(
                                        mCodec,
                                        buffer,
                                        mInFramesCount,
                                        flags,
                                        mRefFramesizeInBytes);
                            }
                            mInFramesCount++;
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
                            if (doneReading(test, mInFramesCount, mCurrentTime, true)) {
                                done = true;
                            }

                            if (!done) {
                                if (mYuvReader != null) {
                                    Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                                    mYuvReader.openFile(test.getInput().getFilepath());
                                    Log.d(TAG, "*** Loop ended start " + current_loop + "***");
                                }
                            }
                        }
                    }

                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }


        }
        mStats.stop();

        Log.d(TAG, "Close muxer and streams");
        if (mCodec != null) {
            mCodec.flush();
            mCodec.stop();
            mCodec.release();
        }
        if (mMuxer != null) {
            try {
                mMuxer.release(); //Release calls stop
            } catch (IllegalStateException ise) {
                //Most likely mean that the muxer is already released. Stupid API
                Log.e(TAG, "Illegal state exception when trying to release the muxer");
            }
        }

        if (mEglSurface != null) {
            mOutputMult.removeEglSurface(mEglSurface);
        }

        if (mYuvReader != null)
            mYuvReader.closeFile();

        if (mSurfaceTexture != null) {
            mSurfaceTexture.detachFromGLContext();
            mSurfaceTexture.releaseTexImage();
            mSurfaceTexture.release();
        }

        return "";
    }


    SurfaceTexture mSurfTex = null;

    /**
     * Fills input buffer for encoder from YUV buffers.
     *
     * @return size of enqueued data.
     */
    private int queueInputBufferEncoder(
            MediaCodec codec, ByteBuffer buffer, int frameCount, int flags, int size) {
        buffer.clear();
        int read = mYuvReader.fillBuffer(buffer, size);
        int currentFrameNbr = (int) ((float) (frameCount) / mKeepInterval);
        int nextFrameNbr = (int) ((float) ((frameCount + 1)) / mKeepInterval);
        setRuntimeParameters(mInFramesCount);
        mDropNext = dropFrame(mInFramesCount);
        updateDynamicFramerate(mInFramesCount);
        if (currentFrameNbr == nextFrameNbr || mDropNext) {
            mSkipped++;
            mDropNext = false;
            read = -2;
        } else if (read == size) {
            long ptsUsec = computePresentationTime(mInFramesCount, mRefFrameTime);
            mFramesAdded++;
            if (mRealtime) {
                sleepUntilNextFrame();
            }

            if (!mIsRgbaSource) {
                mYuvIn.copyFrom(buffer.array());
                yuvToRgbIntrinsic.setInput(mYuvIn);
                yuvToRgbIntrinsic.forEach(mYuvOut);

                mYuvOut.copyTo(mBitmap);
            } else {
                mBitmap.copyPixelsFromBuffer(buffer);
            }

            if (mOutputMult == null){
                mOutputMult = new OutputMultiplier(Texture2dProgram.ProgramType.TEXTURE_2D);
            }
            mOutputMult.newFrameAvailable();

            if (mRealtime) {
                sleepUntilNextFrame();
            }
            mStats.startEncodingFrame(ptsUsec, frameCount);
        } else {
            Log.d(TAG, "***************** FAILED READING SURFACE ENCODER ******************");
            read = -1;
        }

        return read;
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder){}
    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info){}
}
