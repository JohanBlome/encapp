package com.facebook.encapp.utils.codec;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Interface for codec-specific operations in HEIF/MP4 muxing.
 * Each codec implementation handles its own brand codes, configuration boxes, and data conversion.
 */
public interface CodecWriter {
    
    /**
     * Get the codec type this writer handles.
     */
    CodecType getCodecType();
    
    /**
     * Get the major brand for ftyp box.
     * For video: "avc1", "hvc1", "av01", etc.
     * For images: "mif1", "avif", etc.
     */
    String getMajorBrand(boolean isImage);
    
    /**
     * Get compatible brands for ftyp box.
     * Returns an array of FourCC brand codes.
     */
    String[] getCompatibleBrands(boolean isImage);
    
    /**
     * Write the codec configuration box (avcC, hvcC, av1C, vpcC, etc.).
     * 
     * @param file Output file stream
     * @param codecData Raw codec configuration data (can be Annex-B or native format)
     * @param width Video/image width
     * @param height Video/image height
     * @throws IOException if write fails
     */
    void writeCodecConfigBox(FileOutputStream file, byte[] codecData, int width, int height) 
            throws IOException;
    
    /**
     * Write the sample entry box (avc1, hvc1, av01, vp09, etc.) for MP4 video.
     * 
     * @param file Output file stream
     * @param codecData Codec configuration data
     * @param width Video width
     * @param height Video height
     * @param hasCleanAperture Whether to include clean aperture box
     * @param cleanWidth Clean aperture width
     * @param cleanHeight Clean aperture height
     * @throws IOException if write fails
     */
    void writeSampleEntryBox(FileOutputStream file, byte[] codecData, int width, int height,
                            boolean hasCleanAperture, int cleanWidth, int cleanHeight) 
            throws IOException;
    
    /**
     * Convert frame data to the format required for mdat.
     * For AVC/HEVC: Convert Annex-B to AVCC format.
     * For AV1: Convert to OBU format if needed.
     *
     * @param frameData Raw frame data
     * @return Converted frame data ready for mdat, or null on error
     */
    byte[] convertFrameData(byte[] frameData);
    
    /**
     * Get the item type for HEIF images (used in infe box).
     * Returns values like "hvc1", "av01", etc.
     */
    String getItemType();
}
