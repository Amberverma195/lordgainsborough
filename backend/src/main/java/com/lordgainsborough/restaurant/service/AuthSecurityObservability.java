package com.lordgainsborough.restaurant.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded authentication metrics and safe structured logs. Callers may only use enum values.
 */
@Component
public class AuthSecurityObservability {

    public enum Flow { LOGIN, PASSWORD_RESET, SIGNUP }
    public enum Outcome { SUCCESS, INVALID, DENIED, FAILURE }
    public enum Reason {
        LOGIN_SUCCESS,
        INVALID_CREDENTIALS,
        IP_LIMIT,
        IDENTIFIER_IP_COOLDOWN,
        RESET_CODE_SENT,
        RESET_SEND_DENIED,
        PROVIDER_FAILURE,
        RESET_VERIFICATION_SUCCESS,
        INVALID_RESET_CODE,
        RESET_CHALLENGE_LOCKED,
        RESET_CHALLENGE_EXPIRED,
        PASSWORD_RESET_COMPLETED,
        SIGNUP_ATTEMPT_MISMATCH,
        SIGNUP_ATTEMPT_RESTARTED,
        SIGNUP_SUCCESS
    }

    private static final Logger logger = LoggerFactory.getLogger(AuthSecurityObservability.class);
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public AuthSecurityObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(Flow flow, Outcome outcome, Reason reason) {
        String key = flow.name() + "|" + outcome.name() + "|" + reason.name();
        counters.computeIfAbsent(key, ignored -> Counter.builder("auth.security.operations")
                        .description("Authentication security operation outcomes")
                        .tag("flow", flow.name().toLowerCase())
                        .tag("outcome", outcome.name().toLowerCase())
                        .tag("reason", reason.name().toLowerCase())
                        .register(meterRegistry))
                .increment();
        var builder = logger.atInfo()
                .addKeyValue("event", "auth_security_operation");
        if (MDC.get(SignupOtpObservability.CORRELATION_MDC_KEY) == null) {
            String cid = SignupOtpObservability.currentCorrelationId();
            if (cid != null && !"none".equals(cid)) {
                builder.addKeyValue("correlationId", cid);
            }
        }
        builder.addKeyValue("flow", flow.name().toLowerCase())
                .addKeyValue("outcome", outcome.name().toLowerCase())
                .addKeyValue("reason", reason.name().toLowerCase())
                .log("Authentication security operation");
    }
}
