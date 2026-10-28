package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Centralized safe logging and low-cardinality metrics for signup OTP operations.
 *
 * <p>This API intentionally accepts only opaque identifiers, counters, stable result codes,
 * and provider categories. It must never be extended with raw OTPs, OTP digests, email
 * addresses, IP addresses, secrets, or request payloads.</p>
 */
@Component
public class SignupOtpObservability {

    public static final String METRIC_NAME = "signup.otp.operations";
    public static final String CORRELATION_MDC_KEY = "correlationId";

    private static final Logger logger = LoggerFactory.getLogger(SignupOtpObservability.class);
    // Dots, colons, @ and whitespace are excluded so raw IPv4/IPv6/email values are redacted.
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern SAFE_KEYED_HASH = Pattern.compile("[A-Fa-f0-9]{32,128}");

    public enum Event {
        INITIAL_SEND_ACCEPTED("initial_send_accepted", false),
        RESEND_ACCEPTED("resend_accepted", false),
        COOLDOWN_REJECTED("cooldown_rejected", true),
        RESEND_LIMIT_REACHED("resend_limit_reached", true),
        IP_LIMIT_REJECTED("ip_limit_rejected", true),
        GLOBAL_PROVIDER_LIMIT_REJECTED("global_provider_limit_rejected", true),
        CAPTCHA_REQUIRED("captcha_required", true),
        VERIFICATION_SUCCESS("verification_success", false),
        VERIFICATION_FAILURE("verification_failure", true),
        VERIFICATION_LOCK("verification_lock", true),
        CHALLENGE_EXPIRED("challenge_expired", true),
        PROVIDER_DEFINITE_FAILURE("provider_definite_failure", true),
        PROVIDER_TIMEOUT("provider_timeout", true),
        IDEMPOTENCY_REPLAY("idempotency_replay", false),
        ATTEMPT_CONFLICT("attempt_conflict", true),
        ATTEMPT_RESTARTED("attempt_restarted", false);

        private final String metricTag;
        private final boolean warning;

        Event(String metricTag, boolean warning) {
            this.metricTag = metricTag;
            this.warning = warning;
        }

        public String metricTag() {
            return metricTag;
        }
    }

    public enum ProviderCategory {
        ACCEPTED,
        DEFINITE_FAILURE,
        TIMEOUT_OR_AMBIGUOUS
    }

    /**
     * Safe event fields only. No raw contact data or cryptographic material belongs here.
     */
    public record Context(
            String challengeId,
            String sendOperationId,
            String emailKey,
            String clientKey,
            String logicalResultCode,
            Integer resendCount,
            Integer failedVerifyAttempts,
            ProviderCategory providerCategory) {

        public static Context fromChallenge(
                SignupOtpChallenge challenge,
                String logicalResultCode,
                ProviderCategory providerCategory) {
            if (challenge == null) {
                return new Context(
                        null, null, null, null, logicalResultCode, null, null, providerCategory);
            }
            return new Context(
                    challenge.getChallengeId(),
                    challenge.getSendOperationId(),
                    challenge.getEmailKey(),
                    null,
                    logicalResultCode,
                    challenge.getResendCount(),
                    challenge.getFailedVerifyAttempts(),
                    providerCategory);
        }

        public static Context forEmailKey(String emailKey, String logicalResultCode) {
            return new Context(
                    null, null, emailKey, null, logicalResultCode, null, null, null);
        }

        public static Context forClientKey(String clientKey, String logicalResultCode) {
            return new Context(
                    null, null, null, clientKey, logicalResultCode, null, null, null);
        }
    }

    private final Map<Event, Counter> counters;

    public SignupOtpObservability(MeterRegistry meterRegistry) {
        EnumMap<Event, Counter> registered = new EnumMap<>(Event.class);
        for (Event event : Event.values()) {
            registered.put(
                    event,
                    Counter.builder(METRIC_NAME)
                            .description("Signup OTP operation outcomes")
                            .tag("result", event.metricTag)
                            .register(meterRegistry));
        }
        this.counters = Map.copyOf(registered);
    }

    public void record(Event event, Context context) {
        if (event == null) {
            return;
        }
        counters.get(event).increment();

        Context safeContext = context == null
                ? new Context(null, null, null, null, null, null, null, null)
                : context;
        LoggingEventBuilder log = event.warning ? logger.atWarn() : logger.atInfo();
        log.addKeyValue("event", "signup_otp_operation")
                .addKeyValue("result", event.metricTag);
        if (MDC.get(CORRELATION_MDC_KEY) == null) {
            String cid = currentCorrelationId();
            if (cid != null) {
                log.addKeyValue("correlationId", safeIdentifier(cid));
            }
        }
        addIdentifier(log, "challengeId", safeContext.challengeId());
        addIdentifier(log, "sendOperationId", safeContext.sendOperationId());
        addKeyedHash(log, "emailKey", safeContext.emailKey());
        addKeyedHash(log, "clientKey", safeContext.clientKey());
        addIdentifier(log, "logicalResultCode", safeContext.logicalResultCode());
        if (safeContext.resendCount() != null) {
            log.addKeyValue("resendCount", Math.max(0, safeContext.resendCount()));
        }
        if (safeContext.failedVerifyAttempts() != null) {
            log.addKeyValue(
                    "failedVerifyAttempts",
                    Math.max(0, safeContext.failedVerifyAttempts()));
        }
        if (safeContext.providerCategory() != null) {
            log.addKeyValue(
                    "providerResultCategory",
                    safeContext.providerCategory().name().toLowerCase());
        }
        log.log("Signup OTP operation");
    }

    public double count(Event event) {
        Counter counter = counters.get(event);
        return counter == null ? 0.0 : counter.count();
    }

    public static String currentCorrelationId() {
        String correlationId = MDC.get(CORRELATION_MDC_KEY);
        return correlationId == null || correlationId.isBlank()
                ? "none"
                : safeIdentifier(correlationId);
    }

    private static void addIdentifier(
            LoggingEventBuilder log,
            String field,
            String value) {
        if (value != null && !value.isBlank()) {
            log.addKeyValue(field, safeIdentifier(value));
        }
    }

    private static void addKeyedHash(
            LoggingEventBuilder log,
            String field,
            String value) {
        if (value != null && !value.isBlank()) {
            log.addKeyValue(
                    field,
                    SAFE_KEYED_HASH.matcher(value).matches() ? value : "redacted_invalid");
        }
    }

    private static String safeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return SAFE_IDENTIFIER.matcher(value).matches() && !value.matches("\\d{6}")
                ? value
                : "redacted_invalid";
    }
}
