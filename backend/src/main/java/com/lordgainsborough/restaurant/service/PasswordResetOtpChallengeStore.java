package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage SPI for password reset OTP challenge persistence.
 * Implementations provide atomic persistence operations for MongoDB (default)
 * and PostgreSQL.
 */
public interface PasswordResetOtpChallengeStore {

    /**
     * Finds a challenge by its email key and server-generated challenge ID.
     */
    Optional<PasswordResetOtpChallenge> find(String emailKey, String challengeId);

    /**
     * Finds a challenge by its email key.
     */
    Optional<PasswordResetOtpChallenge> findByEmailKey(String emailKey);

    /**
     * Atomically inserts a fresh challenge document/row.
     * Returns {@code false} if a duplicate primary key (emailKey) already exists.
     */
    boolean tryInsert(PasswordResetOtpChallenge challenge);

    /**
     * Atomically replaces an expired or consumed challenge document/row
     * if {@code challengeExpiresAt <= now} or {@code status == CONSUMED}.
     */
    Optional<PasswordResetOtpChallenge> tryAtomicReplaceExpired(
            PasswordResetOtpChallenge challenge, Instant now);

    /**
     * Atomically claims one resend slot, rotates the OTP digest, and reserves a send operation.
     * <p>
     * <b>Cooldown Policy & Parity Invariant:</b> Resend requires {@code nextResendAt <= now}.
     * If {@code nextResendAt} is null or uninitialized, resend fails closed (denies resend),
     * ensuring strict cross-backend behavioral parity between PostgreSQL and MongoDB.
     * </p>
     */
    Optional<PasswordResetOtpChallenge> tryReserveResend(
            String emailKey,
            String challengeId,
            String newDigest,
            String clientIdempotencyKey,
            String sendOperationId,
            Instant now);

    /**
     * Conditionally updates the send status for an active send lease operation.
     */
    Optional<PasswordResetOtpChallenge> markSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now);

    /**
     * Atomically records a failed verification attempt.
     * Enforces atomic transition to {@code LOCKED} on reaching the maximum failed attempt threshold.
     */
    Optional<PasswordResetOtpChallenge> recordFailedVerification(
            String emailKey,
            String challengeId,
            String currentDigest,
            Instant now);

    /**
     * Explicit lock transition when maximum verification failures have been reached.
     */
    Optional<PasswordResetOtpChallenge> lockAfterFailures(
            String emailKey,
            String challengeId,
            Instant now);

    /**
     * Atomically claims an ACTIVE challenge for password reset completion,
     * transitioning status to PROCESSING and binding the resetOperationId.
     */
    Optional<PasswordResetOtpChallenge> tryClaimCorrectCode(
            String emailKey,
            String challengeId,
            String currentDigest,
            String resetOperationId,
            Instant now);

    /**
     * Finalizes a PROCESSING challenge to CONSUMED after user password update has succeeded,
     * setting password_updated = true, consumed_at = now, and clearing code_digest.
     */
    Optional<PasswordResetOtpChallenge> finalizeConsumed(
            String emailKey,
            String challengeId,
            String resetOperationId,
            Instant now);

    /**
     * Resolves user email by user ID for delivering resend codes.
     */
    Optional<String> findUserEmailById(String userId);

    /**
     * Atomically executes the password reset mutation participating in a single transaction:
     * 1. Validates and claims the challenge (or verifies active claim for resume/replay).
     * 2. Updates the user password, increments token_version, sets password_changed_at,
     *    and sets last_password_reset_operation_id.
     * 3. Finalizes the challenge to CONSUMED, sets password_updated = true, consumed_at,
     *    and clears the code digest.
     * All operations succeed together or roll back together.
     */
    PasswordResetExecutionResult executePasswordResetTransaction(
            String emailKey,
            String challengeId,
            String expectedDigest,
            String resetOperationId,
            String rawPassword,
            Instant now);
}
