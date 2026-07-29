package com.lordgainsborough.restaurant.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "login_failure_states")
public class LoginFailureState {

    @Id
    private String id;
    private String subjectHash;
    private int failureCount;
    private Instant windowStartedAt;
    private Instant blockedUntil;
    private Instant updatedAt;

    @Indexed(name = "login_failure_state_ttl", expireAfterSeconds = 0)
    private Instant expiresAt;

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
}
