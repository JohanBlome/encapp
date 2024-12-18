package com.facebook.encapp.utils;

public class CPUInfo {
    int mId;
    float mPerformance;
    String mFeatures;
    int mCpuImplementer;
    int mCpuArchitecture;
    int mCpuVariant;
    int mCpuPart;
    int mCpuRevison;

    public CPUInfo(int id, float performance, String features, int cpuImplementer, int cpuArchitecture, int cpuVariant, int cpuPart, int cpuRevison) {
        this.mId = id;
        this.mPerformance = performance;
        this.mFeatures = features;
        this.mCpuImplementer = cpuImplementer;
        this.mCpuArchitecture = cpuArchitecture;
        this.mCpuVariant = cpuVariant;
        this.mCpuPart = cpuPart;
        this.mCpuRevison = cpuRevison;
    }
}
