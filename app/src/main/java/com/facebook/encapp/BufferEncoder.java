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
    public BufferEncoder(Test test) {
        super(test);
        mStats = new Statistics("raw encoder", mTest);
    }

    public String start() {
        Log.d(TAG, "** Raw buffer encoding - " + mTest.getCommon().getDescription() + " **");
        try {
            if (TestDefinitionHelper.checkBasicSettings(mTest)) {
                mTest = TestDefinitionHelper.updateBasicSettings(mTest);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error: " + e.getMessage());
        }
        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();
        if (mTest.getInput().hasRealtime())
            mRealtime = mTest.getInput().getRealtime();

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();
        mSkipped = 0;
        mFramesAdded = 0;
        Size sourceResolution = SizeUtils.parseXString(mTest.getInput().getResolution());
        // TODO(chema): this assumes 4:2:0 subsampling, and therefore YUV
        mRefFramesizeInBytes = (int) (sourceResolution.getWidth() *
                sourceResolution.getHeight() * 1.5);

        mRealtime = mTest.getInput().getRealtime();
        mYuvReader = new FileReader();

        if (!mYuvReader.openFile(checkFilePath(mTest.getInput().getFilepath()), mTest.getInput().getPixFmt())) {
            return "Could not open file";
        }

        MediaFormat mediaFormat;
        boolean useImage = false;
        try {
            // Unless we have a mime, do lookup
            if (mTest.getConfigure().getMime().length() == 0) {
                try {
                    mTest = setCodecNameAndIdentifier(mTest);
                } catch (Exception e) {
                    return e.getMessage();
                }
                Log.d(TAG, "codec: " + mTest.getConfigure().getCodec() + " mime: " + mTest.getConfigure().getMime());
            }
            Log.d(TAG, "Create codec by name: " + mTest.getConfigure().getCodec());
            mCodec = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());

            mediaFormat = TestDefinitionHelper.buildMediaFormat(mTest);
            Log.d(TAG, "MediaFormat (mTest)");
            logMediaFormat(mediaFormat);
            setConfigureParams(mTest, mediaFormat);
            Log.d(TAG, "MediaFormat (configure)");
            logMediaFormat(mediaFormat);
            if (mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                useImage = true;
            }
            Log.d(TAG, "useImage: " + useImage);
            Log.d(TAG, "Configure: " + mCodec.getName());
            mCodec.configure(
                    mediaFormat,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            Log.d(TAG, "MediaFormat (post-mTest)");
            logMediaFormat(mCodec.getInputFormat());
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

        float mReferenceFrameRate = mTest.getInput().getFramerate();
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

        int current_loop = 1;
        boolean input_done = false;
        boolean output_done = false;
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
        while (!input_done || !output_done) {
            int index;
            if (mFramesAdded % 100 == 0) {
                Log.d(TAG, mTest.getCommon().getId() + " - BufferEncoder: frames: " + mFramesAdded +
                        " inframes: " + mInFramesCount +
                        " current_loop: " + current_loop +
                        " current_time: " + mCurrentTimeSec);
            }
            // 1. process the encoder input
            try {
                index = mCodec.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
                int flags = 0;

                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    input_done = true;
                }
                if (index >= 0) {
                    int size = -1;
                    // get the ByteBuffer where we will write the image to encode
                    ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
                    while (size < 0 && !input_done) {
                        try {
                            size = queueInputBufferEncoder(
                                    mYuvReader,
                                    mCodec,
                                    byteBuffer,
                                    index,
                                    mInFramesCount,
                                    flags,
                                    mRefFramesizeInBytes,
                                    useImage);

                            mInFramesCount++;
                        } catch (IllegalStateException isx) {
                            Log.e(TAG, "Queue encoder failed, " + index + ", mess: " + isx.getMessage());
                        }
                        if (size == -2) {
                            continue;
                        } else if (size <= 0) {
                            // restart the loop
                            mYuvReader.closeFile();
                            current_loop++;
                            if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, true)) {
                                input_done = true;
                                // Set EOS flag and call encoder
                                flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                                size = queueInputBufferEncoder(
                                     mYuvReader,
                                     mCodec,
                                     byteBuffer,
                                     index,
                                     mInFramesCount,
                                     flags,
                                     mRefFramesizeInBytes,
                                     useImage);
                            }

                            if (!input_done) {
                                Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                                mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt());
                                Log.d(TAG, "*** Loop ended start " + current_loop + "***");
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "dequeueInputBuffer, no index, " + index);
                }
            } catch (MediaCodec.CodecException ex) {
                Log.e(TAG, "dequeueInputBuffer: MediaCodec.CodecException error");
                ex.printStackTrace();
                return "dequeueInputBuffer: MediaCodec.CodecException error";
            } catch (IllegalStateException ex) {
                Log.e(TAG, "dequeueInputBuffer: IllegalStateException error");
                ex.printStackTrace();
                return "dequeueInputBuffer: IllegalStateException error";
            }

            // 2. process the encoder output
            try {
                index = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // check if the input is already done
                    if (input_done) {
                        output_done = true;
                    }
                    // otherwise ignore
                } else if (index >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        MediaFormat oformat = mCodec.getOutputFormat();

                        if (mWriteFile) {
                            mVideoTrack = mMuxer.addTrack(oformat);
                            mMuxer.start();
                        }
                        mCodec.releaseOutputBuffer(index, false /* render */);
                    } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        output_done = true;
                    } else {
                        mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                        ++mOutFramesCount;
                        if (mMuxer != null && mVideoTrack != -1) {
                            ByteBuffer data = mCodec.getOutputBuffer(index);
                            mMuxer.writeSampleData(mVideoTrack, data, info);
                        }
                        mCodec.releaseOutputBuffer(index, false /* render */);
                        mCurrentTimeSec = info.presentationTimeUs / 1000000.0;
                    }
                }
            } catch (MediaCodec.CodecException ex) {
                Log.e(TAG, "dequeueOutputBuffer: MediaCodec.CodecException error");
                ex.printStackTrace();
                return "dequeueOutputBuffer: MediaCodec.CodecException error";
            } catch (IllegalStateException ex) {
                Log.e(TAG, "dequeueOutputBuffer: IllegalStateException error");
                ex.printStackTrace();
                return "dequeueOutputBuffer: IllegalStateException error";
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

    public void stopAllActivity(){}

    public void release() {
    }
}
