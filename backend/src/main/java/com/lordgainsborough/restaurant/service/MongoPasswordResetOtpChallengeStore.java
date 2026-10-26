package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.PasswordResetOtpChallengeRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * MongoDB implementation of {@link PasswordResetOtpChallengeStore}.
 * Default persistence backend for password reset OTP challenges.
 */
@Component("mongoPasswordResetOtpChallengeStore")
public class MongoPasswordResetOtpChallengeStore implements PasswordResetOtpChallengeStore {

    @FunctionalInterface
    public interface TransactionFailureHook {
        void execute(TransactionStatus status, String identifier);
    }

    private final PasswordResetOtpChallengeRepository repository;
    private final MongoTemplate mongoTemplate;
    private final PasswordResetProperties properties;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;

    private volatile TransactionFailureHook failureInjectorBeforeUserUpdate;
    private volatile TransactionFailureHook failureInjectorBeforeChallengeFinalize;
    private volatile boolean simulateFinalizeConsumedFailure;

    public void setFailureInjectorBeforeUserUpdate(TransactionFailureHook failureInjectorBeforeUserUpdate) {
        this.failureInjectorBeforeUserUpdate = failureInjectorBeforeUserUpdate;
    }

    public void setFailureInjectorBeforeChallengeFinalize(TransactionFailureHook failureInjectorBeforeChallengeFinalize) {
        this.failureInjectorBeforeChallengeFinalize = failureInjectorBeforeChallengeFinalize;
    }

    public void setSimulateFinalizeConsumedFailure(boolean simulateFinalizeConsumedFailure) {
        this.simulateFinalizeConsumedFailure = simulateFinalizeConsumedFailure;
    }

    public void resetFailureInjectors() {
        this.failureInjectorBeforeUserUpdate = null;
        this.failureInjectorBeforeChallengeFinalize = null;
        this.simulateFinalizeConsumedFailure = false;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MongoPasswordResetOtpChallengeStore(
            PasswordResetOtpChallengeRepository repository,
            MongoTemplate mongoTemplate,
            PasswordResetProperties properties,
            ObjectProvider<UserRepository> userRepositoryProvider,
            ObjectProvider<MongoTransactionManager> transactionManagerProvider) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.userRepository = userRepositoryProvider.getIfAvailable();
        this.transactionManager = transactionManagerProvider.getIfAvailable();
    }

    public MongoPasswordResetOtpChallengeStore(
            PasswordResetOtpChallengeRepository repository,
            MongoTemplate mongoTemplate,
            PasswordResetProperties properties,
            UserRepository userRepository,
            MongoTransactionManager transactionManager) {
        this(repository, mongoTemplate, properties, userRepository, (PlatformTransactionManager) transactionManager);
    }

    public MongoPasswordResetOtpChallengeStore(
            PasswordResetOtpChallengeRepository repository,
            MongoTemplate mongoTemplate,
            PasswordResetProperties properties,
            UserRepository userRepository,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.userRepository = userRepository;
        this.transactionManager = transactionManager;
    }

    public MongoPasswordResetOtpChallengeStore(
            PasswordResetOtpChallengeRepository repository,
            MongoTemplate mongoTemplate,
            PasswordResetProperties properties) {
        this(repository, mongoTemplate, properties, (UserRepository) null, (PlatformTransactionManager) null);
    }

    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    @Override
    public Optional<PasswordResetOtpChallenge> find(String emailKey, String challengeId) {
        if (emailKey == null || emailKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(emailKey)
                .filter(challenge -> challengeId != null && challengeId.equals(challenge.getChallengeId()));
    }

    @Override
    public Optional<PasswordResetOtpChallenge> findByEmailKey(String emailKey) {
        if (emailKey == null || emailKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(emailKey);
    }

    @Override
    public boolean tryInsert(PasswordResetOtpChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge is required");
        try {
            repository.insert(challenge);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public Optional<PasswordResetOtpChallenge> tryAtomicReplaceExpired(
            PasswordResetOtpChallenge challenge, Instant now) {
        Objects.requireNonNull(challenge, "challenge is required");
        Objects.requireNonNull(now, "now is required");

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

    @Override
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

    @Override
    public Optional<PasswordResetOtpChallenge> markSendOutcome(
            String emailKey,
            String sendOperationId,
            OtpSendStatus outcome,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(sendOperationId, "sendOperationId is required");
        Objects.requireNonNull(now, "now is required");

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

    @Override
    public Optional<PasswordResetOtpChallenge> recordFailedVerification(
            String emailKey,
            String challengeId,
            String currentDigest,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(currentDigest, "currentDigest is required");
        Objects.requireNonNull(now, "now is required");

        int maxFailures = properties.getMaxVerificationFailures();

        // 1. Attempts 1..(maxFailures-1): atomically increment without locking
        Criteria incCriteria = Criteria.where("_id").is(emailKey)
                .and("challengeId").is(challengeId)
                .and("purpose").is(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET)
                .and("status").is(PasswordResetChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("codeExpiresAt").gt(now)
                .and("codeDigest").is(currentDigest)
                .and("failedVerificationAttempts").lt(maxFailures - 1);

        Update incUpdate = new Update()
                .inc("failedVerificationAttempts", 1)
                .set("updatedAt", now);

        PasswordResetOtpChallenge updated = mongoTemplate.findAndModify(
                new Query(incCriteria),
                incUpdate,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class);

        if (updated != null) {
            return Optional.of(updated);
        }

        // 2. Final attempt: atomically increment and transition status to LOCKED in one operation
        Criteria lockCriteria = Criteria.where("_id").is(emailKey)
                .and("challengeId").is(challengeId)
                .and("purpose").is(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET)
                .and("status").is(PasswordResetChallengeStatus.ACTIVE)
                .and("challengeExpiresAt").gt(now)
                .and("codeExpiresAt").gt(now)
                .and("codeDigest").is(currentDigest)
                .and("failedVerificationAttempts").is(maxFailures - 1);

        Update lockUpdate = new Update()
                .inc("failedVerificationAttempts", 1)
                .set("status", PasswordResetChallengeStatus.LOCKED)
                .set("updatedAt", now);

        PasswordResetOtpChallenge locked = mongoTemplate.findAndModify(
                new Query(lockCriteria),
                lockUpdate,
                FindAndModifyOptions.options().returnNew(true),
                PasswordResetOtpChallenge.class);

        return Optional.ofNullable(locked);
    }

    @Override
    public Optional<PasswordResetOtpChallenge> lockAfterFailures(
            String emailKey,
            String challengeId,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(now, "now is required");

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

    @Override
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

    @Override
    public Optional<PasswordResetOtpChallenge> finalizeConsumed(
            String emailKey,
            String challengeId,
            String resetOperationId,
            Instant now) {
        Objects.requireNonNull(emailKey, "emailKey is required");
        Objects.requireNonNull(challengeId, "challengeId is required");
        Objects.requireNonNull(resetOperationId, "resetOperationId is required");
        Objects.requireNonNull(now, "now is required");

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

    @Override
    public Optional<String> findUserEmailById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        if (userRepository != null) {
            return userRepository.findById(userId).map(User::getEmail);
        }
        if (mongoTemplate != null) {
            User u = mongoTemplate.findById(userId, User.class);
            return Optional.ofNullable(u != null ? u.getEmail() : null);
        }
        return Optional.empty();
    }

    @Override
    public PasswordResetExecutionResult executePasswordResetTransaction(
            String emailKey,
            String challengeId,
            String expectedDigest,
            String resetOperationId,
            String rawPassword,
            Instant now) {
        TransactionTemplate tx = transactionManager != null
                ? new TransactionTemplate(transactionManager)
                : null;
        if (tx != null) {
            try {
                return tx.execute(status -> doReset(status, emailKey, challengeId, expectedDigest, resetOperationId, rawPassword, now));
            } catch (Exception ex) {
                return PasswordResetExecutionResult.failure(
                        PasswordResetExecutionResult.Status.TRANSACTION_FAILED,
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            }
        }
        if (properties != null && properties.isRequireTransaction()) {
            throw new IllegalStateException(
                    "Transactional execution is required by configuration (app.auth.password-reset.require-transaction=true), " +
                    "but no transaction manager is configured for MongoDB password reset.");
        }
        return doReset(null, emailKey, challengeId, expectedDigest, resetOperationId, rawPassword, now);
    }

    private PasswordResetExecutionResult doReset(
            TransactionStatus status,
            String emailKey,
            String challengeId,
            String expectedDigest,
            String resetOperationId,
            String rawPassword,
            Instant now) {
        Optional<PasswordResetOtpChallenge> currentOpt = find(emailKey, challengeId);
        if (currentOpt.isEmpty()) {
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge not found");
        }
        PasswordResetOtpChallenge challenge = currentOpt.get();

        if (challenge.getPurpose() != PasswordResetOtpChallenge.Purpose.PASSWORD_RESET) {
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Invalid challenge purpose: " + challenge.getPurpose());
        }

        if (challenge.getChallengeExpiresAt() == null || !now.isBefore(challenge.getChallengeExpiresAt())) {
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.CHALLENGE_EXPIRED,
                    challenge.getChallengeExpiresAt() == null ? "Challenge expiration timestamp is missing" : "Challenge expired");
        }

        if (challenge.getStatus() == PasswordResetChallengeStatus.CONSUMED) {
            if (resetOperationId.equals(challenge.getResetOperationId()) && challenge.isPasswordUpdated()) {
                return PasswordResetExecutionResult.alreadyCompleted(challenge);
            }
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge already consumed by different operation");
        }

        if (challenge.getStatus() == PasswordResetChallengeStatus.PROCESSING) {
            if (!resetOperationId.equals(challenge.getResetOperationId())) {
                if (status != null) {
                    status.setRollbackOnly();
                }
                return PasswordResetExecutionResult.failure(
                        PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge concurrently claimed by different operation");
            }
            // Resume mutation
            return applyUserPasswordAndFinalize(status, challenge, resetOperationId, rawPassword, now);
        }

        if (challenge.getStatus() != PasswordResetChallengeStatus.ACTIVE) {
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Challenge not active");
        }

        if (challenge.getCodeExpiresAt() == null || !now.isBefore(challenge.getCodeExpiresAt())) {
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.CODE_EXPIRED,
                    challenge.getCodeExpiresAt() == null ? "Code expiration timestamp is missing" : "Code expired");
        }

        if (expectedDigest == null || expectedDigest.isBlank() || challenge.getCodeDigest() == null || !expectedDigest.equals(challenge.getCodeDigest())) {
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Code digest mismatch");
        }

        // Claim challenge
        Optional<PasswordResetOtpChallenge> claimed = tryClaimCorrectCode(
                emailKey, challengeId, expectedDigest, resetOperationId, now);
        if (claimed.isEmpty()) {
            PasswordResetOtpChallenge latest = find(emailKey, challengeId).orElse(challenge);
            if (latest.getStatus() == PasswordResetChallengeStatus.PROCESSING
                    && resetOperationId.equals(latest.getResetOperationId())) {
                return applyUserPasswordAndFinalize(status, latest, resetOperationId, rawPassword, now);
            }
            if (latest.getStatus() == PasswordResetChallengeStatus.CONSUMED
                    && resetOperationId.equals(latest.getResetOperationId())
                    && latest.isPasswordUpdated()) {
                return PasswordResetExecutionResult.alreadyCompleted(latest);
            }
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.INVALID_CHALLENGE, "Claim failed");
        }

        return applyUserPasswordAndFinalize(status, claimed.get(), resetOperationId, rawPassword, now);
    }

    private PasswordResetExecutionResult applyUserPasswordAndFinalize(
            TransactionStatus status,
            PasswordResetOtpChallenge challenge,
            String resetOperationId,
            String rawPassword,
            Instant now) {
        if (failureInjectorBeforeUserUpdate != null) {
            failureInjectorBeforeUserUpdate.execute(status, challenge.getUserId());
        }

        String passwordHash = PasswordUtil.hashPassword(rawPassword);

        Query query = Query.query(Criteria.where("_id").is(challenge.getUserId())
                .and("lastPasswordResetOperationId").ne(resetOperationId));
        Update update = new Update()
                .set("password", passwordHash)
                .inc("tokenVersion", 1L)
                .set("passwordChangedAt", now)
                .set("lastPasswordResetOperationId", resetOperationId);
        User updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                User.class);
        if (updated == null) {
            User existing = userRepository != null
                    ? userRepository.findById(challenge.getUserId()).orElse(null)
                    : mongoTemplate.findById(challenge.getUserId(), User.class);
            if (existing == null || !resetOperationId.equals(existing.getLastPasswordResetOperationId())) {
                if (status != null) {
                    status.setRollbackOnly();
                }
                return PasswordResetExecutionResult.failure(
                        PasswordResetExecutionResult.Status.USER_UPDATE_FAILED, "User not found or update failed");
            }
        }

        if (failureInjectorBeforeChallengeFinalize != null) {
            failureInjectorBeforeChallengeFinalize.execute(status, challenge.getChallengeId());
        }

        Optional<PasswordResetOtpChallenge> finalized;
        if (simulateFinalizeConsumedFailure) {
            finalized = Optional.empty();
        } else {
            finalized = finalizeConsumed(
                    challenge.getEmailKey(),
                    challenge.getChallengeId(),
                    resetOperationId,
                    now);
        }

        if (finalized.isEmpty()) {
            if (status != null) {
                status.setRollbackOnly();
            }
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.TRANSACTION_FAILED, "Finalize consumed failed");
        }

        if (status != null && status.isRollbackOnly()) {
            return PasswordResetExecutionResult.failure(
                    PasswordResetExecutionResult.Status.TRANSACTION_FAILED, "Transaction marked rollback-only");
        }

        return PasswordResetExecutionResult.success(finalized.get());
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
