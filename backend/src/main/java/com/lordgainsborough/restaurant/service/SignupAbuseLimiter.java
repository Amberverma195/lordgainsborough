package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Fixed-window counters for signup OTP secondary abuse limits.
 * Documents use keyed IP hashes and TTL via {@code expiresAt} (Mongo store).
 */
@Service
public class SignupAbuseLimiter {

    public static final String GLOBAL_SUBJECT = "global";

    public sealed interface Decision {
        record Allowed() implements Decision {}

        record Denied(long retryAfterSeconds, SignupAbuseDenyReason reason) implements Decision {}
    }

    public enum SignupAbuseDenyReason {
        INITIAL_SEND_IP,
        TOTAL_SEND_IP,
        VERIFY_IP,
        GLOBAL_PROVIDER_SEND,
        CAPTCHA_REQUIRED
    }

    private final AbuseCounterStore store;
    private final SignupAbuseProperties properties;
    private final AbuseIpHasher ipHasher;
    private final Clock clock;

    public SignupAbuseLimiter(
            AbuseCounterStore store,
            SignupAbuseProperties properties,
            AbuseIpHasher ipHasher,
            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.ipHasher = ipHasher;
        this.clock = clock;
    }

    public String hashClientIp(String clientIp) {
        return ipHasher.hashIp(clientIp);
    }

    /**
     * Reserve capacity for an OTP send that may hit the email provider.
     *
     * @param countAsInitial whether this looks like a first send for the email (no usable challenge)
     */
    public Decision checkAndConsumeSend(String clientIp, boolean countAsInitial, String captchaToken) {
        String ipHash = ipHasher.hashIp(clientIp);

        Decision captcha = enforceCaptchaIfNeeded(ipHash, captchaToken);
        if (captcha instanceof Decision.Denied) {
            return captcha;
        }

        if (countAsInitial) {
            Decision initial = tryConsume(
                    BucketType.INITIAL_SEND_IP,
                    ipHash,
                    properties.getInitialSendsPerIp(),
                    properties.getInitialSendWindow(),
                    SignupAbuseDenyReason.INITIAL_SEND_IP);
            if (initial instanceof Decision.Denied) {
                return initial;
            }
        }

        Decision total = tryConsume(
                BucketType.TOTAL_SEND_IP,
                ipHash,
                properties.getTotalSendsPerIp(),
                properties.getTotalSendWindow(),
                SignupAbuseDenyReason.TOTAL_SEND_IP);
        if (total instanceof Decision.Denied) {
            return total;
        }

        return tryConsume(
                BucketType.GLOBAL_PROVIDER_SEND,
                ipHasher.hashRaw(GLOBAL_SUBJECT),
                properties.getGlobalProviderSends(),
                properties.getGlobalProviderWindow(),
                SignupAbuseDenyReason.GLOBAL_PROVIDER_SEND);
    }

    public Decision checkAndConsumeVerification(String clientIp) {
        return tryConsume(
                BucketType.VERIFY_IP,
                ipHasher.hashIp(clientIp),
                properties.getVerificationsPerIp(),
                properties.getVerificationWindow(),
                SignupAbuseDenyReason.VERIFY_IP);
    }

    public boolean isCaptchaRequired(String clientIp) {
        if (!properties.isCaptchaEnabled()) {
            return false;
        }
        long count = currentCount(
                BucketType.INITIAL_SEND_IP,
                ipHasher.hashIp(clientIp),
                properties.getInitialSendWindow());
        return count >= properties.getCaptchaAfterInitialSends();
    }

    public long currentCount(BucketType type, String subjectHash, Duration window) {
        Instant now = clock.instant();
        Instant windowStart = alignWindowStart(now, window);
        String id = documentId(type, subjectHash, windowStart);
        return store.getCount(id, now);
    }

    Decision enforceCaptchaIfNeeded(String ipHash, String captchaToken) {
        if (!properties.isCaptchaEnabled()) {
            return new Decision.Allowed();
        }
        long count = currentCount(
                BucketType.INITIAL_SEND_IP,
                ipHash,
                properties.getInitialSendWindow());
        if (count < properties.getCaptchaAfterInitialSends()) {
            return new Decision.Allowed();
        }
        if (isCaptchaTokenValid(captchaToken)) {
            return new Decision.Allowed();
        }
        return new Decision.Denied(60L, SignupAbuseDenyReason.CAPTCHA_REQUIRED);
    }

    private boolean isCaptchaTokenValid(String captchaToken) {
        String expected = properties.getCaptchaSharedToken();
        return StringUtils.hasText(expected)
                && StringUtils.hasText(captchaToken)
                && expected.equals(captchaToken.trim());
    }

    Decision tryConsume(
            BucketType type,
            String subjectHash,
            int limit,
            Duration window,
            SignupAbuseDenyReason denyReason) {
        if (limit <= 0) {
            return new Decision.Allowed();
        }
        Instant now = clock.instant();
        Instant windowStart = alignWindowStart(now, window);
        Instant expiresAt = windowStart.plus(window).plus(Duration.ofMinutes(5));
        String id = documentId(type, subjectHash, windowStart);

        long count = store.incrementAndGet(id, type, subjectHash, windowStart, expiresAt);
        if (count > limit) {
            long retryAfter = Math.max(1L, Duration.between(now, windowStart.plus(window)).getSeconds());
            return new Decision.Denied(retryAfter, denyReason);
        }
        return new Decision.Allowed();
    }

    static Instant alignWindowStart(Instant now, Duration window) {
        long seconds = Math.max(1L, window.getSeconds());
        long epoch = now.getEpochSecond();
        long aligned = (epoch / seconds) * seconds;
        return Instant.ofEpochSecond(aligned);
    }

    String documentId(BucketType type, String subjectHash, Instant windowStart) {
        return ipHasher.hashRaw(type.name() + "|" + subjectHash + "|" + windowStart.getEpochSecond());
    }

    /** Test helper. */
    public void clearAllForTests() {
        store.clear();
    }

    AbuseCounterStore storeForTests() {
        return store;
    }
}
