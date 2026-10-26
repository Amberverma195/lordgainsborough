package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Concurrency-safe, transactional PostgreSQL persistence for password reset OTP challenges.
 * Executes atomic conditional SQL transitions against {@code password_reset_otp_challenges}
 * and coordinates atomic multi-entity password mutations against {@code users}.
 */
public class PostgresPasswordResetOtpChallengeStore implements PasswordResetOtpChallengeStore {

    @FunctionalInterface
    public interface TransactionFailureHook {
        void execute(TransactionStatus status, String identifier);
    }

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PasswordResetProperties properties;

    private volatile TransactionFailureHook failureInjectorBeforeUserUpdate;
    private volatile TransactionFailureHook failureInjectorBeforeChallengeFinalize;

    public void setFailureInjectorBeforeUserUpdate(TransactionFailureHook failureInjectorBeforeUserUpdate) {
        this.failureInjectorBeforeUserUpdate = failureInjectorBeforeUserUpdate;
    }

    public void setFailureInjectorBeforeChallengeFinalize(TransactionFailureHook failureInjectorBeforeChallengeFinalize) {
        this.failureInjectorBeforeChallengeFinalize = failureInjectorBeforeChallengeFinalize;
    }

    public void resetFailureInjectors() {
        this.failureInjectorBeforeUserUpdate = null;
        this.failureInjectorBeforeChallengeFinalize = null;
    }

    private static final RowMapper<PasswordResetOtpChallenge> ROW_MAPPER = (rs, rowNum) -> {
        PasswordResetOtpChallenge ch = new PasswordResetOtpChallenge();
        ch.setEmailKey(rs.getString("email_key"));
        ch.setChallengeId(rs.getString("challenge_id"));
        ch.setUserId(rs.getString("user_id"));

        String purposeStr = rs.getString("purpose");
        try {
            ch.setPurpose(purposeStr != null ? PasswordResetOtpChallenge.Purpose.valueOf(purposeStr) : null);
        } catch (IllegalArgumentException ex) {
            ch.setPurpose(null);
        }

        String statusStr = rs.getString("status");
        ch.setStatus(statusStr != null ? PasswordResetChallengeStatus.valueOf(statusStr) : null);

        ch.setCodeDigest(rs.getString("code_digest"));

        Timestamp codeExpiresAt = rs.getTimestamp("code_expires_at");
        ch.setCodeExpiresAt(codeExpiresAt != null ? codeExpiresAt.toInstant() : null);

        Timestamp challengeExpiresAt = rs.getTimestamp("challenge_expires_at");
        ch.setChallengeExpiresAt(challengeExpiresAt != null ? challengeExpiresAt.toInstant() : null);

        Timestamp nextResendAt = rs.getTimestamp("next_resend_at");
        ch.setNextResendAt(nextResendAt != null ? nextResendAt.toInstant() : null);

        ch.setResendCount(rs.getInt("resend_count"));
        ch.setFailedVerificationAttempts(rs.getInt("failed_verification_attempts"));
        ch.setClientIdempotencyKey(rs.getString("client_idempotency_key"));
        ch.setSendOperationId(rs.getString("send_operation_id"));

        String sendStatusStr = rs.getString("send_status");
        ch.setSendStatus(sendStatusStr != null ? OtpSendStatus.valueOf(sendStatusStr) : null);

        Timestamp sendLeaseUntil = rs.getTimestamp("send_lease_until");
        ch.setSendLeaseUntil(sendLeaseUntil != null ? sendLeaseUntil.toInstant() : null);

        ch.setResetOperationId(rs.getString("reset_operation_id"));
        ch.setPasswordUpdated(rs.getBoolean("password_updated"));

        Timestamp consumedAt = rs.getTimestamp("consumed_at");
        ch.setConsumedAt(consumedAt != null ? consumedAt.toInstant() : null);

        Timestamp createdAt = rs.getTimestamp("created_at");
        ch.setCreatedAt(createdAt != null ? createdAt.toInstant() : null);

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        ch.setUpdatedAt(updatedAt != null ? updatedAt.toInstant() : null);

        return ch;
    };

    public PostgresPasswordResetOtpChallengeStore(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            PasswordResetProperties properties) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PostgresPasswordResetOtpChallengeStore(
            DataSource dataSource,
            PasswordResetProperties properties) {
        this(dataSource, new DataSourceTransactionManager(dataSource), properties);
    }

    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public PasswordResetProperties getProperties() {
        return properties;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    @Override
    public Optional<PasswordResetOtpChallenge> find(String emailKey, String challengeId) {
        if (emailKey == null || emailKey.isBlank() || challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
            SELECT email_key, challenge_id, user_id, purpose, status, code_digest,
                   code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                   failed_verification_attempts, client_idempotency_key, send_operation_id,
                   send_status, send_lease_until, reset_operation_id, password_updated,
                   consumed_at, created_at, updated_at
            FROM password_reset_otp_challenges
            WHERE email_key = ? AND challenge_id = ?
            """;
        List<PasswordResetOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER, emailKey, challengeId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<PasswordResetOtpChallenge> findByEmailKey(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return Optional.empty();
        }
        String sql = """
            SELECT email_key, challenge_id, user_id, purpose, status, code_digest,
                   code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                   failed_verification_attempts, client_idempotency_key, send_operation_id,
                   send_status, send_lease_until, reset_operation_id, password_updated,
                   consumed_at, created_at, updated_at
            FROM password_reset_otp_challenges
            WHERE email_key = ?
            """;
        List<PasswordResetOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER, emailKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public boolean tryInsert(PasswordResetOtpChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge is required");
        Objects.requireNonNull(challenge.getEmailKey(), "emailKey is required");
        Objects.requireNonNull(challenge.getChallengeId(), "challengeId is required");
        Objects.requireNonNull(challenge.getUserId(), "userId is required");

        Boolean result = transactionTemplate.execute(status -> {
            String sql = """
                INSERT INTO password_reset_otp_challenges (
                    email_key, challenge_id, user_id, purpose, status, code_digest,
                    code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                    failed_verification_attempts, client_idempotency_key, send_operation_id,
                    send_status, send_lease_until, reset_operation_id, password_updated,
                    consumed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (email_key) DO NOTHING
                """;
            int rows = jdbcTemplate.update(
                    sql,
                    challenge.getEmailKey(),
                    challenge.getChallengeId(),
                    challenge.getUserId(),
                    challenge.getPurpose() != null ? challenge.getPurpose().name() : PasswordResetOtpChallenge.Purpose.PASSWORD_RESET.name(),
                    challenge.getStatus() != null ? challenge.getStatus().name() : PasswordResetChallengeStatus.ACTIVE.name(),
                    challenge.getCodeDigest(),
                    toTimestamp(challenge.getCodeExpiresAt()),
                    toTimestamp(challenge.getChallengeExpiresAt()),
                    toTimestamp(challenge.getNextResendAt()),
                    challenge.getResendCount(),
                    challenge.getFailedVerificationAttempts(),
                    challenge.getClientIdempotencyKey(),
                    challenge.getSendOperationId(),
                    challenge.getSendStatus() != null ? challenge.getSendStatus().name() : null,
                    toTimestamp(challenge.getSendLeaseUntil()),
                    challenge.getResetOperationId(),
                    challenge.isPasswordUpdated(),
                    toTimestamp(challenge.getConsumedAt()),
                    toTimestamp(challenge.getCreatedAt()),
                    toTimestamp(challenge.getUpdatedAt())
            );
            return rows > 0;
        });
        return Boolean.TRUE.equals(result);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<PasswordResetOtpChallenge> tryAtomicReplaceExpired(
            PasswordResetOtpChallenge challenge, Instant now) {
        Objects.requireNonNull(challenge, "challenge is required");
        Objects.requireNonNull(challenge.getEmailKey(), "emailKey is required");
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE password_reset_otp_challenges
                SET challenge_id = ?,
                    user_id = ?,
                    purpose = ?,
                    status = ?,
                    code_digest = ?,
                    code_expires_at = ?,
                    challenge_expires_at = ?,
                    next_resend_at = ?,
                    resend_count = ?,
                    failed_verification_attempts = ?,
                    client_idempotency_key = ?,
                    send_operation_id = ?,
                    send_status = ?,
                    send_lease_until = ?,
                    reset_operation_id = NULL,
                    password_updated = FALSE,
                    consumed_at = NULL,
                    created_at = ?,
                    updated_at = ?
                WHERE email_key = ?
                  AND (challenge_expires_at <= ? OR status = 'CONSUMED')
                RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                          code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                          failed_verification_attempts, client_idempotency_key, send_operation_id,
                          send_status, send_lease_until, reset_operation_id, password_updated,
                          consumed_at, created_at, updated_at
                """;
            List<PasswordResetOtpChallenge> results = jdbcTemplate.query(
                    sql,
                    ROW_MAPPER,
                    challenge.getChallengeId(),
                    challenge.getUserId(),
                    challenge.getPurpose() != null ? challenge.getPurpose().name() : PasswordResetOtpChallenge.Purpose.PASSWORD_RESET.name(),
                    challenge.getStatus() != null ? challenge.getStatus().name() : PasswordResetChallengeStatus.ACTIVE.name(),
                    challenge.getCodeDigest(),
                    toTimestamp(challenge.getCodeExpiresAt()),
                    toTimestamp(challenge.getChallengeExpiresAt()),
                    toTimestamp(challenge.getNextResendAt()),
                    challenge.getResendCount(),
                    challenge.getFailedVerificationAttempts(),
                    challenge.getClientIdempotencyKey(),
                    challenge.getSendOperationId(),
                    challenge.getSendStatus() != null ? challenge.getSendStatus().name() : null,
                    toTimestamp(challenge.getSendLeaseUntil()),
                    toTimestamp(challenge.getCreatedAt()),
                    toTimestamp(challenge.getUpdatedAt()),
                    challenge.getEmailKey(),
                    toTimestamp(now)
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<PasswordResetOtpChallenge> tryReserveResend(
            String emailKey,
            String challengeId,
            String newDigest,
            String clientIdempotencyKey,
            String sendOperationId,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(newDigest, "newDigest is required");
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE password_reset_otp_challenges
                SET code_digest = ?,
                    code_expires_at = ?,
                    next_resend_at = ?,
                    resend_count = resend_count + 1,
                    client_idempotency_key = ?,
                    send_operation_id = ?,
                    send_status = 'RESERVED',
                    send_lease_until = ?,
                    updated_at = ?
                WHERE email_key = ?
                  AND challenge_id = ?
                  AND purpose = 'PASSWORD_RESET'
                  AND status = 'ACTIVE'
                  AND challenge_expires_at > ?
                  AND next_resend_at <= ?
                  AND resend_count < ?
                  AND (client_idempotency_key IS NULL OR client_idempotency_key <> ?)
                RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                          code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                          failed_verification_attempts, client_idempotency_key, send_operation_id,
                          send_status, send_lease_until, reset_operation_id, password_updated,
                          consumed_at, created_at, updated_at
                """;
            List<PasswordResetOtpChallenge> results = jdbcTemplate.query(
                    sql,
                    ROW_MAPPER,
                    newDigest,
                    toTimestamp(now.plus(properties.getCodeTtl())),
                    toTimestamp(now.plus(properties.getResendCooldown())),
                    clientIdempotencyKey,
                    sendOperationId,
                    toTimestamp(now.plus(properties.getSendLease())),
                    toTimestamp(now),
                    emailKey,
                    challengeId,
                    toTimestamp(now),
                    toTimestamp(now),
                    properties.getMaxResends(),
                    clientIdempotencyKey
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<PasswordResetOtpChallenge> markSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(sendOperationId, "sendOperationId is required");
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE password_reset_otp_challenges
                SET send_status = ?,
                    send_lease_until = NULL,
                    updated_at = ?
                WHERE email_key = ?
                  AND send_operation_id = ?
                  AND send_status = 'RESERVED'
                RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                          code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                          failed_verification_attempts, client_idempotency_key, send_operation_id,
                          send_status, send_lease_until, reset_operation_id, password_updated,
                          consumed_at, created_at, updated_at
                """;
            List<PasswordResetOtpChallenge> results = jdbcTemplate.query(
                    sql,
                    ROW_MAPPER,
                    outcome.name(),
                    toTimestamp(now),
                    emailKey,
                    sendOperationId
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<PasswordResetOtpChallenge> recordFailedVerification(
            String emailKey,
            String challengeId,
            String currentDigest,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(currentDigest, "currentDigest is required");
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE password_reset_otp_challenges
                SET failed_verification_attempts = failed_verification_attempts + 1,
                    status = CASE WHEN failed_verification_attempts + 1 >= ? THEN 'LOCKED' ELSE status END,
                    updated_at = ?
                WHERE email_key = ?
                  AND challenge_id = ?
                  AND purpose = 'PASSWORD_RESET'
                  AND status = 'ACTIVE'
                  AND challenge_expires_at > ?
                  AND code_expires_at > ?
                  AND code_digest = ?
                  AND failed_verification_attempts < ?
                RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                          code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                          failed_verification_attempts, client_idempotency_key, send_operation_id,
                          send_status, send_lease_until, reset_operation_id, password_updated,
                          consumed_at, created_at, updated_at
                """;
            List<PasswordResetOtpChallenge> results = jdbcTemplate.query(
                    sql,
                    ROW_MAPPER,
                    properties.getMaxVerificationFailures(),
                    toTimestamp(now),
                    emailKey,
                    challengeId,
                    toTimestamp(now),
                    toTimestamp(now),
                    currentDigest,
                    properties.getMaxVerificationFailures()
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<PasswordResetOtpChallenge> lockAfterFailures(
            String emailKey,
            String challengeId,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE password_reset_otp_challenges
                SET status = 'LOCKED',
                    updated_at = ?
                WHERE email_key = ?
                  AND challenge_id = ?
                  AND status = 'ACTIVE'
                  AND failed_verification_attempts >= ?
                RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                          code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                          failed_verification_attempts, client_idempotency_key, send_operation_id,
                          send_status, send_lease_until, reset_operation_id, password_updated,
                          consumed_at, created_at, updated_at
                """;
            List<PasswordResetOtpChallenge> results = jdbcTemplate.query(
                    sql,
                    ROW_MAPPER,
                    toTimestamp(now),
                    emailKey,
                    challengeId,
                    properties.getMaxVerificationFailures()
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<PasswordResetOtpChallenge> tryClaimCorrectCode(
            String emailKey,
            String challengeId,
            String currentDigest,
            String resetOperationId,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(currentDigest, "currentDigest is required");
        Objects.requireNonNull(resetOperationId, "resetOperationId is required");
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE password_reset_otp_challenges
                SET status = 'PROCESSING',
                    reset_operation_id = ?,
                    updated_at = ?
                WHERE email_key = ?
                  AND challenge_id = ?
                  AND purpose = 'PASSWORD_RESET'
                  AND status = 'ACTIVE'
                  AND challenge_expires_at > ?
                  AND code_expires_at > ?
                  AND code_digest = ?
                  AND failed_verification_attempts < ?
                RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                          code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                          failed_verification_attempts, client_idempotency_key, send_operation_id,
                          send_status, send_lease_until, reset_operation_id, password_updated,
                          consumed_at, created_at, updated_at
                """;
            List<PasswordResetOtpChallenge> results = jdbcTemplate.query(
                    sql,
                    ROW_MAPPER,
                    resetOperationId,
                    toTimestamp(now),
                    emailKey,
                    challengeId,
                    toTimestamp(now),
                    toTimestamp(now),
                    currentDigest,
                    properties.getMaxVerificationFailures()
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<PasswordResetOtpChallenge> finalizeConsumed(
            String emailKey,
            String challengeId,
            String resetOperationId,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(resetOperationId, "resetOperationId is required");
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE password_reset_otp_challenges
                SET status = 'CONSUMED',
                    password_updated = TRUE,
                    consumed_at = ?,
                    code_digest = NULL,
                    updated_at = ?
                WHERE email_key = ?
                  AND challenge_id = ?
                  AND status = 'PROCESSING'
                  AND reset_operation_id = ?
                RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                          code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                          failed_verification_attempts, client_idempotency_key, send_operation_id,
                          send_status, send_lease_until, reset_operation_id, password_updated,
                          consumed_at, created_at, updated_at
                """;
            List<PasswordResetOtpChallenge> results = jdbcTemplate.query(
                    sql,
                    ROW_MAPPER,
                    toTimestamp(now),
                    toTimestamp(now),
                    emailKey,
                    challengeId,
                    resetOperationId
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    public Optional<String> findUserEmailById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT email FROM users WHERE id = ?";
        List<String> emails = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("email"), userId);
        return emails.isEmpty() ? Optional.empty() : Optional.of(emails.get(0));
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public PasswordResetExecutionResult executePasswordResetTransaction(
            String emailKey,
            String challengeId,
            String expectedDigest,
            String resetOperationId,
            String rawPassword,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(resetOperationId, "resetOperationId is required");
        Objects.requireNonNull(rawPassword, "rawPassword is required");
        Objects.requireNonNull(now, "now is required");

        try {
            return transactionTemplate.execute(status -> {
                // 1. Acquire row lock on the challenge row to ensure strict serializability and concurrency control
                String selectSql = """
                    SELECT email_key, challenge_id, user_id, purpose, status, code_digest,
                           code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                           failed_verification_attempts, client_idempotency_key, send_operation_id,
                           send_status, send_lease_until, reset_operation_id, password_updated,
                           consumed_at, created_at, updated_at
                    FROM password_reset_otp_challenges
                    WHERE email_key = ?
                    FOR UPDATE
                    """;
                List<PasswordResetOtpChallenge> rows = jdbcTemplate.query(selectSql, ROW_MAPPER, emailKey);
                if (rows.isEmpty()) {
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge not found for emailKey: " + emailKey);
                }
                PasswordResetOtpChallenge challenge = rows.get(0);

                if (!challengeId.equals(challenge.getChallengeId())) {
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge ID mismatch");
                }

                // 2. Validate challenge preconditions (fail-closed)
                if (challenge.getPurpose() != PasswordResetOtpChallenge.Purpose.PASSWORD_RESET) {
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.INVALID_CHALLENGE,
                            "Invalid challenge purpose: " + challenge.getPurpose());
                }

                if (challenge.getChallengeExpiresAt() == null || !now.isBefore(challenge.getChallengeExpiresAt())) {
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.CHALLENGE_EXPIRED,
                            challenge.getChallengeExpiresAt() == null ? "Challenge expiration timestamp is missing" : "Challenge expired");
                }

                // 3. Validate challenge state
                if (challenge.getStatus() == PasswordResetChallengeStatus.CONSUMED) {
                    if (resetOperationId.equals(challenge.getResetOperationId()) && challenge.isPasswordUpdated()) {
                        return PasswordResetExecutionResult.alreadyCompleted(challenge);
                    }
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge already consumed by different operation");
                }

                if (challenge.getStatus() == PasswordResetChallengeStatus.LOCKED) {
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.CHALLENGE_LOCKED, "Challenge is locked");
                }

                if (challenge.getStatus() == PasswordResetChallengeStatus.PROCESSING) {
                    if (!resetOperationId.equals(challenge.getResetOperationId())) {
                        status.setRollbackOnly();
                        return PasswordResetExecutionResult.failure(
                                PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge in progress by different operation");
                    }
                    // Valid resume with same resetOperationId
                } else if (challenge.getStatus() == PasswordResetChallengeStatus.ACTIVE) {
                    if (challenge.getCodeExpiresAt() == null || !now.isBefore(challenge.getCodeExpiresAt())) {
                        status.setRollbackOnly();
                        return PasswordResetExecutionResult.failure(
                                PasswordResetExecutionResult.Status.CODE_EXPIRED,
                                challenge.getCodeExpiresAt() == null ? "Code expiration timestamp is missing" : "Code expired");
                    }
                    if (challenge.getFailedVerificationAttempts() >= properties.getMaxVerificationFailures()) {
                        status.setRollbackOnly();
                        return PasswordResetExecutionResult.failure(
                                PasswordResetExecutionResult.Status.CHALLENGE_LOCKED, "Max verification failures reached");
                    }
                    if (expectedDigest == null || expectedDigest.isBlank() || challenge.getCodeDigest() == null || !expectedDigest.equals(challenge.getCodeDigest())) {
                        status.setRollbackOnly();
                        return PasswordResetExecutionResult.failure(
                                PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Code digest mismatch");
                    }

                    // Transition challenge to PROCESSING
                    String claimSql = """
                        UPDATE password_reset_otp_challenges
                        SET status = 'PROCESSING',
                            reset_operation_id = ?,
                            updated_at = ?
                        WHERE email_key = ? AND challenge_id = ? AND status = 'ACTIVE'
                        """;
                    int claimed = jdbcTemplate.update(claimSql, resetOperationId, toTimestamp(now), emailKey, challengeId);
                    if (claimed == 0) {
                        status.setRollbackOnly();
                        return PasswordResetExecutionResult.failure(
                                PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Concurrent claim conflict");
                    }
                } else {
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Unexpected challenge status: " + challenge.getStatus());
                }

                // Test failure injection hook before user update
                if (failureInjectorBeforeUserUpdate != null) {
                    failureInjectorBeforeUserUpdate.execute(status, challenge.getUserId());
                }

                // 3. Update user password, token_version, password_changed_at, and last_password_reset_operation_id
                String passwordHash = PasswordUtil.hashPassword(rawPassword);
                String updateUserSql = """
                    UPDATE users
                    SET password = ?,
                        token_version = token_version + 1,
                        password_changed_at = ?,
                        last_password_reset_operation_id = ?
                    WHERE id = ?
                      AND (last_password_reset_operation_id IS NULL OR last_password_reset_operation_id <> ?)
                    """;
                int userUpdated = jdbcTemplate.update(
                        updateUserSql,
                        passwordHash,
                        toTimestamp(now),
                        resetOperationId,
                        challenge.getUserId(),
                        resetOperationId);

                if (userUpdated == 0) {
                    // Check if user exists and already has this resetOperationId (idempotent replay)
                    String verifyUserSql = "SELECT last_password_reset_operation_id FROM users WHERE id = ?";
                    List<String> lastOps = jdbcTemplate.query(verifyUserSql, (rs, rowNum) -> rs.getString(1), challenge.getUserId());
                    if (lastOps.isEmpty()) {
                        status.setRollbackOnly();
                        return PasswordResetExecutionResult.failure(
                                PasswordResetExecutionResult.Status.USER_NOT_FOUND, "Associated user record not found: " + challenge.getUserId());
                    }
                    if (!resetOperationId.equals(lastOps.get(0))) {
                        status.setRollbackOnly();
                        return PasswordResetExecutionResult.failure(
                                PasswordResetExecutionResult.Status.USER_UPDATE_FAILED, "Failed to update user password");
                    }
                }

                // Test failure injection hook before challenge finalization
                if (failureInjectorBeforeChallengeFinalize != null) {
                    failureInjectorBeforeChallengeFinalize.execute(status, challengeId);
                }

                // 4. Finalize challenge to CONSUMED
                String finalizeSql = """
                    UPDATE password_reset_otp_challenges
                    SET status = 'CONSUMED',
                        password_updated = TRUE,
                        consumed_at = ?,
                        code_digest = NULL,
                        updated_at = ?
                    WHERE email_key = ?
                      AND challenge_id = ?
                      AND status = 'PROCESSING'
                      AND reset_operation_id = ?
                    RETURNING email_key, challenge_id, user_id, purpose, status, code_digest,
                              code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                              failed_verification_attempts, client_idempotency_key, send_operation_id,
                              send_status, send_lease_until, reset_operation_id, password_updated,
                              consumed_at, created_at, updated_at
                    """;
                List<PasswordResetOtpChallenge> finalizedRows = jdbcTemplate.query(
                        finalizeSql,
                        ROW_MAPPER,
                        toTimestamp(now),
                        toTimestamp(now),
                        emailKey,
                        challengeId,
                        resetOperationId);

                if (finalizedRows.isEmpty()) {
                    status.setRollbackOnly();
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.TRANSACTION_FAILED, "Failed to finalize challenge consumption");
                }

                if (status.isRollbackOnly()) {
                    return PasswordResetExecutionResult.failure(
                            PasswordResetExecutionResult.Status.TRANSACTION_FAILED, "Transaction marked rollback-only");
                }

                return PasswordResetExecutionResult.success(finalizedRows.get(0));
            });
        } catch (Exception ex) {
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.TRANSACTION_FAILED,
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }
}
