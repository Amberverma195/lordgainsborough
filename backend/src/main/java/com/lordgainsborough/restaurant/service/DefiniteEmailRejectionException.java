package com.lordgainsborough.restaurant.service;

/**
 * Provider rejected the send in a way that is safe to treat as not delivered
 * (for example a 4xx validation error).
 */
public class DefiniteEmailRejectionException extends EmailDeliveryException {
    public DefiniteEmailRejectionException(String message) {
        super(message);
    }

    public DefiniteEmailRejectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
