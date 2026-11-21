package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "account_legal_acceptances")
public class JpaAccountLegalAcceptance {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "terms_version", length = 50, nullable = false)
    private String termsVersion;

    @Column(name = "privacy_version", length = 50, nullable = false)
    private String privacyVersion;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "acceptance_source", length = 50, nullable = false)
    private String acceptanceSource = "signup";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public JpaAccountLegalAcceptance() {}

    public JpaAccountLegalAcceptance(
            String id,
            String userId,
            String termsVersion,
            String privacyVersion,
            Instant acceptedAt,
            String acceptanceSource) {
        this.id = id;
        this.userId = userId;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
        this.acceptedAt = acceptedAt;
        this.acceptanceSource = acceptanceSource != null ? acceptanceSource : "signup";
        this.createdAt = Instant.now();
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
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
        if (!(o instanceof JpaAccountLegalAcceptance that)) return false;
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
