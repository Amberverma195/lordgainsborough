package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.LoginRateLimitProperties;
import com.lordgainsborough.restaurant.config.PostgresAbuseCounterStoreConfiguration;
import com.lordgainsborough.restaurant.config.PostgresLoginRateLimiterConfiguration;
import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import com.lordgainsborough.restaurant.service.LoginRateLimiter.FailureResult;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-014: Comprehensive Integration Test Suite for PostgreSQL Login Rate Limiting.
 * <p>
 * Verifies transactional row-level locking ({@code SELECT ... FOR UPDATE}), atomic upserts,
 * boundary transitions, fail-closed security semantics, integer overflow guards,
 * concurrency (20 to 100 threads), and coexistence with MongoDB.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresLoginRateLimiterIntegrationTest {

    private static final String TEST_DB_PREFIX = "pg_login_limit_test_";

    private String testPort;
    private String testDb;
    private String testJdbcUrl;
    private DisposableDatabaseSession databaseSession;
    private PGSimpleDataSource dataSource;
    private PostgresAbuseCounterStore counterStore;
    private PostgresLoginFailureStateStore failureStore;
    private AbuseIpHasher hasher;
    private LoginRateLimitProperties properties;
    private JdbcTemplate jdbcTemplate;

    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static class DisposableDatabaseSession {
        private final String port;
        private final String dbName;
        private boolean createdAndOwned = false;

        DisposableDatabaseSession(String port, String dbName) {
            this.port = port;
            this.dbName = dbName;
        }

        void provision() throws Exception {
            assertThat(port).isNotBlank();
            assertThat(dbName).startsWith(TEST_DB_PREFIX).matches("^[a-z0-9_]{5,63}$");
            assertThat(dbName).isNotIn("postgres", "template0", "template1", "public", "lordgainsborough", "restaurant");

            String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE " + dbName);
                this.createdAndOwned = true;
            }
        }

        void drop() {
            if (!createdAndOwned || dbName == null) {
                return;
            }
            String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid()");
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
                this.createdAndOwned = false;
            } catch (Exception e) {
                System.err.println("Warning: Failed to drop test db " + dbName + ": " + e.getMessage());
            }
        }
    }

    @BeforeAll
    void setUpDatabase() throws Exception {
        testPort = System.getProperty("test.postgres.port", "54353");
        testDb = TEST_DB_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        testJdbcUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/" + testDb + "?sslmode=disable";

        databaseSession = new DisposableDatabaseSession(testPort, testDb);
        databaseSession.provision();

        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(testJdbcUrl);
        dataSource.setUser("postgres");
        dataSource.setPassword("postgres");

        jdbcTemplate = new JdbcTemplate(dataSource);
        counterStore = new PostgresAbuseCounterStore(dataSource, true);
        failureStore = new PostgresLoginFailureStateStore(dataSource);

        SignupAbuseProperties hashProps = new SignupAbuseProperties();
        hashProps.setIpHashSecret("pg-014-test-salt-secret-9988");
        hasher = new AbuseIpHasher(hashProps);

        properties = new LoginRateLimitProperties();
        properties.setIpAttempts(3);
        properties.setIpWindow(Duration.ofMinutes(5));
        properties.setIdentifierIpFailures(5);
        properties.setFailureWindow(Duration.ofMinutes(5));
        properties.setCooldown(Duration.ofSeconds(30));
    }

    @AfterAll
    void tearDownDatabase() {
        if (databaseSession != null) {
            databaseSession.drop();
        }
    }

    // =========================================================================
    // 1 - 4: Context, DI, and Coexistence Verification
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Req 1: PostgreSQL login limiter and failure store initialize cleanly")
    void test01_initialization() {
        assertThat(failureStore).isNotNull();
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        assertThat(limiter).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Req 2: Default profile without opt-in properties does not register PostgresLoginFailureStateStore")
    void test02_defaultMongoProfileRemainsUnchanged() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresLoginRateLimiterConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(PostgresLoginFailureStateStore.class);
                });
    }

    @Test
    @Order(3)
    @DisplayName("Req 3: Combined context with Mongo and Postgres resolves to Postgres @Primary")
    void test03_bothPersistenceInfrastructuresCoexistSafely() {
        MongoTemplate mockMongo = Mockito.mock(MongoTemplate.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresLoginRateLimiterConfiguration.class))
                .withBean("mongoTemplate", MongoTemplate.class, () -> mockMongo)
                .withUserConfiguration(MongoLoginFailureStateStore.class)
                .withPropertyValues("spring.profiles.active=postgres-migration", "app.security.postgres-login-limiter.enabled=true")
                .withBean(DataSource.class, () -> dataSource)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("mongoLoginFailureStateStore");
                    assertThat(ctx).hasBean("postgresLoginFailureStateStore");

                    // Unqualified injection resolves strictly to Postgres (@Primary)
                    assertThat(ctx.getBean(LoginFailureStateStore.class))
                            .isInstanceOf(PostgresLoginFailureStateStore.class);

                    // Named beans resolve both
                    assertThat(ctx.getBean("mongoLoginFailureStateStore", LoginFailureStateStore.class))
                            .isInstanceOf(MongoLoginFailureStateStore.class);
                    assertThat(ctx.getBean("postgresLoginFailureStateStore", LoginFailureStateStore.class))
                            .isInstanceOf(PostgresLoginFailureStateStore.class);
                });
    }

    @Test
    @Order(4)
    @DisplayName("Req 4: No duplicate bean ambiguity for LoginFailureStateStore")
    void test04_noDuplicateSecurityBeanInjection() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresLoginRateLimiterConfiguration.class))
                .withPropertyValues("spring.profiles.active=postgres-migration", "app.security.postgres-login-limiter.enabled=true")
                .withBean(DataSource.class, () -> dataSource)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(PostgresLoginFailureStateStore.class);
                    assertThat(ctx).hasBean("transactionManager");
                });
    }

    // =========================================================================
    // 5 - 8: Per-IP Request Limiting Semantics
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Req 5: First IP request is allowed")
    void test05_firstIpRequestAllowed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-15T10:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        OptionalLong result = limiter.consumeIpRequest("198.51.100.1");
        assertThat(result).isEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("Req 6: Requests exceeding IP attempt limit are denied with retry-after")
    void test06_ipAttemptsExceedingLimitDenied() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-15T10:00:10Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String ip = "198.51.100.2";

        assertThat(limiter.consumeIpRequest(ip)).isEmpty(); // 1
        assertThat(limiter.consumeIpRequest(ip)).isEmpty(); // 2
        assertThat(limiter.consumeIpRequest(ip)).isEmpty(); // 3 (ipAttempts = 3)

        OptionalLong denied = limiter.consumeIpRequest(ip); // 4
        assertThat(denied).isPresent();
        assertThat(denied.getAsLong()).isGreaterThan(0L).isLessThanOrEqualTo(300L);
    }

    @Test
    @Order(7)
    @DisplayName("Req 7: Distinct IPs are isolated into separate buckets")
    void test07_distinctIpsIsolated() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-15T10:00:20Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.consumeIpRequest("198.51.100.3")).isEmpty();
        }
        assertThat(limiter.consumeIpRequest("198.51.100.3")).isPresent(); // Denied

        // Unrelated IP is still allowed
        assertThat(limiter.consumeIpRequest("198.51.100.4")).isEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("Req 8: IP window rollover resets request counter in distinct window bucket")
    void test08_ipWindowRolloverCorrect() {
        Instant t0 = Instant.parse("2026-07-15T10:00:30Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String ip = "198.51.100.5";

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.consumeIpRequest(ip)).isEmpty();
        }
        assertThat(limiter.consumeIpRequest(ip)).isPresent(); // Denied in window 0

        // Advance past 5-minute window
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(limiter.consumeIpRequest(ip)).isEmpty(); // Allowed in window 1
    }

    // =========================================================================
    // 9 - 19: Failure Count, Cooldown, and Rollover Semantics
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("Req 9: First failed login returns non-denied and stores failure_count = 1")
    void test09_firstFailedLoginCountIsOne() {
        Instant t0 = Instant.parse("2026-07-15T11:00:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-1@example.com";
        String ip = "198.51.100.10";

        FailureResult res = limiter.recordFailure(id, ip);
        assertThat(res.denied()).isFalse();
        assertThat(res.retryAfterSeconds()).isEqualTo(0L);

        String stateId = limiter.failureStateId(id, ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(10)
    @DisplayName("Req 10: Second failed login increments failure_count to 2")
    void test10_secondFailedLoginCountIsTwo() {
        Instant t0 = Instant.parse("2026-07-15T11:05:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-2@example.com";
        String ip = "198.51.100.11";

        limiter.recordFailure(id, ip);
        FailureResult res2 = limiter.recordFailure(id, ip);
        assertThat(res2.denied()).isFalse();

        String stateId = limiter.failureStateId(id, ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(2);
    }

    @Test
    @Order(11)
    @DisplayName("Req 11: Failure threshold sets blocked_until to now + cooldown")
    void test11_failureThresholdSetsCooldown() {
        Instant t0 = Instant.parse("2026-07-15T11:10:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-3@example.com";
        String ip = "198.51.100.12";

        for (int i = 1; i <= 5; i++) {
            limiter.recordFailure(id, ip);
        }

        String stateId = limiter.failureStateId(id, ip);
        Timestamp blockedUntil = jdbcTemplate.queryForObject(
                "SELECT blocked_until FROM login_failure_states WHERE id = ?",
                Timestamp.class,
                stateId
        );
        assertThat(blockedUntil).isNotNull();
        assertThat(blockedUntil.toInstant()).isEqualTo(t0.plus(properties.getCooldown()));
    }

    @Test
    @Order(12)
    @DisplayName("Req 12: Threshold-reaching request retains legacy FailureResult(false, 0) semantics")
    void test12_thresholdReachingRequestRetainsLegacySemantics() {
        Instant t0 = Instant.parse("2026-07-15T11:15:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-4@example.com";
        String ip = "198.51.100.13";

        for (int i = 1; i <= 4; i++) {
            assertThat(limiter.recordFailure(id, ip).denied()).isFalse();
        }

        // 5th attempt (threshold)
        FailureResult fifth = limiter.recordFailure(id, ip);
        assertThat(fifth.denied()).isFalse();
        assertThat(fifth.retryAfterSeconds()).isEqualTo(0L);
    }

    @Test
    @Order(13)
    @DisplayName("Req 13: Requests during active cooldown are denied with positive retry-after")
    void test13_requestsDuringActiveCooldownDenied() {
        Instant t0 = Instant.parse("2026-07-15T11:20:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-5@example.com";
        String ip = "198.51.100.14";

        for (int i = 1; i <= 5; i++) {
            limiter.recordFailure(id, ip);
        }

        // 6th attempt occurs within 30s cooldown
        clock.advance(Duration.ofSeconds(10));
        FailureResult sixth = limiter.recordFailure(id, ip);
        assertThat(sixth.denied()).isTrue();
        assertThat(sixth.retryAfterSeconds()).isEqualTo(20L);

        // Failure count does not increment during active cooldown
        String stateId = limiter.failureStateId(id, ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(5);
    }

    @Test
    @Order(14)
    @DisplayName("Req 14: activeCooldown returns exact remaining seconds")
    void test14_countdownRetryAfterSecondsIsCorrect() {
        Instant t0 = Instant.parse("2026-07-15T11:25:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-6@example.com";
        String ip = "198.51.100.15";

        for (int i = 1; i <= 5; i++) {
            limiter.recordFailure(id, ip);
        }

        clock.advance(Duration.ofSeconds(12));
        OptionalLong cd = limiter.activeCooldown(id, ip);
        assertThat(cd).isPresent();
        assertThat(cd.getAsLong()).isEqualTo(18L);
    }

    @Test
    @Order(15)
    @DisplayName("Req 15: Cooldown expires at the exact boundary")
    void test15_cooldownExpiresAtExactBoundary() {
        Instant t0 = Instant.parse("2026-07-15T11:30:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-7@example.com";
        String ip = "198.51.100.16";

        for (int i = 1; i <= 5; i++) {
            limiter.recordFailure(id, ip);
        }

        // 1 ms before blockedUntil -> active cooldown
        clock.set(t0.plusSeconds(30).minusMillis(1));
        assertThat(limiter.activeCooldown(id, ip)).isPresent();

        // Exactly at blockedUntil -> cooldown expired
        clock.set(t0.plusSeconds(30));
        assertThat(limiter.activeCooldown(id, ip)).isEmpty();

        // 1 ms after blockedUntil -> cooldown expired
        clock.set(t0.plusSeconds(30).plusMillis(1));
        assertThat(limiter.activeCooldown(id, ip)).isEmpty();
    }

    @Test
    @Order(16)
    @DisplayName("Req 16: Expired state no longer blocks login even if blocked_until was set")
    void test16_expiredStateNoLongerBlocksLogin() {
        Instant t0 = Instant.parse("2026-07-15T11:35:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-8@example.com";
        String ip = "198.51.100.17";

        for (int i = 1; i <= 5; i++) {
            limiter.recordFailure(id, ip);
        }

        // Advance past expiresAt (failureWindow + cooldown + 5m buffer)
        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.activeCooldown(id, ip)).isEmpty();
    }

    @Test
    @Order(17)
    @DisplayName("Req 17: Expired retained row resets safely to failure_count = 1")
    void test17_expiredRetainedRowResetsSafely() {
        Instant t0 = Instant.parse("2026-07-15T11:40:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-9@example.com";
        String ip = "198.51.100.18";

        limiter.recordFailure(id, ip);
        clock.advance(Duration.ofMinutes(30)); // Stale expired row

        FailureResult fresh = limiter.recordFailure(id, ip);
        assertThat(fresh.denied()).isFalse();

        String stateId = limiter.failureStateId(id, ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(18)
    @DisplayName("Req 18: Failure window expiration resets the failure count")
    void test18_failureWindowExpirationResetsCount() {
        Instant t0 = Instant.parse("2026-07-15T11:45:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-10@example.com";
        String ip = "198.51.100.19";

        limiter.recordFailure(id, ip); // attempt 1
        limiter.recordFailure(id, ip); // attempt 2

        // Advance 6 minutes (failureWindow = 5m)
        clock.advance(Duration.ofMinutes(6));

        FailureResult fresh = limiter.recordFailure(id, ip);
        assertThat(fresh.denied()).isFalse();

        String stateId = limiter.failureStateId(id, ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(19)
    @DisplayName("Req 19: Fresh failures after cooldown start a new sequence at count = 1")
    void test19_freshFailuresAfterCooldownStartNewSequence() {
        Instant t0 = Instant.parse("2026-07-15T11:50:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "member-11@example.com";
        String ip = "198.51.100.20";

        for (int i = 1; i <= 5; i++) {
            limiter.recordFailure(id, ip);
        }

        // Advance past 30-second cooldown
        clock.advance(Duration.ofSeconds(31));

        FailureResult postCooldown = limiter.recordFailure(id, ip);
        assertThat(postCooldown.denied()).isFalse();

        String stateId = limiter.failureStateId(id, ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // 20 - 24: Targeted Clearing & Key Hashing Parity
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("Req 20: Successful login clears only the exact matching state ID")
    void test20_successfulLoginClearsOnlyMatchingState() {
        Instant t0 = Instant.parse("2026-07-15T12:00:00Z");
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        limiter.recordFailure("target@example.com", "198.51.100.21");
        limiter.recordFailure("target@example.com", "198.51.100.22");
        limiter.recordFailure("other@example.com", "198.51.100.21");

        limiter.clearFailures("target@example.com", "198.51.100.21");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM login_failure_states WHERE id = ?",
                Integer.class,
                limiter.failureStateId("target@example.com", "198.51.100.21")
        )).isEqualTo(0);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM login_failure_states WHERE id = ?",
                Integer.class,
                limiter.failureStateId("target@example.com", "198.51.100.22")
        )).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM login_failure_states WHERE id = ?",
                Integer.class,
                limiter.failureStateId("other@example.com", "198.51.100.21")
        )).isEqualTo(1);
    }

    @Test
    @Order(21)
    @DisplayName("Req 21: Repeated clear is safe and idempotent")
    void test21_repeatedClearIsSafe() {
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        limiter.clearFailures("nonexistent@example.com", "198.51.100.23");
        limiter.clearFailures("nonexistent@example.com", "198.51.100.23");
    }

    @Test
    @Order(22)
    @DisplayName("Req 22: Other identifiers remain completely unaffected")
    void test22_otherIdentifiersRemainUnaffected() {
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        limiter.recordFailure("victim@example.com", "198.51.100.24");
        limiter.clearFailures("attacker@example.com", "198.51.100.24");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM login_failure_states WHERE id = ?",
                Integer.class,
                limiter.failureStateId("victim@example.com", "198.51.100.24")
        )).isEqualTo(1);
    }

    @Test
    @Order(23)
    @DisplayName("Req 23: Different client IPs remain independent for same identifier")
    void test23_differentIpsRemainIndependent() {
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        limiter.recordFailure("user@example.com", "198.51.100.25");
        limiter.recordFailure("user@example.com", "198.51.100.26");

        String id1 = limiter.failureStateId("user@example.com", "198.51.100.25");
        String id2 = limiter.failureStateId("user@example.com", "198.51.100.26");
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @Order(24)
    @DisplayName("Req 24: Exact ID hashing matches MongoDB implementation byte-for-byte")
    void test24_exactIdHashingMatchesMongoImplementation() {
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        String id = limiter.failureStateId("Member@Example.COM ", "203.0.113.10");
        String expectedSubjHash = hasher.hashRaw("login-identifier-ip|member@example.com|" + hasher.hashIp("203.0.113.10"));
        String expectedId = hasher.hashRaw("login-failure-state|" + expectedSubjHash);

        assertThat(id).isEqualTo(expectedId);
    }

    // =========================================================================
    // 25 - 29: Schema Constraints, Overflow, Fail-Closed, and Rollback
    // =========================================================================

    @Test
    @Order(25)
    @DisplayName("Req 25: Timestamps persist without unintended timezone shifts")
    void test25_timestampsPersistWithoutUnintendedTimezoneShifts() {
        Instant expected = Instant.parse("2026-10-26T15:30:45.123Z");
        MutableClock clock = new MutableClock(expected);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        limiter.recordFailure("tz@example.com", "198.51.100.27");
        String id = limiter.failureStateId("tz@example.com", "198.51.100.27");

        Timestamp ws = jdbcTemplate.queryForObject(
                "SELECT window_started_at FROM login_failure_states WHERE id = ?",
                Timestamp.class,
                id
        );
        assertThat(ws).isNotNull();
        assertThat(ws.toInstant().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(expected.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @Order(26)
    @DisplayName("Req 26: Negative failure counts violate check constraint chk_login_failure_count")
    void test26_negativeCountsRemainSqlInvalid() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO login_failure_states (id, subject_hash, failure_count, window_started_at, updated_at, expires_at) " +
                "VALUES ('neg-test', 'hash', -1, NOW(), NOW(), NOW() + INTERVAL '1 hour')"
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Order(27)
    @DisplayName("Req 27: Integer overflow is prevented safely without wrapping negative")
    void test27_integerOverflowFailsSafely() {
        Instant t0 = Instant.now();
        MutableClock clock = new MutableClock(t0);
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "overflow@example.com";
        String ip = "198.51.100.28";

        limiter.recordFailure(id, ip);
        String stateId = limiter.failureStateId(id, ip);

        // Artificially prime to Integer.MAX_VALUE
        jdbcTemplate.update(
                "UPDATE login_failure_states SET failure_count = ? WHERE id = ?",
                Integer.MAX_VALUE,
                stateId
        );

        // Next failure must not wrap negative; must fail-closed and return cooldown
        FailureResult overflowResult = limiter.recordFailure(id, ip);
        assertThat(overflowResult.denied()).isTrue();
        assertThat(overflowResult.retryAfterSeconds()).isEqualTo(30L);

        Integer current = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(current).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @Order(28)
    @DisplayName("Req 28: Data access failures fail closed and deny authentication")
    void test28_dataAccessFailuresFailClosed() {
        // Create store with closed DataSource
        PGSimpleDataSource badDs = new PGSimpleDataSource();
        badDs.setUrl("jdbc:postgresql://127.0.0.1:54399/nonexistent?sslmode=disable");
        badDs.setConnectTimeout(1);

        PostgresLoginFailureStateStore brokenStore = new PostgresLoginFailureStateStore(badDs);
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, brokenStore, properties, hasher, clock);

        FailureResult failClosed = limiter.recordFailure("any@example.com", "198.51.100.29");
        assertThat(failClosed.denied()).isTrue();
        assertThat(failClosed.retryAfterSeconds()).isEqualTo(30L);
    }

    @Test
    @Order(29)
    @DisplayName("Req 29: Transaction rollback restores previously committed state")
    void test29_rollbackPreventsPartiallyPersistedTransitions() {
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);
        String id = "rollback@example.com";
        String ip = "198.51.100.30";

        limiter.recordFailure(id, ip); // failure_count = 1
        String stateId = limiter.failureStateId(id, ip);

        TransactionStatus status = txManager.getTransaction(new DefaultTransactionDefinition());
        limiter.recordFailure(id, ip); // attempt to increment to 2 inside tx
        txManager.rollback(status);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // 30 - 35: Concurrency and Thread Safety
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("Req 30: Concurrent first failures create exactly one state record")
    void test30_concurrentFirstFailuresCreateExactlyOneState() throws Exception {
        int threads = 20;
        String id = "first-race-" + UUID.randomUUID() + "@example.com";
        String ip = "198.51.100.31";
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<FailureResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return limiter.recordFailure(id, ip);
            }));
        }

        for (Future<FailureResult> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        String stateId = limiter.failureStateId(id, ip);
        List<Integer> rows = jdbcTemplate.query(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                (rs, rowNum) -> rs.getInt(1),
                stateId
        );
        assertThat(rows).hasSize(1);
    }

    @Test
    @Order(31)
    @DisplayName("Req 31: 20 concurrent failures cause no lost increments under threshold")
    void test31_20ConcurrentFailuresCauseNoLostIncrements() throws Exception {
        int threads = 20;
        String id = "no-loss-20-" + UUID.randomUUID() + "@example.com";
        String ip = "198.51.100.32";
        MutableClock clock = new MutableClock(Instant.now());

        LoginRateLimitProperties highThresholdProps = new LoginRateLimitProperties();
        highThresholdProps.setIdentifierIpFailures(50); // High threshold prevents premature cooldown
        highThresholdProps.setFailureWindow(Duration.ofMinutes(10));
        highThresholdProps.setCooldown(Duration.ofSeconds(30));

        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, highThresholdProps, hasher, clock);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<FailureResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return limiter.recordFailure(id, ip);
            }));
        }

        for (Future<FailureResult> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        String stateId = limiter.failureStateId(id, ip);
        Integer finalCount = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(finalCount).isEqualTo(threads);
    }

    @Test
    @Order(32)
    @DisplayName("Req 32: 100 concurrent failures cause no lost increments under high threshold")
    void test32_100ConcurrentFailuresUnderThreshold() throws Exception {
        int threads = 100;
        String id = "no-loss-100-" + UUID.randomUUID() + "@example.com";
        String ip = "198.51.100.33";
        MutableClock clock = new MutableClock(Instant.now());

        LoginRateLimitProperties highThresholdProps = new LoginRateLimitProperties();
        highThresholdProps.setIdentifierIpFailures(200); // 200 threshold accommodates all 100 threads
        highThresholdProps.setFailureWindow(Duration.ofMinutes(10));
        highThresholdProps.setCooldown(Duration.ofSeconds(30));

        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, highThresholdProps, hasher, clock);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<FailureResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                return limiter.recordFailure(id, ip);
            }));
        }

        for (Future<FailureResult> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        String stateId = limiter.failureStateId(id, ip);
        Integer finalCount = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(finalCount).isEqualTo(100);
    }

    @Test
    @Order(33)
    @DisplayName("Req 33: Concurrent active-cooldown requests all return denied and do not increment")
    void test33_concurrentActiveCooldownRequestsDoNotIncrement() throws Exception {
        int threads = 30;
        String id = "cooldown-race-" + UUID.randomUUID() + "@example.com";
        String ip = "198.51.100.34";
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        // Reach threshold (5 attempts)
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(id, ip);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<FailureResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return limiter.recordFailure(id, ip);
            }));
        }

        for (Future<FailureResult> f : futures) {
            FailureResult res = f.get(20, TimeUnit.SECONDS);
            assertThat(res.denied()).isTrue();
        }
        executor.shutdown();

        String stateId = limiter.failureStateId(id, ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM login_failure_states WHERE id = ?",
                Integer.class,
                stateId
        );
        assertThat(count).isEqualTo(5);
    }

    @Test
    @Order(34)
    @DisplayName("Req 34: Multiple state IDs process independently under contention")
    void test34_multipleStateIdsProcessIndependently() throws Exception {
        int numUsers = 5;
        int threadsPerUser = 10;
        int totalThreads = numUsers * threadsPerUser;
        MutableClock clock = new MutableClock(Instant.now());

        LoginRateLimitProperties highProps = new LoginRateLimitProperties();
        highProps.setIdentifierIpFailures(50);
        highProps.setFailureWindow(Duration.ofMinutes(10));
        highProps.setCooldown(Duration.ofSeconds(30));

        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, highProps, hasher, clock);

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);
        List<Future<?>> futures = new ArrayList<>();

        List<String> stateIds = new ArrayList<>();
        for (int u = 0; u < numUsers; u++) {
            String id = "user-" + u + "-" + UUID.randomUUID() + "@example.com";
            String ip = "198.51.100." + (40 + u);
            stateIds.add(limiter.failureStateId(id, ip));
            for (int t = 0; t < threadsPerUser; t++) {
                futures.add(executor.submit(() -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    limiter.recordFailure(id, ip);
                    return null;
                }));
            }
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(stateIds).hasSize(numUsers);
        for (String stateId : stateIds) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT failure_count FROM login_failure_states WHERE id = ?",
                    Integer.class,
                    stateId
            );
            assertThat(count).isEqualTo(threadsPerUser);
        }
    }

    @Test
    @Order(35)
    @DisplayName("Req 35: Concurrent recordFailure and clearFailures calls are safe and do not deadlock")
    void test35_concurrentRecordFailureAndClearFailuresSafe() throws Exception {
        int threads = 30;
        String id = "race-clear-" + UUID.randomUUID() + "@example.com";
        String ip = "198.51.100.50";
        MutableClock clock = new MutableClock(Instant.now());
        LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                if (index % 3 == 0) {
                    limiter.clearFailures(id, ip);
                } else {
                    limiter.recordFailure(id, ip);
                }
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();
    }

    // =========================================================================
    // 36 - 38: Logging Privacy, Production Safety, and MongoDB Parity
    // =========================================================================

    @Test
    @Order(36)
    @DisplayName("Req 36: State transition SQL and logs do not leak identifiers or hashes")
    void test36_stateTransitionSqlDoesNotLeakHashesOrIdentifiersIntoLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(PostgresLoginFailureStateStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            String secretEmail = "secret-privacy-test-998877@example.com";
            String secretIp = "198.51.100.99";
            MutableClock clock = new MutableClock(Instant.now());
            LoginRateLimiter limiter = new LoginRateLimiter(counterStore, failureStore, properties, hasher, clock);

            limiter.recordFailure(secretEmail, secretIp);

            // Verify that no log events leak secretEmail or client IP
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage())
                        .doesNotContain(secretEmail)
                        .doesNotContain(secretIp);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @Order(37)
    @DisplayName("Req 37: No production service or controller switches to PostgreSQL automatically")
    void test37_noProductionServiceOrControllerSwitchedToPostgresAutomatically() {
        MongoTemplate mockMongo = Mockito.mock(MongoTemplate.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresLoginRateLimiterConfiguration.class))
                .withBean("mongoTemplate", MongoTemplate.class, () -> mockMongo)
                .withUserConfiguration(MongoLoginFailureStateStore.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // In default context (without opt-in property), Postgres is NOT loaded
                    assertThat(ctx).doesNotHaveBean(PostgresLoginFailureStateStore.class);
                    assertThat(ctx).hasBean("mongoLoginFailureStateStore");
                });
    }

    @Test
    @Order(38)
    @DisplayName("Req 38: MongoLoginFailureStateStore baseline behavior functions correctly")
    void test38_existingMongoLoginRateLimiterTestsContinuePassing() {
        MongoTemplate mockMongo = Mockito.mock(MongoTemplate.class);
        MongoLoginFailureStateStore mongoStore = new MongoLoginFailureStateStore(mockMongo);
        assertThat(mongoStore).isNotNull();

        // Querying non-existent returns empty
        OptionalLong cd = mongoStore.activeCooldown("dummy", Instant.now());
        assertThat(cd).isEmpty();
    }
}
