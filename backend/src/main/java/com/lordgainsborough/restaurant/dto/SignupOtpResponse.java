package com.lordgainsborough.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Public signup OTP API envelope. Timing fields are ISO-8601 instants.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SignupOtpResponse {

    private SignupOtpCode code;
    private String message;
    private String challengeId;
    private Instant serverTime;
    private Instant nextResendAt;
    private Instant otpExpiresAt;
    private Instant challengeExpiresAt;
    private Integer resendsRemaining;
    private Boolean captchaRequired;

    public SignupOtpCode getCode() {
        return code;
    }

    public void setCode(SignupOtpCode code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }

    public Instant getServerTime() {
        return serverTime;
    }

    public void setServerTime(Instant serverTime) {
        this.serverTime = serverTime;
    }

    public Instant getNextResendAt() {
        return nextResendAt;
    }

    public void setNextResendAt(Instant nextResendAt) {
        this.nextResendAt = nextResendAt;
    }

    public Instant getOtpExpiresAt() {
        return otpExpiresAt;
    }

    public void setOtpExpiresAt(Instant otpExpiresAt) {
        this.otpExpiresAt = otpExpiresAt;
    }

    public Instant getChallengeExpiresAt() {
        return challengeExpiresAt;
    }

    public void setChallengeExpiresAt(Instant challengeExpiresAt) {
        this.challengeExpiresAt = challengeExpiresAt;
    }

    public Integer getResendsRemaining() {
        return resendsRemaining;
    }

    public void setResendsRemaining(Integer resendsRemaining) {
        this.resendsRemaining = resendsRemaining;
    }

    public Boolean getCaptchaRequired() {
        return captchaRequired;
    }

    public void setCaptchaRequired(Boolean captchaRequired) {
        this.captchaRequired = captchaRequired;
    }

    public static SignupOtpResponse of(SignupOtpCode code, String message) {
        SignupOtpResponse response = new SignupOtpResponse();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    public static SignupOtpResponse rateLimited(String message, boolean captchaRequired) {
        SignupOtpResponse response = of(
                captchaRequired ? SignupOtpCode.OTP_CAPTCHA_REQUIRED : SignupOtpCode.OTP_RATE_LIMITED,
                message);
        if (captchaRequired) {
            response.setCaptchaRequired(true);
        }
        return response;
    }
}
