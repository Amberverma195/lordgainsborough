ALTER TABLE password_reset_email_queue
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_password_reset_queue_due
    ON password_reset_email_queue (next_attempt_at, created_at)
    WHERE status = 'PENDING' AND is_synthetic = FALSE;
CREATE INDEX idx_password_reset_queue_lease
    ON password_reset_email_queue (lease_until)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_password_reset_queue_cleanup
    ON password_reset_email_queue (updated_at)
    WHERE status IN ('SENT', 'FAILED', 'SKIPPED', 'EXPIRED');
