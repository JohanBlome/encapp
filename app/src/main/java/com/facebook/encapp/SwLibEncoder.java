package com.facebook.encapp;

import static com.facebook.encapp.utils.TestDefinitionHelper.magnitudeToInt;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Vector;


/**
 * Created by jobl on 2018-02-27.
 */

class SwLibEncoder extends Encoder {
    protected static final String TAG = "encapp.buffer_x264_encoder";

    static{
        try {
            Log.d(TAG, "Loading nativeencoder");
            System.loadLibrary("nativeencoder");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library: " + e.getMessage());
        }
    }

    public static native int initEncoder(Parameter[] parameters, int width, int height, int colorSpace, int bitDepth);
    public static native byte[] getHeader();
    // TODO: can the size, color and bitdepth change runtime?
    public static native int encode(byte[] input, byte[] output, int width, int height, int colorSpace, int bitDepth);
    public static native void close();


    public SwLibEncoder(Test test) {
        super(test);
        mStats = new Statistics("raw encoder", mTest);
    }

    private long computePresentationTimeUs(int frameIndex) {
        return frameIndex * 1000000 / 30;
    }

    public static byte[] readYUVFromFile(String filePath, int size, int framePosition) throws IOException {
        byte[] inputBuffer = new byte[size];
        try (FileInputStream fis = new FileInputStream(filePath);
             FileChannel channel = fis.getChannel()) {
            channel.position(framePosition);
            ByteBuffer buffer = ByteBuffer.wrap(inputBuffer);
            int bytesRead = channel.read(buffer);
            if (bytesRead < size) {
                return null;
            }
        }
        return inputBuffer;
    }


    public static byte[][] extractSpsPps(byte[] headerArray) {
        int spsStart = -1;
        int spsEnd = -1;
        int ppsStart = -1;
        int ppsEnd = -1;

        for (int i = 0; i < headerArray.length - 4; i++) {
            // Check for the start code 0x00000001 or 0x000001
            if ((headerArray[i] == 0x00 && headerArray[i+1] == 0x00 && headerArray[i+2] == 0x00 && headerArray[i+3] == 0x01) ||
                    (headerArray[i] == 0x00 && headerArray[i+1] == 0x00 && headerArray[i+2] == 0x01)) {

                int nalType = headerArray[i + (headerArray[i + 2] == 0x01 ? 3 : 4)] & 0x1F;
                if (nalType == 7 && spsStart == -1) { // SPS NAL unit type is 7
                    spsStart = i;
                } else if (nalType == 8 && spsStart != -1 && spsEnd == -1) { // PPS NAL unit type is 8
                    spsEnd = i;
                    ppsStart = i;
                }
                else if(spsEnd != -1 && ppsStart != -1) {
                    if(headerArray[i] == 0x00 && headerArray[i+1] == 0x00 && headerArray[i+2] == 0x01 && headerArray[i-1] != 0x00) {
                        ppsEnd = i;
                        break;
                    }
                }
            }
        }

        byte[] spsBuffer = Arrays.copyOfRange(headerArray, spsStart, spsEnd);
        byte[] ppsBuffer = Arrays.copyOfRange(headerArray, ppsStart, ppsEnd);

        return new byte[][]{spsBuffer, ppsBuffer};
    }

    public boolean checkIfKeyFrame(byte[] bitstream) {
        int nalUnitType;
        for (int i = 0; i < bitstream.length - 4; i++) {
            // Check for the start code 0x00000001 or 0x000001
            if ((bitstream[i] == 0x00 && bitstream[i+1] == 0x00 && bitstream[i+2] == 0x00 && bitstream[i+3] == 0x01) ||
                    (bitstream[i] == 0x00 && bitstream[i+1] == 0x00 && bitstream[i+2] == 0x01)) {

                nalUnitType = bitstream[i + (bitstream[i + 2] == 0x01 ? 3 : 4)] & 0x1F;

                // Check if the NAL unit type is 5 (IDR frame)
                if (nalUnitType == 5) {
                    return true;
                }
            }
        }
        return false;
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

        boolean useImage = false;

        mFrameRate = mTest.getConfigure().getFramerate();
        mWriteFile = !mTest.getConfigure().hasEncode() || mTest.getConfigure().getEncode();
        mSkipped = 0;
        mFramesAdded = 0;
        Size sourceResolution = SizeUtils.parseXString(mTest.getInput().getResolution());
        mRefFramesizeInBytes = (int) (sourceResolution.getWidth() * sourceResolution.getHeight() * 1.5);
        mYuvReader = new FileReader();
        int playoutframes = mTest.getInput().getPlayoutFrames();

        String preset = "fast";//mTest.getEncoderX264().getPreset();
        int width = sourceResolution.getWidth();
        int height = sourceResolution.getHeight();
        int colorSpace = 2;//mTest.getEncoderX264().getColorSpace();
        int bitdepth = 8;//mTest.getEncoderX264().getBitdepth();
        int bitrate = magnitudeToInt(mTest.getConfigure().getBitrate());
        int bitrateMode = mTest.getConfigure().getBitrateMode().getNumber();

        float crf = 23.0f;


        // Create params for this
        // TODO: Translate from mediaformat into this (for common settings).
        Vector<Parameter> params = new Vector<>();
        params.add(Parameter.newBuilder().setKey("preset").setValue("fast").setType(DataValueType.stringType).build());
        params.add(Parameter.newBuilder().setKey("tune").setValue("psnr").setType(DataValueType.stringType).build());
        params.add(Parameter.newBuilder().setKey("i_threads").setValue("1").setType(DataValueType.stringType).build());
        params.add(Parameter.newBuilder().setKey("i_width").setValue(String.valueOf(width)).setType(DataValueType.stringType).build());
        params.add(Parameter.newBuilder().setKey("i_height").setValue(String.valueOf(height)).setType(DataValueType.stringType).build());
        params.add(Parameter.newBuilder().setKey("i_csp").setValue(String.valueOf(colorSpace)).setType(DataValueType.stringType).build());
        params.add(Parameter.newBuilder().setKey("i_bitdepth").setValue(String.valueOf(bitdepth)).setType(DataValueType.stringType).build());

        params.add(Parameter.newBuilder().setKey("bitrate").setValue(String.valueOf(bitrate)).setType(DataValueType.stringType).build());

        if (!mYuvReader.openFile(checkFilePath(mTest.getInput().getFilepath()), mTest.getInput().getPixFmt())) {
            return "Could not open file";
        }

        MediaFormat mediaFormat;
        mediaFormat = TestDefinitionHelper.buildMediaFormat(mTest);
        logMediaFormat(mediaFormat);
        setConfigureParams(mTest, mediaFormat);

        float mReferenceFrameRate = mTest.getInput().getFramerate();
        mKeepInterval = mReferenceFrameRate / mFrameRate;
        mRefFrameTime = calculateFrameTimingUsec(mReferenceFrameRate);

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

        mMuxer = createMuxer(null, mediaFormat);
        try {
            int currentFramePosition = 0;
            boolean input_done = false;
            boolean output_done = false;
            MediaCodec.BufferInfo bufferInfo = null;

            int videoTrackIndex = -1;
            boolean muxerStarted = false;
            int frameSize = width * height * 3 / 2;
            byte[] outputBuffer = new byte[frameSize];
            int estimatedSize = 2048; // Adjust this size as needed
            byte[] headerArray = new byte[estimatedSize];
            int outputBufferSize;


            Parameter[] param_buffer = new Parameter[params.size()];
            params.toArray(param_buffer);
            //TODO: We are setting width, height etc twice.
            int status = initEncoder(param_buffer, width, height, colorSpace, bitdepth);
            if (status != 0) {
                Log.e(TAG, "Init failed");
                return "";
            }
            headerArray = getHeader();
            while (!input_done || !output_done) {
                try {
                    long timeoutUs = VIDEO_CODEC_WAIT_TIME_US;
                    int flags = 0;
                    String filePath = mTest.getInput().getFilepath();

                    if (mRealtime) {
                        sleepUntilNextFrame();
                    }

                    try {
                        byte[] yuvData = readYUVFromFile(filePath, frameSize, currentFramePosition);

                        if (yuvData == null) {
                            input_done = true;
                            output_done = true;
                            continue;
                        }

                        outputBufferSize = encode(yuvData, outputBuffer, width, height, colorSpace, bitdepth);
                        if (outputBufferSize == 0) {
                            return "Failed to encode frame";
                        }
                        mFramesAdded++;
                        currentFramePosition += frameSize;
                    } catch (IOException e) {
                        e.printStackTrace();
                        return e.getMessage();
                    }
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "QueueInputBuffer: IllegalStateException error");
                    ex.printStackTrace();
                    return ex.getMessage();
                }

                try {
                    if (!muxerStarted) {
                        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                        format.setInteger(MediaFormat.KEY_WIDTH, width);
                        format.setInteger(MediaFormat.KEY_HEIGHT, height);
                        format.setInteger(MediaFormat.KEY_BIT_RATE, 800000);
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, 50);
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                        byte[][] spsPps = extractSpsPps(headerArray);

                        ByteBuffer sps = ByteBuffer.wrap(spsPps[0]);
                        ByteBuffer pps = ByteBuffer.wrap(spsPps[1]);
                        format.setByteBuffer("csd-0", sps);
                        format.setByteBuffer("csd-1", pps);

                        bufferInfo = new MediaCodec.BufferInfo();
                        videoTrackIndex = mMuxer.addTrack(format);
                        mMuxer.start();
                        muxerStarted = true;
                    }

                    ByteBuffer buffer = ByteBuffer.wrap(outputBuffer);
                    bufferInfo.offset = 0;
                    bufferInfo.size = outputBufferSize;
                    bufferInfo.presentationTimeUs = computePresentationTimeUsec(mFramesAdded, mRefFrameTime);

                    boolean isKeyFrame = checkIfKeyFrame(outputBuffer);
                    if (isKeyFrame) bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;

                    if(mMuxer != null) {
                        buffer.position(bufferInfo.offset);
                        buffer.limit(bufferInfo.offset + bufferInfo.size);

                        mMuxer.writeSampleData(videoTrackIndex, buffer, bufferInfo);
                    }
                } catch (MediaCodec.CodecException ex) {
                    Log.e(TAG, "dequeueOutputBuffer: MediaCodec.CodecException error");
                    ex.printStackTrace();
                    return "dequeueOutputBuffer: MediaCodec.CodecException error";
                }
            }
            mStats.stop();

            Log.d(TAG, "Close encoder and streams");
            close();

            if (mMuxer != null) {
                try {
                    mMuxer.release();
                } catch (IllegalStateException ise) {
                    Log.e(TAG, "Illegal state exception when trying to release the muxer");
                }
            }
        } catch (Exception ex) {
            return ex.getMessage();
        }
        mYuvReader.closeFile();
        return "";
    }

    public void writeToBuffer(@NonNull MediaCodec codec, int index, boolean encoder) {
        // Not needed
    }

    public void readFromBuffer(@NonNull MediaCodec codec, int index, boolean encoder, MediaCodec.BufferInfo info) {
        // Not needed
    }

    public void stopAllActivity() {
        // Not needed
    }

    public void release() {
        // Not needed
    }
}
