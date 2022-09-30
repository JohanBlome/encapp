package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;


/**
 * Created by jobl on 2018-02-27.
 */

class BufferEncoder extends Encoder {
    public String start(Test td, OutputMultiplier multiplier) {
        //Maybe we can show it?
        return start(td);
    }

    public String start(
            Test test) {
        Log.d(TAG, "** Raw buffer encoding - " + test.getCommon().getDescription() + " **");
        test = TestDefinitionHelper.checkAnUpdateBasicSettings(test);
        if (test.hasRuntime())
            mRuntimeParams = test.getRuntime();
        if (test.getInput().hasRealtime())
            mRealtime = test.getInput().getRealtime();

        mFrameRate = test.getConfigure().getFramerate();
        mWriteFile = !test.getConfigure().hasEncode() || test.getConfigure().getEncode();
        mSkipped = 0;
        mFramesAdded = 0;
        Size sourceResolution = SizeUtils.parseXString(test.getInput().getResolution());
        mRefFramesizeInBytes = (int) (sourceResolution.getWidth() *
                sourceResolution.getHeight() * 1.5);

        mRealtime = test.getInput().getRealtime();
        mStats = new Statistics("raw encoder", test);
        mYuvReader = new FileReader();

        if (!mYuvReader.openFile(checkFilePath(test.getInput().getFilepath()))) {
            return "\nCould not open file";
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
            Log.d(TAG, "MediaFormat (test)");
            checkMediaFormat(format);
            setConfigureParams(test, format);
            // Needed for the buffer input. this can be either nv12, nv21 or yuv420p
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            Log.d(TAG, "MediaFormat (configure)");
            checkMediaFormat(format);
            Log.d(TAG, "Configure: " + mCodec.getName());
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            Log.d(TAG, "MediaFormat (post-test)");
            checkMediaFormat(mCodec.getInputFormat());
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

        float mReferenceFrameRate = test.getInput().getFramerate();
        mKeepInterval = mReferenceFrameRate / mFrameRate;
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Log.d(TAG, "Create muxer");
        mMuxer = createMuxer(mCodec, mCodec.getOutputFormat(), true);

        // This is needed.
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        if (isVP) {
            mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
            mMuxer.start();
        }

        double currentTime = 0;
        int current_loop = 1;
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
        while (!done) {
            int index;
            if (mFramesAdded % 100 == 0) {
                Log.d(TAG, "Frames: " + mFramesAdded + " - inframes: " + mInFramesCount +
                        ", current loop: " + ", current time: " + currentTime + " sec");
            }
            try {
                index = mCodec.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
                int flags = 0;

                if (doneReading(test, mInFramesCount, mCurrentTimeSec, false)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    done = true;
                }
                if (index >= 0) {
                    int size = -1;

                    ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
                    while (size < 0 && !done) {
                        try {
                            size = queueInputBufferEncoder(
                                    mCodec,
                                    byteBuffer,
                                    index,
                                    mInFramesCount,
                                    flags,
                                    mRefFramesizeInBytes);

                            mInFramesCount++;
                        } catch (IllegalStateException isx) {
                            Log.e(TAG, "Queue encoder failed, " + index + ", mess: " + isx.getMessage());
                        }
                        if (size == -2) {
                            continue;
                        } else if (size <= 0) {
                            mYuvReader.closeFile();
                            current_loop++;
                            if (doneReading(test, mInFramesCount, mCurrentTimeSec, true)) {
                                done = true;
                            }

                            if (!done) {
                                Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                                mYuvReader.openFile(test.getInput().getFilepath());
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
                    ++mOutFramesCount;
                    if (mMuxer != null && mVideoTrack != -1) {
                        ByteBuffer data = mCodec.getOutputBuffer(index);
                        mMuxer.writeSampleData(mVideoTrack, data, info);
                    }
                    mCodec.releaseOutputBuffer(index, false /* render */);
                    currentTime = info.presentationTimeUs / 1000000.0;
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

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
    }

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
    }
}
