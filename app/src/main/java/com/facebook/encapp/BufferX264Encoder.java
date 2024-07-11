package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

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

/**
 * Created by jobl on 2018-02-27.
 */

class BufferX264Encoder extends Encoder {
    protected static final String TAG = "encapp.buffer_x264_encoder";

    static{
        try {
            System.loadLibrary("x264");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load x264 library: " + e.getMessage());
        }
    }

    public static native int x264Init(X264ConfigParams x264ConfigParamsInstance, int width, int height,
                                      int colourSpace, int bitdepth, byte[] headerArray);
    public static native int x264Encode(byte[] yBuffer, byte[] uBuffer, byte[] vBuffer,
                                        byte[] outputBuffer, int width, int height, int colourSpace);
    public static native void x264Close();

    public BufferX264Encoder(Test test) {
        super(test);
        mStats = new Statistics("raw encoder", mTest);
    }

    public static class X264ConfigParams {
        String preset;
        String tune;
        boolean fastfirstpass;
        String wpredp;
        float crf;
        float crf_max;
        int cqp;
        int aq_mode;
        int variance;
        int autovariance;
        int autovariance_biased;
        float aq_strength;
        boolean psy;
        float psy_rd;
        int rc_lookahead;
        boolean weightb;
        int weightp;
        boolean ssim;
        boolean intra_refresh;
        boolean bluray_compat;
        int b_bias;
        int b_pyramid;
        boolean mixed_refs;
        boolean dct_8x8;
        boolean fast_pskip;
        boolean aud;
        boolean mbtree;
        String deblock;
        float cplxblur;
        String partitions;
        int direct_pred;
        int slice_max_size;
        String stats;
        int nal_hrd;
        int avcintra_class;
        int me_method;
        int motion_est;
        boolean forced_idr;
        int coder;
        int b_strategy;
        int chromaoffset;
        int sc_threshold;
        int noise_reduction;

        public X264ConfigParams(String preset, String tune, boolean fastfirstpass, String wpredp, float crf, float crf_max, int qp, int aq_mode, int variance, int autovariance, int autovariance_biased, float aq_strength, boolean psy, float psy_rd, int rc_lookahead, boolean weightb, int weightp, boolean ssim, boolean intra_refresh, boolean bluray_compat, int b_bias, int b_pyramid, boolean mixed_refs, boolean dct_8x8, boolean fast_pskip, boolean aud, boolean mbtree, String deblock, float cplxblur, String partitions, int direct_pred, int slice_max_size, String stats, int nal_hrd, int avcintra_class, int me_method, int motion_est, boolean forced_idr, int coder, int b_strategy, int chromaoffset, int sc_threshold, int noise_reduction) {
            this.preset = preset;
            this.tune = tune;
            this.fastfirstpass = fastfirstpass;
            this.wpredp = wpredp;
            this.crf = crf;
            this.crf_max = crf_max;
            this.cqp = cqp;
            this.aq_mode = aq_mode;
            this.variance = variance;
            this.autovariance = autovariance;
            this.autovariance_biased = autovariance_biased;
            this.aq_strength = aq_strength;
            this.psy = psy;
            this.psy_rd = psy_rd;
            this.rc_lookahead = rc_lookahead;
            this.weightb = weightb;
            this.weightp = weightp;
            this.ssim = ssim;
            this.intra_refresh = intra_refresh;
            this.bluray_compat = bluray_compat;
            this.b_bias = b_bias;
            this.b_pyramid = b_pyramid;
            this.mixed_refs = mixed_refs;
            this.dct_8x8 = dct_8x8;
            this.fast_pskip = fast_pskip;
            this.aud = aud;
            this.mbtree = mbtree;
            this.deblock = deblock;
            this.cplxblur = cplxblur;
            this.partitions = partitions;
            this.direct_pred = direct_pred;
            this.slice_max_size = slice_max_size;
            this.stats = stats;
            this.nal_hrd = nal_hrd;
            this.avcintra_class = avcintra_class;
            this.me_method = me_method;
            this.motion_est = motion_est;
            this.forced_idr = forced_idr;
            this.coder = coder;
            this.b_strategy = b_strategy;
            this.chromaoffset = chromaoffset;
            this.sc_threshold = sc_threshold;
            this.noise_reduction = noise_reduction;
        }
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

    public static byte[][] extractYUVPlanes(byte[] yuvData, int width, int height) {
        int frameSize = width * height;
        int qFrameSize = frameSize / 4;

        byte[] yPlane = new byte[frameSize];
        byte[] uPlane = new byte[qFrameSize];
        byte[] vPlane = new byte[qFrameSize];

        System.arraycopy(yuvData, 0, yPlane, 0, frameSize);
        System.arraycopy(yuvData, frameSize, uPlane, 0, qFrameSize);
        System.arraycopy(yuvData, frameSize + qFrameSize, vPlane, 0, qFrameSize);

        return new byte[][]{yPlane, uPlane, vPlane};
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
        int colourSpace = 2;//mTest.getEncoderX264().getColorSpace();
        int bitdepth = 8;//mTest.getEncoderX264().getBitdepth();

        // The below x264 options will be configurable using pbtxt
        String tune = "ssim";
        boolean fastfirstpass = false;
        String wpredp = "auto";
        float crf = 23.0f;
        float crf_max = 0.0f;
        int qp = 0;
        int aq_mode = 0;
        int variance = 0;
        int autovariance = 0;
        int autovariance_biased = 0;
        float aq_strength = 1.0f;
        boolean psy = true;
        float psy_rd = 1.0f;
        int rc_lookahead = 40;
        boolean weightb = true;
        int weightp = 2;
        boolean ssim = false;
        boolean intra_refresh = false;
        boolean bluray_compat = false;
        int b_bias = 0;
        int b_pyramid = 1;
        boolean mixed_refs = true;
        boolean dct_8x8 = true;
        boolean fast_pskip = true;
        boolean aud = false;
        boolean mbtree = true;
        String deblock = "0:0";
        float cplxblur = 20.0f;
        String partitions = "all";
        int direct_pred = 1;
        int slice_max_size = 0;
        String stats = "";
        int nal_hrd = 0;
        int avcintra_class = 0;
        int me_method = 1;
        int motion_est = 1;
        boolean forced_idr = false;
        int coder = 1;
        int b_strategy = 1;
        int chromaoffset = 0;
        int sc_threshold = 40;
        int noise_reduction = 0;

        X264ConfigParams x264ConfigParamsInstance = new X264ConfigParams(
                preset, tune, fastfirstpass, wpredp, crf, crf_max, qp, aq_mode, variance, autovariance, autovariance_biased, aq_strength, psy, psy_rd, rc_lookahead,
                weightb, weightp, ssim, intra_refresh, bluray_compat, b_bias, b_pyramid, mixed_refs, dct_8x8, fast_pskip, aud, mbtree, deblock, cplxblur, partitions,
                direct_pred, slice_max_size, stats, nal_hrd, avcintra_class, me_method, motion_est, forced_idr, coder, b_strategy, chromaoffset, sc_threshold, noise_reduction
        );

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

        String outputStreamName = "x264_output.h264";
        String headerDump = "x264_header_dump.h264";
        File file = new File(Environment.getExternalStorageDirectory(), outputStreamName);
        File file2 = new File(Environment.getExternalStorageDirectory(), headerDump);

        // Ensure the parent directory exists
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            FileOutputStream fileOutputStream2 = new FileOutputStream(file2);

            int currentFramePosition = 0;
            boolean input_done = false;
            boolean output_done = false;
            MediaMuxer muxer = null;
            MediaCodec.BufferInfo bufferInfo = null;

            int videoTrackIndex = -1;
            boolean muxerStarted = false;
            int frameSize = width * height * 3 / 2;
            byte[] outputBuffer = new byte[frameSize];
            int estimatedSize = 2048; // Adjust this size as needed
            byte[] headerArray = new byte[estimatedSize];
            int outputBufferSize;

            int sizeOfHeader = x264Init(x264ConfigParamsInstance, width, height, colourSpace, bitdepth, headerArray);
            boolean flagHeaderSize = true;

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

                        byte[][] planes = extractYUVPlanes(yuvData, width, height);

                        outputBufferSize = x264Encode(planes[0], planes[1], planes[2], outputBuffer, width, height, colourSpace);
                        if (outputBufferSize == 0) {
                            return "Failed to encode frame";
                        }
                        mFramesAdded++;
                        currentFramePosition += frameSize;
                        Log.d(TAG, "Successfully written to " + outputStreamName);
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
                        muxer = new MediaMuxer(Environment.getExternalStorageDirectory().getPath() + "/x264_output.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        videoTrackIndex = muxer.addTrack(format);
                        muxer.start();
                        muxerStarted = true;
                    }

                    ByteBuffer buffer = ByteBuffer.wrap(outputBuffer);
                    bufferInfo.offset = 0;
                    bufferInfo.size = outputBufferSize;
                    bufferInfo.presentationTimeUs = computePresentationTimeUsec(mFramesAdded, mRefFrameTime);

//                    boolean isKeyFrame = checkIfKeyFrame(outputBuffer);
                    bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;

                    if(muxer != null) {
                        buffer.position(bufferInfo.offset);
                        buffer.limit(bufferInfo.offset + bufferInfo.size);

                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo);
                        if(flagHeaderSize)
                            fileOutputStream2.write(headerArray, 0, sizeOfHeader);
                        fileOutputStream.write(buffer.array(), 0, outputBufferSize);
                    }
                    flagHeaderSize = false;
                } catch (MediaCodec.CodecException ex) {
                    Log.e(TAG, "dequeueOutputBuffer: MediaCodec.CodecException error");
                    ex.printStackTrace();
                    return "dequeueOutputBuffer: MediaCodec.CodecException error";
                }
            }
            mStats.stop();

            Log.d(TAG, "Close encoder and streams");
            x264Close();
            fileOutputStream.close();

            if (muxer != null) {
                try {
                    muxer.release();
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
