package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Concurrency-safe, transactional PostgreSQL persistence for signup OTP challenges.
 * Executes atomic conditional SQL transitions against {@code signup_otp_challenges}.
 */
public class PostgresSignupOtpChallengeStore implements SignupOtpChallengeStore {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final RowMapper<SignupOtpChallenge> ROW_MAPPER = (rs, rowNum) -> {
        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setEmailKey(rs.getString("email_key"));
        challenge.setNormalizedEmail(rs.getString("normalized_email"));
        challenge.setChallengeId(rs.getString("challenge_id"));
        challenge.setStatus(OtpChallengeStatus.valueOf(rs.getString("status")));
        challenge.setOtpDigest(rs.getString("otp_digest"));
        challenge.setResendCount(rs.getInt("resend_count"));
        challenge.setFailedVerifyAttempts(rs.getInt("failed_verify_attempts"));

        Timestamp nextResendAt = rs.getTimestamp("next_resend_at");
        challenge.setNextResendAt(nextResendAt != null ? nextResendAt.toInstant() : null);

        Timestamp otpExpiresAt = rs.getTimestamp("otp_expires_at");
        challenge.setOtpExpiresAt(otpExpiresAt != null ? otpExpiresAt.toInstant() : null);

        Timestamp challengeExpiresAt = rs.getTimestamp("challenge_expires_at");
        challenge.setChallengeExpiresAt(challengeExpiresAt != null ? challengeExpiresAt.toInstant() : null);

        Timestamp createdAt = rs.getTimestamp("created_at");
        challenge.setCreatedAt(createdAt != null ? createdAt.toInstant() : null);

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        challenge.setUpdatedAt(updatedAt != null ? updatedAt.toInstant() : null);

        challenge.setSendOperationId(rs.getString("send_operation_id"));
        String sendStatusStr = rs.getString("send_status");
        challenge.setSendStatus(sendStatusStr != null ? OtpSendStatus.valueOf(sendStatusStr) : null);

        Timestamp sendLeaseUntil = rs.getTimestamp("send_lease_until");
        challenge.setSendLeaseUntil(sendLeaseUntil != null ? sendLeaseUntil.toInstant() : null);

        challenge.setClientIdempotencyKey(rs.getString("client_idempotency_key"));
        challenge.setLastSendWasInitial(rs.getBoolean("last_send_was_initial"));
        challenge.setPendingName(rs.getString("pending_name"));
        challenge.setPendingPhone(rs.getString("pending_phone"));
        challenge.setPendingPhoneNormalized(rs.getString("pending_phone_normalized"));
        challenge.setPendingPasswordHash(rs.getString("pending_password_hash"));
        challenge.setAttemptTokenHash(rs.getString("attempt_token_hash"));
        return challenge;
    };

    private static final String CHALLENGE_COLUMNS = """
        email_key, normalized_email, challenge_id, status, otp_digest,
        resend_count, failed_verify_attempts, next_resend_at, otp_expires_at,
        challenge_expires_at, created_at, updated_at, send_operation_id,
        send_status, send_lease_until, client_idempotency_key, last_send_was_initial,
        pending_name, pending_phone, pending_phone_normalized, pending_password_hash,
        attempt_token_hash
        """;

    public PostgresSignupOtpChallengeStore(
            DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager is required");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PostgresSignupOtpChallengeStore(DataSource dataSource) {
        this(dataSource, new DataSourceTransactionManager(dataSource));
    }

    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    @Override
    public Optional<SignupOtpChallenge> findById(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT " + CHALLENGE_COLUMNS + " FROM signup_otp_challenges WHERE email_key = ?";
        List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER, emailKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public boolean tryInsert(SignupOtpChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge is required");
        Objects.requireNonNull(challenge.getEmailKey(), "emailKey is required");

        Boolean result = transactionTemplate.execute(status -> {
            String sql = """
                INSERT INTO signup_otp_challenges (
                    email_key, normalized_email, challenge_id, status, otp_digest,
                    resend_count, failed_verify_attempts, next_resend_at, otp_expires_at,
                    challenge_expires_at, created_at, updated_at, send_operation_id,
                    send_status, send_lease_until, client_idempotency_key, last_send_was_initial,
                    pending_name, pending_phone, pending_phone_normalized, pending_password_hash,
                    attempt_token_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (email_key) DO NOTHING
                """;
            try {
                int rows = jdbcTemplate.update(sql,
                        challenge.getEmailKey(),
                        challenge.getNormalizedEmail(),
                        challenge.getChallengeId(),
                        challenge.getStatus() != null ? challenge.getStatus().name() : OtpChallengeStatus.ACTIVE.name(),
                        challenge.getOtpDigest(),
                        challenge.getResendCount(),
                        challenge.getFailedVerifyAttempts(),
                        toTimestamp(challenge.getNextResendAt()),
                        toTimestamp(challenge.getOtpExpiresAt()),
                        toTimestamp(challenge.getChallengeExpiresAt()),
                        toTimestamp(challenge.getCreatedAt()),
                        toTimestamp(challenge.getUpdatedAt()),
                        challenge.getSendOperationId(),
                        challenge.getSendStatus() != null ? challenge.getSendStatus().name() : null,
                        toTimestamp(challenge.getSendLeaseUntil()),
                        challenge.getClientIdempotencyKey(),
                        challenge.isLastSendWasInitial(),
                        challenge.getPendingName(),
                        challenge.getPendingPhone(),
                        challenge.getPendingPhoneNormalized(),
                        challenge.getPendingPasswordHash(),
                        challenge.getAttemptTokenHash()
                );
                return rows == 1;
            } catch (DuplicateKeyException e) {
                return false;
            }
        });
        return Boolean.TRUE.equals(result);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicReplaceExpired(
            String emailKey, SignupOtpChallenge fresh, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        if (fresh == null) {
            throw new IllegalArgumentException("fresh challenge is required");
        }
        if (fresh.getOtpDigest() == null || fresh.getOtpDigest().isBlank()) {
            throw new IllegalArgumentException("otpDigest is required");
        }
        if (fresh.getChallengeId() == null || fresh.getChallengeId().isBlank()) {
            throw new IllegalArgumentException("challengeId is required");
        }
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE signup_otp_challenges
                SET normalized_email = ?,
                    challenge_id = ?,
                    status = ?,
                    otp_digest = ?,
                    resend_count = 0,
                    failed_verify_attempts = 0,
                    next_resend_at = ?,
                    otp_expires_at = ?,
                    challenge_expires_at = ?,
                    created_at = ?,
                    updated_at = ?,
                    send_operation_id = ?,
                    send_status = ?,
                    send_lease_until = ?,
                    client_idempotency_key = ?,
                    last_send_was_initial = ?,
                    pending_name = ?,
                    pending_phone = ?,
                    pending_phone_normalized = ?,
                    pending_password_hash = ?,
                    attempt_token_hash = ?
                WHERE email_key = ?
                  AND challenge_expires_at <= ?
                RETURNING """ + CHALLENGE_COLUMNS;

            List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER,
                    fresh.getNormalizedEmail(),
                    fresh.getChallengeId(),
                    OtpChallengeStatus.ACTIVE.name(),
                    fresh.getOtpDigest(),
                    toTimestamp(fresh.getNextResendAt()),
                    toTimestamp(fresh.getOtpExpiresAt()),
                    toTimestamp(fresh.getChallengeExpiresAt()),
                    toTimestamp(fresh.getCreatedAt()),
                    toTimestamp(fresh.getUpdatedAt()),
                    fresh.getSendOperationId(),
                    fresh.getSendStatus() != null ? fresh.getSendStatus().name() : null,
                    toTimestamp(fresh.getSendLeaseUntil()),
                    fresh.getClientIdempotencyKey(),
                    fresh.isLastSendWasInitial(),
                    fresh.getPendingName(),
                    fresh.getPendingPhone(),
                    fresh.getPendingPhoneNormalized(),
                    fresh.getPendingPasswordHash(),
                    fresh.getAttemptTokenHash(),
                    emailKey,
                    toTimestamp(now)
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public SignupOtpChallenge save(SignupOtpChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge is required");
        if (challenge.getEmailKey() == null || challenge.getEmailKey().isBlank()) {
            throw new IllegalArgumentException("challenge emailKey is required");
        }

        return transactionTemplate.execute(status -> {
            String sql = """
                INSERT INTO signup_otp_challenges (
                    email_key, normalized_email, challenge_id, status, otp_digest,
                    resend_count, failed_verify_attempts, next_resend_at, otp_expires_at,
                    challenge_expires_at, created_at, updated_at, send_operation_id,
                    send_status, send_lease_until, client_idempotency_key, last_send_was_initial,
                    pending_name, pending_phone, pending_phone_normalized, pending_password_hash,
                    attempt_token_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (email_key) DO UPDATE SET
                    normalized_email = EXCLUDED.normalized_email,
                    challenge_id = EXCLUDED.challenge_id,
                    status = EXCLUDED.status,
                    otp_digest = EXCLUDED.otp_digest,
                    resend_count = EXCLUDED.resend_count,
                    failed_verify_attempts = EXCLUDED.failed_verify_attempts,
                    next_resend_at = EXCLUDED.next_resend_at,
                    otp_expires_at = EXCLUDED.otp_expires_at,
                    challenge_expires_at = EXCLUDED.challenge_expires_at,
                    updated_at = EXCLUDED.updated_at,
                    send_operation_id = EXCLUDED.send_operation_id,
                    send_status = EXCLUDED.send_status,
                    send_lease_until = EXCLUDED.send_lease_until,
                    client_idempotency_key = EXCLUDED.client_idempotency_key,
                    last_send_was_initial = EXCLUDED.last_send_was_initial,
                    pending_name = EXCLUDED.pending_name,
                    pending_phone = EXCLUDED.pending_phone,
                    pending_phone_normalized = EXCLUDED.pending_phone_normalized,
                    pending_password_hash = EXCLUDED.pending_password_hash,
                    attempt_token_hash = EXCLUDED.attempt_token_hash
                RETURNING """ + CHALLENGE_COLUMNS;

            List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER,
                    challenge.getEmailKey(),
                    challenge.getNormalizedEmail(),
                    challenge.getChallengeId(),
                    challenge.getStatus() != null ? challenge.getStatus().name() : OtpChallengeStatus.ACTIVE.name(),
                    challenge.getOtpDigest(),
                    challenge.getResendCount(),
                    challenge.getFailedVerifyAttempts(),
                    toTimestamp(challenge.getNextResendAt()),
                    toTimestamp(challenge.getOtpExpiresAt()),
                    toTimestamp(challenge.getChallengeExpiresAt()),
                    toTimestamp(challenge.getCreatedAt()),
                    toTimestamp(challenge.getUpdatedAt()),
                    challenge.getSendOperationId(),
                    challenge.getSendStatus() != null ? challenge.getSendStatus().name() : null,
                    toTimestamp(challenge.getSendLeaseUntil()),
                    challenge.getClientIdempotencyKey(),
                    challenge.isLastSendWasInitial(),
                    challenge.getPendingName(),
                    challenge.getPendingPhone(),
                    challenge.getPendingPhoneNormalized(),
                    challenge.getPendingPasswordHash(),
                    challenge.getAttemptTokenHash()
            );
            return results.get(0);
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void deleteById(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("DELETE FROM signup_otp_challenges WHERE email_key = ?", emailKey)
        );
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicResend(
            String emailKey,
            String newOtpDigest,
            String sendOperationId,
            String clientIdempotencyKey,
            Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        if (newOtpDigest == null || newOtpDigest.isBlank()) {
            throw new IllegalArgumentException("otpDigest is required");
        }
        if (sendOperationId == null || sendOperationId.isBlank()) {
            throw new IllegalArgumentException("sendOperationId is required");
        }
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE signup_otp_challenges
                SET resend_count = resend_count + 1,
                    otp_digest = ?,
                    otp_expires_at = ?,
                    next_resend_at = ?,
                    send_operation_id = ?,
                    send_status = ?,
                    send_lease_until = ?,
                    client_idempotency_key = ?,
                    last_send_was_initial = FALSE,
                    updated_at = ?
                WHERE email_key = ?
                  AND status = 'ACTIVE'
                  AND challenge_expires_at > ?
                  AND failed_verify_attempts < ?
                  AND resend_count < ?
                  AND next_resend_at <= ?
                  AND (send_status IS NULL OR send_status != 'RESERVED' OR send_lease_until IS NULL OR send_lease_until <= ?)
                RETURNING """ + CHALLENGE_COLUMNS;

            List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER,
                    newOtpDigest,
                    toTimestamp(now.plus(SignupOtpChallengePersistence.OTP_TTL)),
                    toTimestamp(now.plus(SignupOtpChallengePersistence.RESEND_COOLDOWN)),
                    sendOperationId,
                    OtpSendStatus.RESERVED.name(),
                    toTimestamp(now.plus(SignupOtpChallengePersistence.SEND_LEASE)),
                    clientIdempotencyKey,
                    toTimestamp(now),
                    emailKey,
                    toTimestamp(now),
                    SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS,
                    SignupOtpChallengePersistence.MAX_RESENDS,
                    toTimestamp(now),
                    toTimestamp(now)
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryMarkSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        if (sendOperationId == null || sendOperationId.isBlank()) {
            throw new IllegalArgumentException("sendOperationId is required");
        }
        if (outcome == null || outcome == OtpSendStatus.RESERVED) {
            throw new IllegalArgumentException("terminal send outcome is required");
        }
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE signup_otp_challenges
                SET send_status = ?,
                    updated_at = ?
                WHERE email_key = ?
                  AND send_operation_id = ?
                  AND send_status = 'RESERVED'
                RETURNING """ + CHALLENGE_COLUMNS;

            List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER,
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
    public Optional<SignupOtpChallenge> expireAfterDefiniteInitialFailure(
            String emailKey,
            String sendOperationId,
            Instant now) {
        Objects.requireNonNull(now, "now is required");
        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE signup_otp_challenges
                SET challenge_expires_at = ?,
                    updated_at = ?
                WHERE email_key = ?
                  AND send_operation_id = ?
                  AND send_status = 'DEFINITELY_FAILED'
                  AND last_send_was_initial = TRUE
                RETURNING """ + CHALLENGE_COLUMNS;

            List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER,
                    toTimestamp(now),
                    toTimestamp(now),
                    emailKey,
                    sendOperationId
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicMarkVerified(
            String emailKey, String otpDigest, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        if (otpDigest == null || otpDigest.isBlank()) {
            throw new IllegalArgumentException("otpDigest is required");
        }
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE signup_otp_challenges
                SET status = 'VERIFIED',
                    updated_at = ?
                WHERE email_key = ?
                  AND status = 'ACTIVE'
                  AND challenge_expires_at > ?
                  AND otp_expires_at > ?
                  AND failed_verify_attempts < ?
                  AND otp_digest = ?
                RETURNING """ + CHALLENGE_COLUMNS;

            List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER,
                    toTimestamp(now),
                    emailKey,
                    toTimestamp(now),
                    toTimestamp(now),
                    SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS,
                    otpDigest
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated Retained strictly for legacy backwards compatibility.
     */
    @Override
    @Deprecated
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, Instant now) {
        return tryAtomicRecordFailedVerify(emailKey, null, null, now);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, String challengeId, String otpDigest, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            StringBuilder sql = new StringBuilder("""
                UPDATE signup_otp_challenges
                SET failed_verify_attempts = failed_verify_attempts + 1,
                    status = CASE
                        WHEN failed_verify_attempts + 1 >= ? THEN 'LOCKED'
                        ELSE status
                    END,
                    updated_at = ?
                WHERE email_key = ?
                  AND status = 'ACTIVE'
                  AND challenge_expires_at > ?
                  AND otp_expires_at > ?
                  AND failed_verify_attempts < ?
                """);

            List<Object> params = new ArrayList<>();
            params.add(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS);
            params.add(toTimestamp(now));
            params.add(emailKey);
            params.add(toTimestamp(now));
            params.add(toTimestamp(now));
            params.add(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS);

            if (challengeId != null && !challengeId.isBlank()) {
                sql.append("  AND challenge_id = ?\n");
                params.add(challengeId);
            }
            if (otpDigest != null && !otpDigest.isBlank()) {
                sql.append("  AND otp_digest = ?\n");
                params.add(otpDigest);
            }

            sql.append("                RETURNING ").append(CHALLENGE_COLUMNS);

            List<SignupOtpChallenge> results = jdbcTemplate.query(
                    sql.toString(), ROW_MAPPER, params.toArray());
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated Retained strictly for legacy backwards compatibility.
     */
    @Override
    @Deprecated
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, Instant now) {
        return tryAtomicLockAfterMaxFailures(emailKey, null, now);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, String challengeId, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            StringBuilder sql = new StringBuilder("""
                UPDATE signup_otp_challenges
                SET status = 'LOCKED',
                    updated_at = ?
                WHERE email_key = ?
                  AND status = 'ACTIVE'
                  AND failed_verify_attempts >= ?
                """);

            List<Object> params = new ArrayList<>();
            params.add(toTimestamp(now));
            params.add(emailKey);
            params.add(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS);

            if (challengeId != null && !challengeId.isBlank()) {
                sql.append("  AND challenge_id = ?\n");
                params.add(challengeId);
            }

            sql.append("                RETURNING ").append(CHALLENGE_COLUMNS);

            List<SignupOtpChallenge> results = jdbcTemplate.query(
                    sql.toString(), ROW_MAPPER, params.toArray());
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public boolean updatePendingRegistration(
            String emailKey,
            String pendingName,
            String pendingPhone,
            String pendingPhoneNormalized,
            String pendingPasswordHash,
            Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            return false;
        }
        String sql = """
            UPDATE signup_otp_challenges
            SET pending_name = ?,
                pending_phone = ?,
                pending_phone_normalized = ?,
                pending_password_hash = ?,
                updated_at = ?
            WHERE email_key = ?
              AND status = 'ACTIVE'
              AND challenge_expires_at > ?
              AND pending_password_hash IS NULL
            """;
        int updated = jdbcTemplate.update(sql,
                pendingName,
                pendingPhone,
                pendingPhoneNormalized,
                pendingPasswordHash,
                toTimestamp(now),
                emailKey,
                toTimestamp(now));
        return updated == 1;
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Optional<SignupOtpChallenge> tryAtomicRestart(
            String emailKey,
            String expectedAttemptTokenHash,
            SignupOtpChallenge fresh,
            Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        if (fresh == null) {
            throw new IllegalArgumentException("fresh challenge is required");
        }
        Objects.requireNonNull(now, "now is required");

        return transactionTemplate.execute(status -> {
            String sql = """
                UPDATE signup_otp_challenges
                SET normalized_email = ?,
                    challenge_id = ?,
                    status = ?,
                    otp_digest = ?,
                    resend_count = 0,
                    failed_verify_attempts = 0,
                    next_resend_at = ?,
                    otp_expires_at = ?,
                    challenge_expires_at = ?,
                    created_at = ?,
                    updated_at = ?,
                    send_operation_id = ?,
                    send_status = ?,
                    send_lease_until = ?,
                    client_idempotency_key = ?,
                    last_send_was_initial = ?,
                    pending_name = ?,
                    pending_phone = ?,
                    pending_phone_normalized = ?,
                    pending_password_hash = ?,
                    attempt_token_hash = ?
                WHERE email_key = ?
                  AND status = 'ACTIVE'
                  AND challenge_expires_at > ?
                  AND (attempt_token_hash = ? OR (attempt_token_hash IS NULL AND ? IS NULL))
                RETURNING """ + CHALLENGE_COLUMNS;

            List<SignupOtpChallenge> results = jdbcTemplate.query(sql, ROW_MAPPER,
                    fresh.getNormalizedEmail(),
                    fresh.getChallengeId(),
                    OtpChallengeStatus.ACTIVE.name(),
                    fresh.getOtpDigest(),
                    toTimestamp(fresh.getNextResendAt()),
                    toTimestamp(fresh.getOtpExpiresAt()),
                    toTimestamp(fresh.getChallengeExpiresAt()),
                    toTimestamp(fresh.getCreatedAt()),
                    toTimestamp(fresh.getUpdatedAt()),
                    fresh.getSendOperationId(),
                    fresh.getSendStatus() != null ? fresh.getSendStatus().name() : null,
                    toTimestamp(fresh.getSendLeaseUntil()),
                    fresh.getClientIdempotencyKey(),
                    fresh.isLastSendWasInitial(),
                    fresh.getPendingName(),
                    fresh.getPendingPhone(),
                    fresh.getPendingPhoneNormalized(),
                    fresh.getPendingPasswordHash(),
                    fresh.getAttemptTokenHash(),
                    emailKey,
                    toTimestamp(now),
                    expectedAttemptTokenHash,
                    expectedAttemptTokenHash
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });
    }
}
