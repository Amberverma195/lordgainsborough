package com.lordgainsborough.restaurant.service;

/**
 * Base type for classified email-provider outcomes used by signup OTP send/resend.
 */
public abstract class EmailDeliveryException extends RuntimeException {
    protected EmailDeliveryException(String message) {
        super(message);
    }

    protected EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
