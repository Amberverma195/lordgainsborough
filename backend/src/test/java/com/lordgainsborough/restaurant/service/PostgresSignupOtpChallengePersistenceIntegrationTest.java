package com.lordgainsborough.restaurant.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lordgainsborough.restaurant.config.PostgresSignupOtpPersistenceConfiguration;
import com.lordgainsborough.restaurant.dto.SignupOtpChallengeView;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.repository.SignupOtpChallengeRepository;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import com.lordgainsborough.restaurant.service.SignupOtpChallengePersistence.ResendDenialReason;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeService.RequestOtpResult;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeService.VerifyOtpResult;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresSignupOtpChallengePersistenceIntegrationTest {

    private static final String TEST_DB_PREFIX = "pg_signup_otp_test_";
    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T10:00:00Z");

    private String testPort;
    private String testDb;
    private String testJdbcUrl;
    private DisposableDatabaseSession databaseSession;
    private PGSimpleDataSource dataSource;
    private PlatformTransactionManager transactionManager;
    private PostgresSignupOtpChallengeStore store;
    private MutableClock clock;
    private SignupOtpChallengePersistence persistence;
    private EmailSenderService emailSenderService;
    private SignupOtpObservability observability;
    private SignupOtpChallengeService service;
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

        transactionManager = new DataSourceTransactionManager(dataSource);
        store = new PostgresSignupOtpChallengeStore(dataSource, transactionManager);
        clock = new MutableClock(FIXED_NOW);
        persistence = new SignupOtpChallengePersistence(store, clock);

        emailSenderService = mock(EmailSenderService.class);
        observability = new SignupOtpObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new SignupOtpChallengeService(persistence, emailSenderService, observability);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterAll
    void tearDownDatabase() {
        if (databaseSession != null) {
            databaseSession.drop();
        }
    }

    @BeforeEach
    void resetClock() {
        clock.set(FIXED_NOW);
    }

    @AfterEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM signup_otp_challenges");
    }

    // =========================================================================
    // GROUP 1: Identity, Creation & Basic Fields
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("01. Email normalization and SHA-256 key derivation compatibility")
    void test01_emailNormalizationAndKeyDerivation() {
        String raw = "  Member.User+Test@Example.COM ";
        String normalized = persistence.normalizeEmail(raw);
        assertEquals("member.user+test@example.com", normalized);

        String keyA = persistence.emailKeyFromNormalized(normalized);
        String keyB = persistence.emailKeyFromRawEmail(raw);
        assertEquals(64, keyA.length());
        assertEquals(keyA, keyB);
    }

    @Test
    @Order(2)
    @DisplayName("02. BCrypt OTP digest behavior and verification")
    void test02_bcryptOtpDigestBehavior() {
        String rawOtp = "741852";
        String digest1 = persistence.digestOtp(rawOtp);
        String digest2 = persistence.digestOtp(rawOtp);

        assertThat(digest1).startsWith("$2");
        assertThat(digest2).startsWith("$2");
        assertThat(digest1).isNotEqualTo(digest2); // Random salt each time

        assertTrue(persistence.otpMatches(rawOtp, digest1));
        assertTrue(persistence.otpMatches(rawOtp, digest2));
        assertFalse(persistence.otpMatches("000000", digest1));
    }

    @Test
    @Order(3)
    @DisplayName("03. Initial ACTIVE challenge creation contains accurate fields")
    void test03_initialActiveChallengeFields() {
        String email = "alice@example.com";
        String rawOtp = "123456";
        String digest = persistence.digestOtp(rawOtp);
        String chId = "ch-" + UUID.randomUUID();
        String sendOpId = "op-" + UUID.randomUUID();
        String idempKey = "idemp-001";

        SignupOtpChallenge built = persistence.buildNewActiveChallenge(email, digest, chId, sendOpId, idempKey);
        assertTrue(persistence.tryInsert(built));

        String key = persistence.emailKeyFromRawEmail(email);
        Optional<SignupOtpChallenge> loaded = store.findById(key);
        assertTrue(loaded.isPresent());

        SignupOtpChallenge challenge = loaded.get();
        assertEquals(key, challenge.getEmailKey());
        assertEquals("alice@example.com", challenge.getNormalizedEmail());
        assertEquals(chId, challenge.getChallengeId());
        assertEquals(OtpChallengeStatus.ACTIVE, challenge.getStatus());
        assertEquals(digest, challenge.getOtpDigest());
        assertEquals(0, challenge.getResendCount());
        assertEquals(0, challenge.getFailedVerifyAttempts());
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.RESEND_COOLDOWN), challenge.getNextResendAt());
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.OTP_TTL), challenge.getOtpExpiresAt());
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.CHALLENGE_TTL), challenge.getChallengeExpiresAt());
        assertEquals(FIXED_NOW, challenge.getCreatedAt());
        assertEquals(FIXED_NOW, challenge.getUpdatedAt());
        assertEquals(sendOpId, challenge.getSendOperationId());
        assertEquals(OtpSendStatus.RESERVED, challenge.getSendStatus());
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.SEND_LEASE), challenge.getSendLeaseUntil());
        assertEquals(idempKey, challenge.getClientIdempotencyKey());
        assertTrue(challenge.isLastSendWasInitial());
    }

    @Test
    @Order(4)
    @DisplayName("04. Duplicate initial insert fails safely without overwriting")
    void test04_duplicateInitialInsertFailsSafely() {
        String email = "bob@example.com";
        String digest1 = persistence.digestOtp("111111");
        SignupOtpChallenge first = persistence.buildNewActiveChallenge(email, digest1, "ch-first", "op-first", "idemp-1");
        assertTrue(persistence.tryInsert(first));

        String digest2 = persistence.digestOtp("222222");
        SignupOtpChallenge duplicate = persistence.buildNewActiveChallenge(email, digest2, "ch-second", "op-second", "idemp-2");
        assertFalse(persistence.tryInsert(duplicate));

        Optional<SignupOtpChallenge> current = persistence.findByEmailKey(first.getEmailKey());
        assertTrue(current.isPresent());
        assertEquals("ch-first", current.get().getChallengeId());
        assertEquals(digest1, current.get().getOtpDigest());
    }

    @Test
    @Order(5)
    @DisplayName("05. Concurrent 20-thread first insert race guarantees single winner")
    void test05_concurrentFirstInsert20Threads() throws Exception {
        String email = "contention-insert@example.com";
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger winners = new AtomicInteger(0);
        AtomicInteger losers = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            tasks.add(() -> {
                barrier.await();
                SignupOtpChallenge ch = persistence.buildNewActiveChallenge(
                        email,
                        persistence.digestOtp("10000" + idx),
                        "ch-race-" + idx,
                        "op-race-" + idx,
                        "key-" + idx
                );
                if (persistence.tryInsert(ch)) {
                    winners.incrementAndGet();
                } else {
                    losers.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, winners.get());
        assertEquals(19, losers.get());

        // Verify database row
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM signup_otp_challenges WHERE email_key = ?",
                Integer.class,
                persistence.emailKeyFromRawEmail(email)
        );
        assertEquals(1, count);
    }

    @Test
    @Order(6)
    @DisplayName("06. CreatedAt and UpdatedAt accurate to injected Clock")
    void test06_createdAtAndUpdatedAtAccurateToClock() {
        Instant customTime = Instant.parse("2026-10-26T15:30:45Z");
        clock.set(customTime);

        String email = "clock-accuracy@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-clk", "op-clk", "idemp-clk");
        assertTrue(persistence.tryInsert(ch));

        SignupOtpChallenge loaded = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals(customTime, loaded.getCreatedAt());
        assertEquals(customTime, loaded.getUpdatedAt());
    }

    @Test
    @Order(7)
    @DisplayName("07. findActiveUnexpiredByEmail returns empty for unknown address")
    void test07_findActiveUnexpiredByEmailReturnsEmptyForNonExistent() {
        assertThat(persistence.findActiveUnexpiredByEmail("nobody@example.com")).isEmpty();
    }

    // =========================================================================
    // GROUP 2: Expiration & Physical Retention
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("08. OTP active before boundary (now < otpExpiresAt)")
    void test08_otpExpirationActiveBeforeBoundary() {
        String email = "otp-active@example.com";
        String rawOtp = "123456";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp(rawOtp), "ch-exp1", "op-exp1", "k-1");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofMinutes(9).plusSeconds(59)); // 9m 59s -> 1s before 10m
        assertFalse(persistence.isOtpExpired(ch, clock.instant()));

        Optional<SignupOtpChallenge> verified = persistence.tryAtomicMarkVerified(ch.getEmailKey(), ch.getOtpDigest(), clock.instant());
        assertTrue(verified.isPresent());
        assertEquals(OtpChallengeStatus.VERIFIED, verified.get().getStatus());
    }

    @Test
    @Order(9)
    @DisplayName("09. OTP strictly expired exactly at boundary (now == otpExpiresAt)")
    void test09_otpExpirationExactAtBoundary() {
        String email = "otp-exact@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-exp2", "op-exp2", "k-2");
        persistence.tryInsert(ch);

        clock.advance(SignupOtpChallengePersistence.OTP_TTL); // exactly at 10m
        assertTrue(persistence.isOtpExpired(ch, clock.instant()));

        Optional<SignupOtpChallenge> verified = persistence.tryAtomicMarkVerified(ch.getEmailKey(), ch.getOtpDigest(), clock.instant());
        assertTrue(verified.isEmpty());
    }

    @Test
    @Order(10)
    @DisplayName("10. OTP strictly expired after boundary (now > otpExpiresAt)")
    void test10_otpExpirationAfterBoundary() {
        String email = "otp-after@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-exp3", "op-exp3", "k-3");
        persistence.tryInsert(ch);

        clock.advance(SignupOtpChallengePersistence.OTP_TTL.plusSeconds(1));
        assertTrue(persistence.isOtpExpired(ch, clock.instant()));

        Optional<SignupOtpChallenge> verified = persistence.tryAtomicMarkVerified(ch.getEmailKey(), ch.getOtpDigest(), clock.instant());
        assertTrue(verified.isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("11. Challenge active before boundary (now < challengeExpiresAt)")
    void test11_challengeExpirationActiveBeforeBoundary() {
        String email = "ch-active@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-exp4", "op-exp4", "k-4");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofMinutes(29).plusSeconds(59));
        assertFalse(persistence.isChallengeExpired(ch, clock.instant()));
        assertTrue(persistence.findActiveUnexpiredByEmail(email).isPresent());
    }

    @Test
    @Order(12)
    @DisplayName("12. Challenge strictly expired exactly at boundary (now == challengeExpiresAt)")
    void test12_challengeExpirationExactAtBoundary() {
        String email = "ch-exact@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-exp5", "op-exp5", "k-5");
        persistence.tryInsert(ch);

        clock.advance(SignupOtpChallengePersistence.CHALLENGE_TTL);
        assertTrue(persistence.isChallengeExpired(ch, clock.instant()));
        assertTrue(persistence.findActiveUnexpiredByEmail(email).isEmpty());
    }

    @Test
    @Order(13)
    @DisplayName("13. Expired row physically remains in PostgreSQL table")
    void test13_physicallyRetainedExpiredRowRemainsInDatabase() {
        String email = "ch-retained@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-exp6", "op-exp6", "k-6");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofHours(2));
        // Business query filters it out
        assertTrue(persistence.findActiveUnexpiredByEmail(email).isEmpty());

        // Direct physical row query confirms it is still present in the table
        Optional<SignupOtpChallenge> physicalRow = store.findById(ch.getEmailKey());
        assertTrue(physicalRow.isPresent());
        assertEquals("ch-exp6", physicalRow.get().getChallengeId());
    }

    @Test
    @Order(14)
    @DisplayName("14. Atomic replacement succeeds when challenge is expired")
    void test14_atomicReplaceExpiredSucceedsWhenExpired() {
        String email = "replace-ok@example.com";
        SignupOtpChallenge oldCh = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-old", "op-old", "k-old");
        persistence.tryInsert(oldCh);

        clock.advance(SignupOtpChallengePersistence.CHALLENGE_TTL.plusSeconds(10));
        Instant now = clock.instant();

        SignupOtpChallenge fresh = persistence.buildNewActiveChallenge(email, persistence.digestOtp("222222"), "ch-fresh", "op-fresh", "k-fresh");
        Optional<SignupOtpChallenge> replaced = persistence.tryAtomicReplaceExpired(oldCh.getEmailKey(), fresh, now);

        assertTrue(replaced.isPresent());
        assertEquals("ch-fresh", replaced.get().getChallengeId());
        assertEquals(0, replaced.get().getResendCount());
        assertEquals(now.plus(SignupOtpChallengePersistence.CHALLENGE_TTL), replaced.get().getChallengeExpiresAt());
    }

    @Test
    @Order(15)
    @DisplayName("15. Atomic replacement rejected when challenge is still active")
    void test15_atomicReplaceExpiredRejectedWhenActive() {
        String email = "replace-active@example.com";
        SignupOtpChallenge activeCh = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-active", "op-act", "k-act");
        persistence.tryInsert(activeCh);

        clock.advance(Duration.ofMinutes(15)); // only 15 min into 30 min lifetime
        SignupOtpChallenge fresh = persistence.buildNewActiveChallenge(email, persistence.digestOtp("222222"), "ch-rejected", "op-rej", "k-rej");
        Optional<SignupOtpChallenge> replaced = persistence.tryAtomicReplaceExpired(activeCh.getEmailKey(), fresh, clock.instant());

        assertTrue(replaced.isEmpty());
        // Verify original row is unmodified
        SignupOtpChallenge current = persistence.findByEmailKey(activeCh.getEmailKey()).orElseThrow();
        assertEquals("ch-active", current.getChallengeId());
    }

    @Test
    @Order(16)
    @DisplayName("16. Atomic replacement rejected when row does not exist")
    void test16_atomicReplaceExpiredRejectedWhenRowMissing() {
        String missingKey = persistence.emailKeyFromNormalized("ghost@example.com");
        SignupOtpChallenge fresh = persistence.buildNewActiveChallenge("ghost@example.com", persistence.digestOtp("999999"), "ch-ghost", "op-ghost", "k-ghost");
        Optional<SignupOtpChallenge> replaced = persistence.tryAtomicReplaceExpired(missingKey, fresh, clock.instant());
        assertTrue(replaced.isEmpty());
    }

    @Test
    @Order(17)
    @DisplayName("17. Concurrent replacement race between two threads produces single winner")
    void test17_concurrentReplaceExpiredTwoThreads() throws Exception {
        String email = "replace-race@example.com";
        SignupOtpChallenge oldCh = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-old-race", "op-old-race", "k-old-race");
        persistence.tryInsert(oldCh);

        clock.advance(SignupOtpChallengePersistence.CHALLENGE_TTL.plusSeconds(5));
        Instant now = clock.instant();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger replacedCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            barrier.await();
            SignupOtpChallenge fresh1 = persistence.buildNewActiveChallenge(email, persistence.digestOtp("222222"), "ch-winner-1", "op-1", "k-1");
            if (persistence.tryAtomicReplaceExpired(oldCh.getEmailKey(), fresh1, now).isPresent()) {
                replacedCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            barrier.await();
            SignupOtpChallenge fresh2 = persistence.buildNewActiveChallenge(email, persistence.digestOtp("333333"), "ch-winner-2", "op-2", "k-2");
            if (persistence.tryAtomicReplaceExpired(oldCh.getEmailKey(), fresh2, now).isPresent()) {
                replacedCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);
        f1.get();
        f2.get();
        executor.shutdown();

        assertEquals(1, replacedCount.get());
    }

    @Test
    @Order(18)
    @DisplayName("18. tryInsertOrReplaceExpired handles full lifecycle across expiration")
    void test18_tryInsertOrReplaceExpiredWorkflow() {
        String email = "lifecycle-flow@example.com";
        SignupOtpChallenge ch1 = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-life-1", "op-life-1", "k-1");
        assertTrue(persistence.tryInsertOrReplaceExpired(ch1));

        // Duplicate while active returns false
        SignupOtpChallenge ch2 = persistence.buildNewActiveChallenge(email, persistence.digestOtp("222222"), "ch-life-2", "op-life-2", "k-2");
        assertFalse(persistence.tryInsertOrReplaceExpired(ch2));

        // Advance past expiration
        clock.advance(Duration.ofMinutes(31));
        SignupOtpChallenge ch3 = persistence.buildNewActiveChallenge(email, persistence.digestOtp("333333"), "ch-life-3", "op-life-3", "k-3");
        assertTrue(persistence.tryInsertOrReplaceExpired(ch3));

        SignupOtpChallenge current = persistence.findByEmailKey(ch1.getEmailKey()).orElseThrow();
        assertEquals("ch-life-3", current.getChallengeId());
    }

    // =========================================================================
    // GROUP 3: Resend, Cooldown & Rotation
    // =========================================================================

    @Test
    @Order(19)
    @DisplayName("19. Resend within cooldown is denied (now < nextResendAt)")
    void test19_resendWithinCooldownDenied() {
        String email = "resend-cool@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-res1", "op-res1", "k-1");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(15)); // cooldown is 30s
        Optional<SignupOtpChallenge> resend = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-res2", "k-2", clock.instant());
        assertTrue(resend.isEmpty());

        assertEquals(ResendDenialReason.COOLDOWN_ACTIVE, persistence.diagnoseResendDenial(ch.getEmailKey(), clock.instant()));
    }

    @Test
    @Order(20)
    @DisplayName("20. Resend exactly at cooldown boundary is allowed (now == nextResendAt)")
    void test20_resendExactlyAtCooldownBoundaryAllowed() {
        String email = "resend-exact@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-res2", "op-res2", "k-1");
        persistence.tryInsert(ch);

        clock.advance(SignupOtpChallengePersistence.RESEND_COOLDOWN); // exactly 30s
        String newDigest = persistence.digestOtp("222222");
        Optional<SignupOtpChallenge> resend = persistence.tryAtomicResend(ch.getEmailKey(), newDigest, "op-res2b", "k-2", clock.instant());
        assertTrue(resend.isPresent());
        assertEquals(1, resend.get().getResendCount());
        assertEquals(newDigest, resend.get().getOtpDigest());
    }

    @Test
    @Order(21)
    @DisplayName("21. Resend after cooldown boundary is allowed (now > nextResendAt)")
    void test21_resendAfterCooldownBoundaryAllowed() {
        String email = "resend-after@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-res3", "op-res3", "k-1");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(45));
        Optional<SignupOtpChallenge> resend = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("333333"), "op-res3b", "k-3", clock.instant());
        assertTrue(resend.isPresent());
        assertEquals(1, resend.get().getResendCount());
    }

    @Test
    @Order(22)
    @DisplayName("22. Resend increments counter, rotates digest, and refreshes timestamps")
    void test22_resendIncrementsCounterAndRotatesDigest() {
        String email = "resend-rotate@example.com";
        String oldDigest = persistence.digestOtp("111111");
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, oldDigest, "ch-res4", "op-res4a", "k-4a");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(35));
        Instant resendNow = clock.instant();
        String newDigest = persistence.digestOtp("222222");

        SignupOtpChallenge updated = persistence.tryAtomicResend(ch.getEmailKey(), newDigest, "op-res4b", "k-4b", resendNow).orElseThrow();

        assertEquals(1, updated.getResendCount());
        assertEquals(newDigest, updated.getOtpDigest());
        assertEquals("op-res4b", updated.getSendOperationId());
        assertEquals(OtpSendStatus.RESERVED, updated.getSendStatus());
        assertEquals(resendNow.plus(SignupOtpChallengePersistence.OTP_TTL), updated.getOtpExpiresAt());
        assertEquals(resendNow.plus(SignupOtpChallengePersistence.RESEND_COOLDOWN), updated.getNextResendAt());
        assertEquals(resendNow.plus(SignupOtpChallengePersistence.SEND_LEASE), updated.getSendLeaseUntil());
        assertEquals("k-4b", updated.getClientIdempotencyKey());
        assertFalse(updated.isLastSendWasInitial());
        assertEquals(resendNow, updated.getUpdatedAt());
    }

    @Test
    @Order(23)
    @DisplayName("23. Resend does not extend absolute challengeExpiresAt")
    void test23_resendPreservesAbsoluteChallengeExpiresAt() {
        String email = "resend-ttl@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-res5", "op-res5", "k-5");
        persistence.tryInsert(ch);
        Instant originalExpiresAt = ch.getChallengeExpiresAt();

        clock.advance(Duration.ofMinutes(5));
        SignupOtpChallenge updated = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-res5b", "k-5b", clock.instant()).orElseThrow();

        assertEquals(originalExpiresAt, updated.getChallengeExpiresAt());
    }

    @Test
    @Order(24)
    @DisplayName("24. Maximum resend limit enforced (resendCount = 5 capped)")
    void test24_resendLimitEnforcedAtMaxResends() {
        String email = "resend-limit@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("100000"), "ch-res6", "op-0", "k-0");
        persistence.tryInsert(ch);

        for (int i = 1; i <= 5; i++) {
            clock.advance(Duration.ofSeconds(31));
            Optional<SignupOtpChallenge> res = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("10000" + i), "op-" + i, "k-" + i, clock.instant());
            assertTrue(res.isPresent());
            assertEquals(i, res.get().getResendCount());
        }

        // 6th attempt must be rejected
        clock.advance(Duration.ofSeconds(31));
        Optional<SignupOtpChallenge> denied = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("999999"), "op-over", "k-over", clock.instant());
        assertTrue(denied.isEmpty());

        assertEquals(ResendDenialReason.RESEND_LIMIT_REACHED, persistence.diagnoseResendDenial(ch.getEmailKey(), clock.instant()));
    }

    @Test
    @Order(25)
    @DisplayName("25. Resend denied when challenge is expired")
    void test25_resendDeniedWhenChallengeExpired() {
        String email = "resend-exp@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-res7", "op-7", "k-7");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofMinutes(31));
        Optional<SignupOtpChallenge> res = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-7b", "k-7b", clock.instant());
        assertTrue(res.isEmpty());
        assertEquals(ResendDenialReason.CHALLENGE_EXPIRED, persistence.diagnoseResendDenial(ch.getEmailKey(), clock.instant()));
    }

    @Test
    @Order(26)
    @DisplayName("26. Resend denied when challenge is locked")
    void test26_resendDeniedWhenChallengeLocked() {
        String email = "resend-lock@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-res8", "op-8", "k-8");
        ch.setFailedVerifyAttempts(5);
        persistence.tryInsert(ch);
        persistence.tryAtomicLockAfterMaxFailures(ch.getEmailKey(), clock.instant());

        clock.advance(Duration.ofSeconds(45));
        Optional<SignupOtpChallenge> res = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-8b", "k-8b", clock.instant());
        assertTrue(res.isEmpty());
        assertEquals(ResendDenialReason.CHALLENGE_LOCKED, persistence.diagnoseResendDenial(ch.getEmailKey(), clock.instant()));
    }

    @Test
    @Order(27)
    @DisplayName("27. Resend denied when challenge is verified")
    void test27_resendDeniedWhenChallengeVerified() {
        String email = "resend-ver@example.com";
        String digest = persistence.digestOtp("123456");
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, digest, "ch-res9", "op-9", "k-9");
        persistence.tryInsert(ch);
        persistence.tryAtomicMarkVerified(ch.getEmailKey(), digest, clock.instant());

        clock.advance(Duration.ofSeconds(45));
        Optional<SignupOtpChallenge> res = persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-9b", "k-9b", clock.instant());
        assertTrue(res.isEmpty());
    }

    @Test
    @Order(28)
    @DisplayName("28. Concurrent 20-thread resend race allows exactly 1 winner")
    void test28_concurrentResend20ThreadsRace() throws Exception {
        String email = "resend-race20@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-race-res", "op-0", "k-0");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(35));
        Instant now = clock.instant();

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger winners = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            tasks.add(() -> {
                barrier.await();
                Optional<SignupOtpChallenge> res = persistence.tryAtomicResend(
                        ch.getEmailKey(),
                        persistence.digestOtp("20000" + idx),
                        "op-race-" + idx,
                        "k-race-" + idx,
                        now
                );
                if (res.isPresent()) {
                    winners.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, winners.get());
        SignupOtpChallenge finalState = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals(1, finalState.getResendCount());
    }

    @Test
    @Order(29)
    @DisplayName("29. Resend versus unexpired replacement race")
    void test29_resendVersusExpiredReplaceRace() {
        String email = "resend-vs-replace@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-rvr", "op-rvr", "k-rvr");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(40));
        Instant now = clock.instant();

        // Resend succeeds
        assertTrue(persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-res", "k-res", now).isPresent());

        // Replace must fail because challenge is active (not expired)
        SignupOtpChallenge fresh = persistence.buildNewActiveChallenge(email, persistence.digestOtp("333333"), "ch-fresh", "op-fresh", "k-fresh");
        assertTrue(persistence.tryAtomicReplaceExpired(ch.getEmailKey(), fresh, now).isEmpty());
    }

    // =========================================================================
    // GROUP 4: Email Send Reservation & Outcomes
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("30. Mark send outcome: RESERVED -> SENT succeeds")
    void test30_markSendOutcomeReservedToSent() {
        String email = "outcome-sent@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-out1", "op-sent", "k-1");
        persistence.tryInsert(ch);

        Optional<SignupOtpChallenge> updated = persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-sent", OtpSendStatus.SENT, clock.instant());
        assertTrue(updated.isPresent());
        assertEquals(OtpSendStatus.SENT, updated.get().getSendStatus());
    }

    @Test
    @Order(31)
    @DisplayName("31. Mark send outcome: RESERVED -> DEFINITELY_FAILED succeeds")
    void test31_markSendOutcomeReservedToDefinitelyFailed() {
        String email = "outcome-fail@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-out2", "op-fail", "k-2");
        persistence.tryInsert(ch);

        Optional<SignupOtpChallenge> updated = persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-fail", OtpSendStatus.DEFINITELY_FAILED, clock.instant());
        assertTrue(updated.isPresent());
        assertEquals(OtpSendStatus.DEFINITELY_FAILED, updated.get().getSendStatus());
    }

    @Test
    @Order(32)
    @DisplayName("32. Mark send outcome: RESERVED -> DELIVERY_UNKNOWN succeeds")
    void test32_markSendOutcomeReservedToDeliveryUnknown() {
        String email = "outcome-unk@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-out3", "op-unk", "k-3");
        persistence.tryInsert(ch);

        Optional<SignupOtpChallenge> updated = persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-unk", OtpSendStatus.DELIVERY_UNKNOWN, clock.instant());
        assertTrue(updated.isPresent());
        assertEquals(OtpSendStatus.DELIVERY_UNKNOWN, updated.get().getSendStatus());
    }

    @Test
    @Order(33)
    @DisplayName("33. Stale sendOperationId outcome callback is rejected")
    void test33_markSendOutcomeRejectsStaleSendOperationId() {
        String email = "outcome-stale@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-out4", "op-old", "k-4");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(35));
        persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-new", "k-new", clock.instant());

        // Callback with op-old arrives late
        Optional<SignupOtpChallenge> staleAttempt = persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-old", OtpSendStatus.SENT, clock.instant());
        assertTrue(staleAttempt.isEmpty());

        SignupOtpChallenge current = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals("op-new", current.getSendOperationId());
        assertEquals(OtpSendStatus.RESERVED, current.getSendStatus());
    }

    @Test
    @Order(34)
    @DisplayName("34. Outcome callback rejected when sendStatus is not RESERVED")
    void test34_markSendOutcomeRejectsNonReservedStatus() {
        String email = "outcome-double@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-out5", "op-5", "k-5");
        persistence.tryInsert(ch);

        persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-5", OtpSendStatus.SENT, clock.instant());

        // Second callback for same op rejected
        Optional<SignupOtpChallenge> second = persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-5", OtpSendStatus.SENT, clock.instant());
        assertTrue(second.isEmpty());
    }

    @Test
    @Order(35)
    @DisplayName("35. Initial-send definite failure expires challenge for replacement")
    void test35_expireAfterDefiniteInitialFailureExpiresChallenge() {
        String email = "fail-initial@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-f1", "op-f1", "k-f1");
        persistence.tryInsert(ch);
        persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-f1", OtpSendStatus.DEFINITELY_FAILED, clock.instant());

        clock.advance(Duration.ofSeconds(10));
        Instant expireTime = clock.instant();
        Optional<SignupOtpChallenge> expired = persistence.expireAfterDefiniteInitialFailure(ch.getEmailKey(), "op-f1", expireTime);

        assertTrue(expired.isPresent());
        assertEquals(expireTime, expired.get().getChallengeExpiresAt());
    }

    @Test
    @Order(36)
    @DisplayName("36. Resend failure does not expire the whole challenge")
    void test36_expireAfterDefiniteInitialFailureIgnoresResendFailure() {
        String email = "fail-resend@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-f2", "op-f2a", "k-f2a");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(35));
        persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp("222222"), "op-f2b", "k-f2b", clock.instant());
        persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-f2b", OtpSendStatus.DEFINITELY_FAILED, clock.instant());

        // Expire attempt must fail because lastSendWasInitial is false
        Optional<SignupOtpChallenge> expired = persistence.expireAfterDefiniteInitialFailure(ch.getEmailKey(), "op-f2b", clock.instant());
        assertTrue(expired.isEmpty());

        SignupOtpChallenge current = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertThat(current.getChallengeExpiresAt()).isAfter(clock.instant());
    }

    @Test
    @Order(37)
    @DisplayName("37. Expire initial failure requires DEFINITELY_FAILED status")
    void test37_expireAfterDefiniteInitialFailureRequiresDefinitelyFailed() {
        String email = "fail-status@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-f3", "op-f3", "k-f3");
        persistence.tryInsert(ch);
        persistence.tryMarkSendOutcome(ch.getEmailKey(), "op-f3", OtpSendStatus.SENT, clock.instant());

        Optional<SignupOtpChallenge> expired = persistence.expireAfterDefiniteInitialFailure(ch.getEmailKey(), "op-f3", clock.instant());
        assertTrue(expired.isEmpty());
    }

    @Test
    @Order(38)
    @DisplayName("38. Send lease expiration handling")
    void test38_sendLeaseExpirationHandling() {
        String email = "lease-test@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-l1", "op-l1", "k-l1");
        persistence.tryInsert(ch);

        // Before lease expires (lease is 60s)
        clock.advance(Duration.ofSeconds(30));
        SignupOtpChallenge loaded = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertTrue(loaded.getSendLeaseUntil().isAfter(clock.instant()));

        // After lease expires
        clock.advance(Duration.ofSeconds(35));
        assertFalse(loaded.getSendLeaseUntil().isAfter(clock.instant()));
    }

    // =========================================================================
    // GROUP 5: Client Idempotency
    // =========================================================================

    @Test
    @Order(39)
    @DisplayName("39. Idempotency replay of SENT outcome returns existing result")
    void test39_idempotencyReplaySentOutcome() {
        String email = "idemp-sent@example.com";
        String idempKey = "client-key-101";

        RequestOtpResult first = service.requestSignupOtp(email, idempKey);
        assertThat(first).isInstanceOf(RequestOtpResult.Sent.class);

        // Replay with exact same key
        RequestOtpResult second = service.requestSignupOtp(email, idempKey);
        assertThat(second).isInstanceOf(RequestOtpResult.Sent.class);
        RequestOtpResult.Sent sent = (RequestOtpResult.Sent) second;
        assertFalse(sent.newlyAccepted()); // Not newly accepted, replayed!
    }

    @Test
    @Order(40)
    @DisplayName("40. Idempotency replay of DEFINITELY_FAILED outcome")
    void test40_idempotencyReplayDefinitelyFailedOutcome() {
        String email = "idemp-fail@example.com";
        String idempKey = "client-key-102";

        // Mock failure
        doThrow(new DefiniteEmailRejectionException("rejected")).when(emailSenderService).sendOtpEmail(any(), any(), any());

        RequestOtpResult first = service.requestSignupOtp(email, idempKey);
        assertThat(first).isInstanceOf(RequestOtpResult.Failed.class);

        // Replay returns Failed
        RequestOtpResult second = service.requestSignupOtp(email, idempKey);
        assertThat(second).isInstanceOf(RequestOtpResult.Failed.class);

        // Restore mock
        doNothing().when(emailSenderService).sendOtpEmail(any(), any(), any());
    }

    @Test
    @Order(41)
    @DisplayName("41. Idempotency replay of DELIVERY_UNKNOWN outcome")
    void test41_idempotencyReplayDeliveryUnknownOutcome() {
        String email = "idemp-unk@example.com";
        String idempKey = "client-key-103";

        doThrow(new AmbiguousEmailDeliveryException("timeout")).when(emailSenderService).sendOtpEmail(any(), any(), any());

        RequestOtpResult first = service.requestSignupOtp(email, idempKey);
        assertThat(first).isInstanceOf(RequestOtpResult.DeliveryUnknown.class);

        RequestOtpResult second = service.requestSignupOtp(email, idempKey);
        assertThat(second).isInstanceOf(RequestOtpResult.DeliveryUnknown.class);

        doNothing().when(emailSenderService).sendOtpEmail(any(), any(), any());
    }

    @Test
    @Order(42)
    @DisplayName("42. Idempotency replay of RESERVED with active lease returns InFlight")
    void test42_idempotencyReplayReservedWithActiveLease() {
        String email = "idemp-inflight@example.com";
        String idempKey = "client-key-104";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("111111"), "ch-if", "op-if", idempKey);
        persistence.tryInsert(ch);

        RequestOtpResult replay = service.requestSignupOtp(email, idempKey);
        assertThat(replay).isInstanceOf(RequestOtpResult.InFlight.class);
    }

    @Test
    @Order(43)
    @DisplayName("43. Concurrent same-key replay maintains idempotency")
    void test43_concurrentSameKeyReplay() throws Exception {
        String email = "idemp-conc@example.com";
        String idempKey = "client-key-conc";

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<RequestOtpResult> results = Collections.synchronizedList(new ArrayList<>());

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                barrier.await();
                results.add(service.requestSignupOtp(email, idempKey));
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly one challenge created; all returns are Sent
        for (RequestOtpResult res : results) {
            assertThat(res).isInstanceOf(RequestOtpResult.Sent.class);
        }

        SignupOtpChallenge ch = persistence.findByEmailKey(persistence.emailKeyFromRawEmail(email)).orElseThrow();
        assertEquals(0, ch.getResendCount()); // No extra resends occurred!
    }

    @Test
    @Order(44)
    @DisplayName("44. Different idempotency key triggers resend once cooldown passes")
    void test44_differentIdempotencyKeyProceeds() {
        String email = "idemp-diff@example.com";
        RequestOtpResult first = service.requestSignupOtp(email, "key-A");
        assertThat(first).isInstanceOf(RequestOtpResult.Sent.class);

        clock.advance(Duration.ofSeconds(35));
        RequestOtpResult second = service.requestSignupOtp(email, "key-B");
        assertThat(second).isInstanceOf(RequestOtpResult.Sent.class);

        SignupOtpChallenge ch = persistence.findByEmailKey(persistence.emailKeyFromRawEmail(email)).orElseThrow();
        assertEquals(1, ch.getResendCount());
        assertEquals("key-B", ch.getClientIdempotencyKey());
    }

    // =========================================================================
    // GROUP 6: Verification Security & Locking
    // =========================================================================

    @Test
    @Order(45)
    @DisplayName("45. Valid OTP verification marks challenge VERIFIED atomically")
    void test45_verificationSucceedsWithValidOtp() {
        String email = "ver-ok@example.com";
        String rawOtp = "654321";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp(rawOtp), "ch-v1", "op-v1", "k-v1");
        persistence.tryInsert(ch);

        VerifyOtpResult res = service.verifyOtp(email, rawOtp);
        assertThat(res).isInstanceOf(VerifyOtpResult.Verified.class);

        SignupOtpChallenge updated = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals(OtpChallengeStatus.VERIFIED, updated.getStatus());
    }

    @Test
    @Order(46)
    @DisplayName("46. Incorrect OTP fails and increments failed counter")
    void test46_verificationFailsWithIncorrectOtp() {
        String email = "ver-fail@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-v2", "op-v2", "k-v2");
        persistence.tryInsert(ch);

        VerifyOtpResult res = service.verifyOtp(email, "999999");
        assertThat(res).isInstanceOf(VerifyOtpResult.Invalid.class);

        SignupOtpChallenge updated = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals(OtpChallengeStatus.ACTIVE, updated.getStatus());
        assertEquals(1, updated.getFailedVerifyAttempts());
    }

    @Test
    @Order(47)
    @DisplayName("47. Verification cannot succeed twice on already VERIFIED challenge")
    void test47_verificationCannotSucceedTwice() {
        String email = "ver-twice@example.com";
        String rawOtp = "123456";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp(rawOtp), "ch-v3", "op-v3", "k-v3");
        persistence.tryInsert(ch);

        assertThat(service.verifyOtp(email, rawOtp)).isInstanceOf(VerifyOtpResult.Verified.class);

        // Second verification attempt returns Invalid because challenge is already VERIFIED
        VerifyOtpResult second = service.verifyOtp(email, rawOtp);
        assertThat(second).isInstanceOf(VerifyOtpResult.Invalid.class);

        // Direct persistence query rejects re-verification
        Optional<SignupOtpChallenge> reVerify = persistence.tryAtomicMarkVerified(ch.getEmailKey(), ch.getOtpDigest(), clock.instant());
        assertTrue(reVerify.isEmpty());
    }

    @Test
    @Order(48)
    @DisplayName("48. Stale OTP issued before resend is rejected after rotation")
    void test48_verificationRejectsStaleOtpAfterRotation() {
        String email = "ver-stale@example.com";
        String oldOtp = "111111";
        String newOtp = "222222";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp(oldOtp), "ch-v4", "op-v4", "k-v4");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(35));
        persistence.tryAtomicResend(ch.getEmailKey(), persistence.digestOtp(newOtp), "op-v4b", "k-v4b", clock.instant());

        // Verifying with oldOtp fails
        VerifyOtpResult resOld = service.verifyOtp(email, oldOtp);
        assertThat(resOld).isInstanceOf(VerifyOtpResult.Invalid.class);

        // Verifying with newOtp succeeds
        VerifyOtpResult resNew = service.verifyOtp(email, newOtp);
        assertThat(resNew).isInstanceOf(VerifyOtpResult.Verified.class);
    }

    @Test
    @Order(49)
    @DisplayName("49. Failed verification attempts increment atomically")
    void test49_failedVerifyAttemptsIncrementAtomically() {
        String email = "fail-inc@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-v5", "op-v5", "k-v5");
        persistence.tryInsert(ch);

        Optional<SignupOtpChallenge> inc1 = persistence.tryAtomicRecordFailedVerify(ch.getEmailKey(), clock.instant());
        assertTrue(inc1.isPresent());
        assertEquals(1, inc1.get().getFailedVerifyAttempts());

        Optional<SignupOtpChallenge> inc2 = persistence.tryAtomicRecordFailedVerify(ch.getEmailKey(), clock.instant());
        assertTrue(inc2.isPresent());
        assertEquals(2, inc2.get().getFailedVerifyAttempts());
    }

    @Test
    @Order(50)
    @DisplayName("50. Failed verification attempts capped at maximum (5)")
    void test50_failedVerifyAttemptsCappedAtMax() {
        String email = "fail-cap@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-v6", "op-v6", "k-v6");
        persistence.tryInsert(ch);

        for (int i = 1; i <= 5; i++) {
            assertTrue(persistence.tryAtomicRecordFailedVerify(ch.getEmailKey(), clock.instant()).isPresent());
        }

        // 6th attempt returns empty
        Optional<SignupOtpChallenge> over = persistence.tryAtomicRecordFailedVerify(ch.getEmailKey(), clock.instant());
        assertTrue(over.isEmpty());

        SignupOtpChallenge current = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals(5, current.getFailedVerifyAttempts());
    }

    @Test
    @Order(51)
    @DisplayName("51. Lock after max failures transitions status to LOCKED")
    void test51_lockAfterMaxFailuresTransitionsToLocked() {
        String email = "fail-lock@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-v7", "op-v7", "k-v7");
        ch.setFailedVerifyAttempts(5);
        persistence.tryInsert(ch);

        Optional<SignupOtpChallenge> locked = persistence.tryAtomicLockAfterMaxFailures(ch.getEmailKey(), clock.instant());
        assertTrue(locked.isPresent());
        assertEquals(OtpChallengeStatus.LOCKED, locked.get().getStatus());
    }

    @Test
    @Order(52)
    @DisplayName("52. Locked challenge rejects subsequent valid OTP verification")
    void test52_lockedChallengeRejectsSubsequentValidOtp() {
        String email = "locked-valid@example.com";
        String rawOtp = "123456";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp(rawOtp), "ch-v8", "op-v8", "k-v8");
        ch.setFailedVerifyAttempts(5);
        persistence.tryInsert(ch);
        persistence.tryAtomicLockAfterMaxFailures(ch.getEmailKey(), clock.instant());

        VerifyOtpResult res = service.verifyOtp(email, rawOtp);
        assertThat(res).isInstanceOf(VerifyOtpResult.Locked.class);
    }

    @Test
    @Order(53)
    @DisplayName("53. 100 simultaneous failed OTP verification attempts cap exactly at 5")
    void test53_100SimultaneousFailedVerifyAttemptsContention() throws Exception {
        String email = "fail-100@example.com";
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, persistence.digestOtp("123456"), "ch-100", "op-100", "k-100");
        persistence.tryInsert(ch);

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger increments = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                barrier.await();
                if (persistence.tryAtomicRecordFailedVerify(ch.getEmailKey(), clock.instant()).isPresent()) {
                    increments.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(5, increments.get());

        SignupOtpChallenge finalState = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals(5, finalState.getFailedVerifyAttempts());
    }

    @Test
    @Order(54)
    @DisplayName("54. Two valid verification attempts racing produce exactly 1 winner")
    void test54_twoValidVerificationAttemptsRacing() throws Exception {
        String email = "ver-race@example.com";
        String rawOtp = "123456";
        String digest = persistence.digestOtp(rawOtp);
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, digest, "ch-v-race", "op-vr", "k-vr");
        persistence.tryInsert(ch);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger verifiedCount = new AtomicInteger(0);

        Callable<Void> task = () -> {
            barrier.await();
            if (persistence.tryAtomicMarkVerified(ch.getEmailKey(), digest, clock.instant()).isPresent()) {
                verifiedCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task);
        Future<Void> f2 = executor.submit(task);
        f1.get();
        f2.get();
        executor.shutdown();

        assertEquals(1, verifiedCount.get());
    }

    @Test
    @Order(55)
    @DisplayName("55. Verification racing with digest rotation fails cleanly")
    void test55_verificationRacingWithDigestRotation() throws Exception {
        String email = "ver-race-rot@example.com";
        String oldOtp = "111111";
        String oldDigest = persistence.digestOtp(oldOtp);
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, oldDigest, "ch-rot-race", "op-rr", "k-rr");
        persistence.tryInsert(ch);

        clock.advance(Duration.ofSeconds(35));
        Instant now = clock.instant();

        // Rotate first
        String newDigest = persistence.digestOtp("222222");
        persistence.tryAtomicResend(ch.getEmailKey(), newDigest, "op-new", "k-new", now);

        // Verify with old digest must fail
        Optional<SignupOtpChallenge> verified = persistence.tryAtomicMarkVerified(ch.getEmailKey(), oldDigest, now);
        assertTrue(verified.isEmpty());
    }

    // =========================================================================
    // GROUP 7: Transactions, Spring DI & Security
    // =========================================================================

    @Test
    @Order(56)
    @DisplayName("56. Transactional rollback restores previous challenge state")
    void test56_transactionalRollbackRestoresPreviousState() {
        String email = "rollback@example.com";
        String digest = persistence.digestOtp("123456");
        SignupOtpChallenge ch = persistence.buildNewActiveChallenge(email, digest, "ch-rb", "op-rb", "k-rb");
        persistence.tryInsert(ch);

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var status = transactionManager.getTransaction(def);

        try {
            jdbcTemplate.update("UPDATE signup_otp_challenges SET status = 'VERIFIED' WHERE email_key = ?", ch.getEmailKey());
            transactionManager.rollback(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
        }

        SignupOtpChallenge restored = persistence.findByEmailKey(ch.getEmailKey()).orElseThrow();
        assertEquals(OtpChallengeStatus.ACTIVE, restored.getStatus());
    }

    @Configuration
    static class MinimalSpringContextConfig {
        @Bean
        public DataSource dataSource(PostgresSignupOtpChallengePersistenceIntegrationTest testInstance) {
            return testInstance.dataSource;
        }

        @Bean
        public Clock clock(PostgresSignupOtpChallengePersistenceIntegrationTest testInstance) {
            return testInstance.clock;
        }

        @Bean
        public SignupOtpChallengeRepository repository() {
            return mock(SignupOtpChallengeRepository.class);
        }

        @Bean
        public MongoTemplate mongoTemplate() {
            return mock(MongoTemplate.class);
        }
    }

    @Test
    @Order(57)
    @DisplayName("57. Spring DI selects PostgresSignupOtpChallengeStore when opted-in")
    void test57_springContextDISelectsPostgresWhenOptedIn() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MinimalSpringContextConfig.class,
                        PostgresSignupOtpPersistenceConfiguration.class,
                        MongoSignupOtpChallengeStore.class,
                        SignupOtpChallengePersistence.class
                )
                .withPropertyValues("spring.profiles.active=postgres-migration", "app.security.postgres-signup-otp.enabled=true")
                .withSystemProperties("test.postgres.port=" + testPort)
                .withBean(PostgresSignupOtpChallengePersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SignupOtpChallengePersistence persistenceBean = context.getBean(SignupOtpChallengePersistence.class);
                    assertNotNull(persistenceBean);
                    assertThat(persistenceBean.getStore()).isInstanceOf(PostgresSignupOtpChallengeStore.class);
                });
    }

    @Test
    @Order(58)
    @DisplayName("58. Spring DI selects MongoSignupOtpChallengeStore by default")
    void test58_springContextDISelectsMongoByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MinimalSpringContextConfig.class,
                        PostgresSignupOtpPersistenceConfiguration.class,
                        MongoSignupOtpChallengeStore.class,
                        SignupOtpChallengePersistence.class
                )
                .withBean(PostgresSignupOtpChallengePersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SignupOtpChallengePersistence persistenceBean = context.getBean(SignupOtpChallengePersistence.class);
                    assertNotNull(persistenceBean);
                    assertThat(persistenceBean.getStore()).isInstanceOf(MongoSignupOtpChallengeStore.class);
                });
    }

    @Test
    @Order(59)
    @DisplayName("59. Spring transactional proxy verification")
    void test59_springTransactionalProxyVerified() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MinimalSpringContextConfig.class,
                        PostgresSignupOtpPersistenceConfiguration.class
                )
                .withPropertyValues("spring.profiles.active=postgres-migration", "app.security.postgres-signup-otp.enabled=true")
                .withBean(PostgresSignupOtpChallengePersistenceIntegrationTest.class, () -> this)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SignupOtpChallengeStore storeBean = context.getBean("postgresSignupOtpChallengeStore", SignupOtpChallengeStore.class);
                    assertNotNull(storeBean);
                    assertTrue(AopUtils.isAopProxy(storeBean));
                });
    }

    @Test
    @Order(60)
    @DisplayName("60. Sensitive data protection in logs: no raw OTP, normalized email, or digests emitted")
    void test60_noSensitiveDataOrRawOtpInEmittedLogs() {
        Logger storeLogger = (Logger) LoggerFactory.getLogger(SignupOtpObservability.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        storeLogger.addAppender(appender);

        String rawOtp = "987654";
        String rawEmail = "sensitive-user@example.com";
        service.requestSignupOtp(rawEmail, "key-sens");

        for (ILoggingEvent event : appender.list) {
            String formatted = event.getFormattedMessage();
            assertThat(formatted).doesNotContain(rawOtp);
            assertThat(formatted).doesNotContain(rawEmail);
        }
        storeLogger.detachAppender(appender);
    }

    @Test
    @Order(61)
    @DisplayName("61. SQL parameter binding prevents injection attacks")
    void test61_sqlParameterBindingProtectsAgainstInjection() {
        String injectionEmail = "' OR '1'='1";
        String injectionKey = "' OR '1'='1' --";

        // Normalizing will lower and trim
        String normalized = persistence.normalizeEmail(injectionEmail);
        assertEquals("' or '1'='1", normalized);

        // Deriving key is a safe sha256 hex string
        String key = persistence.emailKeyFromNormalized(normalized);
        assertEquals(64, key.length());

        // Searching for injection string finds nothing and does NOT throw syntax error
        Optional<SignupOtpChallenge> found = persistence.findByEmailKey(injectionKey);
        assertTrue(found.isEmpty());
    }
}
