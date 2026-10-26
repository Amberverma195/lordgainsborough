package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.repository.SignupOtpChallengeRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * MongoDB implementation of {@link SignupOtpChallengeStore}.
 * Default persistence backend for signup OTP challenges.
 */
@Component("mongoSignupOtpChallengeStore")
public class MongoSignupOtpChallengeStore implements SignupOtpChallengeStore {

    private final SignupOtpChallengeRepository repository;
    private final MongoTemplate mongoTemplate;

    public MongoSignupOtpChallengeStore(
            SignupOtpChallengeRepository repository,
            MongoTemplate mongoTemplate) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
    }

    @Override
    public Optional<SignupOtpChallenge> findById(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(emailKey);
    }

    @Override
    public boolean tryInsert(SignupOtpChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge is required");
        try {
            mongoTemplate.insert(challenge);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
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

    @Override
    public SignupOtpChallenge save(SignupOtpChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge is required");
        if (challenge.getEmailKey() == null || challenge.getEmailKey().isBlank()) {
            throw new IllegalArgumentException("challenge emailKey is required");
        }
        return repository.save(challenge);
    }

    @Override
    public void deleteById(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return;
        }
        repository.deleteById(emailKey);
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>Parity Invariant:</b> Requires {@code nextResendAt <= now}.
     * Null or missing {@code nextResendAt} fails closed and denies resend, matching PostgreSQL.
     * </p>
     */
    @Override
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

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("failedVerifyAttempts").lt(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS)
                .and("resendCount").lt(SignupOtpChallengePersistence.MAX_RESENDS)
                .and("nextResendAt").lte(now));

        Update update = new Update()
                .inc("resendCount", 1)
                .set("otpDigest", newOtpDigest)
                .set("otpExpiresAt", now.plus(SignupOtpChallengePersistence.OTP_TTL))
                .set("nextResendAt", now.plus(SignupOtpChallengePersistence.RESEND_COOLDOWN))
                .set("sendOperationId", sendOperationId)
                .set("sendStatus", OtpSendStatus.RESERVED)
                .set("sendLeaseUntil", now.plus(SignupOtpChallengePersistence.SEND_LEASE))
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

    @Override
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

    @Override
    public Optional<SignupOtpChallenge> expireAfterDefiniteInitialFailure(
            String emailKey,
            String sendOperationId,
            Instant now) {
        Objects.requireNonNull(now, "now is required");
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

    @Override
    public Optional<SignupOtpChallenge> tryAtomicMarkVerified(
            String emailKey, String otpDigest, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        if (otpDigest == null || otpDigest.isBlank()) {
            throw new IllegalArgumentException("otpDigest is required");
        }
        Objects.requireNonNull(now, "now is required");

        Query query = new Query(Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("otpExpiresAt").gt(now)
                .and("failedVerifyAttempts").lt(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS)
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
     * {@inheritDoc}
     *
     * @deprecated Retained strictly for legacy backwards compatibility.
     */
    @Override
    @Deprecated
    public Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, Instant now) {
        return tryAtomicRecordFailedVerify(emailKey, null, null, now);
    }

    @Override
    public Optional<SignupOtpChallenge> tryAtomicRecordFailedVerify(
            String emailKey, String challengeId, String otpDigest, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        Objects.requireNonNull(now, "now is required");

        // Attempts 1..4: atomically claim an intermediate attempt slot (< 4) without locking
        Criteria incCriteria = Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("otpExpiresAt").gt(now)
                .and("failedVerifyAttempts").lt(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS - 1);

        if (challengeId != null && !challengeId.isBlank()) {
            incCriteria = incCriteria.and("challengeId").is(challengeId);
        }
        if (otpDigest != null && !otpDigest.isBlank()) {
            incCriteria = incCriteria.and("otpDigest").is(otpDigest);
        }

        Update incUpdate = new Update()
                .inc("failedVerifyAttempts", 1)
                .set("updatedAt", now);

        SignupOtpChallenge updated = mongoTemplate.findAndModify(
                new Query(incCriteria),
                incUpdate,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);

        if (updated != null) {
            return Optional.of(updated);
        }

        // Attempt 5: atomically increment 4 -> 5 and transition status to LOCKED in a single operation
        Criteria lockCriteria = Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("otpExpiresAt").gt(now)
                .and("failedVerifyAttempts").is(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS - 1);

        if (challengeId != null && !challengeId.isBlank()) {
            lockCriteria = lockCriteria.and("challengeId").is(challengeId);
        }
        if (otpDigest != null && !otpDigest.isBlank()) {
            lockCriteria = lockCriteria.and("otpDigest").is(otpDigest);
        }

        Update lockUpdate = new Update()
                .inc("failedVerifyAttempts", 1)
                .set("status", OtpChallengeStatus.LOCKED)
                .set("updatedAt", now);

        SignupOtpChallenge locked = mongoTemplate.findAndModify(
                new Query(lockCriteria),
                lockUpdate,
                FindAndModifyOptions.options().returnNew(true),
                SignupOtpChallenge.class);

        return Optional.ofNullable(locked);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated Retained strictly for legacy backwards compatibility.
     */
    @Override
    @Deprecated
    public Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, Instant now) {
        return tryAtomicLockAfterMaxFailures(emailKey, null, now);
    }

    @Override
    public Optional<SignupOtpChallenge> tryAtomicLockAfterMaxFailures(
            String emailKey, String challengeId, Instant now) {
        if (emailKey == null || emailKey.isBlank()) {
            throw new IllegalArgumentException("emailKey is required");
        }
        Objects.requireNonNull(now, "now is required");

        Criteria criteria = Criteria.where("_id").is(emailKey)
                .and("status").is(OtpChallengeStatus.ACTIVE)
                .and("failedVerifyAttempts").gte(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS);

        if (challengeId != null && !challengeId.isBlank()) {
            criteria = criteria.and("challengeId").is(challengeId);
        }

        Query query = new Query(criteria);

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
}
