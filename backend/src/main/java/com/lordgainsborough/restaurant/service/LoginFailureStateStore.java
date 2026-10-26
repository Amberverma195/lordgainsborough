package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.LoginRateLimitProperties;
import com.lordgainsborough.restaurant.service.LoginRateLimiter.FailureResult;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * Storage SPI for managing login failure state and temporary lockout enforcement.
 * <p>
 * Implementations provide persistence-layer operations for tracking failed authentication attempts,
 * evaluating active lockout cooldowns, and resetting failure states upon successful authentication.
 * </p>
 */
public interface LoginFailureStateStore {

    /**
     * Checks whether an active lockout cooldown applies to the given state ID at the specified reference instant.
     *
     * @param id  application-assigned state identifier
     * @param now reference instant to evaluate cooldown against
     * @return {@link OptionalLong} containing positive retry-after seconds if in active cooldown, or empty if not
     */
    OptionalLong activeCooldown(String id, Instant now);

    /**
     * Atomically records one failed authentication attempt for the given state ID.
     *
     * @param id          application-assigned state identifier
     * @param subjectHash keyed cryptographic digest of the client IP and normalized login identifier
     * @param now         reference instant of the failed attempt
     * @param properties  rate limit configuration rules (thresholds, windows, cooldowns)
     * @return {@link FailureResult} indicating whether the request is denied and retry-after seconds
     */
    FailureResult recordFailure(
            String id,
            String subjectHash,
            Instant now,
            LoginRateLimitProperties properties);

    /**
     * Clears any existing login failure state for the given state ID upon successful authentication.
     *
     * @param id application-assigned state identifier
     */
    void clearFailures(String id);
}
