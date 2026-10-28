-- -----------------------------------------------------------------------------
-- V3: Pending Signup Registration Details
-- Stages pending user registration details (name, phone, BCrypt password hash)
-- alongside the signup OTP challenge so password entry is required only once.
-- -----------------------------------------------------------------------------

ALTER TABLE signup_otp_challenges
    ADD COLUMN IF NOT EXISTS pending_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS pending_phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS pending_phone_normalized VARCHAR(10),
    ADD COLUMN IF NOT EXISTS pending_password_hash VARCHAR(255);
