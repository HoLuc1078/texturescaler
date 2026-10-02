package com.evernight.texturescaler.core;

/**
 * Reads image dimensions straight from the PNG IHDR chunk without decoding the image.
 *
 * <p>This is the fast path that lets the scaler skip the ~99% of textures that are
 * already small enough without opening a decoder at all.</p>
 */
public final class PngInfo {

    /** Number of leading bytes required to read the IHDR dimensions. */
    public static final int HEADER_BYTES = 24;

    private PngInfo() {
    }

    /**
     * @return {@code {width, height}} or {@code null} when {@code data} is not a plain
     *         non-interlaced-readable PNG header (caller then falls back to a full decode).
     */
    public static int[] read(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            return null;
        }
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        if ((data[0] & 0xFF) != 0x89 || data[1] != 0x50 || data[2] != 0x4E || data[3] != 0x47
                || data[4] != 0x0D || data[5] != 0x0A || data[6] != 0x1A || data[7] != 0x0A) {
            return null;
        }
        // IHDR length (4 bytes) + "IHDR" (4 bytes) then width/height (big-endian)
        if (data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
            return null;
        }
        int w = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16) | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
        int h = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16) | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
        if (w <= 0 || h <= 0 || w > 65536 || h > 65536) {
            return null;
        }
        return new int[]{w, h};
    }
}
