package com.lordgainsborough.restaurant.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/** Shared, durable denylist. Only SHA-256 token digests are persisted. */
@Component
public class SessionRevocationStore {
    private final JdbcTemplate jdbc;
    public SessionRevocationStore(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }
    private String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public boolean isRevoked(String token) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM revoked_account_sessions WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP)", Boolean.class, digest(token)));
    }
    public void revoke(String token, Instant expiresAt) {
        jdbc.update("INSERT INTO revoked_account_sessions (token_hash, expires_at) VALUES (?, ?) ON CONFLICT (token_hash) DO NOTHING", digest(token), Timestamp.from(expiresAt));
    }
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void cleanupExpired() {
        jdbc.update("DELETE FROM revoked_account_sessions WHERE token_hash IN (SELECT token_hash FROM revoked_account_sessions WHERE expires_at <= CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 5000)");
    }
}
