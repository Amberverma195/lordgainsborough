package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory abuse counters for unit tests (mirrors Mongo upsert/$inc semantics).
 */
final class InMemoryAbuseCounterStore implements AbuseCounterStore {

    private record Entry(AtomicLong count, Instant expiresAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public long incrementAndGet(
            String id,
            BucketType type,
            String subjectHash,
            Instant windowStart,
            Instant expiresAt) {
        Entry entry = entries.compute(id, (key, existing) -> {
            if (existing == null) {
                return new Entry(new AtomicLong(1), expiresAt);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return entry.count.get();
    }

    @Override
    public long getCount(String id, Instant now) {
        Entry entry = entries.get(id);
        if (entry == null || entry.expiresAt == null || !entry.expiresAt.isAfter(now)) {
            return 0L;
        }
        return entry.count.get();
    }

    @Override
    public void clear() {
        entries.clear();
    }

    /** Simulate TTL expiry by removing expired entries (or force-clear one id). */
    void expireNow(String id) {
        entries.remove(id);
    }

    void expireAll() {
        entries.clear();
    }
}
