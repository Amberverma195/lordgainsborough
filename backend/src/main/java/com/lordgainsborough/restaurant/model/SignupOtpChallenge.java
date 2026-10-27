package com.lordgainsborough.restaurant.model;

import java.time.Instant;

public class SignupOtpChallenge {

    private String emailKey;

    private String normalizedEmail;
    private String challengeId;
    private OtpChallengeStatus status;

    /** BCrypt digest of the OTP — never store or log the raw code. */
    private String otpDigest;

    private int resendCount;
    private int failedVerifyAttempts;

    private Instant nextResendAt;
    private Instant otpExpiresAt;

    private Instant challengeExpiresAt;

    private Instant createdAt;
    private Instant updatedAt;

    /** Server-generated id for one email dispatch attempt (also passed to providers when supported). */
    private String sendOperationId;

    private OtpSendStatus sendStatus;

    /** Lease bound to {@link #sendOperationId} while the provider call is in flight. */
    private Instant sendLeaseUntil;

    /**
     * Client {@code Idempotency-Key} for the send/resend HTTP request.
     * Replays must not rotate the OTP or call the provider again.
     */
    private String clientIdempotencyKey;

    /** Whether the last reserved/completed send was the initial challenge create. */
    private boolean lastSendWasInitial;

    public String getEmailKey() {
        return emailKey;
    }

    public void setEmailKey(String emailKey) {
        this.emailKey = emailKey;
    }

    public String getNormalizedEmail() {
        return normalizedEmail;
    }

    public void setNormalizedEmail(String normalizedEmail) {
        this.normalizedEmail = normalizedEmail;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }

    public OtpChallengeStatus getStatus() {
        return status;
    }

    public void setStatus(OtpChallengeStatus status) {
        this.status = status;
    }

    public String getOtpDigest() {
        return otpDigest;
    }

    public void setOtpDigest(String otpDigest) {
        this.otpDigest = otpDigest;
    }

    public int getResendCount() {
        return resendCount;
    }

    public void setResendCount(int resendCount) {
        this.resendCount = resendCount;
    }

    public int getFailedVerifyAttempts() {
        return failedVerifyAttempts;
    }

    public void setFailedVerifyAttempts(int failedVerifyAttempts) {
        this.failedVerifyAttempts = failedVerifyAttempts;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSendOperationId() {
        return sendOperationId;
    }

    public void setSendOperationId(String sendOperationId) {
        this.sendOperationId = sendOperationId;
    }

    public OtpSendStatus getSendStatus() {
        return sendStatus;
    }

    public void setSendStatus(OtpSendStatus sendStatus) {
        this.sendStatus = sendStatus;
    }

    public Instant getSendLeaseUntil() {
        return sendLeaseUntil;
    }

    public void setSendLeaseUntil(Instant sendLeaseUntil) {
        this.sendLeaseUntil = sendLeaseUntil;
    }

    public String getClientIdempotencyKey() {
        return clientIdempotencyKey;
    }

    public void setClientIdempotencyKey(String clientIdempotencyKey) {
        this.clientIdempotencyKey = clientIdempotencyKey;
    }

    public boolean isLastSendWasInitial() {
        return lastSendWasInitial;
    }

    public void setLastSendWasInitial(boolean lastSendWasInitial) {
        this.lastSendWasInitial = lastSendWasInitial;
    }

    /**
     * Deliberately excludes normalizedEmail and otpDigest so accidental entity logging
     * cannot disclose contact data or OTP verification material.
     */
    @Override
    public String toString() {
        return "SignupOtpChallenge{"
                + "emailKey='" + emailKey + '\''
                + ", challengeId='" + challengeId + '\''
                + ", status=" + status
                + ", resendCount=" + resendCount
                + ", failedVerifyAttempts=" + failedVerifyAttempts
                + ", sendOperationId='" + sendOperationId + '\''
                + ", sendStatus=" + sendStatus
                + '}';
    }
}
