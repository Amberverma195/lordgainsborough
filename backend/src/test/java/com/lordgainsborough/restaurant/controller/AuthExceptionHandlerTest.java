package com.lordgainsborough.restaurant.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthExceptionHandlerTest {

    private final AuthExceptionHandler handler = new AuthExceptionHandler();

    @Test
    void illegalArgumentReturnsGenericBadRequestWithoutInternalDetails() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("secret internal detail"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(
                "Unable to process this request. Please check your details and try again.",
                response.getBody().get("message"));
        assertFalse(response.getBody().values().stream().anyMatch(v -> v.contains("secret")));
    }

    @Test
    void unexpectedExceptionReturnsGeneric500WithoutStackOrClassNames() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String sensitiveMessage = "otp=123456 email=member@example.com ip=203.0.113.7";

        ResponseEntity<Map<String, String>> response =
                handler.handleUnexpected(new RuntimeException(sensitiveMessage));

        try {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals("Something went wrong. Please try again in a moment.", response.getBody().get("message"));
            assertFalse(response.getBody().get("message").contains("Mongo"));
            assertFalse(response.getBody().get("message").contains("RuntimeException"));
            String logged = appender.list.getLast().getFormattedMessage()
                    + appender.list.getLast().getKeyValuePairs();
            assertFalse(logged.contains("123456"));
            assertFalse(logged.contains("member@example.com"));
            assertFalse(logged.contains("203.0.113.7"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void unexpectedExceptionWithExistingCorrelationIdInMdcDoesNotThrowDuplicateKeyException() {
        org.slf4j.MDC.put("correlationId", "test-corr-12345");
        jakarta.servlet.http.HttpServletRequest mockRequest = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(mockRequest.getRequestURI()).thenReturn("/api/auth/signup/send-otp");

        try {
            ResponseEntity<Map<String, String>> response =
                    handler.handleUnexpected(new RuntimeException("database query failed"), mockRequest);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals("Something went wrong. Please try again in a moment.", response.getBody().get("message"));
        } finally {
            org.slf4j.MDC.remove("correlationId");
        }
    }

    @Test
    void classifyStageCorrectlyCategorizesExceptions() {
        assertEquals("database_access",
                AuthExceptionHandler.classifyStage(new org.springframework.dao.CannotAcquireLockException("deadlock")));
        assertEquals("database_access",
                AuthExceptionHandler.classifyStage(new java.sql.SQLException("Connection refused")));

        assertEquals("email_delivery",
                AuthExceptionHandler.classifyStage(new org.springframework.mail.MailSendException("SMTP error")));
        assertEquals("email_delivery",
                AuthExceptionHandler.classifyStage(new java.net.SocketTimeoutException("connect timed out")));

        assertEquals("response_construction",
                AuthExceptionHandler.classifyStage(new org.springframework.http.converter.HttpMessageNotWritableException("serialize error")));

        assertEquals("otp_reservation",
                AuthExceptionHandler.classifyStage(new IllegalStateException("challenge lease expired")));

        assertEquals("general",
                AuthExceptionHandler.classifyStage(new NullPointerException("null reference")));
    }

    @Test
    void resolveOperationMapsUriToOperation() {
        jakarta.servlet.http.HttpServletRequest request = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);

        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/auth/signup/send-otp");
        assertEquals("signup_send_otp", AuthExceptionHandler.resolveOperation(request));

        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/auth/signup/status");
        assertEquals("signup_status", AuthExceptionHandler.resolveOperation(request));

        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/auth/signup");
        assertEquals("signup_complete", AuthExceptionHandler.resolveOperation(request));

        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/auth/login");
        assertEquals("login", AuthExceptionHandler.resolveOperation(request));

        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/auth/forgot-password/send-otp");
        assertEquals("forgot_password", AuthExceptionHandler.resolveOperation(request));

        assertEquals("unknown", AuthExceptionHandler.resolveOperation(null));
    }
}
