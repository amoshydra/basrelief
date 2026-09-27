package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the list orders.
 *
 * <p>Small on purpose, but the default is load-bearing: a gallery that opens in
 * an order nobody chose is a bug that is invisible in a build log, and the
 * fallback for an unreadable value is this constant, not "whatever came first".
 */
public class SortOrderTest {

    @Test
    public void everyOrderRoundTripsThroughItsStoredForm() {
        for (SortOrder order : SortOrder.values()) {
            assertEquals(order, SortOrder.parse(order.key()));
        }
    }

    @Test
    public void theStoredFormIsNotTheConstantName() {
        // A rename of a constant must not change what the last build wrote, or
        // every user silently loses their chosen order.
        for (SortOrder order : SortOrder.values()) {
            assertTrue("stored form should be stable lowercase, not " + order.name(),
                    order.key().equals(order.key().toLowerCase()));
        }
    }

    @Test
    public void anUnknownStoredFormFallsBackToTheDefault() {
        for (String bad : new String[]{null, "", "   ", "added", "ADDED_DESC", "name", "42"}) {
            assertEquals("bad value: " + bad, SortOrder.DEFAULT, SortOrder.parse(bad));
        }
    }

    /**
     * Pinning the default on purpose. It is the order a new import should land
     * in — the photo you just added is the one you are looking for — and
     * changing it should be a deliberate edit here, visible in the diff.
     */
    @Test
    public void newestFirstIsTheDefault() {
        assertEquals(SortOrder.ADDED_DESC, SortOrder.DEFAULT);
    }

    @Test
    public void everyOrderHasAReadableLabel() {
        for (SortOrder order : SortOrder.values()) {
            assertNotNull(order.label());
            assertTrue("empty label for " + order, order.label().length() > 0);
        }
    }
}
