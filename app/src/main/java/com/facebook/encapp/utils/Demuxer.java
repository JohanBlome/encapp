package com.facebook.encapp.utils;

import android.util.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MPEG4 demuxer implementation for parsing MP4 files and extracting video frames.
 * This is a pure Java implementation that can be used as a fallback when the
 * Android MediaExtractor is not available.
 */
public class Demuxer {
    private static final String TAG = "Demuxer";

    private static final int BOX_FTYP = 0x66747970;  // 'ftyp'
    private static final int BOX_MOOV = 0x6D6F6F76;  // 'moov'
    private static final int BOX_MDAT = 0x6D646174;  // 'mdat'
    private static final int BOX_TRAK = 0x7472616B;  // 'trak'
    private static final int BOX_MDIA = 0x6D646961;  // 'mdia'
    private static final int BOX_MINF = 0x6D696E66;  // 'minf'
    private static final int BOX_STBL = 0x7374626C;  // 'stbl'
    private static final int BOX_STSD = 0x73747364;  // 'stsd'
    private static final int BOX_STTS = 0x73747473;  // 'stts'
    private static final int BOX_STSS = 0x73747373;  // 'stss'
    private static final int BOX_STSC = 0x73747363;  // 'stsc'
    private static final int BOX_STSZ = 0x7374737A;  // 'stsz'
    private static final int BOX_STCO = 0x7374636F;  // 'stco'
    private static final int BOX_CO64 = 0x636F3634;  // 'co64'
    private static final int BOX_CTTS = 0x63747473;  // 'ctts'
    private static final int BOX_TKHD = 0x746B6864;  // 'tkhd'
    private static final int BOX_MDHD = 0x6D646864;  // 'mdhd'
    private static final int BOX_AVC1 = 0x61766331;  // 'avc1'
    private static final int BOX_HVC1 = 0x68766331;  // 'hvc1'
    private static final int BOX_HEV1 = 0x68657631;  // 'hev1'
    private static final int BOX_AVCC = 0x61766343;  // 'avcC'
    private static final int BOX_HVCC = 0x68766343;  // 'hvcC'

    private final String mFilename;
    private RandomAccessFile mFile;

    private int mWidth;
    private int mHeight;
    private float mFrameRate;
    private int mTimeScale;
    private boolean mIsHEVC;

    private byte[] mCodecSpecificData;

    private List<SampleInfo> mSamples;
    private List<Integer> mSampleSizes;
    private List<Long> mChunkOffsets;
    private List<Integer> mKeyFrames;
    private List<Integer> mTimeToSample;
    private List<Integer> mSampleToChunk;
    private List<Integer> mCompositionTimeOffset;

    private int mCurrentSample;
    private boolean mEOS;

    private long mMdatOffset;
    private long mMdatSize;

    public static class Frame {
        public byte[] data;
        public long timestamp;  // PTS
        public long dts;
        public boolean isKeyFrame;
        public int size;
    }

    private static class MP4Box {
        long size;
        int type;
        long offset;
    }

    private static class SampleInfo {
        long offset;
        int size;
        long timestamp;
        long dts;
        boolean isKeyFrame;
    }

    public Demuxer(String filename) {
        mFilename = filename;
        mWidth = 0;
        mHeight = 0;
        mFrameRate = 0.0f;
        mTimeScale = 0;
        mIsHEVC = false;
        mCurrentSample = 0;
        mEOS = false;
        mMdatOffset = 0;
        mMdatSize = 0;

        mSamples = new ArrayList<>();
        mSampleSizes = new ArrayList<>();
        mChunkOffsets = new ArrayList<>();
        mKeyFrames = new ArrayList<>();
        mTimeToSample = new ArrayList<>();
        mSampleToChunk = new ArrayList<>();
        mCompositionTimeOffset = new ArrayList<>();
    }

    public boolean initialize() {
        Log.d(TAG, "Initializing demuxer for file: " + mFilename);

        try {
            mFile = new RandomAccessFile(mFilename, "r");
        } catch (IOException e) {
            Log.e(TAG, "Failed to open file: " + mFilename, e);
            return false;
        }

        Log.d(TAG, "File opened successfully, parsing MP4...");
        if (!parseMP4()) {
            Log.e(TAG, "Failed to parse MP4 file");
            return false;
        }

        Log.d(TAG, "MP4 parsed successfully, building sample table...");
        if (!buildSampleTable()) {
            Log.e(TAG, "Failed to build sample table");
            return false;
        }

        Log.d(TAG, String.format("Successfully initialized demuxer: %dx%d, %.2f fps, %s, %d samples",
                mWidth, mHeight, mFrameRate, mIsHEVC ? "HEVC" : "H.264", mSamples.size()));
        return true;
    }

    public boolean getNextFrame(Frame frame) {
        if (mEOS || mCurrentSample >= mSamples.size()) {
            Log.d(TAG, String.format("getNextFrame: Returning false - mEOS=%b, mCurrentSample=%d, mSamples.size()=%d",
                    mEOS, mCurrentSample, mSamples.size()));
            mEOS = true;
            return false;
        }

        if (mCurrentSample == 0) {
            int idrIndex = 0;
            boolean foundIDR = false;
            for (int i = 0; i < mSamples.size(); i++) {
                if (mSamples.get(i).isKeyFrame) {
                    idrIndex = i;
                    foundIDR = true;
                    break;
                }
            }

            if (foundIDR && idrIndex > 0) {
                Log.d(TAG, "Skipping to first IDR frame at sample " + idrIndex);
                mCurrentSample = idrIndex;
            }
        }

        SampleInfo sample = mSamples.get(mCurrentSample);

        try {
            byte[] rawData = new byte[sample.size];
            mFile.seek(sample.offset);
            int bytesRead = mFile.read(rawData);

            if (bytesRead != sample.size) {
                Log.e(TAG, "Failed to read frame data at sample " + mCurrentSample);
                return false;
            }

            frame.data = convertAVCCFrameToAnnexB(rawData);
            frame.dts = sample.dts;
            frame.timestamp = sample.timestamp;
            frame.isKeyFrame = sample.isKeyFrame;
            frame.size = frame.data.length;

            mCurrentSample++;
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read frame at sample " + mCurrentSample, e);
            return false;
        }
    }

    public boolean isEOS() {
        return mEOS;
    }

    public boolean reset() {
        mCurrentSample = 0;
        mEOS = false;
        return true;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public float getFrameRate() {
        return mFrameRate;
    }

    public int getTimeScale() {
        return mTimeScale;
    }

    public boolean isHEVC() {
        return mIsHEVC;
    }

    public byte[] getCodecSpecificData() {
        return mCodecSpecificData;
    }

    public void close() {
        if (mFile != null) {
            try {
                mFile.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing file", e);
            }
        }
    }

    private static int fourcc(char a, char b, char c, char d) {
        return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((c & 0xFF) << 8) | (d & 0xFF);
    }

    private int readUint32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
                ((data[offset + 1] & 0xFF) << 16) |
                ((data[offset + 2] & 0xFF) << 8) |
                (data[offset + 3] & 0xFF);
    }

    private long readUint64(byte[] data, int offset) {
        return (((long) readUint32(data, offset)) << 32) |
                (readUint32(data, offset + 4) & 0xFFFFFFFFL);
    }

    private int readUint16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private boolean parseMP4() {
        try {
            long fileSize = mFile.length();
            long offset = 0;

            while (offset < fileSize) {
                MP4Box box = new MP4Box();
                if (!parseBox(box, offset)) {
                    Log.e(TAG, "Failed to parse box at offset " + offset);
                    return false;
                }

                switch (box.type) {
                    case BOX_FTYP:
                        if (!parseFtyp(box)) {
                            Log.e(TAG, "Failed to parse ftyp box");
                            return false;
                        }
                        break;
                    case BOX_MOOV:
                        if (!parseMoov(box)) {
                            Log.e(TAG, "Failed to parse moov box");
                            return false;
                        }
                        break;
                    case BOX_MDAT:
                        if (!parseMdat(box)) {
                            Log.e(TAG, "Failed to parse mdat box");
                            return false;
                        }
                        break;
                    default:
                        Log.v(TAG, String.format("Skipping box type: 0x%08x", box.type));
                        break;
                }

                offset += box.size;
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing MP4", e);
            return false;
        }
    }

    private boolean parseBox(MP4Box box, long offset) {
        try {
            mFile.seek(offset);

            byte[] header = new byte[8];
            if (mFile.read(header) != 8) {
                return false;
            }

            box.size = readUint32(header, 0) & 0xFFFFFFFFL;
            box.type = readUint32(header, 4);
            box.offset = offset;

            if (box.size == 1) {
                byte[] extSize = new byte[8];
                if (mFile.read(extSize) != 8) {
                    return false;
                }
                box.size = readUint64(extSize, 0);
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing box", e);
            return false;
        }
    }

    private boolean parseFtyp(MP4Box box) {
        return true;
    }

    private boolean parseMoov(MP4Box box) {
        try {
            long offset = box.offset + 8;
            long endOffset = box.offset + box.size;

            while (offset < endOffset) {
                MP4Box childBox = new MP4Box();
                if (!parseBox(childBox, offset)) {
                    return false;
                }

                if (childBox.type == BOX_TRAK) {
                    if (!parseTrak(childBox, offset)) {
                        Log.e(TAG, "Failed to parse trak box");
                        return false;
                    }
                }

                offset += childBox.size;
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing moov", e);
            return false;
        }
    }

    private boolean parseTrak(MP4Box box, long offset) {
        try {
            long currentOffset = offset + 8;
            long endOffset = offset + box.size;

            int trackWidth = 0;
            int trackHeight = 0;
            int trackTimeScale = 0;
            float trackFrameRate = 0.0f;
            boolean trackIsHEVC = false;
            byte[] trackCodecSpecificData = null;
            boolean isVideoTrack = false;

            while (currentOffset < endOffset) {
                MP4Box childBox = new MP4Box();
                if (!parseBox(childBox, currentOffset)) {
                    return false;
                }

                switch (childBox.type) {
                    case BOX_TKHD: {
                        mFile.seek(childBox.offset + 8);
                        byte[] tkhdData = new byte[84];
                        if (mFile.read(tkhdData) >= 84) {
                            trackWidth = readUint32(tkhdData, 76) >> 16;
                            trackHeight = readUint32(tkhdData, 80) >> 16;
                            Log.v(TAG, String.format("Track dimensions: %dx%d", trackWidth, trackHeight));
                        }
                        break;
                    }
                    case BOX_MDIA: {
                        long mdiaOffset = currentOffset + 8;
                        long mdiaEnd = currentOffset + childBox.size;

                        while (mdiaOffset < mdiaEnd) {
                            MP4Box mdiaChild = new MP4Box();
                            if (!parseBox(mdiaChild, mdiaOffset)) {
                                return false;
                            }

                            if (mdiaChild.type == BOX_MDHD) {
                                mFile.seek(mdiaChild.offset + 8);
                                byte[] mdhdData = new byte[32];
                                if (mFile.read(mdhdData) >= 32) {
                                    int version = mdhdData[0] & 0xFF;
                                    if (version == 0) {
                                        trackTimeScale = readUint32(mdhdData, 12);
                                        int duration = readUint32(mdhdData, 16);
                                        Log.v(TAG, String.format("Timescale: %d, duration: %d", trackTimeScale, duration));
                                    }
                                }
                            } else if (mdiaChild.type == BOX_MINF) {
                                long minfOffset = mdiaOffset + 8;
                                long minfEnd = mdiaOffset + mdiaChild.size;

                                while (minfOffset < minfEnd) {
                                    MP4Box minfChild = new MP4Box();
                                    if (!parseBox(minfChild, minfOffset)) {
                                        return false;
                                    }

                                    if (minfChild.type == BOX_STBL) {
                                        long stblOffset = minfOffset + 8;
                                        long stblEnd = minfOffset + minfChild.size;

                                        while (stblOffset < stblEnd) {
                                            MP4Box stblChild = new MP4Box();
                                            if (!parseBox(stblChild, stblOffset)) {
                                                return false;
                                            }

                                            switch (stblChild.type) {
                                                case BOX_STSD: {
                                                    mFile.seek(stblChild.offset + 8);
                                                    byte[] stsdHeader = new byte[8];
                                                    if (mFile.read(stsdHeader) == 8) {
                                                        int entryCount = readUint32(stsdHeader, 4);
                                                        if (entryCount > 0) {
                                                            byte[] sampleDesc = new byte[8];
                                                            if (mFile.read(sampleDesc) == 8) {
                                                                int sampleType = readUint32(sampleDesc, 4);

                                                                if (sampleType == BOX_AVC1 ||
                                                                        sampleType == BOX_HVC1 ||
                                                                        sampleType == BOX_HEV1) {
                                                                    isVideoTrack = true;
                                                                    trackIsHEVC = (sampleType == BOX_HVC1 ||
                                                                            sampleType == BOX_HEV1);
                                                                    Log.v(TAG, String.format("Found video track: type=0x%08x, isHEVC=%b",
                                                                            sampleType, trackIsHEVC));

                                                                    trackCodecSpecificData = parseStsd(stblChild);
                                                                } else {
                                                                    Log.v(TAG, String.format("Skipping non-video track: type=0x%08x", sampleType));
                                                                }
                                                            }
                                                        }
                                                    }
                                                    break;
                                                }
                                                case BOX_STTS:
                                                    if (isVideoTrack && !parseStts(stblChild)) return false;
                                                    break;
                                                case BOX_STSS:
                                                    if (isVideoTrack && !parseStss(stblChild)) return false;
                                                    break;
                                                case BOX_STSC:
                                                    if (isVideoTrack && !parseStsc(stblChild)) return false;
                                                    break;
                                                case BOX_STSZ:
                                                    if (isVideoTrack && !parseStsz(stblChild)) return false;
                                                    break;
                                                case BOX_STCO:
                                                case BOX_CO64:
                                                    if (isVideoTrack && !parseStco(stblChild)) return false;
                                                    break;
                                                case BOX_CTTS:
                                                    if (isVideoTrack && !parseCtts(stblChild)) return false;
                                                    break;
                                            }

                                            stblOffset += stblChild.size;
                                        }
                                    }

                                    minfOffset += minfChild.size;
                                }
                            }

                            mdiaOffset += mdiaChild.size;
                        }
                        break;
                    }
                }

                currentOffset += childBox.size;
            }

            if (isVideoTrack && trackWidth > 0 && trackHeight > 0) {
                mWidth = trackWidth;
                mHeight = trackHeight;
                mTimeScale = trackTimeScale;
                mFrameRate = trackFrameRate;
                mIsHEVC = trackIsHEVC;
                mCodecSpecificData = trackCodecSpecificData;
                Log.d(TAG, String.format("Selected video track: %dx%d, %.2f fps, %s",
                        mWidth, mHeight, mFrameRate, mIsHEVC ? "HEVC" : "AVC"));
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing trak", e);
            return false;
        }
    }

    private byte[] parseStsd(MP4Box box) {
        try {
            mFile.seek(box.offset + 8);
            byte[] stsdHeader = new byte[8];
            if (mFile.read(stsdHeader) != 8) {
                return null;
            }

            int entryCount = readUint32(stsdHeader, 4);
            if (entryCount == 0) {
                return null;
            }

            byte[] sampleDesc = new byte[8];
            if (mFile.read(sampleDesc) != 8) {
                return null;
            }

            int sampleSize = readUint32(sampleDesc, 0);
            int sampleType = readUint32(sampleDesc, 4);

            mIsHEVC = (sampleType == BOX_HVC1 || sampleType == BOX_HEV1);
            Log.v(TAG, String.format("Sample type: 0x%08x, isHEVC: %b", sampleType, mIsHEVC));

            mFile.seek(box.offset + 8 + 8 + 8 + 70);

            long configOffset = box.offset + 8 + 8 + 8 + 78;
            long endOffset = box.offset + box.size;

            while (configOffset < endOffset) {
                mFile.seek(configOffset);
                byte[] configHeader = new byte[8];
                if (mFile.read(configHeader) != 8) {
                    break;
                }

                int configSize = readUint32(configHeader, 0);
                int configType = readUint32(configHeader, 4);

                if ((configType == BOX_AVCC && !mIsHEVC) || (configType == BOX_HVCC && mIsHEVC)) {
                    byte[] rawConfig = new byte[configSize - 8];
                    mFile.read(rawConfig);
                    Log.v(TAG, "Found codec config, size: " + (configSize - 8));
                    Log.d(TAG, String.format("Storing codec config in original format: isHEVC=%b, size=%d",
                            mIsHEVC, rawConfig.length));
                    return rawConfig;
                }

                configOffset += configSize;
            }

            return null;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing stsd", e);
            return null;
        }
    }

    private boolean parseStts(MP4Box box) {
        try {
            mFile.seek(box.offset + 8);
            byte[] sttsHeader = new byte[8];
            if (mFile.read(sttsHeader) != 8) {
                return false;
            }

            int entryCount = readUint32(sttsHeader, 4);
            mTimeToSample.clear();

            for (int i = 0; i < entryCount; i++) {
                byte[] entry = new byte[8];
                if (mFile.read(entry) != 8) {
                    return false;
                }
                mTimeToSample.add(readUint32(entry, 0));
                mTimeToSample.add(readUint32(entry, 4));
            }

            Log.v(TAG, "Time-to-sample entries: " + entryCount);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing stts", e);
            return false;
        }
    }

    private boolean parseStss(MP4Box box) {
        try {
            mFile.seek(box.offset + 8);
            byte[] stssHeader = new byte[8];
            if (mFile.read(stssHeader) != 8) {
                return false;
            }

            int entryCount = readUint32(stssHeader, 4);
            mKeyFrames.clear();

            for (int i = 0; i < entryCount; i++) {
                byte[] entry = new byte[4];
                if (mFile.read(entry) != 4) {
                    return false;
                }
                mKeyFrames.add(readUint32(entry, 0) - 1);
            }

            Log.v(TAG, "Sync sample entries: " + entryCount);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing stss", e);
            return false;
        }
    }

    private boolean parseStsc(MP4Box box) {
        try {
            mFile.seek(box.offset + 8);
            byte[] stscHeader = new byte[8];
            if (mFile.read(stscHeader) != 8) {
                return false;
            }

            int entryCount = readUint32(stscHeader, 4);
            mSampleToChunk.clear();

            for (int i = 0; i < entryCount; i++) {
                byte[] entry = new byte[12];
                if (mFile.read(entry) != 12) {
                    return false;
                }
                mSampleToChunk.add(readUint32(entry, 0));
                mSampleToChunk.add(readUint32(entry, 4));
                mSampleToChunk.add(readUint32(entry, 8));
            }

            Log.v(TAG, "Sample-to-chunk entries: " + entryCount);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing stsc", e);
            return false;
        }
    }

    private boolean parseStsz(MP4Box box) {
        try {
            mFile.seek(box.offset + 8);
            byte[] stszHeader = new byte[12];
            if (mFile.read(stszHeader) != 12) {
                return false;
            }

            int sampleSize = readUint32(stszHeader, 4);
            int sampleCount = readUint32(stszHeader, 8);

            mSampleSizes.clear();
            if (sampleSize == 0) {
                for (int i = 0; i < sampleCount; i++) {
                    byte[] entry = new byte[4];
                    if (mFile.read(entry) != 4) {
                        return false;
                    }
                    mSampleSizes.add(readUint32(entry, 0));
                }
            } else {
                for (int i = 0; i < sampleCount; i++) {
                    mSampleSizes.add(sampleSize);
                }
            }

            Log.v(TAG, "Sample sizes: " + sampleCount + " samples");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing stsz", e);
            return false;
        }
    }

    private boolean parseStco(MP4Box box) {
        try {
            mFile.seek(box.offset + 8);
            byte[] stcoHeader = new byte[8];
            if (mFile.read(stcoHeader) != 8) {
                return false;
            }

            int entryCount = readUint32(stcoHeader, 4);
            mChunkOffsets.clear();

            boolean is64bit = (box.type == BOX_CO64);
            for (int i = 0; i < entryCount; i++) {
                if (is64bit) {
                    byte[] entry = new byte[8];
                    if (mFile.read(entry) != 8) {
                        return false;
                    }
                    mChunkOffsets.add(readUint64(entry, 0));
                } else {
                    byte[] entry = new byte[4];
                    if (mFile.read(entry) != 4) {
                        return false;
                    }
                    mChunkOffsets.add(readUint32(entry, 0) & 0xFFFFFFFFL);
                }
            }

            Log.v(TAG, "Chunk offsets: " + entryCount + " chunks");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing stco", e);
            return false;
        }
    }

    private boolean parseCtts(MP4Box box) {
        try {
            mFile.seek(box.offset + 8);
            byte[] cttsHeader = new byte[8];
            if (mFile.read(cttsHeader) != 8) {
                return false;
            }

            int entryCount = readUint32(cttsHeader, 4);
            mCompositionTimeOffset.clear();

            for (int i = 0; i < entryCount; i++) {
                byte[] entry = new byte[8];
                if (mFile.read(entry) != 8) {
                    return false;
                }
                mCompositionTimeOffset.add(readUint32(entry, 0));
                mCompositionTimeOffset.add(readUint32(entry, 4));
            }

            Log.v(TAG, "Composition time offset entries: " + entryCount);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error parsing ctts", e);
            return false;
        }
    }

    private boolean parseMdat(MP4Box box) {
        mMdatOffset = box.offset + 8;
        mMdatSize = box.size - 8;
        Log.v(TAG, String.format("Media data: offset=%d, size=%d", mMdatOffset, mMdatSize));
        return true;
    }

    private byte[] convertAVCCFrameToAnnexB(byte[] frameData) {
        if (frameData == null || frameData.length == 0) {
            Log.e(TAG, "Empty frame data");
            return new byte[0];
        }

        List<Byte> annexBData = new ArrayList<>();
        byte[] startCode = new byte[]{0x00, 0x00, 0x00, 0x01};

        if (frameData.length < 4) {
            Log.e(TAG, "Frame data too small for length field: " + frameData.length);
            return new byte[0];
        }

        int firstNalLength = readUint32(frameData, 0);
        if (firstNalLength == 0 || firstNalLength > frameData.length - 4) {
            Log.e(TAG, String.format("Invalid first NAL length: %d, frame size: %d", firstNalLength, frameData.length));
            if (frameData[0] == 0x00 && frameData[1] == 0x00 && frameData[2] == 0x00 && frameData[3] == 0x01) {
                Log.w(TAG, "Frame appears to already be in Annex B format, copying as-is");
                return frameData;
            }
            return new byte[0];
        }

        boolean hasIDRFrame = false;
        int tempOffset = 0;
        while (tempOffset + 4 <= frameData.length) {
            int nalLength = readUint32(frameData, tempOffset);
            tempOffset += 4;

            if (nalLength == 0) {
                Log.w(TAG, "Zero-length NAL unit at offset " + (tempOffset - 4) + ", skipping");
                continue;
            }

            if (tempOffset + nalLength > frameData.length) {
                Log.e(TAG, String.format("Invalid NAL unit length: %d, remaining data: %d",
                        nalLength, frameData.length - tempOffset));
                break;
            }

            int nalType = 0;
            if (mIsHEVC) {
                nalType = (frameData[tempOffset] >> 1) & 0x3F;
                if (nalType == 19 || nalType == 20 || nalType == 21) {
                    hasIDRFrame = true;
                    break;
                }
            } else {
                nalType = frameData[tempOffset] & 0x1F;
                if (nalType == 5) {
                    hasIDRFrame = true;
                    break;
                }
            }

            tempOffset += nalLength;
        }

        if (hasIDRFrame && mCodecSpecificData != null && mCodecSpecificData.length > 0) {
            byte[] parameterSets;
            if (mIsHEVC) {
                parameterSets = convertHVCCToAnnexB(mCodecSpecificData);
            } else {
                parameterSets = convertAVCCToAnnexB(mCodecSpecificData);
            }

            if (parameterSets != null && parameterSets.length > 0) {
                for (byte b : parameterSets) {
                    annexBData.add(b);
                }
            } else {
                Log.e(TAG, "Failed to convert codec specific data to parameter sets");
            }
        }

        int offset = 0;
        int nalCount = 0;
        while (offset + 4 <= frameData.length) {
            int nalLength = readUint32(frameData, offset);
            offset += 4;

            if (nalLength == 0) {
                Log.w(TAG, "Zero-length NAL unit at offset " + (offset - 4) + ", skipping");
                continue;
            }

            if (offset + nalLength > frameData.length) {
                Log.e(TAG, String.format("Invalid NAL unit length: %d, remaining data: %d",
                        nalLength, frameData.length - offset));
                break;
            }

            if (nalLength > frameData.length) {
                Log.e(TAG, String.format("NAL unit length %d exceeds total data size %d",
                        nalLength, frameData.length));
                break;
            }

            for (byte b : startCode) {
                annexBData.add(b);
            }

            for (int i = offset; i < offset + nalLength; i++) {
                annexBData.add(frameData[i]);
            }

            offset += nalLength;
            nalCount++;
        }

        if (nalCount == 0) {
            Log.e(TAG, "No NAL units found in frame data");
            if (frameData.length > 0) {
                Log.w(TAG, "Attempting recovery: treating entire frame as single NAL unit");
                for (byte b : startCode) {
                    annexBData.add(b);
                }
                for (byte b : frameData) {
                    annexBData.add(b);
                }
                nalCount = 1;
            }
        }

        byte[] result = new byte[annexBData.size()];
        for (int i = 0; i < annexBData.size(); i++) {
            result[i] = annexBData.get(i);
        }
        return result;
    }

    private byte[] convertAVCCToAnnexB(byte[] avccData) {
        if (avccData == null || avccData.length < 7) {
            Log.e(TAG, "AVCC data too small: " + (avccData == null ? 0 : avccData.length));
            return new byte[0];
        }

        List<Byte> annexBData = new ArrayList<>();
        byte[] startCode = new byte[]{0x00, 0x00, 0x00, 0x01};

        int offset = 4;

        int nalLengthSize = (avccData[offset] & 0x03) + 1;
        offset++;

        int numSPS = avccData[offset] & 0x1F;
        offset++;

        for (int i = 0; i < numSPS; i++) {
            if (offset + 2 > avccData.length) break;

            int spsLength = readUint16(avccData, offset);
            offset += 2;

            if (offset + spsLength > avccData.length) break;

            for (byte b : startCode) {
                annexBData.add(b);
            }
            for (int j = 0; j < spsLength; j++) {
                annexBData.add(avccData[offset + j]);
            }

            offset += spsLength;
        }

        if (offset >= avccData.length) {
            byte[] result = new byte[annexBData.size()];
            for (int i = 0; i < annexBData.size(); i++) {
                result[i] = annexBData.get(i);
            }
            return result;
        }

        int numPPS = avccData[offset] & 0xFF;
        offset++;

        for (int i = 0; i < numPPS; i++) {
            if (offset + 2 > avccData.length) break;

            int ppsLength = readUint16(avccData, offset);
            offset += 2;

            if (offset + ppsLength > avccData.length) break;

            for (byte b : startCode) {
                annexBData.add(b);
            }
            for (int j = 0; j < ppsLength; j++) {
                annexBData.add(avccData[offset + j]);
            }

            offset += ppsLength;
        }

        byte[] result = new byte[annexBData.size()];
        for (int i = 0; i < annexBData.size(); i++) {
            result[i] = annexBData.get(i);
        }
        return result;
    }

    private byte[] convertHVCCToAnnexB(byte[] hvccData) {
        if (hvccData == null || hvccData.length < 23) {
            Log.e(TAG, "HVCC data too small: " + (hvccData == null ? 0 : hvccData.length));
            return new byte[0];
        }

        List<Byte> annexBData = new ArrayList<>();
        byte[] startCode = new byte[]{0x00, 0x00, 0x00, 0x01};

        int offset = 22;

        if (offset >= hvccData.length) {
            return new byte[0];
        }

        int numArrays = hvccData[offset] & 0xFF;
        offset++;

        for (int i = 0; i < numArrays; i++) {
            if (offset + 3 > hvccData.length) break;

            offset += 1;

            int numNalus = readUint16(hvccData, offset);
            offset += 2;

            for (int j = 0; j < numNalus; j++) {
                if (offset + 2 > hvccData.length) break;

                int naluLength = readUint16(hvccData, offset);
                offset += 2;

                if (offset + naluLength > hvccData.length) break;

                for (byte b : startCode) {
                    annexBData.add(b);
                }
                for (int k = 0; k < naluLength; k++) {
                    annexBData.add(hvccData[offset + k]);
                }

                offset += naluLength;
            }
        }

        byte[] result = new byte[annexBData.size()];
        for (int i = 0; i < annexBData.size(); i++) {
            result[i] = annexBData.get(i);
        }
        return result;
    }

    private boolean buildSampleTable() {
        Log.d(TAG, String.format("buildSampleTable: mSampleSizes.size()=%d, mChunkOffsets.size()=%d, " +
                        "mSampleToChunk.size()=%d, mCompositionTimeOffset.size()=%d",
                mSampleSizes.size(), mChunkOffsets.size(), mSampleToChunk.size(),
                mCompositionTimeOffset.size()));

        if (mSampleSizes.isEmpty() || mChunkOffsets.isEmpty() || mSampleToChunk.isEmpty()) {
            Log.e(TAG, String.format("Missing required sample table data - sizes:%d, chunks:%d, sampleToChunk:%d",
                    mSampleSizes.size(), mChunkOffsets.size(), mSampleToChunk.size()));
            return false;
        }

        mSamples.clear();

        int sampleIndex = 0;
        long dtsTimestamp = 0;
        int timeIndex = 0;
        int timeRemaining = 0;
        int timeDelta = 0;

        if (!mTimeToSample.isEmpty()) {
            timeRemaining = mTimeToSample.get(0);
            timeDelta = mTimeToSample.get(1);
        }

        int cttsIndex = 0;
        int cttsRemaining = 0;
        int compositionOffset = 0;
        boolean hasCtts = !mCompositionTimeOffset.isEmpty();

        if (hasCtts) {
            cttsRemaining = mCompositionTimeOffset.get(0);
            compositionOffset = mCompositionTimeOffset.get(1);
        }

        for (int chunkIndex = 0; chunkIndex < mChunkOffsets.size(); chunkIndex++) {
            long chunkOffset = mChunkOffsets.get(chunkIndex);

            int samplesPerChunk = 1;
            for (int i = 0; i < mSampleToChunk.size(); i += 3) {
                int firstChunk = mSampleToChunk.get(i) - 1;
                if (chunkIndex >= firstChunk) {
                    samplesPerChunk = mSampleToChunk.get(i + 1);
                }
            }

            long sampleOffset = chunkOffset;
            for (int i = 0; i < samplesPerChunk && sampleIndex < mSampleSizes.size(); i++) {
                SampleInfo sample = new SampleInfo();
                sample.offset = sampleOffset;
                sample.size = mSampleSizes.get(sampleIndex);

                sample.dts = dtsTimestamp;

                if (hasCtts) {
                    sample.timestamp = dtsTimestamp + compositionOffset;
                } else {
                    sample.timestamp = dtsTimestamp;
                }

                if (mTimeScale > 0) {
                    sample.dts = (sample.dts * 1000000L) / mTimeScale;
                    sample.timestamp = (sample.timestamp * 1000000L) / mTimeScale;
                }

                sample.isKeyFrame = mKeyFrames.contains(sampleIndex);

                mSamples.add(sample);

                sampleOffset += sample.size;
                sampleIndex++;

                if (timeRemaining > 0) {
                    timeRemaining--;
                    dtsTimestamp += timeDelta;

                    if (timeRemaining == 0 && timeIndex + 2 < mTimeToSample.size()) {
                        timeIndex += 2;
                        timeRemaining = mTimeToSample.get(timeIndex);
                        timeDelta = mTimeToSample.get(timeIndex + 1);
                    }
                }

                if (hasCtts && cttsRemaining > 0) {
                    cttsRemaining--;

                    if (cttsRemaining == 0 && cttsIndex + 2 < mCompositionTimeOffset.size()) {
                        cttsIndex += 2;
                        cttsRemaining = mCompositionTimeOffset.get(cttsIndex);
                        compositionOffset = mCompositionTimeOffset.get(cttsIndex + 1);
                    }
                }
            }
        }

        if (!mSamples.isEmpty() && mTimeScale > 0) {
            long totalDuration = mSamples.get(mSamples.size() - 1).dts;
            if (totalDuration > 0) {
                double durationInSeconds = (double) totalDuration / 1000000.0;
                mFrameRate = (float) (mSamples.size() / durationInSeconds);
            } else {
                long totalSampleDuration = 0;
                for (int i = 0; i < mTimeToSample.size(); i += 2) {
                    int sampleCount = mTimeToSample.get(i);
                    int sampleDuration = mTimeToSample.get(i + 1);
                    totalSampleDuration += (long) sampleCount * sampleDuration;
                }
                if (totalSampleDuration > 0) {
                    double durationInSeconds = (double) totalSampleDuration / mTimeScale;
                    mFrameRate = (float) (mSamples.size() / durationInSeconds);
                }
            }
        }

        Log.d(TAG, String.format("Built sample table with %d samples, hasCtts=%b", mSamples.size(), hasCtts));
        if (hasCtts) {
            Log.d(TAG, "First few samples - DTS/PTS pairs:");
            for (int i = 0; i < Math.min(5, mSamples.size()); i++) {
                SampleInfo sample = mSamples.get(i);
                Log.d(TAG, String.format("Sample %d: DTS=%d, PTS=%d", i, sample.dts, sample.timestamp));
            }
        }

        return true;
    }
}
