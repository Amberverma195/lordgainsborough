package com.lordgainsborough.restaurant.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCartItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCatalogMigration;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderMessage;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReview;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReviewItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaPasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaReservation;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SecurityChallengeJpaIntegrationTest {

    private static final String TEST_DB = "sec_challenge_jpa_test";
    private String testPort;
    private String jdbcUrl;
    private PGSimpleDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean emfBean;
    private EntityManagerFactory emf;

    @Configuration
    @EntityScan(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.entity")
    static class TestEntityScanConfig {}

    private String getRequiredPort() {
        String port = System.getProperty("test.postgres.port");
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-007 PostgreSQL integration tests")
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

    private JpaUser createAndPersistUser(EntityManager em, String emailSuffix) {
        String userId = "user-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Sec User " + emailSuffix, "sec." + emailSuffix + "." + UUID.randomUUID() + "@example.com", "519-555-0100", "MEMBER");
        em.persist(user);
        return user;
    }

    @Test
    @Order(1)
    @DisplayName("Hibernate Metadata Validation: Validates all 15 JPA entities with ddl-auto=validate")
    void test1_hibernateMetadataValidation() {
        assertThat(emf.isOpen()).isTrue();
        assertThat(emf.getMetamodel().entity(JpaUser.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCategory.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaMenuItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCatalogMigration.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaStoreSettings.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCustomerOrder.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderMessage.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCart.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCartItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderReview.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderReviewItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaReservation.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaSignupOtpChallenge.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaPasswordResetOtpChallenge.class)).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Signup Challenge Insertion & Retrieval: Persists and reloads with accurate fields")
    void test2_signupChallengeInsertionAndRetrieval() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String rawEmail = "user." + UUID.randomUUID() + "@example.com";
        String normalizedEmail = rawEmail.toLowerCase();
        String emailKey = sha256Hex(normalizedEmail);
        String challengeId = "sc-" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                    emailKey,
                    normalizedEmail,
                    challengeId,
                    OtpChallengeStatus.ACTIVE,
                    "$2a$10$abcdefDummyDigestForTestingOnly12345",
                    expiresAt
            );
            challenge.setResendCount(1);
            challenge.setFailedVerifyAttempts(2);
            challenge.setLastSendWasInitial(true);
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Fresh persistence context: reload and assert
        em = emf.createEntityManager();
        try {
            JpaSignupOtpChallenge loaded = em.find(JpaSignupOtpChallenge.class, emailKey);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getEmailKey()).isEqualTo(emailKey);
            assertThat(loaded.getNormalizedEmail()).isEqualTo(normalizedEmail);
            assertThat(loaded.getChallengeId()).isEqualTo(challengeId);
            assertThat(loaded.getStatus()).isEqualTo(OtpChallengeStatus.ACTIVE);
            assertThat(loaded.getOtpDigest()).isEqualTo("$2a$10$abcdefDummyDigestForTestingOnly12345");
            assertThat(loaded.getResendCount()).isEqualTo(1);
            assertThat(loaded.getFailedVerifyAttempts()).isEqualTo(2);
            assertThat(loaded.isLastSendWasInitial()).isTrue();
            assertThat(loaded.getChallengeExpiresAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(expiresAt);
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getUpdatedAt()).isNotNull();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Assigned SHA-256 emailKey: Preserved exactly without UUID or raw email conversion")
    void test3_assignedSha256EmailKeyPreservedExactly() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String explicitSha256 = sha256Hex("test-sha256-exact-" + UUID.randomUUID());
        String challengeId = "sc-sha-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                    explicitSha256,
                    "sha-test@example.com",
                    challengeId,
                    OtpChallengeStatus.ACTIVE,
                    "$2a$10$digest1234567890",
                    Instant.now().plus(20, ChronoUnit.MINUTES)
            );
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify via direct JDBC that email_key in PostgreSQL equals the 64-char SHA-256
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT email_key FROM signup_otp_challenges WHERE email_key = '" + explicitSha256 + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("email_key")).isEqualTo(explicitSha256);
            assertThat(rs.getString("email_key")).hasSize(64);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Signup Status Enums: ACTIVE, VERIFIED, LOCKED round-trip as database strings")
    void test4_allSignupStatusEnumsRoundTripAsStrings() throws Exception {
        for (OtpChallengeStatus status : OtpChallengeStatus.values()) {
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            String key = sha256Hex("status-test-" + status + "-" + UUID.randomUUID());
            try {
                tx.begin();
                JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                        key,
                        "status-" + status + "@example.com",
                        "ch-" + UUID.randomUUID(),
                        status,
                        "$2a$10$digest",
                        Instant.now().plus(15, ChronoUnit.MINUTES)
                );
                em.persist(challenge);
                em.flush();
                tx.commit();
            } catch (Exception e) {
                if (tx.isActive()) tx.rollback();
                throw e;
            } finally {
                em.close();
            }

            // Direct JDBC check that column holds enum name string
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT status FROM signup_otp_challenges WHERE email_key = '" + key + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo(status.name());
            }

            // Entity reload check
            em = emf.createEntityManager();
            try {
                JpaSignupOtpChallenge reloaded = em.find(JpaSignupOtpChallenge.class, key);
                assertThat(reloaded.getStatus()).isEqualTo(status);
            } finally {
                em.close();
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("Send Status Enums: RESERVED, SENT, DEFINITELY_FAILED, DELIVERY_UNKNOWN, and null round-trip")
    void test5_allSendStatusEnumsRoundTripCorrectlyIncludingNull() throws Exception {
        OtpSendStatus[] statusesToTest = {
                OtpSendStatus.RESERVED,
                OtpSendStatus.SENT,
                OtpSendStatus.DEFINITELY_FAILED,
                OtpSendStatus.DELIVERY_UNKNOWN,
                null
        };

        for (OtpSendStatus sendStatus : statusesToTest) {
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            String key = sha256Hex("send-status-" + sendStatus + "-" + UUID.randomUUID());
            try {
                tx.begin();
                JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                        key,
                        "send-" + sendStatus + "@example.com",
                        "ch-" + UUID.randomUUID(),
                        OtpChallengeStatus.ACTIVE,
                        "$2a$10$digest",
                        Instant.now().plus(15, ChronoUnit.MINUTES)
                );
                challenge.setSendStatus(sendStatus);
                em.persist(challenge);
                em.flush();
                tx.commit();
            } catch (Exception e) {
                if (tx.isActive()) tx.rollback();
                throw e;
            } finally {
                em.close();
            }

            // Direct JDBC check
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT send_status FROM signup_otp_challenges WHERE email_key = '" + key + "'")) {
                assertThat(rs.next()).isTrue();
                String dbVal = rs.getString("send_status");
                if (sendStatus == null) {
                    assertThat(dbVal).isNull();
                } else {
                    assertThat(dbVal).isEqualTo(sendStatus.name());
                }
            }

            // Entity reload check
            em = emf.createEntityManager();
            try {
                JpaSignupOtpChallenge reloaded = em.find(JpaSignupOtpChallenge.class, key);
                assertThat(reloaded.getSendStatus()).isEqualTo(sendStatus);
            } finally {
                em.close();
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("OTP Digest Persistence & JSON Protection: Digest persists in DB but excluded from JSON")
    void test6_otpDigestSurvivesPersistenceButExcludedFromJson() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("digest-protect-" + UUID.randomUUID());
        String secretDigest = "$2a$10$TopSecretBcryptDigestMaterial777888999";

        try {
            tx.begin();
            JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                    key,
                    "digest-user@example.com",
                    "ch-" + UUID.randomUUID(),
                    OtpChallengeStatus.ACTIVE,
                    secretDigest,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Entity reload check: digest is intact internally
        em = emf.createEntityManager();
        JpaSignupOtpChallenge reloaded;
        try {
            reloaded = em.find(JpaSignupOtpChallenge.class, key);
            assertThat(reloaded.getOtpDigest()).isEqualTo(secretDigest);
        } finally {
            em.close();
        }

        // Jackson serialization check: digest and normalizedEmail excluded
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        String json = mapper.writeValueAsString(reloaded);

        assertThat(json).doesNotContain(secretDigest);
        assertThat(json).doesNotContain("otpDigest");
        assertThat(json).doesNotContain("otp_digest");
        assertThat(json).doesNotContain("digest-user@example.com");
        assertThat(json).contains("\"emailKey\":\"" + key + "\"");
    }

    @Test
    @Order(7)
    @DisplayName("Counters Persistence: Resend and failed verification counters persist accurately")
    void test7_resendCountersAndFailedAttemptCountersPersist() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("counters-" + UUID.randomUUID());

        try {
            tx.begin();
            JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                    key,
                    "counter@example.com",
                    "ch-" + UUID.randomUUID(),
                    OtpChallengeStatus.ACTIVE,
                    "$2a$10$digest",
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            challenge.setResendCount(4);
            challenge.setFailedVerifyAttempts(3);
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaSignupOtpChallenge reloaded = em.find(JpaSignupOtpChallenge.class, key);
            assertThat(reloaded.getResendCount()).isEqualTo(4);
            assertThat(reloaded.getFailedVerifyAttempts()).isEqualTo(3);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(8)
    @DisplayName("SQL CHECK Constraints on Counters: Negative counter values are rejected by PostgreSQL")
    void test8_negativeCounterValuesRejectedBySqlCheckConstraints() throws Exception {
        String key1 = sha256Hex("chk-neg-resend-" + UUID.randomUUID());
        String key2 = sha256Hex("chk-neg-verify-" + UUID.randomUUID());

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {

            // Negative resend_count rejected by chk_signup_otp_resend_count
            assertThatThrownBy(() -> stmt.executeUpdate(
                    "INSERT INTO signup_otp_challenges (email_key, normalized_email, challenge_id, status, otp_digest, resend_count, failed_verify_attempts, challenge_expires_at) " +
                            "VALUES ('" + key1 + "', 'neg1@example.com', 'c1', 'ACTIVE', 'digest', -1, 0, NOW() + INTERVAL '10 minutes')"))
                    .hasMessageContaining("chk_signup_otp_resend_count");

            // Negative failed_verify_attempts rejected by chk_signup_otp_failed_verify
            assertThatThrownBy(() -> stmt.executeUpdate(
                    "INSERT INTO signup_otp_challenges (email_key, normalized_email, challenge_id, status, otp_digest, resend_count, failed_verify_attempts, challenge_expires_at) " +
                            "VALUES ('" + key2 + "', 'neg2@example.com', 'c2', 'ACTIVE', 'digest', 0, -1, NOW() + INTERVAL '10 minutes')"))
                    .hasMessageContaining("chk_signup_otp_failed_verify");
        }
    }

    @Test
    @Order(9)
    @DisplayName("Timestamp Round-Trip: All Instant timestamps round-trip with timezone accuracy")
    void test9_signupTimestampsRoundTripExactly() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("timestamps-" + UUID.randomUUID());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant challengeExp = now.plus(30, ChronoUnit.MINUTES);
        Instant otpExp = now.plus(10, ChronoUnit.MINUTES);
        Instant nextResend = now.plus(30, ChronoUnit.SECONDS);
        Instant sendLease = now.plus(60, ChronoUnit.SECONDS);

        try {
            tx.begin();
            JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                    key,
                    "time@example.com",
                    "ch-" + UUID.randomUUID(),
                    OtpChallengeStatus.ACTIVE,
                    "$2a$10$digest",
                    challengeExp
            );
            challenge.setOtpExpiresAt(otpExp);
            challenge.setNextResendAt(nextResend);
            challenge.setSendLeaseUntil(sendLease);
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaSignupOtpChallenge reloaded = em.find(JpaSignupOtpChallenge.class, key);
            assertThat(reloaded.getChallengeExpiresAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(challengeExp);
            assertThat(reloaded.getOtpExpiresAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(otpExp);
            assertThat(reloaded.getNextResendAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(nextResend);
            assertThat(reloaded.getSendLeaseUntil().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(sendLease);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(10)
    @DisplayName("Nullable Signup Fields: Nullable timestamps and dispatch fields persist and reload as null")
    void test10_nullableSignupChallengeFieldsPersistCorrectly() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("nullable-" + UUID.randomUUID());

        try {
            tx.begin();
            JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                    key,
                    "null@example.com",
                    "ch-" + UUID.randomUUID(),
                    OtpChallengeStatus.ACTIVE,
                    "$2a$10$digest",
                    Instant.now().plus(20, ChronoUnit.MINUTES)
            );
            challenge.setNextResendAt(null);
            challenge.setOtpExpiresAt(null);
            challenge.setSendOperationId(null);
            challenge.setSendStatus(null);
            challenge.setSendLeaseUntil(null);
            challenge.setClientIdempotencyKey(null);
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaSignupOtpChallenge reloaded = em.find(JpaSignupOtpChallenge.class, key);
            assertThat(reloaded.getNextResendAt()).isNull();
            assertThat(reloaded.getOtpExpiresAt()).isNull();
            assertThat(reloaded.getSendOperationId()).isNull();
            assertThat(reloaded.getSendStatus()).isNull();
            assertThat(reloaded.getSendLeaseUntil()).isNull();
            assertThat(reloaded.getClientIdempotencyKey()).isNull();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(11)
    @DisplayName("Idempotency Key Persistence: Client idempotency key persists without truncation or alteration")
    void test11_signupClientIdempotencyKeyPersistsWithoutAlteration() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("idemp-" + UUID.randomUUID());
        String idempKey = "client-idemp-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                    key,
                    "idemp@example.com",
                    "ch-" + UUID.randomUUID(),
                    OtpChallengeStatus.ACTIVE,
                    "$2a$10$digest",
                    Instant.now().plus(20, ChronoUnit.MINUTES)
            );
            challenge.setClientIdempotencyKey(idempKey);
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaSignupOtpChallenge reloaded = em.find(JpaSignupOtpChallenge.class, key);
            assertThat(reloaded.getClientIdempotencyKey()).isEqualTo(idempKey);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(12)
    @DisplayName("Password Reset Challenge Insertion: Persists and reloads against existing JpaUser")
    void test12_passwordResetChallengeInsertionAndRetrieval() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("pwd-reset-" + UUID.randomUUID());
        String challengeId = "pr-" + UUID.randomUUID();
        Instant challengeExp = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "pwd-reset-init");
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    challengeId,
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    challengeExp
            );
            challenge.setCodeDigest("$2a$10$codeDigestBcryptTest");
            challenge.setCodeExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaPasswordResetOtpChallenge reloaded = em.find(JpaPasswordResetOtpChallenge.class, key);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getEmailKey()).isEqualTo(key);
            assertThat(reloaded.getChallengeId()).isEqualTo(challengeId);
            assertThat(reloaded.getPurpose()).isEqualTo(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET);
            assertThat(reloaded.getStatus()).isEqualTo(PasswordResetChallengeStatus.ACTIVE);
            assertThat(reloaded.getCodeDigest()).isEqualTo("$2a$10$codeDigestBcryptTest");
            assertThat(reloaded.getChallengeExpiresAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(challengeExp);
            assertThat(reloaded.isPasswordUpdated()).isFalse();
            assertThat(reloaded.getUser()).isNotNull();
            assertThat(reloaded.getUserId()).isEqualTo(reloaded.getUser().getId());
        } finally {
            em.close();
        }
    }

    @Test
    @Order(13)
    @DisplayName("User Relationship Mapping: Resolves lazily and safely derives getUserId()")
    void test13_existingJpaUserRelationshipPersistsCorrectly() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("pwd-user-rel-" + UUID.randomUUID());
        String userId;

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "user-rel");
            userId = user.getId();
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaPasswordResetOtpChallenge reloaded = em.find(JpaPasswordResetOtpChallenge.class, key);
            assertThat(reloaded.getUserId()).isEqualTo(userId);
            assertThat(reloaded.getUser().getId()).isEqualTo(userId);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(14)
    @DisplayName("Foreign Key Constraint: Invalid or missing user_id is rejected by fk_pwd_reset_user")
    void test14_invalidOrMissingUserIdRejectedByPostgreSQL() throws Exception {
        String nonExistentUserId = "user-nonexistent-" + UUID.randomUUID();
        String key = sha256Hex("invalid-user-key-" + UUID.randomUUID());

        // 1. In JPA: reference an unpersisted user ID via em.getReference
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaUser nonExistentUserRef = em.getReference(JpaUser.class, nonExistentUserId);
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    nonExistentUserRef,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(challenge);
            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }

        // 2. In PostgreSQL directly via JDBC: assert foreign key constraint rejection
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.executeUpdate(
                    "INSERT INTO password_reset_otp_challenges (email_key, challenge_id, user_id, purpose, status, challenge_expires_at) " +
                            "VALUES ('" + key + "', 'ch-sql-" + UUID.randomUUID() + "', '" + nonExistentUserId + "', 'PASSWORD_RESET', 'ACTIVE', NOW() + INTERVAL '10 minutes')"))
                    .hasMessageContaining("fk_pwd_reset_user");
        }
    }

    @Test
    @Order(15)
    @DisplayName("Challenge Deletion Isolation: Deleting a password-reset challenge does NOT delete its user")
    void test15_deletingPasswordResetChallengeDoesNotDeleteUser() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("del-chal-" + UUID.randomUUID());
        String userId;

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "no-user-del");
            userId = user.getId();
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Delete the challenge
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaPasswordResetOtpChallenge challenge = em.find(JpaPasswordResetOtpChallenge.class, key);
            em.remove(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify user still exists in database via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users WHERE id = '" + userId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Order(16)
    @DisplayName("User Deletion Cascade: Deleting a user cascades to its password-reset challenges")
    void test16_deletingUserCascadesToPasswordResetChallenges() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("user-cascade-" + UUID.randomUUID());
        String userId;

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "cascade-target");
            userId = user.getId();
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Delete the user via JDBC (triggering ON DELETE CASCADE)
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            int rowsDeleted = stmt.executeUpdate("DELETE FROM users WHERE id = '" + userId + "'");
            assertThat(rowsDeleted).isEqualTo(1);

            // Verify challenge row was deleted via cascade
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM password_reset_otp_challenges WHERE email_key = '" + key + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(17)
    @DisplayName("Unique challenge_id: uq_pwd_reset_challenge_id rejects duplicate challenge identifiers")
    void test17_uniqueChallengeIdConstraintRejectsDuplicates() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String sharedChallengeId = "ch-shared-" + UUID.randomUUID();
        String key1 = sha256Hex("unique-ch-1-" + UUID.randomUUID());
        String key2 = sha256Hex("unique-ch-2-" + UUID.randomUUID());

        String userId;
        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "unique-ch");
            userId = user.getId();
            JpaPasswordResetOtpChallenge chal1 = new JpaPasswordResetOtpChallenge(
                    key1,
                    sharedChallengeId,
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(chal1);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Attempt second challenge with the same challenge_id
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaUser managedUser = em.find(JpaUser.class, userId);
            JpaPasswordResetOtpChallenge chal2 = new JpaPasswordResetOtpChallenge(
                    key2,
                    sharedChallengeId,
                    managedUser,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(chal2);
            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(18)
    @DisplayName("Password Reset Status Enums: ACTIVE, PROCESSING, LOCKED, CONSUMED round-trip")
    void test18_passwordResetChallengeStatusEnumRoundTrip() throws Exception {
        for (PasswordResetChallengeStatus status : PasswordResetChallengeStatus.values()) {
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            String key = sha256Hex("pr-status-" + status + "-" + UUID.randomUUID());

            try {
                tx.begin();
                JpaUser user = createAndPersistUser(em, "pr-status-" + status);
                JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                        key,
                        "ch-" + UUID.randomUUID(),
                        user,
                        PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                        status,
                        Instant.now().plus(15, ChronoUnit.MINUTES)
                );
                em.persist(challenge);
                em.flush();
                tx.commit();
            } catch (Exception e) {
                if (tx.isActive()) tx.rollback();
                throw e;
            } finally {
                em.close();
            }

            // Direct JDBC check
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT status FROM password_reset_otp_challenges WHERE email_key = '" + key + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo(status.name());
            }

            // Entity reload check
            em = emf.createEntityManager();
            try {
                JpaPasswordResetOtpChallenge reloaded = em.find(JpaPasswordResetOtpChallenge.class, key);
                assertThat(reloaded.getStatus()).isEqualTo(status);
            } finally {
                em.close();
            }
        }
    }

    @Test
    @Order(19)
    @DisplayName("Purpose Enum Persistence: Persists and reloads PASSWORD_RESET as exact string")
    void test19_purposeEnumPersistsAsCorrectStringValue() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("purpose-" + UUID.randomUUID());

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "purpose");
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT purpose FROM password_reset_otp_challenges WHERE email_key = '" + key + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("purpose")).isEqualTo("PASSWORD_RESET");
        }
    }

    @Test
    @Order(20)
    @DisplayName("Consumed State: Nullable code_digest supports consumed challenge state")
    void test20_nullableCodeDigestSupportsConsumedChallengeState() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("consumed-" + UUID.randomUUID());

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "consumed");
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            challenge.setCodeDigest("$2a$10$activeDigest123");
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Consume the challenge: set codeDigest to null, status to CONSUMED, consumedAt to now
        em = emf.createEntityManager();
        tx = em.getTransaction();
        Instant consumedInstant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        try {
            tx.begin();
            JpaPasswordResetOtpChallenge loaded = em.find(JpaPasswordResetOtpChallenge.class, key);
            loaded.setStatus(PasswordResetChallengeStatus.CONSUMED);
            loaded.setCodeDigest(null);
            loaded.setConsumedAt(consumedInstant);
            loaded.setPasswordUpdated(true);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Direct SQL check
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT code_digest, status, password_updated, consumed_at FROM password_reset_otp_challenges WHERE email_key = '" + key + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("code_digest")).isNull();
            assertThat(rs.getString("status")).isEqualTo("CONSUMED");
            assertThat(rs.getBoolean("password_updated")).isTrue();
            assertThat(rs.getTimestamp("consumed_at").toInstant().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(consumedInstant);
        }
    }

    @Test
    @Order(21)
    @DisplayName("Password Updated & Consumed At: Fields persist accurately")
    void test21_passwordUpdatedAndConsumedAtFieldsPersistAccurately() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("pwd-updated-" + UUID.randomUUID());
        Instant consumedAt = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "pwd-upd");
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.CONSUMED,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            challenge.setPasswordUpdated(true);
            challenge.setConsumedAt(consumedAt);
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaPasswordResetOtpChallenge reloaded = em.find(JpaPasswordResetOtpChallenge.class, key);
            assertThat(reloaded.isPasswordUpdated()).isTrue();
            assertThat(reloaded.getConsumedAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(consumedAt);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(22)
    @DisplayName("Operation IDs: reset_operation_id and send_operation_id persist correctly")
    void test22_resetOperationIdAndSendOperationIdPersistCorrectly() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String key = sha256Hex("op-ids-" + UUID.randomUUID());
        String resetOpId = "reset-op-" + UUID.randomUUID();
        String sendOpId = "send-op-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "op-ids");
            JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                    key,
                    "ch-" + UUID.randomUUID(),
                    user,
                    PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                    PasswordResetChallengeStatus.ACTIVE,
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
            challenge.setResetOperationId(resetOpId);
            challenge.setSendOperationId(sendOpId);
            em.persist(challenge);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        try {
            JpaPasswordResetOtpChallenge reloaded = em.find(JpaPasswordResetOtpChallenge.class, key);
            assertThat(reloaded.getResetOperationId()).isEqualTo(resetOpId);
            assertThat(reloaded.getSendOperationId()).isEqualTo(sendOpId);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(23)
    @DisplayName("Challenge Expiration Boundaries: Helper methods correctly evaluate expiration edges")
    void test23_challengeExpirationBoundariesRespected() {
        Instant boundary = Instant.parse("2026-11-01T12:00:00Z");

        JpaSignupOtpChallenge signup = new JpaSignupOtpChallenge();
        signup.setChallengeExpiresAt(boundary);
        signup.setOtpExpiresAt(boundary);
        signup.setNextResendAt(boundary);
        signup.setSendLeaseUntil(boundary);

        // Before boundary -> not expired, resend not allowed, lease active
        Instant before = boundary.minusSeconds(1);
        assertThat(signup.isChallengeExpired(before)).isFalse();
        assertThat(signup.isOtpExpired(before)).isFalse();
        assertThat(signup.isResendAllowed(before)).isFalse();
        assertThat(signup.isSendLeaseActive(before)).isTrue();

        // Exactly at boundary -> expired, resend allowed, lease not active
        assertThat(signup.isChallengeExpired(boundary)).isTrue();
        assertThat(signup.isOtpExpired(boundary)).isTrue();
        assertThat(signup.isResendAllowed(boundary)).isTrue();
        assertThat(signup.isSendLeaseActive(boundary)).isFalse();

        // After boundary -> expired, resend allowed, lease not active
        Instant after = boundary.plusSeconds(1);
        assertThat(signup.isChallengeExpired(after)).isTrue();
        assertThat(signup.isOtpExpired(after)).isTrue();
        assertThat(signup.isResendAllowed(after)).isTrue();
        assertThat(signup.isSendLeaseActive(after)).isFalse();

        // Repeat for password reset challenge
        JpaPasswordResetOtpChallenge reset = new JpaPasswordResetOtpChallenge();
        reset.setChallengeExpiresAt(boundary);
        reset.setCodeExpiresAt(boundary);
        reset.setNextResendAt(boundary);
        reset.setSendLeaseUntil(boundary);

        assertThat(reset.isChallengeExpired(before)).isFalse();
        assertThat(reset.isCodeExpired(before)).isFalse();
        assertThat(reset.isChallengeExpired(boundary)).isTrue();
        assertThat(reset.isCodeExpired(boundary)).isTrue();
        assertThat(reset.isResendAllowed(boundary)).isTrue();
        assertThat(reset.isSendLeaseActive(boundary)).isFalse();

        // Fail-closed security semantics: missing (null) expiry is treated as expired
        signup.setChallengeExpiresAt(null);
        assertThat(signup.isChallengeExpired(before)).isTrue();
        assertThat(signup.isChallengeExpired()).isTrue();

        reset.setChallengeExpiresAt(null);
        assertThat(reset.isChallengeExpired(before)).isTrue();
        assertThat(reset.isChallengeExpired()).isTrue();
    }

    @Test
    @Order(24)
    @DisplayName("Jackson Serialization Safeguards: Sensitive fields excluded from JSON serialization")
    void test24_sensitiveFieldsExcludedFromJsonSerialization() throws Exception {
        JpaUser user = new JpaUser(
                "user-pwd-sec-" + UUID.randomUUID(),
                "Bob Security",
                "bob." + UUID.randomUUID() + "@example.com",
                "519-555-0188",
                "MEMBER"
        );
        user.setPassword("SecretBcryptHash$2a$10$secret1234567890");
        user.setTokenVersion(17L);

        String secretCodeDigest = "$2a$10$secretResetCodeDigest999";
        String secretResetOpId = "reset-op-secret-111";

        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                sha256Hex("sec-json-" + UUID.randomUUID()),
                "ch-sec-" + UUID.randomUUID(),
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );
        challenge.setCodeDigest(secretCodeDigest);
        challenge.setResetOperationId(secretResetOpId);
        challenge.setClientIdempotencyKey("secret-idemp-key-555");
        challenge.setSendOperationId("secret-send-op-444");

        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        String json = mapper.writeValueAsString(challenge);

        // Exposes userId safely
        assertThat(json).contains("\"userId\":\"" + user.getId() + "\"");

        // Never leaks code digest, user object, password, tokenVersion, or operation secrets
        assertThat(json).doesNotContain(secretCodeDigest);
        assertThat(json).doesNotContain("codeDigest");
        assertThat(json).doesNotContain("code_digest");
        assertThat(json).doesNotContain("SecretBcryptHash");
        assertThat(json).doesNotContain("\"password\":");
        assertThat(json).doesNotContain("\"tokenVersion\":");
        assertThat(json).doesNotContain(secretResetOpId);
        assertThat(json).doesNotContain("resetOperationId");
        assertThat(json).doesNotContain("secret-idemp-key-555");
        assertThat(json).doesNotContain("clientIdempotencyKey");
        assertThat(json).doesNotContain("secret-send-op-444");
        assertThat(json).doesNotContain("sendOperationId");
        assertThat(json).doesNotContain("\"user\":");
    }

    @Test
    @Order(25)
    @DisplayName("Logging Safeguards: toString excludes raw codes, digests, emailKey, challengeId, userId, and operation identifiers")
    void test25_loggingSafeguards_toStringExcludesSensitiveIdentifiers() {
        JpaSignupOtpChallenge signup = new JpaSignupOtpChallenge(
                sha256Hex("test-signup-log@example.com"),
                "Normalized.Signup.Log@Example.com",
                "ch-signup-log-9999",
                OtpChallengeStatus.ACTIVE,
                "$2a$10$secretSignupOtpDigest12345",
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );
        signup.setOtpExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        signup.setSendOperationId("send-op-signup-secret-111");
        signup.setClientIdempotencyKey("client-idemp-signup-222");

        String signupLog = signup.toString();

        // Must NOT contain sensitive identifiers, contact info, operation secrets, or digests
        assertThat(signupLog).doesNotContain(signup.getEmailKey());
        assertThat(signupLog).doesNotContain("Normalized.Signup.Log@Example.com");
        assertThat(signupLog).doesNotContain("ch-signup-log-9999");
        assertThat(signupLog).doesNotContain("$2a$10$secretSignupOtpDigest12345");
        assertThat(signupLog).doesNotContain("send-op-signup-secret-111");
        assertThat(signupLog).doesNotContain("client-idemp-signup-222");
        assertThat(signupLog).doesNotContain("emailKey");
        assertThat(signupLog).doesNotContain("challengeId");
        assertThat(signupLog).doesNotContain("sendOperationId");

        // Must retain operational flags
        assertThat(signupLog).contains("status=ACTIVE");
        assertThat(signupLog).contains("resendCount=0");

        JpaUser user = new JpaUser(
                "user-log-id-8888",
                "Alice Logger",
                "alice.log@example.com",
                "519-555-0199",
                "MEMBER"
        );
        user.setPassword("secretPasswordHash");

        JpaPasswordResetOtpChallenge reset = new JpaPasswordResetOtpChallenge(
                sha256Hex("test-reset-log@example.com"),
                "ch-reset-log-7777",
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(15, ChronoUnit.MINUTES)
        );
        reset.setCodeDigest("$2a$10$secretResetDigest777");
        reset.setResetOperationId("reset-op-secret-888");
        reset.setSendOperationId("send-op-secret-999");
        reset.setClientIdempotencyKey("client-idemp-reset-333");

        String resetLog = reset.toString();

        // Must NOT contain sensitive identifiers, user ID, challenge ID, operation secrets, or digests
        assertThat(resetLog).doesNotContain(reset.getEmailKey());
        assertThat(resetLog).doesNotContain("ch-reset-log-7777");
        assertThat(resetLog).doesNotContain("user-log-id-8888");
        assertThat(resetLog).doesNotContain("Alice Logger");
        assertThat(resetLog).doesNotContain("alice.log@example.com");
        assertThat(resetLog).doesNotContain("secretPasswordHash");
        assertThat(resetLog).doesNotContain("$2a$10$secretResetDigest777");
        assertThat(resetLog).doesNotContain("reset-op-secret-888");
        assertThat(resetLog).doesNotContain("send-op-secret-999");
        assertThat(resetLog).doesNotContain("client-idemp-reset-333");
        assertThat(resetLog).doesNotContain("challengeId");
        assertThat(resetLog).doesNotContain("userId");
        assertThat(resetLog).doesNotContain("emailKey");

        // Must retain operational state
        assertThat(resetLog).contains("status=ACTIVE");
        assertThat(resetLog).contains("purpose=PASSWORD_RESET");
    }

    @Test
    @Order(26)
    @DisplayName("Spring Boot Minimal Context: Boots and validates all 15 JPA entities with ddl-auto=validate")
    void test26_springBootContext_bootsAndValidatesAllEntities() {
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

            // Verify all 15 JPA entity types are registered and validated
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
        });
    }
}
