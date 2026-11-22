package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import java.time.LocalDateTime;
import java.util.Locale;

final class KdsOrderTransitions {
    private KdsOrderTransitions() {}

    public static boolean isValidTransition(String current, String next) {
        if (next == null || next.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return "NEW".equalsIgnoreCase(next);
        }
        String from = current.trim().toUpperCase(Locale.ROOT);
        String to = next.trim().toUpperCase(Locale.ROOT);

        if (from.equals(to)) {
            return true;
        }

        return switch (from) {
            case "NEW" -> "ACCEPTED".equals(to) || "READY".equals(to) || "NO_SHOW".equals(to);
            case "ACCEPTED" -> "READY".equals(to) || "PICKED_UP".equals(to) || "NO_SHOW".equals(to);
            case "READY" -> "PICKED_UP".equals(to) || "NO_SHOW".equals(to) || "ACCEPTED".equals(to);
            case "NO_SHOW" -> "READY".equals(to) || "PICKED_UP".equals(to);
            case "PICKED_UP" -> false;
            default -> false;
        };
    }

    static boolean status(CustomerOrder order, String next, LocalDateTime now) {
        String previous = order.getStatus();
        if (!isValidTransition(previous, next)) {
            throw new IllegalStateException("Invalid order status transition from " + previous + " to " + next);
        }
        boolean firstNoShow = "NO_SHOW".equals(next) && !"NO_SHOW".equalsIgnoreCase(previous == null ? "" : previous)
                && order.getNoShowAt() == null;
        order.setStatus(next);
        if ("ACCEPTED".equals(next) && order.getAcceptedAt() == null) order.setAcceptedAt(now);
        if ("READY".equals(next) && order.getReadyAt() == null) order.setReadyAt(now);
        if ("PICKED_UP".equals(next) && order.getPickedUpAt() == null) order.setPickedUpAt(now);
        if (firstNoShow) order.setNoShowAt(now);
        return firstNoShow;
    }
}
