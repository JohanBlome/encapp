package com.facebook.encapp;

import static com.facebook.encapp.utils.TestDefinitionHelper.magnitudeToInt;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.PixFmt;
import com.facebook.encapp.proto.Runtime;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.FrameInfo;
import com.facebook.encapp.utils.MediaCodecInfoHelper;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.StringParameter;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Vector;


/**
 * Created by jobl on 2018-02-27.
 */

class CustomEncoder extends Encoder {
    protected static final String TAG = "encapp.buffer_x264_encoder";

    public static native int initEncoder(Parameter[] parameters, int width, int height, int colorFormat, int bitDepth);
    public static native byte[] getHeader();
    // TODO: can the size, color and bitdepth change runtime?
    public static native int encode(byte[] input, byte[] output, FrameInfo info);

    public static native StringParameter[] getAllEncoderSettings();

    public static native void updateSettings(Parameter[] parameters);
    public static native void close();


    public CustomEncoder(Test test, String filesDir) {
        super(test);
        mStats = new Statistics("raw encoder", mTest);
        // Load lib
        String name = mTest.getConfigure().getCodec();
        File lib = new File(name);
        name = lib.getName();
        String targetPath =  filesDir + "/" + name;
        try {
            Log.d(TAG, "Load native library: " + name);
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(lib));

            FileOutputStream fos = new FileOutputStream(targetPath);
            byte[] tmp = new byte[1024];
            int read = Integer.MAX_VALUE;
            while(read > 0) {
                read = bis.read(tmp);
                if (read > 0) {
                    fos.write(tmp, 0, read);
                }
            }
            bis.close();
            fos.close();

        } catch (FileNotFoundException e) {
            Log.d(TAG, "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.d(TAG, "Ioxceptionen. " + e.getMessage());
        }
        try {
            Log.d(TAG, "Loading nativeencoder");
            System.load(targetPath);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library, " + name + ", " + targetPath + ": " + e.getMessage());
        }
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
                if (nalType == H264_NALU_TYPE_SPS && spsStart == -1) { // SPS NAL unit type is 7
                    spsStart = i;
                } else if (nalType == H264_NALU_TYPE_PPS && spsStart != -1 && spsEnd == -1) { // PPS NAL unit type is 8
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
                if (nalUnitType == H264_NALU_TYPE_IDR) {
                    return true;
                }
            }
        }
        return false;
    }


    public void setRuntimeParameters(int frame) {
        // go through all runtime settings and see which are due
        if (mRuntimeParams == null) return;
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
                Log.d(TAG, "Set runtime parameter @ " + frame + " key: " + param.getKey() + ", " + param.getType() + ", " + param.getValue());
                // Easy everything (for now) is str
            }
        }

        Parameter[] param_buffer = new Parameter[params.size()];
        params.toArray(param_buffer);
        updateSettings(param_buffer);
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
        mRefFramesizeInBytes = (int) (sourceResolution.getWidth() * sourceResolution.getHeight() * 1.5);
        mYuvReader = new FileReader();
        //TODO: fix looping
        int playoutframes = mTest.getInput().getPlayoutFrames();

        int width = sourceResolution.getWidth();
        int height = sourceResolution.getHeight();
        int pixelformat = mTest.getInput().getPixFmt().getNumber();
        int bitdepth = (pixelformat == PixFmt.p010le.getNumber())? 10: 8;
        int bitrate = magnitudeToInt(mTest.getConfigure().getBitrate());
        int bitratemode = mTest.getConfigure().getBitrateMode().getNumber();
        int iframeinterval =  mTest.getConfigure().getIFrameInterval();

        float crf = 23.0f;
        Log.d(TAG, width + "x" + height + ",  pixelformat: " + pixelformat + ", bitdepth:" + bitdepth + ", bitrate_mode:" + bitratemode + ", bitrate: " + bitrate + ", iframeinterval: "+ iframeinterval);
        // Create params for this


        // Caching vital values and see if they can be set runtime.
        Vector<Parameter> params = new Vector(mTest.getConfigure().getParameterList());
        // This one needs to be set as a native param.
        try {
            addEncoderParameters(params, DataValueType.intType.name(), BITRATE, String.valueOf(bitrate));
            addEncoderParameters(params, DataValueType.intType.name(), BITRATE_MODE, String.valueOf(bitratemode));
            addEncoderParameters(params, DataValueType.floatType.name(), I_FRAME_INTERVAL, String.valueOf(iframeinterval));
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

        mMuxerWrapper = createMuxerWrapper(null, mediaFormat);
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
            int status = initEncoder(param_buffer, width, height, pixelformat, bitdepth);
            StringParameter[] settings_ = getAllEncoderSettings();
            //add generic parameters to mTest, this way it can bre exactly reproduced (?) - in the case x264: no
            ArrayList<Parameter> param_list = new ArrayList<>();
            for (StringParameter par: settings_) {
                param_list.add(par.getParameter());
            }

            // TODO: where to save this information
            mTest = mTest.toBuilder().setConfigure(mTest.getConfigure().toBuilder().addAllParameter(param_list)).build();
            Log.d(TAG, "Updated test: " + mTest);
            mStats.updateTest(mTest);
            if (status != 0) {
                Log.e(TAG, "Init failed");
                return "";
            }
            headerArray = getHeader();
            FrameInfo info;
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

                        long pts = computePresentationTimeUs(mPts, mFramesAdded, mRefFrameTime);
                        info = mStats.startEncodingFrame(pts, mFramesAdded);
                        // Let us read the setting in native and force key frame if set here.
                        // If (for some reason a key frame is not produced it will be updated in the native code
                        //info.isIFrame(true);
                        outputBufferSize = encode(yuvData, outputBuffer, info);
                        // Look at nal type as well, not just key frame?
                        // To ms?
                        mStats.stopEncodingFrame(info.getPts() , info.getSize(), info.isIFrame());
                        if (outputBufferSize == 0) {
                            return "Failed to encode frame";
                        } else if (outputBufferSize == -1) {
                            return "Encoder not started";
                        }
                        currentFramePosition += frameSize;
                        mFramesAdded++;
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
                        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                        format.setFloat(MediaFormat.KEY_FRAME_RATE, 30);//mFrameRate);
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 20);//MediaCodecInfoHelper.mapEncappPixFmtToAndroidColorFormat(PixFmt.forNumber(pixelformat)));
                        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);//iframeinterval);//TODO:

                        byte[][] spsPps = extractSpsPps(headerArray);

                        ByteBuffer sps = ByteBuffer.wrap(spsPps[0]);
                        ByteBuffer pps = ByteBuffer.wrap(spsPps[1]);
                        format.setByteBuffer("csd-0", sps);
                        format.setByteBuffer("csd-1", pps);

                        bufferInfo = new MediaCodec.BufferInfo();
                        videoTrackIndex = mMuxerWrapper.addTrack(format);
                        mMuxerWrapper.start();
                        muxerStarted = true;
                    }

                    ByteBuffer buffer = ByteBuffer.wrap(outputBuffer);
                    bufferInfo.offset = 0;
                    bufferInfo.size = outputBufferSize;
                    bufferInfo.presentationTimeUs = info.getPts();

                    //TODO: we get this from FrameInfo instead
                    boolean isKeyFrame = checkIfKeyFrame(outputBuffer);
                    if (isKeyFrame) bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;

                    if(mMuxerWrapper != null) {
                        buffer.position(bufferInfo.offset);
                        buffer.limit(bufferInfo.offset + bufferInfo.size);

                        mMuxerWrapper.writeSampleData(videoTrackIndex, buffer, bufferInfo);
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

            if (mMuxerWrapper != null) {
                try {
                    mMuxerWrapper.release();
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
