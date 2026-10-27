package com.lordgainsborough.restaurant.model;

import java.time.Instant;

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

    private String id;

    private BucketType bucketType;

    /** Keyed hash of the IP (or "global") — never store raw IPs. */
    private String subjectHash;

    private long count;

    private Instant windowStart;

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
