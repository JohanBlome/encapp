package com.facebook.encapp.utils;

import android.media.MediaCodec;

public class FrameBuffer {
        public MediaCodec mCodec;
        public int mBufferId = -1;
        public MediaCodec.BufferInfo mInfo;

        public FrameBuffer(MediaCodec codec, int id, MediaCodec.BufferInfo info) {
            mCodec = codec;
            mBufferId = id;
            mInfo = info;
        }
}
