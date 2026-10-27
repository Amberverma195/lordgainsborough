package com.lordgainsborough.restaurant.model;

import java.time.Instant;

public class PasswordResetOtpChallenge {

    public enum Purpose { PASSWORD_RESET }

    private String emailKey;

    private String challengeId;

    private String userId;
    private Purpose purpose;
    private PasswordResetChallengeStatus status;
    private String codeDigest;
    private Instant codeExpiresAt;

    private Instant challengeExpiresAt;

    private Instant nextResendAt;
    private int resendCount;
    private int failedVerificationAttempts;
    private String clientIdempotencyKey;
    private String sendOperationId;
    private OtpSendStatus sendStatus;
    private Instant sendLeaseUntil;
    private String resetOperationId;
    private boolean passwordUpdated;
    private Instant consumedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public String getEmailKey() { return emailKey; }
    public void setEmailKey(String value) { this.emailKey = value; }
    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String value) { this.challengeId = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { this.userId = value; }
    public Purpose getPurpose() { return purpose; }
    public void setPurpose(Purpose value) { this.purpose = value; }
    public PasswordResetChallengeStatus getStatus() { return status; }
    public void setStatus(PasswordResetChallengeStatus value) { this.status = value; }
    public String getCodeDigest() { return codeDigest; }
    public void setCodeDigest(String value) { this.codeDigest = value; }
    public Instant getCodeExpiresAt() { return codeExpiresAt; }
    public void setCodeExpiresAt(Instant value) { this.codeExpiresAt = value; }
    public Instant getChallengeExpiresAt() { return challengeExpiresAt; }
    public void setChallengeExpiresAt(Instant value) { this.challengeExpiresAt = value; }
    public Instant getNextResendAt() { return nextResendAt; }
    public void setNextResendAt(Instant value) { this.nextResendAt = value; }
    public int getResendCount() { return resendCount; }
    public void setResendCount(int value) { this.resendCount = value; }
    public int getFailedVerificationAttempts() { return failedVerificationAttempts; }
    public void setFailedVerificationAttempts(int value) { this.failedVerificationAttempts = value; }
    public String getClientIdempotencyKey() { return clientIdempotencyKey; }
    public void setClientIdempotencyKey(String value) { this.clientIdempotencyKey = value; }
    public String getSendOperationId() { return sendOperationId; }
    public void setSendOperationId(String value) { this.sendOperationId = value; }
    public OtpSendStatus getSendStatus() { return sendStatus; }
    public void setSendStatus(OtpSendStatus value) { this.sendStatus = value; }
    public Instant getSendLeaseUntil() { return sendLeaseUntil; }
    public void setSendLeaseUntil(Instant value) { this.sendLeaseUntil = value; }
    public String getResetOperationId() { return resetOperationId; }
    public void setResetOperationId(String value) { this.resetOperationId = value; }
    public boolean isPasswordUpdated() { return passwordUpdated; }
    public void setPasswordUpdated(boolean value) { this.passwordUpdated = value; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant value) { this.consumedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { this.createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }

    @Override
    public String toString() {
        return "PasswordResetOtpChallenge{challengeId='" + challengeId
                + "', purpose=" + purpose
                + ", status=" + status
                + ", resendCount=" + resendCount
                + ", failedVerificationAttempts=" + failedVerificationAttempts
                + ", sendStatus=" + sendStatus + "}";
    }
}
