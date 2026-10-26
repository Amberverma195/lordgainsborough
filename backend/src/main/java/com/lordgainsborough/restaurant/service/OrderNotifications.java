package com.lordgainsborough.restaurant.service;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No network operation during a write transaction, and no HTTP retry after delivery failure. */
final class OrderNotifications {
    private OrderNotifications() {}

    static void afterCommit(Runnable notification) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Order notification requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deliver(notification); }
        });
    }

    static void deliver(Runnable notification) {
        try { notification.run(); }
        catch (RuntimeException failure) {
            // Do not log payloads or exception text that could contain customer secrets.
            LoggerFactory.getLogger(OrderNotifications.class).warn("Persisted order notification delivery failed");
        }
    }
}
