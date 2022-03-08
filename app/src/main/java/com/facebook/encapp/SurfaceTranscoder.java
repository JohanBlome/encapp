package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.cts.InputSurface;
import android.media.cts.OutputSurface;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.facebook.encapp.proto.Configure;
import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.DecoderConfigure;
import com.facebook.encapp.proto.DecoderRuntime;
import com.facebook.encapp.proto.Test;
import com.facebook.encapp.utils.FileReader;
import com.facebook.encapp.utils.SizeUtils;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestDefinitionHelper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class SurfaceTranscoder extends BufferEncoder {
    MediaExtractor mExtractor;
    MediaCodec mDecoder;
    AtomicReference<Surface> mInputSurfaceReference;
    InputSurface mInputSurface;
    OutputSurface mOutputSurface;
    DecoderRuntime mDecoderRuntimeParams;

    public String start(Test test) {
        boolean noEncoding = false;
        if (test.getConfigure().hasEncode()) {
            noEncoding = !test.getConfigure().getEncode();
        }
        if (noEncoding) {
            Log.d(TAG, "**** Surface Decode, no encode ***");
        } else {
            Log.d(TAG, "**** Surface Transcode - " + test.getCommon().getDescription() + " ***");
        }

        if (test.hasRuntime())
            mRuntimeParams = test.getRuntime();
        if (test.hasDecoderRuntime() )
            mDecoderRuntimeParams = test.getDecoderRuntime();

        if (test.getInput().hasRealtime())
            mRealtime = test.getInput().getRealtime();
        mFrameRate = test.getConfigure().getFramerate();
        Log.d(TAG, "Realtime = " + mRealtime);
        mWriteFile = (test.getConfigure().hasEncode())?test.getConfigure().getEncode():true;

        mYuvReader = new FileReader();
        if (!mYuvReader.openFile(test.getInput().getFilepath())) {
            return "\nCould not open file";
        }


        mExtractor = new MediaExtractor();
        MediaFormat inputFormat = null;
        try {
            mExtractor.setDataSource(test.getInput().getFilepath());
            int trackNum = 0;
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
            Log.d(TAG, "Extractor input format");
            checkMediaFormat(inputFormat);
            // Allow explicit decoder only for non encoding tests (!?)
         /*   if (noEncoding) {
                //TODO: throw error on failed lookup
                //TODO: fix decoder lookup
                test = setCodecNameAndIdentifier(test);
                Log.d(TAG, "Create codec by name: " + test.getConfigure().getCodec());
                mDecoder = MediaCodec.createByCodecName(test.getDecoderConfigure().getCodec());

            } else {*/
                Log.d(TAG, "Create decoder by type: " + inputFormat.getString(MediaFormat.KEY_MIME));
                mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            //}
        } catch (IOException e) {
            mExtractor.release();
            e.printStackTrace();
            return "Failed to create decoder";
        }

        test = TestDefinitionHelper.updateInputSettings(test, inputFormat);
        test = TestDefinitionHelper.checkAnUpdateBasicSettings(test);
        mStats = new Statistics("surface encoder", test);

        Size res = SizeUtils.parseXString(test.getInput().getResolution());
        int width = res.getWidth();
        int height = res.getHeight();
        mRefFramesizeInBytes = (int) (width * height * 1.5);

        mReferenceFrameRate = test.getInput().getFramerate();
        mRefFrameTime = calculateFrameTiming(mReferenceFrameRate);


        if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            mReferenceFrameRate = (float)(inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
        }
        mKeepInterval = mReferenceFrameRate / (float) mFrameRate;

        MediaFormat format; 
        try {
            if (!noEncoding) {
                if (test.getConfigure().getMime().length() == 0) {
                    Log.d(TAG, "codec id: " + test.getConfigure().getCodec());
                    //TODO: throw error on failed lookup
                    test = setCodecNameAndIdentifier(test);
                }
                mStats.setCodec(test.getConfigure().getCodec());
                Log.d(TAG, "Create encoder by name: " + test.getConfigure().getCodec());
                mCodec = MediaCodec.createByCodecName(test.getConfigure().getCodec());
            } else {
                mStats.setCodec(Statistics.NA);
            }
            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }
            //Use same color settings as the input
            Log.d(TAG, "Check decoder settings");
            format = TestDefinitionHelper.buildMediaFormat(test);
            Log.d(TAG, "Created encoder format");
            checkMediaFormat(format);
            Log.d(TAG, "Set color format");
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            Size encodeResolution = Size.parseSize(test.getConfigure().getResolution());
            mOutputSurface = new OutputSurface(encodeResolution.getWidth(), encodeResolution.getHeight(), true);
            if (!noEncoding) {

                setConfigureParams(test, format);
                mInputSurfaceReference = new AtomicReference<>();
                mCodec.configure(
                        format,
                        null /* surface */,
                        null /* crypto */,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                Log.d(TAG, "Check input format after encoder is configured");
                checkMediaFormat(mCodec.getInputFormat());
                mInputSurfaceReference.set(mCodec.createInputSurface());
                mInputSurface = new InputSurface(mInputSurfaceReference.get());
                mInputSurface.makeCurrent();
            }

            mOutputSurface = new OutputSurface();
            Log.d(TAG, "Check input format before config decoder");
            checkMediaFormat(inputFormat);
            setDecoderConfigureParams(test, inputFormat);
            mDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
            mDecoder.start();
            mStats.setDecoderName(mDecoder.getName());
            mStats.setDecoderMediaFormat(mDecoder.getInputFormat());
            if (!noEncoding) {
                mStats.setEncoderMediaFormat(mCodec.getInputFormat());
            }
        } catch (IOException iox) {
            Log.e(TAG, "Failed to create codec: " + iox.getMessage());
            return "Failed to create codec";
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: " + cex.getMessage());
            return "Failed to create codec";
        }
        if (!noEncoding) {
            try {
                mCodec.start();
            } catch (Exception ex) {
                Log.e(TAG, "Start failed: " + ex.getMessage());
                return "Start encoding failed";
            }
        }

        if (!noEncoding) {
            Log.d(TAG, "Create muxer");
            mMuxer = createMuxer(mCodec, format, true);

            // This is needed.
            boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
            if (isVP) {
                mVideoTrack = mMuxer.addTrack(mCodec.getOutputFormat());
                mMuxer.start();
            }
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long pts_offset = 0;
        long last_pts = 0;
        int current_loop = 1;
        boolean done = false;

        mStats.start();
        while (!done) {
            int index;
            if ((mFramesAdded % 100 == 0 && !noEncoding ) || (mInFramesCount % 100 == 0 && noEncoding )) {
                Log.d(TAG, "SurfaceTranscoder, Frames: " + mFramesAdded + " - inframes: " + mInFramesCount +
                        ", current loop: " + current_loop  + ", current time: " + mCurrentTime + " sec");
            }
            try {
                int flags = 0;
                if (doneReading(test, mInFramesCount, false)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    done = true;
                }
                index = mDecoder.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US);
                if (index >= 0 && !done) {
                    int size = -1;
                    while (size < 0) {
                        ByteBuffer buffer = mDecoder.getInputBuffer(index);
                        size = mExtractor.readSampleData(buffer, 0);
                        flags = mExtractor.getSampleFlags();

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ) {
                            Log.d(TAG, "Decoder eos!!!");
                            done = true;
                        }

                        if (done) {
                            flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        }

                        setDecoderRuntimeParameters(test, mInFramesCount);
                        long pts = mExtractor.getSampleTime()  + pts_offset;
                        last_pts = pts;
                        mInFramesCount++;
                        if (mRealtime) {
                            sleepUntilNextFrame(mInFramesCount);
                        }

                        mStats.startDecodingFrame(pts, size, flags);
                        if (size > 0) {
                            mDecoder.queueInputBuffer(index, 0, size, pts, flags);
                        }
                        boolean eof = !mExtractor.advance();
                        if (eof) {
                            mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            current_loop++;
                            pts_offset = last_pts + mFrameTime;
                            Log.d(TAG, "*** Loop ended starting " + current_loop + " ***");

                            if (doneReading(test, mInFramesCount, true)) {
                                done = true;
                            }
                        }
                        mCurrentTime = mExtractor.getSampleTime()/1000000;
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            index = mDecoder.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Just ignore
                Log.d(TAG, "Try again later");
                continue;
            } else if (index >= 0) {
                if (info.size > 0) {
                    long pts = info.presentationTimeUs;
                    mStats.stopDecodingFrame(pts);

                    ByteBuffer data = mDecoder.getOutputBuffer(index);
                    int currentFrameNbr = (int) ((float) (mInFramesCount) / mKeepInterval);
                    int nextFrameNbr = (int) ((float) ((mInFramesCount + 1)) / mKeepInterval);
                    setRuntimeParameters(mInFramesCount);
                    mDropNext = dropFrame(mInFramesCount);
                    updateDynamicFramerate(mInFramesCount);

                    if (currentFrameNbr == nextFrameNbr || mDropNext || noEncoding) {
                        mDecoder.releaseOutputBuffer(index, false); //Skip this and read again
                        mDropNext = false;
                        mSkipped++;
                    } else {
                        mDecoder.releaseOutputBuffer(index, true);
                        mOutputSurface.awaitNewImage();
                        mOutputSurface.drawImage();

                        //egl have time in ns
                        mInputSurface.setPresentationTime(pts * 1000);
                        mInputSurface.swapBuffers();
                        mStats.startEncodingFrame(pts, mInFramesCount);
                    }

                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || done) {
                    ///Done
                    if (mCodec != null) {
                        Log.d(TAG, "Signal eos");
                        mCodec.signalEndOfInputStream();
                    }
                }
            }

            if (!noEncoding) {
                index = mCodec.dequeueOutputBuffer(info, 1);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    //Just ignore
                } else if (index >= 0) {
                    mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    ByteBuffer data = mCodec.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        MediaFormat oformat = mCodec.getOutputFormat();
                        Log.e(TAG, "BUFFER_FLAG_CODEC_CONFIG, output format: " + oformat);
                        checkMediaFormat(oformat);
                        mStats.setEncoderMediaFormat(oformat);

                        if (mWriteFile) {
                            mVideoTrack = mMuxer.addTrack(oformat);
                            Log.d(TAG, "Start muxer");
                            mMuxer.start();
                        }
                        mCodec.releaseOutputBuffer(index, false);
                    } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "End of stream");
                        done = true;
                        break;
                    } else {
                        mFramesAdded += 1;

                        if (mMuxer != null) {
                            mMuxer.writeSampleData(mVideoTrack, data, info);
                        }
                        mCodec.releaseOutputBuffer(index, false);

                    }
                }
            }
        }

        mStats.stop();


        mStats.stop();
        Log.d(TAG, "Done transcoding");
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
        try {
            if (mMuxer != null) {
                mMuxer.stop();
                mMuxer.release();
            }
        } catch (IllegalStateException iex) {
            Log.e(TAG, "Failed to shut down:" + iex.getLocalizedMessage());
        }
        if (mOutputSurface != null)
            mOutputSurface.release();
        if (mInputSurface != null)
            mInputSurface.release();
        if (mExtractor != null)
            mExtractor.release();
        return "";
    }


    public void setDecoderConfigureParams(Test test, MediaFormat format) {
        DecoderConfigure config = test.getDecoderConfigure();


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

    public void setDecoderRuntimeParameters(Test test, int frame) {;
        // go through all runtime settings and see which are due
        if (mDecoderRuntimeParams == null) return;
        Bundle bundle = new Bundle();

        for (DecoderRuntime.Parameter param: mDecoderRuntimeParams.getParameterList()) {
            if (param.getFramenum() == frame) {
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

        if (bundle.keySet().size() > 0 && mDecoder != null) {
            mDecoder.setParameters(bundle);
        }
    }
}
