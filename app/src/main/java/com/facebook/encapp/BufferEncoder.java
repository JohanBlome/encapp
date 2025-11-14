package com.facebook.encapp;

import static com.facebook.encapp.utils.MediaCodecInfoHelper.getMediaFormatValueFromKey;
import static com.facebook.encapp.utils.MediaCodecInfoHelper.mediaFormatComparison;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.PixFmt;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Set;


/**
 * Created by jobl on 2018-02-27.
 */

class BufferEncoder extends Encoder {
    private static final String TAG = "encapp.buffer_encoder";

    public BufferEncoder(Test test) {
        super(test);
        mStats = new Statistics("raw encoder", mTest);
    }

    public String start() {
        Log.d(TAG, "** Raw buffer encoding - " + mTest.getCommon().getDescription() + " **");
        mTest = TestDefinitionHelper.updateBasicSettings(mTest);
        if (mTest.hasRuntime())
            mRuntimeParams = mTest.getRuntime();
        if (mTest.getInput().hasRealtime())
            mRealtime = mTest.getInput().getRealtime();

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();
        mSkipped = 0;
        mFramesAdded = 0;
        Size sourceResolution = SizeUtils.parseXString(mTest.getInput().getResolution());
        PixFmt inputFmt = mTest.getInput().getPixFmt();
        mRefFramesizeInBytes = MediaCodecInfoHelper.frameSizeInBytes(inputFmt, sourceResolution.getWidth(), sourceResolution.getHeight());
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
            mStats.pushTimestamp("encoder.configure");
            mCodec.configure(
                    mediaFormat,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            mStats.pushTimestamp("encoder.configure");
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
            mStats.pushTimestamp("encoder.start");
            mCodec.start();
            mStats.pushTimestamp("encoder.start");
        } catch (Exception ex) {
            Log.e(TAG, "Start failed: " + ex.getMessage());
            return "Start encoding failed";
        }

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mKeepInterval = mReferenceFrameRate / mFrameRate;
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Log.d(TAG, "Create muxer");
        MediaFormat outputFormat = mCodec.getOutputFormat();
        // Log format
        Log.d(TAG, "Actual check of some formats after first mediaformat update.");
        Log.d(TAG, MediaCodecInfoHelper.mediaFormatToString(outputFormat));
        mMuxer = createMuxer(mCodec, outputFormat);
        // This is needed.
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        if (isVP) {
            mVideoTrack = mMuxer.addTrack(outputFormat);
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
        int failures = 0;
        MediaFormat currentOutputFormat = mCodec.getOutputFormat();
        Dictionary<String, Object> latestFrameChanges = null;
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
                long timeoutUs = VIDEO_CODEC_WAIT_TIME_US;
                index = mCodec.dequeueInputBuffer(timeoutUs);
                int flags = 0;

                if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    input_done = true;
                }
                if (mRealtime) {
                    sleepUntilNextFrame();
                }
                if (index >= 0) {
                    failures = 0;
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
                    failures += 1;
                    if (failures >= VIDEO_CODEC_MAX_INPUT_SEC) {
                        // too many consecutive failures
                        return "dequeueInputBuffer(): Too many consecutive failures";
                    }
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
            index = 1;
            while (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                try {
                    long timeoutUs = VIDEO_CODEC_WAIT_TIME_US;
                    index = mCodec.dequeueOutputBuffer(info, timeoutUs);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // check if the input is already done
                        if (input_done) {
                            output_done = true;
                        }
                        // otherwise ignore
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (Build.VERSION.SDK_INT >= 29) {
                            MediaFormat oformat = mCodec.getOutputFormat();
                            latestFrameChanges = mediaFormatComparison(currentOutputFormat, oformat);
                            currentOutputFormat = oformat;
                        }
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
                            FrameInfo frameInfo = mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                                    (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                            ++mOutFramesCount;
                            frameInfo.addInfo(latestFrameChanges);
                            latestFrameChanges = null;
                            if (mMuxer != null && mVideoTrack != -1) {
                                ByteBuffer data = mCodec.getOutputBuffer(index);
                                mMuxer.writeSampleData(mVideoTrack, data, info);
                            }
                            mCodec.releaseOutputBuffer(index, false /* render */);
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
