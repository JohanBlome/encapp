package com.facebook.encapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import android.media.cts.InputSurface;
import android.media.cts.OutputSurface;

import com.facebook.encapp.utils.Statistics;
import com.facebook.encapp.utils.VideoConstraints;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Stack;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

public class SurfaceTranscoder extends Transcoder{
    MediaExtractor mExtractor;
    MediaCodec mDecoder;
    AtomicReference<Surface> mInputSurfaceReference;
    InputSurface mInputSurface;
    OutputSurface mOutputSurface;

    public String transcode(VideoConstraints vc,
                            String filename,
                            Size refFrameSize,
                            String dynamic,
                            int loop,
                            boolean writeFile) {
        Log.d(TAG, "**** SURFACE TRANSCODE ***");
        int keyFrameInterval = vc.getKeyframeRate();
        mNextLimit = -1;
        mSkipped = 0;
        mFramesAdded = 0;
        mRefFramesizeInBytes = (int)(refFrameSize.getWidth() * refFrameSize.getHeight() * 1.5);
        mWriteFile = writeFile;
        mStats = new Statistics("surface encoder", vc);

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
        mExtractor = new MediaExtractor();
        MediaFormat inputFormat = null;
        try {
            mExtractor.setDataSource(filename);
            int trackNum = 0;
	    int tracks = mExtractor.getTrackCount();
            for (int track = 0; track < tracks; track++) {
                inputFormat = mExtractor.getTrackFormat(track);
                if (inputFormat.containsKey(MediaFormat.KEY_MIME) &&
                        inputFormat.getString(MediaFormat.KEY_MIME).toLowerCase().contains("video")) {
                    Log.d(TAG, "Found video track at " + track + " " + inputFormat.getString(MediaFormat.KEY_MIME));
                    trackNum = track;
                }
            }
            mExtractor.selectTrack(trackNum);
            inputFormat = mExtractor.getTrackFormat(trackNum);
            mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            e.printStackTrace();
        }

        MediaFormat format;
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

            MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
            String id = vc.getVideoEncoderIdentifier();
            String codecName = "";
            Vector<MediaCodecInfo> matching = getMediaCodecInfos(codecInfos, id);

            if (matching.size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("\nAmbigous codecs \n" + matching.size() + " codecs matching.\n");
                for(MediaCodecInfo info: matching) {
                    sb.append(info.getName() + "\n");
                }
                return sb.toString();
            } else if (matching.size() == 0) {
                return "\nNo matching codecs to : " + id;
            } else {
                vc.setVideoEncoderIdentifier(matching.elementAt(0).getSupportedTypes()[0]);
                codecName = matching.elementAt(0).getName();
            }
    
            mStats.setCodec(codecName);
            Log.d(TAG, "Create codec by name: " + codecName);
            mCodec = MediaCodec.createByCodecName(codecName);

            Log.d(TAG, "Done");
            if (inputFormat == null) {
                Log.e(TAG, "no input format");
                return "no input format";
            }
            //Use same color settings as the input
            Log.d(TAG, "Check decoder settings");
            if(inputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                vc.setColorRange(inputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE));
                Log.d(TAG, "Color range set: " + vc.getColorRange());
            }
            if(inputFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                vc.setColorRange(inputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
                Log.d(TAG, "Color transfer set: " + vc.getColorRange());
            }
            if(inputFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                vc.setColorRange(inputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD));
                Log.d(TAG, "Color standard set: " + vc.getColorRange());
            }
            // We explicitly set the color format
            format = vc.createEncoderMediaFormat(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            if (mUseLTR) {
                format.setInteger(MEDIA_KEY_LTR_NUM_FRAMES, vc.getLTRCount());
            }
            if (vc.getHierStructLayers() > 0) {
                format.setInteger(MEDIA_KEY_HIER_STRUCT_LAYERS, vc.getHierStructLayers());
            }
            //IFrame size preset only valid for cbr on qcomm
            if (vc.getmBitrateMode() == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) {
                Log.d(TAG, "Set iframe preset: " + vc.getIframeSizePreset());
                switch (vc.getIframeSizePreset()) {
                    case DEFAULT:
                        format.setInteger(MEDIA_KEY_IFRAME_SIZE_PRESET, 0);
                        break;
                    case MEDIUM:
                        format.setInteger(MEDIA_KEY_IFRAME_SIZE_PRESET, 1);
                        break;
                    case HUGE:
                        format.setInteger(MEDIA_KEY_IFRAME_SIZE_PRESET, 2);
                        break;
                    case UNLIMITED:
                        format.setInteger(MEDIA_KEY_IFRAME_SIZE_PRESET, 3);
                        break;
                    default:
                        //Not possible
                }
            }
            int temporalLayerCount = vc.getTemporalLayerCount();
            if (temporalLayerCount > 1) {
                String temporalLayerValue;
                if (codecName.contains("vp8")) {
                    temporalLayerValue = "webrtc.vp8." + temporalLayerCount + "-layer";
                } else {
                    temporalLayerValue = "android.generic." + temporalLayerCount;
                }
                format.setString(MediaFormat.KEY_TEMPORAL_LAYERING, temporalLayerValue);
                Log.d(TAG, "Set temporal layers to " + temporalLayerValue);
            }

            Log.d(TAG, "Everything set. " + vc.getSettings());

            mInputSurfaceReference = new AtomicReference<>();
            mOutputSurface = new OutputSurface(vc.getVideoSize().getWidth(), vc.getVideoSize().getHeight());
            mCodec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurfaceReference.set(mCodec.createInputSurface());
            mInputSurface = new InputSurface(mInputSurfaceReference.get());
            mInputSurface.makeCurrent();

            mOutputSurface = new OutputSurface();
            mDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
            mDecoder.start();
        } catch (IOException iox) {
            Log.e(TAG, "Failed to create codec: "+iox.getMessage());
            return "Failed to create codec";
        } catch (MediaCodec.CodecException cex) {
            Log.e(TAG, "Configure failed: "+cex.getMessage());
            return "Failed to create codec";
        }

        try {
            mCodec.start();
        }
        catch(Exception ex){
            Log.e(TAG, "Start failed: "+ex.getMessage());
            return "Start encoding failed";
        }


        int inFramesCount = 0;
        int outFramesCount = 0;
        mFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        float mReferenceFrameRate = vc.getmReferenceFPS();
        mKeepInterval = mReferenceFrameRate / (float)mFrameRate;
        int mPts = 132;
        calculateFrameTiming();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isVP = mCodec.getCodecInfo().getName().toLowerCase().contains(".vp");
        if (isVP) {
            //There seems to be a bug so that this key is no set (but used).
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, format.getInteger(MediaFormat.KEY_BITRATE_MODE));
            if (mWriteFile)
                mMuxer = createMuxer(mCodec, format, true);
        }
        long totalTime = 0;
        long last_pts = 0;
        int current_loop = 1;
        while (loop >= current_loop) {
            int index;
            Log.d(TAG, "Frames: "+mFramesAdded + " - inframes: "+inFramesCount + ", current loop: " + current_loop);
            try {
                index = mDecoder.dequeueInputBuffer(VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
                if (index >= 0) {
                    ByteBuffer buffer = mDecoder.getInputBuffer(index);
                    int size = mExtractor.readSampleData(buffer, 0);
                    if (size > 0) {
                        mDecoder.queueInputBuffer(index, 0, size, mExtractor.getSampleTime(), mExtractor.getSampleFlags());
                    }
                    boolean eof = !mExtractor.advance();
                    if (eof) {
                        mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        current_loop++;

                        if (current_loop > loop) {
                            Log.d(TAG, "End of stream!");
                            mDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            break;
                        }
                        Log.d(TAG, "*** Loop ended starting " + current_loop + " ***");
                    }
                }
            }catch (Exception ex) {
                ex.printStackTrace();
            }

            index = mDecoder.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Just ignore
                continue;
            } else if (index >= 0) {
                if (info.size > 0) {
                    if (mNextLimit != -1 && inFramesCount >= mNextLimit) {
                        getNextLimit(inFramesCount);
                    }
                    ByteBuffer data = mDecoder.getOutputBuffer(index);
                    int currentFrameNbr = (int)((float)(inFramesCount) / mKeepInterval);
                    int nextFrameNbr = (int)((float)((inFramesCount + 1)) / mKeepInterval);
                    if (currentFrameNbr == nextFrameNbr || mDropNext) {
                        mDecoder.releaseOutputBuffer(index, false); //Skip this and read again
                        mDropNext = false;
                        mSkipped++;
                    } else {
                        mDecoder.releaseOutputBuffer(index, true);
                        mOutputSurface.awaitNewImage();
                        mOutputSurface.drawImage();
                        long pts = info.presentationTimeUs;
                        if (pts > last_pts) {
                            last_pts = pts;
                        } else {
                            // Loop
                            pts = (current_loop - 1) * last_pts + pts;
                        }
                        //egl have time in ns
                        mInputSurface.setPresentationTime(pts * 1000);
                        mInputSurface.swapBuffers();
                        mStats.startFrame(pts);
                    }

                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0){
                    ///Done
                    mCodec.signalEndOfInputStream();
                }
                inFramesCount++;
            }

            index = mCodec.dequeueOutputBuffer(info, VIDEO_CODEC_WAIT_TIME_US /* timeoutUs */);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //Just ignore
            } else if (index >= 0) {
                ByteBuffer data = mCodec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Log.e(TAG, "BUFFER_FLAG_CODEC_CONFIG: "+format);
                    MediaFormat oformat = mCodec.getOutputFormat();
                    //There seems to be a bug so that this key is no set (but used).
                    oformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
                    oformat.setInteger(MediaFormat.KEY_FRAME_RATE, format.getInteger(   MediaFormat.KEY_FRAME_RATE));
                    oformat.setInteger(MediaFormat.KEY_BITRATE_MODE, format.getInteger(MediaFormat.KEY_BITRATE_MODE));
                    if (mWriteFile)
                        mMuxer = createMuxer(mCodec, oformat, true);
                    mCodec.releaseOutputBuffer(index, false /* render */);
                } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                } else {
                    boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    mStats.stopFrame(info.presentationTimeUs, info.size, keyFrame);

                    if (keyFrame) {
                        Log.d(TAG, "Out buffer has KEY_FRAME @ " +outFramesCount );
                    }

                    ++outFramesCount;
                    mFramesAdded += 1;
                    totalTime += info.presentationTimeUs;
                    if (mMuxer != null)
                        mMuxer.writeSampleData(0, data, info);
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
        } catch(IllegalStateException iex) {
                Log.e(TAG, "Failed to shut down:" + iex.getLocalizedMessage()) ;
        }
        try {
            if (mMuxer != null) {
                mMuxer.stop();
                mMuxer.release();
            }
        }
        catch(IllegalStateException iex) {
            Log.e(TAG, "Failed to shut down:" + iex.getLocalizedMessage()) ;
        }

        return "";
    }


}
