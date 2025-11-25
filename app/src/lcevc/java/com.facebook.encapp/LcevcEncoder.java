package com.facebook.encapp;

import static com.facebook.encapp.utils.TestDefinitionHelper.magnitudeToInt;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.PixFmt;
import com.facebook.encapp.proto.Runtime;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.CliSettings;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.Iterator;

import java.io.Closeable;
import java.nio.file.StandardOpenOption;

import com.vnova.lcevc.jni.EIL;
import com.vnova.lcevc.eil.AndroidEIL;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;


public class LcevcEncoder extends Encoder {
    protected static final String TAG = "LCEVC Encoder";
    public LcevcEncoder(Test test) {
        super(test);
        mStats = new Statistics("lcevc_encoder", mTest);
    }

    public void setRuntimeParameters(int frameNum) {
        // go through all runtime settings and see which are due
        if (mRuntimeParams == null)
            return;
        Vector<Parameter> params = new Vector();

        for (Runtime.VideoBitrateParameter bitrate : mRuntimeParams.getVideoBitrateList()) {
            if (bitrate.getFramenum() == frameNum) {
                addEncoderParameters(params, DataValueType.intType.name(), BITRATE, String.valueOf(bitrate));
                break;
            }
        }

        for (Long sync : mRuntimeParams.getRequestSyncList()) {
            if (sync == frameNum) {
                addEncoderParameters(params, DataValueType.longType.name(), "request-sync", "");
                break;
            }
        }

        for (Parameter param : mRuntimeParams.getParameterList()) {
            if (param.getFramenum() == frameNum) {
                Log.d(TAG, "Set runtime parameter @ " + frameNum + " key: " + param.getKey() + ", " + param.getType()
                        + ", " + param.getValue());
            }
        }

        Parameter[] paramBuffer = new Parameter[params.size()];
        params.toArray(paramBuffer);
    }

    /**
     Get all lcevc-based encoder settings from the tests' proto files.
     These are the json keys with "parameter".
     */
    public void getBaseEncoderConfig(Vector<Parameter> encoderBaseConfig) {
        try {
            int bitrate = magnitudeToInt(mTest.getConfigure().getBitrate());
            int iFrameInterval = mTest.getConfigure().getIFrameInterval();
            float frameRate = mTest.getConfigure().getFramerate();

            addEncoderParameters(encoderBaseConfig, DataValueType.intType.name(), BITRATE, String.valueOf(bitrate));
            addEncoderParameters(encoderBaseConfig, DataValueType.intType.name(), I_FRAME_INTERVAL, String.valueOf(iFrameInterval));
            addEncoderParameters(encoderBaseConfig, DataValueType.floatType.name(), FRAMERATE, String.valueOf(frameRate));

        } catch (Exception ex) {
            Log.d(TAG, "Exception: " + ex);
        }
    }

    public String start() {
        Log.d(TAG, "** LCEVC encoding - " + mTest.getCommon().getDescription() + " **");
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
        mRefFramesizeInBytes = (int) (sourceResolution.getWidth() * sourceResolution.getHeight() * 1.5);
        mYuvReader = new FileReader();

        int width = sourceResolution.getWidth();
        int height = sourceResolution.getHeight();
        int pixelFormat = mTest.getInput().getPixFmt().getNumber();
        int bitdepth = (pixelFormat == PixFmt.p010le.getNumber()) ? 10 : 8;
        int bitrate = magnitudeToInt(mTest.getConfigure().getBitrate());
        int bitrateMode = mTest.getConfigure().getBitrateMode().getNumber();
        int iFrameInterval = mTest.getConfigure().getIFrameInterval();

        Log.d(TAG, width + "x" + height + ",  pixelFormat: " + pixelFormat + ", bitdepth:" + bitdepth
                + ", bitrateMode:" + bitrateMode + ", bitrate: " + bitrate + ", iFrameInterval: " + iFrameInterval);

        // Caching vital values and see if they can be set runtime.
        Vector<Parameter> params = new Vector(mTest.getConfigure().getParameterList());
        // This one needs to be set as a native param.
        try {
            addEncoderParameters(params, DataValueType.intType.name(), BITRATE, String.valueOf(bitrate));
        } catch (Exception ex) {
            Log.d(TAG, "Exception: " + ex);
        }
        if (!mYuvReader.openFile(checkFilePath(mTest.getInput().getFilepath()), mTest.getInput().getPixFmt())) {
            return "Could not open file";
        }

        MediaFormat mediaFormat;
        mediaFormat = TestDefinitionHelper.buildMediaFormat(mTest);
        logMediaFormat(mediaFormat);
        setConfigureParams(mTest, mediaFormat);

        mStats.setEncoderMediaFormat(mediaFormat);

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

        try {
            Vector<Parameter> encoderEILConfig = new Vector<>(mTest.getConfigure().getParameterList());

            Vector<Parameter> encoderBaseConfig = new Vector<>();
            getBaseEncoderConfig(encoderBaseConfig);

            mTest = mTest.toBuilder().setConfigure(mTest.getConfigure().toBuilder().addAllParameter(encoderBaseConfig))
                    .build();

            Log.d(TAG, "Updated test: " + mTest);
            mStats.updateTest(mTest);

            // Initialise and run the LCEVC Encoder.
            File inputFile;
            EncoderEIL eilEncoder;

            try {
                inputFile = new File(mTest.getInput().getFilepath());
            } catch (Exception ex) {
                return "Unable to find suitable input file";
            }

            try {
                eilEncoder = new EncoderEIL(
                        inputFile,
                        width,
                        height,
                        pixelFormat,
                        encoderBaseConfig,
                        encoderEILConfig,
                        mStats,
                        mPts,
                        mRefFrameTime);
            } catch (Exception ex) {
                return "Unable to to open file";
            }

            boolean result = eilEncoder.run();
            if (!result) {
                return "Unable to access encode input file";
            }

            mStats.stop();

            Log.d(TAG, "Close encoder and streams");

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

class EncoderEIL {
    private static final String TAG = "Lcevc Encoder EIL";
    public static final String CONFIG_KEY_BASE_ENCODER = "base_encoder";
    public static final String CONFIG_KEY_GOP_LENGTH = "gop_length";
    public static final String CODEC_MEDIACODEC_H264 = "mediacodec_h264";
    public static final String CODEC_X264 = "x264";
    private BaseMuxer mMuxer;
    private Input mInput;
    private AndroidEIL mEIL;
    String mMuxedFile = "";
    private boolean mIsEOS = false;
    private Statistics mStats;
    private long mReferencePts = 0;
    private double mRefFrameTime = 0;
    private int mReceivedOutputBuffer = 0;
    private EncoderProperty mEncoderProperty;

    /**
     Read the input file and extracts the frames to be encoded.
     */
    static class Input implements Closeable
    {
        private File mFile;
        private FileChannel mChannel;
        private int mFrameSize = 0;
        private ByteBuffer mFramebuffer;
        private int mFramesRead = 0;
        private int mNumFrames = 0;

        public Input(File file, int frameWidth, int frameHeight) {
            if (frameWidth == 0 || frameHeight == 0) {
                throw new IllegalArgumentException("Input must have non-zero frame width and height");
            }

            mFile = file;
            try {
                mChannel = FileChannel.open(mFile.toPath(), StandardOpenOption.READ);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot open file channel", ex);
            }

            // Frame size in bytes assuming YUV 4:2:0 format (1.5 bytes per pixel)
            mFrameSize = frameWidth * frameHeight * 3 / 2;
            mFramebuffer = ByteBuffer.allocateDirect(mFrameSize);

            mNumFrames = (int) (mFile.length() / mFrameSize);
        }

        public void close() throws IOException {
            mChannel.close();
        }

        public int getFramesRead() {
            return mFramesRead;
        }

        // Check if all frames have been read.
        public boolean isFinished() {
            return mFramesRead >= mNumFrames;
        }

        // Reads a frame's worth of data from a file into a buffer
        public ByteBuffer nextFrame() throws IOException {
            mFramebuffer.clear();

            int bytesRead = mChannel.read(mFramebuffer);
            if (bytesRead == mFrameSize) {
                mFramesRead++;
                mFramebuffer.flip();
                return mFramebuffer;
            }

            return null;
        }

    }

    /**
     Hold base and eil encoder parameters.
     */
    static class EncoderProperty {
        int mFrameWidth = 0;
        int mFrameHeight = 0;
        int mBitrate = 0;
        int mIframeinterval = 0;
        float mFramerate = 0;
        int mPixelFormat = 0;

        HashMap<String, Object> mEILParameters;

        EncoderProperty(int width, int height, int pixelFormat, Vector<Parameter> baseConfig, Vector<Parameter> eilConfig) {
            mFrameWidth = width;;
            mFrameHeight = height;
            mPixelFormat = pixelFormat;
            mEILParameters = new HashMap<>();

            // Extract the bitrate, frame rate and iframe interval from the base config.
            for (int i=0; i<baseConfig.size(); i++) {
                Parameter param = baseConfig.elementAt(i);
                if (param.getKey().equals(Encoder.BITRATE) ) {
                    mBitrate = Integer.parseInt(param.getValue());
                }
                else if (param.getKey().equals(Encoder.I_FRAME_INTERVAL)) {
                    mIframeinterval = Integer.parseInt(param.getValue());
                }
                else if (param.getKey().equals(Encoder.FRAMERATE)) {
                    mFramerate = Float.parseFloat(param.getValue());
                }
            }

            // Extract the eil parameters and store in a key-value map.
            for (int i=0; i<eilConfig.size(); i++) {
                Parameter param = eilConfig.elementAt(i);
                setKeyValueEilParameter(param);
            }

        }

        public int getFrameWidth() {
            return mFrameWidth;
        }

        public int getFrameHeight() {
            return mFrameHeight;
        }

        public int getBitrate() {
            return mBitrate;
        }

        public float getFramerate() {
            return mFramerate;
        }

        public int getIFrameInterval() {
            return mIframeinterval;
        }

        public int getPixelFormat() {
            if (mPixelFormat > 0)
                return mPixelFormat;
            
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        }

        public String getBaseEncoder() {
            if (mEILParameters != null &&
                    mEILParameters.containsKey(CONFIG_KEY_BASE_ENCODER))
            {
                return mEILParameters.get(CONFIG_KEY_BASE_ENCODER).toString();
            }
            return null;
        }

        // Map the EIL flags to their output. E.g., "qp_max" : 40
        public HashMap<String, Object> getEilParameters() {
            return mEILParameters;
        }

        public void setKeyValueEilParameter(Parameter parameter) {
            if (mEILParameters == null) return;

            switch (parameter.getType().getNumber()) {
                case DataValueType.stringType_VALUE:
                    mEILParameters.put(parameter.getKey(), parameter.getValue());
                    break;
                case DataValueType.intType_VALUE:
                    mEILParameters.put(parameter.getKey(), Integer.parseInt(parameter.getValue()));
                    break;
                case DataValueType.floatType_VALUE:
                    mEILParameters.put(parameter.getKey(), Float.parseFloat(parameter.getValue()));
                    break;
                case DataValueType.longType_VALUE:
                    mEILParameters.put(parameter.getKey(), Long.parseLong(parameter.getValue()));
                    break;
            }
        }

    }

    public EncoderProperty getEncoderProperty() {
        return mEncoderProperty;
    }

    public EncoderEIL(File file,
                      int frameWidth,
                      int frameHeight,
                      int pixelFormat,
                      Vector<Parameter> baseConfig,
                      Vector<Parameter> eilConfig,
                      Statistics statistics,
                      long referencePts,
                      double refFrameTime) {
        try {
            // Initialize the Android EIL and I/O properties.
            mEIL = new AndroidEIL();
            mEncoderProperty = new EncoderProperty(frameWidth, frameHeight, pixelFormat, baseConfig, eilConfig);

            mInput = new Input(file, frameWidth, frameHeight);

            // Initialize encapp encoder's reference and statistics parameters.
            mStats = statistics;
            mReferencePts = referencePts;
            mRefFrameTime = refFrameTime;

            String outputFilename = mStats.getId() + ".mp4";
            mMuxedFile = CliSettings.getWorkDir()  + "/" +  outputFilename; // final muxed mp4 file.

            mStats.setEncodedfile(outputFilename);

        } catch (RuntimeException ex) {
            Log.e(TAG, "Failed to load Input", ex);
        } catch (Exception ex) {
            Log.e(TAG, "Unable to create output file", ex);
        }
    }

    public long getReferencePts() {
        return mReferencePts;
    }

    public double getRefFrameTime() {
        return mRefFrameTime;
    }

    public AndroidEIL getLcevcEIL() {
        return mEIL;
    }

    /**
     * Encode input frames into an MP4 file.
     */
    public boolean run() throws Exception {
        // Initialize the encoder.
        initEncoder();

        // Initialize the muxer.
        String baseCodec = mEncoderProperty.getBaseEncoder();

        BaseMuxer.MuxerType muxerType = baseCodec.equals(CODEC_MEDIACODEC_H264) ? BaseMuxer.MuxerType.MEDIA_MUXER : BaseMuxer.MuxerType.FFMPEG;
        mMuxer = BaseMuxer.create(muxerType, this);

        if (!mMuxer.initialize()) {
            Log.e(TAG, "Failed to initialize the muxer");
            return false;
        }

        // Start encoding.
        try {
            while (!mIsEOS) {
                encode();
                pumpOutput();
            }
        } catch (RuntimeException | IOException ex) {
            Log.e(TAG, "Encoding failed:", ex);
        }

        if (mEIL != null) {
            mEIL.release();
        }

        if (mMuxer != null) {
            mMuxer.close();
        }

        return true;
    }

    /**
     Encode each individual video frame.
     */
    private void encode() throws IOException {
        // Attempt to get a picture object from the EIL
        long picture = mEIL.getPicture();

        // If the EIL has a picture available we can send an image to the encoder for encoding
        if (picture != EIL.INVALID_PICTURE) {
            ByteBuffer inputFrame = mInput.nextFrame();
            if (inputFrame == null) {
                throw new RuntimeException("Failed to get input");
            }

            int currentFrameId = mInput.getFramesRead() - 1;

            // Calculate presentation time for this frame and signal to the statistics class.
            long pts = Encoder.computePresentationTimeUs(getReferencePts(), currentFrameId, getRefFrameTime());
            FrameInfo info = mStats.startEncodingFrame(pts, currentFrameId);

            inputFrame.flip();
            if (!mEIL.encode(picture, inputFrame)) {
                throw new RuntimeException("Unable to encode frame");
            }

            // Signal that frame has been encoded.
            mStats.stopEncodingFrame(info.getPts(), info.getSize(), info.isIFrame());
        }

        // Once we've read all the frames, flush the encoder
        if (mInput.isFinished()) {
            mIsEOS = true;

            // Flush the encoder by sending a null payload
            if (!mEIL.encode(0, null)) {
                throw new RuntimeException("Unable to flush encoder");
            }
        }
    }

    /**
     Extract output frame and populate statistics
     */
    private void pumpOutput() throws IOException {
        final int timeSteppings = 2; // align with MediaCodec timing
        final ArrayList<FrameInfo> encodedFramesStats = mStats.getEncodedFrames();
        final int encodedCount = mStats.getEncodedFrameCount();

        EIL.OutputBuffer output;
        while ((output = mEIL.getOutput()) != null) {
            // Prefer long to avoid overflow during long runs
            final long pts = output.mPts / timeSteppings;
            final long dts = output.mDts / timeSteppings;

            final ByteBuffer src = output.mBuffer;
            if (src == null || !src.hasRemaining()) {
                mEIL.releaseOutput(output);
                continue;
            }

            // Create a normalized read-only view [0..size)
            ByteBuffer view = src.duplicate().slice().asReadOnlyBuffer();
            final int size = view.remaining();

            final boolean isKeyFrame = output.mBaseType == EIL.BaseType.EIL_BT_IDR;

            // Update app statistics.
            if (mReceivedOutputBuffer < encodedCount) {
                FrameInfo fi = encodedFramesStats.get(mReceivedOutputBuffer++);
                fi.isIFrame(isKeyFrame);
                fi.setSize(size);
            }

            // Write packets to chosen muxer.
            mMuxer.writePackets(view, pts, dts, isKeyFrame);

            mEIL.releaseOutput(output);
        }
    }

    /**
     Initialize the LCEVC encoder with the base and eil encoder parameters.
     Prioritize the direct protobuf paramters over the json config file.
     */
    private void initEncoder() throws RuntimeException {
        // Get the base encoder parameters
        int width = mEncoderProperty.getFrameWidth();
        int height = mEncoderProperty.getFrameHeight();
        int bitrate = mEncoderProperty.getBitrate();
        float iFrameInterval = mEncoderProperty.getIFrameInterval();
        float frameRate = mEncoderProperty.getFramerate();

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, mEncoderProperty.getPixelFormat());

        if (frameRate > 0) {
            format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate);
        }
        if (bitrate > 0) {
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        }

        if (iFrameInterval > 0) {
            format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        }

        String eilConfigJson = "";

        // We will create a copy, so we do not append the base encoder and gop_length to the json string.
        HashMap<String, Object> eilParameters = new HashMap<>(mEncoderProperty.getEilParameters());

        // Set base encoder (e.g., x264)
        if (eilParameters.containsKey(CONFIG_KEY_BASE_ENCODER)) {
            String baseCodec = eilParameters.get(CONFIG_KEY_BASE_ENCODER).toString();
            format.setString(AndroidEIL.KEY_BASE_ENCODER, baseCodec);
            eilParameters.remove(CONFIG_KEY_BASE_ENCODER);
        }

        // Gop length cannot be passed to the json config buffer.
        // Must be set with MediaFormat.
        if (eilParameters.containsKey(CONFIG_KEY_GOP_LENGTH)) {
            int gopLength = Integer.parseInt(eilParameters.get(CONFIG_KEY_GOP_LENGTH).toString());
            format.setInteger(AndroidEIL.KEY_GOP_LENGTH, gopLength);
            eilParameters.remove(CONFIG_KEY_GOP_LENGTH);
        }

        // Convert the other eil parameters to json for LCEVC encoding.
        eilConfigJson = convertEncoderMapToJsonString(eilParameters);
        Log.d(TAG, "Parsing LCEVC EIL parameters from protobuf parameters");
        Log.d(TAG, eilConfigJson);

        // eilConfigJson will never be empty due to the brackets ({})
        if (eilConfigJson.length() > 2) {
            try {
                ByteBuffer jsonBuffer = ByteBuffer.wrap(eilConfigJson.getBytes("UTF8"));
                format.setByteBuffer("csd-0", jsonBuffer);
            } catch (Exception e) {
                Log.e(TAG, "Could not set encode JSON EIL parameters", e);
            }
        }

        Log.d(TAG, "Configuring LCEVC EIL with " + format);

        if (!mEIL.configure(format, null)) {
            throw new RuntimeException("Failed to configure LCEVC encoder");
        }

    }

    /**
     Convert all LCEVC EIL parameters to a Json format.
     E.g., "{\"key1\":\"value1\",\"key2\":\"value2\"}"
     */
    public static String convertEncoderMapToJsonString(HashMap<String, Object> eilParameters) {
        if (eilParameters.isEmpty()) {
            return "";
        }

        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{");

        Iterator<Map.Entry<String, Object>> eilParametersIt = eilParameters.entrySet().iterator();
        while (eilParametersIt.hasNext()) {
            Map.Entry<String, Object> entry = eilParametersIt.next();
            String key = entry.getKey();
            Object value = entry.getValue();

            // Append the key in JSON format: "key":
            jsonBuilder.append("\"").append(key).append("\":");

            // Only wrap the string values in quotes.
            if (value instanceof Integer ||
                    value instanceof Double ||
                    value instanceof Float ||
                    value instanceof Long ||
                    value instanceof Boolean) {
                jsonBuilder.append(value);
            } else {
                jsonBuilder.append("\"").append(value).append("\"");
            }

            // Add a comma between pairs, but not after the last pair.
            if (eilParametersIt.hasNext()) {
                jsonBuilder.append(",");
            }
        }

        jsonBuilder.append("}");

        return jsonBuilder.toString();
    }

    /**
     Return path to the muxed file.
     */
    public String getMuxedFile() {
        return mMuxedFile;
    }
}

interface BaseMuxer extends Closeable {
    public boolean initialize() throws IOException;
    public boolean writePackets(ByteBuffer buffer, long pts, long dts, boolean isKeyFrame);
    public void writeTrailer();
    public void close();

    enum MuxerType { FFMPEG, MEDIA_MUXER }

    static BaseMuxer create(MuxerType muxerType, EncoderEIL encoderEIL) {
        switch (muxerType) {
            case FFMPEG:       return new FFmpegMuxerImpl(encoderEIL);
            case MEDIA_MUXER:  return new MediaMuxerImpl(encoderEIL);
            default:           throw new IllegalArgumentException("Unsupported muxer: " + muxerType);
        }
    }
}

class MediaMuxerImpl implements BaseMuxer {
    private EncoderEIL mEncoderEIL;
    private static final String TAG = "MediaMuxer";
    private final int UNSET_VIDEO_TRACK_INDEX = -1;
    private MediaMuxer mMediaMuxer;
    private MediaCodec.BufferInfo mBufferInfo;
    private MediaFormat mFormat;
    private ByteBuffer mBuffer;
    private int mVideoTrackIndex = UNSET_VIDEO_TRACK_INDEX;
    private int mCurrentFrameID = 0;

    public MediaMuxerImpl(EncoderEIL encoderEIL) {
        mEncoderEIL = encoderEIL;
    }

    @Override public boolean initialize() throws IOException {
        mMediaMuxer = new MediaMuxer(mEncoderEIL.getMuxedFile(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        if(mMediaMuxer == null) {
            Log.e(TAG, "Failed to initialize MediaMuxer");
            return false;
        }

        mFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                mEncoderEIL.getLcevcEIL().getBaseWidth(),
                mEncoderEIL.getLcevcEIL().getBaseHeight());

        if (mFormat == null) {
            Log.e(TAG, "Failed to create video format");
            return false;
        }

        mFormat.setInteger(MediaFormat.KEY_WIDTH, mEncoderEIL.getLcevcEIL().getBaseWidth());
        mFormat.setInteger(MediaFormat.KEY_HEIGHT, mEncoderEIL.getLcevcEIL().getBaseHeight());
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, mEncoderEIL.getEncoderProperty().getBitrate());
        mFormat.setFloat(MediaFormat.KEY_FRAME_RATE, mEncoderEIL.getEncoderProperty().getFramerate());
        mFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mEncoderEIL.getEncoderProperty().getPixelFormat());

        if (mEncoderEIL.getEncoderProperty().getIFrameInterval() > 0)
            mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mEncoderEIL.getEncoderProperty().getIFrameInterval());

        mBufferInfo = new MediaCodec.BufferInfo();

        return true;
    }

    @Override public boolean writePackets(ByteBuffer buffer, long pts, long dts, boolean isKeyFrame) {
        mBuffer = buffer.duplicate().slice().asReadOnlyBuffer();

        final String SPS_KEY = "csd-0";
        final String PPS_KEY = "csd-1";

        if ((!mFormat.containsKey(SPS_KEY)) || (!mFormat.containsKey(PPS_KEY))) {
            try {
                ByteBuffer sps = ByteBuffer.wrap(extractNalusByType(mBuffer, Encoder.H264_NALU_TYPE_SPS));
                ByteBuffer pps = ByteBuffer.wrap(extractNalusByType(mBuffer, Encoder.H264_NALU_TYPE_PPS));
                mFormat.setByteBuffer(SPS_KEY, sps);
                mFormat.setByteBuffer(PPS_KEY, pps);
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract the NAL SPS and PPS ", e);
            }

            mVideoTrackIndex = mMediaMuxer.addTrack(mFormat);
            mMediaMuxer.start();
        }

        mBufferInfo.offset = 0;
        mBufferInfo.size = mBuffer.remaining();

        // Use reference pts for MediaMuxer.
        mBufferInfo.presentationTimeUs = Encoder.computePresentationTimeUs(mEncoderEIL.getReferencePts(), mCurrentFrameID++, mEncoderEIL.getRefFrameTime());

        if (isKeyFrame)
            mBufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;

        if(mVideoTrackIndex != UNSET_VIDEO_TRACK_INDEX) {
            mBuffer.position(mBufferInfo.offset);
            mBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
            mMediaMuxer.writeSampleData(mVideoTrackIndex, mBuffer, mBufferInfo);
        }
        else {
            Log.e(TAG, "Failed to initialize the MediaMuxer");
            return false;
        }

        return true;
    }

    @Override public void writeTrailer() {
        if (mMediaMuxer != null) {
            mMediaMuxer.release();
        }

        Log.d(TAG, "Muxing complete!");
    }

    @Override public void close() {
        writeTrailer();
    }

    /**
     * Extracts the NAL unit from the encoded stream based on the nal type.
     */
    public byte[] extractNalusByType(ByteBuffer encodedBuffer, int nalType) {
        ByteBuffer buffer = encodedBuffer.duplicate().slice().asReadOnlyBuffer();

        byte[] encodedStream = new byte[buffer.remaining()];
        buffer.get(encodedStream);

        for (int i = 0; i < encodedStream.length - 4; i++) {
            // Check for NAL unit start code: 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01.
            int nalStartIndex = findNalStartCodePrefixIndex(encodedStream, i);
            if (nalStartIndex < encodedStream.length)  {
                int startCodeLength = findNalStartCodePrefixLength(encodedStream, nalStartIndex);
                // Check if the NAL unit is of the desired type (e.g., SPS or PPS)
                if (getNalUnitType(encodedStream, nalStartIndex) == nalType) {
                    // Find the next start code or end of stream
                    int startPosition = i + startCodeLength;
                    int end = encodedStream.length;
                    for (int j = startPosition; j < encodedStream.length - 4; j++) {
                        end = findNalStartCodePrefixIndex(encodedStream, j);
                        if (end != encodedStream.length)
                            break;
                    }
                    // Extract the NAL unit byte array
                    return Arrays.copyOfRange(encodedStream, startPosition, end);
                }
            }
        }

        return null;
    }

    /**
     * Finds the bitstream index where the NALU start code prefix (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01) begins.
     */
    private static int findNalStartCodePrefixIndex(byte[] data, int startPosition) {
        if ((data[startPosition] == 0x00 && data[startPosition + 1] == 0x00 && data[startPosition + 2] == 0x00 && data[startPosition + 3] == 0x01) ||
                data[startPosition] == 0x00 && data[startPosition + 1] == 0x00 && data[startPosition + 2] == 0x01) {
            return startPosition;
        }
        return data.length;
    }

    /**
     * Find the NALU start code prefix length from the bitstream.
     * In H264, the start code prefix length is either 4 (0x00 0x00 0x00 0x01) or 3 (0x00 0x00 0x01).
     */
    private static int findNalStartCodePrefixLength(byte[] data, int startCodeIndex) {
        return ((data[startCodeIndex + 2] == 0x01)? 3 : 4);
    }

    private static int getNalUnitType(byte[] data, int startCodeIndex) {
        int nalStartCodeLength = findNalStartCodePrefixLength(data, startCodeIndex);
        return data[startCodeIndex + nalStartCodeLength] & 0x1F;
    }
}

class FFmpegMuxerImpl implements BaseMuxer {
    private EncoderEIL mEncoderEIL;
    private static final String TAG = "FFmpeg Muxer";
    private AVFormatContext mFormatContext;
    private AVOutputFormat mOutputFormat;
    private AVStream mStream;
    private AVCodec mCodec;
    private AVCodecContext mCodecContext;
    private AVPacket mAvPacket;
    private boolean isAVPacketInitialized = false;
    private BytePointer mBytePointer;
    private int mNativeCapacity = 0;
    private byte[] mPacket;

    public FFmpegMuxerImpl(EncoderEIL encoderEIL) {
        final long MAX_BYTES = 1L << 31; // Set to 2 GB. Default is 1 GB.

        System.setProperty("org.bytedeco.javacpp.maxbytes", String.valueOf(MAX_BYTES));
        mEncoderEIL = encoderEIL;
    }

    @Override public boolean initialize() {
        mFormatContext = avformat_alloc_context();

        if (mFormatContext == null) {
            Log.e(TAG, "Could not allocate format context.");
            return false;
        }

        mOutputFormat = av_guess_format(null, mEncoderEIL.getMuxedFile(), null);
        if (mOutputFormat == null) {
            Log.e(TAG, "Could not guess output format.");
            return false;
        }
        mFormatContext.oformat(mOutputFormat);

        mStream = avformat_new_stream(mFormatContext, null);
        if (mStream == null) {
            Log.e(TAG, "Could not create new stream.");
            return false;
        }

        mCodec = avcodec_find_encoder(AV_CODEC_ID_H264);
        if (mCodec == null) {
            Log.e(TAG, "H.264 encoder not found.");
            return false;
        }

        mCodecContext = avcodec_alloc_context3(mCodec);
        if (mCodecContext == null) {
            Log.e(TAG, "Could not allocate codec context.");
            return false;
        }

        mCodecContext.codec_id(AV_CODEC_ID_H264);
        mCodecContext.codec_type(AVMEDIA_TYPE_VIDEO);
        mCodecContext.bit_rate(mEncoderEIL.getEncoderProperty().getBitrate());
        mCodecContext.framerate(av_make_q((int) mEncoderEIL.getEncoderProperty().getFramerate(), 1));
        mCodecContext.pix_fmt(AV_PIX_FMT_YUV420P);
        mCodecContext.time_base(av_make_q(1, (int) mEncoderEIL.getEncoderProperty().getFramerate()));

        if (mEncoderEIL.getLcevcEIL() != null) {
            mCodecContext.width(mEncoderEIL.getLcevcEIL().getBaseWidth());
            mCodecContext.height(mEncoderEIL.getLcevcEIL().getBaseHeight());
        }

        if ((mFormatContext.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
            mCodecContext.flags(mCodecContext.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
        }

        if (avcodec_open2(mCodecContext, mCodec, (PointerPointer<?>) null) < 0) {
            Log.e(TAG, "Could not open codec.");
            return false;
        }

        if (avcodec_parameters_from_context(mStream.codecpar(), mCodecContext) < 0) {
            Log.e(TAG, "Failed to copy codec parameters.");
            return false;
        }

        if ((mFormatContext.oformat().flags() & AVFMT_NOFILE) == 0) {
            AVIOContext pb = new AVIOContext(null);
            if (avio_open(pb, mEncoderEIL.getMuxedFile(), AVIO_FLAG_WRITE) < 0) {
                Log.e(TAG, "Could not open output file.");
                return false;
            }
            mFormatContext.pb(pb);
        }

        // Initialize packet + reusable native buffer
        if (!isAVPacketInitialized) {
            mAvPacket = av_packet_alloc();
            if (mAvPacket == null) {
                Log.e(TAG, "av_packet_alloc failed.");
                return false;
            }
            av_init_packet(mAvPacket);
            isAVPacketInitialized = true;
        }

        setPacketCapacity();

        if (!writeHeader()) {
            return false;
        }

        Log.d(TAG, "Muxer has been successfully initialized.");

        return true;
    }

    public boolean writeHeader() {
        if (avformat_write_header(mFormatContext, (PointerPointer<?>) null) < 0) {
            Log.e(TAG, "Error writing MP4 header.");
            return false;
        }
        Log.d(TAG, "MP4 header written successfully.");
        return true;
    }

    @Override
    public boolean writePackets(ByteBuffer buffer, long pts, long dts, boolean isKeyFrame) {
        if (!isAVPacketInitialized || mAvPacket == null || mBytePointer == null) {
            Log.e(TAG, "BaseMuxer not initialized.");
            return false;
        }
        if (buffer == null || !buffer.hasRemaining()) {
            return true;
        }

        ByteBuffer view = buffer.duplicate().slice();
        int size = view.remaining();

        ensurePacketCapacity(size);
        view.get(mPacket, 0, size);
        mBytePointer.put(mPacket, 0, size);

        // Build packets
        mAvPacket.data(mBytePointer);
        mAvPacket.size(size);
        mAvPacket.stream_index(mStream.index());

        if (isKeyFrame) {
            mAvPacket.flags(mAvPacket.flags() | AV_PKT_FLAG_KEY);
        }

        // Always rescale BOTH pts and dts to stream time_base
        AVRational encTb   = mCodecContext.time_base();  // usually 1/framerate
        AVRational muxTb   = mStream.time_base();        // chosen by muxer after writeHeader()

        long ptsMux = av_rescale_q(pts, encTb, muxTb);
        long dtsMux = av_rescale_q(dts, encTb, muxTb);
        if (dtsMux > ptsMux) dtsMux = ptsMux;            // safety: enforce DTS <= PTS

        mAvPacket.pts(ptsMux);
        mAvPacket.dts(dtsMux);

        // Give the MP4 muxer an explicit per-frame duration (CFR)
        int frameNum = 1; // one frame
        long duration = av_rescale_q(frameNum, av_make_q(1, (int)mEncoderEIL.getEncoderProperty().getFramerate()), muxTb);
        if (duration <= 0) duration = 1;                 // never zero
        mAvPacket.duration(duration);

        // Write frames
        int ret = av_interleaved_write_frame(mFormatContext, mAvPacket);
        if (ret < 0) {
            Log.e(TAG, "Error writing avPacket: " + ret);
            return false;
        }

        av_packet_unref(mAvPacket);

        return true;
    }

    private void ensurePacketCapacity(int size) {
        if (size <= mNativeCapacity)
            return;

        if (mBytePointer != null) {
            mBytePointer.deallocate();  // free old native memory
            mBytePointer = null;
        }

        // Grow in chunks to avoid frequent reallocations
        mNativeCapacity = Math.max(size, mNativeCapacity * 2);

        mBytePointer = new BytePointer(mNativeCapacity);
        mPacket = new byte[mNativeCapacity];
    }

    private void setPacketCapacity() {
        mNativeCapacity = 64 * 1024; // Set the intial packet capacity to 64 KB.

        mPacket = new byte[mNativeCapacity];
        mBytePointer = new BytePointer(mNativeCapacity);
    }

    public void writeTrailer() {
        if (mFormatContext != null) {
            int ret = av_write_trailer(mFormatContext);
            if (ret < 0) Log.e(TAG, "Error writing trailer: " + ret);
        }

        if (mAvPacket != null) {
            av_packet_free(mAvPacket);
            mAvPacket = null;
            isAVPacketInitialized = false;
        }
        if (mBytePointer != null) {
            mBytePointer.close();
            mBytePointer = null;
            mNativeCapacity = 0;
        }

        if (mCodecContext != null) {
            avcodec_free_context(mCodecContext);
            mCodecContext = null;
        }

        if (mFormatContext != null) {
            // Close IO if we opened it
            AVIOContext pb = mFormatContext.pb();
            if (pb != null) {
                avio_closep(mFormatContext.pb());
            }
            avformat_free_context(mFormatContext);
            mFormatContext = null;
        }

        Log.d(TAG, "Muxing complete!");
    }

    @Override public void close() {
        writeTrailer();
    }
}