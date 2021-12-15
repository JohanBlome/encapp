package com.facebook.encapp.utils;

public class SessionParam {
    public String getOutputCodec() {
        return mOutputCodec;
    }

    public void setOutputCodec(String outputCodec) {
        this.mOutputCodec = outputCodec;
    }

    public String getOutputResolution() {
        return mOutputResolution;
    }

    public void setOutputResolution(String outputResolution) {
        this.mOutputResolution = outputResolution;
    }

    public String getInputResolution() {
        return mInputResolution;
    }

    public void setInputResolution(String inputResolution) {
        this.mInputResolution = inputResolution;
    }

    public String getInputFile() {
        return mInputFile;
    }

    public void setInputFile(String inputFile) {
        this.mInputFile = inputFile;
    }

    public String getInputFps() {
        return mInputFps;
    }

    public void setInputFps(String inputFps) {
        this.mInputFps = inputFps;
    }

    public String getOutputFps() {
        return mOutputFps;
    }

    public void setOutputFps(String outputFps) {
        this.mOutputFps = outputFps;
    }


    public String mOutputCodec = null;
    public String mInputResolution = null;
    public String mInputFile = null;
    public String mInputFps = null;
    public String mOutputFps = null;
    public String mOutputResolution = null;

}
