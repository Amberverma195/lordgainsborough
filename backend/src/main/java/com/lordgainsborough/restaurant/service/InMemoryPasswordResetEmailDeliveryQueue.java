package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetDeliveryTask;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * In-memory delivery queue for non-PostgreSQL / testing environments.
 * Supports synchronous (direct) execution for deterministic unit tests,
 * or asynchronous execution to test timing decoupling.
 */
public class InMemoryPasswordResetEmailDeliveryQueue implements PasswordResetEmailDeliveryQueue {

    private final Map<String, PasswordResetDeliveryTask> pendingTasks = new ConcurrentHashMap<>();
    private final EmailSenderService emailSenderService;
    private final PasswordResetOtpChallengePersistence persistence;
    private final AuthSecurityObservability observability;
    private final Executor executor;

    public InMemoryPasswordResetEmailDeliveryQueue(
            EmailSenderService emailSenderService,
            PasswordResetOtpChallengePersistence persistence,
            AuthSecurityObservability observability) {
        this(emailSenderService, persistence, observability, Runnable::run);
    }

    public InMemoryPasswordResetEmailDeliveryQueue(
            EmailSenderService emailSenderService,
            PasswordResetOtpChallengePersistence persistence,
            AuthSecurityObservability observability,
            Executor executor) {
        this.emailSenderService = emailSenderService;
        this.persistence = persistence;
        this.observability = observability;
        this.executor = executor != null ? executor : Runnable::run;
    }

    @Override
    public boolean isDurableStore() {
        return false;
    }

    public Map<String, PasswordResetDeliveryTask> getPendingTasks() {
        return pendingTasks;
    }

    @Override
    public CompletableFuture<Void> enqueue(PasswordResetDeliveryTask task) {
        pendingTasks.put(task.getId(), task);
        if (task.isSynthetic()) {
            pendingTasks.remove(task.getId());
            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.SUCCESS,
                        AuthSecurityObservability.Reason.RESET_SHADOW_CHALLENGE);
            }
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> deliverItem(task), executor);
    }

    @Override
    public int processPending() {
        int processed = 0;
        for (PasswordResetDeliveryTask task : pendingTasks.values()) {
            deliverItem(task);
            processed++;
        }
        return processed;
    }

    private void deliverItem(PasswordResetDeliveryTask task) {
        pendingTasks.remove(task.getId());
        Instant now = persistence != null ? persistence.now() : Instant.now();
        try {
            if (emailSenderService != null) {
                emailSenderService.sendPasswordResetCodeEmail(
                        task.getRecipientEmail(),
                        task.getRawCode(),
                        task.getSendOperationId(),
                        task.getValidity());
            }

            if (persistence != null) {
                persistence.markSendOutcome(
                        task.getEmailKey(),
                        task.getSendOperationId(),
                        OtpSendStatus.SENT,
                        now);
            }

            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.SUCCESS,
                        AuthSecurityObservability.Reason.PROVIDER_ACCEPTED);
            }
        } catch (AmbiguousEmailDeliveryException ambiguous) {
            if (persistence != null) {
                persistence.markSendOutcome(
                        task.getEmailKey(),
                        task.getSendOperationId(),
                        OtpSendStatus.DELIVERY_UNKNOWN,
                        now);
            }
            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.FAILURE,
                        AuthSecurityObservability.Reason.PROVIDER_TIMEOUT_OR_AMBIGUOUS);
            }
        } catch (RuntimeException definite) {
            if (persistence != null) {
                persistence.markSendOutcome(
                        task.getEmailKey(),
                        task.getSendOperationId(),
                        OtpSendStatus.DEFINITELY_FAILED,
                        now);
                persistence.expireAfterDefiniteInitialFailure(
                        task.getEmailKey(),
                        task.getSendOperationId(),
                        now);
            }
            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.FAILURE,
                        AuthSecurityObservability.Reason.PROVIDER_DEFINITE_FAILURE);
            }
        }
    }
}
