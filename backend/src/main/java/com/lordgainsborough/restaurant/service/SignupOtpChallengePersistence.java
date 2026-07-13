package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.repository.SignupOtpChallengeRepository;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * MongoDB persistence for signup OTP challenges.
 * Controllers / email sending are not wired here yet — this is the storage layer only.
 */
@Service
public class SignupOtpChallengePersistence {

    public static final Duration OTP_TTL = Duration.ofMinutes(10);
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(30);
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    public static final Duration SEND_LEASE = Duration.ofSeconds(60);
    public static final int MAX_RESENDS = 5;
    public static final int MAX_FAILED_VERIFY_ATTEMPTS = 5;

    private final SignupOtpChallengeRepository repository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public SignupOtpChallengePersistence(
            SignupOtpChallengeRepository repository,
            MongoTemplate mongoTemplate,
            Clock clock) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
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

    /**
     * Loads a challenge only if it is ACTIVE and {@code challengeExpiresAt} is still in the future.
     * Does not rely on Mongo TTL cleanup.
     */
    public Optional<SignupOtpChallenge> findActiveUnexpiredByEmail(String email) {
        Instant now = clock.instant();
        String emailKey = emailKeyFromRawEmail(email);
        Optional<SignupOtpChallenge> found = repository.findById(emailKey);
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
        Optional<SignupOtpChallenge> found = repository.findById(emailKey);
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

    /**
     * @deprecated Prefer the overload that includes send reservation fields.
     */
    public SignupOtpChallenge buildNewActiveChallenge(String email, String otpDigest, String challengeId) {
        return buildNewActiveChallenge(email, otpDigest, challengeId, UUID.randomUUID().toString(), null);
    }

    /**
     * Atomically inserts a challenge. Returns {@code false} when another request won the race
     * (duplicate {@code emailKey} / _id).
     */
    public boolean tryInsert(SignupOtpChallenge challenge) {
        try {
            mongoTemplate.insert(challenge);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * Atomically replaces an expired challenge document (TTL may not have deleted it yet).
     * Query requires matching {@code emailKey} and {@code challengeExpiresAt <= now}.
     * Returns the fresh document when this request won; empty when another request already replaced
     * it or the document is not expired.
     */
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

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("challengeExpiresAt").lte(now));

        Update update = new Update()
                .set("normalizedEmail", fresh.getNormalizedEmail())
                .set("challengeId", fresh.getChallengeId())
                .set("status", OtpChallengeStatus.ACTIVE)
                .set("otpDigest", fresh.getOtpDigest())
                .set("resendCount", 0)
                .set("failedVerifyAttempts", 0)
                .set("nextResendAt", fresh.getNextResendAt())
                .set("otpExpiresAt", fresh.getOtpExpiresAt())
                .set("challengeExpiresAt", fresh.getChallengeExpiresAt())
                .set("createdAt", fresh.getCreatedAt())
                .set("updatedAt", fresh.getUpdatedAt())
                .set("sendOperationId", fresh.getSendOperationId())
                .set("sendStatus", fresh.getSendStatus())
                .set("sendLeaseUntil", fresh.getSendLeaseUntil())
                .set("clientIdempotencyKey", fresh.getClientIdempotencyKey())
                .set("lastSendWasInitial", fresh.isLastSendWasInitial());

        SignupOtpChallenge replaced = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);
        return Optional.ofNullable(replaced);
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
    public SignupOtpChallenge upsertNewActiveChallenge(String email, String otpDigest) {
        SignupOtpChallenge challenge = buildNewActiveChallenge(email, otpDigest, UUID.randomUUID().toString());
        return repository.save(challenge);
    }

    public SignupOtpChallenge save(SignupOtpChallenge challenge) {
        if (challenge == null || challenge.getEmailKey() == null || challenge.getEmailKey().isBlank()) {
            throw new IllegalArgumentException("challenge emailKey is required");
        }
        challenge.setUpdatedAt(clock.instant());
        return repository.save(challenge);
    }

    public void deleteByEmail(String email) {
        repository.deleteById(emailKeyFromRawEmail(email));
    }

    public void deleteByEmailKey(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return;
        }
        repository.deleteById(emailKey);
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * Atomically claims one resend slot, rotates the OTP digest, and reserves a send operation.
     * Returns the updated challenge when this request won; empty when the query matched nothing.
     */
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

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("resendCount").lt(MAX_RESENDS)
                .and("nextResendAt").lte(now));

        Update update = new Update()
                .inc("resendCount", 1)
                .set("otpDigest", newOtpDigest)
                .set("otpExpiresAt", now.plus(OTP_TTL))
                .set("nextResendAt", now.plus(RESEND_COOLDOWN))
                .set("sendOperationId", sendOperationId)
                .set("sendStatus", OtpSendStatus.RESERVED)
                .set("sendLeaseUntil", now.plus(SEND_LEASE))
                .set("clientIdempotencyKey", clientIdempotencyKey)
                .set("lastSendWasInitial", false)
                .set("updatedAt", now);

        SignupOtpChallenge updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);
        return Optional.ofNullable(updated);
    }

    /**
     * Conditionally advances send status for a specific {@code sendOperationId}.
     */
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

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("sendOperationId").is(sendOperationId)
                .and("sendStatus").is(OtpSendStatus.RESERVED));

        Update update = new Update()
                .set("sendStatus", outcome)
                .set("updatedAt", now);

        SignupOtpChallenge updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);
        return Optional.ofNullable(updated);
    }

    /**
     * After a definite initial-send failure, expire the challenge so a different idempotency key
     * can replace it, while keeping the document for same-key replay until TTL removes it.
     */
    public Optional<SignupOtpChallenge> expireAfterDefiniteInitialFailure(
            String emailKey,
            String sendOperationId,
            Instant now) {
        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("sendOperationId").is(sendOperationId)
                .and("sendStatus").is(OtpSendStatus.DEFINITELY_FAILED)
                .and("lastSendWasInitial").is(true));

        Update update = new Update()
                .set("challengeExpiresAt", now)
                .set("updatedAt", now);

        SignupOtpChallenge updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);
        return Optional.ofNullable(updated);
    }

    public Optional<SignupOtpChallenge> findByEmailKey(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(emailKey);
    }

    public ResendDenialReason diagnoseResendDenial(String emailKey, Instant now) {
        Optional<SignupOtpChallenge> found = findByEmailKey(emailKey);
        if (found.isEmpty()) {
            return ResendDenialReason.CHALLENGE_MISSING;
        }

        SignupOtpChallenge challenge = found.get();
        if (challenge.getStatus() == OtpChallengeStatus.LOCKED) {
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
     * Atomically marks an ACTIVE challenge VERIFIED for the current OTP digest.
     * Including {@code otpDigest} in the query ensures a pre-resend OTP cannot win after rotation.
     */
    public Optional<SignupOtpChallenge> tryAtomicMarkVerified(
            String emailKey, String otpDigest, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        if (otpDigest == null || otpDigest.isBlank()) {
            throw new IllegalArgumentException("otpDigest is required");
        }

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("otpExpiresAt").gt(now)
                .and("otpDigest").is(otpDigest));

        Update update = new Update()
                .set("status", OtpChallengeStatus.VERIFIED)
                .set("updatedAt", now);

        SignupOtpChallenge updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);
        return Optional.ofNullable(updated);
    }

    /**
     * Atomically increments failed verify attempts for an ACTIVE, unexpired challenge.
     * Returns the updated document when this request claimed an attempt slot.
     */
    public Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(String emailKey, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("otpExpiresAt").gt(now)
                .and("failedVerifyAttempts").lt(MAX_FAILED_VERIFY_ATTEMPTS));

        Update update = new Update()
                .inc("failedVerifyAttempts", 1)
                .set("updatedAt", now);

        SignupOtpChallenge updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);
        return Optional.ofNullable(updated);
    }

    /**
     * Atomically locks an ACTIVE challenge that has exhausted failed verify attempts.
     */
    public Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(String emailKey, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("failedVerifyAttempts").gte(MAX_FAILED_VERIFY_ATTEMPTS));

        Update update = new Update()
                .set("status", OtpChallengeStatus.LOCKED)
                .set("updatedAt", now);

        SignupOtpChallenge updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);
        return Optional.ofNullable(updated);
    }

    public enum ResendDenialReason {
        COOLDOWN_ACTIVE,
        RESEND_LIMIT_REACHED,
        CHALLENGE_EXPIRED,
        CHALLENGE_LOCKED,
        CHALLENGE_MISSING
    }
}
