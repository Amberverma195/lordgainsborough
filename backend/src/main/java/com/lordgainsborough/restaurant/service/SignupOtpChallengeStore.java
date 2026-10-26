package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage SPI for signup OTP challenge persistence.
 * Implementations provide atomic persistence operations for MongoDB (default)
 * and PostgreSQL.
 */
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

    /**
     * Atomically claims one resend slot, rotates the OTP digest, and reserves a send operation.
     */
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
     * Atomically marks an ACTIVE challenge VERIFIED for the current OTP digest.
     */
    Optional<SignupOtpChallenge> tryAtomicMarkVerified(
            String emailKey, String otpDigest, Instant now);

    /**
     * Atomically increments failed verify attempts for an ACTIVE, unexpired challenge.
     */
    Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, Instant now);

    /**
     * Atomically locks an ACTIVE challenge that has exhausted failed verify attempts.
     */
    Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, Instant now);
}
