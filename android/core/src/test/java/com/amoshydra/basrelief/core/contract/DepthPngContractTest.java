package com.amoshydra.basrelief.core.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.depth.DepthPng;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The embedded-depth chunk, against {@code contract/deph/} — the same PNG files
 * {@code reference/webgl-port/src/png-depth.js} is tested with by
 * {@code contract/deph/png-depth.test.mjs}.
 *
 * <p>Why a fixture at all: the app and the reference port each parse a photograph's
 * {@code dePH}/{@code dEPt} chunk and hand the renderer a depth map. The two cannot
 * share code, and a disagreement is not an error on either side — it is a photo
 * that has relief on the phone and none on the web.
 *
 * <p>The fixture's payload bytes are deliberately unlike the outer image's pixels,
 * so returning the COLOUR image where the depth belongs fails the digest rather
 * than passing with a plausible picture.
 */
public class DepthPngContractTest {

    private static JSONArray cases() {
        return ContractFiles.json("deph", "cases.json").getJSONArray("cases");
    }

    @Test
    public void everyFixtureMatchesTheContract() {
        JSONArray cases = cases();
        assertTrue("the table is empty", cases.length() > 0);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String file = c.getString("file");
            String status = c.getString("status");
            DepthPng.Result res = DepthPng.extract(ContractFiles.bytes("deph", file));

            if (!"ok".equals(status)) {
                assertNull(file + ": a refused fixture must answer null", res);
                continue;
            }

            assertNotNull(file + ": " + c.getString("why"), res);
            assertEquals(file + ": payload length",
                    c.getInt("payloadBytes"), payload(res).length);
            assertEquals(file + ": payload bytes",
                    c.getString("payloadSha256"), ContractFiles.sha256(payload(res)));

            if ("raw".equals(c.getString("form"))) {
                assertNull(file + ": raw grey is not a PNG payload", res.png);
                assertNotNull(file + ": raw grey payload", res.rawGray);
                assertEquals(file + ": width", c.getInt("width"), res.rawW);
                assertEquals(file + ": height", c.getInt("height"), res.rawH);
            } else {
                assertEquals(file + ": a whole PNG is passed through", "png",
                        c.getString("form"));
                assertNull(file + ": a PNG payload is not raw grey", res.rawGray);
                assertNotNull(file + ": PNG payload", res.png);
                assertEquals(file + ": the PNG signature", 0x89, res.png[0] & 0xff);
            }
        }
    }

    /**
     * The refusals are three different reasons and the Java API answers one null for
     * all of them, deliberately: the app only ever asks "is there depth here". The
     * reasons are still in the table — the JS test pins the exact code — and this
     * asserts that each is represented, so a fixture cannot quietly become a no-op.
     */
    @Test
    public void everyRefusalInTheTableIsRepresented() {
        JSONArray cases = cases();
        boolean notPng = false;
        boolean noChunk = false;
        boolean badPayload = false;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            if ("ok".equals(c.getString("status"))) continue;
            String status = c.getString("status");
            notPng |= "not-png".equals(status);
            noChunk |= "no-depth-chunk".equals(status);
            badPayload |= "bad-payload".equals(status);
            assertNull(c.getString("file"), DepthPng.extract(
                    ContractFiles.bytes("deph", c.getString("file"))));
        }
        assertTrue("no not-png fixture", notPng);
        assertTrue("no no-depth-chunk fixture", noChunk);
        assertTrue("no bad-payload fixture", badPayload);
    }

    /**
     * Raw grey takes its dimensions from the OUTER IHDR, so the fixture that places
     * the same payload before IHDR must be refused: the chunk claims nothing.
     */
    @Test
    public void aRawPayloadBeforeIhdrClaimsNothing() {
        JSONObject c = find("raw-before-ihdr.png");
        assertEquals("bad-payload", c.getString("status"));
        assertNull(DepthPng.extract(ContractFiles.bytes("deph", "raw-before-ihdr.png")));
    }

    /** Matching the key is case-insensitive, and both spellings are ordinary chunks. */
    @Test
    public void theChunkKeyIsCaseInsensitive() {
        for (String file : new String[]{"case-dEPt.png", "case-DEPH.png"}) {
            DepthPng.Result res = DepthPng.extract(ContractFiles.bytes("deph", file));
            assertNotNull(file, res);
            assertNotNull(file, res.rawGray);
        }
    }

    /** A chunk whose length runs past the end of the file stops the walk; it must not throw. */
    @Test
    public void aTruncatedChunkDoesNotReadPastTheBuffer() {
        assertNull(DepthPng.extract(ContractFiles.bytes("deph", "truncated-chunk.png")));
    }

    private static JSONObject find(String file) {
        JSONArray cases = cases();
        for (int i = 0; i < cases.length(); i++) {
            if (file.equals(cases.getJSONObject(i).getString("file"))) {
                return cases.getJSONObject(i);
            }
        }
        throw new AssertionError("no fixture named " + file);
    }

    private static byte[] payload(DepthPng.Result res) {
        return res.rawGray != null ? res.rawGray : res.png;
    }
}
