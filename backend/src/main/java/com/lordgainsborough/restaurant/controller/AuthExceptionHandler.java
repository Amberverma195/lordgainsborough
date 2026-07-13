package com.lordgainsborough.restaurant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.lordgainsborough.restaurant.service.SignupOtpObservability;

import java.util.Map;

/**
 * Ensures auth clients never receive stack traces, Mongo errors, provider details,
 * or internal class names.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "message", "Unable to process this request. Please check your details and try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        // Exception messages/stack traces may retain request or provider details. Log only safe metadata.
        logger.atError()
                .addKeyValue(
                        "correlationId",
                        SignupOtpObservability.currentCorrelationId())
                .addKeyValue("exceptionType", ex.getClass().getSimpleName())
                .log("Unexpected authentication controller error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "message", "Something went wrong. Please try again in a moment."));
    }
}
