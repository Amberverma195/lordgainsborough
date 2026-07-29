package com.lordgainsborough.restaurant.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "signup_abuse_limits")
public class SignupAbuseLimit {

    public enum BucketType {
        INITIAL_SEND_IP,
        TOTAL_SEND_IP,
        VERIFY_IP,
        GLOBAL_PROVIDER_SEND,
        LOGIN_REQUEST_IP,
        RESET_NEW_CHALLENGE_EMAIL,
        RESET_EMAIL_OPERATION_IP,
        RESET_VERIFICATION_IP,
        RESET_GLOBAL_EMAIL_OPERATION,
        RESET_IDEMPOTENCY
    }

    @Id
    private String id;

    private BucketType bucketType;

    /** Keyed hash of the IP (or "global") — never store raw IPs. */
    private String subjectHash;

    private long count;

    private Instant windowStart;

    @Indexed(name = "signup_abuse_limit_ttl", expireAfterSeconds = 0)
    private Instant expiresAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public BucketType getBucketType() {
        return bucketType;
    }

    public void setBucketType(BucketType bucketType) {
        this.bucketType = bucketType;
    }

    public String getSubjectHash() {
        return subjectHash;
    }

    public void setSubjectHash(String subjectHash) {
        this.subjectHash = subjectHash;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
