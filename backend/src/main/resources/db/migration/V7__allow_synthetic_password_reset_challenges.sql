-- -----------------------------------------------------------------------------
-- V7: Allow Synthetic Password Reset Challenges
-- Supports shared, persistent synthetic challenges for nonexistent accounts to
-- guarantee enumeration prevention across restarts and multi-server deployments.
-- -----------------------------------------------------------------------------
ALTER TABLE password_reset_otp_challenges
    ALTER COLUMN user_id DROP NOT NULL;
