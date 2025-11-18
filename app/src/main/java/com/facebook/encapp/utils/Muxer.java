package com.facebook.encapp.utils;

import android.util.Log;

import com.facebook.encapp.utils.codec.CodecType;
import com.facebook.encapp.utils.codec.CodecWriter;
import com.facebook.encapp.utils.codec.CodecWriterFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java MP4/HEIC muxer for video encoding.
 * Supports multiple codecs: H.264 (AVC), H.265 (HEVC), AV1 and VP9.
 * Can create either MP4 video files or HEIC/AVIF image files (single I-frame).
 * 
 * This is a port of the C++ EncappMuxer to pure Java with multi-codec support.
 */
public class Muxer {
    private static final String TAG = "encapp.muxer";

    private String mFilename;
    private FileOutputStream mFile;
    private boolean mInitialized;
    private boolean mFinalized;
    private int mWidth;
    private int mHeight;
    private int mTimeScale;
    private float mFrameRate;
    private CodecType mCodecType;
    private CodecWriter mCodecWriter;
    private boolean mIsHEIC;
    private int mCleanApertureWidth;
    private int mCleanApertureHeight;
    private boolean mHasCleanAperture;
    private long mMdatOffset;
    private long mMdatSizeOffset;
    private byte[] mCodecConfigData;
    private List<Sample> mSamples;

    public enum ContainerFormat {
        MP4_VIDEO,
        HEIC_IMAGE
    }

    private static class Sample {
        long offset;
        int size;
        long timestamp;
        long duration;
        boolean isKeyFrame;
    }

    /**
     * Create a new Muxer instance.
     * 
     * @param filename Output file path
     * @param width Video width in pixels
     * @param height Video height in pixels
     * @param timeScale Timescale (typically 90000 for video)
     * @param frameRate Frame rate in fps
     */
    public Muxer(String filename, int width, int height, int timeScale, float frameRate) {
        mFilename = filename;
        mWidth = width;
        mHeight = height;
        mTimeScale = timeScale;
        mFrameRate = frameRate;
        mCleanApertureWidth = width;
        mCleanApertureHeight = height;
        mHasCleanAperture = false;
        mInitialized = false;
        mFinalized = false;
        mIsHEIC = false;
        mCodecType = CodecType.HEVC;
        mSamples = new ArrayList<>();
    }

    /**
     * Set container format (MP4 video or HEIC image).
     */
    public void setContainerFormat(ContainerFormat format) {
        mIsHEIC = (format == ContainerFormat.HEIC_IMAGE);
        if (mIsHEIC) {
            Log.d(TAG, "Muxer configured for HEIC single-frame mode");
        }
    }

    /**
     * Set clean aperture (display dimensions) for cropping.
     */
    public void setCleanAperture(int cleanWidth, int cleanHeight) {
        if (mInitialized) {
            Log.w(TAG, "Cannot set clean aperture after initialization");
            return;
        }
        mCleanApertureWidth = cleanWidth;
        mCleanApertureHeight = cleanHeight;
        mHasCleanAperture = (cleanWidth != mWidth || cleanHeight != mHeight);
        Log.d(TAG, String.format("Set clean aperture: %dx%d (display), encoded: %dx%d",
                cleanWidth, cleanHeight, mWidth, mHeight));
    }

    /**
     * Initialize the muxer with codec configuration data (SPS/PPS for H.264, VPS/SPS/PPS for H.265).
     * 
     * @param codecData Codec-specific data (can be Annex-B or AVCC format)
     * @param isHEVC true for HEVC/H.265, false for AVC/H.264
     * @return true on success
     */
    public boolean initialize(byte[] codecData, boolean isHEVC) {
        CodecType codecType = isHEVC ? CodecType.HEVC : CodecType.AVC;
        return initialize(codecData, codecType);
    }

    /**
     * Initialize the muxer with codec configuration data and codec type.
     * 
     * @param codecData Codec-specific data (can be Annex-B or native format)
     * @param codecType The codec type to use
     * @return true on success
     */
    public boolean initialize(byte[] codecData, CodecType codecType) {
        if (mInitialized) {
            Log.e(TAG, "Muxer already initialized");
            return false;
        }

        mCodecType = codecType;
        mCodecWriter = CodecWriterFactory.createWriter(codecType);

        try {
            mFile = new FileOutputStream(mFilename);
            Log.i(TAG, String.format("Created muxer for file: %s (codec: %s)", 
                mFilename, codecType.name()));
        } catch (IOException e) {
            Log.e(TAG, "Failed to open output file: " + mFilename, e);
            return false;
        }

        // Store codec configuration data
        if (codecData != null && codecData.length > 0) {
            mCodecConfigData = codecData.clone();
            Log.i(TAG, String.format("Codec config data size: %d bytes", codecData.length));
        } else {
            Log.w(TAG, "No codec configuration data provided");
        }

        try {
            // Write ftyp box
            writeFtypBox();

            // Write mdat box header (size will be updated in finalize)
            mMdatOffset = getFilePosition();
            writeInt32(0); // Size placeholder
            mMdatSizeOffset = getFilePosition();
            writeString("mdat");

        } catch (IOException e) {
            Log.e(TAG, "Failed to write initial boxes", e);
            return false;
        }

        mInitialized = true;
        return true;
    }

    /**
     * Initialize the muxer with codec auto-detection from MIME type.
     * 
     * @param codecData Codec-specific data
     * @param mimeType MIME type string (e.g., "video/hevc", "video/avc", "video/av1")
     * @return true on success
     */
    public boolean initializeFromMimeType(byte[] codecData, String mimeType) {
        Log.d(TAG, "MIME type: " + mimeType);
        CodecType codecType = CodecType.fromMimeType(mimeType);
        Log.d(TAG, "Auto-detected codec from MIME type '" + mimeType + "': " + codecType.name());
        boolean result = initialize(codecData, codecType);
        return result;
    }

    /**
     * Add a frame to the muxer.
     * 
     * @param frameData Frame data (can be Annex-B or AVCC format, will be converted to AVCC)
     * @param presentationTimeUs Presentation timestamp in microseconds
     * @param isKeyFrame true if this is an I-frame/keyframe
     * @return true on success
     */
    public boolean addFrame(byte[] frameData, long presentationTimeUs, boolean isKeyFrame) {
        if (!mInitialized) {
            Log.e(TAG, "Muxer not initialized");
            return false;
        }

        if (mFinalized) {
            Log.e(TAG, "Muxer already finalized");
            return false;
        }

        // For HEIC images, only accept the first keyframe
        if (mIsHEIC && !mSamples.isEmpty()) {
            Log.d(TAG, "HEIC mode: ignoring additional frame (already have first keyframe)");
            return true; // Return success but don't add the frame
        }

        if (frameData == null || frameData.length == 0) {
            Log.i(TAG, "Zero-size frame received, treating as EOS marker");
            Sample sample = new Sample();
            try {
                sample.offset = getFilePosition();
            } catch (IOException e) {
                Log.e(TAG, "Failed to get file position", e);
                return false;
            }
            sample.size = 0;
            sample.timestamp = presentationTimeUs * mTimeScale / 1000000;
            sample.isKeyFrame = isKeyFrame;
            sample.duration = (long) (mTimeScale / mFrameRate);
            mSamples.add(sample);
            return true;
        }

        try {
            // Convert to AVCC format if needed using codec writer
            byte[] avccData = mCodecWriter != null ? 
                mCodecWriter.convertFrameData(frameData) : frameData;
            if (avccData == null || avccData.length == 0) {
                Log.e(TAG, "AVCC conversion failed");
                return false;
            }

            // Record sample information
            Sample sample = new Sample();
            sample.offset = getFilePosition();
            sample.size = avccData.length;
            sample.timestamp = presentationTimeUs * mTimeScale / 1000000;
            sample.isKeyFrame = isKeyFrame;

            Log.e(TAG, String.format("Frame %d: writing %d bytes at offset %d, keyframe=%b",
                mSamples.size(), avccData.length, sample.offset, isKeyFrame));

            // Calculate duration based on previous sample
            if (!mSamples.isEmpty()) {
                Sample prevSample = mSamples.get(mSamples.size() - 1);
                long prevTimestamp = prevSample.timestamp;
                if (sample.timestamp > prevTimestamp) {
                    prevSample.duration = sample.timestamp - prevTimestamp;
                } else {
                    prevSample.duration = (long) (mTimeScale / mFrameRate);
                    Log.w(TAG, "Timestamp out of order! Using default duration");
                }
            }

            // For first sample, duration will be set when next sample is added
            sample.duration = 0;

            // Write frame data
            long beforeWrite = getFilePosition();
            mFile.write(avccData);
            long afterWrite = getFilePosition();
            
            Log.e(TAG, String.format("Frame %d written: offset=%d, expected_size=%d, actual_written=%d",
                mSamples.size(), sample.offset, avccData.length, afterWrite - beforeWrite));

            // Add sample to list
            mSamples.add(sample);

            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to write frame", e);
            return false;
        }
    }

    /**
     * Finalize the muxer and write the moov box (for video) or meta box (for HEIC images).
     * @return true on success
     */
    public boolean finalizeMux() {
        Log.i(TAG, "Finalizing " + (mIsHEIC ? "HEIC" : "MP4") + " file: " + mFilename);

        if (!mInitialized) {
            Log.e(TAG, "Muxer not initialized");
            return false;
        }

        if (mFinalized) {
            Log.e(TAG, "Muxer already finalized");
            return false;
        }

        if (mSamples.isEmpty()) {
            Log.e(TAG, "No samples added to the file");
            return false;
        }

        try {
            // Set duration for last sample
            Sample lastSample = mSamples.get(mSamples.size() - 1);
            lastSample.duration = (long) (mTimeScale / mFrameRate);

            // Calculate mdat size
            long mdatSize = 8; // Box header
            for (Sample sample : mSamples) {
                mdatSize += sample.size;
            }

            // Update mdat size
            long currentPos = getFilePosition();
            seek(mMdatOffset);
            writeInt32((int) mdatSize);
            seek(currentPos);

            // Write meta box (for HEIC) or moov box (for video)
            if (mIsHEIC) {
                writeMetaBox(); // HEIC uses item-based structure
            } else {
                writeMoovBox(); // MP4 uses track-based structure
            }

            // Log statistics
            int keyFrameCount = 0;
            long totalDuration = 0;
            long totalSize = 0;
            for (Sample sample : mSamples) {
                if (sample.isKeyFrame) keyFrameCount++;
                totalDuration += sample.duration;
                totalSize += sample.size;
            }

            Log.i(TAG, "File statistics:");
            Log.i(TAG, "  - Total samples: " + mSamples.size());
            Log.i(TAG, "  - Key frames: " + keyFrameCount);
            Log.i(TAG, "  - Total duration: " + totalDuration + " timeScale units (" +
                    (totalDuration * 1000 / mTimeScale) + " ms)");
            Log.i(TAG, "  - Total data size: " + totalSize + " bytes");
            if (totalDuration > 0) {
                Log.i(TAG, "  - Average bitrate: " + (totalSize * 8 * mTimeScale / totalDuration) + " bps");
            }

            mFinalized = true;
            Log.i(TAG, (mIsHEIC ? "HEIC" : "MP4") + " file finalized successfully: " + mFilename);
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to finalize", e);
            return false;
        }
    }

    /**
     * Close the muxer and release resources.
     */
    public void close() {
        if (!mFinalized && mInitialized) {
            Log.w(TAG, "MP4 file not finalized, finalizing now");
            finalizeMux();
        }

        if (mFile != null) {
            try {
                mFile.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing file", e);
            }
            mFile = null;
        }
    }

    // ========== Helper Methods ==========

    private static int fourcc(String s) {
        if (s.length() != 4) {
            throw new IllegalArgumentException("FourCC must be 4 characters");
        }
        return (s.charAt(0) & 0xFF) |
               ((s.charAt(1) & 0xFF) << 8) |
               ((s.charAt(2) & 0xFF) << 16) |
               ((s.charAt(3) & 0xFF) << 24);
    }

    private long getFilePosition() throws IOException {
        return mFile.getChannel().position();
    }

    private void seek(long position) throws IOException {
        mFile.getChannel().position(position);
    }

    private void writeInt8(int value) throws IOException {
        mFile.write(value & 0xFF);
    }

    private void writeInt16(int value) throws IOException {
        mFile.write((value >> 8) & 0xFF);
        mFile.write(value & 0xFF);
    }

    private void writeInt32(int value) throws IOException {
        mFile.write((value >> 24) & 0xFF);
        mFile.write((value >> 16) & 0xFF);
        mFile.write((value >> 8) & 0xFF);
        mFile.write(value & 0xFF);
    }

    private void writeInt64(long value) throws IOException {
        mFile.write((int) ((value >> 56) & 0xFF));
        mFile.write((int) ((value >> 48) & 0xFF));
        mFile.write((int) ((value >> 40) & 0xFF));
        mFile.write((int) ((value >> 32) & 0xFF));
        mFile.write((int) ((value >> 24) & 0xFF));
        mFile.write((int) ((value >> 16) & 0xFF));
        mFile.write((int) ((value >> 8) & 0xFF));
        mFile.write((int) (value & 0xFF));
    }

    private void writeString(String s) throws IOException {
        mFile.write(s.getBytes("US-ASCII"));
    }

    private void writeBytes(byte[] data) throws IOException {
        mFile.write(data);
    }

    private long startBox(String type) throws IOException {
        long position = getFilePosition();
        writeInt32(0); // Size placeholder
        writeString(type);
        return position;
    }

    private void endBox(long position) throws IOException {
        long currentPos = getFilePosition();
        int size = (int) (currentPos - position);
        seek(position);
        writeInt32(size);
        seek(currentPos);
    }

    // ========== Box Writing Methods ==========

    private void writeFtypBox() throws IOException {
        long position = startBox("ftyp");

        if (mCodecWriter != null) {
            String majorBrand = mCodecWriter.getMajorBrand(mIsHEIC);
            String[] compatibleBrands = mCodecWriter.getCompatibleBrands(mIsHEIC);
            
            writeString(majorBrand);
            writeInt32(0); // Minor version
            
            for (String brand : compatibleBrands) {
                writeString(brand);
            }
            
            if (!mIsHEIC) {
                writeString("mp41");
                writeString("isom");
            }
        } else {
            Log.w(TAG, "No codec writer available, writing default ftyp");
            writeString("isom");
            writeInt32(0);
            writeString("isom");
            writeString("mp41");
        }

        endBox(position);
    }

    private void writeMoovBox() throws IOException {
        long position = startBox("moov");
        writeMvhdBox();
        writeTrakBox();
        endBox(position);
    }

    private void writeMvhdBox() throws IOException {
        long position = startBox("mvhd");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(0); // Creation time
        writeInt32(0); // Modification time
        writeInt32(mTimeScale); // Time scale

        // Duration
        long totalDuration = 0;
        for (Sample sample : mSamples) {
            totalDuration += sample.duration;
        }
        writeInt32((int) totalDuration);

        writeInt32(0x00010000); // Preferred rate (1.0)
        writeInt16(0x0100);     // Preferred volume (1.0)

        // Reserved
        for (int i = 0; i < 10; i++) {
            writeInt8(0);
        }

        // Unity matrix
        writeInt32(0x00010000);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x00010000);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x40000000);

        // Pre-defined
        for (int i = 0; i < 6; i++) {
            writeInt32(0);
        }

        writeInt32(2); // Next track ID

        endBox(position);
    }

    private void writeTrakBox() throws IOException {
        long position = startBox("trak");
        writeTkhdBox();
        writeMdiaBox();
        endBox(position);
    }

    private void writeTkhdBox() throws IOException {
        long position = startBox("tkhd");

        writeInt8(0);      // Version
        writeInt8(0);      // Flags
        writeInt16(0x0007); // Track enabled

        writeInt32(0); // Creation time
        writeInt32(0); // Modification time
        writeInt32(1); // Track ID
        writeInt32(0); // Reserved

        // Duration
        long totalDuration = 0;
        for (Sample sample : mSamples) {
            totalDuration += sample.duration;
        }
        writeInt32((int) totalDuration);

        writeInt32(0); // Reserved
        writeInt32(0); // Reserved

        writeInt16(0); // Layer
        writeInt16(0); // Alternate group
        writeInt16(0); // Volume

        writeInt16(0); // Reserved

        // Unity matrix
        writeInt32(0x00010000);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x00010000);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x40000000);

        writeInt32(mWidth << 16);  // Width
        writeInt32(mHeight << 16); // Height

        endBox(position);
    }

    private void writeMdiaBox() throws IOException {
        long position = startBox("mdia");
        writeMdhdBox();
        writeHdlrBox();
        writeMinfBox();
        endBox(position);
    }

    private void writeMdhdBox() throws IOException {
        long position = startBox("mdhd");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(0); // Creation time
        writeInt32(0); // Modification time
        writeInt32(mTimeScale); // Time scale

        // Duration
        long totalDuration = 0;
        for (Sample sample : mSamples) {
            totalDuration += sample.duration;
        }
        writeInt32((int) totalDuration);

        writeInt16(0x55c4); // Language (undefined)
        writeInt16(0);      // Quality

        endBox(position);
    }

    private void writeHdlrBox() throws IOException {
        long position = startBox("hdlr");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(0); // Pre-defined
        writeString("vide"); // Handler type (video)

        writeInt32(0); // Reserved
        writeInt32(0);
        writeInt32(0);

        writeString("VideoHandler");
        writeInt8(0); // Null terminator

        endBox(position);
    }

    private void writeMinfBox() throws IOException {
        long position = startBox("minf");
        writeVmhdBox();
        writeDinfBox();
        writeStblBox();
        endBox(position);
    }

    private void writeVmhdBox() throws IOException {
        long position = startBox("vmhd");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(1);

        writeInt16(0); // Graphics mode
        writeInt16(0); // Opcolor
        writeInt16(0);
        writeInt16(0);

        endBox(position);
    }

    private void writeDinfBox() throws IOException {
        long position = startBox("dinf");
        writeDrefBox();
        endBox(position);
    }

    private void writeDrefBox() throws IOException {
        long position = startBox("dref");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(1); // Entry count

        // URL box
        long urlPosition = startBox("url ");
        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(1); // Data is in this file
        endBox(urlPosition);

        endBox(position);
    }

    private void writeStblBox() throws IOException {
        long position = startBox("stbl");
        writeStsdBox();
        writeSttsBox();
        writeStssBox();
        writeStscBox();
        writeStsZBox();
        writeStcoBox();
        endBox(position);
    }

    private void writeStsdBox() throws IOException {
        long position = startBox("stsd");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(1); // Entry count

        if (mCodecWriter != null) {
            mCodecWriter.writeSampleEntryBox(mFile, mCodecConfigData, mWidth, mHeight,
                mHasCleanAperture, mCleanApertureWidth, mCleanApertureHeight);
        } else {
            Log.w(TAG, "No codec writer available for sample entry");
        }

        endBox(position);
    }

    private void writeAvc1Box() throws IOException {
        long position = startBox("avc1");

        // Reserved
        for (int i = 0; i < 6; i++) {
            writeInt8(0);
        }

        writeInt16(1); // Data reference index
        writeInt16(0); // Pre-defined
        writeInt16(0); // Reserved

        // Pre-defined
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);

        writeInt16(mWidth);  // Width
        writeInt16(mHeight); // Height

        writeInt32(0x00480000); // Horizontal resolution (72 dpi)
        writeInt32(0x00480000); // Vertical resolution (72 dpi)

        writeInt32(0); // Reserved
        writeInt16(1); // Frame count

        // Compressor name (32 bytes)
        writeInt8(0);
        for (int i = 0; i < 31; i++) {
            writeInt8(0);
        }

        writeInt16(0x0018); // Depth (24 bits)
        writeInt16(-1);     // Pre-defined

        writeAvccBox();

        if (mHasCleanAperture) {
            writeClapBox();
        }

        endBox(position);
    }

    private void writeHvc1Box() throws IOException {
        long position = startBox("hvc1");

        // Reserved
        for (int i = 0; i < 6; i++) {
            writeInt8(0);
        }

        writeInt16(1); // Data reference index
        writeInt16(0); // Pre-defined
        writeInt16(0); // Reserved

        // Pre-defined
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);

        writeInt16(mWidth);  // Width
        writeInt16(mHeight); // Height

        writeInt32(0x00480000); // Horizontal resolution (72 dpi)
        writeInt32(0x00480000); // Vertical resolution (72 dpi)

        writeInt32(0); // Reserved
        writeInt16(1); // Frame count

        // Compressor name (32 bytes)
        writeInt8(0);
        for (int i = 0; i < 31; i++) {
            writeInt8(0);
        }

        writeInt16(0x0018); // Depth (24 bits)
        writeInt16(-1);     // Pre-defined

        writeHvccBox();

        endBox(position);
    }

    private void writeAvccBox() throws IOException {
        long position = startBox("avcC");

        if (mCodecConfigData != null && mCodecConfigData.length > 0) {
            Log.d(TAG, String.format("Writing avcC box with %d bytes of codec config data", mCodecConfigData.length));
            Log.d(TAG, String.format("First bytes: %02x %02x %02x %02x", 
                mCodecConfigData[0] & 0xFF, 
                mCodecConfigData.length > 1 ? mCodecConfigData[1] & 0xFF : 0,
                mCodecConfigData.length > 2 ? mCodecConfigData[2] & 0xFF : 0,
                mCodecConfigData.length > 3 ? mCodecConfigData[3] & 0xFF : 0));
            
            // Check if already in AVCC format (starts with 0x01 = configuration version)
            // AVCC format: version(1) + profile(1) + compatibility(1) + level(1) + lengthSizeMinusOne(1) + ...
            if (mCodecConfigData.length >= 7 && mCodecConfigData[0] == 0x01) {
                Log.d(TAG, "Codec data appears to be in AVCC format, writing directly");
                writeBytes(mCodecConfigData);
            } else {
                // Parse Annex-B and create AVCC config
                Log.d(TAG, "Codec data appears to be in Annex-B format, parsing...");
                writeAvccFromAnnexB(mCodecConfigData);
            }
        } else {
            Log.w(TAG, "No codec config data available, writing minimal avcC box");
            writeMinimalAvccBox();
        }

        endBox(position);
    }

    private void writeMinimalAvccBox() throws IOException {
        writeInt8(1);    // Configuration version
        writeInt8(0x42); // Profile (Baseline)
        writeInt8(0x80); // Profile compatibility
        writeInt8(0x1E); // Level (3.0)
        writeInt8(0xFF); // Length size minus one (4 bytes)
        writeInt8(0xE0); // Number of SPS (0)
        writeInt8(0x00); // Number of PPS (0)
    }

    private void writeHvccBox() throws IOException {
        long position = startBox("hvcC");

        if (mCodecConfigData != null && mCodecConfigData.length > 0) {
            // Check if already in HVCC format (starts with 0x01 = configuration version)
            if (mCodecConfigData.length >= 23 && mCodecConfigData[0] == 0x01) {
                writeBytes(mCodecConfigData);
            } else {
                writeHvccFromAnnexB(mCodecConfigData);
            }
        } else {
            Log.e(TAG, "hvcC: No codec config data, writing minimal hvcC box");
            writeMinimalHvccBox();
        }

        endBox(position);
    }

    private void writeMinimalHvccBox() throws IOException {
        writeInt8(1);    // Configuration version
        writeInt8(0x01); // Profile space (0) | tier flag (0) | profile (1 = Main)
        writeInt32(0x60000000); // Profile compatibility flags
        writeInt32(0);   // Constraint indicator flags high
        writeInt32(0);   // Constraint indicator flags low
        writeInt8(30);   // Level (3.0)
        writeInt16(0xF000); // Min spatial segmentation
        writeInt8(0xFC); // Parallelism type
        writeInt8(0xFD); // Chroma format (1 = 4:2:0)
        writeInt8(0xF8); // Bit depth luma minus 8 (0 = 8 bits)
        writeInt8(0xF8); // Bit depth chroma minus 8 (0 = 8 bits)
        writeInt16(0);   // Average frame rate (0 = unspecified)
        writeInt8(0x03); // lengthSizeMinusOne (3 = 4 bytes)
        writeInt8(0);    // Number of arrays (0)
    }

    private void writeSttsBox() throws IOException {
        long position = startBox("stts");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(mSamples.size()); // Entry count

        for (Sample sample : mSamples) {
            writeInt32(1); // Sample count
            writeInt32((int) sample.duration);
        }

        endBox(position);
    }

    private void writeStssBox() throws IOException {
        // Count key frames
        int keyFrameCount = 0;
        for (Sample sample : mSamples) {
            if (sample.isKeyFrame) {
                keyFrameCount++;
            }
        }

        if (keyFrameCount == 0) {
            return; // Don't write box if no key frames
        }

        long position = startBox("stss");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(keyFrameCount); // Entry count

        int sampleNumber = 1;
        for (Sample sample : mSamples) {
            if (sample.isKeyFrame) {
                writeInt32(sampleNumber);
            }
            sampleNumber++;
        }

        endBox(position);
    }

    private void writeStscBox() throws IOException {
        long position = startBox("stsc");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        // Write one sample per chunk for better compatibility
        writeInt32(1); // Entry count

        writeInt32(1); // First chunk
        writeInt32(1); // Samples per chunk (1 sample per chunk)
        writeInt32(1); // Sample description index

        endBox(position);
    }

    private void writeStsZBox() throws IOException {
        long position = startBox("stsz");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(0); // Sample size (0 = different sizes)
        writeInt32(mSamples.size()); // Sample count

        for (Sample sample : mSamples) {
            writeInt32(sample.size);
        }

        endBox(position);
    }

    private void writeStcoBox() throws IOException {
        long position = startBox("stco");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        // Write one chunk offset per sample (one sample per chunk)
        writeInt32(mSamples.size()); // Entry count = number of samples

        for (Sample sample : mSamples) {
            writeInt32((int) sample.offset); // Each sample's offset in the file
        }

        endBox(position);
    }

    private void writeClapBox() throws IOException {
        long position = startBox("clap");

        // Clean aperture width (fixed-point 32.32)
        writeInt32(mCleanApertureWidth << 16);
        writeInt32(0);

        // Clean aperture height (fixed-point 32.32)
        writeInt32(mCleanApertureHeight << 16);
        writeInt32(0);

        // Horizontal offset (fixed-point 32.32)
        int hOffset = (mWidth - mCleanApertureWidth) / 2;
        writeInt32(hOffset << 16);
        writeInt32(0);

        // Vertical offset (fixed-point 32.32)
        int vOffset = (mHeight - mCleanApertureHeight) / 2;
        writeInt32(vOffset << 16);
        writeInt32(0);

        endBox(position);
    }

    // ========== HEIC-specific boxes (item-based structure) ==========

    /**
     * Write meta box for HEIC image files.
     * HEIC uses item-based structure, not track-based like MP4 video.
     */
    private void writeMetaBox() throws IOException {
        long position = startBox("meta");

        writeInt8(0); // Version
        writeInt8(0); // Flags
        writeInt16(0);

        // Handler box - declares this is a picture handler
        writeHdlrBoxForMeta();

        // Primary item box - declares which item is the primary image
        writePitmBox();

        // Item location box - where items are stored in mdat
        writeIlocBox();

        // Item information box - metadata about items
        writeIinfBox();

        // Item properties box - image properties (dimensions, codec config)
        writeIprpBox();

        endBox(position);
    }

    private void writeHdlrBoxForMeta() throws IOException {
        long position = startBox("hdlr");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(0); // Pre-defined
        writeString("pict"); // Handler type (picture)

        writeInt32(0); // Reserved
        writeInt32(0);
        writeInt32(0);

        writeString(""); // Name (empty)
        writeInt8(0); // Null terminator

        endBox(position);
    }

    private void writePitmBox() throws IOException {
        long position = startBox("pitm");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt16(1); // Item ID of primary item (first and only item)

        endBox(position);
    }

    private void writeIlocBox() throws IOException {
        long position = startBox("iloc");

        writeInt8(0);  // Version 0
        writeInt8(0);  // Flags
        writeInt16(0);

        // offset_size(4 bits) | length_size(4 bits) = 0x44 (both 4 bytes)
        writeInt8(0x44);
        
        // base_offset_size(4 bits) | reserved(4 bits) = 0x00
        writeInt8(0x00);

        writeInt16(1); // Item count (1 item - the image)

        // Item 1 - references the HEVC image data in mdat
        // Version 0 structure: item_ID, data_reference_index, [base_offset if size>0], extent_count, extents
        writeInt16(1); // Item ID
        writeInt16(0); // Data reference index (0 = same file)
        
        // NO base_offset here because base_offset_size = 0
        
        // extent_count
        writeInt16(1); // One extent (the entire image data)

        // For HEIC: the "item" is all the sample data (should be just one I-frame)
        // Calculate total size of all samples
        long totalDataSize = 0;
        long firstOffset = 0;
        
        if (!mSamples.isEmpty()) {
            firstOffset = mSamples.get(0).offset;
            for (Sample sample : mSamples) {
                totalDataSize += sample.size;
            }
            
        }

        // Extent: offset (from start of file) and length (total data size)
        // These are 4 bytes each because offset_size=4 and length_size=4
        writeInt32((int) firstOffset);     // Extent offset (start of mdat data)
        writeInt32((int) totalDataSize);   // Extent length (all image data)

        endBox(position);
    }

    private void writeIinfBox() throws IOException {
        long position = startBox("iinf");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt16(1); // Entry count (1 item)

        // Item info entry
        writeInfeBox();

        endBox(position);
    }

    private void writeInfeBox() throws IOException {
        long position = startBox("infe");

        writeInt8(2);  // Version 2
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt16(1); // Item ID
        writeInt16(0); // Item protection index (0 = no protection)
        
        // Write codec-specific item type (hvc1, av01, avc1, etc.)
        String itemType = mCodecWriter != null ? mCodecWriter.getItemType() : "hvc1";
        writeString(itemType);
        
        writeString(""); // Item name (empty)
        writeInt8(0); // Null terminator

        endBox(position);
    }

    private void writeIprpBox() throws IOException {
        long position = startBox("iprp");

        // Item property container
        writeIpcoBox();

        // Item property association
        writeIpmaBox();

        endBox(position);
    }

    private void writeIpcoBox() throws IOException {
        long position = startBox("ipco");

        // Property 1: Image spatial extents (ispe) - describes image dimensions
        writeIspeBox();

        // Property 2: Pixel information (pixi) - describes bit depth
        writePixiBox();

        // Property 3: Color information (colr) - CRITICAL for proper color display
        writeColrBox();

        // Property 4: Codec configuration (hvcC, avcC, av1C, etc.) - codec parameters
        if (mCodecWriter != null) {
            mCodecWriter.writeCodecConfigBox(mFile, mCodecConfigData, mWidth, mHeight);
        } else {
            Log.w(TAG, "No codec writer available for HEIC codec config");
        }

        endBox(position);
    }

    private void writePixiBox() throws IOException {
        long position = startBox("pixi");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt8(3); // Number of channels (Y, U, V for 4:2:0)
        
        // Bit depths for each channel (typically 8 for standard HEVC)
        writeInt8(8); // Y channel bit depth
        writeInt8(8); // U channel bit depth
        writeInt8(8); // V channel bit depth

        endBox(position);
    }

    private void writeColrBox() throws IOException {
        long position = startBox("colr");

        // Color type: 'nclx' for uncompressed YUV
        writeString("nclx");

        // Color primaries: 1 = BT.709 (standard HD video)
        writeInt16(1);

        // Transfer characteristics: 1 = BT.709 (standard HD video)
        writeInt16(1);

        // Matrix coefficients: 1 = BT.709 (YUV to RGB conversion)
        writeInt16(1);

        // Full range flag: bit 7 = full_range_flag
        // 0x00 = limited range (16-235), which is standard for video
        // 0x80 = full range (0-255)
        // Most HEVC encoders use limited range by default
        writeInt8(0x00); // Limited range (video range)

        endBox(position);
    }

    private void writeIspeBox() throws IOException {
        long position = startBox("ispe");

        writeInt8(0);  // Version
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(mWidth);  // Image width
        writeInt32(mHeight); // Image height

        endBox(position);
    }

    private void writeIpmaBox() throws IOException {
        long position = startBox("ipma");

        writeInt8(0);  // Version 0
        writeInt8(0);  // Flags
        writeInt16(0);

        writeInt32(1); // Entry count (1 item)

        // Association for item 1
        writeInt16(1); // Item ID
        writeInt8(4);  // Association count (ispe + pixi + colr + hvcC)

        // Property associations: index with bit 7 = essential flag
        // Bit 7 set (0x80) means essential property
        writeInt8(0x81); // Property index 1 (ispe) | essential flag
        writeInt8(0x82); // Property index 2 (pixi) | essential flag
        writeInt8(0x83); // Property index 3 (colr) | essential flag
        writeInt8(0x84); // Property index 4 (hvcC) | essential flag

        endBox(position);
    }

    // ========== Codec Config Parsing ==========

    private void writeAvccFromAnnexB(byte[] annexB) throws IOException {
        // Log first 64 bytes as hex for debugging
        StringBuilder hexDump = new StringBuilder();
        for (int i = 0; i < Math.min(64, annexB.length); i++) {
            if (i > 0 && i % 16 == 0) hexDump.append("\n");
            hexDump.append(String.format("%02x ", annexB[i] & 0xFF));
        }

        // Find SPS and PPS NAL units
        List<byte[]> spsNals = new ArrayList<>();
        List<byte[]> ppsNals = new ArrayList<>();

        int offset = 0;
        while (offset < annexB.length) {
            // Find start code
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

            // Get NAL unit type
            int nalType = annexB[nalStart] & 0x1F;

            // Find next start code
            int nalEnd = annexB.length;
            for (int i = nalStart + 1; i + 2 < annexB.length; i++) {
                if (annexB[i] == 0 && annexB[i + 1] == 0 &&
                    (annexB[i + 2] == 1 || 
                     (i + 3 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            byte[] nalData = new byte[nalEnd - nalStart];
            System.arraycopy(annexB, nalStart, nalData, 0, nalData.length);

            if (nalType == 7) { // SPS
                spsNals.add(nalData);
                Log.d(TAG, "Added SPS NAL");
            } else if (nalType == 8) { // PPS
                ppsNals.add(nalData);
                Log.d(TAG, "Added PPS NAL");
            }

            offset = nalEnd;
        }

        if (!spsNals.isEmpty() && !ppsNals.isEmpty()) {
            // Get profile/level from first SPS
            byte[] sps = spsNals.get(0);
            byte profile = sps.length > 1 ? sps[1] : 0x42;
            byte compatibility = sps.length > 2 ? sps[2] : (byte) 0x80;
            byte level = sps.length > 3 ? sps[3] : 0x1E;

            writeInt8(1); // Configuration version
            writeInt8(profile & 0xFF);
            writeInt8(compatibility & 0xFF);
            writeInt8(level & 0xFF);
            writeInt8(0xFF); // Length size minus one (4 bytes)

            // Write SPS
            writeInt8(0xE0 | spsNals.size());
            for (byte[] spsNal : spsNals) {
                writeInt16(spsNal.length);
                writeBytes(spsNal);
            }

            // Write PPS
            writeInt8(ppsNals.size());
            for (byte[] ppsNal : ppsNals) {
                writeInt16(ppsNal.length);
                writeBytes(ppsNal);
            }
        } else {
            Log.w(TAG, "No SPS/PPS found in Annex-B data, writing minimal avcC");
            writeMinimalAvccBox();
        }
    }

    private void writeHvccFromAnnexB(byte[] annexB) throws IOException {
        // Log first 64 bytes as hex for debugging
        StringBuilder hexDump = new StringBuilder();
        hexDump.append("HEVC codec data hex dump:\n");
        for (int i = 0; i < Math.min(64, annexB.length); i++) {
            if (i > 0 && i % 16 == 0) hexDump.append("\n");
            hexDump.append(String.format("%02x ", annexB[i] & 0xFF));
        }

        // Find VPS, SPS, and PPS NAL units
        List<byte[]> vpsNals = new ArrayList<>();
        List<byte[]> spsNals = new ArrayList<>();
        List<byte[]> ppsNals = new ArrayList<>();

        int offset = 0;
        while (offset < annexB.length) {
            // Find start code
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

            // Get HEVC NAL unit type (bits 1-6 of first byte after start code)
            int nalHeader = annexB[nalStart] & 0xFF;
            int nalType = (nalHeader >> 1) & 0x3F;

            // Find next start code
            int nalEnd = annexB.length;
            for (int i = nalStart + 1; i + 2 < annexB.length; i++) {
                if (annexB[i] == 0 && annexB[i + 1] == 0 &&
                    (annexB[i + 2] == 1 || 
                     (i + 3 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            byte[] nalData = new byte[nalEnd - nalStart];
            System.arraycopy(annexB, nalStart, nalData, 0, nalData.length);


            if (nalType == 32) { // VPS
                vpsNals.add(nalData);
            } else if (nalType == 33) { // SPS
                spsNals.add(nalData);
            } else if (nalType == 34) { // PPS
                ppsNals.add(nalData);
            } else {
                Log.e(TAG, String.format("Ignoring NAL type %d", nalType));
            }

            offset = nalEnd;
        }

        if (!vpsNals.isEmpty() && !spsNals.isEmpty() && !ppsNals.isEmpty()) {
            // Parse values from actual SPS
            byte[] firstSps = spsNals.get(0);
            
            // Parse profile_tier_level from SPS (starts at byte 0)
            // Byte 0: Forbidden_zero_bit(1) + nal_unit_type(6) + nuh_layer_id(6) + nuh_temporal_id_plus1(3)
            // Byte 2+: profile_tier_level structure
            int profileSpace = 0;
            int tierFlag = 0;
            int profileIdc = 1; // Default Main
            int levelIdc = 93; // Default 3.1
            
            if (firstSps.length >= 13) {
                // Skip NAL header (2 bytes), then read profile_tier_level
                // Byte 2: general_profile_space(2) + general_tier_flag(1) + general_profile_idc(5)
                profileSpace = (firstSps[2] >> 6) & 0x03;
                tierFlag = (firstSps[2] >> 5) & 0x01;
                profileIdc = firstSps[2] & 0x1F;
                
                // Byte 12: general_level_idc
                levelIdc = firstSps[12] & 0xFF;
            }
            
            writeInt8(1); // configurationVersion = 1
            
            // Byte 1: general_profile_space(2) + general_tier_flag(1) + general_profile_idc(5)
            writeInt8((profileSpace << 6) | (tierFlag << 5) | profileIdc);
            
            // Bytes 2-5: general_profile_compatibility_flags (32 bits)
            writeInt32(0x60000000);
            
            // Bytes 6-11: general_constraint_indicator_flags (48 bits)
            writeInt32(0);   // Upper 32 bits
            writeInt16(0);   // Lower 16 bits
            
            // Byte 12: general_level_idc
            writeInt8(levelIdc);
            
            // Bytes 13-14: reserved(4 bits '1111') + min_spatial_segmentation_idc(12 bits)
            writeInt16(0xF000);
            
            // Byte 15: reserved(6 bits '111111') + parallelismType(2 bits)
            writeInt8(0xFC);
            
            // Byte 16: reserved(6 bits '111111') + chromaFormat(2 bits)
            // chromaFormat: 0=monochrome, 1=4:2:0, 2=4:2:2, 3=4:4:4
            // For YUV 4:2:0: 0xFC | 0x01 = 0xFD
            writeInt8(0xFD); // 11111101 = reserved(111111) + chroma_format(01 = 4:2:0 YUV)
            
            // Byte 17: reserved(5 bits '11111') + bitDepthLumaMinus8(3 bits)
            // For 8-bit: bitDepthLumaMinus8 = 0, so: 11111000 = 0xF8
            writeInt8(0xF8); // 8-bit luma
            
            // Byte 18: reserved(5 bits '11111') + bitDepthChromaMinus8(3 bits)
            // For 8-bit: bitDepthChromaMinus8 = 0, so: 11111000 = 0xF8
            writeInt8(0xF8); // 8-bit chroma
            
            // Bytes 19-20: avgFrameRate (0 = unspecified)
            writeInt16(0);
            
            // Byte 21: constantFrameRate(2) + numTemporalLayers(3) + temporalIdNested(1) + lengthSizeMinusOne(2)
            // We use 4-byte NAL lengths, so lengthSizeMinusOne = 3 (bits 1-0 = 11)
            // constantFrameRate = 0 (bits 7-6 = 00)
            // numTemporalLayers = 1 (bits 5-3 = 001)
            // temporalIdNested = 1 (bit 2 = 1)
            // Result: 00 001 1 11 = 0x0F
            writeInt8(0x0F);
            
            // Byte 22: numOfArrays
            writeInt8(3); // VPS, SPS, PPS

            // Write VPS array
            writeInt8(0x80 | 32); // array_completeness | NAL_unit_type (VPS)
            writeInt16(vpsNals.size());
            for (byte[] vps : vpsNals) {
                writeInt16(vps.length);
                writeBytes(vps);
            }

            // Write SPS array
            writeInt8(0x80 | 33); // array_completeness | NAL_unit_type (SPS)
            writeInt16(spsNals.size());
            for (byte[] sps : spsNals) {
                writeInt16(sps.length);
                writeBytes(sps);
            }

            // Write PPS array
            writeInt8(0x80 | 34); // array_completeness | NAL_unit_type (PPS)
            writeInt16(ppsNals.size());
            for (byte[] pps : ppsNals) {
                writeInt16(pps.length);
                writeBytes(pps);
            }
        } else {
            Log.e(TAG, "No VPS/SPS/PPS found in Annex-B data, writing minimal hvcC");
            writeMinimalHvccBox();
        }
    }

    // ========== Annex-B to AVCC Conversion ==========

    /**
     * Convert Annex-B format (start code-prefixed) to AVCC format (length-prefixed).
     * Simple conversion matching C++ implementation - NO FILTERING.
     * Returns the original buffer if already in AVCC format.
     */
    private byte[] convertToAVCC(byte[] buffer, boolean isHEVC) {
        if (buffer == null || buffer.length == 0) {
            return null;
        }

        // Check if already in AVCC format
        if (buffer.length >= 8) {
            int firstNalLength = ((buffer[0] & 0xFF) << 24) |
                                ((buffer[1] & 0xFF) << 16) |
                                ((buffer[2] & 0xFF) << 8) |
                                (buffer[3] & 0xFF);

            // Validate AVCC format
            if (firstNalLength > 0 && firstNalLength <= buffer.length - 4 &&
                !(buffer[0] == 0 && buffer[1] == 0 && buffer[2] == 0 && buffer[3] == 1)) {
                
                // Validate entire buffer
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
                    return buffer;
                }
            }
        }

        // Convert from Annex-B to AVCC (simple conversion, matching C++ implementation)
        List<byte[]> nalUnits = new ArrayList<>();
        int offset = 0;

        while (offset < buffer.length) {
            // Find start code (0x000001 or 0x00000001)
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

            // Find next start code
            int nalEnd = buffer.length;
            for (int i = nalStart + 1; i + 2 < buffer.length; i++) {
                if (buffer[i] == 0 && buffer[i + 1] == 0 &&
                    (buffer[i + 2] == 1 || 
                     (i + 3 < buffer.length && buffer[i + 2] == 0 && buffer[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            // Extract NAL unit (including header)
            if (nalEnd > nalStart) {
                byte[] nalUnit = new byte[nalEnd - nalStart];
                System.arraycopy(buffer, nalStart, nalUnit, 0, nalUnit.length);
                
                // Add all NAL units - let them through (matching C++ behavior)
                nalUnits.add(nalUnit);
            }

            offset = nalEnd;
        }

        if (nalUnits.isEmpty()) {
            Log.e(TAG, "No NAL units found in Annex-B buffer");
            return null;
        }

        // Build AVCC buffer (matching C++ implementation exactly)
        int totalSize = 0;
        for (byte[] nal : nalUnits) {
            totalSize += 4 + nal.length;
        }

        byte[] avccBuffer = new byte[totalSize];
        int writeOffset = 0;

        for (byte[] nal : nalUnits) {
            // Write 4-byte length prefix (big-endian)
            avccBuffer[writeOffset++] = (byte) ((nal.length >> 24) & 0xFF);
            avccBuffer[writeOffset++] = (byte) ((nal.length >> 16) & 0xFF);
            avccBuffer[writeOffset++] = (byte) ((nal.length >> 8) & 0xFF);
            avccBuffer[writeOffset++] = (byte) (nal.length & 0xFF);

            // Write NAL unit data
            System.arraycopy(nal, 0, avccBuffer, writeOffset, nal.length);
            writeOffset += nal.length;
        }

        // Final validation (matching C++ checks)
        int validationOffset = 0;
        int nalCount = 0;
        
        while (validationOffset + 4 <= avccBuffer.length) {
            int nalLength = ((avccBuffer[validationOffset] & 0xFF) << 24) |
                           ((avccBuffer[validationOffset + 1] & 0xFF) << 16) |
                           ((avccBuffer[validationOffset + 2] & 0xFF) << 8) |
                           (avccBuffer[validationOffset + 3] & 0xFF);
            
            if (nalLength == 0 || validationOffset + 4 + nalLength > avccBuffer.length) {
                Log.e(TAG, String.format(
                    "CRITICAL: Invalid AVCC data - NAL#%d length=%d at offset=%d, buffer size=%d",
                    nalCount, nalLength, validationOffset, avccBuffer.length));
                return null;
            }
            
            nalCount++;
            validationOffset += 4 + nalLength;
        }
        
        if (validationOffset != avccBuffer.length) {
            Log.e(TAG, String.format(
                "CRITICAL: AVCC buffer size mismatch - consumed=%d, total=%d",
                validationOffset, avccBuffer.length));
            return null;
        }
        
        return avccBuffer;
    }
}