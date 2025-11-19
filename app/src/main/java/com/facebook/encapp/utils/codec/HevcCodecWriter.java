package com.facebook.encapp.utils.codec;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * HEVC/H.265 codec writer for HEIF/MP4 containers.
 * Handles hvcC box generation and Annex-B to AVCC conversion.
 */
public class HevcCodecWriter extends BaseCodecWriter {

    public HevcCodecWriter() {
        super(CodecType.HEVC);
    }

    @Override
    public String getMajorBrand(boolean isImage) {
        return isImage ? "mif1" : "hvc1";  // mif1 for HEIF multi-image files
    }

    @Override
    public String[] getCompatibleBrands(boolean isImage) {
        if (isImage) {
            return new String[]{"heic", "mif1", "miaf", "hevc", "heix"};
        } else {
            return new String[]{"hvc1", "hev1", "hevc", "iso6", "mp41", "isom"};
        }
    }

    @Override
    public void writeCodecConfigBox(FileOutputStream file, byte[] codecData, int width, int height)
            throws IOException {
        logError("=== Writing hvcC box ===");
        long position = startBox(file, "hvcC");

        if (codecData != null && codecData.length > 0) {
            logError(String.format("hvcC: Codec config data size = %d bytes", codecData.length));
            logError(String.format("hvcC: First bytes: %02x %02x %02x %02x",
                codecData[0] & 0xFF,
                codecData.length > 1 ? codecData[1] & 0xFF : 0,
                codecData.length > 2 ? codecData[2] & 0xFF : 0,
                codecData.length > 3 ? codecData[3] & 0xFF : 0));

            if (codecData.length >= 23 && codecData[0] == 0x01) {
                logError("hvcC: Data appears to be in HVCC format, writing directly");
                writeBytes(file, codecData);
            } else {
                logError("hvcC: Data appears to be in Annex-B format, parsing...");
                writeHvccFromAnnexB(file, codecData);
            }
        } else {
            logError("hvcC: No codec config data, writing minimal hvcC box");
            writeMinimalHvccBox(file);
        }

        endBox(file, position);
        logError("=== Finished writing hvcC box ===");
    }

    @Override
    public void writeSampleEntryBox(FileOutputStream file, byte[] codecData, int width, int height,
                                   boolean hasCleanAperture, int cleanWidth, int cleanHeight)
            throws IOException {
        long position = startBox(file, "hvc1");

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
        return convertToAVCC(frameData, true);
    }

    private void writeMinimalHvccBox(FileOutputStream file) throws IOException {
        writeInt8(file, 1);
        writeInt8(file, 0x01);
        writeInt32(file, 0x60000000);
        writeInt32(file, 0);
        writeInt32(file, 0);
        writeInt8(file, 30);
        writeInt16(file, 0xF000);
        writeInt8(file, 0xFC);
        writeInt8(file, 0xFD);
        writeInt8(file, 0xF8);
        writeInt8(file, 0xF8);
        writeInt16(file, 0);
        writeInt8(file, 0x03);
        writeInt8(file, 0);
    }

    private void writeHvccFromAnnexB(FileOutputStream file, byte[] annexB) throws IOException {
        logError(String.format("Parsing HEVC Annex-B data: %d bytes", annexB.length));

        StringBuilder hexDump = new StringBuilder();
        hexDump.append("HEVC codec data hex dump:\n");
        for (int i = 0; i < Math.min(64, annexB.length); i++) {
            if (i > 0 && i % 16 == 0) hexDump.append("\n");
            hexDump.append(String.format("%02x ", annexB[i] & 0xFF));
        }
        logError(hexDump.toString());

        List<byte[]> vpsNals = new ArrayList<>();
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

            int nalHeader = annexB[nalStart] & 0xFF;
            int nalType = (nalHeader >> 1) & 0x3F;

            int nalEnd = annexB.length;
            for (int i = nalStart + 1; i + 2 < annexB.length; i++) {
                if (annexB[i] == 0 && annexB[i + 1] == 0 &&
                    (annexB[i + 2] == 1 ||
                     (i + 3 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1))) {
                    nalEnd = i;
                    logError(String.format("Found next start code at position %d", i));
                    break;
                }
            }

            byte[] nalData = new byte[nalEnd - nalStart];
            System.arraycopy(annexB, nalStart, nalData, 0, nalData.length);

            logError(String.format("Found HEVC NAL at offset %d, header=0x%02x, type=%d, size=%d bytes",
                offset, nalHeader, nalType, nalData.length));

            if (nalType == 32) {
                vpsNals.add(nalData);
                logError("Added VPS NAL");
            } else if (nalType == 33) {
                spsNals.add(nalData);
                logError("Added SPS NAL");
            } else if (nalType == 34) {
                ppsNals.add(nalData);
                logError("Added PPS NAL");
            } else {
                logError(String.format("Ignoring NAL type %d", nalType));
            }

            offset = nalEnd;
        }

        logError(String.format("Found %d VPS, %d SPS, %d PPS NAL units",
            vpsNals.size(), spsNals.size(), ppsNals.size()));

        if (!vpsNals.isEmpty() && !spsNals.isEmpty() && !ppsNals.isEmpty()) {
            logError("Writing full hvcC configuration");

            byte[] firstSps = spsNals.get(0);

            int profileSpace = 0;
            int tierFlag = 0;
            int profileIdc = 1; // Main profile (default)
            int levelIdc = 90;  // Level 3.0 (default for HEIC)

            // Profile compatibility flags - transmitted MSB first in HEVC
            // Bit 0 is MSB of byte 0, bit 31 is LSB of byte 3
            // Bit 1 (Main) = bit 6 of byte 0 = 0x40
            // Bit 2 (Main 10) = bit 5 of byte 0 = 0x20
            // Bit 3 (Main Still Picture) = bit 4 of byte 0 = 0x10
            // Combined: 0x70 in byte 0 = 0x70000000 for full 32-bit field
            long profileCompatibilityFlags = 0x70000000L;

            // Constraint indicator flags - should be ALL ZEROS for HEIC
            // Working file shows: 0 0 0 0 0 0
            long constraintIndicatorFlags = 0x000000000000L;

            if (firstSps.length >= 13) {
                profileSpace = (firstSps[2] >> 6) & 0x03;
                tierFlag = (firstSps[2] >> 5) & 0x01;
                profileIdc = firstSps[2] & 0x1F;
                levelIdc = firstSps[12] & 0xFF;

                logError(String.format("Parsed from SPS: profile_space=%d, tier=%d, profile=%d, level=%d",
                    profileSpace, tierFlag, profileIdc, levelIdc));
            }

            writeInt8(file, 1); // configurationVersion
            writeInt8(file, (profileSpace << 6) | (tierFlag << 5) | profileIdc);

            // Write 32-bit profile_compatibility_flags
            writeInt32(file, (int)profileCompatibilityFlags);

            // Write 48-bit constraint_indicator_flags (6 bytes)
            // This needs to be written as 6 individual bytes, not as int32+int16
            writeInt8(file, (int)((constraintIndicatorFlags >> 40) & 0xFF));
            writeInt8(file, (int)((constraintIndicatorFlags >> 32) & 0xFF));
            writeInt8(file, (int)((constraintIndicatorFlags >> 24) & 0xFF));
            writeInt8(file, (int)((constraintIndicatorFlags >> 16) & 0xFF));
            writeInt8(file, (int)((constraintIndicatorFlags >> 8) & 0xFF));
            writeInt8(file, (int)(constraintIndicatorFlags & 0xFF));

            writeInt8(file, levelIdc);
            writeInt16(file, 0xF000);
            writeInt8(file, 0xFC);
            writeInt8(file, 0xFD);
            writeInt8(file, 0xF8);
            writeInt8(file, 0xF8);
            writeInt16(file, 0);
            writeInt8(file, 0x0F);

            logError("Wrote hvcC header: lengthSizeMinusOne=3 (4-byte NAL lengths)");

            writeInt8(file, 3);

            writeInt8(file, 0x80 | 32);
            writeInt16(file, vpsNals.size());
            for (byte[] vps : vpsNals) {
                writeInt16(file, vps.length);
                writeBytes(file, vps);
                logError(String.format("Wrote VPS: %d bytes", vps.length));
            }

            writeInt8(file, 0x80 | 33);
            writeInt16(file, spsNals.size());
            for (byte[] sps : spsNals) {
                writeInt16(file, sps.length);
                writeBytes(file, sps);
                logError(String.format("Wrote SPS: %d bytes", sps.length));
            }

            writeInt8(file, 0x80 | 34);
            writeInt16(file, ppsNals.size());
            for (byte[] pps : ppsNals) {
                writeInt16(file, pps.length);
                writeBytes(file, pps);
                logError(String.format("Wrote PPS: %d bytes", pps.length));
            }

            logError("Successfully wrote hvcC with VPS/SPS/PPS");
        } else {
            logError("No VPS/SPS/PPS found in Annex-B data, writing minimal hvcC");
            writeMinimalHvccBox(file);
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

        log(String.format("Converted Annex-B to AVCC: %d NAL units, %d bytes total",
            nalCount, avccBuffer.length));

        return avccBuffer;
    }
}
