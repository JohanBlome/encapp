package com.facebook.encapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.cts.BitmapRender;
import android.media.cts.InputSurface;
import android.media.cts.OutputSurface;
import android.os.Bundle;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;

import com.facebook.encapp.utils.CameraSource;
import com.facebook.encapp.utils.ConfigureParam;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;


/**
 * Created by jobl on 2018-02-27.
 */

class SurfaceEncoder extends Encoder {
    Bitmap mBitmap = null;
    AtomicReference<Surface> mInputSurfaceReference;
    Context mContext;
    InputSurface mInputSurface;
    private Allocation mYuvIn;
    private Allocation mYuvOut;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    BitmapRender mBitmapRender = null;
    SurfaceTexture mSurfaceTexture;
    boolean mIsRgbaSource = false;
    boolean mIsCameraSource = false;
    CameraSource mCameraSource;
    OutputSurface mOutputSurface;
    int mOutFramesCount = 0;
    int mInFramesCount = 0;
    boolean mUseCameraTimestamp = true;

    public SurfaceEncoder(Context context) {
        mContext = context;
    }

    public String encode(
            TestParams vc,
            boolean writeFile) {
        return encode(vc, writeFile, null);
    }

    public String encode(
            TestParams vc,
            boolean writeFile,
            Object synchStart) {

        Log.d(TAG, "** Raw input encoding - " + vc.getDescription() + " **");
        mRuntimeParams = vc.getRuntimeParameters();
        mSkipped = 0;
        mFramesAdded = 0;
        int loop = vc.getLoopCount();
        int keyFrameInterval = vc.getKeyframeRate();
        int width = vc.getReferenceSize().getWidth();
        int height = vc.getReferenceSize().getHeight();
        mRefFramesizeInBytes = (int) (width * height* 1.5);
        mRealtime = vc.isRealtime();
        mWriteFile = writeFile;
        mFrameRate = vc.getFPS();
        mReferenceFrameRate = vc.getmReferenceFPS();
        mKeepInterval = mReferenceFrameRate / (float) mFrameRate;
        mStats = new Statistics("raw encoder", vc);

        if (vc.getInputfile().endsWith("rgba")) {
            mIsRgbaSource = true;
            mRefFramesizeInBytes = (int) (width * height * 4);
        } else if (vc.getInputfile().equals("camera")) {
            mIsCameraSource = true;
            mCameraSource = CameraSource.getCamera(mContext);
            mReferenceFrameRate = 30; //We strive for this at least
            mKeepInterval = mReferenceFrameRate / (float) mFrameRate;
        }

        if (!mIsRgbaSource) {
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
            if (!mYuvReader.openFile(vc.getInputfile())) {
                return "\nCould not open file";
            }

        }


        vc.addEncoderConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface));

        MediaFormat format;

        try {
            String codecName = getCodecName(vc);
            mStats.setCodec(codecName);
            Log.d(TAG, "Create codec by name: " + codecName);
            mCodec = MediaCodec.createByCodecName(codecName);

            format = vc.createEncoderMediaFormat(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            checkConfigureParams(vc, format);
            setConfigureParams(vc, vc.getEncoderConfigure(), format);
            checkConfig(format);
            mInputSurfaceReference = new AtomicReference<>();
            setConfigureParams(vc, vc.getEncoderConfigure(), format);
            mCodec.setCallback(new CallbackHandler());
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurfaceReference.set(mCodec.createInputSurface());
            mInputSurface = new InputSurface(mInputSurfaceReference.get());
            mStats.setEncoderMediaFormat(mCodec.getInputFormat());
            checkConfigureParams(vc, mCodec.getInputFormat());

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

        if (mIsCameraSource) {
            mInputSurface.makeCurrent();
            Log.d(TAG,"create output surface");
            mOutputSurface = new OutputSurface(width, height, false);
            mCameraSource.registerSurface(mOutputSurface.getSurface(), width, height);
        }

        mRefFrameTime = calculateFrameTiming(mReferenceFrameRate);
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        boolean isQCom = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".qcom");

        Log.d(TAG, "Create muxer");
        mMuxer = createMuxer(mCodec, mCodec.getOutputFormat(), true);
        if (isVP) {
            mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
            mMuxer.start();
        }


        int current_loop = 1;
        ByteBuffer buffer = ByteBuffer.allocate(mRefFramesizeInBytes);
        boolean done = false;
        long firstTimestamp = -1;
        mStats.start();
        int errorCounter = 0;
        while (!done) {
            int index;
            if (mInFramesCount % 100 == 0) {
                Log.d(TAG, "SurfaceEncoder, Frames: " + mFramesAdded + " - inframes: " + mInFramesCount +
                        ", current loop: " + current_loop + " / "+loop + ", current time: " + mCurrentTime + " sec");
            }
            try {
                int flags = 0;
                if (doneReading(vc, current_loop, mCurrentTime, mInFramesCount)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    done = true;
                }
                if (vc.getDurationSec() > 0 && mCurrentTime >= vc.getDurationSec() |
                        vc.getDurationFrames() > 0 && mInFramesCount > vc.getDurationFrames()) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                }
                if (VP8_IS_BROKEN && isVP && isQCom && mInFramesCount > 0 &&
                        keyFrameInterval > 0 && mInFramesCount % (mFrameRate * keyFrameInterval) == 0) {
                    Bundle params = new Bundle();
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    mCodec.setParameters(params);
                }
                int size = -1;

                if (mIsCameraSource) {
                    try {
                        long timestamp = mOutputSurface.awaitNewImage();
                        if (firstTimestamp <= 0) {
                            firstTimestamp = timestamp;
                        }
                        mOutputSurface.drawImage();
                        int currentFrameNbr = (int) ((float) (mInFramesCount) / mKeepInterval);
                        int nextFrameNbr = (int) ((float) ((mInFramesCount + 1)) / mKeepInterval);

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
                            mInputSurface.setPresentationTime(ptsNsec);
                            mInputSurface.swapBuffers();
                            mStats.startEncodingFrame(ptsNsec / 1000, mInFramesCount);
                            //TODO: dynamic settings?
                            mFramesAdded++;
                        }
                        mInFramesCount++;
                    } catch (Exception ex) {
                        //TODO: make a real fix for when a camera encoder quits before another
                        // nd the surface is removed. The camera request needs to be updated.
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
                            if (doneReading(vc, current_loop, mCurrentTime, mInFramesCount)) {
                                done = true;
                            }

                            if (!done) {
                                if (mYuvReader != null) {
                                    Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                                    mYuvReader.openFile(vc.getInputfile());
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

        if (mYuvReader != null)
            mYuvReader.closeFile();

        if (mIsCameraSource) {
            mCameraSource.closeCamera();
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
        setRuntimeParameters(frameCount, mCodec, mRuntimeParams);
        buffer.clear();
        int read = mYuvReader.fillBuffer(buffer, size);
        int currentFrameNbr = (int) ((float) (frameCount) / mKeepInterval);
        int nextFrameNbr = (int) ((float) ((frameCount + 1)) / mKeepInterval);

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
            mInputSurface.makeCurrent();

            if (mBitmapRender == null){
                mBitmapRender = new BitmapRender();
                mBitmapRender.surfaceCreated();

                mSurfaceTexture = new SurfaceTexture(mBitmapRender.getTextureId());
            }
            mBitmapRender.drawFrame(mBitmap);
            mInputSurface.setPresentationTime(ptsUsec * 1000);
            mInputSurface.swapBuffers();
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

    private class CallbackHandler extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            Log.d(TAG, "onInputBufferAvailable");
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                MediaFormat oformat = mCodec.getOutputFormat();

                if (mWriteFile) {
                    mVideoTrack = mMuxer.addTrack(oformat);
                    mMuxer.start();
                }
                mCodec.releaseOutputBuffer(index, false /* render */);
            } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                //break;
            } else {
                try {
                    mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    ++mOutFramesCount;
                    if (mMuxer != null && mVideoTrack != -1) {
                        ByteBuffer data = mCodec.getOutputBuffer(index);
                        mMuxer.writeSampleData(mVideoTrack, data, info);
                    }

                    mCodec.releaseOutputBuffer(index, false /* render */);
                } catch (IllegalStateException ise) {
                    // Codec may be closed elsewhere...
                    Log.e(TAG,"Failed to relese buffer");
                }
                mCurrentTime = info.presentationTimeUs/1000000.0;
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.d(TAG, "onError: " + e.getDiagnosticInfo());
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            MediaFormat oformat = mCodec.getOutputFormat();
            checkConfig(oformat);
        }
    }

}
