package com.amoshydra.basrelief.core.render;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Regenerates the mesh goldens from the CURRENT code.
 *
 * <p>Deliberately not a JUnit test, because a test that rewrites its own
 * expectation can never fail. Run it by hand, read the diff, and only then
 * decide whether the geometry change was intended:
 *
 * <pre>
 *   ./gradlew :core:classes
 *   java -cp core/build/classes/java/main:core/src/test/resources \
 *        com.amoshydra.basrelief.core.render.RegenerateGoldens \
 *        core/src/test/resources/golden
 * </pre>
 *
 * <p>The input depth map ({@code mesh-depth.bin}) is NOT regenerated — it is
 * the fixed stimulus the test is defined against. Only the expected geometry
 * is rewritten.
 */
public final class RegenerateGoldens {

    private static final int W = 256, H = 320, GRID = 32;
    private static final float ASPECT = W / (float) H;

    public static void main(String[] args) throws IOException {
        String dir = args.length > 0 ? args[0] : "core/src/test/resources/golden";
        byte[] depth = readAll(dir + "/mesh-depth.bin");
        if (depth.length != W * H) {
            throw new IOException("unexpected depth size " + depth.length);
        }
        ReliefMesh mesh = ReliefMesh.build(depth, W, H, GRID, ASPECT);
        writeFloats(dir + "/mesh-positions.bin", mesh.positions);
        writeFloats(dir + "/mesh-normals.bin", mesh.normals);
        System.out.println("regenerated from " + mesh.vertexCount + " vertices -> " + dir);
    }

    private static void writeFloats(String path, float[] a) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(path))) {
            o.writeInt(a.length);
            for (float v : a) o.writeFloat(v);
        }
    }

    private static byte[] readAll(String path) throws IOException {
        try (InputStream in = new java.io.FileInputStream(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] all = bos.toByteArray();
            int len = ((all[0] & 0xff) << 24) | ((all[1] & 0xff) << 16)
                    | ((all[2] & 0xff) << 8) | (all[3] & 0xff);
            byte[] body = new byte[len];
            System.arraycopy(all, 4, body, 0, len);
            return body;
        }
    }
}
