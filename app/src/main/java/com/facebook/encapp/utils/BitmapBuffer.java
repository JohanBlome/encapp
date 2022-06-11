package com.facebook.encapp.utils;

import android.graphics.Bitmap;

public class BitmapBuffer implements BufferObject{
    public Bitmap mBitmap;
    public long mTimestampUs;

    public BitmapBuffer(Bitmap bitmap, long timestampUs) {
        mBitmap = bitmap;
        mTimestampUs = timestampUs;
    }
    @Override
    public long getTimestampUs() {
        return mTimestampUs;
    }
}
