-- -----------------------------------------------------------------------------
-- V6: Account Legal Acceptances & Signup Consent Staging
-- Records append-only legal acceptance events for Terms of Service and Privacy Policy.
-- Adds staging columns to signup_otp_challenges to bind consent to signup attempts.
-- -----------------------------------------------------------------------------

CREATE TABLE account_legal_acceptances (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    terms_version VARCHAR(50) NOT NULL,
    privacy_version VARCHAR(50) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acceptance_source VARCHAR(50) NOT NULL DEFAULT 'signup',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_legal_acceptances_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_legal_acceptances_user_versions UNIQUE (user_id, terms_version, privacy_version, acceptance_source)
);

CREATE INDEX idx_legal_acceptances_user_id ON account_legal_acceptances (user_id);
CREATE INDEX idx_legal_acceptances_accepted_at ON account_legal_acceptances (accepted_at);

ALTER TABLE signup_otp_challenges
    ADD COLUMN IF NOT EXISTS pending_terms_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS pending_privacy_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS pending_legal_accepted_at TIMESTAMP WITH TIME ZONE;
