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
        Log.d(TAG, "**** Surface Transcode - " + vc.getDescription() + " ***");
        mRuntimeParams = vc.getRuntimeParameters();
        mSkipped = 0;
        mFramesAdded = 0;

        mWriteFile = writeFile;
        mStats = new Statistics("surface encoder", vc);
        mStats.start();
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
            mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            e.printStackTrace();
        }

        int keyFrameInterval = vc.getKeyframeRate();
        MediaFormat format;
        try {
            String codecName = getCodecName(vc);
            mStats.setCodec(codecName);
            Log.d(TAG, "Create codec by name: " + codecName);
            mCodec = MediaCodec.createByCodecName(codecName);

            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }
            //Use same color settings as the input
            Log.d(TAG, "Check decoder settings");
            if (inputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                vc.addConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_RANGE, inputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE)));
                Log.d(TAG, "Color range set: " + inputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE));
            }
            if (inputFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                vc.addConfigureSetting(new ConfigureParam(MediaFormat.KEY_COLOR_TRANSFER, inputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)));
                Log.d(TAG, "Color transfer set: " + inputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
            }
            if (inputFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                vc.addConfigureSetting( new ConfigureParam(MediaFormat.KEY_COLOR_STANDARD, inputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD)));
                Log.d(TAG, "Color standard set: " + inputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD));
            }

            format = vc.createEncoderMediaFormat(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            Log.d(TAG, "Set color format");
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mInputSurfaceReference = new AtomicReference<>();
            mOutputSurface = new OutputSurface(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            setConfigureParams(vc, format);
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            checkConfigureParams(vc, mCodec.getInputFormat());
            mInputSurfaceReference.set(mCodec.createInputSurface());
            mInputSurface = new InputSurface(mInputSurfaceReference.get());
            mInputSurface.makeCurrent();

            mOutputSurface = new OutputSurface();
            mDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
            mDecoder.start();
            mStats.setDecoderMediaFormat(mDecoder.getInputFormat());
            mStats.setEncoderMediaFormat(mCodec.getInputFormat());
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
        calculateFrameTiming();
        Log.e(TAG, "ref " + mReferenceFrameRate+", fps " + mFrameRate +", keepint. =  " + mKeepInterval + ", mFrameTime = " + mFrameTime);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".vp");
        boolean isQCom = mCodec.getCodecInfo().getName().toLowerCase(Locale.US).contains(".qcom");

        Log.d(TAG, "Create muxer");
        mMuxer = createMuxer(mCodec, format, true);

        long totalTime = 0;
        long last_pts = 0;
        int current_loop = 1;
        while (loop + 1 >= current_loop) {
            int index;
            if (mFramesAdded % 100 == 0) {
                Log.d(TAG, "Frames: " + mFramesAdded + " - inframes: " + inFramesCount + ", current loop: " + current_loop);
            }
            try {
                index = mDecoder.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
                if (index >= 0) {
                    if (VP8_IS_BROKEN && isVP && isQCom && inFramesCount > 0 &&
                            keyFrameInterval > 0 && inFramesCount % (mFrameRate * keyFrameInterval) == 0) {
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        mCodec.setParameters(params);
                    }

                    ByteBuffer buffer = mDecoder.getInputBuffer(index);
                    int size = mExtractor.readSampleData(buffer, 0);
                    if (size > 0) {
                        mStats.startDecodingFrame(mExtractor.getSampleTime(), mExtractor.getSampleSize(), mExtractor.getSampleFlags());
                        mDecoder.queueInputBuffer(index, 0, size, mExtractor.getSampleTime(), mExtractor.getSampleFlags());
                    }
                    boolean eof = !mExtractor.advance();
                    if (eof) {
                        mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        current_loop++;

                        if (current_loop > loop) {
                            Log.d(TAG, "End of stream!");
                            try {
                                mStats.startDecodingFrame(mExtractor.getSampleTime(), mExtractor.getSampleSize(), mExtractor.getSampleFlags());
                                mDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            } catch (MediaCodec.CodecException cex) {
                                Log.d(TAG, "End of stream: "+ cex.getMessage());
                            }
                            break;
                        }
                        Log.d(TAG, "*** Loop ended starting " + current_loop + " ***");
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            index = mDecoder.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Just ignore
                continue;
            } else if (index >= 0) {
                if (info.size > 0) {
                    long pts =  computePresentationTime(inFramesCount); //info.presentationTimeUs;
                    mStats.stopDecodingFrame(pts);
                    setRuntimeParameters(inFramesCount);
                    ByteBuffer data = mDecoder.getOutputBuffer(index);
                    int currentFrameNbr = (int) ((float) (inFramesCount) / mKeepInterval);
                    int nextFrameNbr = (int) ((float) ((inFramesCount + 1)) / mKeepInterval);
                    if (currentFrameNbr == nextFrameNbr || mDropNext) {
                        mDecoder.releaseOutputBuffer(index, false); //Skip this and read again
                        mDropNext = false;
                        mSkipped++;
                    } else {
                        mDecoder.releaseOutputBuffer(index, true);
                        mOutputSurface.awaitNewImage();
                        mOutputSurface.drawImage();

                        if (pts > last_pts) {
                            last_pts = pts;
                        } else {
                            // Loop
                            pts = (current_loop - 1) * last_pts + pts;
                        }
                        //egl have time in ns
                        mInputSurface.setPresentationTime(pts * 1000);
                        mInputSurface.swapBuffers();
                        mStats.startEncodingFrame(pts);
                    }

                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    ///Done
                    mCodec.signalEndOfInputStream();
                }
                inFramesCount++;
            }

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
                    break;
                } else {
                    ++outFramesCount;
                    mFramesAdded += 1;
                    totalTime += info.presentationTimeUs;
                    if (mMuxer != null)
                        mMuxer.writeSampleData(mVideoTrack, data, info);
                    mCodec.releaseOutputBuffer(index, false /* render */);
                }
            }
        }

        mStats.stop();
        Log.d(TAG, "Done transcoding");
        try {
            if (mCodec != null) {
                mCodec.stop();
                mCodec.release();
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

        return "";
    }


}
