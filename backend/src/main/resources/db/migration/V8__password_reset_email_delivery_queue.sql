-- -----------------------------------------------------------------------------
-- V8: Password Reset Email Delivery Queue
-- Decouples email transmission (Resend REST API) from the public HTTP response
-- thread to prevent account enumeration via timing side-channel analysis.
-- Persists outbound reset emails durably across restarts and multi-node clusters.
-- -----------------------------------------------------------------------------
CREATE TABLE password_reset_email_queue (
    id                   VARCHAR(64) PRIMARY KEY,
    email_key            VARCHAR(64) NOT NULL,
    challenge_id         VARCHAR(64) NOT NULL,
    recipient_email      VARCHAR(255) NOT NULL,
    raw_code             VARCHAR(32),
    send_operation_id    VARCHAR(64) NOT NULL,
    validity_seconds     BIGINT NOT NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts             INT NOT NULL DEFAULT 0,
    lease_until          TIMESTAMP WITH TIME ZONE,
    last_error           VARCHAR(500),
    is_synthetic         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pwd_reset_queue_challenge FOREIGN KEY (email_key)
        REFERENCES password_reset_otp_challenges (email_key) ON DELETE CASCADE
);

CREATE INDEX idx_pwd_reset_email_queue_poll
    ON password_reset_email_queue (status, lease_until, created_at);
