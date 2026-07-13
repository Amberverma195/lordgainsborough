package com.lordgainsborough.restaurant.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupOtpRateLimiterTest {

    private final SignupOtpRateLimiter limiter = new SignupOtpRateLimiter();

    @Test
    void firstSendInitializesBucketWithoutConsumingResendToken() {
        SignupOtpRateLimiter.AllowResult first = limiter.tryAcquire("user@example.com");

        assertTrue(first.allowed());
        assertEquals(5, first.remainingResends());
    }

    @Test
    void cooldownBlocksImmediateResend() {
        limiter.tryAcquire("user@example.com");
        SignupOtpRateLimiter.AllowResult second = limiter.tryAcquire("user@example.com");

        assertFalse(second.allowed());
        assertTrue(second.retryAfterSeconds() > 0);
        assertEquals(5, second.remainingResends());
    }

    @Test
    void allowsUpToFiveResendsAfterCooldownWindow() throws Exception {
        assertTrue(limiter.tryAcquire("cap@example.com").allowed());

        for (int i = 0; i < 5; i++) {
            forceReady("cap@example.com");
            SignupOtpRateLimiter.AllowResult resend = limiter.tryAcquire("cap@example.com");
            assertTrue(resend.allowed(), "resend " + (i + 1) + " should be allowed");
            assertEquals(4 - i, resend.remainingResends());
        }

        forceReady("cap@example.com");
        SignupOtpRateLimiter.AllowResult blocked = limiter.tryAcquire("cap@example.com");
        assertFalse(blocked.allowed());
        assertTrue(blocked.exhausted());
        assertEquals(0, blocked.remainingResends());
    }

    @Test
    void clearResetsBucket() {
        limiter.tryAcquire("reset@example.com");
        limiter.clear("reset@example.com");

        SignupOtpRateLimiter.AllowResult again = limiter.tryAcquire("reset@example.com");
        assertTrue(again.allowed());
        assertEquals(5, again.remainingResends());
    }

    @SuppressWarnings("unchecked")
    private void forceReady(String email) throws Exception {
        Field field = SignupOtpRateLimiter.class.getDeclaredField("buckets");
        field.setAccessible(true);
        ConcurrentHashMap<String, Object> buckets =
                (ConcurrentHashMap<String, Object>) field.get(limiter);
        Object bucket = buckets.get(email.trim().toLowerCase());
        Field lastSent = bucket.getClass().getDeclaredField("lastSentAtMs");
        lastSent.setAccessible(true);
        lastSent.setLong(bucket, System.currentTimeMillis() - SignupOtpRateLimiter.COOLDOWN_MS - 1);
    }
}
