package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;

import com.facebook.encapp.utils.ConfigureParam;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.RuntimeParam;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Vector;

import static junit.framework.Assert.assertTrue;

/**
 * Created by jobl on 2018-02-27.
 */

class BufferEncoder {
    // Qualcomm added extended omx parameters


    protected static final String TAG = "encapp";
    protected static final long VIDEO_CODEC_WAIT_TIME_US = 1000;

    protected int mFrameRate = 30;
    protected float mKeepInterval = 1.0f;
    protected MediaCodec mCodec;
    protected MediaMuxer mMuxer;

    protected int mSkipped = 0;
    protected int mFramesAdded = 0;
    protected int mRefFramesizeInBytes = (int) (1280 * 720 * 1.5);

    protected long mFrameTime = 0;
    protected boolean mWriteFile = true;
    protected Statistics mStats;
    protected String mFilename;
    protected boolean mDropNext;
    protected HashMap<Integer, ArrayList<RuntimeParam>> mRuntimeParams;
    int mLTRCount = 0;
    boolean VP8_IS_BROKEN = false; // On some older hw vp did not generate key frames by itself
    private long mFilePntr;
    private FileReader mYuvReader;

    public String encode(
            TestParams vc,
            boolean writeFile) {
        mRuntimeParams = vc.getRuntimeParameters();
        mSkipped = 0;
        mFramesAdded = 0;
        mRefFramesizeInBytes = (int) (vc.getReferenceSize().getWidth() *
                vc.getReferenceSize().getHeight() * 1.5);
        mWriteFile = writeFile;
        mStats = new Statistics("raw encoder", vc);
        mStats.start();
        mYuvReader = new FileReader();

        vc.addConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
        int loop = vc.getLoopCount();

        if (!mYuvReader.openFile(vc.getInputfile())) {
            return "\nCould not open file";
        }

        int keyFrameInterval = vc.getKeyframeRate();
        MediaFormat format;
        try {
            String codecName = getCodecName(vc);
            mStats.setCodec(codecName);
            Log.d(TAG, "Create codec by name: " + codecName);
            mCodec = MediaCodec.createByCodecName(codecName);

            Log.d(TAG, "Done");
            format = vc.createEncoderMediaFormat(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());

            setConfigureParams(vc, format);
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);

        } catch (IOException iox) {
            Log.e(TAG, "Failed to create codec: " + iox.getMessage());
            return "Failed to create codec";
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: " + cex.getMessage());
            return "Failed to create codec";
        }

        try {
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
        int mPts = 132;
        calculateFrameTiming();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        boolean isQCom = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".qcom");
        if (isVP) {
            //There seems to be a bug so that this key is no set (but used).
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, format.getInteger(MediaFormat.KEY_BITRATE_MODE));
            if (mWriteFile)
                mMuxer = createMuxer(mCodec, format, true);
        }

        long numBytesSubmitted = 0;
        long numBytesDequeued = 0;
        int current_loop = 1;
        while (loop + 1 >= current_loop) {
            int index;
            Log.d(TAG, "Frames: " + mFramesAdded + " - inframes: " + inFramesCount + ", current loop: " + current_loop);
            try {
                index = mCodec.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);

                if (index >= 0) {
                    int size = -1;
                    if (VP8_IS_BROKEN && isVP && isQCom && inFramesCount > 0 &&
                            keyFrameInterval > 0 && inFramesCount % (mFrameRate * keyFrameInterval) == 0) {
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        mCodec.setParameters(params);
                    }

                    ByteBuffer buffer = mCodec.getInputBuffer(index);
                    while (size < 0) {
                        try {
                            size = queueInputBufferEncoder(
                                    mCodec,
                                    buffer,
                                    index,
                                    inFramesCount,
                                    0,
                                    mRefFramesizeInBytes);

                            inFramesCount++;
                        } catch (IllegalStateException isx) {
                            Log.e(TAG, "Queue encoder failed, " + index + ", mess: " + isx.getMessage());
                        }
                        Log.d(TAG, "size = " + size);
                        if (size == -2) {
                            Log.d(TAG, "continue");
                            continue;
                        } else if (size <= 0) {
                            mYuvReader.closeFile();
                            mFilePntr = 0;

                            current_loop++;
                            if (current_loop > loop) {
                                try {
                                    size = queueInputBufferEncoder(
                                            mCodec,
                                            buffer,
                                            index,
                                            inFramesCount,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                            0);
                                    Log.d(TAG, "End of stream");
                                    inFramesCount++;
                                } catch (IllegalStateException isx) {
                                    Log.e(TAG, "Queue encoder failed, " + index + ", mess: " + isx.getMessage());
                                }
                                break;
                            }
                            Log.d(TAG, " *********** OPEN FILE SECOND TIME *******");
                            mYuvReader.openFile(vc.getInputfile());
                            Log.d(TAG, "*** Loop ended start " + current_loop + "***");
                        }
                    }
                    numBytesSubmitted += size;
                    // if (size == 0) break;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            index = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Just ignore
            } else if (index >= 0) {
                long nowUs = (System.nanoTime() + 500) / 1000;
                ByteBuffer data = mCodec.getOutputBuffer(index);
                Log.d(TAG, "flags = " + (info.flags));
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    MediaFormat oformat = mCodec.getOutputFormat();

                    //There seems to be a bug so that this key is no set (but used).
                    oformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
                    oformat.setInteger(MediaFormat.KEY_FRAME_RATE, format.getInteger(MediaFormat.KEY_FRAME_RATE));
                    oformat.setInteger(MediaFormat.KEY_BITRATE_MODE, format.getInteger(MediaFormat.KEY_BITRATE_MODE));
                    oformat.setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE));

                    if (mWriteFile) {
                        Log.d(TAG, "Create muxer");
                        mMuxer = createMuxer(mCodec, oformat, true);
                    }
                    mCodec.releaseOutputBuffer(index, false /* render */);
                } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                } else {
                    boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    mStats.stopFrame(info.presentationTimeUs, info.size, keyFrame);
                    if (keyFrame) {
                        Log.d(TAG, "Out buffer has KEY_FRAME @ " + outFramesCount);
                    }
                    numBytesDequeued += info.size;
                    ++outFramesCount;

                    if (mMuxer != null) {
                        Log.d(TAG, "Write to muxer: " + data.remaining());
                        mMuxer.writeSampleData(0, data, info);
                    }
                    mCodec.releaseOutputBuffer(index, false /* render */);
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

        if (mFilePntr != 0) {
            mYuvReader.closeFile();
        }

        return "";
    }

    /**
     * Fills input buffer for encoder from YUV buffers.
     *
     * @return size of enqueued data.
     */
    private int queueInputBufferEncoder(
            MediaCodec codec, ByteBuffer buffer, int index, int frameCount, int flags, int size) {
        setRuntimeParameters(frameCount);
        buffer.clear();
        int read = mYuvReader.fillBuffer(buffer, size);
        int currentFrameNbr = (int) ((float) (frameCount) / mKeepInterval);
        int nextFrameNbr = (int) ((float) ((frameCount + 1)) / mKeepInterval);
        if (currentFrameNbr == nextFrameNbr || mDropNext) {
            Log.d(TAG, "Skip frame: " + frameCount);
            mSkipped++;
            mDropNext = false;
            read = -2;
        } else if (read == size) {
            mFramesAdded++;
            long ptsUsec = computePresentationTime(frameCount);
            mStats.startFrame(ptsUsec);
            Log.d(TAG, "Queue frame " + frameCount);
            codec.queueInputBuffer(index, 0 /* offset */, read, ptsUsec /* timeUs */, flags);
        } else {
            read = -1;
        }

        return read;
    }


    /**
     * Generates the presentation time for frameIndex, in microseconds.
     */
    protected long computePresentationTime(int frameIndex) {
        return 132 + frameIndex * 1000000L / mFrameRate;
    }

    protected void calculateFrameTiming() {
        mFrameTime = 1000000L / mFrameRate;
    }


    protected MediaMuxer createMuxer(MediaCodec encoder, MediaFormat format, boolean useStatId) {
        if (!useStatId) {
            Log.d(TAG, "Bitrate mode: " + (format.containsKey(MediaFormat.KEY_BITRATE_MODE) ? format.getInteger(MediaFormat.KEY_BITRATE_MODE) : 0));
            mFilename = String.format(Locale.US, "/sdcard/%s_%dfps_%dx%d_%dbps_iint%d_m%d.mp4",
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
                mFilename = String.format(Locale.US, "/sdcard/%s_%dfps_%dx%d_%dbps_iint%d_m%d.webm",
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
            Log.d(TAG, "Create mMuxer with type " + type + " and filename: " + mFilename);
            mMuxer = new MediaMuxer("/sdcard/" + mFilename, type);
        } catch (IOException e) {
            Log.d(TAG, "FAILED Create mMuxer with type " + type + " and filename: " + mFilename);
            e.printStackTrace();
        }

        mMuxer.addTrack(format);
        String formatTxt = TestParams.getFormatInfo(format);
        Log.d(TAG, "**\nSettings: " + formatTxt + "\n**");
        Log.d(TAG, "Start mMuxer");
        mMuxer.start();
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

    protected String getCodecName(TestParams vc) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        String id = vc.getVideoEncoderIdentifier();
        String codecName = "";
        Vector<MediaCodecInfo> matching = getMediaCodecInfos(codecInfos, id);

        if (matching.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("\nAmbigous codecs \n" + matching.size() + " codecs matching.\n");
            for (MediaCodecInfo info : matching) {
                sb.append(info.getName() + "\n");
            }
            assertTrue(sb.toString(), false);
        } else if (matching.size() == 0) {
            assertTrue("\nNo matching codecs to : " + id, false);
        } else {
            vc.setVideoEncoderIdentifier(matching.elementAt(0).getSupportedTypes()[0]);
            codecName = matching.elementAt(0).getName();
        }
        return codecName;
    }

    protected void setConfigureParams(TestParams vc, MediaFormat format) {
        ArrayList<ConfigureParam> params = vc.getExtraConfigure();
        Log.d(TAG, "setConfigureParams: " + params.toString() + ", l = " + params.size());
        for (ConfigureParam param : params) {
            if (param.value instanceof Integer) {
                format.setInteger(param.name, (Integer) param.value);
            } else if (param.value instanceof String) {
                Log.d(TAG, "Set  " + param.name + " to " + param.value);
                format.setString(param.name, (String) param.value);
            }
        }
    }

    protected void setRuntimeParameters(int frameCount) {
        if (mRuntimeParams != null && !mRuntimeParams.isEmpty()) {
            Bundle params = new Bundle();
            ArrayList<RuntimeParam> runtimeParams = mRuntimeParams.get(Integer.valueOf(frameCount));
            if (runtimeParams != null) {
                for (RuntimeParam param : runtimeParams) {
                    if (param.value == null) {
                        params.putInt(param.name, frameCount);
                    } else if (param.value instanceof Integer) {
                        if (param.name.equals("fps")) {
                            Log.d(TAG, "Set fps to " + param.value);
                            mKeepInterval = (float) mFrameRate / (float) (Integer) param.value;
                        }
                        if (param.name.equals("fps")) {
                            mDropNext = true;
                        } else {
                            Log.d(TAG, param.name + " @ " + frameCount + " - " + param.value);
                            params.putInt(param.name, (Integer) param.value);
                        }
                    } else {
                        Log.d(TAG, "Unknown type: " + param.value.getClass());
                    }
                }
                Log.d(TAG, "Set runtime parameters in codec");
                mCodec.setParameters(params);
            }
        }
    }

}
