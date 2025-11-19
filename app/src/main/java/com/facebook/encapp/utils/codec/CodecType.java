package com.facebook.encapp.utils.codec;

/**
 * Enumeration of supported video/image codecs in HEIF/MP4 containers.
 */
public enum CodecType {
    /**
     * H.264/AVC - Advanced Video Coding
     */
    AVC("avc1", "avcC"),

    /**
     * H.265/HEVC - High Efficiency Video Coding
     */
    HEVC("hvc1", "hvcC"),

    /**
     * AV1 - AOMedia Video 1
     */
    AV1("av01", "av1C"),

    /**
     * VP9 - Google's VP9 codec
     */
    VP9("vp09", "vpcC");

    private final String itemType;
    private final String configBoxType;

    CodecType(String itemType, String configBoxType) {
        this.itemType = itemType;
        this.configBoxType = configBoxType;
    }

    /**
     * Get the item type FourCC code for this codec (used in infe box).
     */
    public String getItemType() {
        return itemType;
    }

    /**
     * Get the codec configuration box type (e.g., "hvcC", "avcC").
     * Returns null for codecs that don't require a config box (e.g., JPEG).
     */
    public String getConfigBoxType() {
        return configBoxType;
    }

    /**
     * Detect codec type from MIME type string.
     */
    public static CodecType fromMimeType(String mimeType) {
        if (mimeType == null) {
            return HEVC; // Default
        }

        String mime = mimeType.toLowerCase();

        // Check for AV1 first (before AVC to avoid "av" substring match)
        if (mime.contains("av01") || mime.contains("av1") || mime.contains("avif")) {
            return AV1;
        } else if (mime.contains("hevc") || mime.contains("heic") || mime.contains("h265") || mime.contains("h.265")) {
            return HEVC;
        } else if (mime.contains("avc") || mime.contains("h264") || mime.contains("h.264")) {
            return AVC;
        } else if (mime.contains("vp9") || mime.contains("vp09")) {
            return VP9;
        }

        return HEVC; // Default fallback
    }

    /**
     * Check if this codec requires a configuration box.
     */
    public boolean requiresConfigBox() {
        return configBoxType != null;
    }
}
