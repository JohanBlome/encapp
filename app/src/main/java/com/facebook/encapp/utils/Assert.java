package com.facebook.encapp.utils;

public class Assert {
    public static void assertTrue(String descr, Boolean test) {
        if (!test) {
            throw new RuntimeException(descr);
        }
    }
}
