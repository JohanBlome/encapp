// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.encapp.utils;

import android.util.Log;
import android.util.Size;

import java.util.Locale;

public class SizeUtils {
    public static Size parseXString(String strToParse) {
        if (strToParse == null || strToParse.isEmpty()) {
            return null;
        }

        String[] resolution = strToParse.trim().toLowerCase(Locale.ROOT).split("x");
        if (resolution.length == 2) {
            int width = Integer.parseInt(resolution[0]);
            int height = Integer.parseInt(resolution[1]);
            return new Size(width, height);
        }
        Log.e("builder", "Failed to parse size: " + strToParse);
        return null;
    }
}
