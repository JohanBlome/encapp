package com.facebook.encapp.utils.codec;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * AV1 codec writer for AVIF images and MP4 video.
 * Handles av1C box generation (simpler than HEVC/AVC).
 */
public class Av1CodecWriter extends BaseCodecWriter {

    public Av1CodecWriter() {
        super(CodecType.AV1);
    }

    @Override
    public String getMajorBrand(boolean isImage) {
        return isImage ? "avif" : "av01";
    }

    @Override
    public String[] getCompatibleBrands(boolean isImage) {
        if (isImage) {
            return new String[]{"avif", "mif1"};
        } else {
            return new String[]{"av01", "iso6", "mp41", "isom"};
        }
    }

    @Override
    public void writeCodecConfigBox(FileOutputStream file, byte[] codecData, int width, int height)
            throws IOException {
        long position = startBox(file, "av1C");

        writeInt8(file, 0x81);
        writeInt8(file, 0x00);
        writeInt8(file, 0x0C);
        writeInt8(file, 0x00);

        endBox(file, position);
        log("Wrote minimal av1C box");
    }

    @Override
    public void writeSampleEntryBox(FileOutputStream file, byte[] codecData, int width, int height,
                                   boolean hasCleanAperture, int cleanWidth, int cleanHeight)
            throws IOException {
        long position = startBox(file, "av01");

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
        log("AV1 frame data conversion not yet implemented, returning as-is");
        return frameData;
    }
}
