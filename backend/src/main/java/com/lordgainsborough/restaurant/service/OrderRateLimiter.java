package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.OrderRateLimitProperties;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OrderRateLimiter {

    private static final Duration TTL_BUFFER = Duration.ofMinutes(5);

    private final AbuseCounterStore counterStore;
    private final OrderRateLimitProperties properties;
    private final AbuseIpHasher hasher;
    private final Clock clock;

    private final ConcurrentMap<String, AtomicLong> memoryCounters = new ConcurrentHashMap<>();

    @Autowired
    public OrderRateLimiter(
            @Autowired(required = false) AbuseCounterStore counterStore,
            @Autowired(required = false) OrderRateLimitProperties properties,
            @Autowired(required = false) AbuseIpHasher hasher,
            @Autowired(required = false) Clock clock) {
        this.counterStore = counterStore;
        this.properties = properties != null ? properties : new OrderRateLimitProperties();
        this.hasher = hasher;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    public OptionalLong consumeIpRequest(String clientIp) {
        int limit = properties.getIpRequests();
        Duration window = properties.getIpWindow();
        if (limit <= 0) {
            return OptionalLong.empty();
        }

        Instant now = clock.instant();
        Instant windowStart = alignWindowStart(now, window);
        long count;

        if (counterStore != null && hasher != null) {
            String subjectHash = hasher.hashIp(clientIp);
            String id = hasher.hashRaw(
                    BucketType.ORDER_REQUEST_IP.name() + "|" + subjectHash + "|" + windowStart.getEpochSecond());
            Instant expiresAt = windowStart.plus(window).plus(TTL_BUFFER);
            count = counterStore.incrementAndGet(
                    id,
                    BucketType.ORDER_REQUEST_IP,
                    subjectHash,
                    windowStart,
                    expiresAt);
        } else {
            String safeIp = clientIp != null && !clientIp.isBlank() ? clientIp.trim().toLowerCase() : "unknown";
            String key = "IP|" + safeIp + "|" + windowStart.getEpochSecond();
            count = memoryCounters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        }

        if (count > limit) {
            long retryAfter = Math.max(1L, Duration.between(now, windowStart.plus(window)).getSeconds());
            return OptionalLong.of(retryAfter);
        }
        return OptionalLong.empty();
    }

    public OptionalLong consumeAccountRequest(String accountKey) {
        int limit = properties.getAccountRequests();
        Duration window = properties.getAccountWindow();
        if (limit <= 0) {
            return OptionalLong.empty();
        }

        Instant now = clock.instant();
        Instant windowStart = alignWindowStart(now, window);
        long count;

        String safeAccount = accountKey != null && !accountKey.isBlank() ? accountKey.trim().toLowerCase() : "unknown";

        if (counterStore != null && hasher != null) {
            String subjectHash = hasher.hashRaw("account:" + safeAccount);
            String id = hasher.hashRaw(
                    BucketType.ORDER_REQUEST_ACCOUNT.name() + "|" + subjectHash + "|" + windowStart.getEpochSecond());
            Instant expiresAt = windowStart.plus(window).plus(TTL_BUFFER);
            count = counterStore.incrementAndGet(
                    id,
                    BucketType.ORDER_REQUEST_ACCOUNT,
                    subjectHash,
                    windowStart,
                    expiresAt);
        } else {
            String key = "ACC|" + safeAccount + "|" + windowStart.getEpochSecond();
            count = memoryCounters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        }

        if (count > limit) {
            long retryAfter = Math.max(1L, Duration.between(now, windowStart.plus(window)).getSeconds());
            return OptionalLong.of(retryAfter);
        }
        return OptionalLong.empty();
    }

    public void clearForTests() {
        memoryCounters.clear();
        if (counterStore != null) {
            counterStore.clear();
        }
    }

    public OrderRateLimitProperties getProperties() {
        return properties;
    }

    private static Instant alignWindowStart(Instant now, Duration window) {
        long seconds = Math.max(1L, window.getSeconds());
        return Instant.ofEpochSecond((now.getEpochSecond() / seconds) * seconds);
    }
}
