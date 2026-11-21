package com.lordgainsborough.restaurant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs in every application instance; PostgreSQL claims arbitrate ownership. */
public class PasswordResetQueueWorker {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetQueueWorker.class);
    private final PasswordResetEmailDeliveryQueue queue;

    public PasswordResetQueueWorker(PasswordResetEmailDeliveryQueue queue) {
        this.queue = queue;
    }

    @Scheduled(initialDelayString = "${app.auth.password-reset.queue-initial-delay:PT5S}",
            fixedDelayString = "${app.auth.password-reset.queue-poll-interval:PT10S}")
    public void recover() {
        try {
            queue.processPending();
        } catch (RuntimeException failure) {
            // Avoid logging payloads, addresses, or exception messages from external services.
            log.error("Password reset queue recovery failed; next scheduled sweep will retry ({})",
                    failure.getClass().getSimpleName());
        }
    }
}
