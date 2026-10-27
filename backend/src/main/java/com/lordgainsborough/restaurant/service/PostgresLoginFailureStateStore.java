package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.LoginRateLimitProperties;
import com.lordgainsborough.restaurant.service.LoginRateLimiter.FailureResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * PG-014: Concurrency-safe PostgreSQL implementation of {@link LoginFailureStateStore}
 * using transactions, atomic upserts, and row-level locking ({@code SELECT ... FOR UPDATE}).
 * <p>
 * Manages records in {@code login_failure_states} against the Flyway V1 relational schema.
 * All operations execute within Spring-managed relational transactions specified by
 * {@code @Transactional(transactionManager = "transactionManager")}.
 * </p>
 * <p>
 * <b>Concurrent Ordering Policy (Successful vs Failed Authentication):</b>
 * <ul>
 *   <li>{@link #clearFailures(String)} deletes any existing failure state upon successful login.</li>
 *   <li>{@link #recordFailure(String, String, Instant, LoginRateLimitProperties)} tracks failure counts and cooldowns.</li>
 *   <li>In a race between a successful-login clear and a failed-login record, the transaction committing last
 *       determines the final state: either no row remains (clear commits last), or attempt #1 starts fresh
 *       without prior counts bleeding through (failure commits last).</li>
 * </ul>
 * </p>
 */
public class PostgresLoginFailureStateStore implements LoginFailureStateStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresLoginFailureStateStore.class);
    private static final Duration TTL_BUFFER = Duration.ofMinutes(5);
    private static final int MAX_ROW_RACE_RETRIES = 20;

    private static final String SQL_INSERT_INITIAL =
            "INSERT INTO login_failure_states (id, subject_hash, failure_count, window_started_at, blocked_until, updated_at, expires_at) " +
            "VALUES (?, ?, 1, ?, NULL, ?, ?) " +
            "ON CONFLICT (id) DO NOTHING";

    private static final String SQL_SELECT_FOR_UPDATE =
            "SELECT failure_count, window_started_at, blocked_until, updated_at, expires_at " +
            "FROM login_failure_states " +
            "WHERE id = ? " +
            "FOR UPDATE";

    private static final String SQL_UPDATE =
            "UPDATE login_failure_states " +
            "SET subject_hash = ?, " +
            "    failure_count = ?, " +
            "    window_started_at = ?, " +
            "    blocked_until = ?, " +
            "    updated_at = ?, " +
            "    expires_at = ? " +
            "WHERE id = ?";

    private static final String SQL_SELECT_ACTIVE_COOLDOWN =
            "SELECT blocked_until, expires_at " +
            "FROM login_failure_states " +
            "WHERE id = ?";

    private static final String SQL_DELETE_BY_ID =
            "DELETE FROM login_failure_states WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactionTemplate;

    public PostgresLoginFailureStateStore(DataSource dataSource) {
        this(dataSource, new DataSourceTransactionManager(Objects.requireNonNull(dataSource, "dataSource must not be null")));
    }

    public PostgresLoginFailureStateStore(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")), transactionManager);
    }

    public PostgresLoginFailureStateStore(
            JdbcTemplate jdbcTemplate,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public OptionalLong activeCooldown(String id, Instant now) {
        Objects.requireNonNull(id, "State ID must not be null");
        Objects.requireNonNull(now, "Instant now must not be null");

        try {
            List<OptionalLong> results = jdbcTemplate.query(
                    SQL_SELECT_ACTIVE_COOLDOWN,
                    (rs, rowNum) -> {
                        Timestamp expiresAtTs = rs.getTimestamp("expires_at");
                        if (expiresAtTs == null) {
                            return OptionalLong.empty();
                        }
                        Instant expiresAt = expiresAtTs.toInstant();
                        // Boundary: at now.equals(expiresAt), expired
                        if (!expiresAt.isAfter(now)) {
                            return OptionalLong.empty();
                        }

                        Timestamp blockedUntilTs = rs.getTimestamp("blocked_until");
                        if (blockedUntilTs == null) {
                            return OptionalLong.empty();
                        }
                        Instant blockedUntil = blockedUntilTs.toInstant();
                        // Boundary: at now.equals(blockedUntil), cooldown expired
                        if (!blockedUntil.isAfter(now)) {
                            return OptionalLong.empty();
                        }

                        long retryAfter = Math.max(1L, Duration.between(now, blockedUntil).getSeconds());
                        return OptionalLong.of(retryAfter);
                    },
                    id
            );

            if (results.isEmpty() || results.get(0).isEmpty()) {
                return OptionalLong.empty();
            }
            return results.get(0);
        } catch (DataAccessException e) {
            log.error("Persistence error checking active login cooldown");
            throw e;
        }
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public FailureResult recordFailure(
            String id,
            String subjectHash,
            Instant now,
            LoginRateLimitProperties properties) {
        Objects.requireNonNull(id, "State ID must not be null");
        Objects.requireNonNull(subjectHash, "Subject hash must not be null");
        Objects.requireNonNull(now, "Instant now must not be null");
        Objects.requireNonNull(properties, "Properties must not be null");

        try {
            return transactionTemplate.execute(status -> {
                for (int attempt = 0; attempt < MAX_ROW_RACE_RETRIES; attempt++) {
                    Instant initialExpiresAt = now.plus(properties.getFailureWindow()).plus(TTL_BUFFER);
                    int inserted = jdbcTemplate.update(
                            SQL_INSERT_INITIAL,
                            id,
                            subjectHash,
                            Timestamp.from(now),
                            Timestamp.from(now),
                            Timestamp.from(initialExpiresAt)
                    );

                    if (inserted == 1) {
                        // Case A: Fresh insert succeeds as attempt #1
                        return new FailureResult(false, 0L);
                    }

                    // Row already exists -> acquire transactional row lock
                    List<LockedState> lockedRows = jdbcTemplate.query(
                            SQL_SELECT_FOR_UPDATE,
                            (rs, rowNum) -> new LockedState(
                                    rs.getInt("failure_count"),
                                    toInstant(rs.getTimestamp("window_started_at")),
                                    toInstant(rs.getTimestamp("blocked_until")),
                                    toInstant(rs.getTimestamp("updated_at")),
                                    toInstant(rs.getTimestamp("expires_at"))
                            ),
                            id
                    );

                    if (lockedRows.isEmpty()) {
                        // Concurrently deleted by clearFailures; retry insert or reacquire in next iteration
                        continue;
                    }

                    LockedState current = lockedRows.get(0);

                    // Case B: Expired state -> treat as fresh failure state
                    if (current.expiresAt == null || !current.expiresAt.isAfter(now)) {
                        Instant resetExpiresAt = now.plus(properties.getFailureWindow()).plus(TTL_BUFFER);
                        int updated = jdbcTemplate.update(
                                SQL_UPDATE,
                                subjectHash,
                                1,
                                Timestamp.from(now),
                                null,
                                Timestamp.from(now),
                                Timestamp.from(resetExpiresAt),
                                id
                        );
                        if (updated == 0) {
                            // Concurrently deleted during update; reacquire in next iteration
                            continue;
                        }
                        return new FailureResult(false, 0L);
                    }

                    // Case C: Active cooldown -> do not increment count, deny with retry-after
                    if (current.blockedUntil != null && current.blockedUntil.isAfter(now)) {
                        long retryAfter = Math.max(1L, Duration.between(now, current.blockedUntil).getSeconds());
                        return new FailureResult(true, retryAfter);
                    }

                    // Cases D, E, F: Check if failure window elapsed or cooldown expired
                    boolean reset = current.windowStartedAt == null
                            || !current.windowStartedAt.plus(properties.getFailureWindow()).isAfter(now)
                            || (current.blockedUntil != null && !current.blockedUntil.isAfter(now));

                    int nextCount;
                    Instant nextWindowStart;
                    if (reset) {
                        nextCount = 1;
                        nextWindowStart = now;
                    } else {
                        if (current.failureCount >= Integer.MAX_VALUE) {
                            log.error("Login failure counter overflow detected during atomic update");
                            return new FailureResult(true, Math.max(1L, properties.getCooldown().getSeconds()));
                        }
                        nextCount = current.failureCount + 1;
                        nextWindowStart = current.windowStartedAt;
                    }

                    Instant blockedUntil = nextCount >= properties.getIdentifierIpFailures()
                            ? now.plus(properties.getCooldown())
                            : null;

                    Instant nextExpiresAt = nextWindowStart
                            .plus(properties.getFailureWindow())
                            .plus(properties.getCooldown())
                            .plus(TTL_BUFFER);

                    int updated = jdbcTemplate.update(
                            SQL_UPDATE,
                            subjectHash,
                            nextCount,
                            Timestamp.from(nextWindowStart),
                            blockedUntil != null ? Timestamp.from(blockedUntil) : null,
                            Timestamp.from(now),
                            Timestamp.from(nextExpiresAt),
                            id
                    );

                    if (updated == 0) {
                        // Concurrently deleted during update; reacquire in next iteration
                        continue;
                    }

                    return new FailureResult(false, 0L);
                }

                log.warn("Exceeded max retry attempts ({}) handling concurrent missing-row race for login failure state", MAX_ROW_RACE_RETRIES);
                return new FailureResult(true, Math.max(1L, properties.getCooldown().getSeconds()));
            });
        } catch (Exception e) {
            log.error("Database access error while recording login failure");
            return new FailureResult(true, Math.max(1L, properties.getCooldown().getSeconds()));
        }
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void clearFailures(String id) {
        Objects.requireNonNull(id, "State ID must not be null");
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(SQL_DELETE_BY_ID, id);
            });
        } catch (DataAccessException e) {
            log.error("Database access error while clearing login failures");
            throw e;
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private record LockedState(
            int failureCount,
            Instant windowStartedAt,
            Instant blockedUntil,
            Instant updatedAt,
            Instant expiresAt
    ) {}

    public DataSource getDataSource() { return jdbcTemplate.getDataSource(); }
}
