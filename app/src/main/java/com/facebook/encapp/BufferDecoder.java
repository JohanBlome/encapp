package com.facebook.encapp;

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
import com.facebook.encapp.utils.OutputMultiplier;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

class BufferDecoder extends Encoder {

    protected static final String TAG = "encapp.decoder";
    // Flag to dump decoded YUV
    private static final boolean YUV_DUMP = false;

    MediaExtractor mExtractor;
    MediaCodec mDecoder;

    public BufferDecoder(Test test) {
        super(test);
    }

    public String start(OutputMultiplier multiplier) {
        return start();
    }

    public String start() {
        Log.d(TAG,"** Buffer decoding - " + mTest.getCommon().getDescription());
        mTest = TestDefinitionHelper.checkAnUpdateBasicSettings(mTest);

        if (mTest.getInput().hasRealtime()) {
            mRealtime = mTest.getInput().getRealtime();
        }

        mFrameRate = mTest.getConfigure().getFramerate();

        mExtractor = new MediaExtractor();

        mStats = new Statistics("decoder", mTest);

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
            mExtractor.selectTrack(trackNum);
            inputFormat = mExtractor.getTrackFormat(trackNum);
            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }

            Log.d(TAG, "Create decoder by name: " + mTest.getConfigure().getCodec());
            mDecoder = MediaCodec.createByCodecName(mTest.getConfigure().getCodec());

            Log.d(TAG, "MediaFormat (test)");
            logMediaFormat(inputFormat);

            Log.d(TAG, "Configure: " + mDecoder.getName());
            mDecoder.configure(inputFormat, null, null, 0);
            Log.d(TAG, "MediaFormat (post-test)");
            logMediaFormat(mDecoder.getInputFormat());
            mStats.setDecoderMediaFormat(mDecoder.getInputFormat());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mStats.setDecoderName(mDecoder.getCodecInfo().getCanonicalName());
            } else {
                mStats.setDecoderName(mDecoder.getCodecInfo().getName());
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
        mRefFramesizeInBytes = (int) (res.getWidth() * res.getHeight() * 1.5);

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
        if(YUV_DUMP) {
            String outputYUVName = mStats.getId() + ".yuv";
            Log.d(TAG, "YUV Filename: "+ outputYUVName);
            file = new File(Environment.getExternalStorageDirectory() + "/" + File.separator + outputYUVName);
            file.delete();
            file.createNewFile();
            fo = new FileOutputStream(file);
        }

        boolean outputDone = false;
        boolean inputDone = false;

        mLastTime = SystemClock.elapsedRealtimeNanos() / 1000;
        while (!outputDone) {
            int index;
            long presentationTimeUs = 0L;

            // Feed more data to the decoder.
            if (!inputDone) {
                index = mDecoder.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US);
                if (index >= 0) {
                    ByteBuffer inputBuffer = mDecoder.getInputBuffer(index);
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuffer's position, limit, etc.
                    int chunkSize = mExtractor.readSampleData(inputBuffer, 0);
                    if (chunkSize < 0) {
                        // End of stream -- send empty frame with EOS flag set.
                        mDecoder.queueInputBuffer(index, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        if (mExtractor.getSampleTrackIndex() != trackIndex) {
                            Log.w(TAG, "WEIRD: got sample from track " +
                                    mExtractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }
                        presentationTimeUs = mExtractor.getSampleTime();
                        mStats.startDecodingFrame(presentationTimeUs, chunkSize, 0);
                        mDecoder.queueInputBuffer(index, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/);

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
                    MediaFormat newFormat = mDecoder.getOutputFormat();
                    Log.d(TAG, "decoder output format changed: " + newFormat);
                } else if(index >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }

                    ByteBuffer outputBuf = mDecoder.getOutputBuffer(index);
                    if(outputBuf.limit() != 0) {
                        mStats.stopDecodingFrame(info.presentationTimeUs);
                    }
                    outputBuf.position(info.offset);
                    outputBuf.limit(info.offset + info.size);
                    outputBuf.get(outData);

                    if(YUV_DUMP) {
                        if (file.exists()) {
                            fo.write(outData);
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
        if(YUV_DUMP) fo.close();
    }

    public void stopAllActivity(){}

    public void release() {
    }
}
