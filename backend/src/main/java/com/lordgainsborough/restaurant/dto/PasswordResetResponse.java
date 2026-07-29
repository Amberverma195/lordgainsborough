package com.lordgainsborough.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PasswordResetResponse {

    private String code;
    private String message;
    private String challengeId;
    private Instant serverTime;
    private Instant nextResendAt;
    private Instant codeExpiresAt;
    private Instant challengeExpiresAt;
    private Integer resendsRemaining;
    private Long retryAfterSeconds;

    public String getCode() { return code; }
    public void setCode(String value) { this.code = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { this.message = value; }
    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String value) { this.challengeId = value; }
    public Instant getServerTime() { return serverTime; }
    public void setServerTime(Instant value) { this.serverTime = value; }
    public Instant getNextResendAt() { return nextResendAt; }
    public void setNextResendAt(Instant value) { this.nextResendAt = value; }
    public Instant getCodeExpiresAt() { return codeExpiresAt; }
    public void setCodeExpiresAt(Instant value) { this.codeExpiresAt = value; }
    public Instant getChallengeExpiresAt() { return challengeExpiresAt; }
    public void setChallengeExpiresAt(Instant value) { this.challengeExpiresAt = value; }
    public Integer getResendsRemaining() { return resendsRemaining; }
    public void setResendsRemaining(Integer value) { this.resendsRemaining = value; }
    public Long getRetryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(Long value) { this.retryAfterSeconds = value; }

    public static PasswordResetResponse of(String code, String message, Instant serverTime) {
        PasswordResetResponse response = new PasswordResetResponse();
        response.setCode(code);
        response.setMessage(message);
        response.setServerTime(serverTime);
        return response;
    }
}
