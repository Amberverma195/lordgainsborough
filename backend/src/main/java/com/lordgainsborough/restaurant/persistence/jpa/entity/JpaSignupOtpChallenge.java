package com.lordgainsborough.restaurant.persistence.jpa.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "signup_otp_challenges")
public class JpaSignupOtpChallenge {

    @Id
    @Column(name = "email_key", length = 64, nullable = false)
    private String emailKey;

    @JsonIgnore
    @Column(name = "normalized_email", length = 255, nullable = false)
    private String normalizedEmail;

    @Column(name = "challenge_id", length = 64, nullable = false)
    private String challengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private OtpChallengeStatus status;

    /**
     * BCrypt or Argon2 digest of the OTP code. Never log or serialize externally.
     */
    @JsonIgnore
    @Column(name = "otp_digest", length = 255, nullable = false)
    private String otpDigest;

    @Column(name = "resend_count", nullable = false)
    private int resendCount = 0;

    @Column(name = "failed_verify_attempts", nullable = false)
    private int failedVerifyAttempts = 0;

    @Column(name = "next_resend_at")
    private Instant nextResendAt;

    @Column(name = "otp_expires_at")
    private Instant otpExpiresAt;

    @Column(name = "challenge_expires_at", nullable = false)
    private Instant challengeExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @JsonIgnore
    @Column(name = "send_operation_id", length = 64)
    private String sendOperationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "send_status", length = 50)
    private OtpSendStatus sendStatus;

    @Column(name = "send_lease_until")
    private Instant sendLeaseUntil;

    @JsonIgnore
    @Column(name = "client_idempotency_key", length = 128)
    private String clientIdempotencyKey;

    @Column(name = "last_send_was_initial", nullable = false)
    private boolean lastSendWasInitial = true;

    public JpaSignupOtpChallenge() {}

    public JpaSignupOtpChallenge(
            String emailKey,
            String normalizedEmail,
            String challengeId,
            OtpChallengeStatus status,
            String otpDigest,
            Instant challengeExpiresAt) {
        this.emailKey = emailKey;
        this.normalizedEmail = normalizedEmail;
        this.challengeId = challengeId;
        this.status = status;
        this.otpDigest = otpDigest;
        this.challengeExpiresAt = challengeExpiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }

    public boolean isChallengeExpired(Instant now) {
        return challengeExpiresAt == null || !now.isBefore(challengeExpiresAt);
    }

    public boolean isChallengeExpired() {
        return isChallengeExpired(Instant.now());
    }

    public boolean isOtpExpired(Instant now) {
        return otpExpiresAt == null || !now.isBefore(otpExpiresAt);
    }

    public boolean isOtpExpired() {
        return isOtpExpired(Instant.now());
    }

    public boolean isSendLeaseActive(Instant now) {
        return sendLeaseUntil != null && now.isBefore(sendLeaseUntil);
    }

    public boolean isSendLeaseActive() {
        return isSendLeaseActive(Instant.now());
    }

    public boolean isResendAllowed(Instant now) {
        return nextResendAt == null || !now.isBefore(nextResendAt);
    }

    public boolean isResendAllowed() {
        return isResendAllowed(Instant.now());
    }

    public String getEmailKey() { return emailKey; }
    public void setEmailKey(String emailKey) { this.emailKey = emailKey; }

    public String getNormalizedEmail() { return normalizedEmail; }
    public void setNormalizedEmail(String normalizedEmail) { this.normalizedEmail = normalizedEmail; }

    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String challengeId) { this.challengeId = challengeId; }

    public OtpChallengeStatus getStatus() { return status; }
    public void setStatus(OtpChallengeStatus status) { this.status = status; }

    public String getOtpDigest() { return otpDigest; }
    public void setOtpDigest(String otpDigest) { this.otpDigest = otpDigest; }

    public int getResendCount() { return resendCount; }
    public void setResendCount(int resendCount) { this.resendCount = resendCount; }

    public int getFailedVerifyAttempts() { return failedVerifyAttempts; }
    public void setFailedVerifyAttempts(int failedVerifyAttempts) { this.failedVerifyAttempts = failedVerifyAttempts; }

    public Instant getNextResendAt() { return nextResendAt; }
    public void setNextResendAt(Instant nextResendAt) { this.nextResendAt = nextResendAt; }

    public Instant getOtpExpiresAt() { return otpExpiresAt; }
    public void setOtpExpiresAt(Instant otpExpiresAt) { this.otpExpiresAt = otpExpiresAt; }

    public Instant getChallengeExpiresAt() { return challengeExpiresAt; }
    public void setChallengeExpiresAt(Instant challengeExpiresAt) { this.challengeExpiresAt = challengeExpiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getSendOperationId() { return sendOperationId; }
    public void setSendOperationId(String sendOperationId) { this.sendOperationId = sendOperationId; }

    public OtpSendStatus getSendStatus() { return sendStatus; }
    public void setSendStatus(OtpSendStatus sendStatus) { this.sendStatus = sendStatus; }

    public Instant getSendLeaseUntil() { return sendLeaseUntil; }
    public void setSendLeaseUntil(Instant sendLeaseUntil) { this.sendLeaseUntil = sendLeaseUntil; }

    public String getClientIdempotencyKey() { return clientIdempotencyKey; }
    public void setClientIdempotencyKey(String clientIdempotencyKey) { this.clientIdempotencyKey = clientIdempotencyKey; }

    public boolean isLastSendWasInitial() { return lastSendWasInitial; }
    public void setLastSendWasInitial(boolean lastSendWasInitial) { this.lastSendWasInitial = lastSendWasInitial; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaSignupOtpChallenge that)) return false;
        return this.emailKey != null && Objects.equals(this.emailKey, that.emailKey);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /**
     * Excludes normalizedEmail, otpDigest, clientIdempotencyKey, emailKey, challengeId,
     * and sendOperationId to prevent accidental disclosure of security metadata and identifiers in logs.
     */
    @Override
    public String toString() {
        return "JpaSignupOtpChallenge{"
                + "status=" + status
                + ", resendCount=" + resendCount
                + ", failedVerifyAttempts=" + failedVerifyAttempts
                + ", sendStatus=" + sendStatus
                + ", lastSendWasInitial=" + lastSendWasInitial
                + '}';
    }
}
