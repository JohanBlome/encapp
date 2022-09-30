package com.facebook.encapp.utils;

public class Assert {
    public static void assertTrue(Boolean condition, String descr) {
        if (!condition) {
            throw new RuntimeException(descr);
        }
    }
}
