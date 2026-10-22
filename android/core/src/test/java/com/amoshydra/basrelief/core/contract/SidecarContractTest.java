package com.amoshydra.basrelief.core.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.amoshydra.basrelief.core.render.SplatSidecar;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * The splat sidecar, against {@code contract/sidecar/} — the golden the node's
 * writer is held to by {@code server/comfy-node/tests/test_contract.py}.
 *
 * <p>Why a fixture at all: the node writes this file and the app reads a lens out
 * of it, and the failure is silent either way. A missing {@code focal} must mean
 * "no claim" — the cloud draws at the demo lens — not a zero focal, which would
 * divide to a 0-degree camera or a NaN and draw nothing.
 */
public class SidecarContractTest {

    private static JSONObject cases() {
        return ContractFiles.json("sidecar", "cases.json");
    }

    private static SplatSidecar read(String file) {
        return SplatSidecar.parse(
                new String(ContractFiles.bytes("sidecar", file), StandardCharsets.UTF_8));
    }

    /** The golden: three numbers in, one dimensionless lens out. */
    @Test
    public void theGoldenIsTheShapeTheNodeWrites() {
        SplatSidecar side = read("splat-sidecar.json");
        assertNotNull(side);
        assertEquals(800, side.focal);
        assertEquals(1200, side.width);
        assertEquals(1600, side.height);
        assertEquals(0.5f, side.lens(), 1e-6f);
    }

    /**
     * Every reader case in the table, including the two absences and the node's own
     * 0. This is the half a screenshot cannot check: a missing lens draws the
     * default look, which looks perfectly fine and is the wrong camera.
     */
    @Test
    public void anAbsentOrZeroNumberIsNoClaimRatherThanZero() {
        org.json.JSONArray table = cases().getJSONArray("cases");
        for (int i = 0; i < table.length(); i++) {
            JSONObject c = table.getJSONObject(i);
            String file = c.getString("file");
            if (c.optBoolean("unparseable", false)) {
                assertNull(file + ": an unreadable body is no sidecar", read(file));
                continue;
            }
            SplatSidecar side = read(file);
            assertNotNull(file, side);
            assertEquals(file + ": focal", c.getInt("focal"), side.focal);
            assertEquals(file + ": width", c.getInt("width"), side.width);
            assertEquals(file + ": height", c.getInt("height"), side.height);
            assertEquals(file + ": lens", c.getDouble("lens"), side.lens(), 1e-6);
        }
    }

    /** A focal with no frame implies no lens; the focal itself still reads back. */
    @Test
    public void aFocalWithoutItsFrameNamesNoLens() {
        SplatSidecar side = read("no-height.json");
        assertNotNull(side);
        assertEquals(800, side.focal);
        assertEquals(0, side.height);
        assertEquals(0f, side.lens(), 0f);
    }

    /** A body that is not a JSON object is no sidecar, not a sidecar with no lens. */
    @Test
    public void anUnreadableBodyIsNoSidecar() {
        assertNull(read("unreadable.json"));
        assertNull(SplatSidecar.parse(null));
        assertNull(SplatSidecar.parse(""));
        assertNull(SplatSidecar.parse("[]"));
    }

    /**
     * The lens is a ratio, so the same camera at two decode sizes is one lens — the
     * property that lets the phone decode the still at whatever height it wants.
     */
    @Test
    public void theLensDoesNotDependOnTheFramesPixelSize() {
        SplatSidecar big = SplatSidecar.parse("{\"focal\":800,\"width\":1200,\"height\":1600}");
        SplatSidecar small = SplatSidecar.parse("{\"focal\":400,\"width\":600,\"height\":800}");
        assertNotNull(big);
        assertNotNull(small);
        assertEquals(big.lens(), small.lens(), 1e-6f);
    }
}
