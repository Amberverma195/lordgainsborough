package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.PasswordResetDeliveryTask;

import java.util.concurrent.CompletableFuture;

/**
 * Durable, asynchronous delivery queue interface for outbound password-reset emails.
 * Decouples external email transmission (HTTP call to Resend) from the public
 * HTTP request thread to prevent account enumeration via timing side channels.
 */
public interface PasswordResetEmailDeliveryQueue {

    /**
     * Enqueues an email delivery task and triggers asynchronous dispatch.
     * Guaranteed not to block the calling HTTP thread on external network I/O.
     */
    CompletableFuture<Void> enqueue(PasswordResetDeliveryTask task);

    /**
     * Drains/processes pending tasks in the queue.
     * Used by scheduled sweepers, crash recovery, and test synchronizations.
     *
     * @return count of processed tasks
     */
    int processPending();

    /**
     * Indicates whether this queue is backed by durable storage (PostgreSQL).
     */
    boolean isDurableStore();
}
