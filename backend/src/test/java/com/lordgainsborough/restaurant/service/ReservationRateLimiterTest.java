package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.ReservationRateLimitProperties;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReservationRateLimiterTest {

    private ReservationRateLimitProperties properties;
    private Instant now;
    private Clock clock;

    @BeforeEach
    void setUp() {
        properties = new ReservationRateLimitProperties();
        properties.setIpRequests(5); // 5 requests for testing
        properties.setIpWindow(Duration.ofMinutes(15));
        now = Instant.parse("2026-11-04T12:00:00Z");
        clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("In-memory fallback allows requests up to limit and denies subsequent requests")
    void testInMemoryRateLimiter() {
        ReservationRateLimiter limiter = new ReservationRateLimiter(null, properties, null, clock);

        for (int i = 1; i <= 5; i++) {
            OptionalLong result = limiter.consumeIpRequest("192.168.1.100");
            assertThat(result).isEmpty();
        }

        // 6th request from same IP is denied
        OptionalLong denied = limiter.consumeIpRequest("192.168.1.100");
        assertThat(denied).isPresent();
        assertThat(denied.getAsLong()).isGreaterThanOrEqualTo(1);

        // Different IP is not denied
        OptionalLong otherIp = limiter.consumeIpRequest("192.168.1.101");
        assertThat(otherIp).isEmpty();
    }

    @Test
    @DisplayName("Window reset allows new requests")
    void testWindowReset() {
        ReservationRateLimiter limiter = new ReservationRateLimiter(null, properties, null, clock);

        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.consumeIpRequest("10.0.0.1")).isEmpty();
        }
        assertThat(limiter.consumeIpRequest("10.0.0.1")).isPresent();

        // Advance clock by 16 minutes (past the 15-minute window)
        Instant later = now.plus(Duration.ofMinutes(16));
        Clock laterClock = Clock.fixed(later, ZoneOffset.UTC);
        ReservationRateLimiter laterLimiter = new ReservationRateLimiter(null, properties, null, laterClock);

        assertThat(laterLimiter.consumeIpRequest("10.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("Uses AbuseCounterStore and AbuseIpHasher when available")
    void testWithAbuseCounterStore() {
        AbuseCounterStore store = mock(AbuseCounterStore.class);
        AbuseIpHasher hasher = mock(AbuseIpHasher.class);

        when(hasher.hashIp("1.2.3.4")).thenReturn("hash-ip");
        when(hasher.hashRaw(any())).thenReturn("doc-id");

        // Allow counts 1..5
        when(store.incrementAndGet(eq("doc-id"), eq(SignupAbuseLimit.BucketType.RESERVATION_REQUEST_IP), eq("hash-ip"), any(), any()))
                .thenReturn(1L)
                .thenReturn(5L)
                .thenReturn(6L);

        ReservationRateLimiter limiter = new ReservationRateLimiter(store, properties, hasher, clock);

        assertThat(limiter.consumeIpRequest("1.2.3.4")).isEmpty();
        assertThat(limiter.consumeIpRequest("1.2.3.4")).isEmpty();
        OptionalLong denied = limiter.consumeIpRequest("1.2.3.4");
        assertThat(denied).isPresent();

        verify(store, times(3)).incrementAndGet(eq("doc-id"), eq(SignupAbuseLimit.BucketType.RESERVATION_REQUEST_IP), eq("hash-ip"), any(), any());
    }
}
