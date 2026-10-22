package com.amoshydra.basrelief.core.splat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The full-covariance projection chain, and above all the two relations a
 * screenshot cannot check: the conic is the covariance's inverse, and the quad's
 * extent is exactly where the Gaussian's exponent is {@code -4.5}.
 *
 * <p>It lives in {@code :core} for the same reason {@code CameraModel} does: the
 * vertex shader mirrors this arithmetic, and a sign or a transpose in it is
 * invisible until the cloud renders as the wrong shape — or, in the degenerate
 * case, as NaN triangles that vanish with no error.
 */
public class SplatProjectionTest {

    private static final float EPS = 1e-4f;

    /** {@code out = a * b}, row-major 3x3. */
    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                o[i * 3 + j] = a[i * 3] * b[j]
                        + a[i * 3 + 1] * b[3 + j]
                        + a[i * 3 + 2] * b[6 + j];
            }
        }
        return o;
    }

    private static float[] diag(float a, float b, float c) {
        return new float[] { a, 0, 0, 0, b, 0, 0, 0, c };
    }

    // ------------------------------------------------------- covariance3d

    @Test
    public void anIdentityQuaternionLeavesTheScalesOnTheDiagonal() {
        float[] out = new float[9];
        SplatProjection.covariance3d(0.5f, 1f, 2f, 1f, 0f, 0f, 0f, out);
        assertEquals(0.25f, out[0], EPS);
        assertEquals(1f, out[4], EPS);
        assertEquals(4f, out[8], EPS);
        assertEquals(0f, out[1], EPS);
        assertEquals(0f, out[2], EPS);
        assertEquals(0f, out[5], EPS);
    }

    /** A 90-degree rotation about z swaps the x and y variances. */
    @Test
    public void aRotationTurnsTheCovariance() {
        float h = (float) Math.sqrt(0.5);
        float[] out = new float[9];
        SplatProjection.covariance3d(2f, 1f, 1f, h, 0f, 0f, h, out);
        // R diag(4,1,1) R^T with R = +90 about z: x-variance -> y.
        assertEquals(1f, out[0], EPS);
        assertEquals(4f, out[4], EPS);
        assertEquals(1f, out[8], EPS);
        assertEquals(0f, out[1], EPS);
    }

    @Test
    public void theCovarianceIsSymmetric() {
        float[] out = new float[9];
        SplatProjection.covariance3d(0.3f, 0.9f, 0.4f, 0.7f, 0.1f, 0.5f, -0.3f, out);
        assertEquals(out[1], out[3], 0f);
        assertEquals(out[2], out[6], 0f);
        assertEquals(out[5], out[7], 0f);
    }

    @Test
    public void aZeroQuaternionIsTheIdentityNotNaN() {
        float[] out = new float[9];
        SplatProjection.covariance3d(1f, 1f, 1f, 0f, 0f, 0f, 0f, out);
        assertEquals(1f, out[0], EPS);
        assertEquals(1f, out[4], EPS);
        assertEquals(1f, out[8], EPS);
    }

    // ------------------------------------------------------ viewCovariance

    @Test
    public void anIdentityViewLeavesTheWorldCovarianceAlone() {
        float[] cov = diag(1f, 2f, 3f);
        float[] out = new float[9];
        SplatProjection.viewCovariance(cov, diag(1f, 1f, 1f), out);
        assertEquals(1f, out[0], EPS);
        assertEquals(2f, out[4], EPS);
        assertEquals(3f, out[8], EPS);
    }

    @Test
    public void theViewRotationConjugatesTheCovariance() {
        // V = 90 degrees about z, world->view. V Sigma V^T swaps x and y.
        float[] v = new float[] { 0, 1, 0, -1, 0, 0, 0, 0, 1 };
        float[] cov = diag(4f, 1f, 1f);
        float[] out = new float[9];
        SplatProjection.viewCovariance(cov, v, out);
        float[] expected = mul(v, mul(cov, new float[] { 0, -1, 0, 1, 0, 0, 0, 0, 1 }));
        for (int i = 0; i < 9; i++) assertEquals(expected[i], out[i], EPS);
        assertEquals(1f, out[0], EPS);
        assertEquals(4f, out[4], EPS);
    }

    // ---------------------------------------------------------- jacobian

    @Test
    public void theJacobianIsThePerspectiveDerivative() {
        float[] j = new float[6];
        SplatProjection.jacobian(100f, 200f, 0.5f, -0.25f, 2f, j);
        assertEquals(50f, j[0], EPS);            // fx / z
        assertEquals(0f, j[1], EPS);
        assertEquals(-12.5f, j[2], EPS);         // -fx x / z^2
        assertEquals(0f, j[3], EPS);
        assertEquals(100f, j[4], EPS);           // fy / z
        assertEquals(12.5f, j[5], EPS);          // -fy y / z^2
    }

    @Test
    public void theCovarianceAtTheCentreIsTheScaledIdentity() {
        float[] j = new float[6];
        SplatProjection.jacobian(100f, 100f, 0f, 0f, 2f, j);
        float[] out = new float[3];
        SplatProjection.covariance2d(j, diag(1f, 1f, 1f), out);
        assertEquals(2500f, out[0], EPS);        // (100/2)^2
        assertEquals(0f, out[1], EPS);
        assertEquals(2500f, out[2], EPS);
    }

    // ------------------------------------------------- conic and extents

    @Test
    public void theConicIsTheInverse() {
        float[] conic = new float[3];
        assertTrue(SplatProjection.conic(4f, 1f, 1f, conic));
        // Sigma * Sigma^-1 = I, read on the symmetric upper triangle.
        assertEquals(1f, 4f * conic[0] + 1f * conic[1], EPS);
        assertEquals(0f, 1f * conic[0] + 1f * conic[1], EPS);
        assertEquals(1f, 1f * conic[1] + 1f * conic[2], EPS);
    }

    @Test
    public void theExtentsAreThreeSigma() {
        float[] r = new float[2];
        assertTrue(SplatProjection.extents(4f, 0f, 1f, 1f, r));
        assertEquals(6f, r[0], EPS);             // 3 * sqrt(4)
        assertEquals(3f, r[1], EPS);             // 3 * sqrt(1)
    }

    /**
     * <b>The number the whole chain exists to get right.</b> The Gaussian's
     * exponent is {@code -0.5 r^T Sigma^-1 r}, so at the 3-sigma extent along a
     * principal axis it must be exactly {@code -4.5} for every covariance, and
     * therefore {@code exp(power) == exp(-4.5)}. A chain that put the extent at
     * two sigma, or forgot the half, would still render a plausible ellipse —
     * this is what makes the quad's edge the Gaussian's actual edge.
     */
    @Test
    public void theConicAtThreeSigmaIsExpMinusFourPointFive() {
        // An anisotropic covariance with an off-diagonal term: the pin must hold
        // on both principal axes, not only the axis-aligned case.
        float c00 = 3.2f, c01 = 0.7f, c11 = 1.1f;
        float[] conic = new float[3];
        float[] r = new float[2];
        assertTrue(SplatProjection.conic(c00, c01, c11, conic));
        assertTrue(SplatProjection.extents(c00, c01, c11, 1f, r));

        float[][] ax = principalAxes(c00, c01, c11);
        float major = SplatProjection.power(conic, r[0] * ax[0][0], r[0] * ax[0][1]);
        float minor = SplatProjection.power(conic, r[1] * ax[1][0], r[1] * ax[1][1]);
        assertEquals(-4.5f, major, 1e-3f);
        assertEquals(-4.5f, minor, 1e-3f);
        assertEquals(SplatProjection.EXP_AT_3_SIGMA, (float) Math.exp(major), 1e-4f);
        assertEquals((float) Math.exp(-4.5f), SplatProjection.EXP_AT_3_SIGMA, 0f);
    }

    /** The two unit principal directions of a symmetric 2x2, as rows. */
    private static float[][] principalAxes(float c00, float c01, float c11) {
        float[][] out = new float[2][2];
        // Eigenvector for the larger eigenvalue: (c01, l1 - c00), normalised.
        float l1 = 0.5f * (c00 + c11) + (float) Math.sqrt(
                Math.max(0.25f * (c00 - c11) * (c00 - c11) + c01 * c01, 0f));
        float vx = c01, vy = l1 - c00;
        float n = (float) Math.sqrt(vx * vx + vy * vy);
        if (!(n > 1e-9f)) { vx = 1f; vy = 0f; n = 1f; }
        out[0][0] = vx / n;
        out[0][1] = vy / n;
        out[1][0] = -out[0][1];
        out[1][1] = out[0][0];
        return out;
    }

    /**
     * A degenerate covariance is refused, not inverted. Zero, rank-one and
     * zero-area 2x2s all have no finite inverse, and the shader that reads the
     * conic would otherwise draw NaN triangles that disappear silently.
     */
    @Test
    public void aDegenerateCovarianceIsRefused() {
        float[] conic = new float[3];
        float[] r = new float[2];
        assertFalse(SplatProjection.conic(0f, 0f, 0f, conic));
        assertFalse(SplatProjection.conic(1f, 1f, 1f, conic));      // det 0
        assertFalse(SplatProjection.conic(1f, 2f, 4f, conic));      // det 0
        assertFalse(SplatProjection.conic(Float.NaN, 0f, 1f, conic));
        assertFalse(SplatProjection.extents(0f, 0f, 0f, 1f, r));
        assertFalse(SplatProjection.extents(1f, 2f, 4f, 1f, r));
        for (float v : conic) assertFalse(Float.isNaN(v));
        for (float v : r) assertFalse(Float.isNaN(v));
        assertEquals(0f, conic[0], 0f);
        assertEquals(0f, r[0], 0f);
    }

    // ---------------------------------------------------------- the chain

    @Test
    public void theFullChainIsSymmetricPositiveDefinite() {
        float h = (float) Math.sqrt(0.5);
        float[] out = new float[3];
        assertTrue(SplatProjection.project(0.4f, 0.9f, 0.2f, h, 0f, h, 0f,
                diag(1f, 1f, 1f), 0.3f, -0.2f, 3f, 400f, 400f, out));
        assertTrue(out[0] > 0f);
        assertTrue(out[2] > 0f);
        assertTrue(out[0] * out[2] - out[1] * out[1] > 0f);
    }

    /**
     * A sphere's screen footprint is the same however the camera is rotated: the
     * covariance is isotropic, so no rotation can change it. This is the
     * invariant that catches a transposed view rotation, which for an
     * anisotropic splat can look plausible and for a sphere cannot.
     */
    @Test
    public void aSphericalSplatIsViewInvariant() {
        float[] a = new float[3];
        float[] b = new float[3];
        float[] identity = diag(1f, 1f, 1f);
        float[] rotY = new float[] { 0, 0, 1, 0, 1, 0, -1, 0, 0 };
        assertTrue(SplatProjection.project(0.5f, 0.5f, 0.5f, 1f, 0f, 0f, 0f,
                identity, 0f, 0f, 2f, 200f, 200f, a));
        assertTrue(SplatProjection.project(0.5f, 0.5f, 0.5f, 1f, 0f, 0f, 0f,
                rotY, 0f, 0f, 2f, 200f, 200f, b));
        assertEquals(a[0], b[0], EPS);
        assertEquals(a[1], b[1], EPS);
        assertEquals(a[2], b[2], EPS);
    }

    @Test
    public void aQuaternionAndItsNegationGiveTheSameCovariance() {
        float h = (float) Math.sqrt(0.5);
        float[] a = new float[9];
        float[] b = new float[9];
        SplatProjection.covariance3d(0.4f, 0.9f, 0.2f, h, 0f, h, 0f, a);
        SplatProjection.covariance3d(0.4f, 0.9f, 0.2f, -h, 0f, -h, 0f, b);
        for (int i = 0; i < 9; i++) assertEquals(a[i], b[i], EPS);
    }
}
