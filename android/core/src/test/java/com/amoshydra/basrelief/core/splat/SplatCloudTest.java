package com.amoshydra.basrelief.core.splat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

/**
 * The {@code .splat} container: 32 bytes a record, little-endian, and the layout
 * happens to be exactly the two {@code RGBA32F} texels the renderer uploads — so
 * these tests pin the record field offsets as much as they pin the reader.
 *
 * <p>The trap is a partial trailing record: a file whose length is not a whole
 * number of records must yield one fewer splat, never a read past the buffer and
 * never a rounded-up count that makes the GPU fetch garbage for the last texel.
 */
public class SplatCloudTest {

    private static byte[] oneRecord(float x, float y, float z,
                                    float sx, float sy, float sz,
                                    int r, int g, int b, int a,
                                    int qw, int qx, int qy, int qz) {
        ByteBuffer bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        bb.putFloat(x).putFloat(y).putFloat(z);
        bb.putFloat(sx).putFloat(sy).putFloat(sz);
        bb.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        bb.put((byte) qw).put((byte) qx).put((byte) qy).put((byte) qz);
        return bb.array();
    }

    @Test
    public void readsEveryFieldAtItsOffset() {
        byte[] raw = oneRecord(1f, -2f, 3.5f, 0.1f, 0.2f, 0.3f,
                10, 20, 30, 40, 200, 100, 50, 0);
        SplatCloud c = SplatCloud.parse(raw);
        assertEquals(1, c.count);

        float[] p = new float[3];
        c.position(0, p);
        assertArrayEquals(new float[] { 1f, -2f, 3.5f }, p, 0f);

        float[] s = new float[3];
        c.scale(0, s);
        assertArrayEquals(new float[] { 0.1f, 0.2f, 0.3f }, s, 0f);

        int[] col = new int[4];
        c.color(0, col);
        assertArrayEquals(new int[] { 10, 20, 30, 40 }, col);

        int[] q = new int[4];
        c.quaternion(0, q);
        assertArrayEquals(new int[] { 200, 100, 50, 0 }, q);
    }

    /**
     * The raw record IS the texel pair: texel A is {@code (x, y, z, sx)} and
     * texel B is {@code (sy, sz, colourBits, quatBits)}, so the four colour bytes
     * and the four quaternion bytes must read back as the little-endian floats
     * the shader's {@code floatBitsToUint} unpacks.
     */
    @Test
    public void theColourAndQuaternionBytesAreTheFloatsTheShaderUnpacks() {
        byte[] raw = oneRecord(0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8);
        SplatCloud c = SplatCloud.parse(raw);
        float expectedColor = Float.intBitsToFloat(0x04030201);
        float expectedQuat = Float.intBitsToFloat(0x08070605);
        assertEquals(expectedColor, c.packedColor(0), 0f);
        assertEquals(expectedQuat, c.packedQuaternion(0), 0f);
    }

    @Test
    public void positionsAreContiguousInRecordOrder() {
        byte[] a = oneRecord(1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] b = oneRecord(4, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] both = new byte[64];
        System.arraycopy(a, 0, both, 0, 32);
        System.arraycopy(b, 0, both, 32, 32);
        SplatCloud c = SplatCloud.parse(both);
        float[] out = new float[6];
        c.positions(out);
        assertArrayEquals(new float[] { 1, 2, 3, 4, 5, 6 }, out, 0f);
    }

    /**
     * The one rule a truncated file needs: a partial trailing record is dropped.
     * The count is a floor, so the reader never names a splat it cannot fill.
     */
    @Test
    public void aPartialTrailingRecordIsIgnoredNotRoundedUp() {
        byte[] raw = oneRecord(1, 2, 3, 0.5f, 0.5f, 0.5f, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] truncated = new byte[32 + 17];
        System.arraycopy(raw, 0, truncated, 0, 32);
        SplatCloud c = SplatCloud.parse(truncated);
        assertEquals(1, c.count);
        // The buffer is untouched; the count is what gates every read, so the
        // 17 stray bytes are never named.
        assertEquals(truncated.length, c.raw().length);
    }

    @Test
    public void emptyAndShortBuffersAreAnEmptyCloudNotAnError() {
        assertEquals(0, SplatCloud.parse(null).count);
        assertEquals(0, SplatCloud.parse(new byte[0]).count);
        assertEquals(0, SplatCloud.parse(new byte[31]).count);
    }

    /** The header of the format: a record that is not 32 bytes is a different format. */
    @Test
    public void theRecordIsThirtyTwoBytes() {
        assertEquals(32, SplatCloud.RECORD_BYTES);
        assertTrue(SplatCloud.parse(new byte[320]).count == 10);
        assertFalse(SplatCloud.parse(new byte[32]).raw().length == 0);
    }
}
