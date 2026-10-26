package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.dto.PasswordResetResponse;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PasswordResetService {

    public record Result(HttpStatus status, PasswordResetResponse body, long retryAfterSeconds) {}

    private static final String GENERIC_SEND_MESSAGE =
            "If an account exists for that email, a reset code has been sent.";
    private static final Pattern STRICT_CODE = Pattern.compile("\\d{6}");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern NUMBER = Pattern.compile("\\d");
    private static final Pattern SPECIAL =
            Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]");

    private final PasswordResetOtpChallengePersistence persistence;
    private final PasswordResetAbuseLimiter abuseLimiter;
    private final PasswordResetPublicHandleService handleService;
    private final PasswordResetProperties properties;
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final EmailSenderService emailSenderService;
    private final AbuseIpHasher hasher;
    private final AuthSecurityObservability observability;
    private TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public PasswordResetService(
            PasswordResetOtpChallengePersistence persistence,
            PasswordResetAbuseLimiter abuseLimiter,
            PasswordResetPublicHandleService handleService,
            PasswordResetProperties properties,
            ObjectProvider<UserRepository> userRepositoryProvider,
            ObjectProvider<MongoTemplate> mongoTemplateProvider,
            EmailSenderService emailSenderService,
            AbuseIpHasher hasher,
            AuthSecurityObservability observability,
            ObjectProvider<MongoTransactionManager> transactionManagerProvider) {
        this(
                persistence,
                abuseLimiter,
                handleService,
                properties,
                userRepositoryProvider != null ? userRepositoryProvider.getIfAvailable() : null,
                mongoTemplateProvider != null ? mongoTemplateProvider.getIfAvailable() : null,
                emailSenderService,
                hasher,
                observability);
        MongoTransactionManager tm = transactionManagerProvider != null ? transactionManagerProvider.getIfAvailable() : null;
        if (tm != null) {
            this.transactionTemplate = new TransactionTemplate(tm);
        }
    }

    public PasswordResetService(
            PasswordResetOtpChallengePersistence persistence,
            PasswordResetAbuseLimiter abuseLimiter,
            PasswordResetPublicHandleService handleService,
            PasswordResetProperties properties,
            UserRepository userRepository,
            MongoTemplate mongoTemplate,
            EmailSenderService emailSenderService,
            AbuseIpHasher hasher,
            AuthSecurityObservability observability,
            MongoTransactionManager transactionManager) {
        this(
                persistence,
                abuseLimiter,
                handleService,
                properties,
                userRepository,
                mongoTemplate,
                emailSenderService,
                hasher,
                observability);
        if (transactionManager != null) {
            this.transactionTemplate = new TransactionTemplate(transactionManager);
        }
    }

    /**
     * Explicit non-transactional constructor used by focused unit tests and the safe
     * claim/update/finalize fallback.
     */
    public PasswordResetService(
            PasswordResetOtpChallengePersistence persistence,
            PasswordResetAbuseLimiter abuseLimiter,
            PasswordResetPublicHandleService handleService,
            PasswordResetProperties properties,
            UserRepository userRepository,
            MongoTemplate mongoTemplate,
            EmailSenderService emailSenderService,
            AbuseIpHasher hasher,
            AuthSecurityObservability observability) {
        this.persistence = persistence;
        this.abuseLimiter = abuseLimiter;
        this.handleService = handleService;
        this.properties = properties;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.emailSenderService = emailSenderService;
        this.hasher = hasher;
        this.observability = observability;
        this.transactionTemplate = null;
    }

    public boolean isPostgresStore() {
        return persistence != null && persistence.isPostgresStore();
    }

    public Result requestInitial(
            String normalizedEmail,
            Optional<User> user,
            String clientIp,
            String idempotencyKey) {
        if (isPostgresStore()) {
            throw new IllegalStateException(
                    "Unsupported mixed-backend call: cannot initiate PostgreSQL password reset challenge using MongoDB User. " +
                    "Use requestInitialForJpaUser or coordinated PostgreSQL user authentication (PG-022).");
        }
        return requestInitial(
                normalizedEmail,
                user.map(User::getId).orElse(null),
                user.map(User::getEmail).orElse(null),
                clientIp,
                idempotencyKey);
    }

    public Result requestInitialForJpaUser(
            String normalizedEmail,
            Optional<com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser> user,
            String clientIp,
            String idempotencyKey) {
        return requestInitial(
                normalizedEmail,
                user.map(com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser::getId).orElse(null),
                user.map(com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser::getEmail).orElse(null),
                clientIp,
                idempotencyKey);
    }

    public Result requestInitial(
            String normalizedEmail,
            String userId,
            String userEmail,
            String clientIp,
            String idempotencyKey) {
        Instant now = persistence.now();
        String emailKey = persistence.emailKey(normalizedEmail);

        OptionalLong operationLimit = abuseLimiter.consumeEmailOperation(clientIp);
        if (operationLimit.isPresent()) {
            return rateLimited(operationLimit.getAsLong(), now);
        }

        Optional<PasswordResetOtpChallenge> current = persistence.findByEmailKey(emailKey);
        if (userId != null && current.isPresent()
                && normalizeIdempotencyKey(idempotencyKey)
                        .equals(current.get().getClientIdempotencyKey())
                && current.get().getStatus() != PasswordResetChallengeStatus.CONSUMED
                && current.get().getChallengeExpiresAt() != null
                && current.get().getChallengeExpiresAt().isAfter(now)) {
            return accepted(current.get(), now);
        }

        OptionalLong challengeLimit = abuseLimiter.consumeNewChallenge(emailKey);
        if (challengeLimit.isPresent()) {
            return rateLimited(challengeLimit.getAsLong(), now);
        }

        if (userId != null && current.isPresent()
                && current.get().getStatus() != PasswordResetChallengeStatus.CONSUMED
                && current.get().getChallengeExpiresAt() != null
                && current.get().getChallengeExpiresAt().isAfter(now)) {
            return accepted(current.get(), now);
        }

        if (userId == null) {
            // Match the dominant local cryptographic work of a real challenge creation.
            persistence.digestCode(generateCode());
            return acceptedShadow(emailKey, now, 0);
        }

        String rawCode = generateCode();
        String operationId = UUID.randomUUID().toString();
        PasswordResetOtpChallenge challenge = newChallenge(
                emailKey,
                userId,
                persistence.digestCode(rawCode),
                normalizeIdempotencyKey(idempotencyKey),
                operationId,
                now);
        Optional<PasswordResetOtpChallenge> reserved =
                persistence.tryCreateOrReplaceExpired(challenge, now);
        if (reserved.isEmpty()) {
            return persistence.findByEmailKey(emailKey)
                    .map(existing -> accepted(existing, now))
                    .orElseGet(() -> acceptedShadow(emailKey, now, 0));
        }

        deliver(userEmail != null ? userEmail : normalizedEmail, rawCode, reserved.get());
        return accepted(persistence.findByEmailKey(emailKey).orElse(reserved.get()), now);
    }

    public Result resend(
            String normalizedEmail,
            String publicHandle,
            String clientIp,
            String idempotencyKey) {
        Instant now = persistence.now();
        String emailKey = persistence.emailKey(normalizedEmail);
        Optional<PasswordResetPublicHandleService.Handle> parsed = handleService.parse(publicHandle);
        if (parsed.isEmpty() || !emailKey.equals(parsed.get().emailKey())) {
            return challengeExpired(now);
        }
        String key = requireIdempotencyKey(idempotencyKey);
        if (key == null) {
            return invalidRequest("A valid Idempotency-Key header is required.", now);
        }

        PasswordResetPublicHandleService.Handle handle = parsed.get();
        Optional<PasswordResetOtpChallenge> found =
                persistence.find(emailKey, handle.internalChallengeId());
        if (found.isEmpty()) {
            return resendShadow(handle, key, clientIp, now);
        }

        PasswordResetOtpChallenge challenge = found.get();
        if (key.equals(challenge.getClientIdempotencyKey())) {
            return accepted(challenge, now);
        }
        Result stateDenial = resendStateDenial(challenge, now);
        if (stateDenial != null) {
            return stateDenial;
        }

        if (abuseLimiter.reserveIdempotency(emailKey, challenge.getChallengeId(), key)) {
            PasswordResetOtpChallenge replay = persistence.find(emailKey, challenge.getChallengeId())
                    .orElse(challenge);
            return accepted(replay, now);
        }

        OptionalLong operationLimit = abuseLimiter.consumeEmailOperation(clientIp);
        if (operationLimit.isPresent()) {
            return rateLimited(operationLimit.getAsLong(), now);
        }

        String rawCode = generateCode();
        String operationId = UUID.randomUUID().toString();
        Optional<PasswordResetOtpChallenge> reserved = persistence.tryReserveResend(
                emailKey,
                challenge.getChallengeId(),
                persistence.digestCode(rawCode),
                key,
                operationId,
                now);
        if (reserved.isEmpty()) {
            PasswordResetOtpChallenge latest = persistence.find(emailKey, challenge.getChallengeId())
                    .orElse(challenge);
            if (key.equals(latest.getClientIdempotencyKey())) {
                return accepted(latest, now);
            }
            Result denial = resendStateDenial(latest, now);
            return denial == null ? cooldown(latest, now) : denial;
        }

        String recipientEmail = null;
        if (userRepository != null) {
            recipientEmail = userRepository.findById(reserved.get().getUserId()).map(User::getEmail).orElse(null);
        }
        if (recipientEmail == null) {
            recipientEmail = persistence.findUserEmailById(reserved.get().getUserId()).orElse(null);
        }
        if (recipientEmail != null) {
            deliver(recipientEmail, rawCode, reserved.get());
        } else {
            persistence.markSendOutcome(
                    emailKey,
                    operationId,
                    OtpSendStatus.DEFINITELY_FAILED,
                    persistence.now());
        }
        return accepted(persistence.findByEmailKey(emailKey).orElse(reserved.get()), now);
    }

    public Result complete(
            String normalizedEmail,
            String publicHandle,
            String code,
            String rawPassword,
            String clientIp,
            String idempotencyKey) {
        Instant now = persistence.now();
        OptionalLong verificationLimit = abuseLimiter.consumeVerification(clientIp);
        if (verificationLimit.isPresent()) {
            return rateLimited(verificationLimit.getAsLong(), now);
        }

        String passwordError = validatePassword(rawPassword);
        if (passwordError != null) {
            return invalidRequest(passwordError, now);
        }
        if (code == null || !STRICT_CODE.matcher(code).matches()) {
            return invalidCode(now);
        }
        String completionKey = requireIdempotencyKey(idempotencyKey);
        if (completionKey == null) {
            return invalidRequest("A valid Idempotency-Key header is required.", now);
        }

        String emailKey = persistence.emailKey(normalizedEmail);
        Optional<PasswordResetPublicHandleService.Handle> parsed = handleService.parse(publicHandle);
        if (parsed.isEmpty() || !emailKey.equals(parsed.get().emailKey())) {
            PasswordUtil.dummyPasswordCheck(code);
            return invalidCode(now);
        }
        Optional<PasswordResetOtpChallenge> found =
                persistence.find(emailKey, parsed.get().internalChallengeId());
        if (found.isEmpty()) {
            PasswordUtil.dummyPasswordCheck(code);
            return invalidCode(now);
        }

        PasswordResetOtpChallenge challenge = found.get();
        if (challenge.getPurpose() != PasswordResetOtpChallenge.Purpose.PASSWORD_RESET) {
            return invalidCode(now);
        }
        if (challenge.getChallengeExpiresAt() == null
                || !challenge.getChallengeExpiresAt().isAfter(now)) {
            return challengeExpired(now);
        }
        if (challenge.getStatus() == PasswordResetChallengeStatus.LOCKED) {
            return locked(now);
        }

        String operationId = hasher.hashRaw(
                "password-reset-operation|" + emailKey + "|"
                        + completionKey);
        if (challenge.getStatus() == PasswordResetChallengeStatus.CONSUMED) {
            if (operationId.equals(challenge.getResetOperationId()) && challenge.isPasswordUpdated()) {
                return completed(now);
            }
            return invalidCode(now);
        }
        if (challenge.getStatus() == PasswordResetChallengeStatus.PROCESSING) {
            if (!operationId.equals(challenge.getResetOperationId())) {
                return invalidCode(now);
            }
            return executeResumeMutation(challenge, operationId, rawPassword, now);
        }
        if (challenge.getStatus() != PasswordResetChallengeStatus.ACTIVE) {
            return invalidCode(now);
        }
        if (challenge.getCodeExpiresAt() == null || !challenge.getCodeExpiresAt().isAfter(now)) {
            return codeExpired(now);
        }

        if (!persistence.codeMatches(code, challenge.getCodeDigest())) {
            Optional<PasswordResetOtpChallenge> failed = persistence.recordFailedVerification(
                    emailKey,
                    challenge.getChallengeId(),
                    challenge.getCodeDigest(),
                    now);
            if (failed.isPresent()
                    && failed.get().getFailedVerificationAttempts()
                    >= properties.getMaxVerificationFailures()) {
                persistence.lockAfterFailures(emailKey, challenge.getChallengeId(), now);
                return locked(now);
            }
            return invalidCode(now);
        }

        return executeClaimMutation(challenge, operationId, rawPassword, now);
    }

    private Result executeClaimMutation(
            PasswordResetOtpChallenge challenge,
            String operationId,
            String rawPassword,
            Instant now) {
        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                challenge.getEmailKey(),
                challenge.getChallengeId(),
                challenge.getCodeDigest(),
                operationId,
                rawPassword,
                now);
        if (result.isSuccess()) {
            observability.record(
                    AuthSecurityObservability.Flow.PASSWORD_RESET,
                    AuthSecurityObservability.Outcome.SUCCESS,
                    AuthSecurityObservability.Reason.RESET_VERIFICATION_SUCCESS);
            observability.record(
                    AuthSecurityObservability.Flow.PASSWORD_RESET,
                    AuthSecurityObservability.Outcome.SUCCESS,
                    AuthSecurityObservability.Reason.PASSWORD_RESET_COMPLETED);
            return completed(now);
        }
        if (result.isAlreadyCompleted()) {
            return completed(now);
        }
        if (result.status() == PasswordResetExecutionResult.Status.CHALLENGE_LOCKED) {
            return locked(now);
        }
        if (result.status() == PasswordResetExecutionResult.Status.CODE_EXPIRED) {
            return codeExpired(now);
        }
        if (result.status() == PasswordResetExecutionResult.Status.CHALLENGE_EXPIRED) {
            return challengeExpired(now);
        }
        return invalidCode(now);
    }

    private Result executeResumeMutation(
            PasswordResetOtpChallenge challenge,
            String operationId,
            String rawPassword,
            Instant now) {
        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                challenge.getEmailKey(),
                challenge.getChallengeId(),
                challenge.getCodeDigest(),
                operationId,
                rawPassword,
                now);
        if (result.isSuccess() || result.isAlreadyCompleted()) {
            return completed(now);
        }
        return invalidCode(now);
    }

    private void deliver(String recipient, String rawCode, PasswordResetOtpChallenge challenge) {
        try {
            emailSenderService.sendPasswordResetCodeEmail(
                    recipient,
                    rawCode,
                    challenge.getSendOperationId());
            persistence.markSendOutcome(
                    challenge.getEmailKey(),
                    challenge.getSendOperationId(),
                    OtpSendStatus.SENT,
                    persistence.now());
        } catch (AmbiguousEmailDeliveryException ambiguous) {
            persistence.markSendOutcome(
                    challenge.getEmailKey(),
                    challenge.getSendOperationId(),
                    OtpSendStatus.DELIVERY_UNKNOWN,
                    persistence.now());
            observability.record(
                    AuthSecurityObservability.Flow.PASSWORD_RESET,
                    AuthSecurityObservability.Outcome.FAILURE,
                    AuthSecurityObservability.Reason.PROVIDER_FAILURE);
        } catch (RuntimeException definite) {
            persistence.markSendOutcome(
                    challenge.getEmailKey(),
                    challenge.getSendOperationId(),
                    OtpSendStatus.DEFINITELY_FAILED,
                    persistence.now());
            observability.record(
                    AuthSecurityObservability.Flow.PASSWORD_RESET,
                    AuthSecurityObservability.Outcome.FAILURE,
                    AuthSecurityObservability.Reason.PROVIDER_FAILURE);
        }
    }

    private PasswordResetOtpChallenge newChallenge(
            String emailKey,
            String userId,
            String digest,
            String idempotencyKey,
            String sendOperationId,
            Instant now) {
        PasswordResetOtpChallenge challenge = new PasswordResetOtpChallenge();
        challenge.setEmailKey(emailKey);
        challenge.setChallengeId(UUID.randomUUID().toString());
        challenge.setUserId(userId);
        challenge.setPurpose(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET);
        challenge.setStatus(PasswordResetChallengeStatus.ACTIVE);
        challenge.setCodeDigest(digest);
        challenge.setCodeExpiresAt(now.plus(properties.getCodeTtl()));
        challenge.setChallengeExpiresAt(now.plus(properties.getChallengeTtl()));
        challenge.setNextResendAt(now.plus(properties.getResendCooldown()));
        challenge.setClientIdempotencyKey(idempotencyKey);
        challenge.setSendOperationId(sendOperationId);
        challenge.setSendStatus(OtpSendStatus.RESERVED);
        challenge.setSendLeaseUntil(now.plus(properties.getSendLease()));
        challenge.setCreatedAt(now);
        challenge.setUpdatedAt(now);
        return challenge;
    }

    private Result resendShadow(
            PasswordResetPublicHandleService.Handle handle,
            String idempotencyKey,
            String clientIp,
            Instant now) {
        if (!handle.challengeExpiresAt().isAfter(now)) {
            return challengeExpired(now);
        }
        if (handle.resendCount() >= properties.getMaxResends()) {
            return resendLimit(handle, now);
        }
        if (handle.nextResendAt().isAfter(now)) {
            return cooldown(handle, now);
        }
        if (abuseLimiter.reserveIdempotency(
                handle.emailKey(), handle.internalChallengeId(), idempotencyKey)) {
            return acceptedShadowHandle(handle, now);
        }
        OptionalLong operationLimit = abuseLimiter.consumeEmailOperation(clientIp);
        if (operationLimit.isPresent()) {
            return rateLimited(operationLimit.getAsLong(), now);
        }
        PasswordResetPublicHandleService.Handle rotated =
                new PasswordResetPublicHandleService.Handle(
                        handle.internalChallengeId(),
                        handle.emailKey(),
                        now.plus(properties.getResendCooldown()),
                        handle.challengeExpiresAt(),
                        handle.resendCount() + 1);
        return acceptedShadowHandle(rotated, now);
    }

    private Result resendStateDenial(PasswordResetOtpChallenge challenge, Instant now) {
        if (challenge.getChallengeExpiresAt() == null
                || !challenge.getChallengeExpiresAt().isAfter(now)) {
            return challengeExpired(now);
        }
        if (challenge.getStatus() == PasswordResetChallengeStatus.LOCKED) {
            return locked(now);
        }
        if (challenge.getStatus() != PasswordResetChallengeStatus.ACTIVE) {
            return challengeExpired(now);
        }
        if (challenge.getResendCount() >= properties.getMaxResends()) {
            return resendLimit(challenge, now);
        }
        if (challenge.getNextResendAt() != null && challenge.getNextResendAt().isAfter(now)) {
            return cooldown(challenge, now);
        }
        return null;
    }

    private Result accepted(PasswordResetOtpChallenge challenge, Instant now) {
        PasswordResetResponse response = PasswordResetResponse.of(
                "RESET_CODE_SENT",
                GENERIC_SEND_MESSAGE,
                now);
        response.setChallengeId(publicHandle(challenge));
        response.setNextResendAt(challenge.getNextResendAt());
        response.setCodeExpiresAt(challenge.getCodeExpiresAt());
        response.setChallengeExpiresAt(challenge.getChallengeExpiresAt());
        response.setResendsRemaining(Math.max(
                0,
                properties.getMaxResends() - challenge.getResendCount()));
        observability.record(
                AuthSecurityObservability.Flow.PASSWORD_RESET,
                AuthSecurityObservability.Outcome.SUCCESS,
                AuthSecurityObservability.Reason.RESET_CODE_SENT);
        return new Result(HttpStatus.ACCEPTED, response, 0L);
    }

    private Result acceptedShadow(String emailKey, Instant now, int resendCount) {
        PasswordResetPublicHandleService.Handle handle =
                new PasswordResetPublicHandleService.Handle(
                        UUID.randomUUID().toString(),
                        emailKey,
                        now.plus(properties.getResendCooldown()),
                        now.plus(properties.getChallengeTtl()),
                        resendCount);
        return acceptedShadowHandle(handle, now);
    }

    private Result acceptedShadowHandle(
            PasswordResetPublicHandleService.Handle handle,
            Instant now) {
        PasswordResetResponse response = PasswordResetResponse.of(
                "RESET_CODE_SENT",
                GENERIC_SEND_MESSAGE,
                now);
        response.setChallengeId(handleService.issue(handle));
        response.setNextResendAt(handle.nextResendAt());
        response.setCodeExpiresAt(now.plus(properties.getCodeTtl()));
        response.setChallengeExpiresAt(handle.challengeExpiresAt());
        response.setResendsRemaining(Math.max(
                0,
                properties.getMaxResends() - handle.resendCount()));
        observability.record(
                AuthSecurityObservability.Flow.PASSWORD_RESET,
                AuthSecurityObservability.Outcome.SUCCESS,
                AuthSecurityObservability.Reason.RESET_CODE_SENT);
        return new Result(HttpStatus.ACCEPTED, response, 0L);
    }

    private String publicHandle(PasswordResetOtpChallenge challenge) {
        return handleService.issue(new PasswordResetPublicHandleService.Handle(
                challenge.getChallengeId(),
                challenge.getEmailKey(),
                challenge.getNextResendAt(),
                challenge.getChallengeExpiresAt(),
                challenge.getResendCount()));
    }

    private Result cooldown(PasswordResetOtpChallenge challenge, Instant now) {
        recordResetSendDenied();
        PasswordResetResponse response = responseFromChallenge(
                "RESET_CODE_RESEND_COOLDOWN",
                "Please wait before requesting another reset code.",
                challenge,
                now);
        long retry = retryAfter(now, challenge.getNextResendAt());
        response.setRetryAfterSeconds(retry);
        return new Result(HttpStatus.TOO_MANY_REQUESTS, response, retry);
    }

    private Result cooldown(PasswordResetPublicHandleService.Handle handle, Instant now) {
        recordResetSendDenied();
        PasswordResetResponse response = PasswordResetResponse.of(
                "RESET_CODE_RESEND_COOLDOWN",
                "Please wait before requesting another reset code.",
                now);
        response.setChallengeId(handleService.issue(handle));
        response.setNextResendAt(handle.nextResendAt());
        response.setChallengeExpiresAt(handle.challengeExpiresAt());
        response.setResendsRemaining(Math.max(
                0,
                properties.getMaxResends() - handle.resendCount()));
        long retry = retryAfter(now, handle.nextResendAt());
        response.setRetryAfterSeconds(retry);
        return new Result(HttpStatus.TOO_MANY_REQUESTS, response, retry);
    }

    private Result resendLimit(PasswordResetOtpChallenge challenge, Instant now) {
        recordResetSendDenied();
        PasswordResetResponse response = responseFromChallenge(
                "RESET_CODE_RESEND_LIMIT_REACHED",
                "No more reset codes can be sent for this reset attempt.",
                challenge,
                now);
        long retry = retryAfter(now, challenge.getChallengeExpiresAt());
        response.setRetryAfterSeconds(retry);
        return new Result(HttpStatus.TOO_MANY_REQUESTS, response, retry);
    }

    private Result resendLimit(PasswordResetPublicHandleService.Handle handle, Instant now) {
        recordResetSendDenied();
        PasswordResetResponse response = PasswordResetResponse.of(
                "RESET_CODE_RESEND_LIMIT_REACHED",
                "No more reset codes can be sent for this reset attempt.",
                now);
        response.setChallengeId(handleService.issue(handle));
        response.setChallengeExpiresAt(handle.challengeExpiresAt());
        response.setResendsRemaining(0);
        long retry = retryAfter(now, handle.challengeExpiresAt());
        response.setRetryAfterSeconds(retry);
        return new Result(HttpStatus.TOO_MANY_REQUESTS, response, retry);
    }

    private Result rateLimited(long retry, Instant now) {
        PasswordResetResponse response = PasswordResetResponse.of(
                "RESET_RATE_LIMITED",
                "Too many reset requests. Please try again later.",
                now);
        response.setRetryAfterSeconds(retry);
        observability.record(
                AuthSecurityObservability.Flow.PASSWORD_RESET,
                AuthSecurityObservability.Outcome.DENIED,
                AuthSecurityObservability.Reason.RESET_SEND_DENIED);
        return new Result(HttpStatus.TOO_MANY_REQUESTS, response, retry);
    }

    private Result challengeExpired(Instant now) {
        observability.record(
                AuthSecurityObservability.Flow.PASSWORD_RESET,
                AuthSecurityObservability.Outcome.DENIED,
                AuthSecurityObservability.Reason.RESET_CHALLENGE_EXPIRED);
        return new Result(
                HttpStatus.GONE,
                PasswordResetResponse.of(
                        "RESET_CHALLENGE_EXPIRED",
                        "This password-reset session has expired.",
                        now),
                0L);
    }

    private Result codeExpired(Instant now) {
        return new Result(
                HttpStatus.GONE,
                PasswordResetResponse.of(
                        "RESET_CHALLENGE_EXPIRED",
                        "This password-reset code has expired.",
                        now),
                0L);
    }

    private Result locked(Instant now) {
        observability.record(
                AuthSecurityObservability.Flow.PASSWORD_RESET,
                AuthSecurityObservability.Outcome.DENIED,
                AuthSecurityObservability.Reason.RESET_CHALLENGE_LOCKED);
        return new Result(
                HttpStatus.TOO_MANY_REQUESTS,
                PasswordResetResponse.of(
                        "RESET_VERIFICATION_LOCKED",
                        "Verification is locked for this password-reset attempt.",
                        now),
                0L);
    }

    private Result invalidCode(Instant now) {
        observability.record(
                AuthSecurityObservability.Flow.PASSWORD_RESET,
                AuthSecurityObservability.Outcome.INVALID,
                AuthSecurityObservability.Reason.INVALID_RESET_CODE);
        return new Result(
                HttpStatus.BAD_REQUEST,
                PasswordResetResponse.of(
                        "RESET_CODE_INVALID",
                        "Invalid password-reset code.",
                        now),
                0L);
    }

    private Result invalidRequest(String message, Instant now) {
        return new Result(
                HttpStatus.BAD_REQUEST,
                PasswordResetResponse.of("RESET_CODE_INVALID", message, now),
                0L);
    }

    private Result completed(Instant now) {
        return new Result(
                HttpStatus.OK,
                PasswordResetResponse.of(
                        "PASSWORD_RESET_COMPLETED",
                        "Password reset completed.",
                        now),
                0L);
    }

    private PasswordResetResponse responseFromChallenge(
            String code,
            String message,
            PasswordResetOtpChallenge challenge,
            Instant now) {
        PasswordResetResponse response = PasswordResetResponse.of(code, message, now);
        response.setChallengeId(publicHandle(challenge));
        response.setNextResendAt(challenge.getNextResendAt());
        response.setCodeExpiresAt(challenge.getCodeExpiresAt());
        response.setChallengeExpiresAt(challenge.getChallengeExpiresAt());
        response.setResendsRemaining(Math.max(
                0,
                properties.getMaxResends() - challenge.getResendCount()));
        return response;
    }

    private String validatePassword(String password) {
        if (password == null || password.isEmpty()) return "Password is required.";
        if (password.length() < 8) return "Password must be at least 8 characters long.";
        if (password.length() > 128) return "Password must be 128 characters or fewer.";
        if (password.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            return "Password contains unsupported control characters.";
        }
        if (!LOWERCASE.matcher(password).find()) return "Password must include one lowercase letter.";
        if (!UPPERCASE.matcher(password).find()) return "Password must include one uppercase letter.";
        if (!NUMBER.matcher(password).find()) return "Password must include one number.";
        if (!SPECIAL.matcher(password).find()) return "Password must include one special character.";
        return null;
    }

    private String generateCode() {
        return String.valueOf(100000 + secureRandom.nextInt(900000));
    }

    private String normalizeIdempotencyKey(String key) {
        String normalized = requireIdempotencyKey(key);
        return normalized == null ? UUID.randomUUID().toString() : normalized;
    }

    private String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.matches("[A-Za-z0-9._:-]{8,128}") ? trimmed : null;
    }

    private long retryAfter(Instant now, Instant until) {
        if (until == null) return 1L;
        return Math.max(1L, Duration.between(now, until).getSeconds());
    }

    private void recordResetSendDenied() {
        observability.record(
                AuthSecurityObservability.Flow.PASSWORD_RESET,
                AuthSecurityObservability.Outcome.DENIED,
                AuthSecurityObservability.Reason.RESET_SEND_DENIED);
    }
}
