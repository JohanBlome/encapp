// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.encapp.utils;

import android.util.Size;

public class SizeUtils {
    public static Size parseXString(String strToParse) {
        if (strToParse == null || strToParse.isEmpty()) {
            return null;
        }

        String[] resolution = strToParse.split("x");
        int width = Integer.parseInt(resolution[0]);
        int height = Integer.parseInt(resolution[1]);
        return new Size(width, height);
    }
}
