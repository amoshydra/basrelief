package com.amoshydra.basrelief.core.render;

/**
 * Minimal column-major 4x4 matrix maths, mirroring the subset of
 * {@code android.opengl.Matrix} that the renderer used to call.
 *
 * <p>It exists so the camera model is a pure JVM class and can be unit-tested
 * without an Android device or instrumentation runner. Results are
 * bit-for-bit the same operations in the same order as the Android originals.
 *
 * <p>Layout: {@code m[col * 4 + row]}, identical to OpenGL / android.opengl.
 * All methods write into a caller-supplied array and return nothing
 * (matching the Android API where practical), except {@link #invert} which
 * reports success because the renderer relies on that.
 */
public final class Mat4 {

    private Mat4() {
    }

    public static void identity(float[] m, int off) {
        for (int i = 0; i < 16; i++) m[off + i] = 0f;
        m[off] = m[off + 5] = m[off + 10] = m[off + 15] = 1f;
    }

    /** {@code result = lhs * rhs}. */
    public static void multiplyMM(float[] result, int resultOff,
                                   float[] lhs, int lhsOff,
                                   float[] rhs, int rhsOff) {
        float[] t = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) {
                    s += lhs[lhsOff + k * 4 + r] * rhs[rhsOff + c * 4 + k];
                }
                t[c * 4 + r] = s;
            }
        }
        System.arraycopy(t, 0, result, resultOff, 16);
    }

    /**
     * In-place {@code m = m * S} where {@code S = diag(sx, sy, sz)} — i.e.
     * POST-multiply, scaling the model before the view and projection are
     * applied. This is the in-place form of the 7-argument
     * {@code Matrix.scaleM(result, ro, lhs, lo, ...)} overload.
     *
     * <p>In column-major storage {@code m[col * 4 + row]}, right-multiplying by
     * a diagonal scales COLUMN c by {@code S[c][c]}. So this multiplies
     * elements 0..3 by sx, 4..7 by sy and 8..11 by sz, and leaves 12..15 (the
     * translation column) alone.
     *
     * <p>Do NOT "simplify" this into a stride-1 loop over rows. That computes
     * {@code S * m} instead, which scales the camera axes rather than the model
     * — it looks plausible but leaves {@code clip.w} depending on model z, so
     * the relief keeps a fisheye bulge even at zero depth and the Depth slider
     * appears to do nothing except relight. Verified against the real
     * {@code android.opengl.Matrix.scaleM} on-device.
     */
    public static void scaleM(float[] m, int off, float sx, float sy, float sz) {
        for (int i = 0; i < 4; i++) m[off + i] *= sx;
        for (int i = 4; i < 8; i++) m[off + i] *= sy;
        for (int i = 8; i < 12; i++) m[off + i] *= sz;
    }

    public static void translateM(float[] m, int off, float x, float y, float z) {
        for (int c = 0; c < 4; c++) {
            m[off + c * 4] += x * m[off + c * 4 + 3];
            m[off + c * 4 + 1] += y * m[off + c * 4 + 3];
            m[off + c * 4 + 2] += z * m[off + c * 4 + 3];
        }
    }

    /**
     * Right-handed perspective projection mapping z to [-1, 1] (GL convention),
     * matching {@code Matrix.perspectiveM}.
     */
    public static void perspective(float[] m, int off, float fovyDeg, float aspect,
                                   float near, float far) {
        double f = 1.0 / Math.tan(Math.toRadians(fovyDeg) * 0.5);
        identity(m, off);
        m[off] = (float) (f / aspect);
        m[off + 5] = (float) f;
        m[off + 10] = (far + near) / (near - far);
        m[off + 11] = -1f;
        m[off + 14] = (float) (2.0 * far * near / (near - far));
        m[off + 15] = 0f;
    }

    /** View matrix looking from the eye toward the centre (matches setLookAtM). */
    public static void lookAt(float[] m, int off,
                              float eyeX, float eyeY, float eyeZ,
                              float cx, float cy, float cz,
                              float upX, float upY, float upZ) {
        float zx = eyeX - cx, zy = eyeY - cy, zz = eyeZ - cz;
        float zl = (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
        if (zl == 0f) zl = 1f;
        zx /= zl; zy /= zl; zz /= zl;

        float xx = upY * zz - upZ * zy;
        float xy = upZ * zx - upX * zz;
        float xz = upX * zy - upY * zx;
        float xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
        if (xl == 0f) xl = 1f;
        xx /= xl; xy /= xl; xz /= xl;

        float yx = zy * xz - zz * xy;
        float yy = zz * xx - zx * xz;
        float yz = zx * xy - zy * xx;

        m[off] = xx; m[off + 1] = yx; m[off + 2] = zx; m[off + 3] = 0f;
        m[off + 4] = xy; m[off + 5] = yy; m[off + 6] = zy; m[off + 7] = 0f;
        m[off + 8] = xz; m[off + 9] = yz; m[off + 10] = zz; m[off + 11] = 0f;
        m[off + 12] = -(xx * eyeX + xy * eyeY + xz * eyeZ);
        m[off + 13] = -(yx * eyeX + yy * eyeY + yz * eyeZ);
        m[off + 14] = -(zx * eyeX + zy * eyeY + zz * eyeZ);
        m[off + 15] = 1f;
    }

    /** {@code result = m * v} for a 4-vector; returns the w component. */
    public static float multiplyMV(float[] result, int resultOff,
                                    float[] m, int mOff,
                                    float[] v, int vOff) {
        float[] t = new float[4];
        for (int r = 0; r < 4; r++) {
            float s = 0f;
            for (int k = 0; k < 4; k++) {
                s += m[mOff + k * 4 + r] * v[vOff + k];
            }
            t[r] = s;
        }
        System.arraycopy(t, 0, result, resultOff, 4);
        return t[3];
    }

    /** General 4x4 inverse via cofactors. Returns false if singular. */
    public static boolean invert(float[] mInv, int invOff, float[] m, int mOff) {
        float[] inv = new float[16];
        float[] a = new float[16];
        System.arraycopy(m, mOff, a, 0, 16);

        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15]
                + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15]
                - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15]
                + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14]
                - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15]
                - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15]
                + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15]
                - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14]
                + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15]
                + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15]
                - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15]
                + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14]
                - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11]
                - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11]
                + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11]
                - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10]
                + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];

        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (det == 0f) return false;
        float d = 1f / det;
        for (int i = 0; i < 16; i++) inv[i] *= d;
        System.arraycopy(inv, 0, mInv, invOff, 16);
        return true;
    }
}
