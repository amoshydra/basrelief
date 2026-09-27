package com.amoshydra.basrelief;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Packs an original image and a depth map into a single self-contained PNG:
 * the color image is re-encoded to PNG and a raw 8-bit gray depth chunk
 * (name "dEPt", width*height bytes, row-major) is inserted before IEND —
 * exactly the format DepthPng parses.
 */
public final class DepthPngPacker {

    public static File pack(Bitmap original, Bitmap depth, File out) throws IOException {
        int w = original.getWidth();
        int h = original.getHeight();

        // scale the depth map to the original size and read it as 8-bit gray
        Bitmap scaled = Bitmap.createScaledBitmap(depth, w, h, true);
        int[] argb = new int[w * h];
        scaled.getPixels(argb, 0, w, 0, 0, w, h);
        byte[] gray = new byte[w * h];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (argb[i] >> 16 & 0xff);
        }

        // re-encode the color image to a clean PNG
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!original.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
            throw new IOException("failed to re-encode image to PNG");
        }
        byte[] outBytes = insertChunk(bos.toByteArray(), "dEPt", gray);

        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(outBytes);
        } finally {
            fos.close();
        }
        return out;
    }

    /** Insert a custom ancillary chunk immediately before IEND. */
    private static byte[] insertChunk(byte[] png, String type, byte[] data) throws IOException {
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = readInt(png, pos);
            String chunkType = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            if (chunkType.equals("IEND")) {
                int chunkLen = 12 + data.length;
                byte[] out = new byte[png.length + chunkLen];
                System.arraycopy(png, 0, out, 0, pos);
                writeInt(out, pos, data.length);
                out[pos + 4] = (byte) type.charAt(0);
                out[pos + 5] = (byte) type.charAt(1);
                out[pos + 6] = (byte) type.charAt(2);
                out[pos + 7] = (byte) type.charAt(3);
                System.arraycopy(data, 0, out, pos + 8, data.length);
                CRC32 crc = new CRC32();
                crc.update(out, pos + 4, 8 + data.length);
                writeInt(out, pos + 8 + data.length, (int) crc.getValue());
                System.arraycopy(png, pos, out, pos + chunkLen, png.length - pos);
                return out;
            }
            pos += 12 + len;
        }
        throw new IOException("IEND not found in PNG");
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
