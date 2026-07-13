package com.lordgainsborough.restaurant.service;

/**
 * Provider timed out or returned an ambiguous status — the message may already
 * have been accepted. Callers must not delete the OTP challenge or immediately
 * send another code for the same logical operation.
 */
public class AmbiguousEmailDeliveryException extends EmailDeliveryException {
    public AmbiguousEmailDeliveryException(String message) {
        super(message);
    }

    public AmbiguousEmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
