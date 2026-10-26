package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.LoginRateLimitProperties;
import com.lordgainsborough.restaurant.model.LoginFailureState;
import com.lordgainsborough.restaurant.service.LoginRateLimiter.FailureResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * MongoDB-backed implementation of {@link LoginFailureStateStore} using compare-and-set updates.
 * <p>
 * This is the default runtime implementation for the production MongoDB deployment.
 * </p>
 */
@Component("mongoLoginFailureStateStore")
public class MongoLoginFailureStateStore implements LoginFailureStateStore {

    private static final Duration TTL_BUFFER = Duration.ofMinutes(5);
    private static final int MAX_CAS_RETRIES = 20;

    private final MongoTemplate mongoTemplate;

    public MongoLoginFailureStateStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    }

    @Override
    public OptionalLong activeCooldown(String id, Instant now) {
        LoginFailureState state = mongoTemplate.findById(id, LoginFailureState.class);
        if (state == null || state.getExpiresAt() == null || !state.getExpiresAt().isAfter(now)) {
            return OptionalLong.empty();
        }
        if (state.getBlockedUntil() != null && state.getBlockedUntil().isAfter(now)) {
            return OptionalLong.of(Math.max(1L, Duration.between(now, state.getBlockedUntil()).getSeconds()));
        }
        return OptionalLong.empty();
    }

    @Override
    public FailureResult recordFailure(
            String id,
            String subjectHash,
            Instant now,
            LoginRateLimitProperties properties) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
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
                return new FailureResult(true, Math.max(1L, Duration.between(now, current.getBlockedUntil()).getSeconds()));
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
        // Safe fallback under extreme contention.
        return new FailureResult(true, Math.max(1L, properties.getCooldown().getSeconds()));
    }

    @Override
    public void clearFailures(String id) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), LoginFailureState.class);
    }
}
