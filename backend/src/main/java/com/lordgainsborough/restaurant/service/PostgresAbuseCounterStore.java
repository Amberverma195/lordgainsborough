package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * PG-013: PostgreSQL implementation of {@link AbuseCounterStore}.
 * <p>
 * Provides concurrency-safe, atomic fixed-window rate-limiting counters
 * using PostgreSQL's {@code INSERT INTO signup_abuse_limits ... ON CONFLICT (id) DO UPDATE ... RETURNING count}.
 * </p>
 * <p>
 * <b>Operational Invariants:</b>
 * <ul>
 *   <li>Single atomic statement: row is either inserted (count=1) or atomically incremented (count+1).</li>
 *   <li>Metadata preservation: {@code window_start}, {@code expires_at}, {@code bucket_type}, and {@code subject_hash}
 *       are never overwritten on conflict during ordinary increments.</li>
 *   <li>Security consistency: Primary key collision with contradictory metadata is rejected with
 *       an {@link IllegalArgumentException} without mutating the counter or metadata.</li>
 *   <li>Counter overflow: BIGINT overflow fails closed via {@link ArithmeticException} rather than wrapping around.</li>
 *   <li>Sensitive data protection: raw client IPs and internal keyed hashes are never logged.</li>
 *   <li>Production safety: {@link #clear()} fails closed with {@link UnsupportedOperationException}
 *       unless explicitly configured for disposable test execution.</li>
 * </ul>
 * </p>
 */
public class PostgresAbuseCounterStore implements AbuseCounterStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresAbuseCounterStore.class);

    private static final String SQL_INCREMENT = """
            INSERT INTO signup_abuse_limits (id, bucket_type, subject_hash, count, window_start, expires_at)
            VALUES (?, ?, ?, 1, ?, ?)
            ON CONFLICT (id) DO UPDATE
            SET count = CASE
                WHEN signup_abuse_limits.count >= 9223372036854775807 THEN
                    signup_abuse_limits.count + 1
                ELSE signup_abuse_limits.count + 1
            END
            WHERE signup_abuse_limits.bucket_type = EXCLUDED.bucket_type
              AND signup_abuse_limits.subject_hash = EXCLUDED.subject_hash
              AND signup_abuse_limits.window_start = EXCLUDED.window_start
              AND signup_abuse_limits.expires_at = EXCLUDED.expires_at
            RETURNING count
            """;

    private static final String SQL_GET_COUNT = """
            SELECT count, expires_at FROM signup_abuse_limits WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final boolean allowClear;

    public PostgresAbuseCounterStore(JdbcTemplate jdbcTemplate, boolean allowClear) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.allowClear = allowClear;
    }

    public PostgresAbuseCounterStore(DataSource dataSource, boolean allowClear) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")), allowClear);
    }

    public PostgresAbuseCounterStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Boolean.getBoolean("app.postgres.abuse-store.allow-clear"));
    }

    public PostgresAbuseCounterStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")),
                Boolean.getBoolean("app.postgres.abuse-store.allow-clear"));
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public long incrementAndGet(
            String id,
            BucketType type,
            String subjectHash,
            Instant windowStart,
            Instant expiresAt) {
        Objects.requireNonNull(id, "Counter ID must not be null");
        Objects.requireNonNull(type, "BucketType must not be null");
        Objects.requireNonNull(subjectHash, "Subject hash must not be null");
        Objects.requireNonNull(windowStart, "Window start must not be null");
        Objects.requireNonNull(expiresAt, "Expires at must not be null");

        List<Long> results;
        try {
            results = jdbcTemplate.query(
                    SQL_INCREMENT,
                    (rs, rowNum) -> rs.getLong("count"),
                    id,
                    type.name(),
                    subjectHash,
                    Timestamp.from(windowStart),
                    Timestamp.from(expiresAt)
            );
        } catch (DataAccessException e) {
            if (isOverflowException(e)) {
                log.error("Abuse counter overflow detected during atomic increment");
                throw new ArithmeticException("Abuse counter overflow");
            }
            throw e;
        }

        if (results == null || results.isEmpty()) {
            log.warn("Conflicting metadata rejected on existing abuse counter");
            throw new IllegalArgumentException(
                    "Conflicting metadata for existing abuse limit record"
            );
        }

        return results.get(0);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public long getCount(String id, Instant now) {
        Objects.requireNonNull(id, "Counter ID must not be null");
        Objects.requireNonNull(now, "Instant now must not be null");

        List<Long> results = jdbcTemplate.query(
                SQL_GET_COUNT,
                (rs, rowNum) -> {
                    long count = rs.getLong("count");
                    Timestamp expiresAtTs = rs.getTimestamp("expires_at");
                    if (expiresAtTs == null) {
                        return 0L;
                    }
                    Instant expiresAt = expiresAtTs.toInstant();
                    if (!expiresAt.isAfter(now)) {
                        return 0L;
                    }
                    return count;
                },
                id
        );

        return (results == null || results.isEmpty()) ? 0L : results.get(0);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void clear() {
        if (!allowClear) {
            throw new UnsupportedOperationException(
                    "Global clearing of PostgreSQL abuse counters is disabled by default to prevent catastrophic rate-limit wipe. "
                            + "Clearing is only authorized in explicitly configured disposable test environments."
            );
        }
        jdbcTemplate.update("DELETE FROM signup_abuse_limits");
        log.info("Cleared all records from signup_abuse_limits (authorized test environment).");
    }

    public boolean isAllowClear() {
        return allowClear;
    }

    private boolean isOverflowException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && (msg.contains("bigint out of range") || msg.contains("out of range"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
