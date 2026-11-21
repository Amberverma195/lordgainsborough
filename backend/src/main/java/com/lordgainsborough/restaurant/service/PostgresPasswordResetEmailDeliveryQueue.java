package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetDeliveryTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * PostgreSQL-backed durable delivery queue for password-reset emails.
 *
 * <p>Persists outbound reset messages to {@code password_reset_email_queue} and asynchronously
 * dispatches them via {@link EmailSenderService}. This completely decouples external network latency
 * (e.g. Resend REST API calls) from the public HTTP response, eliminating timing side channels
 * that would otherwise reveal registered account existence.
 */
public class PostgresPasswordResetEmailDeliveryQueue implements PasswordResetEmailDeliveryQueue {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final EmailSenderService emailSenderService;
    private final PasswordResetOtpChallengePersistence persistence;
    private final PasswordResetProperties properties;
    private final AuthSecurityObservability observability;
    private final Executor asyncExecutor;

    private record QueuedItem(
            String id,
            String emailKey,
            String challengeId,
            String recipientEmail,
            String rawCode,
            String sendOperationId,
            Duration validity
    ) {}

    public PostgresPasswordResetEmailDeliveryQueue(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            EmailSenderService emailSenderService,
            PasswordResetOtpChallengePersistence persistence,
            PasswordResetProperties properties,
            AuthSecurityObservability observability) {
        this(
                dataSource,
                transactionManager,
                emailSenderService,
                persistence,
                properties,
                observability,
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    public PostgresPasswordResetEmailDeliveryQueue(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            EmailSenderService emailSenderService,
            PasswordResetOtpChallengePersistence persistence,
            PasswordResetProperties properties,
            AuthSecurityObservability observability,
            Executor asyncExecutor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager is required");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.emailSenderService = emailSenderService;
        this.persistence = persistence;
        this.properties = properties;
        this.observability = observability;
        this.asyncExecutor = asyncExecutor != null ? asyncExecutor : Executors.newVirtualThreadPerTaskExecutor();
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    @Override
    public boolean isDurableStore() {
        return true;
    }

    @Override
    public CompletableFuture<Void> enqueue(PasswordResetDeliveryTask task) {
        Objects.requireNonNull(task, "task is required");
        Instant now = persistence != null ? persistence.now() : Instant.now();

        String sql = """
            INSERT INTO password_reset_email_queue (
                id, email_key, challenge_id, recipient_email, raw_code,
                send_operation_id, validity_seconds, status, attempts,
                lease_until, is_synthetic, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, 0,
                NULL, ?, ?, ?
            )
        """;

        String status = task.isSynthetic() ? "SKIPPED" : "PENDING";
        jdbcTemplate.update(
                sql,
                task.getId(),
                task.getEmailKey(),
                task.getChallengeId(),
                task.getRecipientEmail(),
                task.isSynthetic() ? null : task.getRawCode(),
                task.getSendOperationId(),
                task.getValidity() != null ? task.getValidity().getSeconds() : 600L,
                status,
                task.isSynthetic(),
                Timestamp.from(task.getCreatedAt() != null ? task.getCreatedAt() : now),
                Timestamp.from(now)
        );

        if (task.isSynthetic()) {
            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.SUCCESS,
                        AuthSecurityObservability.Reason.RESET_SHADOW_CHALLENGE);
            }
            return CompletableFuture.completedFuture(null);
        }

        // Real accounts: dispatch asynchronously without blocking the calling thread
        return CompletableFuture.runAsync(() -> processTask(task.getId()), asyncExecutor);
    }

    @Override
    public int processPending() {
        Instant now = persistence != null ? persistence.now() : Instant.now();
        String selectPendingSql = """
            SELECT id FROM password_reset_email_queue
            WHERE (status = 'PENDING' OR (status = 'PROCESSING' AND lease_until <= ?))
              AND is_synthetic = FALSE
            ORDER BY created_at ASC
            LIMIT 50
        """;

        List<String> ids = jdbcTemplate.query(
                selectPendingSql,
                (rs, rowNum) -> rs.getString("id"),
                Timestamp.from(now)
        );

        int processed = 0;
        for (String id : ids) {
            processTask(id);
            processed++;
        }
        return processed;
    }

    private void processTask(String taskId) {
        Instant now = persistence != null ? persistence.now() : Instant.now();
        Duration leaseDuration = properties != null && properties.getSendLease() != null
                ? properties.getSendLease()
                : Duration.ofSeconds(60);
        Timestamp leaseUntil = Timestamp.from(now.plus(leaseDuration));

        // Atomic claim with lease and attempts increment
        List<QueuedItem> claimed = transactionTemplate.execute(status -> {
            String claimSql = """
                UPDATE password_reset_email_queue
                SET status = 'PROCESSING',
                    lease_until = ?,
                    attempts = attempts + 1,
                    updated_at = ?
                WHERE id = ?
                  AND (status = 'PENDING' OR (status = 'PROCESSING' AND lease_until <= ?))
                  AND is_synthetic = FALSE
                RETURNING id, email_key, challenge_id, recipient_email, raw_code,
                          send_operation_id, validity_seconds
            """;
            return jdbcTemplate.query(claimSql, (rs, rowNum) -> new QueuedItem(
                    rs.getString("id"),
                    rs.getString("email_key"),
                    rs.getString("challenge_id"),
                    rs.getString("recipient_email"),
                    rs.getString("raw_code"),
                    rs.getString("send_operation_id"),
                    Duration.ofSeconds(rs.getLong("validity_seconds"))
            ), leaseUntil, Timestamp.from(now), taskId, Timestamp.from(now));
        });

        if (claimed == null || claimed.isEmpty()) {
            return; // Already processed or claimed by another worker
        }

        QueuedItem item = claimed.get(0);
        deliverItem(item);
    }

    private void deliverItem(QueuedItem item) {
        Instant sendNow = persistence != null ? persistence.now() : Instant.now();
        try {
            if (emailSenderService != null) {
                emailSenderService.sendPasswordResetCodeEmail(
                        item.recipientEmail(),
                        item.rawCode(),
                        item.sendOperationId(),
                        item.validity());
            }

            // On success: clear raw_code, mark SENT
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("""
                    UPDATE password_reset_email_queue
                    SET status = 'SENT',
                        raw_code = NULL,
                        lease_until = NULL,
                        updated_at = ?
                    WHERE id = ?
                    """, Timestamp.from(sendNow), item.id());

                if (persistence != null) {
                    persistence.markSendOutcome(
                            item.emailKey(),
                            item.sendOperationId(),
                            OtpSendStatus.SENT,
                            sendNow);
                }
                return null;
            });

            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.SUCCESS,
                        AuthSecurityObservability.Reason.PROVIDER_ACCEPTED);
            }

        } catch (AmbiguousEmailDeliveryException ambiguous) {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("""
                    UPDATE password_reset_email_queue
                    SET status = 'PENDING',
                        lease_until = NULL,
                        last_error = ?,
                        updated_at = ?
                    WHERE id = ?
                    """, "Ambiguous delivery: " + ambiguous.getMessage(), Timestamp.from(sendNow), item.id());

                if (persistence != null) {
                    persistence.markSendOutcome(
                            item.emailKey(),
                            item.sendOperationId(),
                            OtpSendStatus.DELIVERY_UNKNOWN,
                            sendNow);
                }
                return null;
            });

            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.FAILURE,
                        AuthSecurityObservability.Reason.PROVIDER_TIMEOUT_OR_AMBIGUOUS);
            }

        } catch (RuntimeException definite) {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("""
                    UPDATE password_reset_email_queue
                    SET status = 'FAILED',
                        raw_code = NULL,
                        lease_until = NULL,
                        last_error = ?,
                        updated_at = ?
                    WHERE id = ?
                    """, "Definite rejection: " + definite.getMessage(), Timestamp.from(sendNow), item.id());

                if (persistence != null) {
                    persistence.markSendOutcome(
                            item.emailKey(),
                            item.sendOperationId(),
                            OtpSendStatus.DEFINITELY_FAILED,
                            sendNow);
                    persistence.expireAfterDefiniteInitialFailure(
                            item.emailKey(),
                            item.sendOperationId(),
                            sendNow);
                }
                return null;
            });

            if (observability != null) {
                observability.record(
                        AuthSecurityObservability.Flow.PASSWORD_RESET,
                        AuthSecurityObservability.Outcome.FAILURE,
                        AuthSecurityObservability.Reason.PROVIDER_DEFINITE_FAILURE);
            }
        }
    }
}
