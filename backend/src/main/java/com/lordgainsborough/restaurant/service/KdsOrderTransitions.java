package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import java.time.LocalDateTime;

final class KdsOrderTransitions {
    private KdsOrderTransitions() {}
    static boolean status(CustomerOrder order, String next, LocalDateTime now) {
        boolean firstNoShow = "NO_SHOW".equals(next) && !"NO_SHOW".equalsIgnoreCase(order.getStatus() == null ? "" : order.getStatus())
                && order.getNoShowAt() == null;
        order.setStatus(next);
        if ("ACCEPTED".equals(next) && order.getAcceptedAt() == null) order.setAcceptedAt(now);
        if ("READY".equals(next) && order.getReadyAt() == null) order.setReadyAt(now);
        if ("PICKED_UP".equals(next) && order.getPickedUpAt() == null) order.setPickedUpAt(now);
        if (firstNoShow) order.setNoShowAt(now);
        return firstNoShow;
    }
}
