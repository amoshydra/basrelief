package com.amoshydra.basrelief.core.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.splat.SplatCloud;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The 32-byte {@code .splat} record, against {@code contract/splat/}.
 *
 * <p><b>Why this is a contract now.</b> {@code contract/README.md} used to say the
 * record was deliberately NOT fixtured, because {@code SplatCloudTest} already pin
 * the layout field for field and there was no second implementation to align with.
 * The gallery is that second implementation ({@code web/src/splat.ts}), so the two
 * copies now share bytes the way the tag rule, the {@code dePH} chunk and the
 * sidecar do.
 *
 * <p><b>What a wrong answer looks like, and it is the reason the fixture carries a
 * bright record.</b> The renderer uploads the record as two {@code RGBA32UI} texels
 * and the shader unpacks with integer shifts. Uploaded as {@code RGBA32F} — which
 * the old WebGL prototype does — the four colour bytes become a float, and for an
 * opaque bright splat that float is a <b>NaN</b>: alpha 255 sets every exponent bit,
 * blue at or above 128 sets the last one. The driver's {@code floatBitsToUint}
 * then does not return those bytes on Mali, so the splat draws flat white. Nothing
 * errors; the picture is just wrong in the bright regions. So the fixture's bright
 * record asserts its colour word IS a NaN when read as a float, and that its
 * integer words are exactly the bytes.
 */
public class SplatRecordContractTest {

    private static JSONObject cases() {
        return ContractFiles.json("splat", "cases.json");
    }

    private static byte[] fixture() {
        return ContractFiles.bytes("splat", "records.bin");
    }

    private static JSONObject record(JSONObject c, int i) {
        JSONArray table = c.getJSONArray("recordsList");
        return table.getJSONObject(i);
    }

    private static void assertArray(String what, JSONArray expected, float[] actual, double delta) {
        assertEquals(what + ": length", expected.length(), actual.length);
        for (int i = 0; i < actual.length; i++) {
            assertEquals(what + "[" + i + "]", expected.getDouble(i), actual[i], delta);
        }
    }

    private static void assertBytes(String what, JSONArray expected, int[] actual) {
        assertEquals(what + ": length", expected.length(), actual.length);
        for (int i = 0; i < actual.length; i++) {
            assertEquals(what + "[" + i + "]", expected.getInt(i), actual[i]);
        }
    }

    private static long unsigned(int bits) {
        return Integer.toUnsignedLong(bits);
    }

    /** The checked-in bytes are the ones the table was generated from. */
    @Test
    public void theFixtureIsTheBytesTheTableDescribes() {
        byte[] bytes = fixture();
        JSONObject c = cases();
        assertEquals("record size", c.getInt("recordBytes"), SplatCloud.RECORD_BYTES);
        assertEquals("fixture length", c.getInt("recordsBytes"), bytes.length);
        assertEquals("records", c.getInt("records"), bytes.length / SplatCloud.RECORD_BYTES);
        assertEquals("digest", c.getString("fixtureSha256"), ContractFiles.sha256(bytes));
        assertEquals("the count is a fact about the length", c.getInt("records"),
                SplatCloud.countIn(bytes.length));
    }

    /**
     * Every record, field for field — the same expectation strings the TypeScript
     * reader is asserted against.
     */
    @Test
    public void everyRecordDecodesToTheFieldsTheTableStates() {
        byte[] bytes = fixture();
        SplatCloud cloud = SplatCloud.parse(bytes);
        JSONObject c = cases();
        assertEquals(c.getInt("records"), cloud.count);

        float[] f3 = new float[3];
        int[] b4 = new int[4];
        for (int i = 0; i < cloud.count; i++) {
            JSONObject r = record(c, i);
            String name = r.getString("name");

            cloud.position(i, f3);
            assertArray(name + ": position", r.getJSONArray("position"), f3, 1e-9);
            cloud.scale(i, f3);
            assertArray(name + ": scale", r.getJSONArray("scale"), f3, 1e-9);
            cloud.color(i, b4);
            assertBytes(name + ": colour", r.getJSONArray("color"), b4);
            cloud.quaternion(i, b4);
            assertBytes(name + ": quaternion", r.getJSONArray("quaternion"), b4);
        }
    }

    /**
     * The record IS the texel pair: word 0..3 are the position's and sx's bits,
     * word 4..7 are sy, sz and the two packed {@code uint8x4} words.
     *
     * <p>This is the packing the GPU reads, asserted without a GPU — and asserted
     * from the DECODED fields rather than only compared to the file, so a reader
     * that swapped colour for quaternion would fail here even if the file were
     * re-cut to match.
     */
    @Test
    public void theRecordIsTheTwoIntegerTexelsTheShaderReads() {
        byte[] bytes = fixture();
        ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        JSONObject c = cases();
        int records = c.getInt("records");

        for (int i = 0; i < records; i++) {
            JSONObject r = record(c, i);
            String name = r.getString("name");
            int base = i * SplatCloud.RECORD_BYTES;

            // What the file holds, as the GPU's own unsigned words.
            long[] fromFile = new long[8];
            for (int k = 0; k < 8; k++) fromFile[k] = unsigned(words.getInt(base + k * 4));

            // What the decode implies it must be.
            JSONArray t0 = r.getJSONArray("texels").getJSONArray(0);
            JSONArray t1 = r.getJSONArray("texels").getJSONArray(1);
            for (int k = 0; k < 4; k++) {
                assertEquals(name + ": texel A word " + k, t0.getLong(k), fromFile[k]);
                assertEquals(name + ": texel B word " + k, t1.getLong(k), fromFile[4 + k]);
            }

            JSONArray p = r.getJSONArray("position");
            JSONArray s = r.getJSONArray("scale");
            assertEquals(name + ": A.x is x's bits",
                    unsigned(Float.floatToRawIntBits((float) p.getDouble(0))), fromFile[0]);
            assertEquals(name + ": A.y is y's bits",
                    unsigned(Float.floatToRawIntBits((float) p.getDouble(1))), fromFile[1]);
            assertEquals(name + ": A.z is z's bits",
                    unsigned(Float.floatToRawIntBits((float) p.getDouble(2))), fromFile[2]);
            assertEquals(name + ": A.w is sx's bits",
                    unsigned(Float.floatToRawIntBits((float) s.getDouble(0))), fromFile[3]);
            assertEquals(name + ": B.x is sy's bits",
                    unsigned(Float.floatToRawIntBits((float) s.getDouble(1))), fromFile[4]);
            assertEquals(name + ": B.y is sz's bits",
                    unsigned(Float.floatToRawIntBits((float) s.getDouble(2))), fromFile[5]);

            JSONArray col = r.getJSONArray("color");
            long colourWord = (col.getLong(0) | (col.getLong(1) << 8)
                    | (col.getLong(2) << 16) | (col.getLong(3) << 24));
            assertEquals(name + ": B.z is the colour word", colourWord, fromFile[6]);
            JSONArray q = r.getJSONArray("quaternion");
            long quatWord = (q.getLong(0) | (q.getLong(1) << 8)
                    | (q.getLong(2) << 16) | (q.getLong(3) << 24));
            assertEquals(name + ": B.w is the quaternion word", quatWord, fromFile[7]);

            // And the shader's own unpack: `u & 0xFF`, `(u >> 8) & 0xFF`, ...
            long w = fromFile[6];
            assertEquals(name + ": r", col.getLong(0), w & 0xFFL);
            assertEquals(name + ": g", col.getLong(1), (w >> 8) & 0xFFL);
            assertEquals(name + ": b", col.getLong(2), (w >> 16) & 0xFFL);
            assertEquals(name + ": a", col.getLong(3), (w >> 24) & 0xFFL);
        }
    }

    /**
     * The one record whose FLOAT reading is a NaN, and the fixture must not quietly
     * lose it: this is the difference between the integer path and the prototype's
     * float one, and a screenshot of the right device cannot tell the two apart.
     */
    @Test
    public void aBrightColourWordIsANaNOnTheFloatPathAndExactBytesOnTheIntegerOne() {
        byte[] bytes = fixture();
        ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        JSONObject c = cases();
        int found = 0;

        for (int i = 0; i < c.getInt("records"); i++) {
            JSONObject r = record(c, i);
            int o = i * SplatCloud.RECORD_BYTES;
            float asFloat = words.getFloat(o + 24);
            boolean nan = Float.isNaN(asFloat);
            if (r.optBoolean("colourWordIsNaNAsFloat", false)) {
                found++;
                assertTrue(r.getString("name") + " claims a NaN colour but reads as " + asFloat, nan);
                JSONArray col = r.getJSONArray("color");
                assertTrue("the bright record is opaque", col.getInt(3) >= 128);
                assertTrue("and its blue is high", col.getInt(2) >= 128);
            }
            // Whatever the record, the integer path is exact: SplatCloud exposes the
            // same word as a float, and that is the value no driver may be asked to
            // carry through floatBitsToUint.
            assertEquals(Float.floatToRawIntBits(words.getFloat(o + 24)),
                    Float.floatToRawIntBits(SplatCloud.parse(bytes).packedColor(i)));
        }
        assertEquals("the fixture lost its bright record", 1, found);
        assertNotNull("the demo cloud's bright splats are common, not a corner",
                c.get("sourceColourWordsAsNaN"));
        assertTrue(c.getInt("sourceColourWordsAsNaN") > 1000);
    }

    /**
     * A truncated file yields one fewer splat rather than reading past the buffer —
     * and the gallery's classifier refuses such a file outright, so neither side
     * draws a cloud that is silently missing its end.
     */
    @Test
    public void aPartialTrailingRecordIsDroppedNeverRoundedUp() {
        byte[] bytes = fixture();
        byte[] truncated = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, truncated, 0, bytes.length);
        assertEquals(SplatCloud.countIn(bytes.length), SplatCloud.countIn(truncated.length));
        assertEquals(SplatCloud.parse(bytes).count, SplatCloud.parse(truncated).count);
    }
}
