package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

@Service
public class PasswordResetAbuseLimiter {

    private static final Duration TTL_BUFFER = Duration.ofMinutes(5);

    private final AbuseCounterStore store;
    private final PasswordResetProperties properties;
    private final AbuseIpHasher hasher;
    private final Clock clock;

    public PasswordResetAbuseLimiter(
            AbuseCounterStore store,
            PasswordResetProperties properties,
            AbuseIpHasher hasher,
            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.hasher = hasher;
        this.clock = clock;
    }

    public OptionalLong consumeNewChallenge(String emailKey) {
        return consume(
                BucketType.RESET_NEW_CHALLENGE_EMAIL,
                emailKey,
                properties.getNewChallengesPerEmail(),
                properties.getNewChallengeWindow());
    }

    public OptionalLong consumeEmailOperation(String clientIp) {
        OptionalLong ip = consume(
                BucketType.RESET_EMAIL_OPERATION_IP,
                hasher.hashIp(clientIp),
                properties.getEmailOperationsPerIp(),
                properties.getEmailOperationWindow());
        if (ip.isPresent()) {
            return ip;
        }
        return consume(
                BucketType.RESET_GLOBAL_EMAIL_OPERATION,
                hasher.hashRaw("password-reset-global"),
                properties.getGlobalEmailOperations(),
                properties.getGlobalEmailWindow());
    }

    public OptionalLong consumeVerification(String clientIp) {
        return consume(
                BucketType.RESET_VERIFICATION_IP,
                hasher.hashIp(clientIp),
                properties.getVerificationsPerIp(),
                properties.getVerificationWindow());
    }

    /**
     * Returns true when this deliberate action key was already reserved. This marker is
     * separate from limit counters so retries do not consume another IP/global unit.
     */
    public boolean reserveIdempotency(String emailKey, String challengeId, String idempotencyKey) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getChallengeTtl()).plus(TTL_BUFFER);
        String subjectHash = hasher.hashRaw(
                "reset-idempotency|" + emailKey + "|" + challengeId + "|" + idempotencyKey);
        String id = hasher.hashRaw(BucketType.RESET_IDEMPOTENCY.name() + "|" + subjectHash);
        long count = store.incrementAndGet(
                id,
                BucketType.RESET_IDEMPOTENCY,
                subjectHash,
                now,
                expiresAt);
        return count > 1;
    }

    private OptionalLong consume(BucketType type, String subjectHash, int limit, Duration window) {
        if (limit <= 0) {
            return OptionalLong.empty();
        }
        Instant now = clock.instant();
        Instant windowStart = alignWindowStart(now, window);
        String id = hasher.hashRaw(type.name() + "|" + subjectHash + "|" + windowStart.getEpochSecond());
        long count = store.incrementAndGet(
                id,
                type,
                subjectHash,
                windowStart,
                windowStart.plus(window).plus(TTL_BUFFER));
        if (count <= limit) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(
                1L,
                Duration.between(now, windowStart.plus(window)).getSeconds()));
    }

    private static Instant alignWindowStart(Instant now, Duration window) {
        long seconds = Math.max(1L, window.getSeconds());
        return Instant.ofEpochSecond((now.getEpochSecond() / seconds) * seconds);
    }

    public AbuseCounterStore getStore() { return store; }
}
