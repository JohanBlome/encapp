package com.facebook.encapp.utils;

import android.util.Log;

import com.facebook.encapp.MainActivity;
import com.facebook.encapp.proto.Parameter;
import com.facebook.encapp.proto.PixFmt;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Vector;

public class RawFrameFilterJava implements RawFrameFilter {
    private static String TAG = "encapp.rawframefilter.java";
    private String mFilterName;

    private String mMethod;
    private RawFrameFilter mFilter = null;
    private ArrayList<RawFrameFilter> mFilters = new ArrayList<>();

    RawFrameDefinition mInputDefinition;
    RawFrameDefinition mOutputDefinition;
    public RawFrameFilterJava(String filterName) {
        mFilterName = filterName;
        mFilters.add(new NearestNeighborScaler());
    }

    @Override
    public String[] getAvailableMethods() {
        Vector<String> methods = new Vector<>();
        for (RawFrameFilter filter : mFilters) {
            for (String method : filter.getAvailableMethods()) {
                methods.add(0, method);
            }
        }

        String[] ret = new String[methods.size()];
        return methods.toArray(ret);
    }

    @Override
    public PixFmt[] supportedPixelFormats() {
        return new PixFmt[]{PixFmt.yuv420p};
    }

    @Override
    public boolean setMethod(String method) {
        for (RawFrameFilter filter : mFilters) {
            for (String locm : filter.getAvailableMethods()) {
                if (locm.toLowerCase().equals(method.toLowerCase())) {
                    mMethod = method;
                    mFilter = filter;
                    filter.setMethod(mMethod);
                    return true;
                }
            }
        }

        return false;
    }

    public void setParameters(Parameter[] params) {
        if (mFilter != null) {
            mFilter.setParameters(params);
        }
    }

    ;

    @Override
    public void setRawFrameDefinitions(RawFrameDefinition inputDefinition, RawFrameDefinition outputDefinition) {
        mInputDefinition = inputDefinition;
        mOutputDefinition = outputDefinition;
    }

    @Override
    public boolean processFrame(byte[] input, byte[] output) {
        if (mFilter != null) {
            mFilter.setRawFrameDefinitions(mInputDefinition, mOutputDefinition);
            return mFilter.processFrame(input, output);
        }

        return false;
    }

    @Override
    public String version() {
        if (mFilter != null) {
            return mFilter.version();
        }

        return "";
    }

    @Override
    public String description() {
        if (mFilter != null) {
            return mFilter.description();
        }
        return "";
    }

    @Override
    public String filterpath() {
        return mFilterName;
    }

    @Override
    public void release() {
        if (mFilter != null) {
            mFilter.release();
        }
    }

}
