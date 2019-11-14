package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Stack;

import static junit.framework.Assert.assertTrue;

/**
 * Created by jobl on 2018-02-27.
 */

class Transcoder {
    // Qualcomm added extended omx parameters
    private static final String MEDIA_KEY_LTR_NUM_FRAMES = "vendor.qti-ext-enc-ltr-count.num-ltr-frames";
    private static String MEDIA_KEY_LTR_MAX_COUNT  = "vendor.qti-ext-enc-caps-ltr.max-count";
    private static final String MEDIA_KEY_LTR_MARK_FRAME = "vendor.qti-ext-enc-ltr.mark-frame";
    private static final String MEDIA_KEY_LTR_USE_FRAME  = "vendor.qti-ext-enc-ltr.use-frame";
    private static final String MEDIA_KEY_HIER_STRUCT_LAYERS  = "vendor.qti-ext-enc-hier-struct.layers";

    private static final String TAG = "encapp";

    static {
        System.loadLibrary("encapp");
    }

    private static final long VIDEO_CODEC_WAIT_TIME_US = 1000;

    private int mFrameRate = 30;
    private float mKeepInterval = 1.0f;
    private String mNextEvent = "";
    private MediaCodec mCodec;

    private int mNextLimit = -1;
    private int mSkipped = 0;
    private int mFramesAdded = 0;

    private Stack<String> mDynamicSetting = null;

    private long mFrameTime = 0;
    int mLTRCount = 0;
    private boolean mUseLTR = false;

    public boolean transcode (
            VideoConstraints vc, String filename, int totalFrames, String dynamic) {
        mNextLimit = -1;
        mSkipped = 0;
        mFramesAdded = 0;
        boolean ok = nativeOpenFile(filename);
        if(!ok) {
            Log.e(TAG, "Failed to open yuv file");
            return false;
        }

        int keyFrameInterval = vc.getKeyframeRate();

        if (dynamic != null) {
            mDynamicSetting = new Stack<String>();
            String[] changes = dynamic.split(":");
            if (dynamic.contains("ltrm")) {
                mUseLTR = true;
            }
            for (int i = changes.length-1; i >= 0; i--) {
                String data = changes[i];
                mDynamicSetting.push(data);
            }

            getNextLimit(0);
        }

        MediaFormat format = null;
        try {
           try {
                if (vc.getVideoEncoderIdentifier().length() > 6) { //Should not be any short names
                    Log.d(TAG, "Create codec by name: " + vc.getVideoEncoderIdentifier());
                    mCodec = MediaCodec.createByCodecName(vc.getVideoEncoderIdentifier());
                }

            }
            catch (Exception ex) {
                Log.e(TAG, "Failed to create Codec by name."+ex.getMessage());
            }

            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

            MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
            String id = vc.getVideoEncoderIdentifier();
            for (MediaCodecInfo info: codecInfos) {
                if (info.isEncoder() && info.getName().contains(id)) {
                    id = info.getSupportedTypes()[0];
                    //Update format with this mime
                    vc.setVideoEncoderIdentifier(id);
                }
            }
            if (mCodec == null) {
                Log.e(TAG,"Try by type: "+id);
                mCodec = MediaCodec.createEncoderByType(id);
            }
            format = vc.createEncoderMediaFormat(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            if (mUseLTR) {
                format.setInteger(MEDIA_KEY_LTR_NUM_FRAMES, vc.getLTRCount());
            }
            if (vc.getHierStructLayers() > 0) {
                format.setInteger(MEDIA_KEY_HIER_STRUCT_LAYERS, vc.getHierStructLayers());
            }

            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IOException iox) {
            Log.e(TAG, "Failed to create codec: "+iox.getMessage());
            return false;
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: "+cex.getMessage());
            return false;
        }
        try {
            mCodec.start();
        }
        catch(Exception ex){
            Log.e(TAG, "Start failed: "+ex.getMessage());
            return false;
        }

        MediaFormat inputFormat = mCodec.getInputFormat();
        int stride = inputFormat.containsKey(MediaFormat.KEY_STRIDE)
                ? inputFormat.getInteger(MediaFormat.KEY_STRIDE)
                : inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int vstride = inputFormat.containsKey(MediaFormat.KEY_SLICE_HEIGHT)
                ? inputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT)
                : inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

        int numBytesSubmitted = 0;
        int numBytesDequeued = 0;
        int inFramesCount = 0;
        int outFramesCount = 0;
        long lastOutputTimeUs = 0;
        long start = System.currentTimeMillis();
        int errorcount = 0;

        mFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        float mReferenceFrameRate = vc.getmReferenceFPS();
        mKeepInterval = mReferenceFrameRate / (float)mFrameRate;
        int mPts = 132;
        calculateFrameTiming();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaMuxer muxer = null;
        boolean isVP8 = mCodec.getCodecInfo().getName().toLowerCase().contains("vp8");
        if (isVP8) {
            MediaFormat oformat = mCodec.getOutputFormat();
            //There seems to be a bug so that this key is no set (but used).
            oformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
            Log.d(TAG, "Call create muxer");
            muxer = createMuxer(mCodec, oformat);
        }

        while (true) {
            int index;
            if (mFramesAdded < totalFrames) { //Count not decoded frames but frames added to the output
                try {
                    index = mCodec.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);

                    if (index >= 0) {
                        int size = -1;
                        boolean eos = (inFramesCount == totalFrames - 1);
                        if (isVP8 && inFramesCount > 0 && keyFrameInterval > 0 && inFramesCount % (mFrameRate * keyFrameInterval) == 0) {
                            Bundle params = new Bundle();
                            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                            mCodec.setParameters(params);
                        }
                        ByteBuffer buffer = mCodec.getInputBuffer(index);
                        while (size == -1) {
                            size = queueInputBufferEncoder(
                                    mCodec, buffer, index, inFramesCount,
                                    eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0, (int) (stride * vstride * 1.5));

                            inFramesCount++;
                        }
                        numBytesSubmitted += size;
                        if (size == 0) break;
                    }
                }catch (Exception ex) {
                    ex.printStackTrace();
                }

                index = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    //Just ignore
                } else if (index >= 0) {
                    long nowUs = (System.nanoTime() + 500) / 1000;
                    ByteBuffer data = mCodec.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        MediaFormat oformat = mCodec.getOutputFormat();
                        //There seems to be a bug so that this key is no set (but used).
                        oformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
                        oformat.setInteger(MediaFormat.KEY_FRAME_RATE, format.getInteger(MediaFormat.KEY_FRAME_RATE));
                        muxer = createMuxer(mCodec, oformat);
                        mCodec.releaseOutputBuffer(index, false /* render */);
                    } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                        break;
                    } else if (muxer != null){
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            Log.d(TAG, "Out buffer has KEY_FRAME @ " +outFramesCount );
                        }
                        numBytesDequeued += info.size;
                        ++outFramesCount;

                        muxer.writeSampleData(0, data, info);
                        Log.d(TAG, "Wrote to muxer, release: " + index);
                        mCodec.releaseOutputBuffer(index, false /* render */);
                    }
                }
            } else {
                Log.d(TAG, "Done transcoding");
                break;
            }

        }
        if (mCodec != null) {
            mCodec.stop();
            mCodec.release();
        }
        if (muxer != null) {
            muxer.stop();
            muxer.release();
            Log.d(TAG, "muxer released ");
        }
        nativeCloseFile();
        return true;
    }


    private native int nativeFillBuffer(ByteBuffer buffer, int size);


    /**
     * Fills input buffer for encoder from YUV buffers.
     * @return size of enqueued data.
     */
    private int queueInputBufferEncoder(
            MediaCodec codec, ByteBuffer buffer, int index, int frameCount, int flags, int size) {
        buffer.clear();

        int read = nativeFillBuffer(buffer, size);
        int currentFrameNbr = (int)((float)(frameCount % mFrameRate) / mKeepInterval);
        int nextFrameNbr = (int)((float)((frameCount + 1) % mFrameRate) / mKeepInterval);
        if (currentFrameNbr == nextFrameNbr) {
            read = -1; //Skip this and read again
            mSkipped++;
        }
        if (read > 0) {
            if (mNextLimit != -1 && frameCount >= mNextLimit) {
                getNextLimit(frameCount);
            }
            mFramesAdded++;
            long ptsUsec = computePresentationTime(mFramesAdded);
            codec.queueInputBuffer(index, 0 /* offset */, read, ptsUsec /* timeUs */, flags);
        }
        return read;
    }

    private void getNextLimit(int currentFrame) {
        Bundle params = null;

        while (mNextLimit <= currentFrame && mNextEvent != null) {
            if (params == null) {
                params = new Bundle();
            }
            String[] data = mNextEvent.split("-");
            if (data != null) {
                String command = data[0];
                if (command.equals("fps") && data.length >= 2) {
                    int tmpFps = Integer.parseInt(data[2]);
                    Log.d(TAG, "Set fps to " + tmpFps);
                    mKeepInterval = (float)mFrameRate / (float)tmpFps;
                } else if (command.equals("bit") && data.length >= 2) {
                    int bitrate = Integer.parseInt(data[2]);
                    Log.d(TAG, "Set bitrate to " + bitrate);
                    params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate * 1000); //In kbps
                } else if (command.equals("ltrm") && data.length >= 2) {
                    int ltr = Integer.parseInt(data[2]);
                    Log.d(TAG, "Mark ltr frame " + currentFrame + ", @ " + mFrameTime+ " mark as: "+ltr);
                    params.putInt(MEDIA_KEY_LTR_MARK_FRAME, ltr);

                } else if (command.equals("ltru") && data.length >= 2) {
                    int mLTRRef = Integer.parseInt(data[2]);
                    Log.d(TAG, "Use ltr frame id " + mLTRRef +" @ "+ currentFrame);
                    params.putInt(MEDIA_KEY_LTR_USE_FRAME, mLTRRef);
                } else if (command.equals("key")) {
                    Log.d(TAG, "Request new key frame at " + currentFrame);
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1); //Always ref a key frame?
                }
            }

            if (!mDynamicSetting.empty()) {
                Object obj = mDynamicSetting.pop();
                if (obj != null) {
                    data = ((String) obj).split("-");
                    if (data != null && data.length >= 2) {
                        String command = data[0];
                        mNextLimit = Integer.parseInt(data[1]);
                    }
                    mNextEvent = obj.toString();
                }
            } else {
                mNextEvent = null;
                mNextLimit = -1;
            }
        }

        if (params != null && mCodec != null) {
            mCodec.setParameters(params);
        }

    }

    /**
     * Generates the presentation time for frameIndex, in microseconds.
     */
    private long computePresentationTime(int frameIndex) {
        return 132 + frameIndex * 1000000L / mFrameRate;
    }

    private void calculateFrameTiming() {
        mFrameTime = 1000000L / mFrameRate;
    }


    private MediaMuxer createMuxer(MediaCodec encoder, MediaFormat format) {
        MediaMuxer muxer = null;
        Log.d(TAG, "Bitrate mode: "+(format.containsKey(MediaFormat.KEY_BITRATE_MODE)? format.getInteger(MediaFormat.KEY_BITRATE_MODE): 0));
        String filename = String.format("/sdcard/%s_%dfps_%dx%d_%dbps_iint%d.mp4",
            encoder.getCodecInfo().getName().toLowerCase(),
                (format.containsKey(MediaFormat.KEY_FRAME_RATE)? format.getInteger(MediaFormat.KEY_FRAME_RATE): 0),
                (format.containsKey(MediaFormat.KEY_WIDTH)? format.getInteger(MediaFormat.KEY_WIDTH): 0),
                (format.containsKey(MediaFormat.KEY_HEIGHT)? format.getInteger(MediaFormat.KEY_HEIGHT): 0),
                (format.containsKey(MediaFormat.KEY_BIT_RATE)? format.getInteger(MediaFormat.KEY_BIT_RATE): 0),
                (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)? format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL): 0));
        int type = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        if (encoder.getCodecInfo().getName().toLowerCase().contains("vp")) {
            filename = String.format("/sdcard/%s_%dfps_%dx%d_%dbps_iint%d.webm",
            encoder.getCodecInfo().getName().toLowerCase(),
                    (format.containsKey(MediaFormat.KEY_FRAME_RATE)? format.getInteger(MediaFormat.KEY_FRAME_RATE): 0),
                    (format.containsKey(MediaFormat.KEY_WIDTH)? format.getInteger(MediaFormat.KEY_WIDTH): 0),
                    (format.containsKey(MediaFormat.KEY_HEIGHT)? format.getInteger(MediaFormat.KEY_HEIGHT): 0),
                    (format.containsKey(MediaFormat.KEY_BIT_RATE)? format.getInteger(MediaFormat.KEY_BIT_RATE): 0),
                    (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)? format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL): 0));
            type = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
        }
        try {
            Log.d(TAG, "Create muxer with type "+type+" and filename: "+filename);
            muxer = new MediaMuxer(filename, type);
        } catch (IOException e) {
            Log.d(TAG, "FAILED Create muxer with type "+type+" and filename: "+filename);
            e.printStackTrace();
        }

        muxer.addTrack(format);
        Log.d(TAG, "Start muxer");
        muxer.start();

        return muxer;
    }

    private native boolean nativeOpenFile(String filename);

    private native void nativeCloseFile();
}
