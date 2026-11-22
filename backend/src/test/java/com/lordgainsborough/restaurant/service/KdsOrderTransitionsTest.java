package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class KdsOrderTransitionsTest {

    @Test
    void validatesNormalWorkflowTransitions() {
        // NEW can advance to ACCEPTED
        assertTrue(KdsOrderTransitions.isValidTransition("NEW", "ACCEPTED"));
        assertTrue(KdsOrderTransitions.isNormalWorkflow("NEW", "ACCEPTED"));
        assertFalse(KdsOrderTransitions.isStaffCorrection("NEW", "ACCEPTED"));

        // ACCEPTED can advance to READY
        assertTrue(KdsOrderTransitions.isValidTransition("ACCEPTED", "READY"));
        assertTrue(KdsOrderTransitions.isNormalWorkflow("ACCEPTED", "READY"));
        assertFalse(KdsOrderTransitions.isStaffCorrection("ACCEPTED", "READY"));

        // READY can advance to PICKED_UP (fulfilled) or NO_SHOW (unclaimed)
        assertTrue(KdsOrderTransitions.isValidTransition("READY", "PICKED_UP"));
        assertTrue(KdsOrderTransitions.isNormalWorkflow("READY", "PICKED_UP"));
        assertFalse(KdsOrderTransitions.isStaffCorrection("READY", "PICKED_UP"));

        assertTrue(KdsOrderTransitions.isValidTransition("READY", "NO_SHOW"));
        assertTrue(KdsOrderTransitions.isNormalWorkflow("READY", "NO_SHOW"));
        assertFalse(KdsOrderTransitions.isStaffCorrection("READY", "NO_SHOW"));

        // Idempotent transitions are valid
        assertTrue(KdsOrderTransitions.isValidTransition("NEW", "NEW"));
        assertTrue(KdsOrderTransitions.isValidTransition("ACCEPTED", "ACCEPTED"));
        assertTrue(KdsOrderTransitions.isValidTransition("READY", "READY"));
        assertTrue(KdsOrderTransitions.isValidTransition("NO_SHOW", "NO_SHOW"));
        assertTrue(KdsOrderTransitions.isValidTransition("PICKED_UP", "PICKED_UP"));
    }

    @Test
    void validatesStaffCorrectionTransitions() {
        // READY -> ACCEPTED is a genuine staff correction for premature/accidental mark-ready
        assertTrue(KdsOrderTransitions.isValidTransition("READY", "ACCEPTED"));
        assertTrue(KdsOrderTransitions.isStaffCorrection("READY", "ACCEPTED"));
        assertFalse(KdsOrderTransitions.isNormalWorkflow("READY", "ACCEPTED"));
        assertEquals(KdsOrderTransitions.TransitionType.STAFF_CORRECTION,
                KdsOrderTransitions.classify("READY", "ACCEPTED"));

        // NO_SHOW -> READY is a genuine staff correction for customer arrival or misflagged ticket
        assertTrue(KdsOrderTransitions.isValidTransition("NO_SHOW", "READY"));
        assertTrue(KdsOrderTransitions.isStaffCorrection("NO_SHOW", "READY"));
        assertFalse(KdsOrderTransitions.isNormalWorkflow("NO_SHOW", "READY"));
        assertEquals(KdsOrderTransitions.TransitionType.STAFF_CORRECTION,
                KdsOrderTransitions.classify("NO_SHOW", "READY"));
    }

    @Test
    void rejectsStatusShortcuts() {
        // NEW cannot jump directly to READY (must be accepted and prepared first)
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", "READY"));
        assertFalse(KdsOrderTransitions.isNormalWorkflow("NEW", "READY"));
        assertFalse(KdsOrderTransitions.isStaffCorrection("NEW", "READY"));
        assertEquals(KdsOrderTransitions.TransitionType.INVALID,
                KdsOrderTransitions.classify("NEW", "READY"));

        // ACCEPTED cannot jump directly to PICKED_UP (must be marked ready first)
        assertFalse(KdsOrderTransitions.isValidTransition("ACCEPTED", "PICKED_UP"));
        assertEquals(KdsOrderTransitions.TransitionType.INVALID,
                KdsOrderTransitions.classify("ACCEPTED", "PICKED_UP"));

        // Cannot mark NO_SHOW before order has been prepared and marked READY
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", "NO_SHOW"));
        assertFalse(KdsOrderTransitions.isValidTransition("ACCEPTED", "NO_SHOW"));

        // Cannot shortcut directly from NO_SHOW to PICKED_UP (must restore to READY first)
        assertFalse(KdsOrderTransitions.isValidTransition("NO_SHOW", "PICKED_UP"));

        // Cannot skip directly from NEW to PICKED_UP
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", "PICKED_UP"));
    }

    @Test
    void rejectsInvalidReversalsAndTerminalExits() {
        // PICKED_UP is terminal: cannot transition anywhere else
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "NEW"));
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "ACCEPTED"));
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "READY"));
        assertFalse(KdsOrderTransitions.isValidTransition("PICKED_UP", "NO_SHOW"));

        // Cannot revert to NEW from later stages
        assertFalse(KdsOrderTransitions.isValidTransition("ACCEPTED", "NEW"));
        assertFalse(KdsOrderTransitions.isValidTransition("READY", "NEW"));
        assertFalse(KdsOrderTransitions.isValidTransition("NO_SHOW", "NEW"));

        // NO_SHOW cannot go directly back to ACCEPTED
        assertFalse(KdsOrderTransitions.isValidTransition("NO_SHOW", "ACCEPTED"));

        // Invalid or null targets
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", null));
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", ""));
        assertFalse(KdsOrderTransitions.isValidTransition("NEW", "UNKNOWN"));
    }

    @Test
    void statusMethodUpdatesTimestampsAndHandlesStaffCorrections() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 21, 14, 0);
        CustomerOrder order = new CustomerOrder();
        order.setStatus("NEW");

        // 1. Normal transition NEW -> ACCEPTED
        KdsOrderTransitions.status(order, "ACCEPTED", now);
        assertEquals("ACCEPTED", order.getStatus());
        assertEquals(now, order.getAcceptedAt());

        // 2. Normal transition ACCEPTED -> READY
        LocalDateTime readyTime = now.plusMinutes(20);
        KdsOrderTransitions.status(order, "READY", readyTime);
        assertEquals("READY", order.getStatus());
        assertEquals(readyTime, order.getReadyAt());

        // 3. Staff correction READY -> ACCEPTED (order prematurely marked ready)
        // Clears readyAt so that subsequent legitimate completion records actual ready time
        LocalDateTime correctionTime = readyTime.plusMinutes(2);
        KdsOrderTransitions.status(order, "ACCEPTED", correctionTime);
        assertEquals("ACCEPTED", order.getStatus());
        assertNull(order.getReadyAt(), "readyAt must be reset on correction back to ACCEPTED");
        assertEquals(now, order.getAcceptedAt(), "original acceptedAt timestamp must be preserved");

        // 4. Kitchen finishes and genuinely marks READY
        LocalDateTime trueReadyTime = readyTime.plusMinutes(15);
        KdsOrderTransitions.status(order, "READY", trueReadyTime);
        assertEquals("READY", order.getStatus());
        assertEquals(trueReadyTime, order.getReadyAt());

        // 5. Normal transition READY -> PICKED_UP
        LocalDateTime pickupTime = trueReadyTime.plusMinutes(10);
        KdsOrderTransitions.status(order, "PICKED_UP", pickupTime);
        assertEquals("PICKED_UP", order.getStatus());
        assertEquals(pickupTime, order.getPickedUpAt());

        // 6. Attempt invalid shortcut on new order
        CustomerOrder newOrder = new CustomerOrder();
        newOrder.setStatus("NEW");
        assertThrows(IllegalStateException.class, () ->
                KdsOrderTransitions.status(newOrder, "READY", now));
        assertThrows(IllegalStateException.class, () ->
                KdsOrderTransitions.status(newOrder, "PICKED_UP", now));
        assertThrows(IllegalStateException.class, () ->
                KdsOrderTransitions.status(newOrder, "NO_SHOW", now));
    }
}
