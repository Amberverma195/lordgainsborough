package com.lordgainsborough.restaurant.dto;

public class PasswordResetResendRequest {
    private String email;
    private String challengeId;

    public String getEmail() { return email; }
    public void setEmail(String value) { this.email = value; }
    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String value) { this.challengeId = value; }
}
