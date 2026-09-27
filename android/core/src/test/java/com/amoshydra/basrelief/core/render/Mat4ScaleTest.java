package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins {@link Mat4#scaleM} to the real {@code android.opengl.Matrix.scaleM}.
 *
 * <p>This exists because the first version of this method scaled ROWS, i.e. it
 * computed {@code S * m} instead of {@code m * S}. Nothing else in the suite
 * caught it: the camera round-trip test still passed (it does not use the model
 * scale) and the mesh golden test still passed (it tests geometry, not the
 * projection). The visible symptom was that the Depth slider stopped extruding
 * the relief and only relit it, while the CPU-side values all looked correct.
 *
 * <p>The expected values below were captured by calling the REAL
 * {@code android.opengl.Matrix.scaleM} on a device and printing the result — not
 * derived by hand, which is exactly how the original mistake got in.
 */
public class Mat4ScaleTest {

    /** Deterministic, non-symmetric input: a stride-1 row/column mix-up shows up. */
    private static float[] input() {
        float[] m = new float[16];
        for (int i = 0; i < 16; i++) m[i] = (i % 5) - 2f;
        return m;
    }

    @Test
    public void scaleMScalesColumnsNotRows() {
        float[] m = input();
        Mat4.scaleM(m, 0, 2f, 3f, 4f);

        // Captured from android.opengl.Matrix.scaleM(result,0,lhs,0, 2,3,4).
        float[] expected = {
                -4f, -2f,  0f,  2f,
                 6f, -6f, -3f,  0f,
                 4f,  8f, -8f, -4f,
                 0f,  1f,  2f, -2f,
        };
        for (int i = 0; i < 16; i++) {
            assertEquals("element " + i, expected[i], m[i], 1e-6f);
        }
    }

    /** The translation column (12..15) must never be scaled. */
    @Test
    public void scaleMLeavesTranslationColumnAlone() {
        float[] m = input();
        float[] before = m.clone();
        Mat4.scaleM(m, 0, 7f, 11f, 13f);
        for (int i = 12; i < 16; i++) {
            assertEquals("translation element " + i, before[i], m[i], 0f);
        }
    }

    /**
     * The property that actually broke: after post-multiplying by
     * {@code diag(1,1,0)} the clip-w row must not depend on model z. If it
     * does, the perspective divide still varies with depth and the surface
     * keeps a fisheye bulge at Depth 0 instead of going flat.
     */
    @Test
    public void zeroingModelZRemovesDepthFromClipW() {
        float[] vp = {
                1f, 0f, 0f,  0.3f,
                0f, 1f, 0f, -0.2f,
                0f, 0f, 1f,  0.9f,
                0f, 0f, 0f,  1f,     // clip-w row: 0.3, -0.2, 0.9, 1
        };
        Mat4.scaleM(vp, 0, 1f, 1f, 0f);
        // clip-w coefficients are indices 3, 7, 11, 15
        assertEquals("model z must not reach clip w", 0f, vp[11], 0f);
        assertEquals("clip w x term preserved", 0.3f, vp[3], 1e-6f);
        assertEquals("clip w y term preserved", -0.2f, vp[7], 1e-6f);
        assertEquals("clip w w term preserved", 1f, vp[15], 1e-6f);
    }
}
