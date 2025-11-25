package com.facebook.encapp.utils.codec;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * VP9 codec writer for HEIF images and MP4 video.
 * Handles vpcC box generation.
 */
public class Vp9CodecWriter extends BaseCodecWriter {

    public Vp9CodecWriter() {
        super(CodecType.VP9);
    }

    @Override
    public String getMajorBrand(boolean isImage) {
        return isImage ? "mif1" : "vp09";
    }

    @Override
    public String[] getCompatibleBrands(boolean isImage) {
        if (isImage) {
            return new String[]{"vp09", "mif1"};
        } else {
            return new String[]{"vp09", "iso6", "mp41", "isom"};
        }
    }

    @Override
    public void writeCodecConfigBox(FileOutputStream file, byte[] codecData, int width, int height)
            throws IOException {
        long position = startBox(file, "vpcC");

        writeInt8(file, 1);
        writeInt8(file, 0);
        writeInt8(file, 0);
        writeInt8(file, 0);

        endBox(file, position);
        log("Wrote minimal vpcC box");
    }

    @Override
    public void writeSampleEntryBox(FileOutputStream file, byte[] codecData, int width, int height,
                                   boolean hasCleanAperture, int cleanWidth, int cleanHeight)
            throws IOException {
        long position = startBox(file, "vp09");

        for (int i = 0; i < 6; i++) {
            writeInt8(file, 0);
        }

        writeInt16(file, 1);
        writeInt16(file, 0);
        writeInt16(file, 0);

        writeInt32(file, 0);
        writeInt32(file, 0);
        writeInt32(file, 0);

        writeInt16(file, width);
        writeInt16(file, height);

        writeInt32(file, 0x00480000);
        writeInt32(file, 0x00480000);

        writeInt32(file, 0);
        writeInt16(file, 1);

        writeInt8(file, 0);
        for (int i = 0; i < 31; i++) {
            writeInt8(file, 0);
        }

        writeInt16(file, 0x0018);
        writeInt16(file, -1);

        writeCodecConfigBox(file, codecData, width, height);

        endBox(file, position);
    }

    @Override
    public byte[] convertFrameData(byte[] frameData) {
        log("VP9 frame data conversion not yet implemented, returning as-is");
        return frameData;
    }
}
