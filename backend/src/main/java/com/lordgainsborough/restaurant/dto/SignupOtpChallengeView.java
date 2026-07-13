package com.lordgainsborough.restaurant.dto;

import java.time.Instant;

/**
 * Challenge timing snapshot attached to signup OTP service results.
 */
public record SignupOtpChallengeView(
        String challengeId,
        Instant serverTime,
        Instant nextResendAt,
        Instant otpExpiresAt,
        Instant challengeExpiresAt,
        int resendsRemaining) {
}
