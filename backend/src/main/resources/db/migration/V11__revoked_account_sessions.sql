CREATE TABLE revoked_account_sessions (
    token_hash CHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_revoked_account_sessions_expiry ON revoked_account_sessions (expires_at);
