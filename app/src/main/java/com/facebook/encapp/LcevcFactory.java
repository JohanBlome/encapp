package com.facebook.encapp;

import com.facebook.encapp.proto.Test;

public class LcevcFactory {
    // Return the LcevcEncoder class when using the lcevc flavour. Otherwise, return null.
    public static Encoder createEncoderOrNull(Test test) {
        try {
            Class<?> cls = Class.forName("com.facebook.encapp.LcevcEncoder");
            return (Encoder) cls.getConstructor(Test.class).newInstance(test);
        } catch (Throwable t) {
            return null;
        }
    }

}
