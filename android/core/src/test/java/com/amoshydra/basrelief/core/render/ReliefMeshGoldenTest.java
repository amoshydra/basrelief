package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Pins the exact mesh output for a fixed synthetic depth map.
 *
 * <p>Why this exists: the 8-bit depth-banding artifact (contour lines on faces)
 * was a subtle, hard-won fix that lives entirely in {@link ReliefMesh#blur} and
 * the sampling around it. Nothing about it is visible from the code, and a
 * careless edit — re-quantising the blur to 8-bit, changing the sampling
 * convention, altering the blur radius — silently reintroduces it with no
 * failing build. This test makes that class of regression impossible to merge
 * without noticing.
 *
 * <p>The goldens were captured from the renderer as it shipped, so a mismatch
 * means behaviour changed, not that the expectation is wrong. If you change the
 * geometry ON PURPOSE, re-capture them deliberately (see the note in
 * {@code AGENTS.md} about the banding fix) rather than regenerating them
 * reflexively.
 */
public class ReliefMeshGoldenTest {

    private static final int W = 256, H = 320, GRID = 32;
    private static final float ASPECT = W / (float) H;

    @Test
    public void meshMatchesGoldenOutput() throws IOException {
        byte[] depth = readBlob("mesh-depth.bin");
        assertEquals("golden depth size", W * H, depth.length);

        float[] goldenPositions = readFloats("mesh-positions.bin");
        float[] goldenNormals = readFloats("mesh-normals.bin");

        ReliefMesh mesh = ReliefMesh.build(depth, W, H, GRID, ASPECT);

        assertEquals("vertex count", goldenPositions.length / 3, mesh.positions.length / 3);
        assertEquals("vertexCount field", mesh.positions.length / 3, mesh.vertexCount);
        assertEquals("normal count", goldenNormals.length, mesh.normals.length);
        assertEquals("uv count", mesh.positions.length / 3 * 2, mesh.uvs.length);

        assertArrayEquals("positions (geometry + depth mapping)",
                goldenPositions, mesh.positions, 0f);
        assertArrayEquals("normals (lighting is wrong if these drift)",
                goldenNormals, mesh.normals, 0f);
    }

    /**
     * The blur must keep float precision end to end. Re-quantising the result
     * to 8-bit recreates the terraces and the banding artifact returns; this
     * asserts the output really is fractional rather than whole levels.
     */
    @Test
    public void blurKeepsSubLevelPrecision() {
        byte[] flat = new byte[W * H];
        Arrays.fill(flat, (byte) 100);
        for (int y = 0; y < H; y++) flat[y * W + W / 2] = (byte) 101; // one step

        float[] blurred = ReliefMesh.blur(flat, W, H);

        boolean fractional = false;
        for (float v : blurred) {
            if (v != Math.floor(v)) { fractional = true; break; }
        }
        assertTrue("blur output must contain fractional values; an all-integer "
                + "result means it was re-quantised to 8-bit, which recreates "
                + "the banding terraces", fractional);
    }

    /** A flat depth map must blur to a constant, and the mesh to a flat plane. */
    @Test
    public void flatDepthProducesFlatPlane() {
        final int level = 128;
        byte[] flat = new byte[W * H];
        Arrays.fill(flat, (byte) level);

        // depth is mapped to z as (depth / 255) - 0.5, so mid-grey is not
        // exactly the plane; 127.5 would be.
        final float expectedZ = level / 255f - 0.5f;
        assertTrue("sanity: mid-grey is slightly proud of the plane", expectedZ > 0f);

        ReliefMesh mesh = ReliefMesh.build(flat, W, H, GRID, ASPECT);
        for (int i = 0; i < mesh.positions.length; i += 3) {
            assertEquals("flat depth -> constant z", expectedZ, mesh.positions[i + 2], 1e-6f);
        }
        // With no relief the surface is flat, so every normal points at the camera.
        for (int i = 0; i < mesh.normals.length; i += 3) {
            assertEquals("flat depth -> +z normal", 0f, mesh.normals[i], 1e-6f);
            assertEquals("flat depth -> +z normal", 0f, mesh.normals[i + 1], 1e-6f);
            assertEquals("flat depth -> +z normal", 1f, mesh.normals[i + 2], 1e-6f);
        }
    }

    // ---------------------------------------------------------------- golden IO
    //
    // File layout, matching the capture tool: a 4-byte big-endian ELEMENT
    // count, then the payload. Floats are raw big-endian IEEE-754.

    private static float[] readFloats(String name) throws IOException {
        byte[] raw = readAll(name);
        int count = readCount(raw, name);
        if (raw.length - 4 != count * 4) {
            throw new IOException(name + ": header says " + count + " floats ("
                    + (count * 4) + " bytes) but body is " + (raw.length - 4) + " bytes");
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = Float.intBitsToFloat(
                    ((raw[4 + i * 4] & 0xff) << 24)
                            | ((raw[4 + i * 4 + 1] & 0xff) << 16)
                            | ((raw[4 + i * 4 + 2] & 0xff) << 8)
                            | (raw[4 + i * 4 + 3] & 0xff));
        }
        return out;
    }

    private static byte[] readBlob(String name) throws IOException {
        byte[] raw = readAll(name);
        int count = readCount(raw, name);
        if (raw.length - 4 != count) {
            throw new IOException(name + ": header says " + count
                    + " bytes but body is " + (raw.length - 4) + " bytes");
        }
        byte[] out = new byte[count];
        System.arraycopy(raw, 4, out, 0, count);
        return out;
    }

    private static int readCount(byte[] raw, String name) throws IOException {
        if (raw.length < 4) throw new IOException(name + ": truncated header");
        return ((raw[0] & 0xff) << 24) | ((raw[1] & 0xff) << 16)
                | ((raw[2] & 0xff) << 8) | (raw[3] & 0xff);
    }

    private static byte[] readAll(String name) throws IOException {
        try (InputStream in =
                     ReliefMeshGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            if (in == null) throw new IOException("missing test resource /golden/" + name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
