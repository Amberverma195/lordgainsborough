package com.lordgainsborough.restaurant.persistence.jpa.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * PostgreSQL JPA entity representing failed login attempts and temporary lockout state
 * in {@code login_failure_states}.
 * <p>
 * Maps to the Flyway V1 schema. Primary keys and subject hashes are application-assigned
 * using keyed cryptographic digests; raw IP addresses and unhashed user identifiers
 * are never persisted.
 * </p>
 */
@Entity
@Table(name = "login_failure_states")
public class JpaLoginFailureState {

    @Id
    @Column(name = "id", length = 128, nullable = false)
    private String id;

    /**
     * Keyed cryptographic digest of the client IP and normalized login identifier.
     * Never stores raw IPs or plaintext credentials.
     */
    @JsonIgnore
    @Column(name = "subject_hash", length = 128, nullable = false)
    private String subjectHash;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 1;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public JpaLoginFailureState() {}

    public JpaLoginFailureState(
            String id,
            String subjectHash,
            int failureCount,
            Instant windowStartedAt,
            Instant blockedUntil,
            Instant updatedAt,
            Instant expiresAt) {
        this.id = id;
        this.subjectHash = subjectHash;
        this.failureCount = failureCount;
        this.windowStartedAt = windowStartedAt;
        this.blockedUntil = blockedUntil;
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        this.expiresAt = expiresAt;
    }

    /**
     * Evaluates expiration with fail-closed semantics: missing or null expiry
     * is treated as expired. Boundary is exact: at {@code now.equals(expiresAt)},
     * the record is expired.
     */
    public boolean isExpired(Instant now) {
        return expiresAt == null || !now.isBefore(expiresAt);
    }

    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    /**
     * Evaluates whether a lockout block is currently active:
     * <ul>
     *   <li>If the record is expired, returns {@code false} (stale data must not trigger a lockout).</li>
     *   <li>If {@code blockedUntil} is null, returns {@code false}.</li>
     *   <li>If {@code now} is before {@code blockedUntil}, returns {@code true}.</li>
     *   <li>Exactly at {@code now.equals(blockedUntil)}, returns {@code false} (cooldown expired).</li>
     * </ul>
     */
    public boolean isBlocked(Instant now) {
        if (isExpired(now)) {
            return false;
        }
        return blockedUntil != null && now.isBefore(blockedUntil);
    }

    public boolean isBlocked() {
        return isBlocked(Instant.now());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubjectHash() { return subjectHash; }
    public void setSubjectHash(String subjectHash) { this.subjectHash = subjectHash; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public Instant getWindowStartedAt() { return windowStartedAt; }
    public void setWindowStartedAt(Instant windowStartedAt) { this.windowStartedAt = windowStartedAt; }

    public Instant getBlockedUntil() { return blockedUntil; }
    public void setBlockedUntil(Instant blockedUntil) { this.blockedUntil = blockedUntil; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaLoginFailureState that)) return false;
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
        return "JpaLoginFailureState{"
                + "failureCount=" + failureCount
                + ", windowStartedAt=" + windowStartedAt
                + ", blockedUntil=" + blockedUntil
                + ", updatedAt=" + updatedAt
                + ", expiresAt=" + expiresAt
                + '}';
    }
}
