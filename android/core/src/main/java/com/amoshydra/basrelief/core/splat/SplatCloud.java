package com.amoshydra.basrelief.core.splat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * A 3D Gaussian splat cloud read from the {@code .splat} container: 32 bytes per
 * record, little-endian, exactly the layout the web viewers and the spike use.
 *
 * <pre>
 *   offset  0..11  position  x,y,z        float32 x3
 *   offset 12..23  scale     sx,sy,sz     float32 x3   (already exp()-decoded)
 *   offset 24..27  colour    r,g,b,a      uint8   x4
 *   offset 28..31  quaternion w,x,y,z     uint8   x4   (q*128 + 128)
 * </pre>
 *
 * <p><b>The record IS the GPU texel pair, and that is not a coincidence.</b> The
 * renderer uploads the raw bytes as an {@code RGBA32F} texture with two texels per
 * splat: texel A is {@code (x, y, z, sx)} and texel B is
 * {@code (sy, sz, colourBits, quatBits)}, where the two {@code uint8x4} fields are
 * reinterpreted as floats. That is precisely this 32-byte layout, so the reader
 * never repacks anything and the file can be uploaded verbatim. {@link #raw()}
 * is the whole reader for the app's purposes; the typed accessors exist for the
 * CPU sort (positions) and for the tests.
 *
 * <p><b>Pure JVM, no Android imports</b>, like everything in {@code :core}: the
 * container is parsed here so the layout, the little-endian reads and the
 * trailing-partial-record rule can be unit tested without a device. The one
 * non-pure method, {@link #read}, is a thin {@code Files.readAllBytes} wrapper.
 *
 * <p><b>A partial trailing record is ignored, never rounded up.</b> The count is
 * {@code data.length / 32}; a truncated file therefore yields one fewer splat
 * rather than reading past the buffer. A file shorter than one record is an empty
 * cloud, which the renderer treats as "nothing to draw" rather than as an error.
 */
public final class SplatCloud {

    /** Bytes per splat. The format's whole definition. */
    public static final int RECORD_BYTES = 32;

    /** The number of complete records in the buffer. */
    public final int count;

    private final byte[] data;

    private SplatCloud(byte[] data, int count) {
        this.data = data;
        this.count = count;
    }

    /**
     * Parse a whole {@code .splat} file's bytes.
     *
     * <p>Never throws: a buffer that is null, empty or not a whole number of
     * records parses to the complete records it holds. This is the same rule the
     * index's blob parsing follows -- a window claims nothing, and a corrupt tail
     * costs one splat, not the cloud.
     */
    public static SplatCloud parse(byte[] data) {
        if (data == null) return new SplatCloud(new byte[0], 0);
        return new SplatCloud(data, data.length / RECORD_BYTES);
    }

    /** Read a file and parse it. The only I/O in this class. */
    public static SplatCloud read(File file) throws IOException {
        return parse(Files.readAllBytes(file.toPath()));
    }

    /**
     * The raw record bytes, {@code count * 32} of them.
     *
     * <p>This is what the renderer uploads: see the class comment for why the
     * file layout and the texture layout are the same thing.
     */
    public byte[] raw() {
        return data;
    }

    private static float f32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    /** Position of splat {@code i} into {@code out[0..2]}. */
    public void position(int i, float[] out) {
        int o = i * RECORD_BYTES;
        out[0] = f32(data, o);
        out[1] = f32(data, o + 4);
        out[2] = f32(data, o + 8);
    }

    /** Scale of splat {@code i} into {@code out[0..2]}. */
    public void scale(int i, float[] out) {
        int o = i * RECORD_BYTES + 12;
        out[0] = f32(data, o);
        out[1] = f32(data, o + 4);
        out[2] = f32(data, o + 8);
    }

    /** Colour of splat {@code i} (0..255 per channel) into {@code out[0..3]}. */
    public void color(int i, int[] out) {
        int o = i * RECORD_BYTES + 24;
        out[0] = data[o] & 0xFF;
        out[1] = data[o + 1] & 0xFF;
        out[2] = data[o + 2] & 0xFF;
        out[3] = data[o + 3] & 0xFF;
    }

    /** Quaternion of splat {@code i} (0..255 per component) into {@code out[0..3]}. */
    public void quaternion(int i, int[] out) {
        int o = i * RECORD_BYTES + 28;
        out[0] = data[o] & 0xFF;
        out[1] = data[o + 1] & 0xFF;
        out[2] = data[o + 2] & 0xFF;
        out[3] = data[o + 3] & 0xFF;
    }

    /**
     * Every position into {@code out}, which must hold {@code count * 3} floats.
     *
     * <p>The CPU sort reads positions once per camera move, so it wants them
     * contiguous rather than through an allocating accessor.
     */
    public void positions(float[] out) {
        for (int i = 0, j = 0; i < count; i++, j += 3) {
            int o = i * RECORD_BYTES;
            out[j] = f32(data, o);
            out[j + 1] = f32(data, o + 4);
            out[j + 2] = f32(data, o + 8);
        }
    }

    /**
     * The {@code uint8x4} colour reinterpreted as the float the shader unpacks.
     *
     * <p>The shader does {@code unpack_unorm_4x8(floatBitsToUint(B.z))}, so the
     * four bytes are read as one little-endian float and bit-cast. This is the
     * same value the GPU would compute from the raw bytes; it exists so the
     * round trip can be asserted without a device.
     */
    public float packedColor(int i) {
        return f32(data, i * RECORD_BYTES + 24);
    }

    /** The quaternion bytes as the float the shader unpacks. See {@link #packedColor}. */
    public float packedQuaternion(int i) {
        return f32(data, i * RECORD_BYTES + 28);
    }
}
