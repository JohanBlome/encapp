package com.facebook.encapp.utils;

import android.util.Log;

import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.PixFmt;

public class NearestNeighborScaler implements RawFrameFilter {
    private static String TAG = "encapp.rawframefilter";
    RawFrameDefinition mInputDefinition;
    RawFrameDefinition mOutputDefinition;
    @Override
    public String[] getAvailableMethods() {
        return new String[] {"neighbor"};
    }

    @Override
    public PixFmt[] supportedPixelFormats() {
        return new PixFmt[] {PixFmt.yuv420p};
    }

    @Override
    public boolean setMethod(String method) {
        return true;
    }

    @Override
    public void setParameters(Parameter[] params) {
    }


    @Override
    public void setRawFrameDefinitions(RawFrameDefinition inputDefinition, RawFrameDefinition outputDefinition) {
        mInputDefinition = inputDefinition;
        mOutputDefinition = outputDefinition;
    }

    @Override
    public boolean processFrame(byte[] inpBuffer, byte[] outBuffer) {
        if ((mInputDefinition == null) || (mOutputDefinition == null)) {
            Log.e(TAG, "Input/output not defined.");
            return false;
        }
        // Loop outbuffer and lookup closest source
        // TODO: check mInputDefinition settings
        double w_ratio = (float)mInputDefinition.getWidth() / (float)mOutputDefinition.getWidth();
        double h_ratio = (float)mInputDefinition.getHeight() / (float)mOutputDefinition.getHeight();

        if (mOutputDefinition.getPixFmt() == PixFmt.yuv420p) {
            for (int y = 0; y < mOutputDefinition.getHeight(); y++) {
                int yi = (int) (y * h_ratio);
                int yo_pos = y * mOutputDefinition.getStride();
                int yi_pos = yi * mInputDefinition.getStride();

                for (int x = 0; x < mOutputDefinition.getWidth(); x++) {
                    int xi = (int) (x * w_ratio);
                    int inpos = yi_pos + xi;
                    int outpos = yo_pos + x;

                    outBuffer[outpos] = inpBuffer[inpos];
                }
            }
            for (int y = 0; y < mOutputDefinition.getHeight()/2; y++) {
                int yi = (int)(y * h_ratio);
                int uo_pos = (int)(mOutputDefinition.getYPlaneSize() + y * mOutputDefinition.getWidth()/2);
                int vo_pos = (int) (uo_pos + mOutputDefinition.getChromaPlaneSize());
                int ui_pos = (int)(mInputDefinition.getYPlaneSize() + yi * mInputDefinition.getWidth()/2);
                int vi_pos = (int) (ui_pos + mInputDefinition.getChromaPlaneSize());
                for (int x = 0; x < mOutputDefinition.getWidth()/2; x++) {
                    int xi = (int)(x * w_ratio);

                    int inpos = ui_pos + xi;
                    int outpos = uo_pos + x;

                    outBuffer[outpos] = inpBuffer[inpos];
                    inpos = vi_pos + xi;
                    outpos = vo_pos + x;
                    outBuffer[outpos] = inpBuffer[inpos];
                }
            }
        }

        return true;
    }

    @Override
    public String version() {
        return "0.1";
    }

    @Override
    public String description() {
        return "Java implementation of nearest neighbor algo. " +
                "Not to be used for anything, really.";
    }

    @Override
    public String filterpath() {
        return "builtin java";
    }

    @Override
    public void release() {

    }
}
