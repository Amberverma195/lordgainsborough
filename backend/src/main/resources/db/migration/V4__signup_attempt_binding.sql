-- -----------------------------------------------------------------------------
-- V4: Signup Attempt Binding
-- Binds signup OTP challenges to an unpredictable, server-validated signup
-- attempt secret. Only the SHA-256 verifier hash is stored in this table.
-- -----------------------------------------------------------------------------

ALTER TABLE signup_otp_challenges
    ADD COLUMN IF NOT EXISTS attempt_token_hash VARCHAR(255);
