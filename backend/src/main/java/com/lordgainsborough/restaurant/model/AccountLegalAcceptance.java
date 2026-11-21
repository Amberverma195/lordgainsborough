package com.lordgainsborough.restaurant.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Append-only record of a customer's legal acceptance of the Terms of Service
 * and Privacy Policy. Contains no raw credentials, OTPs, or session secrets.
 */
public class AccountLegalAcceptance {

    private String id;
    private String userId;
    private String termsVersion;
    private String privacyVersion;
    private Instant acceptedAt;
    private String acceptanceSource;
    private Instant createdAt;

    public AccountLegalAcceptance() {}

    public AccountLegalAcceptance(
            String id,
            String userId,
            String termsVersion,
            String privacyVersion,
            Instant acceptedAt,
            String acceptanceSource,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
        this.acceptedAt = acceptedAt;
        this.acceptanceSource = acceptanceSource != null ? acceptanceSource : "signup";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public String getPrivacyVersion() { return privacyVersion; }
    public void setPrivacyVersion(String privacyVersion) { this.privacyVersion = privacyVersion; }

    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }

    public String getAcceptanceSource() { return acceptanceSource; }
    public void setAcceptanceSource(String acceptanceSource) { this.acceptanceSource = acceptanceSource; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountLegalAcceptance that)) return false;
        return Objects.equals(id, that.id)
                && Objects.equals(userId, that.userId)
                && Objects.equals(termsVersion, that.termsVersion)
                && Objects.equals(privacyVersion, that.privacyVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, termsVersion, privacyVersion);
    }
}
