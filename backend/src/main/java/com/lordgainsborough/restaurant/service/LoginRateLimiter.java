package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.LoginRateLimitProperties;
import com.lordgainsborough.restaurant.model.LoginFailureState;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

@Service
public class LoginRateLimiter {

    public record FailureResult(boolean denied, long retryAfterSeconds) {}

    private static final Duration TTL_BUFFER = Duration.ofMinutes(5);
    private static final int MAX_CAS_RETRIES = 20;

    private final AbuseCounterStore counterStore;
    private final MongoTemplate mongoTemplate;
    private final LoginRateLimitProperties properties;
    private final AbuseIpHasher hasher;
    private final Clock clock;

    public LoginRateLimiter(
            AbuseCounterStore counterStore,
            MongoTemplate mongoTemplate,
            LoginRateLimitProperties properties,
            AbuseIpHasher hasher,
            Clock clock) {
        this.counterStore = counterStore;
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.hasher = hasher;
        this.clock = clock;
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
        Instant now = clock.instant();
        LoginFailureState state = mongoTemplate.findById(
                failureStateId(normalizedIdentifier, clientIp),
                LoginFailureState.class);
        if (state == null || state.getExpiresAt() == null || !state.getExpiresAt().isAfter(now)) {
            return OptionalLong.empty();
        }
        if (state.getBlockedUntil() != null && state.getBlockedUntil().isAfter(now)) {
            return OptionalLong.of(retryAfterSeconds(now, state.getBlockedUntil()));
        }
        return OptionalLong.empty();
    }

    /**
     * Atomically records one failed authentication. The failure reaching the threshold is
     * still returned as invalid credentials; later requests observe the stored cooldown.
     */
    public FailureResult recordFailure(String normalizedIdentifier, String clientIp) {
        String id = failureStateId(normalizedIdentifier, clientIp);
        String subjectHash = failureSubjectHash(normalizedIdentifier, clientIp);

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            Instant now = clock.instant();
            LoginFailureState current = mongoTemplate.findById(id, LoginFailureState.class);
            if (current == null || current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
                LoginFailureState created = new LoginFailureState();
                created.setId(id);
                created.setSubjectHash(subjectHash);
                created.setFailureCount(1);
                created.setWindowStartedAt(now);
                created.setUpdatedAt(now);
                created.setExpiresAt(now.plus(properties.getFailureWindow()).plus(TTL_BUFFER));
                try {
                    mongoTemplate.insert(created);
                    return new FailureResult(false, 0L);
                } catch (DuplicateKeyException race) {
                    continue;
                }
            }

            if (current.getBlockedUntil() != null && current.getBlockedUntil().isAfter(now)) {
                return new FailureResult(true, retryAfterSeconds(now, current.getBlockedUntil()));
            }

            boolean reset = current.getWindowStartedAt() == null
                    || !current.getWindowStartedAt().plus(properties.getFailureWindow()).isAfter(now)
                    || (current.getBlockedUntil() != null && !current.getBlockedUntil().isAfter(now));
            int nextCount = reset ? 1 : current.getFailureCount() + 1;
            Instant nextWindowStart = reset ? now : current.getWindowStartedAt();
            Instant blockedUntil = nextCount >= properties.getIdentifierIpFailures()
                    ? now.plus(properties.getCooldown())
                    : null;

            Query cas = Query.query(Criteria.where("_id").is(id)
                    .and("updatedAt").is(current.getUpdatedAt())
                    .and("failureCount").is(current.getFailureCount()));
            Update update = new Update()
                    .set("subjectHash", subjectHash)
                    .set("failureCount", nextCount)
                    .set("windowStartedAt", nextWindowStart)
                    .set("blockedUntil", blockedUntil)
                    .set("updatedAt", now)
                    .set("expiresAt", nextWindowStart
                            .plus(properties.getFailureWindow())
                            .plus(properties.getCooldown())
                            .plus(TTL_BUFFER));
            LoginFailureState updated = mongoTemplate.findAndModify(
                    cas,
                    update,
                    FindAndModifyOptions.options().returnNew(true),
                    LoginFailureState.class);
            if (updated != null) {
                return new FailureResult(false, 0L);
            }
        }
        // Safe failure mode under extreme contention.
        return new FailureResult(true, Math.max(1L, properties.getCooldown().getSeconds()));
    }

    public void clearFailures(String normalizedIdentifier, String clientIp) {
        mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(failureStateId(normalizedIdentifier, clientIp))),
                LoginFailureState.class);
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
