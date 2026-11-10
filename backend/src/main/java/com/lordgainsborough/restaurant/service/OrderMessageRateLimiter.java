package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/** Shared PostgreSQL counters; never fall back to process-local limits. */
@Service
public class OrderMessageRateLimiter {
    public static final int MAX_TEXT_LENGTH = 2000;
    // Allows 2,000 JSON-escaped characters plus the surrounding object.
    public static final int MAX_REQUEST_BYTES = 16 * 1024;
    private final AbuseCounterStore counters;
    private final AbuseIpHasher hasher;
    private final Clock clock;
    private final int perIp;
    private final int perOrder;

    public OrderMessageRateLimiter(AbuseCounterStore counters, AbuseIpHasher hasher, Clock clock,
            @Value("${app.orders.messages.per-ip-per-minute:60}") int perIp,
            @Value("${app.orders.messages.per-order-per-minute:10}") int perOrder) {
        if (perIp < 1 || perOrder < 1) throw new IllegalArgumentException("Message limits must be positive");
        this.counters = counters; this.hasher = hasher; this.clock = clock;
        this.perIp = perIp; this.perOrder = perOrder;
    }

    public OptionalLong consumeIp(String ip) {
        return consume(BucketType.ORDER_MESSAGE_IP, hasher.hashIp(ip), perIp);
    }

    public OptionalLong consumeOrder(String orderId, boolean kitchen) {
        return consume(BucketType.ORDER_MESSAGE_ORDER,
                hasher.hashRaw("message-order:" + (kitchen ? "KDS:" : "CUSTOMER:") + orderId), perOrder);
    }

    private OptionalLong consume(BucketType type, String subject, int limit) {
        Instant now = clock.instant();
        Instant start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 60) * 60);
        String id = hasher.hashRaw(type.name() + "|" + subject + "|" + start.getEpochSecond());
        long count = counters.incrementAndGet(id, type, subject, start, start.plusSeconds(360));
        return count <= limit ? OptionalLong.empty()
                : OptionalLong.of(Math.max(1, Duration.between(now, start.plusSeconds(60)).toSeconds()));
    }
}
