package com.amoshydra.basrelief.core.splat;

/**
 * The full-covariance Gaussian projection, as pure functions.
 *
 * <p><b>The chain, and why it is here rather than only in the shader.</b> A 3D
 * Gaussian is an ellipsoid, not a disc: its orientation matters as much as its
 * size, and the screen-space footprint is that ellipsoid seen through a
 * perspective camera. The chain is
 *
 * <pre>
 *   Sigma3D = R S S^T R^T          world covariance, S = diag(sx, sy, sz)
 *   SigmaV  = V Sigma3D V^T        V = the world-to-view rotation
 *   J       = d(screen)/d(view)    the perspective Jacobian
 *   Sigma2D = J SigmaV J^T         the screen-space covariance (2x2)
 *   conic   = Sigma2D^-1           the quadratic form the fragment shader uses
 *   extents = 3 sqrt(eigenvalues)  the quad's half-axes, at three sigma
 * </pre>
 *
 * <p>Every step is a plain function of plain values so it can be unit tested
 * without a device — the same reason {@code CameraModel}, {@code ReliefMesh} and
 * {@code Picture} live in {@code :core}. The vertex shader mirrors this chain;
 * {@code SplatProjectionTest} pins the relations that make it correct (the conic
 * is the inverse, the extent is three sigma, a degenerate covariance is refused).
 *
 * <p><b>The one relation that is easy to get quietly wrong.</b> At the 3-sigma
 * axis the Gaussian's value is {@code exp(-0.5 * 3^2) = exp(-4.5)}: the exponent
 * is {@code -0.5 * r^T conic r}, so at {@code r = 3 * sqrt(lambda)} along a
 * principal axis it is exactly {@code -4.5}, independent of the covariance. That
 * is the number {@link #EXP_AT_3_SIGMA} records and the test inverts.
 *
 * <p><b>A degenerate covariance is refused, not NaN'd.</b> A zero or
 * rank-deficient 2x2 has no inverse; returning infinities there would poison
 * every vertex that reads the conic. Every function that can divide returns
 * {@code false} instead, and the caller (the shader, or a test) treats that as
 * "draw nothing", which is the same rule {@code SplatCloud} follows for a
 * truncated record.
 */
public final class SplatProjection {

    /** The quad extends to three standard deviations, the usual 3DGS convention. */
    public static final float SIGMA = 3f;

    /** {@code exp(-0.5 * SIGMA^2)} — the Gaussian's value at the quad's edge. */
    public static final float EXP_AT_3_SIGMA = (float) Math.exp(-0.5 * SIGMA * SIGMA);

    /** Below this the 2x2 is treated as singular rather than inverted. */
    public static final float MIN_DET = 1e-9f;

    private SplatProjection() {
    }

    /**
     * The rotation matrix of a quaternion, row-major into {@code out[0..8]}.
     *
     * <p>{@code q} is {@code (w, x, y, z)} — the order the {@code .splat} record
     * stores, decoded to {@code [-1, 1]}. A zero-length quaternion is the
     * identity, because a window claims nothing and the alternative is a NaN
     * matrix.
     */
    public static void quatToRotation(float qw, float qx, float qy, float qz, float[] out) {
        float n = (float) Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        if (!(n > 1e-12f)) {
            qw = 1f; qx = 0f; qy = 0f; qz = 0f;
        } else {
            qw /= n; qx /= n; qy /= n; qz /= n;
        }
        out[0] = 1f - 2f * (qy * qy + qz * qz);
        out[1] = 2f * (qx * qy - qw * qz);
        out[2] = 2f * (qx * qz + qw * qy);
        out[3] = 2f * (qx * qy + qw * qz);
        out[4] = 1f - 2f * (qx * qx + qz * qz);
        out[5] = 2f * (qy * qz - qw * qx);
        out[6] = 2f * (qx * qz - qw * qy);
        out[7] = 2f * (qy * qz + qw * qx);
        out[8] = 1f - 2f * (qx * qx + qy * qy);
    }

    /**
     * {@code Sigma3D = R S S^T R^T}, row-major into {@code out[0..8]}.
     *
     * <p>{@code S} is the diagonal of the stored (already {@code exp}-decoded)
     * scales, so {@code S S^T} is {@code diag(sx^2, sy^2, sz^2)}. The result is
     * symmetric positive semi-definite by construction.
     */
    public static void covariance3d(float sx, float sy, float sz,
                                    float qw, float qx, float qy, float qz,
                                    float[] out) {
        float[] r = new float[9];
        quatToRotation(qw, qx, qy, qz, r);
        float a = sx * sx, b = sy * sy, c = sz * sz;
        // out = R * diag(a,b,c) * R^T  ==  sum_k (a R_k) R_k^T, R_k = k-th column.
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float c0 = r[i * 3] * r[j * 3];
                float c1 = r[i * 3 + 1] * r[j * 3 + 1];
                float c2 = r[i * 3 + 2] * r[j * 3 + 2];
                out[i * 3 + j] = a * c0 + b * c1 + c2 * c;
            }
        }
    }

    /**
     * {@code SigmaV = V Sigma3D V^T}, row-major into {@code out[0..8]}.
     *
     * <p>{@code v} is the world-to-view rotation, row-major. Both are 3x3, and
     * the result is the world covariance expressed in the camera's axes.
     */
    public static void viewCovariance(float[] cov3, float[] v, float[] out) {
        float[] tmp = new float[9];
        mul3(v, cov3, tmp);                 // tmp = V Sigma
        float[] vt = new float[9];
        transpose3(v, vt);
        mul3(tmp, vt, out);                 // out = V Sigma V^T
    }

    /**
     * The perspective Jacobian {@code d(screen)/d(view)}, row-major 2x3 into
     * {@code out[0..5]}.
     *
     * <p>{@code fx}, {@code fy} are the focal lengths in <b>pixels</b>
     * ({@code 0.5 * width * P[0]}, {@code 0.5 * height * P[5]}). The view-space
     * point is {@code (x, y, z)} with {@code z > 0} in front of the camera.
     *
     * <pre>
     *   [ fx/z   0     -fx x / z^2 ]
     *   [ 0      fy/z  -fy y / z^2 ]
     * </pre>
     */
    public static void jacobian(float fx, float fy, float x, float y, float z, float[] out) {
        float invd = 1f / z;
        float invd2 = invd * invd;
        out[0] = fx * invd;
        out[1] = 0f;
        out[2] = -fx * x * invd2;
        out[3] = 0f;
        out[4] = fy * invd;
        out[5] = -fy * y * invd2;
    }

    /**
     * {@code Sigma2D = J SigmaV J^T}, packed into {@code out = {c00, c01, c11}}.
     *
     * <p>Only the symmetric upper triangle is returned, because that is all the
     * conic and the eigen-decomposition read.
     */
    public static void covariance2d(float[] j, float[] cov3, float[] out) {
        // u = J * SigmaV  (2x3)
        float u00 = j[0] * cov3[0] + j[1] * cov3[3] + j[2] * cov3[6];
        float u01 = j[0] * cov3[1] + j[1] * cov3[4] + j[2] * cov3[7];
        float u02 = j[0] * cov3[2] + j[1] * cov3[5] + j[2] * cov3[8];
        float u10 = j[3] * cov3[0] + j[4] * cov3[3] + j[5] * cov3[6];
        float u11 = j[3] * cov3[1] + j[4] * cov3[4] + j[5] * cov3[7];
        float u12 = j[3] * cov3[2] + j[4] * cov3[5] + j[5] * cov3[8];
        // out = u * J^T
        out[0] = u00 * j[0] + u01 * j[1] + u02 * j[2];
        out[1] = u00 * j[3] + u01 * j[4] + u02 * j[5];
        out[2] = u10 * j[3] + u11 * j[4] + u12 * j[5];
    }

    /**
     * The conic {@code Sigma2D^-1}, packed {@code {xx, xy, yy}}.
     *
     * <p>The fragment shader's quadratic form is
     * {@code power = -0.5 (xx dx^2 + 2 xy dx dy + yy dy^2)}, so this is the
     * inverse's upper triangle, not the covariance's. Returns {@code false} and
     * leaves {@code out} zeroed when the 2x2 is singular — the shader then draws
     * nothing, and no infinity ever reaches a vertex.
     */
    public static boolean conic(float c00, float c01, float c11, float[] out) {
        float det = c00 * c11 - c01 * c01;
        if (!(det > MIN_DET) || !isFinite(det)) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f;
            return false;
        }
        out[0] = c11 / det;
        out[1] = -c01 / det;
        out[2] = c00 / det;
        return true;
    }

    /**
     * The quad's half-axes {@code r1 >= r2} at {@link #SIGMA} standard
     * deviations, into {@code out = {r1, r2}}.
     *
     * <p>For the symmetric 2x2 the eigenvalues are
     * {@code (c00 + c11)/2 +/- sqrt(((c00 - c11)/2)^2 + c01^2)}, and the extent
     * is {@code SIGMA * sqrt(lambda)}. {@code fudge} scales both (the shader's
     * coverage knob). Returns {@code false} and zeroes {@code out} for a
     * degenerate covariance.
     */
    public static boolean extents(float c00, float c01, float c11, float fudge, float[] out) {
        float det = c00 * c11 - c01 * c01;
        if (!(det > MIN_DET) || !isFinite(det)) {
            out[0] = 0f; out[1] = 0f;
            return false;
        }
        float mid = 0.5f * (c00 + c11);
        float root = (float) Math.sqrt(Math.max(mid * mid - det, 0f));
        float l1 = mid + root;
        float l2 = Math.max(mid - root, 0f);
        float k = SIGMA * (fudge > 0f ? fudge : 1f);
        out[0] = k * (float) Math.sqrt(Math.max(l1, 0f));
        out[1] = k * (float) Math.sqrt(Math.max(l2, 0f));
        return true;
    }

    /**
     * The Gaussian's exponent at screen offset {@code (dx, dy)}:
     * {@code -0.5 (xx dx^2 + 2 xy dx dy + yy dy^2)}.
     *
     * <p>The fragment shader discards when this is positive (outside the
     * ellipse) and multiplies the colour's alpha by {@code exp(power)}.
     */
    public static float power(float[] conic, float dx, float dy) {
        return -0.5f * (conic[0] * dx * dx + 2f * conic[1] * dx * dy + conic[2] * dy * dy);
    }

    /**
     * The full chain for one splat, into {@code out2d = {c00, c01, c11}}.
     *
     * <p>The convenience the tests and any CPU-side extent work share: it is the
     * same arithmetic the vertex shader performs, in one call. Returns false for
     * a degenerate covariance.
     */
    public static boolean project(float sx, float sy, float sz,
                                  float qw, float qx, float qy, float qz,
                                  float[] viewRot, float vx, float vy, float vz,
                                  float fx, float fy, float[] out2d) {
        float[] cov3 = new float[9];
        covariance3d(sx, sy, sz, qw, qx, qy, qz, cov3);
        float[] covV = new float[9];
        viewCovariance(cov3, viewRot, covV);
        float[] j = new float[6];
        jacobian(fx, fy, vx, vy, vz, j);
        covariance2d(j, covV, out2d);
        return out2d[0] * out2d[2] - out2d[1] * out2d[1] > MIN_DET;
    }

    // ------------------------------------------------------------- helpers

    private static void mul3(float[] a, float[] b, float[] out) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i * 3 + j] = a[i * 3] * b[j]
                        + a[i * 3 + 1] * b[3 + j]
                        + a[i * 3 + 2] * b[6 + j];
            }
        }
    }

    private static void transpose3(float[] a, float[] out) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) out[j * 3 + i] = a[i * 3 + j];
        }
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
