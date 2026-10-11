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

        // blur now runs on the grid-resolution height field, so go through the
        // same path build() uses.
        int n = GRID + 1;
        float[] field = ReliefMesh.blur(ReliefMesh.resample(flat, W, H, n, GRID), n, n);

        boolean fractional = false;
        for (float v : field) {
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

    /**
     * The pivot is the surface point under the origin, so the renderer samples
     * the field it retains at the origin's IMAGE position. The field is in the
     * MODEL-SPACE height unit (the renderer converts its GLES3 copy once, after
     * the texture took the raw values), and the trap pinned here is the row
     * order: row 0 is the image's TOP row (plane y = +0.5). A row or column
     * flipped by accident would put the pivot on the opposite side of the
     * picture, which no camera test can see.
     */
    @Test
    public void fieldHeightIsSampledAtTheOriginsImagePosition() {
        int grid = 2;
        // row-major, top row first, in model units
        float[] field = {-0.40f, -0.30f, -0.20f,
                         -0.10f, 0.00f, 0.10f,
                         0.20f, 0.30f, 0.40f};

        // corners: plane y = +0.5 is the image top, plane x = -0.5 the left
        assertEquals("top-left", -0.40f,
                ReliefMesh.heightAt(field, grid, -0.5f, 0.5f), 1e-6f);
        assertEquals("top-right", -0.20f,
                ReliefMesh.heightAt(field, grid, 0.5f, 0.5f), 1e-6f);
        assertEquals("bottom-left", 0.20f,
                ReliefMesh.heightAt(field, grid, -0.5f, -0.5f), 1e-6f);
        assertEquals("bottom-right", 0.40f,
                ReliefMesh.heightAt(field, grid, 0.5f, -0.5f), 1e-6f);

        // exact samples, and one bilinear point between four of them:
        // (0.25, 0.25) is the middle of the -0.30/-0.20/0.00/0.10 cell
        assertEquals("centre sample", 0.00f,
                ReliefMesh.heightAt(field, grid, 0f, 0f), 1e-6f);
        assertEquals("bilinear between samples", -0.10f,
                ReliefMesh.heightAt(field, grid, 0.25f, 0.25f), 1e-6f);
    }

    /**
     * An origin at or beyond the picture reads the edge it is nearest rather
     * than wrapping, and no field at all answers the plane: 0 is the safe aim,
     * because a window claims nothing and a pivot that guessed would be a
     * camera aimed at a point nobody asked for.
     */
    @Test
    public void fieldHeightClampsOffThePictureAndClaimsNothingWithoutAField() {
        float[] field = {-0.40f, -0.30f, -0.20f,
                         -0.10f, 0.00f, 0.10f,
                         0.20f, 0.30f, 0.40f};
        assertEquals("left of the picture reads the left column", -0.10f,
                ReliefMesh.heightAt(field, 2, -1f, 0f), 1e-6f);
        assertEquals("below the picture reads the bottom row", 0.30f,
                ReliefMesh.heightAt(field, 2, 0f, -1f), 1e-6f);
        assertEquals("no field", 0f, ReliefMesh.heightAt(null, 2, 0f, 0f), 0f);
        assertEquals("no grid", 0f, ReliefMesh.heightAt(field, 0, 0f, 0f), 0f);
        assertEquals("short field", 0f,
                ReliefMesh.heightAt(new float[]{1f}, 2, 0f, 0f), 0f);
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
