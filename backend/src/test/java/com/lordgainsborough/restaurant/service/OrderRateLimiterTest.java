package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.OrderRateLimitProperties;
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

class OrderRateLimiterTest {

    private OrderRateLimitProperties properties;
    private Instant now;
    private Clock clock;

    @BeforeEach
    void setUp() {
        properties = new OrderRateLimitProperties();
        properties.setIpRequests(5);
        properties.setIpWindow(Duration.ofMinutes(15));
        properties.setAccountRequests(3);
        properties.setAccountWindow(Duration.ofMinutes(15));
        now = Instant.parse("2026-11-04T12:00:00Z");
        clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("In-memory IP rate limiter allows requests up to limit and denies subsequent requests")
    void testInMemoryIpRateLimiter() {
        OrderRateLimiter limiter = new OrderRateLimiter(null, properties, null, clock);

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
    @DisplayName("In-memory account rate limiter allows requests up to limit and denies subsequent requests")
    void testInMemoryAccountRateLimiter() {
        OrderRateLimiter limiter = new OrderRateLimiter(null, properties, null, clock);

        for (int i = 1; i <= 3; i++) {
            OptionalLong result = limiter.consumeAccountRequest("customer@example.com");
            assertThat(result).isEmpty();
        }

        // 4th request from same account is denied
        OptionalLong denied = limiter.consumeAccountRequest("customer@example.com");
        assertThat(denied).isPresent();
        assertThat(denied.getAsLong()).isGreaterThanOrEqualTo(1);

        // Different account is not denied
        OptionalLong otherAccount = limiter.consumeAccountRequest("other@example.com");
        assertThat(otherAccount).isEmpty();
    }

    @Test
    @DisplayName("Window reset allows new requests for both IP and Account")
    void testWindowReset() {
        OrderRateLimiter limiter = new OrderRateLimiter(null, properties, null, clock);

        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.consumeIpRequest("10.0.0.1")).isEmpty();
        }
        assertThat(limiter.consumeIpRequest("10.0.0.1")).isPresent();

        for (int i = 1; i <= 3; i++) {
            assertThat(limiter.consumeAccountRequest("user@example.com")).isEmpty();
        }
        assertThat(limiter.consumeAccountRequest("user@example.com")).isPresent();

        // Advance clock by 16 minutes
        Instant later = now.plus(Duration.ofMinutes(16));
        Clock laterClock = Clock.fixed(later, ZoneOffset.UTC);
        OrderRateLimiter laterLimiter = new OrderRateLimiter(null, properties, null, laterClock);

        assertThat(laterLimiter.consumeIpRequest("10.0.0.1")).isEmpty();
        assertThat(laterLimiter.consumeAccountRequest("user@example.com")).isEmpty();
    }

    @Test
    @DisplayName("Uses AbuseCounterStore and AbuseIpHasher for IP and Account rate limits")
    void testWithAbuseCounterStore() {
        AbuseCounterStore store = mock(AbuseCounterStore.class);
        AbuseIpHasher hasher = mock(AbuseIpHasher.class);

        when(hasher.hashIp("1.2.3.4")).thenReturn("hash-ip");
        when(hasher.hashRaw(any())).thenReturn("doc-id");

        // Allow counts 1..5 for IP
        when(store.incrementAndGet(eq("doc-id"), eq(SignupAbuseLimit.BucketType.ORDER_REQUEST_IP), eq("hash-ip"), any(), any()))
                .thenReturn(1L)
                .thenReturn(5L)
                .thenReturn(6L);

        // Allow counts 1..3 for Account
        when(store.incrementAndGet(eq("doc-id"), eq(SignupAbuseLimit.BucketType.ORDER_REQUEST_ACCOUNT), any(), any(), any()))
                .thenReturn(1L)
                .thenReturn(3L)
                .thenReturn(4L);

        OrderRateLimiter limiter = new OrderRateLimiter(store, properties, hasher, clock);

        // IP checks
        assertThat(limiter.consumeIpRequest("1.2.3.4")).isEmpty();
        assertThat(limiter.consumeIpRequest("1.2.3.4")).isEmpty();
        assertThat(limiter.consumeIpRequest("1.2.3.4")).isPresent();

        // Account checks
        assertThat(limiter.consumeAccountRequest("test@example.com")).isEmpty();
        assertThat(limiter.consumeAccountRequest("test@example.com")).isEmpty();
        assertThat(limiter.consumeAccountRequest("test@example.com")).isPresent();

        verify(store, times(3)).incrementAndGet(eq("doc-id"), eq(SignupAbuseLimit.BucketType.ORDER_REQUEST_IP), eq("hash-ip"), any(), any());
        verify(store, times(3)).incrementAndGet(eq("doc-id"), eq(SignupAbuseLimit.BucketType.ORDER_REQUEST_ACCOUNT), any(), any(), any());
    }

    @Test
    @DisplayName("clearForTests resets memory and counter store")
    void testClearForTests() {
        AbuseCounterStore store = mock(AbuseCounterStore.class);
        OrderRateLimiter limiter = new OrderRateLimiter(store, properties, null, clock);

        limiter.clearForTests();
        verify(store).clear();
    }
}
