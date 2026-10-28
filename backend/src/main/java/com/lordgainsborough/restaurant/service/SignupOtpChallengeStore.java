package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;

import java.time.Instant;
import java.util.Optional;

public interface SignupOtpChallengeStore {

    /**
     * Finds a challenge by its SHA-256 derived email key.
     */
    Optional<SignupOtpChallenge> findById(String emailKey);

    /**
     * Atomically inserts a challenge document/row.
     * Returns {@code false} when a duplicate primary key (email_key) already exists.
     */
    boolean tryInsert(SignupOtpChallenge challenge);

    /**
     * Atomically replaces an expired challenge document/row if challengeExpiresAt <= now.
     */
    Optional<SignupOtpChallenge> tryAtomicReplaceExpired(
            String emailKey, SignupOtpChallenge fresh, Instant now);

    /**
     * Saves or upserts a challenge.
     */
    SignupOtpChallenge save(SignupOtpChallenge challenge);

    /**
     * Deletes a challenge by its email key.
     */
    void deleteById(String emailKey);

    Optional<SignupOtpChallenge> tryAtomicResend(
            String emailKey,
            String newOtpDigest,
            String sendOperationId,
            String clientIdempotencyKey,
            Instant now);

    /**
     * Conditionally advances send status for a specific sendOperationId.
     */
    Optional<SignupOtpChallenge> tryMarkSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now);

    /**
     * Expires a challenge after a definite initial send failure.
     */
    Optional<SignupOtpChallenge> expireAfterDefiniteInitialFailure(
            String emailKey,
            String sendOperationId,
            Instant now);

    /**
     * Atomically marks an ACTIVE challenge VERIFIED for the current OTP digest
     * if the failed-attempt threshold has not been reached.
     */
    Optional<SignupOtpChallenge> tryAtomicMarkVerified(
            String emailKey, String otpDigest, Instant now);

    /**
     * Atomically increments failed verify attempts for an ACTIVE, unexpired challenge.
     *
     * @deprecated Retained strictly for legacy backwards compatibility. Security-sensitive
     *             operations must use {@link #tryAtomicRecordFailedVerify(String, String, String, Instant)}
     *             to bind attempt increments to the observed challenge ID and OTP digest.
     */
    @Deprecated
    default Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, Instant now) {
        return tryAtomicRecordFailedVerify(emailKey, null, null, now);
    }

    /**
     * Atomically increments failed verify attempts for an ACTIVE, unexpired challenge
     * bound to the observed challenge ID and OTP digest.
     */
    Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, String challengeId, String otpDigest, Instant now);

    /**
     * Atomically locks an ACTIVE challenge that has exhausted failed verify attempts.
     *
     * @deprecated Retained strictly for legacy backwards compatibility. Callers should prefer
     *             {@link #tryAtomicLockAfterMaxFailures(String, String, Instant)} to bind
     *             the lock transition to the observed challenge ID.
     */
    @Deprecated
    default Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, Instant now) {
        return tryAtomicLockAfterMaxFailures(emailKey, null, now);
    }

    /**
     * Atomically locks an ACTIVE challenge that has exhausted failed verify attempts
     * bound to the observed challenge ID.
     */
    Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, String challengeId, Instant now);

    /**
     * Updates staged pending registration details on an existing challenge.
     */
    default boolean updatePendingRegistration(
            String emailKey,
            String pendingName,
            String pendingPhone,
            String pendingPhoneNormalized,
            String pendingPasswordHash,
            Instant now) {
        return false;
    }
}
