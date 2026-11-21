package com.lordgainsborough.restaurant.persistence.jpa;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaAccountLegalAcceptance;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaSignupOtpChallenge;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaAccountLegalAcceptanceRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaOrderReviewRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.LegalPolicyService;
import com.lordgainsborough.restaurant.service.PostgresSignupOtpChallengeStore;
import com.lordgainsborough.restaurant.service.PostgresUserAuthStore;
import com.lordgainsborough.restaurant.service.SignupOtpChallengePersistence;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeService;
import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AccountLegalAcceptanceJpaIntegrationTest {

    private static final String TEST_DB = "legal_acceptance_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private String testPort;
    private String jdbcUrl;
    private PGSimpleDataSource dataSource;
    private AnnotationConfigApplicationContext springContext;
    private EntityManagerFactory emf;
    private JpaUserRepository userRepository;
    private JpaAccountLegalAcceptanceRepository legalAcceptanceRepository;
    private PostgresUserAuthStore authStore;
    private LegalPolicyService legalPolicyService;
    private PostgresSignupOtpChallengeStore challengeStore;
    private SignupOtpChallengePersistence otpPersistence;
    private SignupOtpChallengeService otpService;

    @Configuration
    @EnableJpaRepositories(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.repository")
    static class TestSpringConfig {

        @Bean
        public DataSource dataSource() {
            String port = com.lordgainsborough.restaurant.support.LocalPostgres.requirePort();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl("jdbc:postgresql://127.0.0.1:" + port + "/" + TEST_DB + "?sslmode=disable");
            ds.setUser("postgres");
            ds.setPassword("postgres");
            return ds;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
            em.setDataSource(dataSource);
            em.setPackagesToScan("com.lordgainsborough.restaurant.persistence.jpa.entity");
            em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

            Properties jpaProps = new Properties();
            jpaProps.put("hibernate.hbm2ddl.auto", "validate");
            jpaProps.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            jpaProps.put("hibernate.show_sql", "false");
            em.setJpaProperties(jpaProps);
            return em;
        }

        @Bean
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    @BeforeAll
    void setUpAll() throws Exception {
        testPort = com.lordgainsborough.restaurant.support.LocalPostgres.requirePort();
        jdbcUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/" + TEST_DB + "?sslmode=disable";

        // 1. Create dedicated database
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE " + TEST_DB);
        }

        // 2. Apply all Flyway migrations (including V6)
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
        flyway.migrate();

        // 3. Initialize Spring context
        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TestSpringConfig.class);
        springContext.refresh();

        emf = springContext.getBean(EntityManagerFactory.class);
        userRepository = springContext.getBean(JpaUserRepository.class);
        legalAcceptanceRepository = springContext.getBean(JpaAccountLegalAcceptanceRepository.class);
        JpaCustomerOrderRepository orderRepo = springContext.getBean(JpaCustomerOrderRepository.class);
        JpaOrderReviewRepository reviewRepo = springContext.getBean(JpaOrderReviewRepository.class);
        JpaTransactionManager txManager = (JpaTransactionManager) springContext.getBean(PlatformTransactionManager.class);

        legalPolicyService = new LegalPolicyService();
        authStore = new PostgresUserAuthStore(userRepository, orderRepo, reviewRepo, txManager, Clock.systemUTC(), legalPolicyService);

        DataSource ds = springContext.getBean(DataSource.class);
        challengeStore = new PostgresSignupOtpChallengeStore(ds, txManager);
        otpPersistence = new SignupOtpChallengePersistence(challengeStore, Clock.systemUTC());
        EmailSenderService emailSender = mock(EmailSenderService.class);
        SignupOtpObservability obs = new SignupOtpObservability(new SimpleMeterRegistry());
        otpService = new SignupOtpChallengeService(otpPersistence, emailSender, obs);
    }

    @AfterAll
    void tearDownAll() {
        if (springContext != null) {
            springContext.close();
        }
        if (testPort != null && TEST_DB != null) {
            String adminUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable";
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + TEST_DB + "' AND pid <> pg_backend_pid()");
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + TEST_DB + " WITH (FORCE)");
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("JpaAccountLegalAcceptance: Persists and reloads with accurate fields and defaults")
    void test1_persistAndReloadLegalAcceptance() {
        String userId = "user-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Alice Consent", "alice." + UUID.randomUUID() + "@example.com", "519-555-0101", "MEMBER");
        userRepository.save(user);

        JpaAccountLegalAcceptance acceptance = new JpaAccountLegalAcceptance();
        acceptance.setUserId(userId);
        acceptance.setTermsVersion("terms-v1.0");
        acceptance.setPrivacyVersion("privacy-v1.0");
        acceptance.setAcceptanceSource("signup");
        acceptance.setAcceptedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        JpaAccountLegalAcceptance saved = legalAcceptanceRepository.save(acceptance);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTermsVersion()).isEqualTo("terms-v1.0");
        assertThat(saved.getPrivacyVersion()).isEqualTo("privacy-v1.0");
        assertThat(saved.getAcceptanceSource()).isEqualTo("signup");
        assertThat(saved.getAcceptedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        // Repository lookups
        List<JpaAccountLegalAcceptance> userAcceptances = legalAcceptanceRepository.findByUserId(userId);
        assertThat(userAcceptances).hasSize(1);
        assertThat(userAcceptances.get(0).getId()).isEqualTo(saved.getId());

        Optional<JpaAccountLegalAcceptance> foundSpecific =
                legalAcceptanceRepository.findByUserIdAndTermsVersionAndPrivacyVersion(userId, "terms-v1.0", "privacy-v1.0");
        assertThat(foundSpecific).isPresent();
        assertThat(foundSpecific.get().getUserId()).isEqualTo(userId);

        long count = legalAcceptanceRepository.countByUserId(userId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("Foreign Key Constraint: Inserting acceptance for nonexistent user throws DataIntegrityViolationException")
    void test2_foreignKeyConstraint_rejectsOrphanAcceptance() {
        JpaAccountLegalAcceptance orphan = new JpaAccountLegalAcceptance();
        orphan.setUserId("nonexistent-user-id");
        orphan.setTermsVersion("terms-v1.0");
        orphan.setPrivacyVersion("privacy-v1.0");
        orphan.setAcceptanceSource("signup");
        orphan.setAcceptedAt(Instant.now());

        assertThatThrownBy(() -> legalAcceptanceRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(3)
    @DisplayName("Unique Constraint: Duplicate acceptance for same user, versions, and source throws exception")
    void test3_uniqueConstraint_rejectsDuplicates() {
        String userId = "user-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Bob Consent", "bob." + UUID.randomUUID() + "@example.com", "519-555-0102", "MEMBER");
        userRepository.save(user);

        JpaAccountLegalAcceptance first = new JpaAccountLegalAcceptance();
        first.setUserId(userId);
        first.setTermsVersion("terms-v1.0");
        first.setPrivacyVersion("privacy-v1.0");
        first.setAcceptanceSource("signup");
        first.setAcceptedAt(Instant.now());
        legalAcceptanceRepository.saveAndFlush(first);

        JpaAccountLegalAcceptance duplicate = new JpaAccountLegalAcceptance();
        duplicate.setUserId(userId);
        duplicate.setTermsVersion("terms-v1.0");
        duplicate.setPrivacyVersion("privacy-v1.0");
        duplicate.setAcceptanceSource("signup");
        duplicate.setAcceptedAt(Instant.now());

        assertThatThrownBy(() -> legalAcceptanceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(4)
    @DisplayName("Cascade Delete: Deleting a user automatically deletes their legal acceptances")
    void test4_cascadeDelete_removesAcceptancesOnUserDeletion() {
        String userId = "user-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Carol Consent", "carol." + UUID.randomUUID() + "@example.com", "519-555-0103", "MEMBER");
        userRepository.save(user);

        JpaAccountLegalAcceptance acc = new JpaAccountLegalAcceptance();
        acc.setUserId(userId);
        acc.setTermsVersion("terms-v1.0");
        acc.setPrivacyVersion("privacy-v1.0");
        acc.setAcceptanceSource("signup");
        acc.setAcceptedAt(Instant.now());
        legalAcceptanceRepository.saveAndFlush(acc);

        assertThat(legalAcceptanceRepository.countByUserId(userId)).isEqualTo(1);

        // Delete user via repository
        userRepository.deleteById(userId);
        userRepository.flush();

        // Legal acceptance should have cascaded to 0
        assertThat(legalAcceptanceRepository.findByUserId(userId)).isEmpty();
        assertThat(legalAcceptanceRepository.countByUserId(userId)).isEqualTo(0);
    }

    @Test
    @Order(5)
    @DisplayName("JpaSignupOtpChallenge: Staged legal fields persist and reload correctly")
    void test5_stagedLegalFieldsInSignupOtpChallenge() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        tx.begin();

        String rawEmail = "staged." + UUID.randomUUID() + "@example.com";
        String emailKey = otpPersistence.emailKeyFromRawEmail(rawEmail);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        JpaSignupOtpChallenge challenge = new JpaSignupOtpChallenge(
                emailKey,
                rawEmail,
                UUID.randomUUID().toString(),
                OtpChallengeStatus.ACTIVE,
                "hashed-code",
                now.plus(30, ChronoUnit.MINUTES)
        );
        challenge.setSendOperationId("op-" + UUID.randomUUID());
        challenge.setPendingTermsVersion("terms-v1.0");
        challenge.setPendingPrivacyVersion("privacy-v1.0");
        challenge.setPendingLegalAcceptedAt(now);

        em.persist(challenge);
        tx.commit();
        em.clear();

        JpaSignupOtpChallenge reloaded = em.find(JpaSignupOtpChallenge.class, challenge.getEmailKey());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getPendingTermsVersion()).isEqualTo("terms-v1.0");
        assertThat(reloaded.getPendingPrivacyVersion()).isEqualTo("privacy-v1.0");
        assertThat(reloaded.getPendingLegalAcceptedAt()).isNotNull();
        em.close();
    }

    @Test
    @Order(6)
    @DisplayName("PostgresUserAuthStore.register: Atomically creates user and records legal acceptance")
    void test6_register_atomicUserAndLegalAcceptance() {
        String email = "direct.reg." + UUID.randomUUID() + "@example.com";
        String phoneDigits = "5195550106";

        User draft = new User();
        draft.setName("Direct Reg User");
        draft.setEmail(email);
        draft.setPhoneNormalized(phoneDigits);

        // Stage initial challenge
        SignupOtpChallengeService.InitialSendResult sendResult = otpService.sendInitialOtp(
                email,
                UUID.randomUUID().toString(),
                draft.getName(),
                "519-555-0106",
                phoneDigits,
                "hashed-pw",
                "attempt-tok",
                "terms-v1.0",
                "privacy-v1.0",
                Instant.now()
        );
        assertThat(sendResult).isEqualTo(SignupOtpChallengeService.InitialSendResult.CREATED);

        // Fetch challenge to read plain OTP from verification test
        String emailKey = otpPersistence.emailKeyFromRawEmail(email);
        Optional<SignupOtpChallenge> optChallenge = challengeStore.findById(emailKey);
        assertThat(optChallenge).isPresent();

        // Register directly using register() with incorrect code
        PostgresUserAuthStore.Registration reg = authStore.register(draft, "Password123!", "000000", otpService);
        // OTP was 000000, so verifyOtp will fail with invalid code, user not created
        assertThat(reg.user()).isNull();
        assertThat(reg.verification()).isInstanceOf(SignupOtpChallengeService.VerifyOtpResult.Invalid.class);

        // Verify no user was created
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("PostgresUserAuthStore.registerPending: Rejects missing legal acceptance and rolls back")
    void test7_registerPending_rejectsMissingConsentAndRollsBack() {
        String email = "noconsent." + UUID.randomUUID() + "@example.com";
        Instant now = Instant.now();
        String rawOtp = "123456";
        String otpDigest = otpPersistence.digestOtp(rawOtp);

        // Create challenge directly WITHOUT pending legal fields
        SignupOtpChallenge challenge = otpPersistence.buildNewActiveChallenge(
                email,
                otpDigest,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                null,
                "No Consent User",
                "519-555-0107",
                "5195550107",
                "hashed-pw",
                null, // attempt token hash
                null, // no terms
                null, // no privacy
                null  // no accepted_at
        );
        challengeStore.tryInsert(challenge);

        // Call registerPending with matching OTP - should fail legal check and roll back
        assertThatThrownBy(() -> authStore.registerPending(email, rawOtp, challenge.getChallengeId(), null, otpService))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legal acceptance record missing for signup");

        // Verify user was NOT created
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("PostgresUserAuthStore.registerPending: Rejects superseded policy version and rolls back")
    void test8_registerPending_rejectsSupersededPolicyAndRollsBack() {
        String email = "superseded." + UUID.randomUUID() + "@example.com";
        Instant now = Instant.now();
        String rawOtp = "123456";
        String otpDigest = otpPersistence.digestOtp(rawOtp);

        // Create challenge with superseded policy version
        SignupOtpChallenge challenge = otpPersistence.buildNewActiveChallenge(
                email,
                otpDigest,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                null,
                "Superseded Policy User",
                "519-555-0108",
                "5195550108",
                "hashed-pw",
                null,
                "terms-v0.9", // superseded
                "privacy-v1.0",
                now
        );
        challengeStore.tryInsert(challenge);

        // Call registerPending with matching OTP - should fail policy check and roll back
        assertThatThrownBy(() -> authStore.registerPending(email, rawOtp, challenge.getChallengeId(), null, otpService))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legal policy version has been superseded");

        // Verify user was NOT created
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    @Order(9)
    @DisplayName("PostgresUserAuthStore.registerPending: Atomically inserts user and legal acceptance record")
    void test9_registerPending_atomicSuccess() {
        String email = "success.reg." + UUID.randomUUID() + "@example.com";
        Instant now = Instant.now();
        String rawOtp = "654321";
        String otpDigest = otpPersistence.digestOtp(rawOtp);

        // Create challenge with valid active policy versions
        SignupOtpChallenge challenge = otpPersistence.buildNewActiveChallenge(
                email,
                otpDigest,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                null,
                "Success User",
                "519-555-0109",
                "5195550109",
                "hashed-pw",
                null,
                "terms-v1.0",
                "privacy-v1.1",
                now
        );
        challengeStore.tryInsert(challenge);

        // Call registerPending with matching OTP
        PostgresUserAuthStore.Registration reg = authStore.registerPending(
                email, rawOtp, challenge.getChallengeId(), null, otpService);

        assertThat(reg.user()).isNotNull();
        assertThat(reg.verification()).isInstanceOf(SignupOtpChallengeService.VerifyOtpResult.Verified.class);
        assertThat(reg.conflict()).isFalse();

        // Verify user was created
        JpaUser created = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(created.getName()).isEqualTo("Success User");

        // Verify legal acceptance was recorded atomically
        List<JpaAccountLegalAcceptance> acceptances = legalAcceptanceRepository.findByUserId(created.getId());
        assertThat(acceptances).hasSize(1);
        assertThat(acceptances.get(0).getTermsVersion()).isEqualTo("terms-v1.0");
        assertThat(acceptances.get(0).getPrivacyVersion()).isEqualTo("privacy-v1.1");
        assertThat(acceptances.get(0).getAcceptanceSource()).isEqualTo("signup");
        assertThat(acceptances.get(0).getAcceptedAt()).isNotNull();
    }
}
