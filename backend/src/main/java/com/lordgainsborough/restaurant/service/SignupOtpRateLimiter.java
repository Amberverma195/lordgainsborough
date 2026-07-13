package com.lordgainsborough.restaurant.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token-bucket rate limiter for signup OTP sends.
 *
 * <p>First request for an email initializes the bucket (initial OTP) without consuming a resend token.
 * Each later request consumes one token and requires a 30s cooldown. Capacity is 5 resends.
 */
@Service
public class SignupOtpRateLimiter {

    public static final int RESEND_CAPACITY = 5;
    public static final long COOLDOWN_MS = 30_000L;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public AllowResult tryAcquire(String email) {
        String key = normalize(email);
        AtomicReference<AllowResult> result = new AtomicReference<>();

        buckets.compute(key, (ignored, existing) -> {
            long now = System.currentTimeMillis();

            // First OTP send for this signup attempt — seed the bucket, do not spend a resend token.
            if (existing == null) {
                result.set(AllowResult.allowed(RESEND_CAPACITY, seconds(COOLDOWN_MS)));
                return new Bucket(RESEND_CAPACITY, now);
            }

            long elapsed = now - existing.lastSentAtMs;
            if (elapsed < COOLDOWN_MS) {
                long retryAfterSeconds = seconds(COOLDOWN_MS - elapsed);
                result.set(AllowResult.denied(
                        "Please wait " + retryAfterSeconds + " seconds before requesting another code.",
                        retryAfterSeconds,
                        existing.tokens,
                        false));
                return existing;
            }

            if (existing.tokens <= 0) {
                result.set(AllowResult.denied(
                        "You've reached the maximum of 5 resend attempts. Please start signup again.",
                        0,
                        0,
                        true));
                return existing;
            }

            existing.tokens -= 1;
            existing.lastSentAtMs = now;
            result.set(AllowResult.allowed(existing.tokens, seconds(COOLDOWN_MS)));
            return existing;
        });

        return result.get();
    }

    public void clear(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        buckets.remove(normalize(email));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }

    private static long seconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    private static final class Bucket {
        private int tokens;
        private long lastSentAtMs;

        private Bucket(int tokens, long lastSentAtMs) {
            this.tokens = tokens;
            this.lastSentAtMs = lastSentAtMs;
        }
    }

    public record AllowResult(
            boolean allowed,
            String message,
            long retryAfterSeconds,
            int remainingResends,
            boolean exhausted) {

        public static AllowResult allowed(int remainingResends, long retryAfterSeconds) {
            return new AllowResult(true, null, retryAfterSeconds, remainingResends, false);
        }

        public static AllowResult denied(String message, long retryAfterSeconds, int remainingResends, boolean exhausted) {
            return new AllowResult(false, message, retryAfterSeconds, remainingResends, exhausted);
        }
    }
}
