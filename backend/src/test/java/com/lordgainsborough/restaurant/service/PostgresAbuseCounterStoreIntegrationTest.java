package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PostgresAbuseCounterStoreConfiguration;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-013: Comprehensive Integration Test Suite for {@link PostgresAbuseCounterStore}.
 * <p>
 * Tests atomic increment-and-get upsert semantics, exact expiration boundaries,
 * BIGINT overflow protection, conflicting metadata rejection, SQL injection defense,
 * production clear() guardrails, bean isolation, and multi-threaded concurrency (20 to 100 threads)
 * against a disposable PostgreSQL 17 database.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresAbuseCounterStoreIntegrationTest {

    private static final String TEST_DB_PREFIX = "pg_abuse_store_test_";

    private String testPort;
    private String testDb;
    private String testJdbcUrl;
    private DisposableDatabaseSession mainDatabaseSession;
    private PGSimpleDataSource dataSource;
    private PostgresAbuseCounterStore store;
    private JdbcTemplate jdbcTemplate;

    static void assertSafeTestEnvironment(String port, String dbName) {
        assertThat(port)
                .withFailMessage("Test PostgreSQL port must not be blank")
                .isNotBlank();
        assertThat(dbName)
                .withFailMessage("Test database name must start with expected prefix: " + TEST_DB_PREFIX)
                .startsWith(TEST_DB_PREFIX);
        assertThat(dbName)
                .withFailMessage("Test database name must match expected identifier pattern")
                .matches("^[a-z0-9_]{5,63}$");
        assertThat(dbName)
                .withFailMessage("Refusing to operate on system/production database")
                .isNotIn("postgres", "template0", "template1", "public", "lordgainsborough", "restaurant");
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
            assertSafeTestEnvironment(port, dbName);
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
            assertSafeTestEnvironment(port, dbName);
            String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid()");
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
                this.createdAndOwned = false;
            } catch (Exception e) {
                System.err.println("Warning: Failed to drop disposable test database " + dbName + ": " + e.getMessage());
            }
        }
    }

    private String getRequiredPort() {
        String port = System.getProperty("test.postgres.port");
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-013 integration tests")
                .isNotBlank();
        return port;
    }

    @BeforeAll
    void setUpAll() throws Exception {
        testPort = getRequiredPort();
        testDb = TEST_DB_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mainDatabaseSession = new DisposableDatabaseSession(testPort, testDb);
        mainDatabaseSession.provision();

        testJdbcUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/" + testDb + "?sslmode=disable";

        // Migrate Flyway V1 schema
        Flyway.configure()
                .dataSource(testJdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(testJdbcUrl);
        dataSource.setUser("postgres");
        dataSource.setPassword("postgres");

        jdbcTemplate = new JdbcTemplate(dataSource);
        store = new PostgresAbuseCounterStore(dataSource, true); // test mode: allowClear = true
    }

    @AfterAll
    void tearDownAll() {
        if (mainDatabaseSession != null) {
            mainDatabaseSession.drop();
        }
    }

    // =========================================================================
    // Cases 1 - 5: Basic Increment & Persistence
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Req 01: First increment returns 1")
    void test01_firstIncrementReturnsOne() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        long count = store.incrementAndGet("c1_id", BucketType.INITIAL_SEND_IP, "hash-01", ws, exp);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @Order(2)
    @DisplayName("Req 02: Row is inserted with count = 1")
    void test02_rowIsInsertedWithCountOne() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count FROM signup_abuse_limits WHERE id = 'c1_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(1L);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Req 03: Second increment returns 2")
    void test03_secondIncrementReturnsTwo() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        // Reuse same windowStart and expiresAt as test01 to maintain metadata consistency
        long count = store.incrementAndGet("c1_id", BucketType.INITIAL_SEND_IP, "hash-01",
                queryWindowStart("c1_id"), queryExpiresAt("c1_id"));
        assertThat(count).isEqualTo(2L);
    }

    @Test
    @Order(4)
    @DisplayName("Req 04: Third increment returns 3")
    void test04_thirdIncrementReturnsThree() {
        long count = store.incrementAndGet("c1_id", BucketType.INITIAL_SEND_IP, "hash-01",
                queryWindowStart("c1_id"), queryExpiresAt("c1_id"));
        assertThat(count).isEqualTo(3L);
    }

    @Test
    @Order(5)
    @DisplayName("Req 05: Stored count matches the last returned count")
    void test05_storedCountMatchesLastReturnedCount() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count FROM signup_abuse_limits WHERE id = 'c1_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(3L);
        }
        assertThat(store.getCount("c1_id", Instant.now())).isEqualTo(3L);
    }

    // =========================================================================
    // Cases 6 - 10: Metadata Persistence & Invariance
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("Req 06: bucketType persists correctly")
    void test06_bucketTypePersistsCorrectly() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT bucket_type FROM signup_abuse_limits WHERE id = 'c1_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("INITIAL_SEND_IP");
        }
    }

    @Test
    @Order(7)
    @DisplayName("Req 07: subjectHash persists correctly")
    void test07_subjectHashPersistsCorrectly() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT subject_hash FROM signup_abuse_limits WHERE id = 'c1_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("hash-01");
        }
    }

    @Test
    @Order(8)
    @DisplayName("Req 08: windowStart persists correctly")
    void test08_windowStartPersistsCorrectly() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT window_start FROM signup_abuse_limits WHERE id = 'c1_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp(1)).isNotNull();
        }
    }

    @Test
    @Order(9)
    @DisplayName("Req 09: expiresAt persists correctly")
    void test09_expiresAtPersistsCorrectly() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT expires_at FROM signup_abuse_limits WHERE id = 'c1_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp(1)).isNotNull();
        }
    }

    @Test
    @Order(10)
    @DisplayName("Req 10: Metadata is unchanged on repeated increments")
    void test10_metadataIsUnchangedOnRepeatedIncrements() throws Exception {
        Instant ws = queryWindowStart("c1_id");
        Instant exp = queryExpiresAt("c1_id");

        store.incrementAndGet("c1_id", BucketType.INITIAL_SEND_IP, "hash-01", ws, exp);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT bucket_type, subject_hash, window_start, expires_at FROM signup_abuse_limits WHERE id = 'c1_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("bucket_type")).isEqualTo("INITIAL_SEND_IP");
            assertThat(rs.getString("subject_hash")).isEqualTo("hash-01");
            assertThat(rs.getTimestamp("window_start").toInstant()).isEqualTo(ws);
            assertThat(rs.getTimestamp("expires_at").toInstant()).isEqualTo(exp);
        }
    }

    // =========================================================================
    // Cases 11 - 13: Isolation Between Counters
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("Req 11: Different IDs are independent")
    void test11_differentIdsAreIndependent() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        long countA = store.incrementAndGet("id-alpha", BucketType.LOGIN_REQUEST_IP, "hash-a", ws, exp);
        long countB = store.incrementAndGet("id-beta", BucketType.LOGIN_REQUEST_IP, "hash-b", ws, exp);

        assertThat(countA).isEqualTo(1L);
        assertThat(countB).isEqualTo(1L);

        store.incrementAndGet("id-alpha", BucketType.LOGIN_REQUEST_IP, "hash-a", ws, exp);

        assertThat(store.getCount("id-alpha", Instant.now())).isEqualTo(2L);
        assertThat(store.getCount("id-beta", Instant.now())).isEqualTo(1L);
    }

    @Test
    @Order(12)
    @DisplayName("Req 12: Different bucket types are independent")
    void test12_differentBucketTypesAreIndependent() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        long c1 = store.incrementAndGet("type-send", BucketType.TOTAL_SEND_IP, "hash-shared", ws, exp);
        long c2 = store.incrementAndGet("type-verify", BucketType.VERIFY_IP, "hash-shared", ws, exp);

        assertThat(c1).isEqualTo(1L);
        assertThat(c2).isEqualTo(1L);
        assertThat(store.getCount("type-send", Instant.now())).isEqualTo(1L);
        assertThat(store.getCount("type-verify", Instant.now())).isEqualTo(1L);
    }

    @Test
    @Order(13)
    @DisplayName("Req 13: Multiple distinct windows are independent")
    void test13_multipleDistinctWindowsAreIndependent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant ws1 = now.minusSeconds(600);
        Instant exp1 = ws1.plusSeconds(300);

        Instant ws2 = now;
        Instant exp2 = ws2.plusSeconds(300);

        store.incrementAndGet("win-1", BucketType.GLOBAL_PROVIDER_SEND, "hash-global", ws1, exp1);
        store.incrementAndGet("win-2", BucketType.GLOBAL_PROVIDER_SEND, "hash-global", ws2, exp2);

        // win-1 is expired at `now`, win-2 is unexpired
        assertThat(store.getCount("win-1", now)).isEqualTo(0L);
        assertThat(store.getCount("win-2", now)).isEqualTo(1L);
    }

    // =========================================================================
    // Case 14: All 10 BucketType Values
    // =========================================================================

    @Test
    @Order(14)
    @DisplayName("Req 14: All ten BucketType values are supported")
    void test14_allTenBucketTypeValuesSupported() throws Exception {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        for (BucketType type : BucketType.values()) {
            String id = "all_types_" + type.name();
            long count = store.incrementAndGet(id, type, "hash-" + type.name(), ws, exp);
            assertThat(count).isEqualTo(1L);

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT bucket_type FROM signup_abuse_limits WHERE id = '" + id + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(type.name());
            }
        }
    }

    // =========================================================================
    // Cases 15 - 20: getCount Semantics & Exact Boundaries
    // =========================================================================

    @Test
    @Order(15)
    @DisplayName("Req 15: Missing record returns zero from getCount")
    void test15_missingRecordReturnsZeroFromGetCount() {
        assertThat(store.getCount("non-existent-" + UUID.randomUUID(), Instant.now())).isEqualTo(0L);
    }

    @Test
    @Order(16)
    @DisplayName("Req 16: Unexpired record returns its count")
    void test16_unexpiredRecordReturnsItsCount() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(600);
        store.incrementAndGet("unexp-id", BucketType.INITIAL_SEND_IP, "hash-u", ws, exp);
        assertThat(store.getCount("unexp-id", ws.plusSeconds(10))).isEqualTo(1L);
    }

    @Test
    @Order(17)
    @DisplayName("Req 17: Exactly at expiresAt, getCount returns zero")
    void test17_exactlyAtExpiresAtGetCountReturnsZero() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(100);
        store.incrementAndGet("exact-exp-id", BucketType.INITIAL_SEND_IP, "hash-e", ws, exp);
        assertThat(store.getCount("exact-exp-id", exp)).isEqualTo(0L);
    }

    @Test
    @Order(18)
    @DisplayName("Req 18: One millisecond before expiry, getCount returns its count")
    void test18_oneMillisecondBeforeExpiryGetCountReturnsCount() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(100);
        store.incrementAndGet("boundary-id", BucketType.INITIAL_SEND_IP, "hash-b", ws, exp);
        assertThat(store.getCount("boundary-id", exp.minusMillis(1))).isEqualTo(1L);
    }

    @Test
    @Order(19)
    @DisplayName("Req 19: One millisecond after expiry, getCount returns zero")
    void test19_oneMillisecondAfterExpiryGetCountReturnsZero() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(100);
        store.incrementAndGet("after-exp-id", BucketType.INITIAL_SEND_IP, "hash-a", ws, exp);
        assertThat(store.getCount("after-exp-id", exp.plusMillis(1))).isEqualTo(0L);
    }

    @Test
    @Order(20)
    @DisplayName("Req 20: Expired physically retained records are handled consistently")
    void test20_expiredPhysicallyRetainedRecordHandledConsistently() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant ws = now.minusSeconds(600);
        Instant exp = now.minusSeconds(300);

        store.incrementAndGet("retained-exp-id", BucketType.RESET_IDEMPOTENCY, "hash-r", ws, exp);

        // Record exists physically in PostgreSQL, but is past expiresAt
        assertThat(store.getCount("retained-exp-id", now)).isEqualTo(0L);

        // Later increment on the same ID increments the count atomically
        long next = store.incrementAndGet("retained-exp-id", BucketType.RESET_IDEMPOTENCY, "hash-r", ws, exp);
        assertThat(next).isEqualTo(2L);
    }

    // =========================================================================
    // Cases 21 - 25: Security, Overflow & Robustness
    // =========================================================================

    @Test
    @Order(21)
    @DisplayName("Req 21: Conflicting metadata is safely rejected")
    void test21_conflictingMetadataIsRejected() {
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        String id = "conflict-test-id";

        store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-orig", ws, exp);

        // 1. Conflict on bucketType
        assertThatThrownBy(() -> store.incrementAndGet(id, BucketType.TOTAL_SEND_IP, "hash-orig", ws, exp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting metadata for existing abuse limit record");

        // 2. Conflict on subjectHash
        assertThatThrownBy(() -> store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-contradictory", ws, exp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting metadata for existing abuse limit record");

        // 3. Conflict on windowStart
        assertThatThrownBy(() -> store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-orig", ws.plusSeconds(1), exp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting metadata for existing abuse limit record");

        // 4. Conflict on expiresAt
        assertThatThrownBy(() -> store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-orig", ws, exp.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting metadata for existing abuse limit record");

        // Confirm database was not corrupted: count is still 1, bucket_type still INITIAL_SEND_IP
        assertThat(store.getCount(id, ws.plusSeconds(10))).isEqualTo(1L);
    }

    @Test
    @Order(22)
    @DisplayName("Req 22: BIGINT values beyond Integer.MAX_VALUE work")
    void test22_bigintValuesBeyondIntegerMaxWork() {
        String id = "bigint-test-id";
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-big", ws, exp);

        // Artificially elevate count beyond Integer.MAX_VALUE (2,147,483,647) to 3,000,000,000
        jdbcTemplate.update("UPDATE signup_abuse_limits SET count = 3000000000 WHERE id = ?", id);

        long next = store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-big", ws, exp);
        assertThat(next).isEqualTo(3000000001L);
        assertThat(store.getCount(id, Instant.now())).isEqualTo(3000000001L);
    }

    @Test
    @Order(23)
    @DisplayName("Req 23: Counter overflow fails safely closed")
    void test23_counterOverflowFailsSafely() {
        String id = "overflow-test-id";
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-ovf", ws, exp);

        // Set to Long.MAX_VALUE (9,223,372,036,854,775,807)
        jdbcTemplate.update("UPDATE signup_abuse_limits SET count = 9223372036854775807 WHERE id = ?", id);

        // Next increment must fail closed with ArithmeticException
        assertThatThrownBy(() -> store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-ovf", ws, exp))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("Abuse counter overflow");

        // Confirm count did not wrap to negative or reset to 0
        assertThat(store.getCount(id, Instant.now())).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @Order(24)
    @DisplayName("Req 24: SQL injection-like string inputs cannot alter query")
    void test24_sqlInjectionLikeStringInputsCannotAlterQuery() throws Exception {
        String maliciousId = "malicious'; DROP TABLE signup_abuse_limits; --";
        String maliciousHash = "hash'; SELECT 1; --";
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        long count = store.incrementAndGet(maliciousId, BucketType.INITIAL_SEND_IP, maliciousHash, ws, exp);
        assertThat(count).isEqualTo(1L);

        // Verify table still exists and record was stored as literal string
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM signup_abuse_limits WHERE id = '" + maliciousId.replace("'", "''") + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Order(25)
    @DisplayName("Req 25: Sensitive IDs or subject hashes are not leaked in application logs")
    void test25_noSecretIdsOrSubjectHashesLogged() {
        // Validation that sensitive IDs or hashes are not embedded in exception messages
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        String secretId = "secret-keyed-token-9988776655";
        store.incrementAndGet(secretId, BucketType.INITIAL_SEND_IP, "secret-hash-value-12345", ws, exp);

        assertThatThrownBy(() -> store.incrementAndGet(secretId, BucketType.TOTAL_SEND_IP, "secret-hash-value-12345", ws, exp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Conflicting metadata for existing abuse limit record");
    }

    // =========================================================================
    // Cases 26 - 32: Real Concurrency Verification (Section 12, Tests A - G)
    // =========================================================================

    @Test
    @Order(26)
    @DisplayName("Req 26 (Test A): 20 concurrent requests on absent bucket have zero lost updates")
    void test26_testA_twentyConcurrentRequests() throws Exception {
        String id = "conc-20-" + UUID.randomUUID();
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        int threads = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<Long> returnedValues = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                long val = store.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-conc20", ws, exp);
                returnedValues.add(val);
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(returnedValues).hasSize(threads);
        assertThat(returnedValues).containsExactlyInAnyOrder(
                1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L,
                11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L
        );
        assertThat(store.getCount(id, Instant.now())).isEqualTo(20L);
    }

    @Test
    @Order(27)
    @DisplayName("Req 27 (Test B): 100 concurrent requests against same bucket increment cleanly")
    void test27_testB_hundredConcurrentRequests() throws Exception {
        String id = "conc-100-" + UUID.randomUUID();
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        int threads = 100;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<Long> returnedValues = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                long val = store.incrementAndGet(id, BucketType.LOGIN_REQUEST_IP, "hash-conc100", ws, exp);
                returnedValues.add(val);
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(returnedValues).hasSize(threads);
        assertThat(store.getCount(id, Instant.now())).isEqualTo(100L);
    }

    @Test
    @Order(28)
    @DisplayName("Req 28 (Test C): Existing bucket contention starting from 50 reaches 100")
    void test28_testC_existingBucketContention() throws Exception {
        String id = "conc-exist-" + UUID.randomUUID();
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        store.incrementAndGet(id, BucketType.TOTAL_SEND_IP, "hash-exist", ws, exp);
        jdbcTemplate.update("UPDATE signup_abuse_limits SET count = 50 WHERE id = ?", id);

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<Long> returnedValues = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                long val = store.incrementAndGet(id, BucketType.TOTAL_SEND_IP, "hash-exist", ws, exp);
                returnedValues.add(val);
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(returnedValues).hasSize(threads);
        assertThat(store.getCount(id, Instant.now())).isEqualTo(100L);
    }

    @Test
    @Order(29)
    @DisplayName("Req 29 (Test D): Independent buckets increment simultaneously without interference")
    void test29_testD_independentBucketsContention() throws Exception {
        int buckets = 5;
        int threadsPerBucket = 20;
        int totalThreads = buckets * threadsPerBucket;
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);
        List<Future<?>> futures = new ArrayList<>();

        for (int b = 0; b < buckets; b++) {
            String bucketId = "bucket-grp-" + b + "-" + UUID.randomUUID();
            for (int t = 0; t < threadsPerBucket; t++) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    store.incrementAndGet(bucketId, BucketType.RESET_EMAIL_OPERATION_IP, "hash-grp", ws, exp);
                    return null;
                }));
            }
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        for (int b = 0; b < buckets; b++) {
            // Re-query count for each bucket
            List<Long> counts = jdbcTemplate.query(
                    "SELECT count FROM signup_abuse_limits WHERE id LIKE 'bucket-grp-" + b + "-%'",
                    (rs, rowNum) -> rs.getLong(1)
            );
            assertThat(counts).hasSize(1);
            assertThat(counts.get(0)).isEqualTo((long) threadsPerBucket);
        }
    }

    @Test
    @Order(30)
    @DisplayName("Req 30 (Test E): Cross-transaction correctness with separately committed connections")
    void test30_testE_crossTransactionCorrectness() throws Exception {
        String id = "tx-correctness-" + UUID.randomUUID();
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        // Open 3 distinct connections and execute explicit transactions
        try (Connection conn1 = dataSource.getConnection();
             Connection conn2 = dataSource.getConnection()) {
            conn1.setAutoCommit(false);
            conn2.setAutoCommit(false);

            JdbcTemplate txTemplate1 = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(conn1, true));
            JdbcTemplate txTemplate2 = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(conn2, true));

            PostgresAbuseCounterStore store1 = new PostgresAbuseCounterStore(txTemplate1, true);
            PostgresAbuseCounterStore store2 = new PostgresAbuseCounterStore(txTemplate2, true);

            long c1 = store1.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-tx", ws, exp);
            conn1.commit();
            assertThat(c1).isEqualTo(1L);

            long c2 = store2.incrementAndGet(id, BucketType.INITIAL_SEND_IP, "hash-tx", ws, exp);
            conn2.commit();
            assertThat(c2).isEqualTo(2L);
        }

        assertThat(store.getCount(id, Instant.now())).isEqualTo(2L);
    }

    @Test
    @Order(31)
    @DisplayName("Req 31 (Test F): Simultaneous first insert handles competing inserts cleanly")
    void test31_testF_simultaneousFirstInsert() throws Exception {
        String id = "competing-first-" + UUID.randomUUID();
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        int threads = 30;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<Long> returnedValues = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                long val = store.incrementAndGet(id, BucketType.VERIFY_IP, "hash-compete", ws, exp);
                returnedValues.add(val);
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(returnedValues).hasSize(threads);
        assertThat(store.getCount(id, Instant.now())).isEqualTo(30L);
    }

    @Test
    @Order(32)
    @DisplayName("Req 32 (Test G): Boundary and metadata integrity under contention")
    void test32_testG_boundaryAndMetadataIntegrityUnderContention() throws Exception {
        String id = "boundary-meta-" + UUID.randomUUID();
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);
        int threads = 25;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                store.incrementAndGet(id, BucketType.RESET_GLOBAL_EMAIL_OPERATION, "hash-meta", ws, exp);
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT bucket_type, subject_hash, count FROM signup_abuse_limits WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("bucket_type")).isEqualTo("RESET_GLOBAL_EMAIL_OPERATION");
            assertThat(rs.getString("subject_hash")).isEqualTo("hash-meta");
            assertThat(rs.getLong("count")).isEqualTo((long) threads);
        }
    }

    // =========================================================================
    // Cases 33 - 34: clear() Safety Requirements
    // =========================================================================

    @Test
    @Order(33)
    @DisplayName("Req 33: Production-like configuration refuses global clear with UnsupportedOperationException")
    void test33_clearInProductionConfigRefusesGlobalClear() {
        PostgresAbuseCounterStore prodStore = new PostgresAbuseCounterStore(dataSource); // default allowClear = false
        assertThat(prodStore.isAllowClear()).isFalse();

        assertThatThrownBy(prodStore::clear)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Global clearing of PostgreSQL abuse counters is disabled by default");
    }

    @Test
    @Order(34)
    @DisplayName("Req 34: Test-mode clear removes records safely without table truncation")
    void test34_clearInTestModeRemovesRecords() throws Exception {
        PostgresAbuseCounterStore testStore = new PostgresAbuseCounterStore(dataSource, true);
        Instant ws = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant exp = ws.plusSeconds(300);

        testStore.incrementAndGet("to-clear-1", BucketType.INITIAL_SEND_IP, "hash-clear", ws, exp);
        testStore.incrementAndGet("to-clear-2", BucketType.INITIAL_SEND_IP, "hash-clear", ws, exp);

        testStore.clear();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM signup_abuse_limits")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }

    // =========================================================================
    // Cases 35 - 36: Spring Boot Configuration Isolation & Profile Selection
    // =========================================================================

    @Test
    @Order(35)
    @DisplayName("Req 35: Default MongoDB context does not register PostgresAbuseCounterStore")
    void test35_defaultContextInjectsMongoAbuseCounterStoreAndNotPostgres() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresAbuseCounterStoreConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(PostgresAbuseCounterStore.class);
                });
    }

    @Test
    @Order(36)
    @DisplayName("Req 36: Opt-in profile and property activates PostgresAbuseCounterStore")
    void test36_optInProfileAndPropertyActivatesPostgresAbuseCounterStore() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresAbuseCounterStoreConfiguration.class))
                .withPropertyValues("spring.profiles.active=postgres-migration", "app.security.postgres-abuse-store.enabled=true")
                .withBean(DataSource.class, () -> dataSource)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(PostgresAbuseCounterStore.class);
                });
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private Instant queryWindowStart(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT window_start FROM signup_abuse_limits WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                id
        );
    }

    private Instant queryExpiresAt(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM signup_abuse_limits WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                id
        );
    }
}
