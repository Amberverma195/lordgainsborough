package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import java.time.LocalDateTime;
import java.util.Locale;

public final class KdsOrderTransitions {
    private KdsOrderTransitions() {}

    public enum TransitionType {
        NORMAL_WORKFLOW,
        STAFF_CORRECTION,
        IDEMPOTENT,
        INVALID
    }

    /**
     * Classifies a requested order status transition.
     *
     * Normal workflow:
     *   NEW -> ACCEPTED (kitchen acknowledges order and starts preparation)
     *   ACCEPTED -> READY (kitchen completes preparation; customer alerted)
     *   READY -> PICKED_UP (customer picks up completed order)
     *   READY -> NO_SHOW (customer fails to collect completed order)
     *
     * Genuine staff corrections:
     *   READY -> ACCEPTED (staff accidentally marked order ready prematurely; return to prep)
     *   NO_SHOW -> READY (customer arrived late or ticket mistakenly marked no-show; restore to ready)
     *
     * Shortcuts and invalid jumps (rejected):
     *   NEW -> READY (bypasses kitchen acceptance)
     *   ACCEPTED -> PICKED_UP (bypasses ready-for-pickup notification)
     *   NEW -> NO_SHOW (cannot be no-show before preparation)
     *   ACCEPTED -> NO_SHOW (cannot be no-show during preparation)
     *   NO_SHOW -> PICKED_UP (must restore to READY first)
     *   PICKED_UP -> * (terminal status)
     *   * -> NEW (cannot revert to new)
     */
    public static TransitionType classify(String current, String next) {
        if (next == null || next.isBlank()) {
            return TransitionType.INVALID;
        }
        if (current == null || current.isBlank()) {
            return "NEW".equalsIgnoreCase(next) ? TransitionType.NORMAL_WORKFLOW : TransitionType.INVALID;
        }
        String from = current.trim().toUpperCase(Locale.ROOT);
        String to = next.trim().toUpperCase(Locale.ROOT);

        if (from.equals(to)) {
            return TransitionType.IDEMPOTENT;
        }

        // Normal forward operational lifecycle
        if ("NEW".equals(from) && "ACCEPTED".equals(to)) {
            return TransitionType.NORMAL_WORKFLOW;
        }
        if ("ACCEPTED".equals(from) && "READY".equals(to)) {
            return TransitionType.NORMAL_WORKFLOW;
        }
        if ("READY".equals(from) && ("PICKED_UP".equals(to) || "NO_SHOW".equals(to))) {
            return TransitionType.NORMAL_WORKFLOW;
        }

        // Genuine staff corrections
        if ("READY".equals(from) && "ACCEPTED".equals(to)) {
            return TransitionType.STAFF_CORRECTION;
        }
        if ("NO_SHOW".equals(from) && "READY".equals(to)) {
            return TransitionType.STAFF_CORRECTION;
        }

        return TransitionType.INVALID;
    }

    public static boolean isNormalWorkflow(String current, String next) {
        return classify(current, next) == TransitionType.NORMAL_WORKFLOW;
    }

    public static boolean isStaffCorrection(String current, String next) {
        return classify(current, next) == TransitionType.STAFF_CORRECTION;
    }

    public static boolean isValidTransition(String current, String next) {
        TransitionType type = classify(current, next);
        return type == TransitionType.NORMAL_WORKFLOW || type == TransitionType.STAFF_CORRECTION || type == TransitionType.IDEMPOTENT;
    }

    static boolean status(CustomerOrder order, String next, LocalDateTime now) {
        String previous = order.getStatus();
        TransitionType type = classify(previous, next);
        if (type == TransitionType.INVALID) {
            throw new IllegalStateException("Invalid order status transition from " + previous + " to " + next);
        }
        if (type == TransitionType.IDEMPOTENT) {
            return false;
        }

        String from = previous == null ? "" : previous.trim().toUpperCase(Locale.ROOT);
        String to = next.trim().toUpperCase(Locale.ROOT);

        boolean firstNoShow = "NO_SHOW".equals(to) && !"NO_SHOW".equals(from)
                && order.getNoShowAt() == null;
        order.setStatus(to);

        if ("ACCEPTED".equals(to) && order.getAcceptedAt() == null) {
            order.setAcceptedAt(now);
        }
        if ("READY".equals(to) && order.getReadyAt() == null) {
            order.setReadyAt(now);
        }
        if ("PICKED_UP".equals(to) && order.getPickedUpAt() == null) {
            order.setPickedUpAt(now);
        }
        if (firstNoShow) {
            order.setNoShowAt(now);
        }

        // Distinguish staff correction handling from normal forward progression
        if (type == TransitionType.STAFF_CORRECTION) {
            if ("READY".equals(from) && "ACCEPTED".equals(to)) {
                // Premature ready mark reversed: clear readyAt so subsequent completion records actual ready time
                order.setReadyAt(null);
            }
        }

        return firstNoShow;
    }
}
