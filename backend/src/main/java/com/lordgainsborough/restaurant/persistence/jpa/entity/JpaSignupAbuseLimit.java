package com.lordgainsborough.restaurant.persistence.jpa.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * PostgreSQL JPA entity representing a rate-limiting abuse counter in {@code signup_abuse_limits}.
 * <p>
 * Maps to the Flyway V1 schema. Primary keys and subject hashes are application-assigned
 * using keyed cryptographic digests; raw IP addresses are never persisted.
 * </p>
 */
@Entity
@Table(name = "signup_abuse_limits")
public class JpaSignupAbuseLimit {

    @Id
    @Column(name = "id", length = 128, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "bucket_type", length = 50, nullable = false)
    private SignupAbuseLimit.BucketType bucketType;

    /**
     * Keyed cryptographic digest of the client IP (or "global"). Never stores raw IPs.
     */
    @JsonIgnore
    @Column(name = "subject_hash", length = 128, nullable = false)
    private String subjectHash;

    @Column(name = "count", nullable = false)
    private long count = 1L;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public JpaSignupAbuseLimit() {}

    public JpaSignupAbuseLimit(
            String id,
            SignupAbuseLimit.BucketType bucketType,
            String subjectHash,
            long count,
            Instant windowStart,
            Instant expiresAt) {
        this.id = id;
        this.bucketType = bucketType;
        this.subjectHash = subjectHash;
        this.count = count;
        this.windowStart = windowStart;
        this.expiresAt = expiresAt;
    }

    /**
     * Evaluates expiration with fail-closed semantics: a missing or null expiry
     * is treated as expired. Boundary is exact: at {@code now.equals(expiresAt)},
     * the window is expired.
     */
    public boolean isExpired(Instant now) {
        return expiresAt == null || !now.isBefore(expiresAt);
    }

    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public SignupAbuseLimit.BucketType getBucketType() { return bucketType; }
    public void setBucketType(SignupAbuseLimit.BucketType bucketType) { this.bucketType = bucketType; }

    public String getSubjectHash() { return subjectHash; }
    public void setSubjectHash(String subjectHash) { this.subjectHash = subjectHash; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaSignupAbuseLimit that)) return false;
        return this.id != null && Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /**
     * Excludes id and subjectHash to prevent disclosure of internal keys
     * or keyed hash material in routine application logs.
     */
    @Override
    public String toString() {
        return "JpaSignupAbuseLimit{"
                + "bucketType=" + bucketType
                + ", count=" + count
                + ", windowStart=" + windowStart
                + ", expiresAt=" + expiresAt
                + '}';
    }
}
