package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SignupOtpChallengePersistence {

    public static final Duration OTP_TTL = Duration.ofMinutes(10);
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(30);
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    public static final Duration SEND_LEASE = Duration.ofSeconds(60);
    public static final int MAX_RESENDS = 5;
    public static final int MAX_FAILED_VERIFY_ATTEMPTS = 5;

    private final SignupOtpChallengeStore store;
    private final Clock clock;

    @Autowired
    public SignupOtpChallengePersistence(SignupOtpChallengeStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Backward-compatible constructor for existing callers and tests.
     */

    public SignupOtpChallengeStore getStore() {
        return store;
    }

    public String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase();
    }

    /**
     * Stable document id derived from the normalized email (SHA-256 hex).
     * Guarantees one challenge document per normalized address.
     */
    public String emailKeyFromNormalized(String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("normalizedEmail is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedEmail.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public String emailKeyFromRawEmail(String email) {
        return emailKeyFromNormalized(normalizeEmail(email));
    }

    public String digestOtp(String rawOtp) {
        if (rawOtp == null || rawOtp.isBlank()) {
            throw new IllegalArgumentException("OTP is required");
        }
        return PasswordUtil.hashPassword(rawOtp.trim());
    }

    public boolean otpMatches(String rawOtp, String otpDigest) {
        if (rawOtp == null || otpDigest == null || otpDigest.isBlank()) {
            return false;
        }
        return PasswordUtil.checkPassword(rawOtp.trim(), otpDigest);
    }

    public boolean isChallengeExpired(SignupOtpChallenge challenge, Instant now) {
        return challenge == null
                || challenge.getChallengeExpiresAt() == null
                || !now.isBefore(challenge.getChallengeExpiresAt());
    }

    public boolean isOtpExpired(SignupOtpChallenge challenge, Instant now) {
        return challenge == null
                || challenge.getOtpExpiresAt() == null
                || !now.isBefore(challenge.getOtpExpiresAt());
    }

    public Optional<SignupOtpChallenge> findActiveUnexpiredByEmail(String email) {
        Instant now = clock.instant();
        String emailKey = emailKeyFromRawEmail(email);
        Optional<SignupOtpChallenge> found = store.findById(emailKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        SignupOtpChallenge challenge = found.get();
        if (challenge.getStatus() != OtpChallengeStatus.ACTIVE || isChallengeExpired(challenge, now)) {
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    public Optional<SignupOtpChallenge> findUsableByEmail(String email) {
        return findActiveUnexpiredByEmail(email);
    }

    public Optional<SignupOtpChallenge> findUsableByEmailKey(String emailKey) {
        Instant now = clock.instant();
        Optional<SignupOtpChallenge> found = store.findById(emailKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SignupOtpChallenge challenge = found.get();
        if (challenge.getStatus() != OtpChallengeStatus.ACTIVE || isChallengeExpired(challenge, now)) {
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    /**
     * Builds a new ACTIVE challenge document (not yet persisted) with send reservation fields.
     * Caller supplies an already-digested OTP — never pass raw OTP into the digest field.
     */
    public SignupOtpChallenge buildNewActiveChallenge(
            String email,
            String otpDigest,
            String challengeId,
            String sendOperationId,
            String clientIdempotencyKey) {
        if (otpDigest == null || otpDigest.isBlank()) {
            throw new IllegalArgumentException("otpDigest is required");
        }
        if (challengeId == null || challengeId.isBlank()) {
            throw new IllegalArgumentException("challengeId is required");
        }
        if (sendOperationId == null || sendOperationId.isBlank()) {
            throw new IllegalArgumentException("sendOperationId is required");
        }

        Instant now = clock.instant();
        String normalizedEmail = normalizeEmail(email);
        String emailKey = emailKeyFromNormalized(normalizedEmail);

        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setEmailKey(emailKey);
        challenge.setNormalizedEmail(normalizedEmail);
        challenge.setChallengeId(challengeId);
        challenge.setStatus(OtpChallengeStatus.ACTIVE);
        challenge.setOtpDigest(otpDigest);
        challenge.setResendCount(0);
        challenge.setFailedVerifyAttempts(0);
        challenge.setNextResendAt(now.plus(RESEND_COOLDOWN));
        challenge.setOtpExpiresAt(now.plus(OTP_TTL));
        challenge.setChallengeExpiresAt(now.plus(CHALLENGE_TTL));
        challenge.setCreatedAt(now);
        challenge.setUpdatedAt(now);
        challenge.setSendOperationId(sendOperationId);
        challenge.setSendStatus(OtpSendStatus.RESERVED);
        challenge.setSendLeaseUntil(now.plus(SEND_LEASE));
        challenge.setClientIdempotencyKey(clientIdempotencyKey);
        challenge.setLastSendWasInitial(true);
        return challenge;
    }

    public SignupOtpChallenge buildNewActiveChallenge(
            String email,
            String otpDigest,
            String challengeId,
            String sendOperationId,
            String clientIdempotencyKey,
            String pendingName,
            String pendingPhone,
            String pendingPhoneNormalized,
            String pendingPasswordHash) {
        SignupOtpChallenge challenge = buildNewActiveChallenge(
                email, otpDigest, challengeId, sendOperationId, clientIdempotencyKey);
        challenge.setPendingName(pendingName);
        challenge.setPendingPhone(pendingPhone);
        challenge.setPendingPhoneNormalized(pendingPhoneNormalized);
        challenge.setPendingPasswordHash(pendingPasswordHash);
        return challenge;
    }

    public boolean updatePendingRegistration(
            String emailKey,
            String pendingName,
            String pendingPhone,
            String pendingPhoneNormalized,
            String pendingPasswordHash) {
        return store.updatePendingRegistration(
                emailKey, pendingName, pendingPhone, pendingPhoneNormalized, pendingPasswordHash, clock.instant());
    }

    /**
     * @deprecated Prefer the overload that includes send reservation fields.
     */
    @Deprecated
    public SignupOtpChallenge buildNewActiveChallenge(String email, String otpDigest, String challengeId) {
        return buildNewActiveChallenge(email, otpDigest, challengeId, UUID.randomUUID().toString(), null);
    }

    /**
     * Atomically inserts a challenge. Returns {@code false} when another request won the race
     * (duplicate {@code emailKey} / _id).
     */
    public boolean tryInsert(SignupOtpChallenge challenge) {
        return store.tryInsert(challenge);
    }

    /**
     * Atomically replaces an expired challenge document (TTL may not have deleted it yet).
     * Query requires matching {@code emailKey} and {@code challengeExpiresAt <= now}.
     * Returns the fresh document when this request won; empty when another request already replaced
     * it or the document is not expired.
     */
    public Optional<SignupOtpChallenge> tryAtomicReplaceExpired(
            String emailKey, SignupOtpChallenge fresh, Instant now) {
        return store.tryAtomicReplaceExpired(emailKey, fresh, now);
    }

    /**
     * Inserts a fresh challenge, or atomically replaces an expired document that still occupies
     * the unique {@code emailKey}. Returns {@code true} when this request owns the fresh challenge.
     */
    public boolean tryInsertOrReplaceExpired(SignupOtpChallenge challenge) {
        if (tryInsert(challenge)) {
            return true;
        }
        return tryAtomicReplaceExpired(challenge.getEmailKey(), challenge, clock.instant()).isPresent();
    }

    /**
     * @deprecated Prefer {@link #buildNewActiveChallenge} + {@link #tryInsert} for atomic create-once semantics.
     */
    @Deprecated
    public SignupOtpChallenge upsertNewActiveChallenge(String email, String otpDigest) {
        SignupOtpChallenge challenge = buildNewActiveChallenge(email, otpDigest, UUID.randomUUID().toString());
        return store.save(challenge);
    }

    public SignupOtpChallenge save(SignupOtpChallenge challenge) {
        if (challenge == null || challenge.getEmailKey() == null || challenge.getEmailKey().isBlank()) {
            throw new IllegalArgumentException("challenge emailKey is required");
        }
        challenge.setUpdatedAt(clock.instant());
        return store.save(challenge);
    }

    public void deleteByEmail(String email) {
        deleteByEmailKey(emailKeyFromRawEmail(email));
    }

    public void deleteByEmailKey(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return;
        }
        store.deleteById(emailKey);
    }

    public Instant now() {
        return clock.instant();
    }

    public Optional<SignupOtpChallenge> tryAtomicResend(
            String emailKey,
            String newOtpDigest,
            String sendOperationId,
            String clientIdempotencyKey,
            Instant now) {
        return store.tryAtomicResend(emailKey, newOtpDigest, sendOperationId, clientIdempotencyKey, now);
    }

    /**
     * Conditionally advances send status for a specific {@code sendOperationId}.
     */
    public Optional<SignupOtpChallenge> tryMarkSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now) {
        return store.tryMarkSendOutcome(emailKey, sendOperationId, outcome, now);
    }

    /**
     * After a definite initial-send failure, expire the challenge so a different idempotency key
     * can replace it, while keeping the document for same-key replay until TTL removes it.
     */
    public Optional<SignupOtpChallenge> expireAfterDefiniteInitialFailure(
            String emailKey,
            String sendOperationId,
            Instant now) {
        return store.expireAfterDefiniteInitialFailure(emailKey, sendOperationId, now);
    }

    public Optional<SignupOtpChallenge> findByEmailKey(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return Optional.empty();
        }
        return store.findById(emailKey);
    }

    public ResendDenialReason diagnoseResendDenial(String emailKey, Instant now) {
        Optional<SignupOtpChallenge> found = findByEmailKey(emailKey);
        if (found.isEmpty()) {
            return ResendDenialReason.CHALLENGE_MISSING;
        }

        SignupOtpChallenge challenge = found.get();
        if (challenge.getStatus() == OtpChallengeStatus.LOCKED
                || challenge.getFailedVerifyAttempts() >= MAX_FAILED_VERIFY_ATTEMPTS) {
            return ResendDenialReason.CHALLENGE_LOCKED;
        }
        if (challenge.getStatus() == OtpChallengeStatus.VERIFIED) {
            return ResendDenialReason.CHALLENGE_MISSING;
        }
        if (isChallengeExpired(challenge, now)) {
            return ResendDenialReason.CHALLENGE_EXPIRED;
        }
        if (challenge.getResendCount() >= MAX_RESENDS) {
            return ResendDenialReason.RESEND_LIMIT_REACHED;
        }
        if (challenge.getNextResendAt() != null && challenge.getNextResendAt().isAfter(now)) {
            return ResendDenialReason.COOLDOWN_ACTIVE;
        }
        // Lost an atomic race — another resend just claimed the slot.
        return ResendDenialReason.COOLDOWN_ACTIVE;
    }

    /**
     * Atomically marks an ACTIVE challenge VERIFIED for the current OTP digest
     * if the failed-attempt threshold has not been reached.
     * Including {@code otpDigest} in the query ensures a pre-resend OTP cannot win after rotation.
     */
    public Optional<SignupOtpChallenge> tryAtomicMarkVerified(
            String emailKey, String otpDigest, Instant now) {
        return store.tryAtomicMarkVerified(emailKey, otpDigest, now);
    }

    /**
     * Atomically increments failed verify attempts for an ACTIVE, unexpired challenge.
     * Returns the updated document when this request claimed an attempt slot.
     *
     * @deprecated Retained strictly for legacy backwards compatibility. Security-sensitive
     *             operations must use {@link #tryAtomicRecordFailedVerify(String, String, String, Instant)}
     *             to bind attempt increments to the observed challenge ID and OTP digest.
     */
    @Deprecated
    public Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(String emailKey, Instant now) {
        return store.tryAtomicRecordFailedVerify(emailKey, now);
    }

    /**
     * Atomically increments failed verify attempts for an ACTIVE, unexpired challenge
     * bound to the observed challenge ID and OTP digest.
     * Returns the updated document when this request claimed an attempt slot.
     */
    public Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, String challengeId, String otpDigest, Instant now) {
        return store.tryAtomicRecordFailedVerify(emailKey, challengeId, otpDigest, now);
    }

    /**
     * Atomically locks an ACTIVE challenge that has exhausted failed verify attempts.
     *
     * @deprecated Retained strictly for legacy backwards compatibility. Callers should prefer
     *             {@link #tryAtomicLockAfterMaxFailures(String, String, Instant)} to bind
     *             the lock transition to the observed challenge ID.
     */
    @Deprecated
    public Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(String emailKey, Instant now) {
        return store.tryAtomicLockAfterMaxFailures(emailKey, now);
    }

    /**
     * Atomically locks an ACTIVE challenge that has exhausted failed verify attempts
     * bound to the observed challenge ID.
     */
    public Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, String challengeId, Instant now) {
        return store.tryAtomicLockAfterMaxFailures(emailKey, challengeId, now);
    }

    public enum ResendDenialReason {
        COOLDOWN_ACTIVE,
        RESEND_LIMIT_REACHED,
        CHALLENGE_EXPIRED,
        CHALLENGE_LOCKED,
        CHALLENGE_MISSING
    }
}
