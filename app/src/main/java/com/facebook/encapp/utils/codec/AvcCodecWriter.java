package com.facebook.encapp.utils.codec;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AVC/H.264 codec writer for HEIF/MP4 containers.
 * Handles avcC box generation and Annex-B to AVCC conversion.
 */
public class AvcCodecWriter extends BaseCodecWriter {

    // Storage for SPS/PPS NAL units extracted from frame data.
    // Used as fallback when codec config data is not provided in initialize().
    private byte[] mExtractedSps = null;
    private byte[] mExtractedPps = null;

    public AvcCodecWriter() {
        super(CodecType.AVC);
    }

    @Override
    public String getMajorBrand(boolean isImage) {
        return isImage ? "mif1" : "avc1";
    }

    @Override
    public String[] getCompatibleBrands(boolean isImage) {
        if (isImage) {
            return new String[]{"avcs", "mif1"};
        } else {
            return new String[]{"avc1", "iso6", "mp41", "isom"};
        }
    }

    @Override
    public void writeCodecConfigBox(FileOutputStream file, byte[] codecData, int width, int height)
            throws IOException {
        long position = startBox(file, "avcC");

        // If no codec config data provided, try using extracted SPS/PPS from frames
        byte[] dataToUse = codecData;
        if ((dataToUse == null || dataToUse.length == 0) && hasExtractedCodecConfig()) {
            dataToUse = getExtractedCodecConfig();
            log("Using SPS/PPS extracted from frame data (no codec config provided)");
        }

        if (dataToUse != null && dataToUse.length > 0) {
            log(String.format("Writing avcC box with %d bytes of codec config data", dataToUse.length));
            log(String.format("First bytes: %02x %02x %02x %02x",
                dataToUse[0] & 0xFF,
                dataToUse.length > 1 ? dataToUse[1] & 0xFF : 0,
                dataToUse.length > 2 ? dataToUse[2] & 0xFF : 0,
                dataToUse.length > 3 ? dataToUse[3] & 0xFF : 0));

            if (dataToUse.length >= 7 && dataToUse[0] == 0x01) {
                log("Codec data appears to be in AVCC format, writing directly");
                writeBytes(file, dataToUse);
            } else {
                log("Codec data appears to be in Annex-B format, parsing...");
                writeAvccFromAnnexB(file, dataToUse);
            }
        } else {
            log("No codec config data available, writing minimal avcC box");
            writeMinimalAvccBox(file);
        }

        endBox(file, position);
    }

    @Override
    public void writeSampleEntryBox(FileOutputStream file, byte[] codecData, int width, int height,
                                   boolean hasCleanAperture, int cleanWidth, int cleanHeight)
            throws IOException {
        long position = startBox(file, "avc1");

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

        if (hasCleanAperture) {
            writeClapBox(file, width, height, cleanWidth, cleanHeight);
        }

        endBox(file, position);
    }

    @Override
    public byte[] convertFrameData(byte[] frameData) {
        // Extract and store SPS/PPS from frame data for use as fallback codec config
        extractAndStoreSpsPs(frameData);
        return convertToAVCC(frameData, false);
    }

    /**
     * Get the extracted codec configuration data (SPS/PPS) in Annex-B format.
     * This is used as fallback when codec config data was not provided during initialization.
     *
     * @return SPS/PPS in Annex-B format, or null if not available
     */
    public byte[] getExtractedCodecConfig() {
        if (mExtractedSps == null || mExtractedPps == null) {
            return null;
        }

        // Build Annex-B format: 00 00 00 01 SPS 00 00 00 01 PPS
        int totalSize = 4 + mExtractedSps.length + 4 + mExtractedPps.length;
        byte[] annexB = new byte[totalSize];
        int offset = 0;

        // Start code for SPS
        annexB[offset++] = 0;
        annexB[offset++] = 0;
        annexB[offset++] = 0;
        annexB[offset++] = 1;
        System.arraycopy(mExtractedSps, 0, annexB, offset, mExtractedSps.length);
        offset += mExtractedSps.length;

        // Start code for PPS
        annexB[offset++] = 0;
        annexB[offset++] = 0;
        annexB[offset++] = 0;
        annexB[offset++] = 1;
        System.arraycopy(mExtractedPps, 0, annexB, offset, mExtractedPps.length);

        return annexB;
    }

    /**
     * Check if SPS/PPS have been extracted from frame data.
     */
    public boolean hasExtractedCodecConfig() {
        return mExtractedSps != null && mExtractedPps != null;
    }

    /**
     * Extract and store SPS/PPS NAL units from frame data.
     * This handles both Annex-B and AVCC format input.
     */
    private void extractAndStoreSpsPs(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return;
        }

        // Already have SPS and PPS, no need to extract again
        if (mExtractedSps != null && mExtractedPps != null) {
            return;
        }

        // Check if data is in AVCC format (4-byte length prefix)
        boolean isAvcc = false;
        if (buffer.length >= 8) {
            int firstNalLength = ((buffer[0] & 0xFF) << 24) |
                                ((buffer[1] & 0xFF) << 16) |
                                ((buffer[2] & 0xFF) << 8) |
                                (buffer[3] & 0xFF);
            if (firstNalLength > 0 && firstNalLength <= buffer.length - 4 &&
                !(buffer[0] == 0 && buffer[1] == 0 && buffer[2] == 0 && buffer[3] == 1)) {
                isAvcc = true;
            }
        }

        if (isAvcc) {
            extractFromAvcc(buffer);
        } else {
            extractFromAnnexB(buffer);
        }
    }

    private void extractFromAvcc(byte[] buffer) {
        int offset = 0;
        while (offset + 4 < buffer.length) {
            int nalLength = ((buffer[offset] & 0xFF) << 24) |
                           ((buffer[offset + 1] & 0xFF) << 16) |
                           ((buffer[offset + 2] & 0xFF) << 8) |
                           (buffer[offset + 3] & 0xFF);

            if (nalLength <= 0 || offset + 4 + nalLength > buffer.length) {
                break;
            }

            int nalStart = offset + 4;
            int nalType = buffer[nalStart] & 0x1F;

            if (nalType == 7 && mExtractedSps == null) {
                // SPS
                mExtractedSps = new byte[nalLength];
                System.arraycopy(buffer, nalStart, mExtractedSps, 0, nalLength);
                log(String.format("Extracted SPS from frame (AVCC): %d bytes", nalLength));
            } else if (nalType == 8 && mExtractedPps == null) {
                // PPS
                mExtractedPps = new byte[nalLength];
                System.arraycopy(buffer, nalStart, mExtractedPps, 0, nalLength);
                log(String.format("Extracted PPS from frame (AVCC): %d bytes", nalLength));
            }

            offset += 4 + nalLength;
        }
    }

    private void extractFromAnnexB(byte[] buffer) {
        int offset = 0;
        while (offset < buffer.length) {
            int startCodeLen = 0;
            if (offset + 3 < buffer.length &&
                buffer[offset] == 0 && buffer[offset + 1] == 0) {
                if (buffer[offset + 2] == 1) {
                    startCodeLen = 3;
                } else if (offset + 4 < buffer.length &&
                          buffer[offset + 2] == 0 && buffer[offset + 3] == 1) {
                    startCodeLen = 4;
                }
            }

            if (startCodeLen == 0) {
                offset++;
                continue;
            }

            int nalStart = offset + startCodeLen;
            if (nalStart >= buffer.length) {
                break;
            }

            int nalType = buffer[nalStart] & 0x1F;

            // Find end of NAL unit
            int nalEnd = buffer.length;
            for (int i = nalStart + 1; i + 2 < buffer.length; i++) {
                if (buffer[i] == 0 && buffer[i + 1] == 0 &&
                    (buffer[i + 2] == 1 ||
                     (i + 3 < buffer.length && buffer[i + 2] == 0 && buffer[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            int nalLength = nalEnd - nalStart;
            if (nalType == 7 && mExtractedSps == null) {
                // SPS
                mExtractedSps = new byte[nalLength];
                System.arraycopy(buffer, nalStart, mExtractedSps, 0, nalLength);
                log(String.format("Extracted SPS from frame (Annex-B): %d bytes", nalLength));
            } else if (nalType == 8 && mExtractedPps == null) {
                // PPS
                mExtractedPps = new byte[nalLength];
                System.arraycopy(buffer, nalStart, mExtractedPps, 0, nalLength);
                log(String.format("Extracted PPS from frame (Annex-B): %d bytes", nalLength));
            }

            offset = nalEnd;
        }
    }

    private void writeMinimalAvccBox(FileOutputStream file) throws IOException {
        writeInt8(file, 1);
        writeInt8(file, 0x42);
        writeInt8(file, 0x80);
        writeInt8(file, 0x1E);
        writeInt8(file, 0xFF);
        writeInt8(file, 0xE0);
        writeInt8(file, 0x00);
    }

    private void writeClapBox(FileOutputStream file, int width, int height, int cleanWidth, int cleanHeight)
            throws IOException {
        long position = startBox(file, "clap");

        writeInt32(file, cleanWidth << 16);
        writeInt32(file, 0);

        writeInt32(file, cleanHeight << 16);
        writeInt32(file, 0);

        int hOffset = (width - cleanWidth) / 2;
        writeInt32(file, hOffset << 16);
        writeInt32(file, 0);

        int vOffset = (height - cleanHeight) / 2;
        writeInt32(file, vOffset << 16);
        writeInt32(file, 0);

        endBox(file, position);
    }

    private void writeAvccFromAnnexB(FileOutputStream file, byte[] annexB) throws IOException {
        log(String.format("Parsing Annex-B data: %d bytes", annexB.length));

        StringBuilder hexDump = new StringBuilder();
        for (int i = 0; i < Math.min(64, annexB.length); i++) {
            if (i > 0 && i % 16 == 0) hexDump.append("\n");
            hexDump.append(String.format("%02x ", annexB[i] & 0xFF));
        }
        log("Codec data hex dump:\n" + hexDump.toString());

        List<byte[]> spsNals = new ArrayList<>();
        List<byte[]> ppsNals = new ArrayList<>();

        int offset = 0;
        while (offset < annexB.length) {
            int startCodeLen = 0;
            if (offset + 3 < annexB.length &&
                annexB[offset] == 0 && annexB[offset + 1] == 0) {
                if (annexB[offset + 2] == 1) {
                    startCodeLen = 3;
                } else if (offset + 4 < annexB.length &&
                          annexB[offset + 2] == 0 && annexB[offset + 3] == 1) {
                    startCodeLen = 4;
                }
            }

            if (startCodeLen == 0) {
                offset++;
                continue;
            }

            int nalStart = offset + startCodeLen;
            if (nalStart >= annexB.length) {
                break;
            }

            int nalType = annexB[nalStart] & 0x1F;

            int nalEnd = annexB.length;
            for (int i = nalStart + 1; i + 2 < annexB.length; i++) {
                if (annexB[i] == 0 && annexB[i + 1] == 0 &&
                    (annexB[i + 2] == 1 ||
                     (i + 3 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1))) {
                    nalEnd = i;
                    log(String.format("Found next start code at position %d", i));
                    break;
                }
            }

            byte[] nalData = new byte[nalEnd - nalStart];
            System.arraycopy(annexB, nalStart, nalData, 0, nalData.length);

            log(String.format("Found NAL at offset %d, type=%d, size=%d bytes",
                offset, nalType, nalData.length));

            if (nalType == 7) {
                spsNals.add(nalData);
                log("Added SPS NAL");
            } else if (nalType == 8) {
                ppsNals.add(nalData);
                log("Added PPS NAL");
            }

            offset = nalEnd;
        }

        log(String.format("Found %d SPS and %d PPS NAL units", spsNals.size(), ppsNals.size()));

        // If we couldn't find SPS/PPS in the codec config data, try using extracted ones from frames
        if (spsNals.isEmpty() && mExtractedSps != null) {
            spsNals.add(mExtractedSps);
            log("Using SPS extracted from frame data as fallback");
        }
        if (ppsNals.isEmpty() && mExtractedPps != null) {
            ppsNals.add(mExtractedPps);
            log("Using PPS extracted from frame data as fallback");
        }

        if (!spsNals.isEmpty() && !ppsNals.isEmpty()) {
            byte[] sps = spsNals.get(0);
            byte profile = sps.length > 1 ? sps[1] : 0x42;
            byte compatibility = sps.length > 2 ? sps[2] : (byte) 0x80;
            byte level = sps.length > 3 ? sps[3] : 0x1E;

            log(String.format("Writing avcC: profile=0x%02x, compat=0x%02x, level=0x%02x",
                profile & 0xFF, compatibility & 0xFF, level & 0xFF));

            writeInt8(file, 1);
            writeInt8(file, profile & 0xFF);
            writeInt8(file, compatibility & 0xFF);
            writeInt8(file, level & 0xFF);
            writeInt8(file, 0xFF);

            writeInt8(file, 0xE0 | spsNals.size());
            for (byte[] spsNal : spsNals) {
                writeInt16(file, spsNal.length);
                writeBytes(file, spsNal);
                log(String.format("Wrote SPS: %d bytes", spsNal.length));
            }

            writeInt8(file, ppsNals.size());
            for (byte[] ppsNal : ppsNals) {
                writeInt16(file, ppsNal.length);
                writeBytes(file, ppsNal);
                log(String.format("Wrote PPS: %d bytes", ppsNal.length));
            }
        } else {
            log("No SPS/PPS found in Annex-B data, writing minimal avcC");
            writeMinimalAvccBox(file);
        }
    }

    private byte[] convertToAVCC(byte[] buffer, boolean isHEVC) {
        if (buffer == null || buffer.length == 0) {
            return null;
        }

        if (buffer.length >= 8) {
            int firstNalLength = ((buffer[0] & 0xFF) << 24) |
                                ((buffer[1] & 0xFF) << 16) |
                                ((buffer[2] & 0xFF) << 8) |
                                (buffer[3] & 0xFF);

            if (firstNalLength > 0 && firstNalLength <= buffer.length - 4 &&
                !(buffer[0] == 0 && buffer[1] == 0 && buffer[2] == 0 && buffer[3] == 1)) {

                int offset = 0;
                boolean isValid = true;
                while (offset + 4 <= buffer.length && isValid) {
                    int nalLength = ((buffer[offset] & 0xFF) << 24) |
                                   ((buffer[offset + 1] & 0xFF) << 16) |
                                   ((buffer[offset + 2] & 0xFF) << 8) |
                                   (buffer[offset + 3] & 0xFF);

                    if (nalLength == 0 || offset + 4 + nalLength > buffer.length) {
                        isValid = false;
                    } else {
                        offset += 4 + nalLength;
                    }
                }

                if (isValid && offset == buffer.length) {
                    log("Frame data already in AVCC format, returning as-is");
                    return buffer;
                }
            }
        }

        List<byte[]> nalUnits = new ArrayList<>();
        int offset = 0;

        while (offset < buffer.length) {
            int startCodeLen = 0;
            if (offset + 3 < buffer.length &&
                buffer[offset] == 0 && buffer[offset + 1] == 0) {
                if (buffer[offset + 2] == 1) {
                    startCodeLen = 3;
                } else if (offset + 4 < buffer.length &&
                          buffer[offset + 2] == 0 && buffer[offset + 3] == 1) {
                    startCodeLen = 4;
                }
            }

            if (startCodeLen == 0) {
                offset++;
                continue;
            }

            int nalStart = offset + startCodeLen;
            if (nalStart >= buffer.length) {
                break;
            }

            int nalEnd = buffer.length;
            for (int i = nalStart + 1; i + 2 < buffer.length; i++) {
                if (buffer[i] == 0 && buffer[i + 1] == 0 &&
                    (buffer[i + 2] == 1 ||
                     (i + 3 < buffer.length && buffer[i + 2] == 0 && buffer[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            if (nalEnd > nalStart) {
                byte[] nalUnit = new byte[nalEnd - nalStart];
                System.arraycopy(buffer, nalStart, nalUnit, 0, nalUnit.length);
                nalUnits.add(nalUnit);
            }

            offset = nalEnd;
        }

        if (nalUnits.isEmpty()) {
            logError("No NAL units found in Annex-B buffer");
            return null;
        }

        int totalSize = 0;
        for (byte[] nal : nalUnits) {
            totalSize += 4 + nal.length;
        }

        byte[] avccBuffer = new byte[totalSize];
        int writeOffset = 0;

        for (byte[] nal : nalUnits) {
            avccBuffer[writeOffset++] = (byte) ((nal.length >> 24) & 0xFF);
            avccBuffer[writeOffset++] = (byte) ((nal.length >> 16) & 0xFF);
            avccBuffer[writeOffset++] = (byte) ((nal.length >> 8) & 0xFF);
            avccBuffer[writeOffset++] = (byte) (nal.length & 0xFF);

            System.arraycopy(nal, 0, avccBuffer, writeOffset, nal.length);
            writeOffset += nal.length;
        }

        int validationOffset = 0;
        int nalCount = 0;

        while (validationOffset + 4 <= avccBuffer.length) {
            int nalLength = ((avccBuffer[validationOffset] & 0xFF) << 24) |
                           ((avccBuffer[validationOffset + 1] & 0xFF) << 16) |
                           ((avccBuffer[validationOffset + 2] & 0xFF) << 8) |
                           (avccBuffer[validationOffset + 3] & 0xFF);

            if (nalLength == 0 || validationOffset + 4 + nalLength > avccBuffer.length) {
                logError(String.format(
                    "CRITICAL: Invalid AVCC data - NAL#%d length=%d at offset=%d, buffer size=%d",
                    nalCount, nalLength, validationOffset, avccBuffer.length));
                return null;
            }

            nalCount++;
            validationOffset += 4 + nalLength;
        }

        if (validationOffset != avccBuffer.length) {
            logError(String.format(
                "CRITICAL: AVCC buffer size mismatch - consumed=%d, total=%d",
                validationOffset, avccBuffer.length));
            return null;
        }

        return avccBuffer;
    }
}
