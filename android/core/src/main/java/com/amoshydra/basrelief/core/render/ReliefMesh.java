package com.amoshydra.basrelief.core.render;

/**
 * Builds a continuous displaced relief surface (no tearing) on the CPU:
 * vertex Z from the depth map, plus per-vertex normals averaged from the
 * surrounding faces so directional light can shade the relief.
 *
 * <p>Pure Java with no Android dependencies, so it can be unit-tested on the
 * JVM (see {@code ReliefMeshGoldenTest}, which pins the exact output so a
 * future refactor cannot silently reintroduce the depth-banding bug).
 */
public final class ReliefMesh {

    /**
     * Radius of the separable FLOAT box blur applied to the depth map before
     * tessellation, as a fraction of the depth map width. The 8-bit depth has
     * quantization terraces (~1 level per few texels on smooth faces); grazing
     * directional light turns them into visible contour lines. Blurring in
     * float (NOT re-quantizing to 8-bit) smooths the slope so normals vary
     * continuously. Verified in the web A/B: radius &gt;= width/78 fully
     * removes the banding.
     *
     * <p>Keep this in sync with {@code DEPTH_BLUR_FRACTION} in the web
     * renderer ({@code src/scene.js}).
     */
    public static final float DEPTH_BLUR_FRACTION = 1f / 78f;

    public final float[] positions; // xyz per vertex
    public final float[] uvs;       // uv per vertex
    public final float[] normals;   // xyz per vertex
    public final int vertexCount;

    private ReliefMesh(float[] positions, float[] uvs, float[] normals, int vertexCount) {
        this.positions = positions;
        this.uvs = uvs;
        this.normals = normals;
        this.vertexCount = vertexCount;
    }

    /**
     * @param depth  row-major 8-bit gray, depth[0] = top-left
     * @param dw, dh depth map size
     * @param grid   subdivisions per side
     * @param aspect image width / height (baked into vertex x)
     */
    public static ReliefMesh build(byte[] depth, int dw, int dh, int grid, float aspect) {
        float[] smooth = blur(depth, dw, dh);
        int n = grid + 1;
        float[] posV = new float[n * n * 3];
        float[] uvV = new float[n * n * 2];
        float[] nrmV = new float[n * n * 3];

        // 1. vertex positions (top row = gy 0, uv v=1 = image top);
        //    bilinear sampling of the float-blurred depth
        for (int gy = 0; gy < n; gy++) {
            for (int gx = 0; gx < n; gx++) {
                int idx = gy * n + gx;
                float u = (float) gx / grid;
                float v = 1f - (float) gy / grid;
                float fx = u * (dw - 1);
                float fy = (1 - v) * (dh - 1);
                int x0 = Math.min(dw - 1, (int) Math.floor(fx));
                int y0 = Math.min(dh - 1, (int) Math.floor(fy));
                int x1 = Math.min(dw - 1, x0 + 1);
                int y1 = Math.min(dh - 1, y0 + 1);
                float tx = fx - x0;
                float ty = fy - y0;
                float v00 = smooth[y0 * dw + x0];
                float v10 = smooth[y0 * dw + x1];
                float v01 = smooth[y1 * dw + x0];
                float v11 = smooth[y1 * dw + x1];
                float z = (((v00 * (1 - tx) + v10 * tx) * (1 - ty)
                        + (v01 * (1 - tx) + v11 * tx) * ty) / 255f) - 0.5f;
                posV[idx * 3] = (u - 0.5f) * aspect;
                posV[idx * 3 + 1] = 0.5f - (float) gy / grid;
                posV[idx * 3 + 2] = z;
                uvV[idx * 2] = u;
                uvV[idx * 2 + 1] = v;
            }
        }

        // 2. accumulate face normals
        for (int gy = 0; gy < grid; gy++) {
            for (int gx = 0; gx < grid; gx++) {
                int tl = gy * n + gx, tr = gy * n + gx + 1;
                int bl = (gy + 1) * n + gx, br = (gy + 1) * n + gx + 1;
                addFace(nrmV, posV, tl, bl, tr);
                addFace(nrmV, posV, tr, bl, br);
            }
        }

        // 3. normalize per-vertex normals
        for (int i = 0; i < n * n; i++) {
            float nx = nrmV[i * 3], ny = nrmV[i * 3 + 1], nz = nrmV[i * 3 + 2];
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 0f) {
                nrmV[i * 3] = nx / l;
                nrmV[i * 3 + 1] = ny / l;
                nrmV[i * 3 + 2] = nz / l;
            } else {
                nrmV[i * 3] = 0f;
                nrmV[i * 3 + 1] = 0f;
                nrmV[i * 3 + 2] = 1f;
            }
        }

        // 4. emit triangles (non-indexed)
        int verts = grid * grid * 6;
        float[] positions = new float[verts * 3];
        float[] uvs = new float[verts * 2];
        float[] normals = new float[verts * 3];
        int[] idx = new int[] { 0, 0, 0 }; // position, uv, normal write offsets
        for (int gy = 0; gy < grid; gy++) {
            for (int gx = 0; gx < grid; gx++) {
                int tl = gy * n + gx, tr = gy * n + gx + 1;
                int bl = (gy + 1) * n + gx, br = (gy + 1) * n + gx + 1;
                emit(positions, uvs, normals, idx, posV, uvV, nrmV, tl, bl, tr);
                emit(positions, uvs, normals, idx, posV, uvV, nrmV, tr, bl, br);
            }
        }
        return new ReliefMesh(positions, uvs, normals, verts);
    }

    private static void addFace(float[] nrm, float[] pos, int a, int b, int c) {
        float ax = pos[a * 3], ay = pos[a * 3 + 1], az = pos[a * 3 + 2];
        float bx = pos[b * 3], by = pos[b * 3 + 1], bz = pos[b * 3 + 2];
        float cx = pos[c * 3], cy = pos[c * 3 + 1], cz = pos[c * 3 + 2];
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float acx = cx - ax, acy = cy - ay, acz = cz - az;
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        nrm[a * 3] += nx; nrm[a * 3 + 1] += ny; nrm[a * 3 + 2] += nz;
        nrm[b * 3] += nx; nrm[b * 3 + 1] += ny; nrm[b * 3 + 2] += nz;
        nrm[c * 3] += nx; nrm[c * 3 + 1] += ny; nrm[c * 3 + 2] += nz;
    }

    private static void emit(float[] p, float[] u, float[] nm, int[] idx,
                             float[] pv, float[] uv, float[] nv, int a, int b, int c) {
        p[idx[0]] = pv[a * 3]; p[idx[0] + 1] = pv[a * 3 + 1]; p[idx[0] + 2] = pv[a * 3 + 2];
        u[idx[1]] = uv[a * 2]; u[idx[1] + 1] = uv[a * 2 + 1];
        nm[idx[2]] = nv[a * 3]; nm[idx[2] + 1] = nv[a * 3 + 1]; nm[idx[2] + 2] = nv[a * 3 + 2];
        idx[0] += 3; idx[1] += 2; idx[2] += 3;
        p[idx[0]] = pv[b * 3]; p[idx[0] + 1] = pv[b * 3 + 1]; p[idx[0] + 2] = pv[b * 3 + 2];
        u[idx[1]] = uv[b * 2]; u[idx[1] + 1] = uv[b * 2 + 1];
        nm[idx[2]] = nv[b * 3]; nm[idx[2] + 1] = nv[b * 3 + 1]; nm[idx[2] + 2] = nv[b * 3 + 2];
        idx[0] += 3; idx[1] += 2; idx[2] += 3;
        p[idx[0]] = pv[c * 3]; p[idx[0] + 1] = pv[c * 3 + 1]; p[idx[0] + 2] = pv[c * 3 + 2];
        u[idx[1]] = uv[c * 2]; u[idx[1] + 1] = uv[c * 2 + 1];
        nm[idx[2]] = nv[c * 3]; nm[idx[2] + 1] = nv[c * 3 + 1]; nm[idx[2] + 2] = nv[c * 3 + 2];
        idx[0] += 3; idx[1] += 2; idx[2] += 3;
    }

    /**
     * Separable box blur kept in float precision. Smooths the depth map's 8-bit
     * quantization terraces so the displaced surface has a continuous slope and
     * grazing directional light can't expose them as contour lines.
     *
     * <p>NOTE: the intermediate and result are {@code float} on purpose. Writing
     * back to an 8-bit array re-creates the terraces and the banding returns.
     */
    static float[] blur(byte[] src, int w, int h) {
        int r = Math.max(1, Math.round(w * DEPTH_BLUR_FRACTION));
        float[] tmp = new float[w * h];
        // horizontal pass
        for (int y = 0; y < h; y++) {
            float s = 0f;
            for (int x = -r; x <= r; x++) {
                int xx = Math.max(0, Math.min(w - 1, x));
                s += src[y * w + xx] & 0xff;
            }
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = s;
                int add = Math.max(0, Math.min(w - 1, x + r + 1));
                int sub = Math.max(0, Math.min(w - 1, x - r));
                s += (src[y * w + add] & 0xff) - (src[y * w + sub] & 0xff);
            }
        }
        // vertical pass
        float[] out = new float[w * h];
        int cnt = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            float s = 0f;
            for (int y = -r; y <= r; y++) {
                int yy = Math.max(0, Math.min(h - 1, y));
                s += tmp[yy * w + x];
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = s / (cnt * cnt);
                int add = Math.max(0, Math.min(h - 1, y + r + 1));
                int sub = Math.max(0, Math.min(h - 1, y - r));
                s += tmp[add * w + x] - tmp[sub * w + x];
            }
        }
        return out;
    }
}
