package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.repository.PasswordResetOtpChallengeRepository;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Component
public class PasswordResetOtpChallengePersistence {

    private final PasswordResetOtpChallengeRepository repository;
    private final MongoTemplate mongoTemplate;
    private final PasswordResetProperties properties;
    private final AbuseIpHasher hasher;
    private final Clock clock;

    public PasswordResetOtpChallengePersistence(
            PasswordResetOtpChallengeRepository repository,
            MongoTemplate mongoTemplate,
            PasswordResetProperties properties,
            AbuseIpHasher hasher,
            Clock clock) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.hasher = hasher;
        this.clock = clock;
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
        return repository.findById(emailKey)
                .filter(challenge -> challengeId != null && challengeId.equals(challenge.getChallengeId()));
    }

    public Optional<PasswordResetOtpChallenge> findByEmailKey(String emailKey) {
        return repository.findById(emailKey);
    }

    public Optional<PasswordResetOtpChallenge> tryCreateOrReplaceExpired(
            PasswordResetOtpChallenge challenge,
            Instant now) {
        try {
            return Optional.of(repository.insert(challenge));
        } catch (DuplicateKeyException duplicate) {
            Query expired = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(challenge.getEmailKey()),
                    new Criteria().orOperator(
                            Criteria.where("challengeExpiresAt").lte(now),
                            Criteria.where("status").is(PasswordResetChallengeStatus.CONSUMED))));
            Update replacement = initialReplacement(challenge);
            return Optional.ofNullable(mongoTemplate.findAndModify(
                    expired,
                    replacement,
                    FindAndModifyOptions.options().returnNew(true),
                    PasswordResetOtpChallenge.class));
        }
    }

    public Optional<PasswordResetOtpChallenge> tryReserveResend(
            String emailKey,
            String challengeId,
            String newDigest,
            String clientIdempotencyKey,
            String sendOperationId,
            Instant now) {
        Query query = Query.query(Criteria.where("_id").is(emailKey)
                .and("challengeId").is(challengeId)
                .and("purpose").is(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET)
                .and("status").is(PasswordResetChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("nextResendAt").lte(now)
                .and("resendCount").lt(properties.getMaxResends())
                .and("clientIdempotencyKey").ne(clientIdempotencyKey));
        Update update = new Update()
                .set("codeDigest", newDigest)
                .set("codeExpiresAt", now.plus(properties.getCodeTtl()))
                .set("nextResendAt", now.plus(properties.getResendCooldown()))
                .inc("resendCount", 1)
                .set("clientIdempotencyKey", clientIdempotencyKey)
                .set("sendOperationId", sendOperationId)
                .set("sendStatus", OtpSendStatus.RESERVED)
                .set("sendLeaseUntil", now.plus(properties.getSendLease()))
                .set("updatedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class));
    }

    public Optional<PasswordResetOtpChallenge> markSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now) {
        Query query = Query.query(Criteria.where("_id").is(emailKey)
                .and("sendOperationId").is(sendOperationId)
                .and("sendStatus").is(OtpSendStatus.RESERVED));
        Update update = new Update()
                .set("sendStatus", outcome)
                .set("sendLeaseUntil", null)
                .set("updatedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class));
    }

    public Optional<PasswordResetOtpChallenge> recordFailedVerification(
            String emailKey,
            String challengeId,
            String currentDigest,
            Instant now) {
        Query query = Query.query(Criteria.where("_id").is(emailKey)
                .and("challengeId").is(challengeId)
                .and("purpose").is(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET)
                .and("status").is(PasswordResetChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("codeExpiresAt").gt(now)
                .and("codeDigest").is(currentDigest)
                .and("failedVerificationAttempts").lt(properties.getMaxVerificationFailures()));
        Update update = new Update()
                .inc("failedVerificationAttempts", 1)
                .set("updatedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class));
    }

    public Optional<PasswordResetOtpChallenge> lockAfterFailures(
            String emailKey,
            String challengeId,
            Instant now) {
        Query query = Query.query(Criteria.where("_id").is(emailKey)
                .and("challengeId").is(challengeId)
                .and("status").is(PasswordResetChallengeStatus.ACTIVE)
                .and("failedVerificationAttempts").gte(properties.getMaxVerificationFailures()));
        Update update = new Update()
                .set("status", PasswordResetChallengeStatus.LOCKED)
                .set("updatedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class));
    }

    public Optional<PasswordResetOtpChallenge> tryClaimCorrectCode(
            String emailKey,
            String challengeId,
            String currentDigest,
            String resetOperationId,
            Instant now) {
        Query query = Query.query(Criteria.where("_id").is(emailKey)
                .and("challengeId").is(challengeId)
                .and("purpose").is(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET)
                .and("status").is(PasswordResetChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("codeExpiresAt").gt(now)
                .and("codeDigest").is(currentDigest)
                .and("failedVerificationAttempts").lt(properties.getMaxVerificationFailures()));
        Update update = new Update()
                .set("status", PasswordResetChallengeStatus.PROCESSING)
                .set("resetOperationId", resetOperationId)
                .set("updatedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class));
    }

    public Optional<PasswordResetOtpChallenge> finalizeConsumed(
            String emailKey,
            String challengeId,
            String resetOperationId,
            Instant now) {
        Query query = Query.query(Criteria.where("_id").is(emailKey)
                .and("challengeId").is(challengeId)
                .and("status").is(PasswordResetChallengeStatus.PROCESSING)
                .and("resetOperationId").is(resetOperationId));
        Update update = new Update()
                .set("status", PasswordResetChallengeStatus.CONSUMED)
                .set("passwordUpdated", true)
                .set("consumedAt", now)
                .set("codeDigest", null)
                .set("updatedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class));
    }

    private Update initialReplacement(PasswordResetOtpChallenge value) {
        return new Update()
                .set("challengeId", value.getChallengeId())
                .set("userId", value.getUserId())
                .set("purpose", value.getPurpose())
                .set("status", value.getStatus())
                .set("codeDigest", value.getCodeDigest())
                .set("codeExpiresAt", value.getCodeExpiresAt())
                .set("challengeExpiresAt", value.getChallengeExpiresAt())
                .set("nextResendAt", value.getNextResendAt())
                .set("resendCount", value.getResendCount())
                .set("failedVerificationAttempts", value.getFailedVerificationAttempts())
                .set("clientIdempotencyKey", value.getClientIdempotencyKey())
                .set("sendOperationId", value.getSendOperationId())
                .set("sendStatus", value.getSendStatus())
                .set("sendLeaseUntil", value.getSendLeaseUntil())
                .set("resetOperationId", null)
                .set("passwordUpdated", false)
                .set("consumedAt", null)
                .set("createdAt", value.getCreatedAt())
                .set("updatedAt", value.getUpdatedAt());
    }
}
