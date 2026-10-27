package com.lordgainsborough.restaurant.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCartItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCatalogMigration;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaLoginFailureState;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderMessage;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReview;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReviewItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaPasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaReservation;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaSignupAbuseLimit;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaSignupOtpChallenge;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaStoreSettings;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
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
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-008: Abuse Counter and Login Failure State JPA Integration Tests.
 * <p>
 * Tests {@link JpaSignupAbuseLimit} and {@link JpaLoginFailureState} against
 * a disposable PostgreSQL 17.11 instance initialized via Flyway V1.
 * Validates all 17 JPA metamodel entities with Hibernate {@code ddl-auto=validate}.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AbuseAndLoginFailureJpaIntegrationTest {

    private static final String TEST_DB = "abuse_failure_jpa_test" + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
    private String testPort;
    private String jdbcUrl;
    private PGSimpleDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean emfBean;
    private EntityManagerFactory emf;

    @org.springframework.boot.test.context.TestConfiguration
    @EntityScan(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.entity")
    static class TestEntityScanConfig {}

    private String getRequiredPort() {
        String port = com.lordgainsborough.restaurant.support.LocalPostgres.requirePort();
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-008 PostgreSQL integration tests")
                .isNotBlank();
        return port;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    void setUpAll() throws Exception {
        testPort = getRequiredPort();
        jdbcUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/" + TEST_DB + "?sslmode=disable";

        // 1. Ensure test database exists
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + TEST_DB + "'");
            if (!rs.next()) {
                stmt.executeUpdate("CREATE DATABASE " + TEST_DB);
            }
        }

        // 2. Apply V1 Flyway migration to test database
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
        flyway.migrate();

        // 3. Configure DataSource
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUser("postgres");
        dataSource.setPassword("postgres");

        // 4. Configure EntityManagerFactory with ddl-auto=validate
        emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(dataSource);
        emfBean.setPackagesToScan("com.lordgainsborough.restaurant.persistence.jpa.entity");
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Properties jpaProperties = new Properties();
        jpaProperties.put("hibernate.hbm2ddl.auto", "validate");
        jpaProperties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        jpaProperties.put("hibernate.show_sql", "false");
        emfBean.setJpaProperties(jpaProperties);
        emfBean.afterPropertiesSet();

        emf = emfBean.getObject();
        assertThat(emf).isNotNull();
    }

    @AfterAll
    void tearDownAll() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
        if (emfBean != null) {
            emfBean.destroy();
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. Flyway V1 Schema Initialization: Tables signup_abuse_limits and login_failure_states exist")
    void test1_flywayV1InitializesSchema() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('signup_abuse_limits', 'login_failure_states')"
            );
            Set<String> tables = new HashSet<>();
            while (rs.next()) {
                tables.add(rs.getString("table_name"));
            }
            assertThat(tables).containsExactlyInAnyOrder("signup_abuse_limits", "login_failure_states");
        }
    }

    @Test
    @Order(2)
    @DisplayName("2. Hibernate Metadata Validation: All 17 JPA entities pass schema validation")
    void test2_hibernateDdlValidateAll17Entities() {
        assertThat(emf.isOpen()).isTrue();
        // Core / Catalog
        assertThat(emf.getMetamodel().entity(JpaUser.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCategory.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaMenuItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCatalogMigration.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaStoreSettings.class)).isNotNull();
        // Orders
        assertThat(emf.getMetamodel().entity(JpaCustomerOrder.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderMessage.class)).isNotNull();
        // Cart / Review / Reservation
        assertThat(emf.getMetamodel().entity(JpaCart.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCartItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderReview.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderReviewItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaReservation.class)).isNotNull();
        // Security Challenges
        assertThat(emf.getMetamodel().entity(JpaSignupOtpChallenge.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaPasswordResetOtpChallenge.class)).isNotNull();
        // Abuse & Failure State (PG-008)
        assertThat(emf.getMetamodel().entity(JpaSignupAbuseLimit.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaLoginFailureState.class)).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("3. Signup Abuse Limit: Persists successfully with all fields round-tripping")
    void test3_signupAbuseLimitPersistsSuccessfully() {
        String id = "abuse-id-" + UUID.randomUUID();
        String subjectHash = sha256Hex("subject-ip-1-" + UUID.randomUUID());
        Instant windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = windowStart.plus(15, ChronoUnit.MINUTES);

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                    id,
                    SignupAbuseLimit.BucketType.INITIAL_SEND_IP,
                    subjectHash,
                    3L,
                    windowStart,
                    expiresAt
            );
            em.persist(limit);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaSignupAbuseLimit reloaded = em2.find(JpaSignupAbuseLimit.class, id);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getId()).isEqualTo(id);
            assertThat(reloaded.getBucketType()).isEqualTo(SignupAbuseLimit.BucketType.INITIAL_SEND_IP);
            assertThat(reloaded.getSubjectHash()).isEqualTo(subjectHash);
            assertThat(reloaded.getCount()).isEqualTo(3L);
            assertThat(reloaded.getWindowStart()).isEqualTo(windowStart);
            assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(4)
    @DisplayName("4. Assigned VARCHAR(128) Primary Key: Preserved exactly without UUID generation or alteration")
    void test4_assignedVarchar128PrimaryKeyPreservedExactly() {
        // Deterministic 128-char application key
        String id128 = "KEY-PREFIX-" + sha256Hex("part1-" + UUID.randomUUID()) + sha256Hex("part2-" + UUID.randomUUID());
        if (id128.length() > 128) {
            id128 = id128.substring(0, 128);
        }
        assertThat(id128.length()).isEqualTo(128);

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                    id128,
                    SignupAbuseLimit.BucketType.LOGIN_REQUEST_IP,
                    sha256Hex("subj-" + UUID.randomUUID()),
                    1L,
                    Instant.now(),
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            em.persist(limit);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaSignupAbuseLimit reloaded = em2.find(JpaSignupAbuseLimit.class, id128);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getId()).isEqualTo(id128);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(5)
    @DisplayName("5. BucketType Enum Mapping: Every BucketType enum value persists and reloads as exact database string")
    void test5_everyExistingBucketTypeEnumPersistsAndReloadsAsDatabaseString() throws Exception {
        for (SignupAbuseLimit.BucketType type : SignupAbuseLimit.BucketType.values()) {
            String id = "bucket-test-" + type.name() + "-" + UUID.randomUUID();
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                        id,
                        type,
                        sha256Hex("subj-" + type.name()),
                        1L,
                        Instant.now(),
                        Instant.now().plus(30, ChronoUnit.MINUTES)
                );
                em.persist(limit);
                tx.commit();
            } finally {
                if (tx.isActive()) tx.rollback();
                em.close();
            }

            // JPA reload verification
            EntityManager em2 = emf.createEntityManager();
            try {
                JpaSignupAbuseLimit reloaded = em2.find(JpaSignupAbuseLimit.class, id);
                assertThat(reloaded.getBucketType()).isEqualTo(type);
            } finally {
                em2.close();
            }

            // Direct JDBC verification confirming exact string value stored in database
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT bucket_type FROM signup_abuse_limits WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("bucket_type")).isEqualTo(type.name());
                }
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("6. Subject Hash: Persists internally without truncation up to 128 characters")
    void test6_subjectHashPersistsInternallyWithoutTruncation() {
        String longHash = sha256Hex("h1-" + UUID.randomUUID()) + sha256Hex("h2-" + UUID.randomUUID());
        assertThat(longHash.length()).isEqualTo(128);

        String id = "long-subj-id-" + UUID.randomUUID();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                    id,
                    SignupAbuseLimit.BucketType.VERIFY_IP,
                    longHash,
                    5L,
                    Instant.now(),
                    Instant.now().plus(10, ChronoUnit.MINUTES)
            );
            em.persist(limit);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaSignupAbuseLimit reloaded = em2.find(JpaSignupAbuseLimit.class, id);
            assertThat(reloaded.getSubjectHash()).isEqualTo(longHash);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(7)
    @DisplayName("7. Count Field: Persists as BIGINT including values greater than Integer.MAX_VALUE")
    void test7_countPersistsAsBigintAboveIntegerMax() throws Exception {
        long largeCount = 7_000_000_000L; // > Integer.MAX_VALUE (2,147,483,647)
        String id = "bigint-count-" + UUID.randomUUID();

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                    id,
                    SignupAbuseLimit.BucketType.GLOBAL_PROVIDER_SEND,
                    sha256Hex("global-subj"),
                    largeCount,
                    Instant.now(),
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            em.persist(limit);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaSignupAbuseLimit reloaded = em2.find(JpaSignupAbuseLimit.class, id);
            assertThat(reloaded.getCount()).isEqualTo(largeCount);
        } finally {
            em2.close();
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count FROM signup_abuse_limits WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("count")).isEqualTo(largeCount);
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("8. Count Zero: Accepted by PostgreSQL SQL check constraint count >= 0")
    void test8_countZeroAcceptedBySql() {
        String id = "count-zero-" + UUID.randomUUID();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                    id,
                    SignupAbuseLimit.BucketType.TOTAL_SEND_IP,
                    sha256Hex("zero-subj"),
                    0L,
                    Instant.now(),
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            em.persist(limit);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaSignupAbuseLimit reloaded = em2.find(JpaSignupAbuseLimit.class, id);
            assertThat(reloaded.getCount()).isEqualTo(0L);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(9)
    @DisplayName("9. Negative Count: Rejected by PostgreSQL CHECK constraint chk_signup_abuse_count")
    void test9_negativeCountRejectedByPostgresCheckConstraint() {
        String id = "neg-count-" + UUID.randomUUID();
        assertThatThrownBy(() -> {
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                        id,
                        SignupAbuseLimit.BucketType.INITIAL_SEND_IP,
                        sha256Hex("neg-subj"),
                        -1L,
                        Instant.now(),
                        Instant.now().plus(1, ChronoUnit.HOURS)
                );
                em.persist(limit);
                tx.commit();
            } finally {
                if (tx.isActive()) tx.rollback();
                em.close();
            }
        }).satisfies(ex -> {
            assertThat(ex).isInstanceOf(PersistenceException.class);
            Throwable root = ex;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).containsIgnoringCase("chk_signup_abuse_count");
        });
    }

    @Test
    @Order(10)
    @DisplayName("10. Window Timestamps: windowStart and expiresAt round-trip with exact Instant precision")
    void test10_windowStartAndExpiresAtRoundTripCorrectly() {
        String id = "window-time-" + UUID.randomUUID();
        Instant windowStart = Instant.parse("2026-10-25T14:30:00Z");
        Instant expiresAt = Instant.parse("2026-10-25T14:45:00Z");

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                    id,
                    SignupAbuseLimit.BucketType.RESET_NEW_CHALLENGE_EMAIL,
                    sha256Hex("reset-window-subj"),
                    2L,
                    windowStart,
                    expiresAt
            );
            em.persist(limit);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaSignupAbuseLimit reloaded = em2.find(JpaSignupAbuseLimit.class, id);
            assertThat(reloaded.getWindowStart()).isEqualTo(windowStart);
            assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(11)
    @DisplayName("11. Expiration Boundary Behavior: isExpired respects exact boundary and fails closed on null")
    void test11_expirationBoundaryBehaviorIsCorrect() {
        Instant expiry = Instant.parse("2026-10-25T12:00:00Z");
        JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                "exp-test-id",
                SignupAbuseLimit.BucketType.VERIFY_IP,
                "dummy-hash",
                1L,
                expiry.minus(15, ChronoUnit.MINUTES),
                expiry
        );

        // Before expiry boundary -> not expired
        Instant before = expiry.minusMillis(1);
        assertThat(limit.isExpired(before)).isFalse();

        // Exactly at expiry boundary -> expired (not before expiry)
        assertThat(limit.isExpired(expiry)).isTrue();

        // After expiry boundary -> expired
        Instant after = expiry.plusMillis(1);
        assertThat(limit.isExpired(after)).isTrue();

        // Missing / null expiration -> fail-closed (expired)
        limit.setExpiresAt(null);
        assertThat(limit.isExpired(before)).isTrue();
        assertThat(limit.isExpired()).isTrue();
    }

    @Test
    @Order(12)
    @DisplayName("12. Required Columns: Rejects null values for NOT NULL fields")
    void test12_requiredColumnsRejectNullValues() {
        // Null windowStart
        assertThatThrownBy(() -> {
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(
                        "null-win-" + UUID.randomUUID(),
                        SignupAbuseLimit.BucketType.VERIFY_IP,
                        sha256Hex("subj"),
                        1L,
                        null,
                        Instant.now().plus(15, ChronoUnit.MINUTES)
                );
                em.persist(limit);
                tx.commit();
            } finally {
                if (tx.isActive()) tx.rollback();
                em.close();
            }
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @Order(13)
    @DisplayName("13. Login Failure State: Persists successfully with all fields round-tripping")
    void test13_loginFailureStatePersistsSuccessfully() {
        String id = "lfs-id-" + UUID.randomUUID();
        String subjectHash = sha256Hex("lfs-subj-" + UUID.randomUUID());
        Instant windowStartedAt = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Instant blockedUntil = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaLoginFailureState state = new JpaLoginFailureState(
                    id,
                    subjectHash,
                    4,
                    windowStartedAt,
                    blockedUntil,
                    updatedAt,
                    expiresAt
            );
            em.persist(state);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaLoginFailureState reloaded = em2.find(JpaLoginFailureState.class, id);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getId()).isEqualTo(id);
            assertThat(reloaded.getSubjectHash()).isEqualTo(subjectHash);
            assertThat(reloaded.getFailureCount()).isEqualTo(4);
            assertThat(reloaded.getWindowStartedAt()).isEqualTo(windowStartedAt);
            assertThat(reloaded.getBlockedUntil()).isEqualTo(blockedUntil);
            assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
            assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(14)
    @DisplayName("14. Login Failure Primary Key: Assigned VARCHAR(128) primary key remains unchanged")
    void test14_loginFailureAssignedPrimaryKeyRemainsUnchanged() {
        String id128 = "LFS-KEY-" + sha256Hex("k1-" + UUID.randomUUID()) + sha256Hex("k2-" + UUID.randomUUID());
        if (id128.length() > 128) {
            id128 = id128.substring(0, 128);
        }

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaLoginFailureState state = new JpaLoginFailureState(
                    id128,
                    sha256Hex("subj-key-test"),
                    1,
                    Instant.now(),
                    null,
                    Instant.now(),
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            em.persist(state);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaLoginFailureState reloaded = em2.find(JpaLoginFailureState.class, id128);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getId()).isEqualTo(id128);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(15)
    @DisplayName("15. Failure Count: Persists accurately as INT")
    void test15_failureCountPersistsAsInt() throws Exception {
        String id = "lfs-count-" + UUID.randomUUID();
        int expectedCount = 5;

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaLoginFailureState state = new JpaLoginFailureState(
                    id,
                    sha256Hex("subj-count"),
                    expectedCount,
                    Instant.now(),
                    null,
                    Instant.now(),
                    Instant.now().plus(30, ChronoUnit.MINUTES)
            );
            em.persist(state);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaLoginFailureState reloaded = em2.find(JpaLoginFailureState.class, id);
            assertThat(reloaded.getFailureCount()).isEqualTo(expectedCount);
        } finally {
            em2.close();
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT failure_count FROM login_failure_states WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("failure_count")).isEqualTo(expectedCount);
            }
        }
    }

    @Test
    @Order(16)
    @DisplayName("16. Failure Count Zero: Accepted by SQL check constraint failure_count >= 0")
    void test16_failureCountZeroAccepted() {
        String id = "lfs-zero-" + UUID.randomUUID();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaLoginFailureState state = new JpaLoginFailureState(
                    id,
                    sha256Hex("subj-zero"),
                    0,
                    Instant.now(),
                    null,
                    Instant.now(),
                    Instant.now().plus(30, ChronoUnit.MINUTES)
            );
            em.persist(state);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaLoginFailureState reloaded = em2.find(JpaLoginFailureState.class, id);
            assertThat(reloaded.getFailureCount()).isEqualTo(0);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(17)
    @DisplayName("17. Negative Failure Count: Rejected by PostgreSQL CHECK constraint chk_login_failure_count")
    void test17_negativeFailureCountRejectedByPostgresCheckConstraint() {
        String id = "lfs-neg-" + UUID.randomUUID();
        assertThatThrownBy(() -> {
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                JpaLoginFailureState state = new JpaLoginFailureState(
                        id,
                        sha256Hex("subj-neg"),
                        -1,
                        Instant.now(),
                        null,
                        Instant.now(),
                        Instant.now().plus(30, ChronoUnit.MINUTES)
                );
                em.persist(state);
                tx.commit();
            } finally {
                if (tx.isActive()) tx.rollback();
                em.close();
            }
        }).satisfies(ex -> {
            assertThat(ex).isInstanceOf(PersistenceException.class);
            Throwable root = ex;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).containsIgnoringCase("chk_login_failure_count");
        });
    }

    @Test
    @Order(18)
    @DisplayName("18. Blocked Until Nullable: Supports both null and future timestamp")
    void test18_blockedUntilSupportsNullAndFutureTimestamp() {
        String idNullBlocked = "lfs-unblocked-" + UUID.randomUUID();
        String idBlocked = "lfs-blocked-" + UUID.randomUUID();
        Instant futureBlocked = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaLoginFailureState unblocked = new JpaLoginFailureState(
                    idNullBlocked,
                    sha256Hex("subj-unblocked"),
                    2,
                    Instant.now(),
                    null,
                    Instant.now(),
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            JpaLoginFailureState blocked = new JpaLoginFailureState(
                    idBlocked,
                    sha256Hex("subj-blocked"),
                    5,
                    Instant.now(),
                    futureBlocked,
                    Instant.now(),
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            em.persist(unblocked);
            em.persist(blocked);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaLoginFailureState reloadedUnblocked = em2.find(JpaLoginFailureState.class, idNullBlocked);
            assertThat(reloadedUnblocked.getBlockedUntil()).isNull();

            JpaLoginFailureState reloadedBlocked = em2.find(JpaLoginFailureState.class, idBlocked);
            assertThat(reloadedBlocked.getBlockedUntil()).isEqualTo(futureBlocked);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(19)
    @DisplayName("19. Time-Based Blocking Boundary: Ends at exact blockedUntil boundary and expired records are not blocked")
    void test19_timeBasedBlockingEndsAtExactBoundary() {
        Instant now = Instant.parse("2026-10-25T15:00:00Z");
        Instant blockedUntil = Instant.parse("2026-10-25T15:15:00Z");
        Instant expiresAt = Instant.parse("2026-10-25T16:00:00Z");

        JpaLoginFailureState state = new JpaLoginFailureState(
                "block-test-id",
                "dummy-hash",
                5,
                now.minus(5, ChronoUnit.MINUTES),
                blockedUntil,
                now,
                expiresAt
        );

        // 1. Before blockedUntil -> active block
        Instant before = blockedUntil.minusMillis(1);
        assertThat(state.isBlocked(before)).isTrue();

        // 2. Exactly at blockedUntil boundary -> block is no longer active
        assertThat(state.isBlocked(blockedUntil)).isFalse();

        // 3. After blockedUntil -> block is not active
        Instant after = blockedUntil.plusMillis(1);
        assertThat(state.isBlocked(after)).isFalse();

        // 4. Null blockedUntil -> never blocked
        state.setBlockedUntil(null);
        assertThat(state.isBlocked(before)).isFalse();

        // 5. Expired record must not be treated as an active block even if blockedUntil is future
        state.setBlockedUntil(Instant.parse("2026-10-25T18:00:00Z"));
        state.setExpiresAt(Instant.parse("2026-10-25T14:59:00Z")); // expired relative to 'now'
        assertThat(state.isBlocked(now)).isFalse();
    }

    @Test
    @Order(20)
    @DisplayName("20. Timestamps Round-Trip: updatedAt and windowStartedAt round-trip without unexpected modification")
    void test20_updatedAtAndWindowStartedAtRoundTripCorrectly() {
        String id = "lfs-times-" + UUID.randomUUID();
        Instant windowStartedAt = Instant.parse("2026-10-25T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-25T10:05:00Z");

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaLoginFailureState state = new JpaLoginFailureState(
                    id,
                    sha256Hex("subj-times"),
                    1,
                    windowStartedAt,
                    null,
                    updatedAt,
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            em.persist(state);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            JpaLoginFailureState reloaded = em2.find(JpaLoginFailureState.class, id);
            assertThat(reloaded.getWindowStartedAt()).isEqualTo(windowStartedAt);
            assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
        } finally {
            em2.close();
        }
    }

    @Test
    @Order(21)
    @DisplayName("21. ExpiresAt Preserved: Record updates do not unintentionally modify expiresAt")
    void test21_expiresAtPersistsWithoutUnintendedModification() {
        String id = "lfs-exp-keep-" + UUID.randomUUID();
        Instant fixedExpiresAt = Instant.parse("2026-10-25T18:30:00Z");

        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaLoginFailureState state = new JpaLoginFailureState(
                    id,
                    sha256Hex("subj-exp-keep"),
                    1,
                    Instant.now(),
                    null,
                    Instant.now(),
                    fixedExpiresAt
            );
            em.persist(state);
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        // Perform an update to failureCount in a separate transaction
        EntityManager em2 = emf.createEntityManager();
        EntityTransaction tx2 = em2.getTransaction();
        try {
            tx2.begin();
            JpaLoginFailureState existing = em2.find(JpaLoginFailureState.class, id);
            existing.setFailureCount(2);
            tx2.commit();
        } finally {
            if (tx2.isActive()) tx2.rollback();
            em2.close();
        }

        // Verify expiresAt remained identical
        EntityManager em3 = emf.createEntityManager();
        try {
            JpaLoginFailureState reloaded = em3.find(JpaLoginFailureState.class, id);
            assertThat(reloaded.getFailureCount()).isEqualTo(2);
            assertThat(reloaded.getExpiresAt()).isEqualTo(fixedExpiresAt);
        } finally {
            em3.close();
        }
    }

    @Test
    @Order(22)
    @DisplayName("22. Jackson Serialization: Does not reveal sensitive hashes or internal identifiers")
    void test22_jacksonOutputDoesNotRevealSensitiveHashesOrInternalIdentifiers() throws Exception {
        String secretSubjectHash = "secret-hash-999-synthetic-key";
        String secretAbuseId = "abuse-id-secret-111";
        String secretFailureId = "lfs-id-secret-222";

        JpaSignupAbuseLimit abuseLimit = new JpaSignupAbuseLimit(
                secretAbuseId,
                SignupAbuseLimit.BucketType.LOGIN_REQUEST_IP,
                secretSubjectHash,
                2L,
                Instant.now(),
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );

        JpaLoginFailureState failureState = new JpaLoginFailureState(
                secretFailureId,
                secretSubjectHash,
                3,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );

        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();

        String abuseJson = mapper.writeValueAsString(abuseLimit);
        assertThat(abuseJson).doesNotContain(secretSubjectHash);
        assertThat(abuseJson).doesNotContain("subjectHash");
        assertThat(abuseJson).doesNotContain(secretAbuseId);
        assertThat(abuseJson).doesNotContain("\"id\":");

        String failureJson = mapper.writeValueAsString(failureState);
        assertThat(failureJson).doesNotContain(secretSubjectHash);
        assertThat(failureJson).doesNotContain("subjectHash");
        assertThat(failureJson).doesNotContain(secretFailureId);
        assertThat(failureJson).doesNotContain("\"id\":");
    }

    @Test
    @Order(23)
    @DisplayName("23. Routine Logging (toString): Excludes internal IDs and subject hashes")
    void test23_toStringDoesNotRevealSecuritySensitiveValues() {
        String secretId = "secret-internal-id-8888";
        String secretHash = "secret-subject-hash-9999";

        JpaSignupAbuseLimit abuseLimit = new JpaSignupAbuseLimit(
                secretId,
                SignupAbuseLimit.BucketType.RESET_IDEMPOTENCY,
                secretHash,
                1L,
                Instant.now(),
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );
        String abuseLog = abuseLimit.toString();
        assertThat(abuseLog).doesNotContain(secretId);
        assertThat(abuseLog).doesNotContain(secretHash);
        assertThat(abuseLog).doesNotContain("subjectHash");
        assertThat(abuseLog).contains("bucketType=RESET_IDEMPOTENCY");
        assertThat(abuseLog).contains("count=1");

        JpaLoginFailureState failureState = new JpaLoginFailureState(
                secretId,
                secretHash,
                3,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );
        String failureLog = failureState.toString();
        assertThat(failureLog).doesNotContain(secretId);
        assertThat(failureLog).doesNotContain(secretHash);
        assertThat(failureLog).doesNotContain("subjectHash");
        assertThat(failureLog).contains("failureCount=3");
    }

    @Test
    @Order(24)
    @DisplayName("24. PostgreSQL Catalog Indexes: Flyway V1 indexes confirmed in pg_indexes catalog")
    void test24_existingIndexesConfirmedInPostgresCatalogMetadata() throws Exception {
        Set<String> indexes = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexname FROM pg_indexes WHERE tablename IN ('signup_abuse_limits', 'login_failure_states')")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexes.add(rs.getString("indexname"));
                }
            }
        }

        // Required indexes from Flyway V1 schema:
        // signup_abuse_limits:
        assertThat(indexes).contains("idx_abuse_limits_expires_at");
        assertThat(indexes).contains("idx_abuse_limits_bucket_subject");
        // login_failure_states:
        assertThat(indexes).contains("idx_login_failure_expires_at");
        assertThat(indexes).contains("idx_login_failure_blocked_until");
    }

    @Test
    @Order(25)
    @DisplayName("25. Spring Boot Minimal Context: Boots and validates all 17 JPA entities with ddl-auto=validate")
    void test25_springBootContextValidatesAll17Entities() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(TestEntityScanConfig.class)
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.username=postgres",
                        "spring.datasource.password=postgres",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                        "spring.jpa.open-in-view=false",
                        "spring.jpa.show-sql=false"
                );

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EntityManagerFactory.class);
            EntityManagerFactory springEmf = context.getBean(EntityManagerFactory.class);
            assertThat(springEmf.isOpen()).isTrue();

            // Verify all 17 JPA entity types are registered and validated against PostgreSQL schema
            assertThat(springEmf.getMetamodel().entity(JpaUser.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCategory.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaMenuItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCatalogMigration.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaStoreSettings.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCustomerOrder.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderMessage.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCart.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCartItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderReview.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderReviewItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaReservation.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaSignupOtpChallenge.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaPasswordResetOtpChallenge.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaSignupAbuseLimit.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaLoginFailureState.class)).isNotNull();
        });
    }
}
