package com.lordgainsborough.restaurant.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Encapsulates an outbound password-reset OTP email delivery job.
 */
public class PasswordResetDeliveryTask {

    private final String id;
    private final String emailKey;
    private final String challengeId;
    private final String recipientEmail;
    private final String rawCode;
    private final String sendOperationId;
    private final Duration validity;
    private final boolean synthetic;
    private final Instant createdAt;

    public PasswordResetDeliveryTask(
            String id,
            String emailKey,
            String challengeId,
            String recipientEmail,
            String rawCode,
            String sendOperationId,
            Duration validity,
            boolean synthetic,
            Instant createdAt) {
        this.id = id;
        this.emailKey = emailKey;
        this.challengeId = challengeId;
        this.recipientEmail = recipientEmail;
        this.rawCode = rawCode;
        this.sendOperationId = sendOperationId;
        this.validity = validity;
        this.synthetic = synthetic;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getEmailKey() {
        return emailKey;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getRawCode() {
        return rawCode;
    }

    public String getSendOperationId() {
        return sendOperationId;
    }

    public Duration getValidity() {
        return validity;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
