package com.facebook.encapp.utils;

import android.content.Context;
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
import java.util.Vector;

public class RawFrameFilterNative implements RawFrameFilter {
    private static String TAG = "encapp.rawframefilter";
    private String mFilterName;
    public RawFrameFilterNative(String filterName) {
        File lib = new File(filterName);
        mFilterName = lib.getName();

        String targetpath =  MainActivity.mContext.getFilesDir() + "/" +  mFilterName;
        try {
            Log.d(TAG, "Load native library: " + mFilterName);
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(lib));

            FileOutputStream fos = new FileOutputStream(targetpath);
            byte[] tmp = new byte[1024];
            int read = Integer.MAX_VALUE;
            while(read > 0) {
                read = bis.read(tmp);
                if (read > 0) {
                    fos.write(tmp, 0, read);
                }
            }
            bis.close();
            fos.close();

        } catch (FileNotFoundException e) {
            Log.d(TAG, "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.d(TAG, "Ioxceptionen. " + e.getMessage());
        }
        System.load(targetpath);
    }

    @Override
    public String[] getAvailableMethods() {
        return getAvailableMethodsNative();
    }

    @Override
    public PixFmt[] supportedPixelFormats() {
        String[] pixelFormats = getSupportedPixelFormatsNative();
        Vector<PixFmt> pixFmts = new Vector<>();
        for (String format: pixelFormats) {
            PixFmt fmt = PixFmt.valueOf(format);
            if (fmt != null) {
                pixFmts.add(fmt);
            }
        }

        PixFmt[] array = new PixFmt[pixFmts.size()];
        pixFmts.toArray(array);
        return array;
    }

    @Override
    public boolean setMethod(String method) {
        return setMethodNative(method);
    }

    public void setParameters(Parameter[] params) { setParametersNative(params); };

    @Override
    public void setRawFrameDefinitions(RawFrameDefinition inputDefinition, RawFrameDefinition outputDefinition) {
        setRawFrameDefinitionsNative(inputDefinition, outputDefinition);
    }

    @Override
    public boolean processFrame(byte[] input, byte[] output) {
        return processFrameNative(input, output);
    }

    @Override
    public String version() {
        return versionNative();
    }

    @Override
    public String description() {
        return descriptionNative();
    }

    @Override
    public String filterpath() {
        return mFilterName;
    }

    @Override
    public void release() {
        releaseNative();
    }


    private native String[] getAvailableMethodsNative();
    private native boolean setMethodNative(String method);

    private native void setParametersNative(Parameter[] params);
    private native boolean processFrameNative(byte[] inpBuffer, byte[] outBuffer);
    private native String versionNative();
    private native String descriptionNative();

    private native String[] getSupportedPixelFormatsNative();

    private native void releaseNative();
    private native void setRawFrameDefinitionsNative(RawFrameDefinition inputDefinition, RawFrameDefinition outputDefinition);
}
