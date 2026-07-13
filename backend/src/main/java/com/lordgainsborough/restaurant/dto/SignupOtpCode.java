package com.lordgainsborough.restaurant.dto;

/**
 * Stable public codes for signup OTP send/verify responses.
 */
public enum SignupOtpCode {
    OTP_SENT,
    OTP_ALREADY_ACTIVE,
    OTP_SEND_IN_PROGRESS,
    OTP_DELIVERY_UNKNOWN,
    OTP_RESEND_COOLDOWN,
    OTP_RESEND_LIMIT_REACHED,
    OTP_CHALLENGE_EXPIRED,
    OTP_INVALID,
    OTP_EXPIRED,
    OTP_VERIFICATION_LOCKED,
    OTP_VERIFIED,
    /** Generic secondary rate limit (IP / global). Do not disclose which limit tripped. */
    OTP_RATE_LIMITED,
    /** CAPTCHA required after suspicious send volume from this client. */
    OTP_CAPTCHA_REQUIRED
}
