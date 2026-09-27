package com.amoshydra.basrelief;

/**
 * Port of the web app's png-depth.js: extracts an embedded depth map from a
 * PNG carrying it in a custom ancillary chunk ("deph"/"dept", case-insensitive).
 * The payload may be a whole PNG or raw 8-bit gray (exactly width*height bytes).
 */
public final class DepthPng {

    /** One of payload types is non-null: a whole PNG, or raw gray + dimensions. */
    public static final class Result {
        public final byte[] png;
        public final byte[] rawGray;
        public final int rawW, rawH;

        Result(byte[] png, byte[] rawGray, int rawW, int rawH) {
            this.png = png;
            this.rawGray = rawGray;
            this.rawW = rawW;
            this.rawH = rawH;
        }
    }

    private static final byte[] PNG_SIG = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    /** Returns null if the input isn't a PNG or has no depth chunk. */
    public static Result extract(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;
        for (int i = 0; i < 8; i++) {
            if (bytes[i] != PNG_SIG[i]) return null; // not a PNG
        }

        int offset = 8;
        int width = 0;
        int height = 0;
        while (offset + 12 <= bytes.length) {
            int length = u32be(bytes, offset);
            if (offset + 12 + length > bytes.length) break; // malformed
            String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);

            if ("IHDR".equals(type)) {
                width = u32be(bytes, offset + 8);
                height = u32be(bytes, offset + 12);
            }

            if (isDepthChunk(type)) {
                byte[] data = new byte[length];
                System.arraycopy(bytes, offset + 8, data, 0, length);
                if (isPng(data)) return new Result(data, null, 0, 0);
                if (width > 0 && height > 0 && data.length == width * height) {
                    return new Result(null, data, width, height);
                }
                return null; // chunk present but payload unrecognized
            }
            offset += 12 + length;
        }
        return null;
    }

    private static boolean isDepthChunk(String type) {
        return "deph".equals(type.toLowerCase()) || "dept".equals(type.toLowerCase());
    }

    private static boolean isPng(byte[] b) {
        if (b == null || b.length < 8) return false;
        for (int i = 0; i < 8; i++) {
            if (b[i] != PNG_SIG[i]) return false;
        }
        return true;
    }

    private static int u32be(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private DepthPng() {
    }
}
