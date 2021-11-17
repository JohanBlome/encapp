package com.facebook.encapp.utils;

public class RuntimeParam {
    public String name;
    public String type;
    public Object value;
    public int frame;
    public RuntimeParam(String name, int frame, String type, Object value) {
        this.name = name;
        this.value = value;
        this.frame = frame;
        this.type = type;
    }
}
