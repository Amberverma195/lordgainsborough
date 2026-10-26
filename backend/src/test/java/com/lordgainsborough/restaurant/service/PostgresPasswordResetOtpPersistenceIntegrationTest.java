package com.lordgainsborough.restaurant.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.config.PostgresPasswordResetPersistenceConfiguration;
import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.dto.PasswordResetResponse;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.repository.PasswordResetOtpChallengeRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.dto.ResetPasswordRequest;
import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.orm.jpa.JpaTransactionManager;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresPasswordResetOtpPersistenceIntegrationTest {

    private static final String TEST_DB_PREFIX = "pg_pwd_reset_test_";
    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T14:00:00Z");
    private static final String TEST_USER_ID = "usr-reset-test-101";
    private static final String TEST_USER_EMAIL = "reset.user@example.com";
    private static final String OLD_PASSWORD = "OldPassword123!";
    private static final String NEW_PASSWORD = "NewPassword456@";

    private String testPort;
    private String testDb;
    private String testJdbcUrl;
    private DisposableDatabaseSession databaseSession;
    private PGSimpleDataSource dataSource;
    private PlatformTransactionManager transactionManager;
    private PostgresPasswordResetOtpChallengeStore store;
    private MutableClock clock;
    private PasswordResetProperties properties;
    private AbuseIpHasher hasher;
    private PasswordResetOtpChallengePersistence persistence;
    private PasswordResetAbuseLimiter abuseLimiter;
    private PasswordResetPublicHandleService handleService;
    private EmailSenderService emailSenderService;
    private AuthSecurityObservability observability;
    private PasswordResetService service;
    private JdbcTemplate jdbcTemplate;
    private JwtUtil jwtUtil;
    private boolean postgresAvailable = false;

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

        boolean isCreatedAndOwned() {
            return createdAndOwned;
        }

        String getDbName() {
            return dbName;
        }
    }

    @BeforeAll
    void setUpDatabase() throws Exception {
        testPort = System.getProperty("test.postgres.port", "54353");
        boolean mandatory = Boolean.getBoolean("test.postgres.mandatory")
                || "true".equalsIgnoreCase(System.getenv("CI_POSTGRES_MANDATORY"))
                || "true".equalsIgnoreCase(System.getenv("CI"));

        properties = new PasswordResetProperties();
        properties.setCodeTtl(Duration.ofMinutes(10));
        properties.setChallengeTtl(Duration.ofMinutes(30));
        properties.setResendCooldown(Duration.ofSeconds(30));
        properties.setSendLease(Duration.ofSeconds(60));
        properties.setMaxResends(5);
        properties.setMaxVerificationFailures(5);
        properties.setNewChallengesPerEmail(100);
        properties.setEmailOperationsPerIp(1_000);
        properties.setGlobalEmailOperations(10_000);
        properties.setVerificationsPerIp(1_000);

        SignupAbuseProperties hashingProperties = new SignupAbuseProperties();
        hashingProperties.setIpHashSecret("password-reset-postgres-test-secret");
        hasher = new AbuseIpHasher(hashingProperties);

        clock = new MutableClock(FIXED_NOW);
        handleService = new PasswordResetPublicHandleService(hasher);
        emailSenderService = mock(EmailSenderService.class);
        doNothing().when(emailSenderService).sendPasswordResetCodeEmail(anyString(), anyString(), anyString());
        observability = new AuthSecurityObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "password-reset-jwt-test-secret-that-is-at-least-32-bytes");
        ReflectionTestUtils.setField(jwtUtil, "expirationTime", 60_000L);

        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable", "postgres", "postgres")) {
            postgresAvailable = true;
        } catch (Exception e) {
            postgresAvailable = false;
            store = mock(PostgresPasswordResetOtpChallengeStore.class);
            persistence = new PasswordResetOtpChallengePersistence(store, properties, hasher, clock);
            abuseLimiter = mock(PasswordResetAbuseLimiter.class);
            service = new PasswordResetService(
                    persistence,
                    abuseLimiter,
                    handleService,
                    properties,
                    null,
                    null,
                    emailSenderService,
                    hasher,
                    observability
            );
            if (mandatory) {
                throw new IllegalStateException("PostgreSQL is mandatory for PG-016 testing on port " + testPort, e);
            }
            return;
        }

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

        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);

        store = new PostgresPasswordResetOtpChallengeStore(dataSource, transactionManager, properties);
        persistence = new PasswordResetOtpChallengePersistence(store, properties, hasher, clock);

        abuseLimiter = new PasswordResetAbuseLimiter(
                new PostgresAbuseCounterStore(dataSource),
                properties,
                hasher,
                clock
        );

        service = new PasswordResetService(
                persistence,
                abuseLimiter,
                handleService,
                properties,
                null,
                null,
                emailSenderService,
                hasher,
                observability
        );
    }

    @AfterAll
    void tearDownDatabase() {
        if (databaseSession != null) {
            databaseSession.drop();
        }
    }

    @BeforeEach
    void resetClockAndData() {
        if (store != null) {
            store.resetFailureInjectors();
        }
        if (!postgresAvailable) return;
        clock.set(FIXED_NOW);
        jdbcTemplate.update("DELETE FROM password_reset_otp_challenges");
        jdbcTemplate.update("DELETE FROM users");
        createTestUser(TEST_USER_ID, TEST_USER_EMAIL, OLD_PASSWORD);
    }

    private void createTestUser(String userId, String email, String rawPassword) {
        String insertSql = """
            INSERT INTO users (id, name, email, password, role, token_version, no_show_count)
            VALUES (?, ?, ?, ?, 'MEMBER', 0, 0)
            """;
        jdbcTemplate.update(insertSql, userId, "Test Member", email, PasswordUtil.hashPassword(rawPassword));
    }

    private void requirePostgres() {
        assumeTrue(postgresAvailable, "PostgreSQL not running on port " + testPort + "; skipping live PostgreSQL test");
    }

    private PasswordResetOtpChallenge buildChallenge(
            String emailKey,
            String challengeId,
            String userId,
            String rawCode,
            Instant now) {
        PasswordResetOtpChallenge ch = new PasswordResetOtpChallenge();
        ch.setEmailKey(emailKey);
        ch.setChallengeId(challengeId);
        ch.setUserId(userId);
        ch.setPurpose(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET);
        ch.setStatus(PasswordResetChallengeStatus.ACTIVE);
        ch.setCodeDigest(persistence.digestCode(rawCode));
        ch.setCodeExpiresAt(now.plus(properties.getCodeTtl()));
        ch.setChallengeExpiresAt(now.plus(properties.getChallengeTtl()));
        ch.setNextResendAt(now.plus(properties.getResendCooldown()));
        ch.setResendCount(0);
        ch.setFailedVerificationAttempts(0);
        ch.setClientIdempotencyKey("idemp-init-1");
        ch.setSendOperationId("op-init-1");
        ch.setSendStatus(OtpSendStatus.RESERVED);
        ch.setSendLeaseUntil(now.plus(properties.getSendLease()));
        ch.setCreatedAt(now);
        ch.setUpdatedAt(now);
        return ch;
    }

    // =========================================================================
    // GROUP 1: Identity, Insertion & Constraints
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("01. Email normalization and HMAC email key derivation")
    void test01_emailNormalizationAndKeyDerivation() {
        String email = "  Reset.Member@Example.COM  ";
        String normalized = email.trim().toLowerCase();
        assertEquals("reset.member@example.com", normalized);

        String key1 = persistence.emailKey(normalized);
        String key2 = persistence.emailKey("reset.member@example.com");
        assertNotNull(key1);
        assertEquals(key1, key2);
    }

    @Test
    @Order(2)
    @DisplayName("02. Initial challenge insertion stores digest, not plaintext code")
    void test02_initialInsertionStoresDigestNotPlaintext() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-01", TEST_USER_ID, rawCode, clock.instant());

        boolean inserted = persistence.getStore().tryInsert(ch);
        assertTrue(inserted);

        Optional<PasswordResetOtpChallenge> stored = persistence.findByEmailKey(emailKey);
        assertTrue(stored.isPresent());
        assertEquals("ch-01", stored.get().getChallengeId());
        assertNotEquals(rawCode, stored.get().getCodeDigest());
        assertTrue(persistence.codeMatches(rawCode, stored.get().getCodeDigest()));
        assertFalse(persistence.codeMatches("000000", stored.get().getCodeDigest()));
    }

    @Test
    @Order(3)
    @DisplayName("03. Duplicate primary key insertion on existing challenge returns false")
    void test03_duplicatePrimaryKeyInsertionReturnsFalse() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch1 = buildChallenge(emailKey, "ch-02a", TEST_USER_ID, "123456", clock.instant());
        PasswordResetOtpChallenge ch2 = buildChallenge(emailKey, "ch-02b", TEST_USER_ID, "654321", clock.instant());

        assertTrue(persistence.getStore().tryInsert(ch1));
        assertFalse(persistence.getStore().tryInsert(ch2));
    }

    @Test
    @Order(4)
    @DisplayName("04. Foreign key constraint to users table is enforced")
    void test04_foreignKeyConstraintToUsersTableEnforced() {
        requirePostgres();
        String emailKey = persistence.emailKey("nonexistent@example.com");
        PasswordResetOtpChallenge invalidUserCh = buildChallenge(emailKey, "ch-fk", "non-existent-user-id", "123456", clock.instant());

        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update("""
                INSERT INTO password_reset_otp_challenges (
                    email_key, challenge_id, user_id, purpose, status, code_digest,
                    code_expires_at, challenge_expires_at, next_resend_at, resend_count,
                    failed_verification_attempts, client_idempotency_key, send_operation_id,
                    send_status, send_lease_until, reset_operation_id, password_updated,
                    consumed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'PASSWORD_RESET', 'ACTIVE', 'digest', NOW(), NOW(), NOW(), 0, 0, 'k', 'op', 'RESERVED', NOW(), NULL, FALSE, NULL, NOW(), NOW())
                """, emailKey, "ch-fk", "non-existent-user-id");
        });
    }

    @Test
    @Order(5)
    @DisplayName("05. Unique constraint on challenge_id is enforced")
    void test05_uniqueConstraintOnChallengeIdEnforced() {
        requirePostgres();
        createTestUser("usr-2", "user2@example.com", OLD_PASSWORD);
        String emailKey1 = persistence.emailKey(TEST_USER_EMAIL);
        String emailKey2 = persistence.emailKey("user2@example.com");

        PasswordResetOtpChallenge ch1 = buildChallenge(emailKey1, "ch-unique-id", TEST_USER_ID, "111111", clock.instant());
        PasswordResetOtpChallenge ch2 = buildChallenge(emailKey2, "ch-unique-id", "usr-2", "222222", clock.instant());

        assertTrue(persistence.getStore().tryInsert(ch1));
        assertThrows(DataIntegrityViolationException.class, () -> persistence.getStore().tryInsert(ch2));
    }

    // =========================================================================
    // GROUP 2: Expired & Consumed Replacement
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("06. Expired challenge is atomically replaced")
    void test06_expiredChallengeIsAtomicallyReplaced() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge oldCh = buildChallenge(emailKey, "ch-old", TEST_USER_ID, "111111", clock.instant());
        oldCh.setChallengeExpiresAt(clock.instant().minusSeconds(1));
        persistence.getStore().tryInsert(oldCh);

        PasswordResetOtpChallenge freshCh = buildChallenge(emailKey, "ch-fresh", TEST_USER_ID, "999999", clock.instant());
        Optional<PasswordResetOtpChallenge> replaced = persistence.tryCreateOrReplaceExpired(freshCh, clock.instant());

        assertTrue(replaced.isPresent());
        assertEquals("ch-fresh", replaced.get().getChallengeId());
        Optional<PasswordResetOtpChallenge> stored = persistence.findByEmailKey(emailKey);
        assertEquals("ch-fresh", stored.orElseThrow().getChallengeId());
    }

    @Test
    @Order(7)
    @DisplayName("07. Consumed challenge is atomically replaced")
    void test07_consumedChallengeIsAtomicallyReplaced() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge consumedCh = buildChallenge(emailKey, "ch-cons", TEST_USER_ID, "111111", clock.instant());
        consumedCh.setStatus(PasswordResetChallengeStatus.CONSUMED);
        consumedCh.setPasswordUpdated(true);
        consumedCh.setConsumedAt(clock.instant());
        persistence.getStore().tryInsert(consumedCh);

        PasswordResetOtpChallenge freshCh = buildChallenge(emailKey, "ch-new", TEST_USER_ID, "888888", clock.instant());
        Optional<PasswordResetOtpChallenge> replaced = persistence.tryCreateOrReplaceExpired(freshCh, clock.instant());

        assertTrue(replaced.isPresent());
        assertEquals("ch-new", replaced.get().getChallengeId());
    }

    @Test
    @Order(8)
    @DisplayName("08. Active unexpired challenge refuses replacement")
    void test08_activeUnexpiredChallengeRefusesReplacement() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge activeCh = buildChallenge(emailKey, "ch-act", TEST_USER_ID, "111111", clock.instant());
        persistence.getStore().tryInsert(activeCh);

        PasswordResetOtpChallenge freshCh = buildChallenge(emailKey, "ch-blocked", TEST_USER_ID, "222222", clock.instant());
        Optional<PasswordResetOtpChallenge> replaced = persistence.tryCreateOrReplaceExpired(freshCh, clock.instant());

        assertTrue(replaced.isEmpty());
        assertEquals("ch-act", persistence.findByEmailKey(emailKey).orElseThrow().getChallengeId());
    }

    // =========================================================================
    // GROUP 3: Resend, Cooldown Boundaries & Limits
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("09. Resend before cooldown expires fails closed")
    void test09_resendBeforeCooldownFailsClosed() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-cooldown", TEST_USER_ID, "111111", clock.instant());
        persistence.getStore().tryInsert(ch);

        // Before cooldown expires (advance only 10s of 30s)
        clock.advance(Duration.ofSeconds(10));
        Optional<PasswordResetOtpChallenge> resend = persistence.tryReserveResend(
                emailKey, "ch-cooldown", persistence.digestCode("222222"), "k-resend-1", "op-resend-1", clock.instant());

        assertTrue(resend.isEmpty());
        assertEquals(0, persistence.findByEmailKey(emailKey).orElseThrow().getResendCount());
    }

    @Test
    @Order(10)
    @DisplayName("10. Null nextResendAt fails closed for strict cross-backend parity")
    void test10_nullNextResendAtFailsClosed() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-null-cd", TEST_USER_ID, "111111", clock.instant());
        ch.setNextResendAt(null);
        persistence.getStore().tryInsert(ch);

        Optional<PasswordResetOtpChallenge> resend = persistence.tryReserveResend(
                emailKey, "ch-null-cd", persistence.digestCode("222222"), "k-null-cd", "op-null-cd", clock.instant());

        assertTrue(resend.isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("11. Exact cooldown boundary permits resend and increments count")
    void test11_exactCooldownBoundaryPermitsResend() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-exact-cd", TEST_USER_ID, "111111", clock.instant());
        persistence.getStore().tryInsert(ch);

        // Advance exactly 30s
        clock.advance(Duration.ofSeconds(30));
        Optional<PasswordResetOtpChallenge> resend = persistence.tryReserveResend(
                emailKey, "ch-exact-cd", persistence.digestCode("222222"), "k-exact-1", "op-exact-1", clock.instant());

        assertTrue(resend.isPresent());
        assertEquals(1, resend.get().getResendCount());
        assertEquals(OtpSendStatus.RESERVED, resend.get().getSendStatus());
        assertTrue(persistence.codeMatches("222222", resend.get().getCodeDigest()));
    }

    @Test
    @Order(12)
    @DisplayName("12. Maximum resends is strictly enforced (6th resend denied)")
    void test12_maximumResendsStrictlyEnforced() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-max-resend", TEST_USER_ID, "111111", clock.instant());
        persistence.getStore().tryInsert(ch);

        for (int i = 1; i <= 5; i++) {
            clock.advance(Duration.ofSeconds(31));
            Optional<PasswordResetOtpChallenge> res = persistence.tryReserveResend(
                    emailKey, "ch-max-resend", persistence.digestCode("code" + i), "k-res-" + i, "op-res-" + i, clock.instant());
            assertTrue(res.isPresent(), "Resend #" + i + " should succeed");
            assertEquals(i, res.get().getResendCount());
        }

        // 6th resend attempt must fail
        clock.advance(Duration.ofSeconds(31));
        Optional<PasswordResetOtpChallenge> denied = persistence.tryReserveResend(
                emailKey, "ch-max-resend", persistence.digestCode("code6"), "k-res-6", "op-res-6", clock.instant());
        assertTrue(denied.isEmpty());
        assertEquals(5, persistence.findByEmailKey(emailKey).orElseThrow().getResendCount());
    }

    @Test
    @Order(13)
    @DisplayName("13. Concurrent resends race: exactly one wins and claims slot")
    void test13_concurrentResendsRaceExactlyOneWins() throws Exception {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-race-resend", TEST_USER_ID, "111111", clock.instant());
        persistence.getStore().tryInsert(ch);

        clock.advance(Duration.ofSeconds(31));
        Instant now = clock.instant();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<Optional<PasswordResetOtpChallenge>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                barrier.await();
                return persistence.tryReserveResend(
                        emailKey,
                        "ch-race-resend",
                        persistence.digestCode("code-" + idx),
                        "idemp-race-" + idx,
                        "op-race-" + idx,
                        now
                );
            }));
        }

        int successCount = 0;
        for (Future<Optional<PasswordResetOtpChallenge>> f : futures) {
            if (f.get().isPresent()) {
                successCount++;
            }
        }
        executor.shutdown();

        assertEquals(1, successCount, "Exactly one concurrent resend should win the slot");
        PasswordResetOtpChallenge finalState = persistence.findByEmailKey(emailKey).orElseThrow();
        assertEquals(1, finalState.getResendCount());
    }

    // =========================================================================
    // GROUP 4: Send Lease & Provider Outcomes
    // =========================================================================

    @Test
    @Order(14)
    @DisplayName("14. Send outcome transition updates status and clears lease")
    void test14_sendOutcomeTransitionUpdatesStatusAndClearsLease() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-lease", TEST_USER_ID, "111111", clock.instant());
        persistence.getStore().tryInsert(ch);

        Optional<PasswordResetOtpChallenge> marked = persistence.markSendOutcome(
                emailKey, "op-init-1", OtpSendStatus.SENT, clock.instant());

        assertTrue(marked.isPresent());
        assertEquals(OtpSendStatus.SENT, marked.get().getSendStatus());
        assertNull(marked.get().getSendLeaseUntil());
    }

    // =========================================================================
    // GROUP 5: Verification Failures & Atomic 5th Attempt Lock
    // =========================================================================

    @Test
    @Order(15)
    @DisplayName("15. Failed verification increments count and 5th attempt atomically transitions to LOCKED")
    void test15_failedVerificationIncrementsAndFifthLocks() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-fail-lock", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        String digest = ch.getCodeDigest();

        // Attempts 1 to 4: increments count, remains ACTIVE
        for (int i = 1; i <= 4; i++) {
            Optional<PasswordResetOtpChallenge> fail = persistence.recordFailedVerification(
                    emailKey, "ch-fail-lock", digest, clock.instant());
            assertTrue(fail.isPresent());
            assertEquals(i, fail.get().getFailedVerificationAttempts());
            assertEquals(PasswordResetChallengeStatus.ACTIVE, fail.get().getStatus());
        }

        // 5th attempt: atomically sets status = LOCKED
        Optional<PasswordResetOtpChallenge> fifth = persistence.recordFailedVerification(
                emailKey, "ch-fail-lock", digest, clock.instant());
        assertTrue(fifth.isPresent());
        assertEquals(5, fifth.get().getFailedVerificationAttempts());
        assertEquals(PasswordResetChallengeStatus.LOCKED, fifth.get().getStatus());

        // 6th attempt: fails to increment because status is no longer ACTIVE and attempts >= 5
        Optional<PasswordResetOtpChallenge> sixth = persistence.recordFailedVerification(
                emailKey, "ch-fail-lock", digest, clock.instant());
        assertTrue(sixth.isEmpty());
    }

    @Test
    @Order(16)
    @DisplayName("16. Stale digest verification attempt does not increment or lock newer challenge")
    void test16_staleDigestVerificationDoesNotIncrement() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-stale-test", TEST_USER_ID, "111111", clock.instant());
        persistence.getStore().tryInsert(ch);

        String staleDigest = persistence.digestCode("000000");
        Optional<PasswordResetOtpChallenge> fail = persistence.recordFailedVerification(
                emailKey, "ch-stale-test", staleDigest, clock.instant());

        assertTrue(fail.isEmpty());
        assertEquals(0, persistence.findByEmailKey(emailKey).orElseThrow().getFailedVerificationAttempts());
    }

    @Test
    @Order(17)
    @DisplayName("17. Concurrent 5th failure requests atomically lock without exceeding max attempts")
    void test17_concurrentFifthFailureAtomicallyLocks() throws Exception {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-conc-lock", TEST_USER_ID, rawCode, clock.instant());
        ch.setFailedVerificationAttempts(4); // 4 failures already recorded
        persistence.getStore().tryInsert(ch);

        String digest = ch.getCodeDigest();
        Instant now = clock.instant();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<Optional<PasswordResetOtpChallenge>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return persistence.recordFailedVerification(emailKey, "ch-conc-lock", digest, now);
            }));
        }

        int successCount = 0;
        for (Future<Optional<PasswordResetOtpChallenge>> f : futures) {
            if (f.get().isPresent()) {
                successCount++;
            }
        }
        executor.shutdown();

        assertEquals(1, successCount, "Exactly one thread should record the 5th attempt");
        PasswordResetOtpChallenge finalState = persistence.findByEmailKey(emailKey).orElseThrow();
        assertEquals(5, finalState.getFailedVerificationAttempts());
        assertEquals(PasswordResetChallengeStatus.LOCKED, finalState.getStatus());
    }

    // =========================================================================
    // GROUP 6: Correct-Code Claim & Concurrency
    // =========================================================================

    @Test
    @Order(18)
    @DisplayName("18. Correct code claims challenge to PROCESSING with resetOperationId")
    void test18_correctCodeClaimsChallengeToProcessing() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-claim", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        Optional<PasswordResetOtpChallenge> claimed = persistence.tryClaimCorrectCode(
                emailKey, "ch-claim", ch.getCodeDigest(), "reset-op-101", clock.instant());

        assertTrue(claimed.isPresent());
        assertEquals(PasswordResetChallengeStatus.PROCESSING, claimed.get().getStatus());
        assertEquals("reset-op-101", claimed.get().getResetOperationId());
    }

    @Test
    @Order(19)
    @DisplayName("19. Concurrent claims race: exactly one claims PROCESSING")
    void test19_concurrentClaimsRaceExactlyOneWins() throws Exception {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-claim-race", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        String digest = ch.getCodeDigest();
        Instant now = clock.instant();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<Optional<PasswordResetOtpChallenge>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                barrier.await();
                return persistence.tryClaimCorrectCode(
                        emailKey, "ch-claim-race", digest, "reset-op-" + idx, now);
            }));
        }

        int claimedCount = 0;
        for (Future<Optional<PasswordResetOtpChallenge>> f : futures) {
            if (f.get().isPresent()) {
                claimedCount++;
            }
        }
        executor.shutdown();

        assertEquals(1, claimedCount, "Only one claim should succeed");
        assertEquals(PasswordResetChallengeStatus.PROCESSING, persistence.findByEmailKey(emailKey).orElseThrow().getStatus());
    }

    // =========================================================================
    // GROUP 7: Atomic Password Reset Transaction & Completion
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("20. Successful password reset updates password, increments token_version by 1, and marks CONSUMED")
    void test20_successfulPasswordResetTransaction() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-atomic-reset", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                emailKey,
                "ch-atomic-reset",
                ch.getCodeDigest(),
                "reset-op-atomic-1",
                NEW_PASSWORD,
                clock.instant()
        );

        assertTrue(result.isSuccess());
        PasswordResetOtpChallenge consumed = result.challenge();
        assertEquals(PasswordResetChallengeStatus.CONSUMED, consumed.getStatus());
        assertTrue(consumed.isPasswordUpdated());
        assertNotNull(consumed.getConsumedAt());
        assertNull(consumed.getCodeDigest());

        // Verify users table state
        String sql = "SELECT password, token_version, password_changed_at, last_password_reset_operation_id FROM users WHERE id = ?";
        jdbcTemplate.query(sql, rs -> {
            assertTrue(PasswordUtil.checkPassword(NEW_PASSWORD, rs.getString("password")));
            assertEquals(1L, rs.getLong("token_version"));
            assertNotNull(rs.getTimestamp("password_changed_at"));
            assertEquals("reset-op-atomic-1", rs.getString("last_password_reset_operation_id"));
        }, TEST_USER_ID);
    }

    @Test
    @Order(21)
    @DisplayName("21. Idempotent replay with same operationId returns alreadyCompleted without duplicate mutation")
    void test21_idempotentReplayReturnsAlreadyCompleted() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-replay", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // First execution
        PasswordResetExecutionResult first = persistence.executePasswordResetTransaction(
                emailKey, "ch-replay", ch.getCodeDigest(), "reset-op-replay", NEW_PASSWORD, clock.instant());
        assertTrue(first.isSuccess());

        // Replay with identical resetOperationId
        PasswordResetExecutionResult replay = persistence.executePasswordResetTransaction(
                emailKey, "ch-replay", null, "reset-op-replay", NEW_PASSWORD, clock.instant());
        assertTrue(replay.isAlreadyCompleted());

        // Token version remains 1, never incremented twice
        Long tokenVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM users WHERE id = ?", Long.class, TEST_USER_ID);
        assertEquals(1L, tokenVersion);
    }

    @Test
    @Order(22)
    @DisplayName("22. Different reset operation ID against CONSUMED challenge is rejected")
    void test22_differentOperationAgainstConsumedChallengeRejected() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-diff-op", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        PasswordResetExecutionResult first = persistence.executePasswordResetTransaction(
                emailKey, "ch-diff-op", ch.getCodeDigest(), "reset-op-first", NEW_PASSWORD, clock.instant());
        assertTrue(first.isSuccess());

        // Replay with different resetOperationId
        PasswordResetExecutionResult different = persistence.executePasswordResetTransaction(
                emailKey, "ch-diff-op", null, "reset-op-second", "AnotherPassword999!", clock.instant());
        assertFalse(different.isSuccess());
        assertFalse(different.isAlreadyCompleted());
        assertEquals(PasswordResetExecutionResult.Status.INVALID_CHALLENGE, different.status());

        // Password remains NEW_PASSWORD
        String storedHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(NEW_PASSWORD, storedHash));
    }

    @Test
    @Order(23)
    @DisplayName("23. Transaction rollback on user update failure: password and challenge remain unchanged")
    void test23_transactionRollbackOnUserFailure() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-rollback", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Inject simulated user-update failure during the transaction without deleting parent user
        store.setFailureInjectorBeforeUserUpdate((status, userId) -> {
            throw new RuntimeException("Simulated user update failure during transaction");
        });

        try {
            PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                    emailKey, "ch-rollback", ch.getCodeDigest(), "reset-op-fail", NEW_PASSWORD, clock.instant());

            assertFalse(result.isSuccess());
            assertEquals(PasswordResetExecutionResult.Status.TRANSACTION_FAILED, result.status());

            // Rollback ensures user password and token_version were NOT modified
            String userSql = "SELECT password, token_version, password_changed_at, last_password_reset_operation_id FROM users WHERE id = ?";
            jdbcTemplate.query(userSql, rs -> {
                assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, rs.getString("password")));
                assertFalse(PasswordUtil.checkPassword(NEW_PASSWORD, rs.getString("password")));
                assertEquals(0L, rs.getLong("token_version"));
                assertNull(rs.getTimestamp("password_changed_at"));
                assertNull(rs.getString("last_password_reset_operation_id"));
            }, TEST_USER_ID);

            // Rollback ensures challenge was NOT left in CONSUMED or corrupted state (remains ACTIVE)
            PasswordResetOtpChallenge challengeAfter = persistence.findByEmailKey(emailKey).orElseThrow();
            assertEquals(PasswordResetChallengeStatus.ACTIVE, challengeAfter.getStatus());
            assertFalse(challengeAfter.isPasswordUpdated());
            assertNull(challengeAfter.getConsumedAt());
            assertNotNull(challengeAfter.getCodeDigest());
        } finally {
            store.resetFailureInjectors();
        }
    }

    @Test
    @Order(24)
    @DisplayName("24. Transaction rollback on challenge finalization failure: user update rolls back")
    void test24_transactionRollbackOnChallengeFinalizationFailure() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-finalize-fail", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Inject simulated challenge finalization failure after user update has already executed
        store.setFailureInjectorBeforeChallengeFinalize((status, challengeId) -> {
            throw new RuntimeException("Simulated challenge finalization persistence failure");
        });

        try {
            PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                    emailKey, "ch-finalize-fail", ch.getCodeDigest(), "reset-op-finalize-fail", NEW_PASSWORD, clock.instant());

            assertFalse(result.isSuccess());
            assertEquals(PasswordResetExecutionResult.Status.TRANSACTION_FAILED, result.status());

            // Rollback ensures user update was undone: password unchanged, token_version not incremented
            String userSql = "SELECT password, token_version, password_changed_at, last_password_reset_operation_id FROM users WHERE id = ?";
            jdbcTemplate.query(userSql, rs -> {
                assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, rs.getString("password")));
                assertFalse(PasswordUtil.checkPassword(NEW_PASSWORD, rs.getString("password")));
                assertEquals(0L, rs.getLong("token_version"));
                assertNull(rs.getTimestamp("password_changed_at"));
                assertNull(rs.getString("last_password_reset_operation_id"));
            }, TEST_USER_ID);

            // Rollback ensures challenge consumption was NOT committed (remains ACTIVE, not CONSUMED)
            PasswordResetOtpChallenge challengeAfter = persistence.findByEmailKey(emailKey).orElseThrow();
            assertEquals(PasswordResetChallengeStatus.ACTIVE, challengeAfter.getStatus());
            assertFalse(challengeAfter.isPasswordUpdated());
            assertNull(challengeAfter.getConsumedAt());
            assertNotNull(challengeAfter.getCodeDigest());
        } finally {
            store.resetFailureInjectors();
        }
    }

    @Test
    @Order(25)
    @DisplayName("25. Concurrent complete calls: exactly one updates password and increments version")
    void test25_concurrentCompleteCallsExactlyOneWins() throws Exception {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-concurrent-reset", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        String digest = ch.getCodeDigest();
        Instant now = clock.instant();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<PasswordResetExecutionResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                barrier.await();
                return persistence.executePasswordResetTransaction(
                        emailKey, "ch-concurrent-reset", digest, "reset-op-conc-" + idx, "Password" + idx + "!", now);
            }));
        }

        int successCount = 0;
        for (Future<PasswordResetExecutionResult> f : futures) {
            if (f.get().isSuccess()) {
                successCount++;
            }
        }
        executor.shutdown();

        assertEquals(1, successCount, "Exactly one concurrent complete operation should succeed");
        Long finalVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM users WHERE id = ?", Long.class, TEST_USER_ID);
        assertEquals(1L, finalVersion, "token_version must increment exactly once");
    }

    @Test
    @Order(26)
    @DisplayName("26. Successful reset revokes prior JWT through token_version mismatch")
    void test26_resetRevokesPriorJwtThroughTokenVersion() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-jwt-revocation", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        String oldJwt = jwtUtil.generateToken(TEST_USER_ID, TEST_USER_EMAIL, 0L);
        JpaUser jpaUser = new JpaUser(TEST_USER_ID, "Test Member", TEST_USER_EMAIL, null, "MEMBER");
        jpaUser.setTokenVersion(0L);

        // Before reset, JWT is valid and version matches
        assertTrue(jwtUtil.validateToken(oldJwt));
        assertEquals(jpaUser.getTokenVersion(), jwtUtil.getTokenVersionFromToken(oldJwt));

        // Execute reset
        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                emailKey, "ch-jwt-revocation", ch.getCodeDigest(), "op-jwt-rev", NEW_PASSWORD, clock.instant());
        assertTrue(result.isSuccess());

        // Reload user tokenVersion from DB
        Long newVersion = jdbcTemplate.queryForObject("SELECT token_version FROM users WHERE id = ?", Long.class, TEST_USER_ID);
        assertEquals(1L, newVersion);
        jpaUser.setTokenVersion(newVersion);

        // Old JWT version does not match updated user tokenVersion
        assertNotEquals(jpaUser.getTokenVersion(), jwtUtil.getTokenVersionFromToken(oldJwt));
    }

    // =========================================================================
    // GROUP 8: Spring Context Configuration & Selection
    // =========================================================================

    @Configuration
    static class MinimalSpringContextConfig {
        @Bean
        public DataSource dataSource(PostgresPasswordResetOtpPersistenceIntegrationTest testInstance) {
            return testInstance.dataSource != null ? testInstance.dataSource : mock(DataSource.class);
        }

        @Bean
        public Clock clock(PostgresPasswordResetOtpPersistenceIntegrationTest testInstance) {
            return testInstance.clock;
        }

        @Bean
        public PasswordResetProperties properties(PostgresPasswordResetOtpPersistenceIntegrationTest testInstance) {
            return testInstance.properties;
        }

        @Bean
        public AbuseIpHasher hasher(PostgresPasswordResetOtpPersistenceIntegrationTest testInstance) {
            return testInstance.hasher;
        }

        @Bean
        public PasswordResetOtpChallengeRepository repository() {
            return mock(PasswordResetOtpChallengeRepository.class);
        }

        @Bean
        public MongoTemplate mongoTemplate() {
            return mock(MongoTemplate.class);
        }

        @Bean
        public UserRepository userRepository() {
            return mock(UserRepository.class);
        }
    }

    @Configuration
    static class MixedAuthControllerSpringContextConfig extends MinimalSpringContextConfig {
        @Bean
        public AuthController authController(
                UserRepository userRepository,
                PasswordResetService passwordResetService,
                @org.springframework.beans.factory.annotation.Value("${app.security.postgres-user-auth.enabled:false}") boolean postgresUserAuthEnabled) {
            return new AuthController(
                    mock(EmailSenderService.class),
                    userRepository,
                    mock(CustomerOrderRepository.class),
                    mock(OrderReviewRepository.class),
                    mock(JwtUtil.class),
                    mock(OtpService.class),
                    mock(SignupOtpChallengeService.class),
                    mock(SignupAbuseLimiter.class),
                    mock(ClientIpResolver.class),
                    mock(SignupOtpObservability.class),
                    mock(LoginRateLimiter.class),
                    passwordResetService,
                    mock(AuthSecurityObservability.class),
                    postgresUserAuthEnabled
            );
        }

        @Bean
        public PasswordResetService passwordResetService(
                PasswordResetOtpChallengePersistence persistence,
                PasswordResetProperties properties,
                AbuseIpHasher hasher) {
            return new PasswordResetService(
                    persistence,
                    mock(PasswordResetAbuseLimiter.class),
                    mock(PasswordResetPublicHandleService.class),
                    properties,
                    null,
                    null,
                    mock(EmailSenderService.class),
                    hasher,
                    mock(AuthSecurityObservability.class)
            );
        }
    }

    @Test
    @Order(27)
    @DisplayName("27. Spring DI selects PostgresPasswordResetOtpChallengeStore when opted-in")
    void test27_springContextDISelectsPostgresWhenOptedIn() {
        requirePostgres();
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MinimalSpringContextConfig.class,
                        PostgresPasswordResetPersistenceConfiguration.class,
                        MongoPasswordResetOtpChallengeStore.class,
                        PasswordResetOtpChallengePersistence.class
                )
                .withPropertyValues("spring.profiles.active=postgres-migration", "app.security.postgres-password-reset.enabled=true")
                .withSystemProperties("test.postgres.port=" + testPort)
                .withBean(PostgresPasswordResetOtpPersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PasswordResetOtpChallengePersistence persistenceBean = context.getBean(PasswordResetOtpChallengePersistence.class);
                    assertNotNull(persistenceBean);
                    assertThat(persistenceBean.getStore()).isInstanceOf(PostgresPasswordResetOtpChallengeStore.class);
                });
    }

    @Test
    @Order(28)
    @DisplayName("28. Spring DI selects MongoPasswordResetOtpChallengeStore by default")
    void test28_springContextDISelectsMongoByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MinimalSpringContextConfig.class,
                        PostgresPasswordResetPersistenceConfiguration.class,
                        MongoPasswordResetOtpChallengeStore.class,
                        PasswordResetOtpChallengePersistence.class
                )
                .withBean(PostgresPasswordResetOtpPersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PasswordResetOtpChallengePersistence persistenceBean = context.getBean(PasswordResetOtpChallengePersistence.class);
                    assertNotNull(persistenceBean);
                    assertThat(persistenceBean.getStore()).isInstanceOf(MongoPasswordResetOtpChallengeStore.class);
                });
    }

    @Test
    @Order(29)
    @DisplayName("29. Spring transactional proxy verification for PostgresPasswordResetOtpChallengeStore")
    void test29_springTransactionalProxyVerified() {
        requirePostgres();
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MinimalSpringContextConfig.class,
                        PostgresPasswordResetPersistenceConfiguration.class
                )
                .withPropertyValues("spring.profiles.active=postgres-migration", "app.security.postgres-password-reset.enabled=true")
                .withBean(PostgresPasswordResetOtpPersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PasswordResetOtpChallengeStore storeBean = context.getBean("postgresPasswordResetOtpChallengeStore", PasswordResetOtpChallengeStore.class);
                    assertNotNull(storeBean);
                    assertTrue(AopUtils.isAopProxy(storeBean));
                });
    }

    @Test
    @Order(30)
    @DisplayName("30. Sensitive data protection in logs: no plaintext OTP, raw password, or digests emitted")
    void test30_sensitiveDataProtectionInLogs() {
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        String rawCode = "849201";
        String rawPassword = "SuperSecretPassword123!";
        service.complete(TEST_USER_EMAIL, "hndl-fake", rawCode, rawPassword, "192.168.1.1", "k-log-test");

        for (ILoggingEvent event : appender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg).doesNotContain(rawCode);
            assertThat(msg).doesNotContain(rawPassword);
            assertThat(msg).doesNotContain("192.168.1.1");
        }
        serviceLogger.detachAppender(appender);
    }

    @Test
    @Order(31)
    @DisplayName("31. SQL parameter binding prevents injection attacks")
    void test31_sqlParameterBindingProtectsAgainstInjection() {
        requirePostgres();
        String injectionKey = "' OR '1'='1' --";
        Optional<PasswordResetOtpChallenge> found = persistence.findByEmailKey(injectionKey);
        assertTrue(found.isEmpty());
    }

    // =========================================================================
    // GROUP 9: Startup Guard & Mixed-Backend Isolation
    // =========================================================================

    @Test
    @Order(32)
    @DisplayName("32. Startup guard throws IllegalStateException when AuthController is loaded with uncoordinated Postgres reset storage")
    void test32_startupGuardRejectsMixedBackendActivation() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MixedAuthControllerSpringContextConfig.class,
                        PostgresPasswordResetPersistenceConfiguration.class,
                        MongoPasswordResetOtpChallengeStore.class,
                        PasswordResetOtpChallengePersistence.class
                )
                .withPropertyValues(
                        "spring.profiles.active=postgres-migration",
                        "app.security.postgres-password-reset.enabled=true",
                        "app.security.postgres-user-auth.enabled=false"
                )
                .withSystemProperties("test.postgres.port=" + testPort)
                .withBean(PostgresPasswordResetOtpPersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Unsupported mixed-backend activation");
                });
    }

    @Test
    @Order(33)
    @DisplayName("33. Startup guard passes when coordinated PostgreSQL user authentication is enabled")
    void test33_startupGuardPassesWithCoordinatedUserAuth() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MixedAuthControllerSpringContextConfig.class,
                        PostgresPasswordResetPersistenceConfiguration.class,
                        MongoPasswordResetOtpChallengeStore.class,
                        PasswordResetOtpChallengePersistence.class
                )
                .withPropertyValues(
                        "spring.profiles.active=postgres-migration",
                        "app.security.postgres-password-reset.enabled=true",
                        "app.security.postgres-user-auth.enabled=true"
                )
                .withSystemProperties("test.postgres.port=" + testPort)
                .withBean(PostgresPasswordResetOtpPersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    @Order(34)
    @DisplayName("34. PasswordResetService rejects requestInitial with MongoDB User when backed by PostgreSQL")
    void test34_passwordResetServiceRejectsMongoUserWhenPostgresStoreActive() {
        requirePostgres();
        User mongoUser = new User();
        mongoUser.setId("mongo-user-123");
        mongoUser.setEmail(TEST_USER_EMAIL);

        assertThatThrownBy(() -> service.requestInitial(TEST_USER_EMAIL, Optional.of(mongoUser), "127.0.0.1", "k-mixed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported mixed-backend call");
    }

    @Test
    @Order(35)
    @DisplayName("35. PasswordResetService accepts requestInitialForJpaUser when backed by PostgreSQL")
    void test35_passwordResetServiceAcceptsJpaUserWhenPostgresStoreActive() {
        requirePostgres();
        JpaUser jpaUser = new JpaUser(TEST_USER_ID, "Test Member", TEST_USER_EMAIL, null, "MEMBER");

        PasswordResetService.Result result = service.requestInitialForJpaUser(
                TEST_USER_EMAIL, Optional.of(jpaUser), "127.0.0.1", "k-jpa-success");
        assertEquals(HttpStatus.ACCEPTED, result.status());
    }

    @Test
    @Order(36)
    @DisplayName("36. AuthController endpoints reject mixed-backend invocation when reset storage is PostgreSQL")
    void test36_authControllerEndpointsRejectMixedBackendInvocation() {
        AuthController authController = new AuthController(
                mock(EmailSenderService.class),
                mock(UserRepository.class),
                mock(CustomerOrderRepository.class),
                mock(OrderReviewRepository.class),
                mock(JwtUtil.class),
                mock(OtpService.class),
                mock(SignupOtpChallengeService.class),
                mock(SignupAbuseLimiter.class),
                mock(ClientIpResolver.class),
                mock(SignupOtpObservability.class),
                mock(LoginRateLimiter.class),
                service,
                mock(AuthSecurityObservability.class),
                false
        );

        SendOtpRequest sendReq = new SendOtpRequest();
        sendReq.setEmail(TEST_USER_EMAIL);
        assertThatThrownBy(() -> authController.sendPasswordResetOtp("k-send", sendReq, mock(HttpServletRequest.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL password reset path is isolated");

        ResetPasswordRequest resetReq = new ResetPasswordRequest();
        resetReq.setEmail(TEST_USER_EMAIL);
        resetReq.setChallengeId("ch-test");
        resetReq.setOtp("123456");
        resetReq.setPassword(NEW_PASSWORD);
        assertThatThrownBy(() -> authController.resetPassword("k-reset", resetReq, mock(HttpServletRequest.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL password reset path is isolated");
    }

    // =========================================================================
    // GROUP 11: Fail-Closed Preconditions & Storage-Layer Validation
    // =========================================================================

    @Test
    @Order(37)
    @DisplayName("37. executePasswordResetTransaction rejects null expectedDigest on ACTIVE challenge fail-closed")
    void test37_executePasswordResetTransactionRejectsNullExpectedDigest() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-null-digest", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                emailKey, "ch-null-digest", null, "op-null-digest", NEW_PASSWORD, clock.instant());

        assertFalse(result.isSuccess());
        assertEquals(PasswordResetExecutionResult.Status.INVALID_CHALLENGE, result.status());

        // Password unchanged
        String storedHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, storedHash));

        // Challenge unchanged (remains ACTIVE)
        PasswordResetOtpChallenge storedCh = persistence.findByEmailKey(emailKey).orElseThrow();
        assertEquals(PasswordResetChallengeStatus.ACTIVE, storedCh.getStatus());
        assertFalse(storedCh.isPasswordUpdated());
    }

    @Test
    @Order(38)
    @DisplayName("38. executePasswordResetTransaction rejects ACTIVE challenge with null code_digest")
    void test38_executePasswordResetTransactionRejectsNullCodeDigestOnChallenge() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-null-db-digest", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Manually update challenge in DB to have null code_digest
        jdbcTemplate.update("UPDATE password_reset_otp_challenges SET code_digest = NULL WHERE email_key = ?", emailKey);

        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                emailKey, "ch-null-db-digest", persistence.digestCode(rawCode), "op-null-db-digest", NEW_PASSWORD, clock.instant());

        assertFalse(result.isSuccess());
        assertEquals(PasswordResetExecutionResult.Status.INVALID_CHALLENGE, result.status());

        // User password remains OLD_PASSWORD
        String storedHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, storedHash));
    }

    @Test
    @Order(39)
    @DisplayName("39. executePasswordResetTransaction rejects challenge with null challenge_expires_at fail-closed")
    void test39_executePasswordResetTransactionRejectsNullChallengeExpiresAt() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-null-challenge-exp", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Clear challenge_expires_at in database
        jdbcTemplate.update("UPDATE password_reset_otp_challenges SET challenge_expires_at = NULL WHERE email_key = ?", emailKey);

        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                emailKey, "ch-null-challenge-exp", ch.getCodeDigest(), "op-null-ch-exp", NEW_PASSWORD, clock.instant());

        assertFalse(result.isSuccess());
        assertEquals(PasswordResetExecutionResult.Status.CHALLENGE_EXPIRED, result.status());
        assertEquals("Challenge expiration timestamp is missing", result.failureReason());

        // User password remains OLD_PASSWORD
        String storedHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, storedHash));
    }

    @Test
    @Order(40)
    @DisplayName("40. executePasswordResetTransaction rejects ACTIVE challenge with null code_expires_at fail-closed")
    void test40_executePasswordResetTransactionRejectsNullCodeExpiresAt() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-null-code-exp", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Clear code_expires_at in database
        jdbcTemplate.update("UPDATE password_reset_otp_challenges SET code_expires_at = NULL WHERE email_key = ?", emailKey);

        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                emailKey, "ch-null-code-exp", ch.getCodeDigest(), "op-null-code-exp", NEW_PASSWORD, clock.instant());

        assertFalse(result.isSuccess());
        assertEquals(PasswordResetExecutionResult.Status.CODE_EXPIRED, result.status());
        assertEquals("Code expiration timestamp is missing", result.failureReason());

        // User password remains OLD_PASSWORD
        String storedHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, storedHash));
    }

    @Test
    @Order(41)
    @DisplayName("41. executePasswordResetTransaction rejects challenge with invalid purpose inside transaction")
    void test41_executePasswordResetTransactionRejectsInvalidPurpose() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-invalid-purpose", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Modify purpose in database to a non-PASSWORD_RESET value
        jdbcTemplate.update("UPDATE password_reset_otp_challenges SET purpose = 'SIGNUP' WHERE email_key = ?", emailKey);

        PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                emailKey, "ch-invalid-purpose", ch.getCodeDigest(), "op-invalid-purpose", NEW_PASSWORD, clock.instant());

        assertFalse(result.isSuccess());
        assertEquals(PasswordResetExecutionResult.Status.INVALID_CHALLENGE, result.status());

        // User password remains OLD_PASSWORD
        String storedHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, storedHash));
    }

    // =========================================================================
    // GROUP 12: Concurrency Races, E2E Complete, JWT Invalidation & Production JPA Transaction Manager
    // =========================================================================

    @Test
    @Order(42)
    @DisplayName("42. Concurrent resend vs complete race: strictly consistent outcome, no partial state")
    void test42_concurrentResendVersusCompleteRace() throws Exception {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-resend-comp-race", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Advance clock past resend cooldown
        clock.advance(Duration.ofSeconds(35));
        Instant now = clock.instant();

        String oldDigest = ch.getCodeDigest();
        String newDigest = persistence.digestCode("654321");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<Optional<PasswordResetOtpChallenge>> resendFuture = executor.submit(() -> {
            barrier.await();
            return persistence.tryReserveResend(emailKey, "ch-resend-comp-race", newDigest, "idemp-resend-race", "send-op-race", now);
        });

        Future<PasswordResetExecutionResult> completeFuture = executor.submit(() -> {
            barrier.await();
            return persistence.executePasswordResetTransaction(emailKey, "ch-resend-comp-race", oldDigest, "reset-op-race", NEW_PASSWORD, now);
        });

        Optional<PasswordResetOtpChallenge> resendResult = resendFuture.get(10, TimeUnit.SECONDS);
        PasswordResetExecutionResult completeResult = completeFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        PasswordResetOtpChallenge stored = persistence.findByEmailKey(emailKey).orElseThrow();
        if (completeResult.isSuccess()) {
            // Complete won: challenge is CONSUMED, password updated
            assertEquals(PasswordResetChallengeStatus.CONSUMED, stored.getStatus());
            assertTrue(stored.isPasswordUpdated());
            String storedPassword = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
            assertTrue(PasswordUtil.checkPassword(NEW_PASSWORD, storedPassword));
            // Resend must have failed
            assertTrue(resendResult.isEmpty());
        } else {
            // Resend won: digest was rotated, complete with oldDigest failed
            assertTrue(resendResult.isPresent());
            assertEquals(PasswordResetChallengeStatus.ACTIVE, stored.getStatus());
            assertEquals(newDigest, stored.getCodeDigest());
            assertEquals(PasswordResetExecutionResult.Status.INVALID_CHALLENGE, completeResult.status());
            String storedPassword = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
            assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, storedPassword));
        }
    }

    @Test
    @Order(43)
    @DisplayName("43. Concurrent replace-expired vs complete race: strictly consistent outcome")
    void test43_concurrentReplaceExpiredVersusCompleteRace() throws Exception {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge oldCh = buildChallenge(emailKey, "ch-old-comp-race", TEST_USER_ID, rawCode, clock.instant());
        // Set challenge to be expired so replacement is eligible
        oldCh.setChallengeExpiresAt(clock.instant().minusSeconds(1));
        persistence.getStore().tryInsert(oldCh);

        PasswordResetOtpChallenge newCh = buildChallenge(emailKey, "ch-fresh-comp-race", TEST_USER_ID, "654321", clock.instant());

        Instant now = clock.instant();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<Optional<PasswordResetOtpChallenge>> replaceFuture = executor.submit(() -> {
            barrier.await();
            return persistence.tryCreateOrReplaceExpired(newCh, now);
        });

        Future<PasswordResetExecutionResult> completeFuture = executor.submit(() -> {
            barrier.await();
            return persistence.executePasswordResetTransaction(emailKey, "ch-old-comp-race", oldCh.getCodeDigest(), "reset-op-replace-race", NEW_PASSWORD, now);
        });

        Optional<PasswordResetOtpChallenge> replaceResult = replaceFuture.get(10, TimeUnit.SECONDS);
        PasswordResetExecutionResult completeResult = completeFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Old challenge was expired, so complete must fail with CHALLENGE_EXPIRED or INVALID_CHALLENGE if replaced
        assertFalse(completeResult.isSuccess());
        assertTrue(replaceResult.isPresent());

        // Password remains unchanged
        String storedPassword = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, storedPassword));
    }

    @Test
    @Order(44)
    @DisplayName("44. Forced challenge-finalization failure after user update rolls back password mutation and increments")
    void test44_forcedChallengeFinalizationFailureRollsBackUserPasswordUpdate() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "123456";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-forced-fin-fail", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        // Inject hook right before finalization to force transaction rollback-only
        store.setFailureInjectorBeforeChallengeFinalize((status, challengeId) -> {
            status.setRollbackOnly();
        });

        try {
            PasswordResetExecutionResult result = persistence.executePasswordResetTransaction(
                    emailKey, "ch-forced-fin-fail", ch.getCodeDigest(), "op-forced-fin-fail", NEW_PASSWORD, clock.instant());

            assertFalse(result.isSuccess());
            assertEquals(PasswordResetExecutionResult.Status.TRANSACTION_FAILED, result.status());

            // Verify user in PostgreSQL: password unchanged, token_version remains 0
            String userSql = "SELECT password, token_version, password_changed_at, last_password_reset_operation_id FROM users WHERE id = ?";
            jdbcTemplate.query(userSql, rs -> {
                assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, rs.getString("password")));
                assertFalse(PasswordUtil.checkPassword(NEW_PASSWORD, rs.getString("password")));
                assertEquals(0L, rs.getLong("token_version"));
                assertNull(rs.getTimestamp("password_changed_at"));
                assertNull(rs.getString("last_password_reset_operation_id"));
            }, TEST_USER_ID);

            // Verify challenge row in PostgreSQL: remains ACTIVE, not CONSUMED, code_digest intact
            PasswordResetOtpChallenge stored = persistence.findByEmailKey(emailKey).orElseThrow();
            assertEquals(PasswordResetChallengeStatus.ACTIVE, stored.getStatus());
            assertFalse(stored.isPasswordUpdated());
            assertNotNull(stored.getCodeDigest());
        } finally {
            store.resetFailureInjectors();
        }
    }

    private String publicHandleFor(PasswordResetOtpChallenge ch) {
        return handleService.issue(new PasswordResetPublicHandleService.Handle(
                ch.getChallengeId(),
                ch.getEmailKey(),
                ch.getNextResendAt(),
                ch.getChallengeExpiresAt(),
                ch.getResendCount()));
    }

    @Test
    @Order(45)
    @DisplayName("45. Full PasswordResetService.complete() success through actual PostgreSQL persistence")
    void test45_fullPasswordResetServiceCompleteSuccessThroughPostgres() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "987123";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-full-service", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);

        String handle = publicHandleFor(ch);

        PasswordResetService.Result result = service.complete(
                TEST_USER_EMAIL,
                handle,
                rawCode,
                NEW_PASSWORD,
                "198.51.100.25",
                "full-service-comp-idemp"
        );

        assertEquals(HttpStatus.OK, result.status());
        assertEquals("PASSWORD_RESET_COMPLETED", result.body().getCode());

        // Verify PostgreSQL users table
        String userSql = "SELECT password, token_version, password_changed_at, last_password_reset_operation_id FROM users WHERE id = ?";
        jdbcTemplate.query(userSql, rs -> {
            assertTrue(PasswordUtil.checkPassword(NEW_PASSWORD, rs.getString("password")));
            assertEquals(1L, rs.getLong("token_version"));
            assertNotNull(rs.getTimestamp("password_changed_at"));
            assertNotNull(rs.getString("last_password_reset_operation_id"));
        }, TEST_USER_ID);

        // Verify PostgreSQL challenges table
        PasswordResetOtpChallenge stored = persistence.findByEmailKey(emailKey).orElseThrow();
        assertEquals(PasswordResetChallengeStatus.CONSUMED, stored.getStatus());
        assertTrue(stored.isPasswordUpdated());
        assertNotNull(stored.getConsumedAt());
        assertNull(stored.getCodeDigest());

        // Replay with same completion key is idempotent
        PasswordResetService.Result replay = service.complete(
                TEST_USER_EMAIL,
                handle,
                rawCode,
                NEW_PASSWORD,
                "198.51.100.25",
                "full-service-comp-idemp"
        );
        assertEquals(HttpStatus.OK, replay.status());
        assertEquals("PASSWORD_RESET_COMPLETED", replay.body().getCode());
        Long finalVersion = jdbcTemplate.queryForObject("SELECT token_version FROM users WHERE id = ?", Long.class, TEST_USER_ID);
        assertEquals(1L, finalVersion);
    }

    @Test
    @Order(46)
    @DisplayName("46. End-to-end JWT authentication behavior: active token rejected after reset, new token accepted")
    void test46_endToEndJwtAuthenticationBehaviorAfterReset() {
        requirePostgres();
        String emailKey = persistence.emailKey(TEST_USER_EMAIL);
        String rawCode = "543210";
        PasswordResetOtpChallenge ch = buildChallenge(emailKey, "ch-jwt-e2e", TEST_USER_ID, rawCode, clock.instant());
        persistence.getStore().tryInsert(ch);
        String handle = publicHandleFor(ch);

        // User initial state: tokenVersion = 0
        String initialJwt = jwtUtil.generateToken(TEST_USER_ID, TEST_USER_EMAIL, 0L);
        assertTrue(jwtUtil.validateToken(initialJwt));
        assertEquals(0L, jwtUtil.getTokenVersionFromToken(initialJwt));

        // Prior to reset, authentication with OLD_PASSWORD succeeds
        String currentPasswordHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertTrue(PasswordUtil.checkPassword(OLD_PASSWORD, currentPasswordHash));

        // Execute reset
        PasswordResetService.Result result = service.complete(
                TEST_USER_EMAIL, handle, rawCode, NEW_PASSWORD, "192.168.1.50", "jwt-e2e-idemp");
        assertEquals(HttpStatus.OK, result.status());

        // Post-reset authentication check: OLD password fails, NEW password succeeds
        String updatedPasswordHash = jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, TEST_USER_ID);
        assertFalse(PasswordUtil.checkPassword(OLD_PASSWORD, updatedPasswordHash));
        assertTrue(PasswordUtil.checkPassword(NEW_PASSWORD, updatedPasswordHash));

        // Token version incremented to 1
        Long updatedVersion = jdbcTemplate.queryForObject("SELECT token_version FROM users WHERE id = ?", Long.class, TEST_USER_ID);
        assertEquals(1L, updatedVersion);

        // Authentication evaluation: Old JWT tokenVersion (0) no longer matches updated user tokenVersion (1)
        Long oldTokenVersionFromJwt = jwtUtil.getTokenVersionFromToken(initialJwt);
        assertNotEquals(updatedVersion, oldTokenVersionFromJwt);

        // New JWT issued with new tokenVersion (1) is valid and matches
        String newJwt = jwtUtil.generateToken(TEST_USER_ID, TEST_USER_EMAIL, updatedVersion);
        assertTrue(jwtUtil.validateToken(newJwt));
        assertEquals(updatedVersion, jwtUtil.getTokenVersionFromToken(newJwt));
    }

    @Test
    @Order(47)
    @DisplayName("47. Production-style JpaTransactionManager configuration selection and integration")
    void test47_productionStyleJpaTransactionManagerSelectionAndResetExecution() {
        requirePostgres();
        EntityManagerFactory mockEmf = mock(EntityManagerFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MinimalSpringContextConfig.class,
                        PostgresPasswordResetPersistenceConfiguration.class,
                        PasswordResetOtpChallengePersistence.class
                )
                .withPropertyValues(
                        "spring.profiles.active=postgres-migration",
                        "app.security.postgres-password-reset.enabled=true"
                )
                .withBean(EntityManagerFactory.class, () -> mockEmf)
                .withBean(PostgresPasswordResetOtpPersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PlatformTransactionManager txManager = context.getBean("transactionManager", PlatformTransactionManager.class);
                    assertNotNull(txManager);
                    assertThat(txManager).isInstanceOf(JpaTransactionManager.class);

                    PostgresPasswordResetOtpChallengeStore storeBean = (PostgresPasswordResetOtpChallengeStore)
                            context.getBean("postgresPasswordResetOtpChallengeStore", PasswordResetOtpChallengeStore.class);
                    assertThat(storeBean.getTransactionManager()).isSameAs(txManager);
                });
    }
}
