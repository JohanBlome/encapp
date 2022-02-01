package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import com.facebook.encapp.utils.ConfigureParam;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;


/**
 * Created by jobl on 2018-02-27.
 */

class BufferEncoder extends Encoder {
    public String encode(
            TestParams vc,
            boolean writeFile) {
        Log.d(TAG, "** Raw buffer encoding - " + vc.getDescription() + " **");
        mRuntimeParams = vc.getRuntimeParameters();
        mSkipped = 0;
        mFramesAdded = 0;
        mRefFramesizeInBytes = (int) (vc.getReferenceSize().getWidth() *
                vc.getReferenceSize().getHeight() * 1.5);
        mRealtime = vc.isRealtime();
        mWriteFile = writeFile;
        mStats = new Statistics("raw encoder", vc);
        mYuvReader = new FileReader();

        vc.addEncoderConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
        int loop = vc.getLoopCount();

        if (!mYuvReader.openFile(vc.getInputfile())) {
            return "\nCould not open file";
        }

        int keyFrameInterval = vc.getKeyframeRate();
        MediaFormat format;
        try {
            Log.d(TAG, "codec id: "+vc.getVideoEncoderIdentifier());
            String codecName = getCodecName(vc);
            mStats.setCodec(codecName);
            Log.d(TAG, "Create codec by name: " + codecName);
            mCodec = MediaCodec.createByCodecName(codecName);

            format = vc.createEncoderMediaFormat(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            checkConfigureParams(vc, format);
            setConfigureParams(vc, vc.getEncoderConfigure(), format);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            Log.d(TAG, "Format of encoder");
            checkConfig(format);
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);

            checkConfigureParams(vc, mCodec.getInputFormat());
            mStats.setEncoderMediaFormat(mCodec.getInputFormat());

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

        int inFramesCount = 0;
        int outFramesCount = 0;
        mFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        float mReferenceFrameRate = vc.getmReferenceFPS();
        mKeepInterval = mReferenceFrameRate / (float) mFrameRate;
        calculateFrameTiming();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        boolean isQCom = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".qcom");

        Log.d(TAG, "Create muxer");
        mMuxer = createMuxer(mCodec, mCodec.getOutputFormat(), true);
        if (isVP) {
            mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
            mMuxer.start();
        }
        double currentTime = 0;
        int current_loop = 1;
        boolean done = false;
        mStats.start();
        while (!done) {
            int index;
            if (mFramesAdded % 100 == 0) {
                Log.d(TAG, "Frames: " + mFramesAdded + " - inframes: " + inFramesCount +
                        ", current loop: " + current_loop + " / "+loop + ", current time: " + currentTime + " sec");
            }
            try {
                index = mCodec.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
                int flags = 0;

                if (doneReading(vc, current_loop, currentTime, inFramesCount)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    done = true;
                }
                if (index >= 0) {
                    int size = -1;
                    if (VP8_IS_BROKEN && isVP && isQCom && inFramesCount > 0 &&
                            keyFrameInterval > 0 && inFramesCount % (mFrameRate * keyFrameInterval) == 0) {
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        mCodec.setParameters(params);
                    }

                    ByteBuffer buffer = mCodec.getInputBuffer(index);
                    while (size < 0 && !done) {
                        try {
                            size = queueInputBufferEncoder(
                                    mCodec,
                                    buffer,
                                    index,
                                    inFramesCount,
                                    flags,
                                    mRefFramesizeInBytes);

                            inFramesCount++;
                        } catch (IllegalStateException isx) {
                            Log.e(TAG, "Queue encoder failed, " + index + ", mess: " + isx.getMessage());
                        }
                        if (size == -2) {
                            continue;
                        } else if (size <= 0) {
                            mYuvReader.closeFile();
                            current_loop++;
                            if (doneReading(vc, current_loop, currentTime, inFramesCount)) {
                                done = true;
                            }

                            if (!done) {
                                Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                                mYuvReader.openFile(vc.getInputfile());
                                Log.d(TAG, "*** Loop ended start " + current_loop + "***");
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "dequeueInputBuffer, no index, " + index);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            index = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Just ignore
            } else if (index >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    MediaFormat oformat = mCodec.getOutputFormat();

                    if (mWriteFile) {
                        mVideoTrack = mMuxer.addTrack(oformat);
                        mMuxer.start();
                    }
                    mCodec.releaseOutputBuffer(index, false /* render */);
                } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                } else {
                    mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                                    (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    ++outFramesCount;
                    if (mMuxer != null && mVideoTrack != -1) {
                        ByteBuffer data = mCodec.getOutputBuffer(index);
                        mMuxer.writeSampleData(mVideoTrack, data, info);
                    }
                    mCodec.releaseOutputBuffer(index, false /* render */);
                    currentTime = info.presentationTimeUs/1000000.0;
                }
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

        mYuvReader.closeFile();
        return "";
    }


    /**
     * Fills input buffer for encoder from YUV buffers.
     *
     * @return size of enqueued data.
     */
    private int queueInputBufferEncoder(
            MediaCodec codec, ByteBuffer buffer, int index, int frameCount, int flags, int size) {
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
            mFramesAdded++;
            long ptsUsec = computePresentationTime(frameCount);
            if (mRealtime) {
                sleepUntilNextFrame();
            }
            mStats.startEncodingFrame(ptsUsec, frameCount);
            codec.queueInputBuffer(index, 0 /* offset */, read, ptsUsec /* timeUs */, flags);
        } else {
            read = -1;
        }

        return read;
    }
}
