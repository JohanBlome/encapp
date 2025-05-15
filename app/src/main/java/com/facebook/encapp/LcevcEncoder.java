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
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;
import java.util.Iterator;

import java.io.Closeable;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONException;
import org.json.JSONObject;

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
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;


class LcevcEncoder extends Encoder {
    protected static final String TAG = "LCEVC Encoder";
    public LcevcEncoder(Test test) {
        super(test);
        mStats = new Statistics("lcevc_encoder", mTest);
    }

    public void setRuntimeParameters(int frame) {
        // go through all runtime settings and see which are due
        if (mRuntimeParams == null)
            return;
        Vector<Parameter> params = new Vector();

        for (Runtime.VideoBitrateParameter bitrate : mRuntimeParams.getVideoBitrateList()) {
            if (bitrate.getFramenum() == frame) {
                addEncoderParameters(params, DataValueType.intType.name(), BITRATE, String.valueOf(bitrate));
                break;
            }
        }

        for (Long sync : mRuntimeParams.getRequestSyncList()) {
            if (sync == frame) {
                addEncoderParameters(params, DataValueType.longType.name(), "request-sync", "");
                break;
            }
        }

        for (Parameter param : mRuntimeParams.getParameterList()) {
            if (param.getFramenum() == frame) {
                Log.d(TAG, "Set runtime parameter @ " + frame + " key: " + param.getKey() + ", " + param.getType()
                        + ", " + param.getValue());
            }
        }

        Parameter[] param_buffer = new Parameter[params.size()];
        params.toArray(param_buffer);
    }

    /**
     Get all lcevc-based encoder settings from the tests' proto files.
     These are the json keys with "parameter".
     */
    public void getBaseEncoderConfig(Vector<Parameter> encoder_base_config) {
        try {
            int bitrate = magnitudeToInt(mTest.getConfigure().getBitrate());
            int iFrameInterval = mTest.getConfigure().getIFrameInterval();
            float frameRate = mTest.getConfigure().getFramerate();

            addEncoderParameters(encoder_base_config, DataValueType.intType.name(), BITRATE, String.valueOf(bitrate));
            addEncoderParameters(encoder_base_config, DataValueType.intType.name(), I_FRAME_INTERVAL, String.valueOf(iFrameInterval));
            addEncoderParameters(encoder_base_config, DataValueType.floatType.name(), FRAMERATE, String.valueOf(frameRate));

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
                + ", bitrate_mode:" + bitrateMode + ", bitrate: " + bitrate + ", iFrameInterval: " + iFrameInterval);

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
            Vector<Parameter> encoder_eil_config = new Vector<>(mTest.getConfigure().getParameterList());

            Vector<Parameter> encoder_base_config = new Vector<>();
            getBaseEncoderConfig(encoder_base_config);

            mTest = mTest.toBuilder().setConfigure(mTest.getConfigure().toBuilder().addAllParameter(encoder_base_config))
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
                        encoder_base_config,
                        encoder_eil_config,
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

            File encodedStreamFile = eilEncoder.getElementaryStream();
            if (encodedStreamFile != null && !encodedStreamFile.exists()) {
                return "Unable to access the LCEVC encoded stream file";
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

/**
 * EIL Class that implements the LCEVC encoding and muxing.
 */
class EncoderEIL {
    private static final String TAG = "Lcevc Encoder EIL";
    public static final String CONFIG_KEY_JSON = "eil_params_path";
    public static final String CONFIG_KEY_BASE_ENCODER = "base_encoder";
    public static final String CONFIG_KEY_GOP_LENGTH = "gop_length";
    public static final String CODEC_MEDIACODEC_H264 = "mediacodec_h264";
    public static final String CODEC_X264 = "x264";
    private Input mInput;
    private FileChannel mOutput;
    private AndroidEIL mEIL;
    private File mOutputFile;
    String mMuxedFile = "";
    private boolean mIsEOS = false;
    private Statistics mStats;
    private long mReferencePts = 0;
    private double mRefFrameTime = 0;
    private int mReceivedOutputBuffer = 0;
    private EncoderProperty mEncoderProperty;

    private ArrayList<AVPacketProperty> avPacketProperty;

    /**
     Read the input file and extracts the frames to be encoded.
     */
    static class Input implements Closeable
    {
        private File mFile;
        private FileChannel mChannel;
        private int mFrameSize = 0;
        private int mFramesRead = 0;
        private int mNumFrames = 0;

        public Input(File file, int frameWidth, int frameHeight) {
            mFile = file;
            try {
                mChannel = FileChannel.open(mFile.toPath(), StandardOpenOption.READ);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot open file channel", ex);
            }

            mFrameSize = frameWidth * frameHeight * 3 / 2;

            if (mFrameSize > 0) {
                mNumFrames = (int) (mFile.length() / mFrameSize);
            } else {
                throw new RuntimeException("Input must have non-zero frame size");
            }
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
            ByteBuffer buffer = ByteBuffer.allocateDirect(mFrameSize);

            if (mChannel.read(buffer) == mFrameSize) {
                mFramesRead++;
                return buffer;
            }

            return null;
        }

    }

    /**
     Handle associated libavformat av property for muxing.
     */
    static class AVPacketProperty {
        private byte[] mPacket;
        private int mPts;
        private int mDts;
        private int mSize;

        public AVPacketProperty(ByteBuffer byteBuffer, int pts, int dts, int size) {
            byteBuffer.flip();

            mPacket= new byte[byteBuffer.remaining()];
            byteBuffer.get(mPacket);

            mSize = size;
            mPts = pts;
            mDts = dts;
        }


        public byte[] getPacket() {
            return mPacket;
        }

        public int getPts() {
            return mPts;
        }

        public int getDts() {
            return mDts;
        }

        public int getSize() {
            return mSize;
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

        EncoderProperty(int width, int height, int pixelFormat, Vector<Parameter> base_config, Vector<Parameter> eil_config) {
            mFrameWidth = width;;
            mFrameHeight = height;
            mPixelFormat = pixelFormat;
            mEILParameters = new HashMap<>();

            // Extract the bitrate, frame rate and iframe interval from the base config.
            for(int i=0; i<base_config.size(); i++) {
                Parameter param = base_config.elementAt(i);
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
            for(int i=0; i<eil_config.size(); i++) {
                Parameter param = eil_config.elementAt(i);
                mEILParameters.put(param.getKey(), param.getValue());
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

        public void setKeyValueEilParameter(String key, Object value) {
            if (mEILParameters != null) {
                mEILParameters.put(key, value);
            }
        }
    }

    public EncoderEIL(File file,
                      int frameWidth,
                      int frameHeight,
                      int pixelFormat,
                      Vector<Parameter> base_config,
                      Vector<Parameter> eil_config,
                      Statistics statistics,
                      long referencePts,
                      double refFrameTime) {
        try {
            // Initialize the Android EIL and I/O properties.
            mEIL = new AndroidEIL();
            mEncoderProperty = new EncoderProperty(frameWidth, frameHeight, pixelFormat, base_config, eil_config);
            avPacketProperty = new ArrayList<>();

            mInput = new Input(file, frameWidth, frameHeight);
            mOutputFile = createEncodedStreamFile(file);
            mOutputFile.getParentFile().mkdirs();
            mOutput = FileChannel.open(mOutputFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

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

    /**
     * Encode input frames into an MP4 file.
     */
    public boolean run() throws IOException {
        boolean ret = false;

        initEncoder();

        // We will need the reference pts and frame time for encoding and muxing. Ensure they are set.
        if (mReferencePts == 0 || mRefFrameTime == 0) {
            Log.e(TAG, "Reference pts or frame time not set");
            return ret;
        }

        try {
            while (!mIsEOS) {
                encode(mReferencePts, mRefFrameTime);
                pumpOutput();
            }
            mOutput.close();
        } catch (RuntimeException | IOException ex) {
            Log.e(TAG, "Encoding failed:", ex);
        }

        // If the base codec is x264, use the libavformat muxer. Otherwise use MediaMuxer.
        String baseCodec = mEncoderProperty.getBaseEncoder();
        if (baseCodec.equals(CODEC_X264)){
            ret = libAVMuxerWrite();
        } else if (baseCodec.equals(CODEC_MEDIACODEC_H264)) {
            ret = mediaMuxerWrite(mReferencePts, mRefFrameTime);
        } else {
            Log.e(TAG, baseCodec + " is not supported");
        }

        if (mEIL != null) {
            mEIL.release();
        }

        return ret;
    }

    /**
     * Initializes libavformat muxer and convert the lcevc encoded stream to mp4.
     * Currently valid for x264 only.
     */
    private boolean libAVMuxerWrite() {
        AVFormatContext formatContext = avformat_alloc_context();
        if (formatContext == null) {
            Log.e(TAG, "Could not allocate format context.");
            return false;
        }

        AVOutputFormat outputFormat = av_guess_format(null, getMuxedFile(), null);
        if (outputFormat == null) {
            Log.e(TAG, "Could not guess output format.");
            return false;
        }
        formatContext.oformat(outputFormat);

        AVStream stream = avformat_new_stream(formatContext, null);
        if (stream == null) {
            Log.e(TAG, "Could not create new stream.");
            return false;
        }

        AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        if (codec == null) {
            Log.e(TAG, "H.264 encoder not found.");
            return false;
        }

        AVCodecContext codecContext = avcodec_alloc_context3(codec);
        if (codecContext == null) {
            Log.e(TAG, "Could not allocate codec context.");
            return false;
        }

        codecContext.codec_id(AV_CODEC_ID_H264);
        codecContext.codec_type(AVMEDIA_TYPE_VIDEO);
        codecContext.bit_rate(mEncoderProperty.getBitrate());
        codecContext.framerate(av_make_q((int) mEncoderProperty.getFramerate(), 1)); // denomenator of 1
        codecContext.pix_fmt(AV_PIX_FMT_YUV420P);
        codecContext.time_base(av_make_q(1, (int) mEncoderProperty.getFramerate()));  // numerator of 1

        if (mEIL != null) {
            codecContext.width(mEIL.getBaseWidth());
            codecContext.height(mEIL.getBaseHeight());
        }


        if ((formatContext.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
            codecContext.flags(codecContext.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
        }

        if (avcodec_open2(codecContext, codec, (PointerPointer) null) < 0) {
            Log.e(TAG, "Could not open codec.");
            return false;
        }

        if (avcodec_parameters_from_context(stream.codecpar(), codecContext) < 0) {
            Log.e(TAG, "Failed to copy codec parameters.");
            return false;
        }

        if ((formatContext.oformat().flags() & AVFMT_NOFILE) == 0) {
            AVIOContext pb = new AVIOContext(null);

            if (avio_open(pb, getMuxedFile(), AVIO_FLAG_WRITE) < 0) {
                Log.e(TAG, "Could not open output file.");
                return false;
            }
            formatContext.pb(pb);
        }

        if (avformat_write_header(formatContext, (PointerPointer) null) < 0) {
            Log.e(TAG, "Error writing MP4 header.");
            return false;
        }

        Log.d(TAG,"MP4 header written successfully.");

        // Create AVPacket for reading and writing
        AVPacket avPacket = av_packet_alloc();
        av_init_packet(avPacket);

        for (AVPacketProperty packet : avPacketProperty) {
            BytePointer bytePointer = new BytePointer(packet.getSize());

            bytePointer.put(packet.getPacket(), 0, packet.getSize());
            avPacket.data(bytePointer);
            avPacket.size(packet.getSize());

            // Set PTS/DTS values (this is crucial for proper synchronization)
            avPacket.pts(av_rescale_q(packet.getPts(), codecContext.time_base(), stream.time_base()));
            avPacket.dts(packet.getDts());

            // Mux the avPacket into the MP4 container
            if (av_interleaved_write_frame(formatContext, avPacket) < 0) {
                Log.e(TAG, "Error writing avPacket.");
                return false;
            }
        }

        // Write trailer (finalizing MP4 file)
        if (av_write_trailer(formatContext) < 0) {
            Log.e(TAG, "Error writing trailer.");
        }

        // Cleanup resources
        avcodec_free_context(codecContext);
        avformat_free_context(formatContext);
        av_packet_free(avPacket);

        Log.d(TAG, "Muxing complete!");
        return true;
    }

    /**
     * Convert the lcevc encoded stream to mp4 using MediaMuxer.
     * Currently valid for mediacodec only.
     */
    private boolean mediaMuxerWrite(long referencePts, double refFrameTime) throws IOException {
        MediaMuxer muxer = new MediaMuxer(getMuxedFile(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        if(muxer == null) {
            Log.e(TAG, "Failed to initialize MediaMuxer");
            return false;
        }

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                mEIL.getBaseWidth(),
                mEIL.getBaseHeight());

        if (format == null) {
            Log.e(TAG, "Failed to create video format");
            return false;
        }

        format.setInteger(MediaFormat.KEY_WIDTH, mEIL.getBaseWidth());
        format.setInteger(MediaFormat.KEY_HEIGHT, mEIL.getBaseHeight());
        format.setInteger(MediaFormat.KEY_BIT_RATE, mEncoderProperty.getBitrate());
        format.setFloat(MediaFormat.KEY_FRAME_RATE, mEncoderProperty.getFramerate());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, mEncoderProperty.getPixelFormat());

        if (mEncoderProperty.getIFrameInterval() > 0)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mEncoderProperty.getIFrameInterval());

        if (avPacketProperty == null || avPacketProperty.isEmpty())
        {
            Log.e(TAG, "No encoded output packet is available");
            return false;
        }


        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int videoTrackIndex = -1;
        int currentFrameID = 0;

        final String spsKey = "csd-0";
        final String ppsKey = "csd-1";

        for (AVPacketProperty packet : avPacketProperty) {
            // sps and pps only need to be set once.
            if ((!format.containsKey(spsKey)) ||
                    (!format.containsKey(ppsKey))) {
                try {
                    ByteBuffer sps = ByteBuffer.wrap(extractSpsPps(packet.getPacket(), Encoder.H264_NALU_TYPE_SPS));
                    ByteBuffer pps = ByteBuffer.wrap(extractSpsPps(packet.getPacket(), Encoder.H264_NALU_TYPE_PPS));
                    format.setByteBuffer(spsKey, sps);
                    format.setByteBuffer(ppsKey, pps);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to extract the NAL SPS and PPS ", e);
                }

                videoTrackIndex = muxer.addTrack(format);
                muxer.start();
            }

            ByteBuffer buffer = ByteBuffer.wrap(packet.getPacket());
            bufferInfo.offset = 0;
            bufferInfo.size = packet.getSize();
            // Use reference pts for MediaMuxer.
            bufferInfo.presentationTimeUs = Encoder.computePresentationTimeUs(referencePts, currentFrameID++, refFrameTime);

            boolean isKeyFrame = Encoder.checkIfKeyFrame(packet.getPacket(), Encoder.SyntaxType.H264);

            if (isKeyFrame)
                bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;

            if(videoTrackIndex != -1) {
                buffer.position(bufferInfo.offset);
                buffer.limit(bufferInfo.offset + bufferInfo.size);
                muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo);
            }
            else {
                Log.e(TAG, "Failed to initialize the MediaMuxer");
                return false;
            }
        }

        muxer.release();

        return true;
    }


    /**
     * Extracts the NAL unit from the encoded stream based on the nal type.
     */
    public static byte[] extractSpsPps(byte[] encodedStream, int nalType) {
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

    /**
     Encode each individual video frame.
     */
    private void encode(long referencePts, double refFrameTime) throws IOException {
        // Attempt to get a picture object from the EIL
        long picture = mEIL.getPicture();

        // If the EIL has a picture available we can send an image to the encoder for encoding
        if (picture != EIL.INVALID_PICTURE) {
            ByteBuffer inputFrame = mInput.nextFrame();
            if (inputFrame == null) {
                throw new RuntimeException("Failed to get input");
            }

            int currentFrameID = mInput.getFramesRead() - 1;

            // Calculate presentation time for this frame and signal to the statistics class.
            long pts = Encoder.computePresentationTimeUs(referencePts, currentFrameID, refFrameTime);
            FrameInfo info = mStats.startEncodingFrame(pts, currentFrameID);

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
        // Adjust timestamp by 2 to align EIL encoder output with MediaCodec timing.
        int timestampSteppings = 2;
        EIL.OutputBuffer output;
        int pts = 0;
        int dts = 0;
        int size = 0;

        while ((output = mEIL.getOutput()) != null) {
            pts = (int) (output.mPts / timestampSteppings);
            dts = (int) (output.mDts / timestampSteppings);
            size = output.mBuffer.limit();

            // Set output buffer flags
            ArrayList<FrameInfo> encodedFramesStats = mStats.getEncodedFrames();
            if (mReceivedOutputBuffer < mStats.getEncodedFrameCount()) {
                FrameInfo frameInfo = encodedFramesStats.get(mReceivedOutputBuffer++);

                if (output.mBuffer != null && output.mBuffer.hasRemaining()) {
                    byte[] outputBuffer = new byte[output.mBuffer.remaining()];
                    output.mBuffer.get(outputBuffer);

                    boolean isKeyFrame = Encoder.checkIfKeyFrame(outputBuffer, Encoder.SyntaxType.H264);
                    frameInfo.isIFrame(isKeyFrame);
                    frameInfo.setSize(size);
                }
            }

            // Flip the output buffer for reading.
            output.mBuffer.flip();

            // Write the elementary stream file.
            mOutput.write(output.mBuffer);

            avPacketProperty.add(new AVPacketProperty(output.mBuffer, pts, dts, size));
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

        // Set key parameters with MediaFormat (bitrate...)
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

        String pathToJsonConfig = "";
        String eilConfigJson = "";

        HashMap<String, Object> eilParameters = mEncoderProperty.getEilParameters();

        // Set base encoder (e.g., x264)
        if (eilParameters.containsKey(CONFIG_KEY_BASE_ENCODER)) {
            String baseCodec = eilParameters.get(CONFIG_KEY_BASE_ENCODER).toString();
            format.setString(AndroidEIL.KEY_BASE_ENCODER, baseCodec);
        }

        // Gop length cannot be passed to the json config buffer.
        // Must be set with MediaFormat.
        if (eilParameters.containsKey(CONFIG_KEY_GOP_LENGTH)) {
            int gopLength = Integer.parseInt(eilParameters.get(CONFIG_KEY_GOP_LENGTH).toString());
            format.setInteger(AndroidEIL.KEY_GOP_LENGTH, gopLength);
        }

        // Convert the other eil parameters to json for LCEVC encoding.
        eilConfigJson = convertEncoderMapToJsonString(eilParameters);
        Log.d(TAG, "Parsing LCEVC EIL parameters from protobuf parameters");
        Log.d(TAG, eilConfigJson);

        // Check if a json config file has been passed.
        if (eilParameters.containsKey(CONFIG_KEY_JSON)) {
            pathToJsonConfig = eilParameters.get(CONFIG_KEY_JSON).toString();

            String[] splitFilePath = pathToJsonConfig.split("/");
            pathToJsonConfig = CliSettings.getWorkDir() + "/" + splitFilePath[splitFilePath.length - 1];
        }

        // Extract the eil parameters from the json file only if no direct parameter has been passed.
        if (!pathToJsonConfig.isEmpty() &&
                (eilParameters.size() == 1 && eilParameters.containsKey(CONFIG_KEY_JSON))) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(pathToJsonConfig)));
                JSONObject jsonObject = new JSONObject(content);

                if (jsonObject.has(CONFIG_KEY_BASE_ENCODER)) {
                    Object value = jsonObject.get(CONFIG_KEY_BASE_ENCODER);
                    String baseCodec = value.toString();
                    format.setString(AndroidEIL.KEY_BASE_ENCODER, baseCodec);

                    // Store base codec in central property class
                    mEncoderProperty.setKeyValueEilParameter(CONFIG_KEY_BASE_ENCODER, baseCodec);
                    // Do not pass to json buffer.
                    jsonObject.remove(CONFIG_KEY_BASE_ENCODER);
                }

                if (jsonObject.has(CONFIG_KEY_GOP_LENGTH)) {
                    Object value = jsonObject.get(CONFIG_KEY_GOP_LENGTH);
                    int gopLength = Integer.parseInt(value.toString());
                    format.setInteger(AndroidEIL.KEY_GOP_LENGTH, gopLength);

                    // Store base codec in central property class
                    mEncoderProperty.setKeyValueEilParameter(CONFIG_KEY_GOP_LENGTH, gopLength);
                    // Do not pass to json buffer.
                    jsonObject.remove(CONFIG_KEY_GOP_LENGTH);
                }

                eilConfigJson = jsonObject.toString();

                Log.d(TAG, "Parsing LCEVC EIL parameters from " + pathToJsonConfig);
                Log.d(TAG, eilConfigJson);
            } catch (IOException e) {
                Log.e(TAG, "Unable to parse the JSON file", e);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

        }

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

            // Do not append the base encoder, gop_length and json config path to the json string.
            if (Objects.equals(key, CONFIG_KEY_BASE_ENCODER) ||
                    Objects.equals(key, CONFIG_KEY_GOP_LENGTH) ||
                    Objects.equals(key, CONFIG_KEY_JSON)) {
                continue;
            }

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
     Create the file to be used to store the encoded elementary stream.
     */
    public File createEncodedStreamFile(File inputFile) {
        String fileName = "encoded.es";
        SimpleDateFormat now = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String nowString = now.format(new Date());
        String inputName = inputFile.getName().replace(".", "_");

        return Paths
                .get(CliSettings.getWorkDir(), "output", inputName + "_" + nowString, fileName)
                .toFile();

    }

    /**
     Return path to the muxed file.
     */
    public String getMuxedFile() {
        return mMuxedFile;
    }

    /**
     Return the encoded and compressed elementary stream.
     */
    public File getElementaryStream() {
        if (mOutputFile.exists()) {
            return mOutputFile;
        }
        else {
            return null;
        }
    }
}