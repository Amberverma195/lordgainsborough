package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupAbuseLimiterTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-13T10:00:00Z");

    private SignupAbuseProperties properties;
    private InMemoryAbuseCounterStore store;
    private AtomicReference<Instant> now;
    private SignupAbuseLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new SignupAbuseProperties();
        properties.setIpHashSecret("test-abuse-secret");
        properties.setInitialSendsPerIp(5);
        properties.setInitialSendWindow(Duration.ofMinutes(15));
        properties.setTotalSendsPerIp(20);
        properties.setTotalSendWindow(Duration.ofHours(24));
        properties.setVerificationsPerIp(8);
        properties.setVerificationWindow(Duration.ofMinutes(15));
        properties.setGlobalProviderSends(50);
        properties.setGlobalProviderWindow(Duration.ofMinutes(1));
        properties.setCaptchaEnabled(false);
        properties.setCaptchaAfterInitialSends(3);

        store = new InMemoryAbuseCounterStore();
        now = new AtomicReference<>(WINDOW_START.plusSeconds(30));
        Clock mutable = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        AbuseIpHasher hasher = new AbuseIpHasher(properties);
        limiter = new SignupAbuseLimiter(store, properties, hasher, mutable);
    }

    @Test
    void oneIpTargetingManyEmailsIsBlockedByInitialSendLimit() {
        String ip = "203.0.113.10";
        for (int i = 0; i < 5; i++) {
            assertInstanceOf(
                    SignupAbuseLimiter.Decision.Allowed.class,
                    limiter.checkAndConsumeSend(ip, true, null));
        }

        SignupAbuseLimiter.Decision denied = limiter.checkAndConsumeSend(ip, true, null);
        SignupAbuseLimiter.Decision.Denied d =
                assertInstanceOf(SignupAbuseLimiter.Decision.Denied.class, denied);
        assertEquals(SignupAbuseLimiter.SignupAbuseDenyReason.INITIAL_SEND_IP, d.reason());
        assertTrue(d.retryAfterSeconds() > 0);
    }

    @Test
    void manyVerificationRequestsFromOneIpAreLimited() {
        String ip = "203.0.113.11";
        for (int i = 0; i < 8; i++) {
            assertInstanceOf(
                    SignupAbuseLimiter.Decision.Allowed.class,
                    limiter.checkAndConsumeVerification(ip));
        }

        SignupAbuseLimiter.Decision denied = limiter.checkAndConsumeVerification(ip);
        SignupAbuseLimiter.Decision.Denied d =
                assertInstanceOf(SignupAbuseLimiter.Decision.Denied.class, denied);
        assertEquals(SignupAbuseLimiter.SignupAbuseDenyReason.VERIFY_IP, d.reason());
        assertTrue(d.retryAfterSeconds() > 0);
    }

    @Test
    void sharedIpLegitimateUsageStaysUnderGenerousDefaults() {
        // 12 different "users" each doing one initial send — under limit of 20 (test uses 5 for other tests;
        // bump for this scenario).
        properties.setInitialSendsPerIp(20);
        properties.setTotalSendsPerIp(100);
        String sharedNat = "198.51.100.40";

        for (int i = 0; i < 12; i++) {
            assertInstanceOf(
                    SignupAbuseLimiter.Decision.Allowed.class,
                    limiter.checkAndConsumeSend(sharedNat, true, null),
                    "shared IP send #" + i);
        }
    }

    @Test
    void limiterAllowsAgainAfterWindowExpiration() {
        properties.setInitialSendsPerIp(2);
        String ip = "203.0.113.12";

        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));
        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));
        assertInstanceOf(SignupAbuseLimiter.Decision.Denied.class,
                limiter.checkAndConsumeSend(ip, true, null));

        // Advance past the 15-minute fixed window → new bucket id (TTL would drop old docs).
        now.set(WINDOW_START.plus(Duration.ofMinutes(15)).plusSeconds(1));

        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));
    }

    @Test
    void differentIpsAreTrackedIndependently() {
        properties.setInitialSendsPerIp(1);
        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend("203.0.113.1", true, null));
        assertInstanceOf(SignupAbuseLimiter.Decision.Denied.class,
                limiter.checkAndConsumeSend("203.0.113.1", true, null));
        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend("203.0.113.2", true, null));
    }

    @Test
    void captchaRequiredAfterSuspiciousInitialVolume() {
        properties.setCaptchaEnabled(true);
        properties.setCaptchaAfterInitialSends(2);
        properties.setCaptchaSharedToken("ok-token");
        properties.setInitialSendsPerIp(20);
        String ip = "203.0.113.13";

        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));
        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));

        SignupAbuseLimiter.Decision denied = limiter.checkAndConsumeSend(ip, true, null);
        assertEquals(
                SignupAbuseLimiter.SignupAbuseDenyReason.CAPTCHA_REQUIRED,
                ((SignupAbuseLimiter.Decision.Denied) denied).reason());

        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, "ok-token"));
    }

    @Test
    void resendsDoNotConsumeInitialBucket() {
        properties.setInitialSendsPerIp(1);
        String ip = "203.0.113.14";

        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));
        // Further initial would deny; resend-shaped request still allowed on total/global.
        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, false, null));
    }

    @Test
    void ttlExpiryClearsSameWindowCounter() {
        properties.setInitialSendsPerIp(1);
        String ip = "203.0.113.16";
        String ipHash = limiter.hashClientIp(ip);
        Instant windowStart = SignupAbuseLimiter.alignWindowStart(now.get(), properties.getInitialSendWindow());
        String id = limiter.documentId(BucketType.INITIAL_SEND_IP, ipHash, windowStart);

        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));
        assertInstanceOf(SignupAbuseLimiter.Decision.Denied.class,
                limiter.checkAndConsumeSend(ip, true, null));

        store.expireNow(id); // Mongo TTL would delete the doc when expiresAt elapses

        assertEquals(0L, store.getCount(id, now.get()));
        assertInstanceOf(SignupAbuseLimiter.Decision.Allowed.class,
                limiter.checkAndConsumeSend(ip, true, null));
    }

    @Test
    void hashedSubjectsAreNotRawIps() {
        String ip = "203.0.113.15";
        limiter.checkAndConsumeSend(ip, true, null);
        String hash = limiter.hashClientIp(ip);
        assertTrue(hash.length() >= 32);
        assertTrue(!hash.contains("203"));
        assertTrue(limiter.currentCount(
                BucketType.INITIAL_SEND_IP,
                hash,
                properties.getInitialSendWindow()) >= 1);
    }
}
