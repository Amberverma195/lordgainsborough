package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.LoginRateLimitProperties;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Service managing login rate limits and temporary account lockouts.
 * <p>
 * Enforces per-IP request counting (via {@link AbuseCounterStore}) and login failure
 * state tracking with progressive cooldowns (via {@link LoginFailureStateStore}).
 * </p>
 */
@Service
public class LoginRateLimiter {

    public record FailureResult(boolean denied, long retryAfterSeconds) {}

    private static final Duration TTL_BUFFER = Duration.ofMinutes(5);

    private final AbuseCounterStore counterStore;
    private final LoginFailureStateStore failureStore;
    private final LoginRateLimitProperties properties;
    private final AbuseIpHasher hasher;
    private final Clock clock;

    @Autowired
    public LoginRateLimiter(
            AbuseCounterStore counterStore,
            LoginFailureStateStore failureStore,
            LoginRateLimitProperties properties,
            AbuseIpHasher hasher,
            Clock clock) {
        this.counterStore = Objects.requireNonNull(counterStore, "counterStore must not be null");
        this.failureStore = Objects.requireNonNull(failureStore, "failureStore must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Backward-compatible constructor for existing MongoDB-backed instantiations.
     */
    public LoginRateLimiter(
            AbuseCounterStore counterStore,
            MongoTemplate mongoTemplate,
            LoginRateLimitProperties properties,
            AbuseIpHasher hasher,
            Clock clock) {
        this(counterStore, new MongoLoginFailureStateStore(mongoTemplate), properties, hasher, clock);
    }

    public OptionalLong consumeIpRequest(String clientIp) {
        Instant now = clock.instant();
        Instant windowStart = alignWindowStart(now, properties.getIpWindow());
        String subjectHash = hasher.hashIp(clientIp);
        String id = hasher.hashRaw(
                BucketType.LOGIN_REQUEST_IP.name() + "|" + subjectHash + "|" + windowStart.getEpochSecond());
        long count = counterStore.incrementAndGet(
                id,
                BucketType.LOGIN_REQUEST_IP,
                subjectHash,
                windowStart,
                windowStart.plus(properties.getIpWindow()).plus(TTL_BUFFER));
        if (count <= properties.getIpAttempts()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(retryAfterSeconds(now, windowStart.plus(properties.getIpWindow())));
    }

    public OptionalLong activeCooldown(String normalizedIdentifier, String clientIp) {
        String id = failureStateId(normalizedIdentifier, clientIp);
        return failureStore.activeCooldown(id, clock.instant());
    }

    /**
     * Atomically records one failed authentication. The failure reaching the threshold is
     * still returned as invalid credentials; later requests observe the stored cooldown.
     */
    public FailureResult recordFailure(String normalizedIdentifier, String clientIp) {
        String id = failureStateId(normalizedIdentifier, clientIp);
        String subjectHash = failureSubjectHash(normalizedIdentifier, clientIp);
        return failureStore.recordFailure(id, subjectHash, clock.instant(), properties);
    }

    public void clearFailures(String normalizedIdentifier, String clientIp) {
        String id = failureStateId(normalizedIdentifier, clientIp);
        failureStore.clearFailures(id);
    }

    String failureStateId(String normalizedIdentifier, String clientIp) {
        return hasher.hashRaw("login-failure-state|" + failureSubjectHash(normalizedIdentifier, clientIp));
    }

    String failureSubjectHash(String normalizedIdentifier, String clientIp) {
        String safeIdentifier = normalizedIdentifier == null ? "" : normalizedIdentifier.trim().toLowerCase();
        return hasher.hashRaw("login-identifier-ip|" + safeIdentifier + "|" + hasher.hashIp(clientIp));
    }

    private static Instant alignWindowStart(Instant now, Duration window) {
        long seconds = Math.max(1L, window.getSeconds());
        return Instant.ofEpochSecond((now.getEpochSecond() / seconds) * seconds);
    }

    private static long retryAfterSeconds(Instant now, Instant until) {
        return Math.max(1L, Duration.between(now, until).getSeconds());
    }
}
