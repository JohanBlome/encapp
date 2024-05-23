package com.facebook.encapp;

import static com.facebook.encapp.utils.MediaCodecInfoHelper.mediaFormatComparison;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Locale;

class BufferDecoder extends Encoder {
    protected static final String TAG = "encapp.decoder";

    MediaExtractor mExtractor;
    MediaCodec mDecoder;
    // Flag to dump decoded YUV
    boolean mDecodeDump = false;

    public BufferDecoder(Test test) {
        super(test);
        mStats = new Statistics("decoder", mTest);
    }

    public String start(OutputMultiplier multiplier) {
        return start();
    }

    public String start() {
        Log.d(TAG,"** Buffer decoding - " + mTest.getCommon().getDescription());
        try {
            if (TestDefinitionHelper.checkBasicSettings(mTest)) {
                mTest = TestDefinitionHelper.updateBasicSettings(mTest);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error: " + e.getMessage());
        }

        if (mTest.getInput().hasRealtime()) {
            mRealtime = mTest.getInput().getRealtime();
        }

        if (mTest.getConfigure().hasDecodeDump()) {
          mDecodeDump = mTest.getConfigure().getDecodeDump();
        }

        mFrameRate = mTest.getConfigure().getFramerate();
        Log.d(TAG, "Create extractor");
        mExtractor = new MediaExtractor();

        MediaFormat inputFormat = null;
        int trackNum = 0;
        try {
            mExtractor.setDataSource(mTest.getInput().getFilepath());
            int tracks = mExtractor.getTrackCount();
            for (int track = 0; track < tracks; track++) {
                inputFormat = mExtractor.getTrackFormat(track);
                if (inputFormat.containsKey(MediaFormat.KEY_MIME) &&
                        inputFormat.getString(MediaFormat.KEY_MIME).toLowerCase(Locale.US).contains("video")) {
                    trackNum = track;
                }
            }
            Log.d(TAG, "Select track");
            mExtractor.selectTrack(trackNum);
            inputFormat = mExtractor.getTrackFormat(trackNum);
            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }

            Log.d(TAG, "Create decoder)");
            if (mTest.getDecoderConfigure().hasCodec()) {
                Log.d(TAG, "Create decoder by name: " + mTest.getDecoderConfigure().getCodec());
                mDecoder = MediaCodec.createByCodecName(mTest.getDecoderConfigure().getCodec());
            } else {
                Log.d(TAG, "Create decoder by mime: " + inputFormat.getString(MediaFormat.KEY_MIME));
                mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setDecoderIsHardwareAccelerated(mDecoder.getCodecInfo().isHardwareAccelerated());
            }
            Log.d(TAG, "MediaFormat (test)");
            logMediaFormat(inputFormat);

            TestDefinitionHelper.setDecoderConfigureParams(mTest, inputFormat);
            Log.d(TAG, "Configure: " + mDecoder.getName());
            mDecoder.configure(inputFormat, null, null, 0);
            Log.d(TAG, "MediaFormat (post-test)");
            logMediaFormat(mDecoder.getInputFormat());
            mStats.setDecoderMediaFormat(mDecoder.getInputFormat());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setDecoder(mDecoder.getCodecInfo().getCanonicalName());
            } else {
                mStats.setDecoder(mDecoder.getCodecInfo().getName());
            }
        } catch (IOException iox) {
            mExtractor.release();
            Log.e(TAG, "Failed to create decoder: " + iox.getMessage());
            return "Failed to create decoder";
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: " + cex.getMessage());
            return "Failed to create decoder";
        }

        try {
            Log.d(TAG, "Start decoder");
            mDecoder.start();
        } catch (Exception ex) {
            Log.e(TAG, "Start failed: " + ex.getMessage());
            return "Start decoding failed";
        }

        Size res = SizeUtils.parseXString(mTest.getInput().getResolution());

        mReferenceFrameRate = mTest.getInput().getFramerate();
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

        if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            mReferenceFrameRate = (float) (inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
        }
        if (mFrameRate <= 0) {
            mFrameRate = mReferenceFrameRate;
        }
        mKeepInterval = mReferenceFrameRate / mFrameRate;

        //mInitDone = true;
        synchronized (this) {
            Log.d(TAG, "Wait for synchronized start");
            try {
                mInitDone = true;
                wait(WAIT_TIME_MS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mFrameTimeUsec = calculateFrameTimingUsec(mFrameRate);
        mStats.start();
        try {
            // start decoding frames
            decodeFrames(trackNum);
        } catch (Exception e) {
            e.printStackTrace();
        }
        mStats.stop();

        try {
            if (mCodec != null) {
                mCodec.flush();
                mCodec.stop();
                mCodec.release();
            }
            if (mDecoder != null) {
                mDecoder.flush();
                mDecoder.stop();
                mDecoder.release();
            }
        } catch (IllegalStateException iex) {
            Log.e(TAG, "Failed to shut down:" + iex.getLocalizedMessage());
        }

        if (mExtractor != null)
            mExtractor.release();
        Log.d(TAG, "Stop writer");
        mDataWriter.stopWriter();

        return "";
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
    }

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
    }

    void decodeFrames(int trackIndex) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        /* YUV file dump */
        File file = null;
        OutputStream fo = null;
        if (mDecodeDump) {
            String outputYUVName = mStats.getId() + ".yuv";
            Log.d(TAG, "YUV Filename: "+ outputYUVName);
            file = new File(Environment.getExternalStorageDirectory() + "/" + File.separator + outputYUVName);
            file.delete();
            file.createNewFile();
            fo = new FileOutputStream(file);
        }

        boolean outputDone = false;
        boolean inputDone = false;
        int currentLoop = 1;
        MediaFormat currentOutputFormat = mDecoder.getOutputFormat();
        Dictionary<String, Object> latestFrameChanges = null;
        mLastTime = SystemClock.elapsedRealtimeNanos() / 1000;
        while (!outputDone) {
            int index;
            long presentationTimeUs = 0L;
            if (mInFramesCount % 100 == 0 && MainActivity.isStable()) {
                Log.d(TAG, mTest.getCommon().getId() + " - " +
                        "frames: " + mFramesAdded +
                        " inframes: " + mInFramesCount +
                        " current_loop: " + currentLoop +
                        " current_time: " + mCurrentTimeSec);
            }
            // Feed more data to the decoder.
            if (!inputDone) {
                index = mDecoder.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US);
                if (index >= 0) {
                    ByteBuffer inputBuffer = mDecoder.getInputBuffer(index);
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuffer's position, limit, etc.
                    int chunkSize = mExtractor.readSampleData(inputBuffer, 0);
                    int flags = 0;
                    if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, false)) {
                        flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        inputDone = true;
                    }
                    if (chunkSize < 0) {
                        if (mYuvReader != null) {
                            mYuvReader.closeFile();
                        }
                        currentLoop++;

                        if (doneReading(mTest, mYuvReader, mInFramesCount, mCurrentTimeSec, true) || mYuvReader == null) {
                            // Set EOS flag and call encoder
                            Log.d(TAG, "*******************************");
                            Log.d(TAG, "End of stream");

                            flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                            // End of stream -- send empty frame with EOS flag set.
                            mDecoder.queueInputBuffer(index, 0, 0, 0L,
                                    flags);
                            inputDone = true;
                        }

                        if (!inputDone) {
                            Log.d(TAG, " *********** OPEN FILE AGAIN *******");
                            mYuvReader.openFile(mTest.getInput().getFilepath(), mTest.getInput().getPixFmt());
                            Log.d(TAG, "*** Loop ended start " + currentLoop + "***");
                        }

                    } else {
                        if (mExtractor.getSampleTrackIndex() != trackIndex) {
                            Log.w(TAG, "WEIRD: got sample from track " +
                                    mExtractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }
                        presentationTimeUs = mExtractor.getSampleTime();
                        mCurrentTimeSec = info.presentationTimeUs / 1000000.0;
                        mStats.startDecodingFrame(presentationTimeUs, chunkSize, flags);

                        mDecoder.queueInputBuffer(index, 0, chunkSize,
                                presentationTimeUs, flags /*flags*/);

                        mInFramesCount++;
                        mExtractor.advance();
                    }

                } else {
                    Log.d(TAG, "Input buffer not available");
                }
            }

            if (!outputDone) {
                index = mDecoder.dequeueOutputBuffer(info, (long) mFrameTimeUsec);
                byte[] outData = new byte[info.size];
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        MediaFormat oformat = mDecoder.getOutputFormat();
                        latestFrameChanges = mediaFormatComparison(currentOutputFormat, oformat);
                        currentOutputFormat = oformat;
                    }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mDecoder.getOutputFormat();
                } else if(index >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        Log.d(TAG, "Output EOS");
                    }

                    ByteBuffer outputBuf = mDecoder.getOutputBuffer(index);
                    if (outputBuf != null) {
                        int limit = outputBuf.limit();
                        if(limit != 0) {
                            FrameInfo frameInfo = mStats.stopDecodingFrame(info.presentationTimeUs);
                            frameInfo.addInfo(latestFrameChanges);
                            latestFrameChanges = null;

                            outputBuf.position(info.offset);
                            outputBuf.limit(info.offset + info.size);
                            outputBuf.get(outData);
                            if (mDecodeDump) {
                                if (file.exists()) {
                                    fo.write(outData);
                                }
                            }
                        }
                    }
                    try {
                        mDecoder.releaseOutputBuffer(index, 0);
                    } catch (IllegalStateException isx) {
                        Log.e(TAG, "Illegal state exception when trying to release output buffers");
                    }
                }
                if(mRealtime) sleepUntilNextFrame(mFrameTimeUsec);
            }
        }
        if (mDecodeDump) fo.close();

        Log.d(TAG, "Decoding done, leaving decoded: " + mStats.getDecodedFrameCount());
    }

    public void stopAllActivity(){}

    public void release() {
    }
}
