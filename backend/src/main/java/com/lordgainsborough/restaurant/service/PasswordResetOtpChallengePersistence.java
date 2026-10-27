package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Component
public class PasswordResetOtpChallengePersistence {

    private final PasswordResetOtpChallengeStore store;
    private final PasswordResetProperties properties;
    private final AbuseIpHasher hasher;
    private final Clock clock;

    @Autowired
    public PasswordResetOtpChallengePersistence(
            PasswordResetOtpChallengeStore store,
            PasswordResetProperties properties,
            AbuseIpHasher hasher,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.hasher = Objects.requireNonNull(hasher, "hasher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Backward-compatible constructor for existing callers and tests.
     */

    public PasswordResetOtpChallengeStore getStore() {
        return store;
    }

    public boolean isPostgresStore() {
        return store instanceof PostgresPasswordResetOtpChallengeStore;
    }

    public Instant now() {
        return clock.instant();
    }

    public String emailKey(String normalizedEmail) {
        return hasher.hashRaw("password-reset-email|" + normalizedEmail);
    }

    public String digestCode(String rawCode) {
        return PasswordUtil.hashPassword(rawCode);
    }

    public boolean codeMatches(String rawCode, String digest) {
        return rawCode != null && digest != null && PasswordUtil.checkPassword(rawCode, digest);
    }

    public Optional<PasswordResetOtpChallenge> find(String emailKey, String challengeId) {
        return store.find(emailKey, challengeId);
    }

    public Optional<PasswordResetOtpChallenge> findByEmailKey(String emailKey) {
        return store.findByEmailKey(emailKey);
    }

    public Optional<PasswordResetOtpChallenge> tryCreateOrReplaceExpired(
            PasswordResetOtpChallenge challenge,
            Instant now) {
        if (store.tryInsert(challenge)) {
            return Optional.of(challenge);
        }
        return store.tryAtomicReplaceExpired(challenge, now);
    }

    public Optional<PasswordResetOtpChallenge> tryReserveResend(
            String emailKey,
            String challengeId,
            String newDigest,
            String clientIdempotencyKey,
            String sendOperationId,
            Instant now) {
        return store.tryReserveResend(emailKey, challengeId, newDigest, clientIdempotencyKey, sendOperationId, now);
    }

    public Optional<PasswordResetOtpChallenge> markSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now) {
        return store.markSendOutcome(emailKey, sendOperationId, outcome, now);
    }

    public Optional<PasswordResetOtpChallenge> recordFailedVerification(
            String emailKey,
            String challengeId,
            String currentDigest,
            Instant now) {
        return store.recordFailedVerification(emailKey, challengeId, currentDigest, now);
    }

    public Optional<PasswordResetOtpChallenge> lockAfterFailures(
            String emailKey,
            String challengeId,
            Instant now) {
        return store.lockAfterFailures(emailKey, challengeId, now);
    }

    public Optional<PasswordResetOtpChallenge> tryClaimCorrectCode(
            String emailKey,
            String challengeId,
            String currentDigest,
            String resetOperationId,
            Instant now) {
        return store.tryClaimCorrectCode(emailKey, challengeId, currentDigest, resetOperationId, now);
    }

    public Optional<PasswordResetOtpChallenge> finalizeConsumed(
            String emailKey,
            String challengeId,
            String resetOperationId,
            Instant now) {
        return store.finalizeConsumed(emailKey, challengeId, resetOperationId, now);
    }

    public Optional<String> findUserEmailById(String userId) {
        return store.findUserEmailById(userId);
    }

    public PasswordResetExecutionResult executePasswordResetTransaction(
            String emailKey,
            String challengeId,
            String expectedDigest,
            String resetOperationId,
            String rawPassword,
            Instant now) {
        return store.executePasswordResetTransaction(emailKey, challengeId, expectedDigest, resetOperationId, rawPassword, now);
    }
}
