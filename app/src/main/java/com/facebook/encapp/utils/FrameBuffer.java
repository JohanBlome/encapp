package com.facebook.encapp.utils;

import android.graphics.Bitmap;
import android.media.MediaCodec;

import java.nio.Buffer;

public class FrameBuffer implements BufferObject {
        public MediaCodec mCodec;
        public int mBufferId = -1;
        public MediaCodec.BufferInfo mInfo;

    public FrameBuffer(MediaCodec codec, int id, MediaCodec.BufferInfo info) {
        mCodec = codec;
        mBufferId = id;
        mInfo = info;
    }


    @Override
    public long getTimestampUs() {
        if (mInfo != null) {
            return mInfo.presentationTimeUs;
        } else {
            return -1;
        }
    }
}
