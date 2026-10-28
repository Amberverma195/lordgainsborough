package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.service.EmailDeliveryException;
import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "message", "Unable to process this request. Please check your details and try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex, HttpServletRequest request) {
        String operation = resolveOperation(request);
        String stage = classifyStage(ex);

        var builder = logger.atError();
        if (MDC.get(SignupOtpObservability.CORRELATION_MDC_KEY) == null) {
            String cid = SignupOtpObservability.currentCorrelationId();
            if (cid != null && !"none".equals(cid)) {
                builder.addKeyValue("correlationId", cid);
            }
        }
        builder.addKeyValue("operation", operation)
                .addKeyValue("stage", stage)
                .addKeyValue("exceptionClass", ex.getClass().getName())
                .addKeyValue("exceptionType", ex.getClass().getSimpleName())
                .log("Unexpected authentication controller error");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "message", "Something went wrong. Please try again in a moment."));
    }

    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        return handleUnexpected(ex, null);
    }

    static String classifyStage(Throwable ex) {
        if (ex == null) {
            return "general";
        }
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String className = current.getClass().getName();
            String message = current.getMessage() != null ? current.getMessage().toLowerCase() : "";

            if (current instanceof org.springframework.dao.DataAccessException
                    || current instanceof java.sql.SQLException
                    || className.contains("PersistenceException")
                    || className.contains("HibernateException")
                    || className.contains("TransactionException")
                    || className.contains("org.postgresql")
                    || className.contains("DuplicateKeyException")) {
                return "database_access";
            }

            if (current instanceof EmailDeliveryException
                    || current instanceof org.springframework.mail.MailException
                    || className.contains("MessagingException")
                    || className.contains("MailConnectException")
                    || className.contains("SocketTimeoutException")
                    || className.contains("ResourceAccessException")
                    || message.contains("smtp")
                    || message.contains("brevo")
                    || message.contains("mail")) {
                return "email_delivery";
            }

            if (current instanceof org.springframework.http.converter.HttpMessageConversionException
                    || className.contains("JsonProcessingException")
                    || className.contains("HttpMessageNotWritableException")) {
                return "response_construction";
            }

            if (message.contains("challenge")
                    || message.contains("otp")
                    || message.contains("lease")
                    || message.contains("attempt token")
                    || message.contains("idempotency")) {
                return "otp_reservation";
            }
        }
        return "general";
    }

    static String resolveOperation(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return "unknown";
        }
        if (uri.contains("/signup/send-otp")) {
            return "signup_send_otp";
        }
        if (uri.contains("/signup/verify-otp") || uri.contains("/verify-otp")) {
            return "signup_verify_otp";
        }
        if (uri.contains("/signup/status")) {
            return "signup_status";
        }
        if (uri.contains("/signup")) {
            return "signup_complete";
        }
        if (uri.contains("/login")) {
            return "login";
        }
        if (uri.contains("/forgot-password")) {
            return "forgot_password";
        }
        if (uri.contains("/reset-password")) {
            return "reset_password";
        }
        return "auth_general";
    }
}

