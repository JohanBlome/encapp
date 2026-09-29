package com.facebook.encapp.utils;

import android.media.MediaCodecInfo;

import org.json.JSONArray;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaCodecInfoHelperTest {

    @Test
    public void profileLevelsToJsonPreservesEveryPair() throws Exception {
        MediaCodecInfo.CodecProfileLevel main = new MediaCodecInfo.CodecProfileLevel();
        main.profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
        main.level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4;

        MediaCodecInfo.CodecProfileLevel main10 = new MediaCodecInfo.CodecProfileLevel();
        main10.profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
        main10.level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5;

        JSONArray result = MediaCodecInfoHelper.profileLevelsToJson(
                new MediaCodecInfo.CodecProfileLevel[]{main, main10});

        assertEquals(2, result.length());
        assertEquals(main.profile, result.getJSONObject(0).getInt("profile"));
        assertEquals(main.level, result.getJSONObject(0).getInt("level"));
        assertEquals(main10.profile, result.getJSONObject(1).getInt("profile"));
        assertEquals(main10.level, result.getJSONObject(1).getInt("level"));
    }

    @Test
    public void profileLevelsToJsonPreservesEmptyArray() throws Exception {
        JSONArray result = MediaCodecInfoHelper.profileLevelsToJson(
                new MediaCodecInfo.CodecProfileLevel[0]);

        assertEquals(0, result.length());
    }
}
