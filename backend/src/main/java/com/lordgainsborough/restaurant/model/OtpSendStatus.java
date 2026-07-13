package com.lordgainsborough.restaurant.model;

/**
 * Lifecycle of a single signup OTP email dispatch attempt.
 */
public enum OtpSendStatus {
    /** Challenge mutated and lease claimed; provider call not yet confirmed. */
    RESERVED,
    /** Provider accepted the message for delivery. */
    SENT,
    /** Provider rejected the request; safe to treat as not delivered. */
    DEFINITELY_FAILED,
    /** Timeout or other ambiguous outcome; message may or may not have been accepted. */
    DELIVERY_UNKNOWN
}
