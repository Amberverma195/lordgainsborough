package com.lordgainsborough.restaurant.persistence.jpa.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "password_reset_otp_challenges")
public class JpaPasswordResetOtpChallenge {

    @Id
    @Column(name = "email_key", length = 64, nullable = false)
    private String emailKey;

    @Column(name = "challenge_id", length = 64, nullable = false, unique = true)
    private String challengeId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private JpaUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 50, nullable = false)
    private PasswordResetOtpChallenge.Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private PasswordResetChallengeStatus status;

    /**
     * BCrypt hash of the reset code. Nullable once consumed. Never serialize or log.
     */
    @JsonIgnore
    @Column(name = "code_digest", length = 255)
    private String codeDigest;

    @Column(name = "code_expires_at")
    private Instant codeExpiresAt;

    @Column(name = "challenge_expires_at", nullable = false)
    private Instant challengeExpiresAt;

    @Column(name = "next_resend_at")
    private Instant nextResendAt;

    @Column(name = "resend_count", nullable = false)
    private int resendCount = 0;

    @Column(name = "failed_verification_attempts", nullable = false)
    private int failedVerificationAttempts = 0;

    @JsonIgnore
    @Column(name = "client_idempotency_key", length = 128)
    private String clientIdempotencyKey;

    @JsonIgnore
    @Column(name = "send_operation_id", length = 64)
    private String sendOperationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "send_status", length = 50)
    private OtpSendStatus sendStatus;

    @Column(name = "send_lease_until")
    private Instant sendLeaseUntil;

    @JsonIgnore
    @Column(name = "reset_operation_id", length = 64)
    private String resetOperationId;

    @Column(name = "password_updated", nullable = false)
    private boolean passwordUpdated = false;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public JpaPasswordResetOtpChallenge() {}

    public JpaPasswordResetOtpChallenge(
            String emailKey,
            String challengeId,
            JpaUser user,
            PasswordResetOtpChallenge.Purpose purpose,
            PasswordResetChallengeStatus status,
            Instant challengeExpiresAt) {
        this.emailKey = emailKey;
        this.challengeId = challengeId;
        this.user = user;
        this.purpose = purpose;
        this.status = status;
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
        this.updatedAt = Instant.now();
    }

    public boolean isChallengeExpired(Instant now) {
        return challengeExpiresAt == null || !now.isBefore(challengeExpiresAt);
    }

    public boolean isChallengeExpired() {
        return isChallengeExpired(Instant.now());
    }

    public boolean isCodeExpired(Instant now) {
        return codeExpiresAt == null || !now.isBefore(codeExpiresAt);
    }

    public boolean isCodeExpired() {
        return isCodeExpired(Instant.now());
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

    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String challengeId) { this.challengeId = challengeId; }

    @JsonIgnore
    public JpaUser getUser() { return user; }
    public void setUser(JpaUser user) { this.user = user; }

    @JsonProperty("userId")
    public String getUserId() { return user != null ? user.getId() : null; }

    public PasswordResetOtpChallenge.Purpose getPurpose() { return purpose; }
    public void setPurpose(PasswordResetOtpChallenge.Purpose purpose) { this.purpose = purpose; }

    public PasswordResetChallengeStatus getStatus() { return status; }
    public void setStatus(PasswordResetChallengeStatus status) { this.status = status; }

    public String getCodeDigest() { return codeDigest; }
    public void setCodeDigest(String codeDigest) { this.codeDigest = codeDigest; }

    public Instant getCodeExpiresAt() { return codeExpiresAt; }
    public void setCodeExpiresAt(Instant codeExpiresAt) { this.codeExpiresAt = codeExpiresAt; }

    public Instant getChallengeExpiresAt() { return challengeExpiresAt; }
    public void setChallengeExpiresAt(Instant challengeExpiresAt) { this.challengeExpiresAt = challengeExpiresAt; }

    public Instant getNextResendAt() { return nextResendAt; }
    public void setNextResendAt(Instant nextResendAt) { this.nextResendAt = nextResendAt; }

    public int getResendCount() { return resendCount; }
    public void setResendCount(int resendCount) { this.resendCount = resendCount; }

    public int getFailedVerificationAttempts() { return failedVerificationAttempts; }
    public void setFailedVerificationAttempts(int failedVerificationAttempts) { this.failedVerificationAttempts = failedVerificationAttempts; }

    public String getClientIdempotencyKey() { return clientIdempotencyKey; }
    public void setClientIdempotencyKey(String clientIdempotencyKey) { this.clientIdempotencyKey = clientIdempotencyKey; }

    public String getSendOperationId() { return sendOperationId; }
    public void setSendOperationId(String sendOperationId) { this.sendOperationId = sendOperationId; }

    public OtpSendStatus getSendStatus() { return sendStatus; }
    public void setSendStatus(OtpSendStatus sendStatus) { this.sendStatus = sendStatus; }

    public Instant getSendLeaseUntil() { return sendLeaseUntil; }
    public void setSendLeaseUntil(Instant sendLeaseUntil) { this.sendLeaseUntil = sendLeaseUntil; }

    public String getResetOperationId() { return resetOperationId; }
    public void setResetOperationId(String resetOperationId) { this.resetOperationId = resetOperationId; }

    public boolean isPasswordUpdated() { return passwordUpdated; }
    public void setPasswordUpdated(boolean passwordUpdated) { this.passwordUpdated = passwordUpdated; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaPasswordResetOtpChallenge that)) return false;
        return this.emailKey != null && Objects.equals(this.emailKey, that.emailKey);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /**
     * Excludes codeDigest, resetOperationId, and user security fields to avoid
     * accidental credential disclosure in logs.
     */
    @Override
    public String toString() {
        return "JpaPasswordResetOtpChallenge{"
                + "challengeId='" + challengeId + '\''
                + ", userId='" + getUserId() + '\''
                + ", purpose=" + purpose
                + ", status=" + status
                + ", resendCount=" + resendCount
                + ", failedVerificationAttempts=" + failedVerificationAttempts
                + ", sendStatus=" + sendStatus
                + ", passwordUpdated=" + passwordUpdated
                + '}';
    }
}
