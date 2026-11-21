-- -----------------------------------------------------------------------------
-- V9: Encrypt Password Reset Email Queue Payload
-- Replaces plaintext raw_code with encrypted_code to prevent plaintext OTP
-- exposure in PostgreSQL database tables, transaction logs, and backups.
-- -----------------------------------------------------------------------------
ALTER TABLE password_reset_email_queue
    ADD COLUMN encrypted_code VARCHAR(255);

ALTER TABLE password_reset_email_queue
    DROP COLUMN raw_code;
