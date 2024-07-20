package com.facebook.encapp.utils;

import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.PixFmt;

public interface RawFrameFilter {
    public String[] getAvailableMethods();

    public PixFmt[] supportedPixelFormats();
    public boolean setMethod(String method);
    public void setParameters(Parameter[] params);
    public void setRawFrameDefinitions(RawFrameDefinition input, RawFrameDefinition output);
    public boolean processFrame(byte[] input, byte[] output);

    public String version();
    public String description();
    public String filterpath();
    public void release();
}
