package com.facebook.encapp.utils.codec;

/**
 * Factory for creating codec-specific writers.
 */
public class CodecWriterFactory {

    /**
     * Create a codec writer for the specified codec type.
     */
    public static CodecWriter createWriter(CodecType codecType) {
        switch (codecType) {
            case HEVC:
                return new HevcCodecWriter();
            case AVC:
                return new AvcCodecWriter();
            case AV1:
                return new Av1CodecWriter();
            case VP9:
                return new Vp9CodecWriter();
            default:
                throw new IllegalArgumentException("Unsupported codec type: " + codecType);
        }
    }

    /**
     * Create a codec writer from MIME type.
     */
    public static CodecWriter createFromMimeType(String mimeType) {
        CodecType codecType = CodecType.fromMimeType(mimeType);
        return createWriter(codecType);
    }
}
