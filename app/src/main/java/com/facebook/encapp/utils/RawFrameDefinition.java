package com.facebook.encapp.utils;

import com.facebook.encapp.proto.Input;
import com.facebook.encapp.proto.PixFmt;

public class RawFrameDefinition {
    int mWidth;
    int mHeight;
    int mStride;
    PixFmt mPixFmt;

    int mYplaneSize = 0;
    int mChromaPlaneSize = 0;

    public RawFrameDefinition(int width, int height, int stride, PixFmt pixFmt) {
        this.mWidth = width;
        this.mHeight = height;
        this.mStride = stride;
        this.mPixFmt = pixFmt;
        this.mYplaneSize = stride * height;
        //TODO: stride?
        this.mChromaPlaneSize = (int)(stride/2)  * (int)(height/2);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getStride() {
        return mStride;
    }

    public PixFmt getPixFmt() {
        return mPixFmt;
    }

    public String getPixFmtAsString() {
        return getPixFmt().toString();
    }
    public int getYPlaneSize() {
        return mYplaneSize;
    }

    public int getChromaPlaneSize() {
        return mChromaPlaneSize;
    }

}
