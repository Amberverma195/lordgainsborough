package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class KdsOrderTransitionsTest {

    @Test
    void validatesValidTransitions() {
        // NEW can go to ACCEPTED, READY, NO_SHOW, or NEW
        assertTrue(KdsOrderTransitions.isValidTransition("NEW", "ACCEPTED"));
        assertTrue(KdsOrderTransitions.isValidTransition("NEW", "READY"));
        assertTrue(KdsOrderTransitions.isValidTransition("NEW", "NO_SHOW"));
        assertTrue(KdsOrderTransitions.isValidTransition("NEW", "NEW"));

        // ACCEPTED can go to READY, PICKED_UP, NO_SHOW, or ACCEPTED
        assertTrue(KdsOrderTransitions.isValidTransition("ACCEPTED", "READY"));
        assertTrue(KdsOrderTransitions.isValidTransition("ACCEPTED", "PICKED_UP"));
        assertTrue(KdsOrderTransitions.isValidTransition("ACCEPTED", "NO_SHOW"));
        assertTrue(KdsOrderTransitions.isValidTransition("ACCEPTED", "ACCEPTED"));

        // READY can go to PICKED_UP, NO_SHOW, ACCEPTED, or READY
        assertTrue(KdsOrderTransitions.isValidTransition("READY", "PICKED_UP"));
        assertTrue(KdsOrderTransitions.isValidTransition("READY", "NO_SHOW"));
        assertTrue(KdsOrderTransitions.isValidTransition("READY", "ACCEPTED"));
        assertTrue(KdsOrderTransitions.isValidTransition("READY", "READY"));

        // NO_SHOW can go to READY, PICKED_UP, or NO_SHOW
        assertTrue(KdsOrderTransitions.isValidTransition("NO_SHOW", "READY"));
        assertTrue(KdsOrderTransitions.isValidTransition("NO_SHOW", "PICKED_UP"));
        assertTrue(KdsOrderTransitions.isValidTransition("NO_SHOW", "NO_SHOW"));

        // PICKED_UP can only stay PICKED_UP
        assertTrue(KdsOrderTransitions.isValidTransition("PICKED_UP", "PICKED_UP"));
    }

    @Test
    void rejectsInvalidTransitions() {
        // PICKED_UP is terminal
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "NEW"));
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "ACCEPTED"));
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "READY"));
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "NO_SHOW"));

        // Cannot revert to NEW from later stages
        assertFalse(KdsOrderTransitions.isValidTransition("ACCEPTED", "NEW"));
        assertFalse(KdsOrderTransitions.isValidTransition("READY", "NEW"));
        assertFalse(KdsOrderTransitions.isValidTransition("NO_SHOW", "NEW"));

        // Cannot skip directly from NEW to PICKED_UP without preparing/ready
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", "PICKED_UP"));

        // NO_SHOW cannot go back to ACCEPTED
        assertFalse(KdsOrderTransitions.isValidTransition("NO_SHOW", "ACCEPTED"));

        // Invalid or null targets
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", null));
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", ""));
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", "UNKNOWN"));
    }

    @Test
    void statusMethodUpdatesTimestampsAndEnforcesRules() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 21, 14, 0);
        CustomerOrder order = new CustomerOrder();
        order.setStatus("NEW");

        // Valid transition NEW -> ACCEPTED
        KdsOrderTransitions.status(order, "ACCEPTED", now);
        assertEquals("ACCEPTED", order.getStatus());
        assertEquals(now, order.getAcceptedAt());

        // Valid transition ACCEPTED -> READY
        LocalDateTime readyTime = now.plusMinutes(20);
        KdsOrderTransitions.status(order, "READY", readyTime);
        assertEquals("READY", order.getStatus());
        assertEquals(readyTime, order.getReadyAt());

        // Valid transition READY -> PICKED_UP
        LocalDateTime pickupTime = readyTime.plusMinutes(10);
        KdsOrderTransitions.status(order, "PICKED_UP", pickupTime);
        assertEquals("PICKED_UP", order.getStatus());
        assertEquals(pickupTime, order.getPickedUpAt());

        // Attempt invalid transition PICKED_UP -> NEW
        assertThrows(IllegalStateException.class, () ->
                KdsOrderTransitions.status(order, "NEW", pickupTime.plusMinutes(5)));
    }
}
