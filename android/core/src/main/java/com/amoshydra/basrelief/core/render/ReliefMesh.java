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
     * Radius of the separable FLOAT box blur, as a fraction of the GRID width.
     * The 8-bit depth has quantization terraces (~1 level per few texels on
     * smooth faces); grazing directional light turns them into visible contour
     * lines. Blurring in float (NOT re-quantizing to 8-bit) smooths the slope
     * so normals vary continuously. Verified in the web A/B: radius &gt;= width/78
     * fully removes the banding.
     *
     * <p>The blur runs on the grid-resolution height field rather than on the
     * source depth map, because that is the only resolution the mesh ever
     * samples. A source-resolution blur of radius {@code sourceW/78} works out
     * to {@code grid/78} in grid cells regardless of the source size, so the
     * smoothing is the same while the cost stops scaling with the photo. The
     * old order (blur at source resolution, then sample) also allocated two
     * full-size float buffers, which on a 12MP depth map is ~100MB.
     *
     * <p>Keep this in sync with {@code DEPTH_BLUR_FRACTION} in the web
     * renderer ({@code src/scene.js}).
     */
    public static final float DEPTH_BLUR_FRACTION = 1f / 78f;

    /** The n*n blurred height field the mesh was built from, in model units. */
    public final float[] field;

    public final float[] positions; // xyz per vertex
    public final float[] uvs;       // uv per vertex
    public final float[] normals;   // xyz per vertex
    public final int vertexCount;

    private ReliefMesh(float[] field, float[] positions, float[] uvs, float[] normals,
                       int vertexCount) {
        this.field = field;
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
        int n = grid + 1;
        // Resample to grid resolution FIRST, then blur at that resolution. The
        // mesh only ever reads the field at these n*n points, so doing the work
        // at source resolution was ~99% wasted.
        float[] smooth = blurredField(depth, dw, dh, grid);
        float[] posV = new float[n * n * 3];
        float[] uvV = new float[n * n * 2];
        float[] nrmV = new float[n * n * 3];

        // 1. vertex positions (top row = gy 0, uv v=1 = image top);
        //    bilinear sampling of the float-blurred depth
        // 1. vertex positions (top row = gy 0, uv v=1 = image top).
        //    The bilinear sampling of the source depth map already happened in
        //    resample(), so this just reads the grid point it produced.
        for (int gy = 0; gy < n; gy++) {
            for (int gx = 0; gx < n; gx++) {
                int idx = gy * n + gx;
                float u = (float) gx / grid;
                float v = 1f - (float) gy / grid;
                float z = (smooth[idx] / 255f) - 0.5f;
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
        float[] zOut = new float[n * n];
        for (int i = 0; i < n * n; i++) zOut[i] = posV[i * 3 + 2];
        return new ReliefMesh(zOut, positions, uvs, normals, verts);
    }

    /**
     * The blurred {@code (grid+1)^2} float height field, in {@code 0..255}, that
     * the GPU displacement path uploads as a texture.
     *
     * <p>This is the one half of {@link #build} that must stay on the CPU: the
     * float blur is what removes 8-bit depth banding, and it is deliberately not
     * re-quantised. The other half -- displacing vertices, averaging normals and
     * emitting 393k duplicated vertices into three 12.6 MB buffers -- moves to
     * the vertex shader, which samples this field, so nothing here pays for a
     * triangulation it no longer draws.
     *
     * <p>{@code build} calls this too, so the two paths cannot disagree about the
     * field they start from.
     *
     * @param depth row-major 8-bit gray, depth[0] = top-left
     * @param dw,   dh depth map size
     * @param grid  subdivisions per side
     */
    public static float[] blurredField(byte[] depth, int dw, int dh, int grid) {
        int n = grid + 1;
        return blur(resample(depth, dw, dh, n, grid), n, n);
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
    /**
     * Bilinear resample of the 8-bit depth map down to the n*n grid points the
     * mesh actually uses. Top row (index 0) is the image top, matching the uv
     * convention in {@link #build}.
     */
    static float[] resample(byte[] src, int dw, int dh, int n, int grid) {
        float[] out = new float[n * n];
        for (int gy = 0; gy < n; gy++) {
            for (int gx = 0; gx < n; gx++) {
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
                float v00 = src[y0 * dw + x0] & 0xff;
                float v10 = src[y0 * dw + x1] & 0xff;
                float v01 = src[y1 * dw + x0] & 0xff;
                float v11 = src[y1 * dw + x1] & 0xff;
                out[gy * n + gx] = (v00 * (1 - tx) + v10 * tx) * (1 - ty)
                        + (v01 * (1 - tx) + v11 * tx) * ty;
            }
        }
        return out;
    }

    /**
     * Separable box blur kept in float precision. Smooths the depth map's 8-bit
     * quantization terraces so the displaced surface has a continuous slope and
     * grazing directional light can't expose them as contour lines.
     *
     * <p>NOTE: the intermediate and result are {@code float} on purpose. Writing
     * back to an 8-bit array re-creates the terraces and the banding returns.
     */
    static float[] blur(float[] src, int w, int h) {
        int r = Math.max(1, Math.round(w * DEPTH_BLUR_FRACTION));
        float[] tmp = new float[w * h];
        // horizontal pass
        for (int y = 0; y < h; y++) {
            float acc = 0f;
            for (int x = -r; x <= r; x++) {
                int xx = Math.max(0, Math.min(w - 1, x));
                acc += src[y * w + xx];
            }
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = acc;
                int add = Math.max(0, Math.min(w - 1, x + r + 1));
                int sub = Math.max(0, Math.min(w - 1, x - r));
                acc += src[y * w + add] - src[y * w + sub];
            }
        }
        // vertical pass
        float[] out = new float[w * h];
        int cnt = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            float acc = 0f;
            for (int y = -r; y <= r; y++) {
                int yy = Math.max(0, Math.min(h - 1, y));
                acc += tmp[yy * w + x];
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = acc / (cnt * cnt);
                int add = Math.max(0, Math.min(h - 1, y + r + 1));
                int sub = Math.max(0, Math.min(h - 1, y - r));
                acc += tmp[add * w + x] - tmp[sub * w + x];
            }
        }
        return out;
    }

}
