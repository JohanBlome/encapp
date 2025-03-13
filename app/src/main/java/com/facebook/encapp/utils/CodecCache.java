package com.facebook.encapp.utils;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.facebook.encapp.proto.Test;

import java.io.IOException;
import java.util.Vector;

public class CodecCache {
    private static CodecCache me = new CodecCache();
    private static String TAG = "encapp.cc";
    Vector<MediaCodec> mEncoders = new Vector<>();
    Vector<MediaCodec> mDecoders = new Vector<>();
    Vector<MediaCodec> mInUse = new Vector<>();

    public MediaCodec getEncoder(String description) {
        //TODO: check all settings, for now assume name
        if (mEncoders.size() == 0) return null;
        synchronized (mInUse) {
            for(MediaCodec codec: mEncoders) {
                if (codec.getName().equals(description)) {
                    Log.d(TAG, "Reusing codec: " + codec.getName());
                    mEncoders.remove(codec);
                    mInUse.add(codec);
                    return codec;
                } else {
                    String[] types = codec.getCodecInfo().getSupportedTypes();
                    for (String typedesc: codec.getCodecInfo().getSupportedTypes()){
                        if (typedesc.contains(description.toLowerCase())) {
                            Log.d(TAG, "Reusing codec: " + codec.getName());
                            mEncoders.remove(codec);
                            mInUse.add(codec);
                            return codec;
                        }
                    }
                }
            }
        }
        return null;
    }

    public MediaCodec getDecoder(String description) {
        if (mEncoders.size() == 0) return null;
        synchronized (mInUse) {
            for(MediaCodec codec: mDecoders) {
                if (codec.getName().equals(description)) {
                    Log.d(TAG, "Reusing decoder:" +  codec.getName());
                    mDecoders.remove(codec);
                    mInUse.add(codec);
                    return codec;
                } else {
                    String[] types = codec.getCodecInfo().getSupportedTypes();
                    for (String typedesc: codec.getCodecInfo().getSupportedTypes()){
                        if (typedesc.contains(description.toLowerCase())) {
                            Log.d(TAG, "Reusing codec: " + codec.getName());
                            mDecoders.remove(codec);
                            mInUse.add(codec);
                            return codec;
                        }
                    }
                }
            }
        }
        return null;
    }

    public void returnEncoder(MediaCodec codec) {
        synchronized (mInUse) {
            mInUse.remove(codec);
            mEncoders.add(codec);
        }
    }

    public void returnDecoder(MediaCodec codec) {
        synchronized (mInUse) {
            mInUse.remove(codec);
            mDecoders.add(codec);
        }
    }

    public void clearCodecs() {
        for (MediaCodec codec: mDecoders) {
            codec.release();
        }

        for (MediaCodec codec: mEncoders) {
            codec.release();
        }

        for (MediaCodec codec: mInUse) {
            codec.release();
        }
    }


    public MediaCodec createEncoder(Test test) throws IOException{
        MediaCodec codec = null;
        if (test.getConfigure().getMime().length() == 0) {
            Log.d(TAG, "codec id: " + test.getConfigure().getCodec());
            try {
                test = MediaCodecInfoHelper.setCodecNameAndIdentifier(test);
            } catch (Exception e) {
                Log.d(TAG, "Failed to match encoder");
                throw new IOException("Cannot match name");
            }
            Log.d(TAG, "codec: " + test.getConfigure().getCodec() + " mime: " + test.getConfigure().getMime());
        }
        Log.d(TAG, "Create encoder by name: " + test.getConfigure().getCodec());
        Log.d(TAG, "Create encoder by name: " + test.getConfigure().getCodec());
        //TODO: check settings
        try {
            codec = MediaCodec.createByCodecName(test.getConfigure().getCodec());
        } catch (IOException iox) {
            Log.d(TAG, "Failed creating encoder: " + iox.getMessage());
            throw new IOException(iox);
        }

        mInUse.add(codec);
        return codec;
    }

    public MediaCodec createDecoder(Test test, MediaFormat format) throws IOException {
        MediaCodec codec = null;
        try {
            if (test.getDecoderConfigure().hasCodec()) {
                Log.d(TAG, "Create codec by name: " + test.getDecoderConfigure().getCodec());
                // TODO: check decoder settings
                codec = MediaCodec.createByCodecName(test.getDecoderConfigure().getCodec());
            } else {
                Log.d(TAG, "Create decoder by type: " + format.getString(MediaFormat.KEY_MIME));
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            }
        } catch (IOException iox) {
            Log.e(TAG, "Failed creating decoder: " + iox.getMessage());
            throw new IOException(iox);
        }

        mInUse.add(codec);
        return codec;
    }

    public static CodecCache getCache() {
        return me;
    }

    /* for now ignore configure and let the test do it
    public void configureEncoder(Test test, MediaFormat inputFormat) {

    }


    public void configureDecoder(Test test, MediaFormat format) {

    }
    */

}
