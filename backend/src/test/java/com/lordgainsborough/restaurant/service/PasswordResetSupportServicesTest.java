package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordResetSupportServicesTest {

    private static final Instant NOW = Instant.parse("2026-07-13T15:00:10Z");

    private PasswordResetProperties properties;
    private MutableClock clock;
    private PasswordResetAbuseLimiter limiter;
    private PasswordResetPublicHandleService handles;

    @BeforeEach
    void setUp() {
        properties = new PasswordResetProperties();
        properties.setEmailOperationsPerIp(2);
        properties.setEmailOperationWindow(Duration.ofMinutes(15));
        properties.setGlobalEmailOperations(100);
        properties.setGlobalEmailWindow(Duration.ofMinutes(1));
        properties.setNewChallengesPerEmail(1);
        properties.setNewChallengeWindow(Duration.ofHours(1));
        properties.setVerificationsPerIp(1);
        properties.setVerificationWindow(Duration.ofMinutes(15));

        SignupAbuseProperties hashingProperties = new SignupAbuseProperties();
        hashingProperties.setIpHashSecret("password-reset-support-test-secret");
        AbuseIpHasher hasher = new AbuseIpHasher(hashingProperties);
        clock = new MutableClock(NOW);
        limiter = new PasswordResetAbuseLimiter(
                new InMemoryAbuseCounterStore(), properties, hasher, clock);
        handles = new PasswordResetPublicHandleService(hasher);
    }

    @Test
    void fixedWindowLimitsAreScopedByIpEmailAndOperationType() {
        String ip = "203.0.113.50";
        assertTrue(limiter.consumeEmailOperation(ip).isEmpty());
        assertTrue(limiter.consumeEmailOperation(ip).isEmpty());
        assertTrue(limiter.consumeEmailOperation(ip).isPresent());
        assertTrue(limiter.consumeEmailOperation("203.0.113.51").isEmpty());

        assertTrue(limiter.consumeNewChallenge("email-key-a").isEmpty());
        assertTrue(limiter.consumeNewChallenge("email-key-a").isPresent());
        assertTrue(limiter.consumeNewChallenge("email-key-b").isEmpty());

        assertTrue(limiter.consumeVerification(ip).isEmpty());
        assertTrue(limiter.consumeVerification(ip).isPresent());

        clock.advance(Duration.ofMinutes(15));
        assertTrue(limiter.consumeEmailOperation(ip).isEmpty());
        assertTrue(limiter.consumeVerification(ip).isEmpty());
    }

    @Test
    void idempotencyReservationsAreIsolatedByChallengeAndActionKey() {
        assertFalse(limiter.reserveIdempotency("email-key", "challenge-a", "action-key"));
        assertTrue(limiter.reserveIdempotency("email-key", "challenge-a", "action-key"));
        assertFalse(limiter.reserveIdempotency("email-key", "challenge-a", "other-action"));
        assertFalse(limiter.reserveIdempotency("email-key", "challenge-b", "action-key"));
        assertFalse(limiter.reserveIdempotency("other-email", "challenge-a", "action-key"));
    }

    @Test
    void publicHandleRoundTripsAndRejectsTamperingOrMalformedInput() {
        PasswordResetPublicHandleService.Handle expected =
                new PasswordResetPublicHandleService.Handle(
                        "internal-challenge",
                        "hashed-email-key",
                        NOW.plusSeconds(30),
                        NOW.plusSeconds(1_800),
                        2);
        String token = handles.issue(expected);

        assertEquals(expected, handles.parse(token).orElseThrow());

        int signatureStart = token.indexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);
        assertTrue(handles.parse(tampered).isEmpty());
        assertTrue(handles.parse("not-a-handle").isEmpty());
        assertTrue(handles.parse("x".repeat(1_025)).isEmpty());
        assertTrue(handles.parse(null).isEmpty());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
