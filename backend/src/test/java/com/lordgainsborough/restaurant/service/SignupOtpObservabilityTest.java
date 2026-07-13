package com.lordgainsborough.restaurant.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupOtpObservabilityTest {

    private SimpleMeterRegistry registry;
    private SignupOtpObservability observability;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observability = new SignupOtpObservability(registry);
        logger = (Logger) LoggerFactory.getLogger(SignupOtpObservability.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(SignupOtpObservability.CORRELATION_MDC_KEY, "request-123");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        logger.detachAppender(appender);
        appender.stop();
        registry.close();
    }

    @Test
    void registersAndIncrementsEveryRequiredCounter() {
        for (SignupOtpObservability.Event event : SignupOtpObservability.Event.values()) {
            observability.record(event, null);

            assertEquals(
                    1.0,
                    registry.get(SignupOtpObservability.METRIC_NAME)
                            .tag("result", event.metricTag())
                            .counter()
                            .count());
        }
    }

    @Test
    void emitsOnlyStructuredSafeFields() {
        String emailKey = "a".repeat(64);
        String clientKey = "b".repeat(64);
        observability.record(
                SignupOtpObservability.Event.RESEND_ACCEPTED,
                new SignupOtpObservability.Context(
                        "c8b53d46-406d-4f17-a35f-27bf7ab42eb6",
                        "0bb8e2d5-1bbf-4384-8312-bf81a9d4871c",
                        emailKey,
                        clientKey,
                        "OTP_SENT",
                        2,
                        1,
                        SignupOtpObservability.ProviderCategory.ACCEPTED));

        ILoggingEvent event = appender.list.getLast();
        Map<String, String> fields = event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));

        assertEquals(Level.INFO, event.getLevel());
        assertEquals("signup_otp_operation", fields.get("event"));
        assertEquals("request-123", fields.get("correlationId"));
        assertEquals("resend_accepted", fields.get("result"));
        assertEquals(emailKey, fields.get("emailKey"));
        assertEquals(clientKey, fields.get("clientKey"));
        assertEquals("2", fields.get("resendCount"));
        assertEquals("1", fields.get("failedVerifyAttempts"));
        assertEquals("accepted", fields.get("providerResultCategory"));
    }

    @Test
    void redactsOtpDigestEmailIpAndInvalidCorrelationValues() {
        String rawOtp = "123456";
        String digest = "$2a$12$abcdefghijklmnopqrstuvwxyz012345678901234567890123456";
        String email = "member@example.com";
        String ip = "203.0.113.7";
        MDC.put(SignupOtpObservability.CORRELATION_MDC_KEY, ip);

        observability.record(
                SignupOtpObservability.Event.VERIFICATION_FAILURE,
                new SignupOtpObservability.Context(
                        rawOtp,
                        digest,
                        email,
                        ip,
                        email,
                        0,
                        1,
                        null));

        ILoggingEvent event = appender.list.getLast();
        String renderedFields = event.getKeyValuePairs().toString();
        assertFalse(renderedFields.contains(rawOtp));
        assertFalse(renderedFields.contains(digest));
        assertFalse(renderedFields.contains(email));
        assertFalse(renderedFields.contains(ip));
        assertTrue(renderedFields.contains("redacted_invalid"));
    }
}
