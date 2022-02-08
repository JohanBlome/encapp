package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.cts.InputSurface;
import android.media.cts.OutputSurface;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import com.facebook.encapp.utils.ConfigureParam;
import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.TestParams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class SurfaceTranscoder extends BufferEncoder {
    MediaExtractor mExtractor;
    MediaCodec mDecoder;
    AtomicReference<Surface> mInputSurfaceReference;
    InputSurface mInputSurface;
    OutputSurface mOutputSurface;

    public String encode(TestParams vc,
                         boolean writeFile) {
        boolean noEncoding = vc.noEncoding();
        if (noEncoding) {
            Log.d(TAG, "**** Surface Decode, no encode ***");
        } else {
            Log.d(TAG, "**** Surface Transcode - " + vc.getDescription() + " ***");
        }
        mRuntimeParams = vc.getRuntimeParameters();
        mSkipped = 0;
        mFramesAdded = 0;

        mWriteFile = writeFile;
        mStats = new Statistics("surface encoder", vc);
        int loop = vc.getLoopCount();

        mExtractor = new MediaExtractor();
        MediaFormat inputFormat = null;
        try {
            mExtractor.setDataSource(vc.getInputfile());
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
            if (vc.getDecoder().length() > 0) {
                Log.d(TAG, "Create decoder by name: " + vc.getDecoder());
                mDecoder = MediaCodec.createByCodecName(vc.getDecoder());
            } else {
                Log.d(TAG, "Create decoder by type: " + inputFormat.getString(MediaFormat.KEY_MIME));
                mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            }
        } catch (IOException e) {
            mExtractor.release();
            e.printStackTrace();
            return "Failed to create decoder";
        }
        boolean isVP = false;
        boolean isQCom = false;
        int keyFrameInterval = vc.getKeyframeRate();

        MediaFormat format;
        try {
            if (!noEncoding) {
                String codecName = getCodecName(vc);
                mStats.setCodec(codecName);
                Log.d(TAG, "Create encoder by name: " + codecName);
                mCodec = MediaCodec.createByCodecName(codecName);
            } else {
                mStats.setCodec(Statistics.NA);
            }
            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }
            //Use same color settings as the input
            Log.d(TAG, "Check decoder settings");
            if (inputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                vc.addEncoderConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_RANGE, inputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE)));
                Log.d(TAG, "Color range set: " + inputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE));
            }
            if (inputFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                vc.addEncoderConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_TRANSFER, inputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)));
                Log.d(TAG, "Color transfer set: " + inputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            }
            if (inputFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                vc.addEncoderConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_STANDARD, inputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD)));
                Log.d(TAG, "Color standard set: " + inputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD));
            }
            Log.d(TAG, "Configure decoder with extra settings");
            setConfigureParams(vc, vc.getDecoderConfigure(), inputFormat);

            format = vc.createEncoderMediaFormat(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            Log.d(TAG, "Set color format");
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);


            mOutputSurface = new OutputSurface(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            if (!noEncoding) {
                mInputSurfaceReference = new AtomicReference<>();
                setConfigureParams(vc, vc.getEncoderConfigure(), format);
                mCodec.configure(
                        format,
                        null /* surface */,
                        null /* crypto */,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                checkConfigureParams(vc, mCodec.getInputFormat());
                mInputSurfaceReference.set(mCodec.createInputSurface());
                mInputSurface = new InputSurface(mInputSurfaceReference.get());
                mInputSurface.makeCurrent();
            }

            mOutputSurface = new OutputSurface();
            checkConfig(inputFormat);
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
        mFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        float referenceFrameRate = vc.getmReferenceFPS();
        mKeepInterval = referenceFrameRate / (float) mFrameRate;
        calculateFrameTiming();

        if (!noEncoding) {
            Log.d(TAG, "Create muxer");
            mMuxer = createMuxer(mCodec, format, true);
            isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
            isQCom = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".qcom");
        }

        int inFramesCount = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        double currentTime = 0;
        long pts_offset = 0;
        long last_pts = 0;
        int current_loop = 1;
        boolean done = false;
        mStats.start();
        while (!done) {
            int index;
            if ((mFramesAdded % 100 == 0 && !noEncoding ) || (inFramesCount % 100 == 0 && noEncoding )) {
                Log.d(TAG, "SurfaceTranscoder, Frames: " + mFramesAdded + " - inframes: " + inFramesCount +
                        ", current loop: " + current_loop + " / "+loop + ", current time: " + currentTime + " sec");
            }
            try {
                int flags = 0;
                if (doneReading(vc, current_loop, currentTime, inFramesCount)) {
                    flags += MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    done = true;
                }
                index = mDecoder.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
                if (index >= 0 && !done) {
                    if (VP8_IS_BROKEN && isVP && isQCom && inFramesCount > 0 &&
                            keyFrameInterval > 0 && inFramesCount % (mFrameRate * keyFrameInterval) == 0) {
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        mCodec.setParameters(params);
                    }

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
                        setRuntimeParameters(inFramesCount, mDecoder, mDecoderRuntimeParams);
                        long pts = mExtractor.getSampleTime()  + pts_offset;
                        last_pts = pts;

                        mStats.startDecodingFrame(pts, mExtractor.getSampleSize(), flags);
                        inFramesCount++;
                        mDecoder.queueInputBuffer(index, 0, size, pts, flags);

                        boolean eof = !mExtractor.advance();
                        if (eof) {
                            mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            current_loop++;
                            pts_offset = last_pts + mFrameTime;
                            Log.d(TAG, "*** Loop ended starting " + current_loop + " ***");
                        }
                        if (doneReading(vc, current_loop, currentTime, inFramesCount)) {
                            done = true;
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            index = mDecoder.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Just ignore
                Log.d(TAG, "Try again later");
                continue;
            } else if (index >= 0) {
                if (info.size > 0) {
                    long pts = info.presentationTimeUs;
                    mStats.stopDecodingFrame(pts);
                    setRuntimeParameters(inFramesCount, mCodec, mRuntimeParams);
                    ByteBuffer data = mDecoder.getOutputBuffer(index);
                    int currentFrameNbr = (int) ((float) (inFramesCount) / mKeepInterval);
                    int nextFrameNbr = (int) ((float) ((inFramesCount + 1)) / mKeepInterval);
                    if (currentFrameNbr == nextFrameNbr || mDropNext || noEncoding) {
                        mDecoder.releaseOutputBuffer(index, false); //Skip this and read again
                        mDropNext = false;
                        mSkipped++;
                    } else {
                        mDecoder.releaseOutputBuffer(index, true);
                        mOutputSurface.awaitNewImage();
                        mOutputSurface.drawImage();

                        //egl have time in ns
                        currentTime = pts/1000000.0;
                        mInputSurface.setPresentationTime(pts * 1000);
                        mInputSurface.swapBuffers();
                        if (mRealtime) {
                            sleepUntilNextFrame();
                        }
                        mStats.startEncodingFrame(pts, inFramesCount);
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
                index = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    //Just ignore
                } else if (index >= 0) {
                    mStats.stopEncodingFrame(info.presentationTimeUs, info.size,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    ByteBuffer data = mCodec.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        MediaFormat oformat = mCodec.getOutputFormat();
                        Log.e(TAG, "BUFFER_FLAG_CODEC_CONFIG: " + oformat);
                        checkConfig(oformat);

                        if (mWriteFile) {
                            mVideoTrack = mMuxer.addTrack(oformat);
                            Log.d(TAG, "Start muxer");
                            mMuxer.start();
                        }
                        mCodec.releaseOutputBuffer(index, false /* render */);
                    } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "End of stream");
                        done = true;
                        break;
                    } else {
                        mFramesAdded += 1;

                        if (mMuxer != null) {
                            mMuxer.writeSampleData(mVideoTrack, data, info);
                        }
                        mCodec.releaseOutputBuffer(index, false /* render */);

                    }
                }
            }
        }

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
}
