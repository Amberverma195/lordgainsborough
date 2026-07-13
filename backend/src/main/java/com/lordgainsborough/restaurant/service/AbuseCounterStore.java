package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;

import java.time.Instant;

/**
 * Persistence for fixed-window abuse counters (Mongo in production; in-memory in unit tests).
 */
public interface AbuseCounterStore {

    /**
     * Atomically increments the bucket and returns the new count.
     */
    long incrementAndGet(
            String id,
            BucketType type,
            String subjectHash,
            Instant windowStart,
            Instant expiresAt);

    /**
     * Current count if the document exists and has not expired; otherwise 0.
     */
    long getCount(String id, Instant now);

    void clear();
}
