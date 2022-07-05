package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.Configure;
import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.Runtime;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.Assert;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FpsMeasure;
import com.facebook.encapp.utils.FrameBuffer;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Encoder {
    protected static final String TAG = "encapp.encoder";
    protected static final long VIDEO_CODEC_WAIT_TIME_US = 1000000;
    final static int WAIT_TIME_MS = 30000;  // 30 secs
    final static int WAIT_TIME_SHORT_MS = 1000;  // 1 sec
    protected float mFrameRate = 30;
    float mReferenceFrameRate = 30;
    protected double mFrameTimeUsec = 0;
    protected double mRefFrameTime = 0;
    double mCurrentTimeSec;
    double mFirstFrameTimestampUsec = -1;
    long mLastTime = -1;
    protected float mKeepInterval = 1.0f;
    protected MediaCodec mCodec;
    protected MediaMuxer mMuxer;
    protected int mSkipped = 0;
    protected int mFramesAdded = 0;
    protected int mRefFramesizeInBytes = (int) (1280 * 720 * 1.5);
    protected boolean mWriteFile = true;
    protected Statistics mStats;
    protected String mFilename;
    protected Test mTest;
    protected boolean mDropNext;
    protected Runtime mRuntimeParams;
    protected FileReader mYuvReader;
    protected int mVideoTrack = -1;
    long mPts = 132;
    boolean mRealtime = false;


    int mOutFramesCount = 0;
    int mInFramesCount = 0;
    boolean mInitDone = false;
    DataWriter mDataWriter;
    FpsMeasure mFpsMeasure;
    boolean mStable = true;

    public Encoder() {
        mDataWriter = new DataWriter();
        mDataWriter.start();
    }

    public static void checkMediaFormat(MediaFormat format) {

        Log.d(TAG, "Check config: version = " + Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 29) {
            Set<String> features = format.getFeatures();
            for (String feature : features) {
                Log.d(TAG, "MediaFormat: " + feature);
            }

            Set<String> keys = format.getKeys();
            for (String key : keys) {
                int type = format.getValueTypeForKey(key);
                switch (type) {
                    case MediaFormat.TYPE_BYTE_BUFFER:
                        Log.d(TAG, "MediaFormat: " + key + " - bytebuffer: " + format.getByteBuffer(key));
                        break;
                    case MediaFormat.TYPE_FLOAT:
                        Log.d(TAG, "MediaFormat: " + key + " - float: " + format.getFloat(key));
                        break;
                    case MediaFormat.TYPE_INTEGER:
                        Log.d(TAG, "MediaFormat: " + key + " - integer: " + format.getInteger(key));
                        break;
                    case MediaFormat.TYPE_LONG:
                        Log.d(TAG, "MediaFormat: " + key + " - long: " + format.getLong(key));
                        break;
                    case MediaFormat.TYPE_NULL:
                        Log.d(TAG, "MediaFormat: " + key + " - null");
                        break;
                    case MediaFormat.TYPE_STRING:
                        Log.d(TAG, "MediaFormat: " + key + " - string: " + format.getString(key));
                        break;
                }

            }
        } else {

            StringBuilder str = new StringBuilder();
            String[] keys = {
                    MediaFormat.KEY_BIT_RATE,
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaFormat.KEY_MIME,
                    MediaFormat.KEY_FRAME_RATE,
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaFormat.KEY_COLOR_RANGE,
                    MediaFormat.KEY_COLOR_STANDARD,
                    MediaFormat.KEY_COLOR_TRANSFER,
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    MediaFormat.KEY_LATENCY,
                    MediaFormat.KEY_LEVEL,
                    MediaFormat.KEY_PROFILE,
                    MediaFormat.KEY_SLICE_HEIGHT,
                    MediaFormat.KEY_SLICE_HEIGHT,
                    MediaFormat.KEY_TEMPORAL_LAYERING,
            };

            for (String key : keys) {
                if (format.containsKey(key)) {
                    String val = "";
                    try {
                        val = format.getString(key);
                    } catch (ClassCastException ex1) {
                        try {
                            val = Integer.toString(format.getInteger(key));
                        } catch (ClassCastException ex2) {
                            try {
                                val = Float.toString(format.getFloat(key));
                            } catch (ClassCastException ex3) {
                                try {
                                    val = Long.toString(format.getLong(key));
                                } catch (ClassCastException ex4) {
                                    Log.d(TAG, "Failed to get key: " + key);
                                }
                            }

                        }
                    }
                    if (val != null && val.length() > 0) {
                        Log.d(TAG, "MediaFormat: " + key + " - string: " + val);
                    }
                }
            }

        }
    }

    public abstract String start(Test td);

    public boolean isStable() {
        return mStable;
    }

    public String checkFilePath(String path) {
        if (path.startsWith(Environment.getExternalStorageDirectory().getPath())) {
            return path;
        }

        int last_dir = path.lastIndexOf('/');
        if (last_dir == -1) {
            return Environment.getExternalStorageDirectory().getPath() + "/" + path;
        }

        return Environment.getExternalStorageDirectory().getPath() + "/" + path.substring(last_dir);
    }

    protected void sleepUntilNextFrame(double frameTimeUsec) {
        long now = System.nanoTime() / 1000; //To Us
        long sleepTimeMs = (long)(frameTimeUsec - (now - mLastTime)) / 1000; //To ms
        if (sleepTimeMs < 0) {
            // We have been delayed. Run forward.
            sleepTimeMs = 0;
        }
        if (sleepTimeMs > 0) {
            try {
                Thread.sleep(sleepTimeMs);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mLastTime = now;
    }

    protected void sleepUntilNextFrame() {
        long now = System.nanoTime() / 1000; //To Us
        long sleepTimeMs = (long)(mFrameTimeUsec - (now - mLastTime)) / 1000; //To ms
        if (sleepTimeMs < 0) {
            // We have been delayed. Run forward.
            sleepTimeMs = 0;
        }
        if (sleepTimeMs > 0) {
            try {
                Thread.sleep(sleepTimeMs);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mLastTime = now;
    }


    /**
     * Generates the presentation time for frameIndex, in microseconds.
     */
    protected long computePresentationTimeUsec(int frameIndex, double frameTimeUsec) {
        return mPts + (long) (frameIndex * frameTimeUsec);
    }

    protected double calculateFrameTimingUsec(float frameRate) {
        return mFrameTimeUsec = 1000000.0 / frameRate;
    }

    public boolean initDone() {
        return mInitDone;
    }

    protected MediaMuxer createMuxer(MediaCodec encoder, MediaFormat format, boolean useStatId) {
        if (!useStatId) {
            Log.d(TAG, "Bitrate mode: " + (format.containsKey(MediaFormat.KEY_BITRATE_MODE) ? format.getInteger(MediaFormat.KEY_BITRATE_MODE) : 0));
            mFilename = String.format(Locale.US, Environment.getExternalStorageDirectory().getPath() + "/%s_%dfps_%dx%d_%dbps_iint%d_m%d.mp4",
                    encoder.getCodecInfo().getName().toLowerCase(Locale.US),
                    (format.containsKey(MediaFormat.KEY_FRAME_RATE) ? format.getInteger(MediaFormat.KEY_FRAME_RATE) : 0),
                    (format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0),
                    (format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0),
                    (format.containsKey(MediaFormat.KEY_BIT_RATE) ? format.getInteger(MediaFormat.KEY_BIT_RATE) : 0),
                    (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL) ? format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL) : 0),
                    (format.containsKey(MediaFormat.KEY_BITRATE_MODE) ? format.getInteger(MediaFormat.KEY_BITRATE_MODE) : 0));
        } else {
            mFilename = mStats.getId() + ".mp4";
        }
        int type = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        if (encoder.getCodecInfo().getName().toLowerCase(Locale.US).contains("vp")) {
            if (!useStatId) {
                mFilename = String.format(Locale.US, Environment.getExternalStorageDirectory().getPath() + "/%s_%dfps_%dx%d_%dbps_iint%d_m%d.webm",
                        encoder.getCodecInfo().getName().toLowerCase(Locale.US),
                        (format.containsKey(MediaFormat.KEY_FRAME_RATE) ? format.getInteger(MediaFormat.KEY_FRAME_RATE) : 0),
                        (format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0),
                        (format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0),
                        (format.containsKey(MediaFormat.KEY_BIT_RATE) ? format.getInteger(MediaFormat.KEY_BIT_RATE) : 0),
                        (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL) ? format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL) : 0),
                        (format.containsKey(MediaFormat.KEY_BITRATE_MODE) ? format.getInteger(MediaFormat.KEY_BITRATE_MODE) : 0));
            } else {
                mFilename = mStats.getId() + ".webm";
            }
            type = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
        }
        try {
            String fullFilename = Environment.getExternalStorageDirectory().getPath() + "/" + mFilename;
            Log.d(TAG, "Create mMuxer with type " + type + " and filename: " + fullFilename);
            mMuxer = new MediaMuxer(fullFilename, type);
        } catch (IOException e) {
            Log.d(TAG, "FAILED Create mMuxer with type " + type + " and filename: " + mFilename);
            e.printStackTrace();
        }

        mStats.setEncodedfile(mFilename);
        return mMuxer;
    }

    public String getOutputFilename() {
        return mFilename;
    }

    @NonNull
    protected Vector<MediaCodecInfo> getMediaCodecInfos(MediaCodecInfo[] codecInfos, String id) {
        Vector<MediaCodecInfo> matching = new Vector<>();
        for (MediaCodecInfo info : codecInfos) {
            //Handle special case of codecs with naming schemes consisting of substring of another

            if (info.isEncoder()) {
                if (info.getSupportedTypes().length > 0 &&
                        info.getSupportedTypes()[0].toLowerCase(Locale.US).contains("video")) {
                    if (info.getName().toLowerCase(Locale.US).equals(id.toLowerCase(Locale.US))) {
                        //Break on exact match
                        matching.clear();
                        matching.add(info);
                        break;
                    } else if (info.getName().toLowerCase(Locale.US).contains(id.toLowerCase(Locale.US))) {
                        matching.add(info);
                    }
                }
            }
        }
        return matching;
    }

    public Statistics getStatistics() {
        return mStats;
    }

    protected Test setCodecNameAndIdentifier(Test test) {
        String partialName = test.getConfigure().getCodec();
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        String codecName = "";
        Vector<MediaCodecInfo> matching = getMediaCodecInfos(codecInfos, partialName);

        if (matching.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("\nAmbigous codecs for " + partialName + "\n" + matching.size() + " codecs matching.\n");
            for (MediaCodecInfo info : matching) {
                sb.append(info.getName() + "\n");
            }
            Assert.assertTrue(sb.toString(), false);
        } else if (matching.size() == 0) {
            Assert.assertTrue("\nNo matching codecs to : " + partialName, false);
        } else {
            Log.d(TAG, "Set codec and mime: " + codecName);
            Test.Builder builder = Test.newBuilder(test);
            Configure configure = Configure.
                    newBuilder(test.
                            getConfigure())
                    .setCodec(matching.get(0).getName())
                    .setMime(matching.get(0).getSupportedTypes()[0]).build();
            builder.setConfigure(configure);
            return builder.build();
        }
        return test;
    }

    protected void setConfigureParams(Test test, MediaFormat format) {
        List<Configure.Parameter> params = test.getConfigure().getParameterList();
        for (Configure.Parameter param : params) {
            switch (param.getType().getNumber()) {
                case DataValueType.intType_VALUE:
                    format.setInteger(param.getKey(), Integer.parseInt(param.getValue()));
                    break;
                case DataValueType.stringType_VALUE:
                    format.setString(param.getKey(), param.getValue());
                    break;
            }
        }
    }

    boolean doneReading(Test test, int frame, double time, boolean loop) {
        boolean done = false;
        if (!test.getInput().hasStoptimeSec() && !test.getInput().hasPlayoutFrames() && loop) {
            return true;
        }
        if (test.getInput().hasPlayoutFrames() && test.getInput().getPlayoutFrames() > 0) {
            if (frame >= test.getInput().getPlayoutFrames()) {
                done = true;
            }
        }
        if (test.getInput().hasStoptimeSec() && test.getInput().getStoptimeSec() > 0) {
            if (time >= test.getInput().getStoptimeSec()) {
                Log.d(TAG, "Stoptime reached: " + time + " - " + test.getInput().getStoptimeSec());
                done = true;
            }
        }

        return done;
    }

    public void setRuntimeParameters(int frame) {
        // go through all runtime settings and see which are due
        if (mRuntimeParams == null) return;
        Bundle bundle = new Bundle();
        for (Runtime.VideoBitrateParameter bitrate : mRuntimeParams.getVideoBitrateList()) {
            if (bitrate.getFramenum() == frame) {
                bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,
                        TestDefinitionHelper.magnitudeToInt(bitrate.getBitrate()));
                break;
            }
        }

        for (Long sync : mRuntimeParams.getRequestSyncList()) {
            if (sync == frame) {
                bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, frame);
                break;
            }
        }

        for (Runtime.Parameter param : mRuntimeParams.getParameterList()) {
            if (param.getFramenum() == frame) {
                Log.d(TAG, "Set runtime parameter @ " + frame + " key: " + param.getKey() + ", " + param.getType() + ", " + param.getValue());
                switch (param.getType().getNumber()) {
                    case DataValueType.floatType_VALUE:
                        float fval = Float.parseFloat(param.getValue());
                        bundle.putFloat(param.getKey(), fval);
                        break;
                    case DataValueType.intType_VALUE:
                        int ival = TestDefinitionHelper.magnitudeToInt(param.getValue());
                        bundle.putInt(param.getKey(), ival);
                        break;
                    case DataValueType.longType_VALUE:
                        long lval = Long.parseLong(param.getValue());
                        bundle.putLong(param.getKey(), lval);
                        break;
                    case DataValueType.stringType_VALUE:
                        bundle.putString(param.getKey(), param.getValue());
                        break;
                    default:
                        ///Should not be here
                }
            }
        }

        if (bundle.keySet().size() > 0 && mCodec != null) {
            mCodec.setParameters(bundle);
        }
    }

    boolean dropFrame(long frame) {
        if (mRuntimeParams == null) return false;
        for (Long drop : mRuntimeParams.getDropList()) {
            if (drop.longValue() == frame) {
                return true;
            }
        }

        return false;
    }

    void updateDynamicFramerate(long frame) {
        if (mRuntimeParams == null) return;
        for (Runtime.DynamicFramerateParameter rate : mRuntimeParams.getDynamicFramerateList()) {
            if (rate.getFramenum() == frame) {
                mKeepInterval = mFrameRate / rate.getFramerate();
                mFrameTimeUsec = calculateFrameTimingUsec(rate.getFramerate());
                return;
            }
        }
    }


    public boolean dropFromDynamicFramerate(int frame) {
        int currentFrameNbr = (int) ((float) (frame) / mKeepInterval);
        int nextFrameNbr = (int) ((float) ((frame + 1)) / mKeepInterval);
        return (currentFrameNbr == nextFrameNbr);
    }


    /**
     * Fills input buffer for encoder from YUV buffers.
     *
     * @return size of enqueued data.
     */
    protected int queueInputBufferEncoder(
            MediaCodec codec, ByteBuffer buffer, int index, int frameCount, int flags, int size) {
        buffer.clear();
        int read = mYuvReader.fillBuffer(buffer, size);
        long ptsUsec = computePresentationTimeUsec(frameCount, mRefFrameTime);
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


    public abstract void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder);

    public abstract void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info);

    protected class DataWriter extends Thread {
        ConcurrentLinkedQueue<FrameBuffer> mEncodeBuffers = new ConcurrentLinkedQueue<>();
        boolean mDone = false;

        public void stopWriter() {
            mDone = true;
        }


        @Override
        public void run() {
            while (!mDone) {
                while (mEncodeBuffers.size() > 0) {
                    FrameBuffer buffer = mEncodeBuffers.poll();
                    if (buffer == null) {
                        Log.e(TAG, "Buffer empty");
                        continue;
                    }

                    if ((buffer.mInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        MediaFormat oformat = mCodec.getOutputFormat();
                        mStats.setEncoderMediaFormat(mCodec.getInputFormat());
                        Log.d(TAG, "Start muxer: " + mMuxer +", write? " + mWriteFile);
                        if (mWriteFile && mMuxer != null) {
                            mVideoTrack = mMuxer.addTrack(oformat);
                            Log.d(TAG, "Start muxer, track = " + mVideoTrack);
                            mMuxer.start();
                        }
                        mCodec.releaseOutputBuffer(buffer.mBufferId, false /* render */);
                    } else {
                        if ((buffer.mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "End of stream: ");
                            mDone = true;
                        }
                        if (mFirstFrameTimestampUsec != -1) {
                            long timestampUsec = mPts + (long) (buffer.mInfo.presentationTimeUs - mFirstFrameTimestampUsec);
                            if (timestampUsec < 0) {
                                mCodec.releaseOutputBuffer(buffer.mBufferId, false /* render */);
                                continue;
                            }
                            try {
                                mStats.stopEncodingFrame(timestampUsec, buffer.mInfo.size,
                                        (buffer.mInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                                ++mOutFramesCount;
                                if (mMuxer != null && mVideoTrack != -1) {
                                    ByteBuffer data = mCodec.getOutputBuffer(buffer.mBufferId);
                                    mMuxer.writeSampleData(mVideoTrack, data, buffer.mInfo);
                                }

                                mCodec.releaseOutputBuffer(buffer.mBufferId, false /* render */);
                            } catch (Exception ise) {
                                // Codec may be closed elsewhere...
                                Log.e(TAG, "Writing failed: " + ise.getMessage());
                            }
                            mCurrentTimeSec = timestampUsec / 1000000.0;
                        } else {
                            mCodec.releaseOutputBuffer(buffer.mBufferId, false /* render */);
                        }
                    }
                }

                synchronized (mEncodeBuffers) {
                    if (mEncodeBuffers.size() == 0 && !mDone) {
                        try {
                            mEncodeBuffers.wait(WAIT_TIME_SHORT_MS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        public void addBuffer(MediaCodec codec, int id, MediaCodec.BufferInfo info) {
            mEncodeBuffers.add(new FrameBuffer(codec, id, info));
            synchronized (mEncodeBuffers) {
                mEncodeBuffers.notifyAll();
            }
        }
    }

    public class EncoderCallbackHandler extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            writeToBuffer(codec, index, true);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            mDataWriter.addBuffer(codec, index, info);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.d(TAG, "onError: " + e.getDiagnosticInfo());
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            MediaFormat oformat = codec.getOutputFormat();
            Log.d(TAG, "Encoder output format changed to:");
            Encoder.checkMediaFormat(oformat);
        }
    }

    public class DecoderCallbackHandler extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            //  Log.d(TAG, "DecoderCallbackHandler onInputBufferAvailable");
            writeToBuffer(codec, index, false);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            readFromBuffer(codec, index, false, info);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.d(TAG, "onError: " + e.getDiagnosticInfo());
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            MediaFormat oformat = codec.getOutputFormat();
            Log.d(TAG, "Decoder output format changed to: ");
            Encoder.checkMediaFormat(oformat);
        }
    }
}
