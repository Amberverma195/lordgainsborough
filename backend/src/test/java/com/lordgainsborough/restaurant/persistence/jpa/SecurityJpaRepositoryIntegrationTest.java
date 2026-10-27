package com.lordgainsborough.restaurant.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
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
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCartRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCatalogMigrationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaLoginFailureStateRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaOrderReviewRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaPasswordResetOtpChallengeRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaReservationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaSignupAbuseLimitRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaSignupOtpChallengeRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaStoreSettingsRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-011: Integration test suite for Security Challenge and Abuse-Limit Spring Data JPA repositories.
 * <p>
 * Tests {@link JpaSignupOtpChallengeRepository}, {@link JpaPasswordResetOtpChallengeRepository},
 * {@link JpaSignupAbuseLimitRepository}, and {@link JpaLoginFailureStateRepository} against a
 * disposable PostgreSQL 17 instance initialized via Flyway V1.
 * </p>
 * <p>
 * Verifies all 31 core requirements covering Spring Data JPA proxy initialization, query execution,
 * foreign key cascade/preservation, constraint enforcement, exact expiration boundary semantics,
 * Jackson serialization safeguards, and production default-profile startup isolation.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SecurityJpaRepositoryIntegrationTest {

    private static final String TEST_DB_PREFIX = "sec_jpa_repo_test_";
    private String testDb;
    private String testPort;
    private static String testJdbcUrl;
    private boolean databaseCreatedByTest = false;

    private AnnotationConfigApplicationContext context;
    private EntityManagerFactory entityManagerFactory;
    private ObjectMapper objectMapper;

    // PG-011 Repositories under test
    private JpaSignupOtpChallengeRepository signupOtpRepository;
    private JpaPasswordResetOtpChallengeRepository passwordResetRepository;
    private JpaSignupAbuseLimitRepository abuseLimitRepository;
    private JpaLoginFailureStateRepository loginFailureRepository;

    // Foreign-key parent repository
    private JpaUserRepository userRepository;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableJpaRepositories(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.repository")
    @EntityScan(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.entity")
    static class TestJpaRepoConfig {

        @Bean
        public DataSource dataSource() {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(testJdbcUrl);
            ds.setUser("postgres");
            ds.setPassword("postgres");
            return ds;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
            emfBean.setDataSource(dataSource);
            emfBean.setPackagesToScan("com.lordgainsborough.restaurant.persistence.jpa.entity");
            emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

            Properties props = new Properties();
            props.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            props.setProperty("hibernate.hbm2ddl.auto", "validate");
            props.setProperty("hibernate.show_sql", "false");
            emfBean.setJpaProperties(props);

            return emfBean;
        }

        @Bean
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    private String getRequiredPort() {
        String port = com.lordgainsborough.restaurant.support.LocalPostgres.requirePort();
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-011 integration tests")
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
        testDb = TEST_DB_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        provisionDisposableDatabaseSafely(testDb);

        testJdbcUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/" + testDb + "?sslmode=disable";

        // Execute Flyway V1 migration
        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        // Initialize Spring JPA context
        context = new AnnotationConfigApplicationContext(TestJpaRepoConfig.class);
        entityManagerFactory = context.getBean(EntityManagerFactory.class);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        signupOtpRepository = context.getBean(JpaSignupOtpChallengeRepository.class);
        passwordResetRepository = context.getBean(JpaPasswordResetOtpChallengeRepository.class);
        abuseLimitRepository = context.getBean(JpaSignupAbuseLimitRepository.class);
        loginFailureRepository = context.getBean(JpaLoginFailureStateRepository.class);
        userRepository = context.getBean(JpaUserRepository.class);
    }

    private DisposableDatabaseSession mainDatabaseSession;

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

        boolean isCreatedAndOwned() {
            return createdAndOwned;
        }
    }

    @AfterAll
    void tearDownAll() {
        if (context != null) {
            context.close();
        }
        if (mainDatabaseSession != null) {
            mainDatabaseSession.drop();
        }
    }

    private void provisionDisposableDatabaseSafely(String dbName) throws Exception {
        mainDatabaseSession = new DisposableDatabaseSession(testPort, dbName);
        mainDatabaseSession.provision();
        this.databaseCreatedByTest = mainDatabaseSession.isCreatedAndOwned();
    }

    private void dropDisposableDatabaseSafely(String dbName) {
        if (mainDatabaseSession != null) {
            mainDatabaseSession.drop();
        }
    }

    // =========================================================================
    // Requirement 1 - 3: Repository Proxies, Isolation & Entity Metamodel
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Req 1: Four new JPA repository proxies initialize successfully")
    void test01_fourNewJpaRepositoryProxiesInitialize() {
        assertThat(signupOtpRepository).isNotNull();
        assertThat(passwordResetRepository).isNotNull();
        assertThat(abuseLimitRepository).isNotNull();
        assertThat(loginFailureRepository).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Req 2: No MongoDB repositories register as JPA repositories")
    void test02_noMongoDBRepositoriesRegisterAsJpaRepositories() {
        assertThat(context.containsBean("signupOtpChallengeRepository")).isFalse();
        assertThat(context.containsBean("passwordResetOtpChallengeRepository")).isFalse();
        assertThat(context.containsBean("signupAbuseLimitRepository")).isFalse();
        assertThat(context.containsBean("loginFailureStateRepository")).isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("Req 3: Hibernate ddl-auto=validate recognizes all 17 JPA metamodel entities")
    void test03_hibernateMetamodelValidatesAll17Entities() {
        var metamodel = entityManagerFactory.getMetamodel();

        // 5 Core entities (PG-004)
        assertThat(metamodel.entity(JpaUser.class)).isNotNull();
        assertThat(metamodel.entity(JpaCategory.class)).isNotNull();
        assertThat(metamodel.entity(JpaMenuItem.class)).isNotNull();
        assertThat(metamodel.entity(JpaCatalogMigration.class)).isNotNull();
        assertThat(metamodel.entity(JpaStoreSettings.class)).isNotNull();

        // 8 Operational entities (PG-005, PG-006)
        assertThat(metamodel.entity(JpaCustomerOrder.class)).isNotNull();
        assertThat(metamodel.entity(JpaOrderItem.class)).isNotNull();
        assertThat(metamodel.entity(JpaOrderMessage.class)).isNotNull();
        assertThat(metamodel.entity(JpaCart.class)).isNotNull();
        assertThat(metamodel.entity(JpaCartItem.class)).isNotNull();
        assertThat(metamodel.entity(JpaOrderReview.class)).isNotNull();
        assertThat(metamodel.entity(JpaOrderReviewItem.class)).isNotNull();
        assertThat(metamodel.entity(JpaReservation.class)).isNotNull();

        // 4 Security entities (PG-007, PG-008)
        assertThat(metamodel.entity(JpaSignupOtpChallenge.class)).isNotNull();
        assertThat(metamodel.entity(JpaPasswordResetOtpChallenge.class)).isNotNull();
        assertThat(metamodel.entity(JpaSignupAbuseLimit.class)).isNotNull();
        assertThat(metamodel.entity(JpaLoginFailureState.class)).isNotNull();
    }

    // =========================================================================
    // Requirement 4 - 10: Signup OTP Challenge Repository
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("Req 4: Signup OTP challenge save and findById work")
    void test04_signupOtpSaveAndFindById() {
        String email = "signup.crud." + UUID.randomUUID() + "@example.com";
        String emailKey = sha256Hex(email);
        String challengeId = "ch-signup-" + UUID.randomUUID();

        JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                emailKey,
                email,
                challengeId,
                OtpChallengeStatus.ACTIVE,
                "$2a$10$syntheticDigestForSignupCrudTest00000000000000000000000",
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        signupOtpRepository.saveAndFlush(challenge);

        Optional<JpaSignupOtpChallenge> found = signupOtpRepository.findById(emailKey);
        assertThat(found).isPresent();
        assertThat(found.get().getEmailKey()).isEqualTo(emailKey);
        assertThat(found.get().getChallengeId()).isEqualTo(challengeId);
        assertThat(found.get().getStatus()).isEqualTo(OtpChallengeStatus.ACTIVE);
    }

    @Test
    @Order(5)
    @DisplayName("Req 5: Assigned signup emailKey remains unchanged")
    void test05_assignedSignupEmailKeyRemainsUnchanged() {
        String email = "signup.key." + UUID.randomUUID() + "@example.com";
        String emailKey = sha256Hex(email);

        JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                emailKey,
                email,
                "ch-key-" + UUID.randomUUID(),
                OtpChallengeStatus.ACTIVE,
                "$2a$10$syntheticDigestForKeyPreservationTest00000000000000000",
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        JpaSignupOtpChallenge saved = signupOtpRepository.saveAndFlush(challenge);
        assertThat(saved.getEmailKey()).isEqualTo(emailKey);

        JpaSignupOtpChallenge reloaded = signupOtpRepository.findById(emailKey).orElseThrow();
        assertThat(reloaded.getEmailKey()).isEqualTo(emailKey);
    }

    @Test
    @Order(6)
    @DisplayName("Req 6: Signup challenge update and reload work and advance updatedAt via @PreUpdate")
    void test06_signupChallengeUpdateAndReload() throws Exception {
        String email = "signup.upd." + UUID.randomUUID() + "@example.com";
        String emailKey = sha256Hex(email);

        JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                emailKey,
                email,
                "ch-upd-" + UUID.randomUUID(),
                OtpChallengeStatus.ACTIVE,
                "$2a$10$syntheticDigestForUpdateTest00000000000000000000000000",
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        JpaSignupOtpChallenge saved = signupOtpRepository.saveAndFlush(challenge);
        Instant initialUpdatedAt = saved.getUpdatedAt();
        assertThat(initialUpdatedAt).isNotNull();

        // Brief delay to ensure clock tick between initial persist and update
        Thread.sleep(15);

        JpaSignupOtpChallenge toUpdate = signupOtpRepository.findById(emailKey).orElseThrow();
        toUpdate.setStatus(OtpChallengeStatus.VERIFIED);
        toUpdate.setResendCount(2);
        toUpdate.setFailedVerifyAttempts(1);
        // Note: toUpdate.updatedAt is NOT manually updated here; JPA @PreUpdate must advance it
        signupOtpRepository.saveAndFlush(toUpdate);

        JpaSignupOtpChallenge reloaded = signupOtpRepository.findById(emailKey).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OtpChallengeStatus.VERIFIED);
        assertThat(reloaded.getResendCount()).isEqualTo(2);
        assertThat(reloaded.getFailedVerifyAttempts()).isEqualTo(1);
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isAfter(initialUpdatedAt);
    }

    @Test
    @Order(7)
    @DisplayName("Req 7: Signup challenge existsById and deleteById work")
    void test07_signupChallengeExistsByIdAndDeleteById() {
        String email = "signup.del." + UUID.randomUUID() + "@example.com";
        String emailKey = sha256Hex(email);

        JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                emailKey,
                email,
                "ch-del-" + UUID.randomUUID(),
                OtpChallengeStatus.ACTIVE,
                "$2a$10$syntheticDigestForDeleteTest00000000000000000000000000",
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        signupOtpRepository.saveAndFlush(challenge);

        assertThat(signupOtpRepository.existsById(emailKey)).isTrue();
        assertThat(signupOtpRepository.existsById("non-existent-email-key")).isFalse();

        signupOtpRepository.deleteById(emailKey);

        assertThat(signupOtpRepository.existsById(emailKey)).isFalse();
        assertThat(signupOtpRepository.findById(emailKey)).isEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("Req 8: Signup challenge enum and timestamp values persist correctly")
    void test08_signupChallengeEnumAndTimestampValuesPersistCorrectly() {
        String email = "signup.enums." + UUID.randomUUID() + "@example.com";
        String emailKey = sha256Hex(email);

        Instant nextResend = Instant.now().plus(30, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MILLIS);
        Instant otpExpiry = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant challengeExpiry = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant sendLease = Instant.now().plus(60, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MILLIS);

        JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                emailKey,
                email,
                "ch-enums-" + UUID.randomUUID(),
                OtpChallengeStatus.ACTIVE,
                "$2a$10$syntheticDigestForEnumTest000000000000000000000000000",
                challengeExpiry
        );
        challenge.setNextResendAt(nextResend);
        challenge.setOtpExpiresAt(otpExpiry);
        challenge.setSendOperationId("send-op-" + UUID.randomUUID());
        challenge.setSendStatus(OtpSendStatus.SENT);
        challenge.setSendLeaseUntil(sendLease);
        challenge.setClientIdempotencyKey("idem-key-" + UUID.randomUUID());
        challenge.setLastSendWasInitial(false);

        signupOtpRepository.saveAndFlush(challenge);

        JpaSignupOtpChallenge reloaded = signupOtpRepository.findById(emailKey).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OtpChallengeStatus.ACTIVE);
        assertThat(reloaded.getSendStatus()).isEqualTo(OtpSendStatus.SENT);
        assertThat(reloaded.getNextResendAt()).isEqualTo(nextResend);
        assertThat(reloaded.getOtpExpiresAt()).isEqualTo(otpExpiry);
        assertThat(reloaded.getChallengeExpiresAt()).isEqualTo(challengeExpiry);
        assertThat(reloaded.getSendLeaseUntil()).isEqualTo(sendLease);
        assertThat(reloaded.isLastSendWasInitial()).isFalse();
    }

    @Test
    @Order(9)
    @DisplayName("Req 9: Signup challenge digest and protected fields do not appear in JSON")
    void test09_signupChallengeDigestAndProtectedFieldsDoNotAppearInJson() throws Exception {
        JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                "protected-email-key-123456",
                "secret.user@example.com",
                "ch-json-1234",
                OtpChallengeStatus.ACTIVE,
                "$2a$10$superSecretDigestThatMustNeverLeak000000000000000000000",
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        challenge.setSendOperationId("op-secret-123");
        challenge.setClientIdempotencyKey("idem-secret-456");

        String json = objectMapper.writeValueAsString(challenge);

        assertThat(json).doesNotContain("superSecretDigest");
        assertThat(json).doesNotContain("secret.user@example.com");
        assertThat(json).doesNotContain("op-secret-123");
        assertThat(json).doesNotContain("idem-secret-456");

        assertThat(json).contains("ch-json-1234");
        assertThat(json).contains("ACTIVE");
    }

    @Test
    @Order(10)
    @DisplayName("Req 10: Signup challenge expiration boundaries and queries are correct")
    void test10_signupChallengeExpirationBoundariesAreCorrect() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        Instant cutoff = Instant.parse("2026-10-26T12:00:00Z");

        // 1. Expired record (before cutoff)
        JpaSignupOtpChallenge expired = new JpaSignupOtpChallenge(
                sha256Hex("exp1." + testSuffix + "@test.com"),
                "exp1." + testSuffix + "@test.com",
                "ch-exp-" + testSuffix,
                OtpChallengeStatus.ACTIVE,
                "$2a$10$synthDigest0000000000000000000000000000000000000000000",
                cutoff.minusSeconds(10)
        );

        // 2. Exact boundary record
        JpaSignupOtpChallenge boundary = new JpaSignupOtpChallenge(
                sha256Hex("bnd1." + testSuffix + "@test.com"),
                "bnd1." + testSuffix + "@test.com",
                "ch-bnd-" + testSuffix,
                OtpChallengeStatus.ACTIVE,
                "$2a$10$synthDigest0000000000000000000000000000000000000000000",
                cutoff
        );

        // 3. Future unexpired record
        JpaSignupOtpChallenge future = new JpaSignupOtpChallenge(
                sha256Hex("fut1." + testSuffix + "@test.com"),
                "fut1." + testSuffix + "@test.com",
                "ch-fut-" + testSuffix,
                OtpChallengeStatus.ACTIVE,
                "$2a$10$synthDigest0000000000000000000000000000000000000000000",
                cutoff.plusSeconds(10)
        );

        signupOtpRepository.saveAllAndFlush(List.of(expired, boundary, future));

        List<JpaSignupOtpChallenge> expiredList = signupOtpRepository.findByChallengeExpiresAtLessThanEqual(cutoff);
        List<String> expiredKeys = expiredList.stream().map(JpaSignupOtpChallenge::getEmailKey).toList();

        assertThat(expiredKeys).contains(expired.getEmailKey(), boundary.getEmailKey());
        assertThat(expiredKeys).doesNotContain(future.getEmailKey());

        // Entity helper expiration boundary assertions
        assertThat(boundary.isChallengeExpired(cutoff.minusMillis(1))).isFalse();
        assertThat(boundary.isChallengeExpired(cutoff)).isTrue();
        assertThat(boundary.isChallengeExpired(cutoff.plusMillis(1))).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("Req 10b: Signup challenge supplementary queries (challengeId, status, idempotencyKey)")
    void test10b_signupChallengeSupplementaryQueries() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        String sharedChallengeId = "ch-shared-" + testSuffix;
        String idempotencyKey = "idem-test-" + testSuffix;

        // V1 schema allows duplicate challenge_id (non-unique index)
        JpaSignupOtpChallenge ch1 = new JpaSignupOtpChallenge(
                sha256Hex("sup1." + testSuffix + "@test.com"),
                "sup1." + testSuffix + "@test.com",
                sharedChallengeId,
                OtpChallengeStatus.ACTIVE,
                "$2a$10$synthDigest0000000000000000000000000000000000000000000",
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        ch1.setClientIdempotencyKey(idempotencyKey);

        JpaSignupOtpChallenge ch2 = new JpaSignupOtpChallenge(
                sha256Hex("sup2." + testSuffix + "@test.com"),
                "sup2." + testSuffix + "@test.com",
                sharedChallengeId,
                OtpChallengeStatus.VERIFIED,
                "$2a$10$synthDigest0000000000000000000000000000000000000000000",
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        signupOtpRepository.saveAllAndFlush(List.of(ch1, ch2));

        // findByChallengeId returns List
        List<JpaSignupOtpChallenge> byChallengeId = signupOtpRepository.findByChallengeId(sharedChallengeId);
        assertThat(byChallengeId).hasSize(2);

        // findByStatus
        List<JpaSignupOtpChallenge> activeList = signupOtpRepository.findByStatus(OtpChallengeStatus.ACTIVE);
        assertThat(activeList.stream().anyMatch(c -> c.getEmailKey().equals(ch1.getEmailKey()))).isTrue();

        // findByClientIdempotencyKey
        List<JpaSignupOtpChallenge> byIdem = signupOtpRepository.findByClientIdempotencyKey(idempotencyKey);
        assertThat(byIdem).hasSize(1);
        assertThat(byIdem.get(0).getEmailKey()).isEqualTo(ch1.getEmailKey());
    }

    // =========================================================================
    // Requirement 11 - 19: Password Reset OTP Challenge Repository
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("Req 11: Password-reset challenge save and findById work")
    void test11_passwordResetChallengeSaveAndFindById() {
        String userId = "usr-pwd11-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Reset User 11", "reset11." + UUID.randomUUID() + "@example.com", "555-0211", "MEMBER");
        userRepository.save(user);

        String emailKey = sha256Hex("reset11@example.com");
        String challengeId = "ch-pwd-11-" + UUID.randomUUID();

        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                emailKey,
                challengeId,
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        passwordResetRepository.saveAndFlush(challenge);

        Optional<JpaPasswordResetOtpChallenge> found = passwordResetRepository.findById(emailKey);
        assertThat(found).isPresent();
        assertThat(found.get().getChallengeId()).isEqualTo(challengeId);
        assertThat(found.get().getUser().getId()).isEqualTo(userId);
    }

    @Test
    @Order(12)
    @DisplayName("Req 12: findByChallengeId resolves the correct challenge")
    void test12_findByChallengeIdResolvesCorrectChallenge() {
        String userId = "usr-pwd12-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Reset User 12", "reset12." + UUID.randomUUID() + "@example.com", "555-0212", "MEMBER");
        userRepository.save(user);

        String emailKey = sha256Hex("reset12@example.com");
        String challengeId = "ch-pwd-12-" + UUID.randomUUID();

        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                emailKey,
                challengeId,
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        passwordResetRepository.saveAndFlush(challenge);

        Optional<JpaPasswordResetOtpChallenge> found = passwordResetRepository.findByChallengeId(challengeId);
        assertThat(found).isPresent();
        assertThat(found.get().getEmailKey()).isEqualTo(emailKey);
    }

    @Test
    @Order(13)
    @DisplayName("Req 13: Missing challengeId returns Optional.empty()")
    void test13_missingChallengeIdReturnsOptionalEmpty() {
        Optional<JpaPasswordResetOtpChallenge> missing = passwordResetRepository.findByChallengeId("non-existent-ch-id");
        assertThat(missing).isEmpty();
    }

    @Test
    @Order(14)
    @DisplayName("Req 14: Duplicate reset challengeId violates unique constraint")
    void test14_duplicateResetChallengeIdViolatesUniqueConstraint() {
        String userId = "usr-dupch-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Dup User", "dupch." + UUID.randomUUID() + "@example.com", "555-0214", "MEMBER");
        userRepository.save(user);

        String sharedChallengeId = "ch-shared-dup-" + UUID.randomUUID();

        JpaPasswordResetOtpChallenge ch1 = new JpaPasswordResetOtpChallenge(
                sha256Hex("user1.dup@test.com"),
                sharedChallengeId,
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        passwordResetRepository.saveAndFlush(ch1);

        JpaPasswordResetOtpChallenge ch2 = new JpaPasswordResetOtpChallenge(
                sha256Hex("user2.dup@test.com"),
                sharedChallengeId,
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        assertThatThrownBy(() -> passwordResetRepository.saveAndFlush(ch2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(15)
    @DisplayName("Req 15: Password-reset challenge user FK is enforced")
    void test15_passwordResetChallengeUserFkIsEnforced() {
        JpaUser ghostUser = new JpaUser("usr-nonexistent-" + UUID.randomUUID(), "Ghost", "ghost@example.com", "555-0000", "MEMBER");

        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                sha256Hex("ghost.pwd@test.com"),
                "ch-ghost-" + UUID.randomUUID(),
                ghostUser,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        assertThatThrownBy(() -> passwordResetRepository.saveAndFlush(challenge))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(16)
    @DisplayName("Req 16: Challenge deletion does not delete its user")
    void test16_challengeDeletionDoesNotDeleteItsUser() {
        String userId = "usr-delch-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Surviving User", "delch." + UUID.randomUUID() + "@example.com", "555-0216", "MEMBER");
        userRepository.save(user);

        String emailKey = sha256Hex("survive.user@example.com");
        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                emailKey,
                "ch-survive-" + UUID.randomUUID(),
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        passwordResetRepository.saveAndFlush(challenge);

        passwordResetRepository.deleteById(emailKey);

        assertThat(passwordResetRepository.findById(emailKey)).isEmpty();
        assertThat(userRepository.findById(userId)).isPresent();
    }

    @Test
    @Order(17)
    @DisplayName("Req 17: User deletion cascades to password-reset challenges according to V1")
    void test17_userDeletionCascadesToPasswordResetChallenges() throws Exception {
        String userId = "usr-cascade-pwd-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Cascade Target", "cascadetarget." + UUID.randomUUID() + "@example.com", "555-0217", "MEMBER");
        userRepository.save(user);

        String emailKey = sha256Hex("cascade.pwd@example.com");
        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                emailKey,
                "ch-cascade-" + UUID.randomUUID(),
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        passwordResetRepository.saveAndFlush(challenge);

        // Delete user directly via JDBC to verify ON DELETE CASCADE constraint in PostgreSQL
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            stmt.setString(1, userId);
            int rowsDeleted = stmt.executeUpdate();
            assertThat(rowsDeleted).isEqualTo(1);
        }

        // Verify challenge record was deleted via PostgreSQL CASCADE
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM password_reset_otp_challenges WHERE email_key = ?")) {
            stmt.setString(1, emailKey);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(18)
    @DisplayName("Req 18: Password-reset status, purpose and consumed-state fields round-trip")
    void test18_passwordResetStatusPurposeAndConsumedStateFieldsRoundTrip() {
        String userId = "usr-consumed-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Consumed User", "consumed." + UUID.randomUUID() + "@example.com", "555-0218", "MEMBER");
        userRepository.save(user);

        String emailKey = sha256Hex("consumed.pwd@example.com");
        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                emailKey,
                "ch-consumed-" + UUID.randomUUID(),
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        challenge.setCodeDigest("$2a$10$syntheticDigestBeforeConsumption00000000000000000");
        passwordResetRepository.saveAndFlush(challenge);

        // Transition to consumed state: codeDigest becomes null, passwordUpdated = true, consumedAt set
        JpaPasswordResetOtpChallenge reloaded = passwordResetRepository.findById(emailKey).orElseThrow();
        reloaded.setStatus(PasswordResetChallengeStatus.CONSUMED);
        reloaded.setCodeDigest(null);
        reloaded.setPasswordUpdated(true);
        Instant consumedInstant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        reloaded.setConsumedAt(consumedInstant);
        reloaded.setResetOperationId("rst-op-" + UUID.randomUUID());
        passwordResetRepository.saveAndFlush(reloaded);

        JpaPasswordResetOtpChallenge fresh = passwordResetRepository.findById(emailKey).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PasswordResetChallengeStatus.CONSUMED);
        assertThat(fresh.getCodeDigest()).isNull();
        assertThat(fresh.isPasswordUpdated()).isTrue();
        assertThat(fresh.getConsumedAt()).isEqualTo(consumedInstant);
        assertThat(fresh.getResetOperationId()).isNotNull();
    }

    @Test
    @Order(19)
    @DisplayName("Req 19: Password-reset sensitive fields and user details stay hidden from JSON")
    void test19_passwordResetSensitiveFieldsAndUserDetailsStayHiddenFromJson() throws Exception {
        JpaUser user = new JpaUser("usr-json-sec", "Secret User", "sec@example.com", "555-0219", "MEMBER");
        user.setPassword("$2a$10$superSecretUserPasswordHashThatMustNeverLeak");

        JpaPasswordResetOtpChallenge challenge = new JpaPasswordResetOtpChallenge(
                "email-key-json-sec",
                "ch-sec-json-123",
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
        challenge.setCodeDigest("$2a$10$superSecretResetCodeDigestThatMustNeverLeak");
        challenge.setSendOperationId("send-op-sec-789");
        challenge.setClientIdempotencyKey("idem-sec-456");

        String json = objectMapper.writeValueAsString(challenge);

        assertThat(json).doesNotContain("superSecretResetCodeDigest");
        assertThat(json).doesNotContain("superSecretUserPasswordHash");
        assertThat(json).doesNotContain("send-op-sec-789");
        assertThat(json).doesNotContain("idem-sec-456");

        assertThat(json).contains("ch-sec-json-123");
        assertThat(json).contains("PASSWORD_RESET");
    }

    @Test
    @Order(19)
    @DisplayName("Req 19b: Password-reset supplementary queries (findByUserId, status, expiry)")
    void test19b_passwordResetSupplementaryQueries() {
        String userId = "usr-sup-pwd-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Sup Pwd User", "suppwd." + UUID.randomUUID() + "@example.com", "555-0219", "MEMBER");
        userRepository.save(user);

        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        Instant cutoff = Instant.parse("2026-10-26T14:00:00Z");

        JpaPasswordResetOtpChallenge ch1 = new JpaPasswordResetOtpChallenge(
                sha256Hex("sup1.pwd." + testSuffix + "@test.com"),
                "ch-sup-pwd1-" + testSuffix,
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.ACTIVE,
                cutoff.minusSeconds(10)
        );

        JpaPasswordResetOtpChallenge ch2 = new JpaPasswordResetOtpChallenge(
                sha256Hex("sup2.pwd." + testSuffix + "@test.com"),
                "ch-sup-pwd2-" + testSuffix,
                user,
                PasswordResetOtpChallenge.Purpose.PASSWORD_RESET,
                PasswordResetChallengeStatus.PROCESSING,
                cutoff.plusSeconds(10)
        );

        passwordResetRepository.saveAllAndFlush(List.of(ch1, ch2));

        // findByUserId (JPQL)
        List<JpaPasswordResetOtpChallenge> byUserJpql = passwordResetRepository.findByUserId(userId);
        assertThat(byUserJpql).hasSize(2);

        // findByUser_Id (derived traversal)
        List<JpaPasswordResetOtpChallenge> byUserDerived = passwordResetRepository.findByUser_Id(userId);
        assertThat(byUserDerived).hasSize(2);

        // findByStatus
        List<JpaPasswordResetOtpChallenge> processing = passwordResetRepository.findByStatus(PasswordResetChallengeStatus.PROCESSING);
        assertThat(processing.stream().anyMatch(c -> c.getEmailKey().equals(ch2.getEmailKey()))).isTrue();

        // findByChallengeExpiresAtLessThanEqual
        List<JpaPasswordResetOtpChallenge> expired = passwordResetRepository.findByChallengeExpiresAtLessThanEqual(cutoff);
        assertThat(expired.stream().anyMatch(c -> c.getEmailKey().equals(ch1.getEmailKey()))).isTrue();
        assertThat(expired.stream().noneMatch(c -> c.getEmailKey().equals(ch2.getEmailKey()))).isTrue();
    }

    // =========================================================================
    // Requirement 20 - 24: Signup Abuse Limit Repository
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("Req 20: Signup abuse counter save/find/update works")
    void test20_signupAbuseCounterSaveFindUpdate() {
        String subjectHash = sha256Hex("ip-client-20-" + UUID.randomUUID());
        String id = "INITIAL_SEND_IP|" + subjectHash + "|1700000000";

        Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = windowStart.plus(15, ChronoUnit.MINUTES);

        JpaSignupAbuseLimit abuseLimit = new JpaSignupAbuseLimit(
                id,
                SignupAbuseLimit.BucketType.INITIAL_SEND_IP,
                subjectHash,
                1L,
                windowStart,
                expiresAt
        );
        abuseLimitRepository.saveAndFlush(abuseLimit);

        JpaSignupAbuseLimit reloaded = abuseLimitRepository.findById(id).orElseThrow();
        assertThat(reloaded.getCount()).isEqualTo(1L);

        reloaded.setCount(5L);
        abuseLimitRepository.saveAndFlush(reloaded);

        JpaSignupAbuseLimit updated = abuseLimitRepository.findById(id).orElseThrow();
        assertThat(updated.getCount()).isEqualTo(5L);
    }

    @Test
    @Order(21)
    @DisplayName("Req 21: Signup abuse counter handles BIGINT values above Integer.MAX_VALUE")
    void test21_signupAbuseCounterHandlesBigintValuesAboveIntegerMaxValue() {
        String subjectHash = sha256Hex("ip-client-21-" + UUID.randomUUID());
        String id = "TOTAL_SEND_IP|" + subjectHash + "|1700000001";

        long largeCount = 5_000_000_000L; // Exceeds Integer.MAX_VALUE (2,147,483,647)

        JpaSignupAbuseLimit abuseLimit = new JpaSignupAbuseLimit(
                id,
                SignupAbuseLimit.BucketType.TOTAL_SEND_IP,
                subjectHash,
                largeCount,
                Instant.now(),
                Instant.now().plus(24, ChronoUnit.HOURS)
        );
        abuseLimitRepository.saveAndFlush(abuseLimit);

        JpaSignupAbuseLimit reloaded = abuseLimitRepository.findById(id).orElseThrow();
        assertThat(reloaded.getCount()).isEqualTo(largeCount);
    }

    @Test
    @Order(22)
    @DisplayName("Req 22: BucketType and keyed subject hash persist correctly")
    void test22_bucketTypeAndKeyedSubjectHashPersistCorrectly() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();

        for (SignupAbuseLimit.BucketType type : SignupAbuseLimit.BucketType.values()) {
            String subjectHash = sha256Hex("subject-" + type.name() + "-" + testSuffix);
            String id = type.name() + "|" + subjectHash + "|1700000002";

            JpaSignupAbuseLimit limit = new JpaSignupAbuseLimit(id, type, subjectHash, 1L, now, now.plusSeconds(300));
            abuseLimitRepository.saveAndFlush(limit);

            JpaSignupAbuseLimit reloaded = abuseLimitRepository.findById(id).orElseThrow();
            assertThat(reloaded.getBucketType()).isEqualTo(type);
            assertThat(reloaded.getSubjectHash()).isEqualTo(subjectHash);
        }
    }

    @Test
    @Order(23)
    @DisplayName("Req 23: Negative abuse counter rejected by PostgreSQL check constraint")
    void test23_negativeAbuseCounterRejectedByPostgreSQL() {
        String subjectHash = sha256Hex("ip-client-23-" + UUID.randomUUID());
        String id = "VERIFY_IP|" + subjectHash + "|1700000003";

        JpaSignupAbuseLimit negativeCount = new JpaSignupAbuseLimit(
                id,
                SignupAbuseLimit.BucketType.VERIFY_IP,
                subjectHash,
                -1L,
                Instant.now(),
                Instant.now().plusSeconds(600)
        );

        assertThatThrownBy(() -> abuseLimitRepository.saveAndFlush(negativeCount))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(24)
    @DisplayName("Req 24: Abuse counter expiry and supplementary read-only queries work")
    void test24_abuseCounterExpiryAndSupplementaryReadOnlyQueriesWork() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        String subjectHash = sha256Hex("subject-24-" + testSuffix);
        Instant cutoff = Instant.parse("2026-10-26T15:00:00Z");

        JpaSignupAbuseLimit exp1 = new JpaSignupAbuseLimit(
                "INITIAL_SEND_IP|" + subjectHash + "|1001",
                SignupAbuseLimit.BucketType.INITIAL_SEND_IP,
                subjectHash,
                3L,
                cutoff.minusSeconds(900),
                cutoff.minusSeconds(1)
        );

        JpaSignupAbuseLimit exp2 = new JpaSignupAbuseLimit(
                "VERIFY_IP|" + subjectHash + "|1002",
                SignupAbuseLimit.BucketType.VERIFY_IP,
                subjectHash,
                1L,
                cutoff.minusSeconds(900),
                cutoff
        );

        JpaSignupAbuseLimit active = new JpaSignupAbuseLimit(
                "INITIAL_SEND_IP|" + subjectHash + "|1003",
                SignupAbuseLimit.BucketType.INITIAL_SEND_IP,
                subjectHash,
                2L,
                cutoff,
                cutoff.plusSeconds(900)
        );

        abuseLimitRepository.saveAllAndFlush(List.of(exp1, exp2, active));

        // findByBucketTypeAndSubjectHash returns List (non-unique composite index)
        List<JpaSignupAbuseLimit> byBucketAndSubject = abuseLimitRepository.findByBucketTypeAndSubjectHash(
                SignupAbuseLimit.BucketType.INITIAL_SEND_IP, subjectHash);
        assertThat(byBucketAndSubject).hasSize(2);

        // findBySubjectHash
        List<JpaSignupAbuseLimit> bySubject = abuseLimitRepository.findBySubjectHash(subjectHash);
        assertThat(bySubject).hasSize(3);

        // findByExpiresAtLessThanEqual
        List<JpaSignupAbuseLimit> expiredList = abuseLimitRepository.findByExpiresAtLessThanEqual(cutoff);
        List<String> expiredIds = expiredList.stream().map(JpaSignupAbuseLimit::getId).toList();
        assertThat(expiredIds).contains(exp1.getId(), exp2.getId());
        assertThat(expiredIds).doesNotContain(active.getId());

        // Exact boundary assertions
        assertThat(exp2.isExpired(cutoff.minusMillis(1))).isFalse();
        assertThat(exp2.isExpired(cutoff)).isTrue();
        assertThat(exp2.isExpired(cutoff.plusMillis(1))).isTrue();
    }

    // =========================================================================
    // Requirement 25 - 29: Login Failure State Repository
    // =========================================================================

    @Test
    @Order(25)
    @DisplayName("Req 25: Login failure state save/find/update works")
    void test25_loginFailureStateSaveFindUpdate() {
        String subjectHash = sha256Hex("ip-login-25-" + UUID.randomUUID());
        String id = "LOGIN_IP|" + subjectHash;

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = now.plus(15, ChronoUnit.MINUTES);

        JpaLoginFailureState state = new JpaLoginFailureState(
                id,
                subjectHash,
                1,
                now,
                null,
                now,
                expiresAt
        );
        loginFailureRepository.saveAndFlush(state);

        JpaLoginFailureState reloaded = loginFailureRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailureCount()).isEqualTo(1);
        assertThat(reloaded.getBlockedUntil()).isNull();

        reloaded.setFailureCount(4);
        Instant blockedUntil = now.plus(30, ChronoUnit.SECONDS);
        reloaded.setBlockedUntil(blockedUntil);
        loginFailureRepository.saveAndFlush(reloaded);

        JpaLoginFailureState updated = loginFailureRepository.findById(id).orElseThrow();
        assertThat(updated.getFailureCount()).isEqualTo(4);
        assertThat(updated.getBlockedUntil()).isEqualTo(blockedUntil);
    }

    @Test
    @Order(26)
    @DisplayName("Req 26: Login failure INT counter and negative-value constraint work")
    void test26_loginFailureIntCounterAndNegativeValueConstraint() {
        String subjectHash = sha256Hex("ip-login-26-" + UUID.randomUUID());
        String id = "LOGIN_IDENTIFIER|" + subjectHash;

        JpaLoginFailureState negativeFailure = new JpaLoginFailureState(
                id,
                subjectHash,
                -1,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now().plusSeconds(600)
        );

        assertThatThrownBy(() -> loginFailureRepository.saveAndFlush(negativeFailure))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(27)
    @DisplayName("Req 27: Nullable blockedUntil and exact boundary behavior work")
    void test27_nullableBlockedUntilAndExactBoundaryBehavior() {
        String subjectHash = sha256Hex("ip-login-27-" + UUID.randomUUID());
        Instant baseTime = Instant.parse("2026-10-26T16:00:00Z");
        Instant blockedUntil = baseTime.plus(30, ChronoUnit.SECONDS);
        Instant expiresAt = baseTime.plus(5, ChronoUnit.MINUTES);

        JpaLoginFailureState state = new JpaLoginFailureState(
                "LOGIN_STATE|" + subjectHash,
                subjectHash,
                3,
                baseTime,
                blockedUntil,
                baseTime,
                expiresAt
        );

        // Before blockedUntil: actively blocked
        assertThat(state.isBlocked(blockedUntil.minusMillis(1))).isTrue();

        // Exactly at blockedUntil: cooldown expired, unblocked
        assertThat(state.isBlocked(blockedUntil)).isFalse();

        // After blockedUntil: unblocked
        assertThat(state.isBlocked(blockedUntil.plusMillis(1))).isFalse();

        // When blockedUntil is null: unblocked
        state.setBlockedUntil(null);
        assertThat(state.isBlocked(baseTime)).isFalse();
    }

    @Test
    @Order(28)
    @DisplayName("Req 28: Expired login state does not represent an active time-based block")
    void test28_expiredLoginStateDoesNotRepresentActiveTimeBasedBlock() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.parse("2026-10-26T16:30:00Z");

        // 1. Actively blocked record: blocked_until in future AND expires_at in future
        String activeSubject = sha256Hex("active-login-" + testSuffix);
        JpaLoginFailureState activeBlock = new JpaLoginFailureState(
                "LOGIN_ACT|" + activeSubject,
                activeSubject,
                5,
                now.minusSeconds(60),
                now.plusSeconds(30), // future block
                now,
                now.plusSeconds(300) // future expiry
        );

        // 2. Stale record: blocked_until in future, BUT record expires_at is in past
        String staleSubject = sha256Hex("stale-login-" + testSuffix);
        JpaLoginFailureState staleExpired = new JpaLoginFailureState(
                "LOGIN_STALE|" + staleSubject,
                staleSubject,
                5,
                now.minusSeconds(600),
                now.plusSeconds(30), // future block timestamp
                now.minusSeconds(600),
                now.minusSeconds(1) // EXPIRED!
        );

        loginFailureRepository.saveAllAndFlush(List.of(activeBlock, staleExpired));

        // Entity helper evaluation: stale record must never be evaluated as active lockout
        assertThat(staleExpired.isExpired(now)).isTrue();
        assertThat(staleExpired.isBlocked(now)).isFalse();

        assertThat(activeBlock.isExpired(now)).isFalse();
        assertThat(activeBlock.isBlocked(now)).isTrue();

        // Repository query: findActiveBlocks filters out expired records
        List<JpaLoginFailureState> activeBlocksJpql = loginFailureRepository.findActiveBlocks(now);
        List<String> activeIds = activeBlocksJpql.stream().map(JpaLoginFailureState::getId).toList();
        assertThat(activeIds).contains(activeBlock.getId());
        assertThat(activeIds).doesNotContain(staleExpired.getId());

        // Repository query: findByBlockedUntilAfterAndExpiresAtAfter
        List<JpaLoginFailureState> activeBlocksDerived = loginFailureRepository.findByBlockedUntilAfterAndExpiresAtAfter(now, now);
        List<String> activeDerivedIds = activeBlocksDerived.stream().map(JpaLoginFailureState::getId).toList();
        assertThat(activeDerivedIds).contains(activeBlock.getId());
        assertThat(activeDerivedIds).doesNotContain(staleExpired.getId());
    }

    @Test
    @Order(29)
    @DisplayName("Req 29: Sensitive IDs, hashes and operation identifiers remain protected in JSON")
    void test29_sensitiveIdsHashesAndOperationIdentifiersRemainProtected() throws Exception {
        JpaSignupAbuseLimit abuseLimit = new JpaSignupAbuseLimit(
                "SECRET_ID_ABUSE_12345",
                SignupAbuseLimit.BucketType.INITIAL_SEND_IP,
                "SECRET_SUBJECT_HASH_ABCDE",
                3L,
                Instant.now(),
                Instant.now().plusSeconds(600)
        );
        String abuseJson = objectMapper.writeValueAsString(abuseLimit);
        assertThat(abuseJson).doesNotContain("SECRET_ID_ABUSE_12345");
        assertThat(abuseJson).doesNotContain("SECRET_SUBJECT_HASH_ABCDE");
        assertThat(abuseJson).contains("INITIAL_SEND_IP");

        JpaLoginFailureState loginState = new JpaLoginFailureState(
                "SECRET_ID_LOGIN_67890",
                "SECRET_SUBJECT_HASH_FGHIJ",
                2,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now().plusSeconds(600)
        );
        String loginJson = objectMapper.writeValueAsString(loginState);
        assertThat(loginJson).doesNotContain("SECRET_ID_LOGIN_67890");
        assertThat(loginJson).doesNotContain("SECRET_SUBJECT_HASH_FGHIJ");
        assertThat(loginJson).contains("2");
    }

    // =========================================================================
    // Requirement 30 - 31: All 13 JPA Repository Beans & Default Profile Isolation
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("Req 30: All 13 JPA repository beans initialize with no MongoDB bean collisions")
    void test30_all13JpaRepositoryBeansInitializeWithNoCollisions() {
        // 1. Verify all 13 JPA repository beans are registered in active JPA context
        assertThat(context.containsBean("jpaUserRepository")).isTrue();
        assertThat(context.containsBean("jpaCategoryRepository")).isTrue();
        assertThat(context.containsBean("jpaMenuItemRepository")).isTrue();
        assertThat(context.containsBean("jpaCatalogMigrationRepository")).isTrue();
        assertThat(context.containsBean("jpaStoreSettingsRepository")).isTrue();

        assertThat(context.containsBean("jpaCustomerOrderRepository")).isTrue();
        assertThat(context.containsBean("jpaCartRepository")).isTrue();
        assertThat(context.containsBean("jpaOrderReviewRepository")).isTrue();
        assertThat(context.containsBean("jpaReservationRepository")).isTrue();

        assertThat(context.containsBean("jpaSignupOtpChallengeRepository")).isTrue();
        assertThat(context.containsBean("jpaPasswordResetOtpChallengeRepository")).isTrue();
        assertThat(context.containsBean("jpaSignupAbuseLimitRepository")).isTrue();
        assertThat(context.containsBean("jpaLoginFailureStateRepository")).isTrue();

        // 2. Verify no MongoDB repository bean names collide in this context
        assertThat(context.containsBean("userRepository")).isFalse();
        assertThat(context.containsBean("categoryRepository")).isFalse();
        assertThat(context.containsBean("menuItemRepository")).isFalse();
        assertThat(context.containsBean("catalogMigrationRepository")).isFalse();
        assertThat(context.containsBean("storeSettingsRepository")).isFalse();

        assertThat(context.containsBean("customerOrderRepository")).isFalse();
        assertThat(context.containsBean("cartRepository")).isFalse();
        assertThat(context.containsBean("orderReviewRepository")).isFalse();
        assertThat(context.containsBean("reservationRepository")).isFalse();

        assertThat(context.containsBean("signupOtpChallengeRepository")).isFalse();
        assertThat(context.containsBean("passwordResetOtpChallengeRepository")).isFalse();
        assertThat(context.containsBean("signupAbuseLimitRepository")).isFalse();
        assertThat(context.containsBean("loginFailureStateRepository")).isFalse();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    static class DefaultProfileTestConfig {}

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DefaultProfileIsolationConfig {}

    @Test
    @Order(31)
    @DisplayName("Req 31: Default MongoDB profile isolation and no repository bean collisions")
    void test31_defaultProfileStartupIsolationWithAppropriateAutoConfigurations() {
        // 1. Meaningful repository auto-configuration test:
        // When DataJpaRepositoriesAutoConfiguration executes against the repository package,
        // verify that without DataSource / EntityManagerFactory beans (as in the default profile),
        // it cleanly backs off and activates ZERO JPA repository beans.
        new ApplicationContextRunner()
                .withUserConfiguration(DefaultProfileTestConfig.class)
                .withConfiguration(AutoConfigurations.of(DataJpaRepositoriesAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();

                    // Assert all 4 security JPA repositories are absent
                    assertThat(ctx).doesNotHaveBean(JpaSignupOtpChallengeRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaPasswordResetOtpChallengeRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaSignupAbuseLimitRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaLoginFailureStateRepository.class);

                    // Assert all other 9 JPA repositories are absent
                    assertThat(ctx).doesNotHaveBean(JpaUserRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCategoryRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaMenuItemRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCatalogMigrationRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaStoreSettingsRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCustomerOrderRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCartRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaOrderReviewRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaReservationRepository.class);

                    assertThat(ctx.getBeanNamesForType(JpaSignupOtpChallengeRepository.class)).isEmpty();
                    assertThat(ctx.getBeanNamesForType(JpaPasswordResetOtpChallengeRepository.class)).isEmpty();
                    assertThat(ctx.getBeanNamesForType(JpaSignupAbuseLimitRepository.class)).isEmpty();
                    assertThat(ctx.getBeanNamesForType(JpaLoginFailureStateRepository.class)).isEmpty();
                });

        // 2. Meaningful DataSource auto-configuration test:
        // Verify that DataSourceAutoConfiguration without PostgreSQL connection settings fails,
        // proving that unexcluded relational auto-configuration cannot start in the default environment.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                });

        // 3. Meaningful Spring Boot @EnableAutoConfiguration test using actual application settings:
        // With production spring.autoconfigure.exclude applied, relational infrastructure is completely excluded.
        new ApplicationContextRunner()
                .withUserConfiguration(DefaultProfileIsolationConfig.class)
                .withPropertyValues(
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                        "spring.flyway.enabled=false"
                )
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(DataSource.class);
                    assertThat(ctx).doesNotHaveBean(EntityManagerFactory.class);
                    assertThat(ctx).doesNotHaveBean(Flyway.class);

                    assertThat(ctx).doesNotHaveBean(JpaSignupOtpChallengeRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaPasswordResetOtpChallengeRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaSignupAbuseLimitRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaLoginFailureStateRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCustomerOrderRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCartRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaOrderReviewRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaReservationRepository.class);
                });
    }

    @Test
    @Order(32)
    @DisplayName("Req 32: Disposable database creation failure and unowned teardown safety guardrails")
    void test32_databaseCreationFailureCannotCauseDeletionOfExistingDatabase() throws Exception {
        String safeName = TEST_DB_PREFIX + "guard_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable";

        // 1. Manually create an existing database to simulate an existing database not owned by session
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE " + safeName);
        }

        try {
            // 2. An unowned session must refuse to drop the database
            DisposableDatabaseSession unownedSession = new DisposableDatabaseSession(testPort, safeName);
            assertThat(unownedSession.isCreatedAndOwned()).isFalse();

            // Calling drop() without successful provision() must NO-OP
            unownedSession.drop();

            // Prove database was NOT deleted
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + safeName + "'")) {
                assertThat(rs.next()).isTrue();
            }

            // 3. Attempting to provision over an existing database must fail and not grant ownership
            assertThatThrownBy(unownedSession::provision).isInstanceOf(Exception.class);
            assertThat(unownedSession.isCreatedAndOwned()).isFalse();

            // Calling drop() again after failed provision must still NO-OP
            unownedSession.drop();

            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + safeName + "'")) {
                assertThat(rs.next()).isTrue();
            }
        } finally {
            // Cleanup safeName
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + safeName + "' AND pid <> pg_backend_pid()");
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + safeName + " WITH (FORCE)");
            }
        }

        // 4. Assert protected names are rejected
        assertThatThrownBy(() -> assertSafeTestEnvironment(testPort, "postgres"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment(testPort, "template0"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment(testPort, "template1"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment(testPort, "public"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment(testPort, "lordgainsborough"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment(testPort, "restaurant"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment(testPort, "invalid_prefix_db"))
                .isInstanceOf(AssertionError.class);

        // 5. Successful ownership provision and drop lifecycle works as expected
        String ownedName = TEST_DB_PREFIX + "owned_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DisposableDatabaseSession ownedSession = new DisposableDatabaseSession(testPort, ownedName);
        ownedSession.provision();
        assertThat(ownedSession.isCreatedAndOwned()).isTrue();

        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + ownedName + "'")) {
            assertThat(rs.next()).isTrue();
        }

        ownedSession.drop();
        assertThat(ownedSession.isCreatedAndOwned()).isFalse();

        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + ownedName + "'")) {
            assertThat(rs.next()).isFalse();
        }
    }
}
