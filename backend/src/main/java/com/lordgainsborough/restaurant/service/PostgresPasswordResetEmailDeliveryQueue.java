package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetDeliveryTask;
import com.lordgainsborough.restaurant.security.PasswordResetQueuePayloadEncryptor;
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
 *
 * <p>Payload Security: Queued OTP payloads are encrypted at the application level using AES-256-GCM
 * with a key stored separately from PostgreSQL. Plaintext OTPs are NEVER stored in PostgreSQL.
 * Decryption occurs strictly inside the delivery worker thread, and {@code encrypted_code} is immediately
 * cleared after acceptance or a terminal outcome to minimize retention.
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
    final PasswordResetQueuePayloadEncryptor encryptor;

    record QueuedItem(
            String id,
            String emailKey,
            String challengeId,
            String recipientEmail,
            String encryptedCode,
            String sendOperationId,
            Duration validity,
            int attempt
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
                Executors.newVirtualThreadPerTaskExecutor(),
                new PasswordResetQueuePayloadEncryptor(properties)
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
        this(
                dataSource,
                transactionManager,
                emailSenderService,
                persistence,
                properties,
                observability,
                asyncExecutor,
                new PasswordResetQueuePayloadEncryptor(properties)
        );
    }

    public PostgresPasswordResetEmailDeliveryQueue(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            EmailSenderService emailSenderService,
            PasswordResetOtpChallengePersistence persistence,
            PasswordResetProperties properties,
            AuthSecurityObservability observability,
            Executor asyncExecutor,
            PasswordResetQueuePayloadEncryptor encryptor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager is required");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.emailSenderService = emailSenderService;
        this.persistence = persistence;
        this.properties = properties != null ? properties : new PasswordResetProperties();
        this.observability = observability;
        this.asyncExecutor = asyncExecutor != null ? asyncExecutor : Executors.newVirtualThreadPerTaskExecutor();
        this.encryptor = encryptor != null ? encryptor : new PasswordResetQueuePayloadEncryptor(properties);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    public PasswordResetQueuePayloadEncryptor getEncryptor() {
        return encryptor;
    }

    private Instant now() {
        if (persistence != null) {
            Instant current = persistence.now();
            if (current != null) {
                return current;
            }
        }
        return Instant.now();
    }

    @Override
    public boolean isDurableStore() {
        return true;
    }

    @Override
    public CompletableFuture<Void> enqueue(PasswordResetDeliveryTask task) {
        Objects.requireNonNull(task, "task is required");
        Instant now = now();

        String encryptedCode = task.isSynthetic()
                ? null
                : encryptor.encrypt(task.getRawCode(), task.getId());

        String sql = """
            INSERT INTO password_reset_email_queue (
                id, email_key, challenge_id, recipient_email, encrypted_code,
                send_operation_id, validity_seconds, status, attempts,
                lease_until, is_synthetic, created_at, updated_at, next_attempt_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, 0,
                NULL, ?, ?, ?, ?
            )
        """;

        String status = task.isSynthetic() ? "SKIPPED" : "PENDING";
        jdbcTemplate.update(
                sql,
                task.getId(),
                task.getEmailKey(),
                task.getChallengeId(),
                task.getRecipientEmail(),
                encryptedCode,
                task.getSendOperationId(),
                task.getValidity() != null ? task.getValidity().getSeconds() : 600L,
                status,
                task.isSynthetic(),
                Timestamp.from(task.getCreatedAt() != null ? task.getCreatedAt() : now),
                Timestamp.from(now),
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

    // A resend replaces the operation and digest. Never send a superseded code.
    private static final String LIVE_CHALLENGE = """
            EXISTS (SELECT 1 FROM password_reset_otp_challenges c
                WHERE c.email_key = q.email_key AND c.challenge_id = q.challenge_id
                  AND c.send_operation_id = q.send_operation_id AND c.status = 'ACTIVE'
                  AND c.code_expires_at > ? AND c.challenge_expires_at > ?)
            """;

    @Override
    public int processPending() {
        Instant current = now();
        cleanup(current);
        List<String> ids = jdbcTemplate.query("""
            SELECT id FROM password_reset_email_queue
            WHERE ((status = 'PENDING' AND next_attempt_at <= ?)
                OR (status = 'PROCESSING' AND (lease_until IS NULL OR lease_until <= ?)))
              AND is_synthetic = FALSE AND attempts < ?
            ORDER BY created_at ASC LIMIT 50
            """, (rs, rowNum) -> rs.getString("id"),
                Timestamp.from(current), Timestamp.from(current), properties.getQueueMaxAttempts());
        int processed = 0;
        for (String id : ids) {
            if (processTask(id)) processed++;
        }
        return processed;
    }

    private void cleanup(Instant current) {
        Timestamp timestamp = Timestamp.from(current);
        // Never invalidate a live owner's lease. Expired leases can be retired safely.
        jdbcTemplate.update("""
            UPDATE password_reset_email_queue q
            SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'EXPIRED' END,
                encrypted_code = NULL, lease_until = NULL, updated_at = ?
            WHERE (status = 'PENDING' OR (status = 'PROCESSING'
                AND (lease_until IS NULL OR lease_until <= ?)))
              AND (attempts >= ? OR created_at + validity_seconds * INTERVAL '1 second' <= ?
                OR NOT (
            """ + LIVE_CHALLENGE + "))", properties.getQueueMaxAttempts(), timestamp, timestamp,
                properties.getQueueMaxAttempts(), timestamp, timestamp, timestamp);
        jdbcTemplate.update("""
            DELETE FROM password_reset_email_queue WHERE id IN (
                SELECT id FROM password_reset_email_queue
                WHERE status IN ('SENT', 'FAILED', 'SKIPPED', 'EXPIRED') AND updated_at < ?
                ORDER BY updated_at LIMIT 500)
            """, Timestamp.from(current.minus(properties.getQueueRetention())));
    }

    private boolean processTask(String taskId) {
        Instant current = now();
        Timestamp timestamp = Timestamp.from(current);
        Duration lease = properties.getSendLease();
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalStateException("Password reset send lease must be positive");
        }
        // Claim and commit before network I/O. Incremented attempts also fence stale owners.
        List<QueuedItem> claimed = transactionTemplate.execute(status -> jdbcTemplate.query("""
            UPDATE password_reset_email_queue q
            SET status = 'PROCESSING', lease_until = ?, attempts = attempts + 1, updated_at = ?
            WHERE id = ? AND is_synthetic = FALSE AND attempts < ?
              AND ((status = 'PENDING' AND next_attempt_at <= ?)
                OR (status = 'PROCESSING' AND (lease_until IS NULL OR lease_until <= ?)))
              AND created_at + validity_seconds * INTERVAL '1 second' > ?
              AND
            """ + LIVE_CHALLENGE + """
            RETURNING id, email_key, challenge_id, recipient_email, encrypted_code,
                      send_operation_id, validity_seconds, attempts
            """, (rs, rowNum) -> new QueuedItem(
                rs.getString("id"), rs.getString("email_key"), rs.getString("challenge_id"),
                rs.getString("recipient_email"), rs.getString("encrypted_code"),
                rs.getString("send_operation_id"), Duration.ofSeconds(rs.getLong("validity_seconds")),
                rs.getInt("attempts")),
                Timestamp.from(current.plus(lease)), timestamp, taskId, properties.getQueueMaxAttempts(),
                timestamp, timestamp, timestamp, timestamp, timestamp));
        if (claimed == null || claimed.isEmpty()) return false;
        deliverItem(claimed.get(0));
        return true;
    }

    void deliverItem(QueuedItem item) {
        try {
            String plainCode = encryptor.decrypt(item.encryptedCode(), item.id());
            if (emailSenderService == null || plainCode == null) {
                throw new IllegalStateException("Missing reset email sender or payload");
            }
            // Keep body and idempotency key identical across retries, including validity text.
            emailSenderService.sendPasswordResetCodeEmail(item.recipientEmail(), plainCode,
                    item.sendOperationId(), item.validity());
        } catch (AmbiguousEmailDeliveryException ambiguous) {
            finish(item, true, false);
            return;
        } catch (RuntimeException definite) {
            finish(item, false, false);
            return;
        }
        // A DB failure after acceptance leaves the lease recoverable; it is not a provider rejection.
        finish(item, false, true);
    }

    private void finish(QueuedItem item, boolean ambiguous, boolean sent) {
        Instant current = now();
        boolean retry = ambiguous && item.attempt() < properties.getQueueMaxAttempts();
        Duration backoff = properties.getQueueRetryBackoff();
        Duration cap = properties.getQueueMaxRetryBackoff();
        for (int i = 1; i < item.attempt() && backoff.compareTo(cap) < 0; i++) {
            backoff = backoff.multipliedBy(2);
        }
        if (backoff.compareTo(cap) > 0) backoff = cap;
        String finalStatus = sent ? "SENT" : retry ? "PENDING" : "FAILED";
        OtpSendStatus outcome = sent ? OtpSendStatus.SENT
                : ambiguous ? OtpSendStatus.DELIVERY_UNKNOWN : OtpSendStatus.DEFINITELY_FAILED;
        Instant nextAttempt = current.plus(backoff);
        Boolean owned = transactionTemplate.execute(status -> {
            int changed = jdbcTemplate.update("""
                UPDATE password_reset_email_queue
                SET status = ?, encrypted_code = CASE WHEN ? THEN encrypted_code ELSE NULL END,
                    lease_until = NULL, next_attempt_at = ?, last_error = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND attempts = ? AND lease_until > ?
                """, finalStatus, retry, Timestamp.from(nextAttempt),
                    sent ? null : ambiguous ? "Provider delivery uncertain" : "Provider delivery failed",
                    Timestamp.from(current), item.id(), item.attempt(), Timestamp.from(current));
            if (changed == 0) return false;
            if (persistence != null) {
                persistence.markSendOutcome(item.emailKey(), item.sendOperationId(), outcome, current);
                // Ambiguous exhaustion must not invalidate a code which may have arrived.
                if (!sent && !ambiguous) {
                    persistence.expireAfterDefiniteInitialFailure(item.emailKey(), item.sendOperationId(), current);
                }
            }
            return true;
        });
        if (Boolean.TRUE.equals(owned) && observability != null) {
            observability.record(AuthSecurityObservability.Flow.PASSWORD_RESET,
                    sent ? AuthSecurityObservability.Outcome.SUCCESS : AuthSecurityObservability.Outcome.FAILURE,
                    sent ? AuthSecurityObservability.Reason.PROVIDER_ACCEPTED
                            : ambiguous ? AuthSecurityObservability.Reason.PROVIDER_TIMEOUT_OR_AMBIGUOUS
                            : AuthSecurityObservability.Reason.PROVIDER_DEFINITE_FAILURE);
        }
    }
}
