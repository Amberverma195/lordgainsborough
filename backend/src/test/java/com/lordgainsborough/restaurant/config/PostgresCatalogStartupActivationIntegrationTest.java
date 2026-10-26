package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCatalogMigrationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-012 Verification: End-to-end Spring Boot application startup activation test.
 * <p>
 * Verifies:
 * <ol>
 *   <li>Repository auto-configuration ({@link DataJpaRepositoriesAutoConfiguration}) successfully
 *       registers repositories, and {@link PostgresCatalogInitializerConfiguration} activates under
 *       {@code postgres-migration} profile with {@code app.catalog.postgres-seed.enabled=true}.</li>
 *   <li>The startup {@link CommandLineRunner} executes the synchronization seed exactly once into
 *       the relational database (14 categories, 119 menu items, migration marker) and is idempotent.</li>
 *   <li>Activation strictly backs off when the opt-in property is missing or in the default profile.</li>
 *   <li>When {@link MongoTransactionManager} and JPA {@link PlatformTransactionManager} coexist,
 *       {@code @Transactional(transactionManager = "transactionManager")} unambiguously selects the
 *       relational JPA transaction manager without throwing {@code NoUniqueBeanDefinitionException}.</li>
 *   <li>Disposable database session refuses unowned drops and enforces safe naming guardrails.</li>
 * </ol>
 * </p>
 */
public class PostgresCatalogStartupActivationIntegrationTest {

    private static final String TEST_DB_PREFIX = "pg_seed_act_test_";
    private static String testPort;
    private static String testDb;
    private static String testDbCoexist;
    private static DisposableDatabaseSession activationSession;
    private static DisposableDatabaseSession coexistSession;

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

    @BeforeAll
    static void setUpDatabase() throws Exception {
        testPort = System.getProperty("test.postgres.port", "54353");
        testDb = TEST_DB_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        testDbCoexist = TEST_DB_PREFIX + "coex_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        activationSession = new DisposableDatabaseSession(testPort, testDb);
        activationSession.provision();

        coexistSession = new DisposableDatabaseSession(testPort, testDbCoexist);
        coexistSession.provision();
    }

    @AfterAll
    static void tearDownDatabase() {
        if (activationSession != null) {
            activationSession.drop();
        }
        if (coexistSession != null) {
            coexistSession.drop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "com.lordgainsborough.restaurant")
    @EntityScan(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.entity")
    static class TestAppConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class CoexistingMongoTransactionManagerConfig {
        @Bean
        public MongoTransactionManager mongoTransactionManager() {
            MongoDatabaseFactory mockDbFactory = Mockito.mock(MongoDatabaseFactory.class);
            return new MongoTransactionManager(mockDbFactory);
        }
    }

    private ApplicationContextRunner baseContextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        DataJpaRepositoriesAutoConfiguration.class
                ))
                .withUserConfiguration(TestAppConfig.class, PostgresCatalogInitializerConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:" + testPort + "/" + testDb + "?sslmode=disable",
                        "spring.datasource.username=postgres",
                        "spring.datasource.password=postgres",
                        "spring.flyway.enabled=true",
                        "spring.flyway.locations=classpath:db/migration",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect"
                );
    }

    @Test
    @DisplayName("1. Repository auto-configuration registers beans and CommandLineRunner performs catalog seed exactly once")
    void test01_startupActivationWithAutoConfiguration_registersBeansAndPerformsSeeding() {
        baseContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=postgres-migration",
                        "app.catalog.postgres-seed.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // 1. Verify repository auto-configuration registered the repository beans
                    assertThat(context).hasSingleBean(JpaCategoryRepository.class);
                    assertThat(context).hasSingleBean(JpaMenuItemRepository.class);
                    assertThat(context).hasSingleBean(JpaCatalogMigrationRepository.class);

                    // 2. Verify synchronization bean and CommandLineRunner are registered
                    assertThat(context).hasSingleBean(PostgresCatalogSynchronizationService.class);
                    assertThat(context).hasBean("postgresCatalogCommandLineRunner");

                    PostgresCatalogSynchronizationService syncService =
                            context.getBean(PostgresCatalogSynchronizationService.class);
                    CommandLineRunner runner = context.getBean("postgresCatalogCommandLineRunner", CommandLineRunner.class);
                    JpaCategoryRepository categoryRepo = context.getBean(JpaCategoryRepository.class);
                    JpaMenuItemRepository menuItemRepo = context.getBean(JpaMenuItemRepository.class);
                    JpaCatalogMigrationRepository migrationRepo = context.getBean(JpaCatalogMigrationRepository.class);

                    // 3. Execute runner to perform the seed
                    runner.run();

                    // 4. Verify catalog contents persisted in PostgreSQL
                    assertThat(categoryRepo.count()).isEqualTo(14);
                    assertThat(menuItemRepo.count()).isEqualTo(119);
                    assertThat(migrationRepo.existsById(CatalogSeedSpecifications.MIGRATION_ID)).isTrue();

                    // 5. Verify repeated execution is idempotent (skips seed, counts unchanged)
                    boolean secondRunResult = syncService.synchronizeCatalog();
                    assertThat(secondRunResult).isFalse();
                    assertThat(categoryRepo.count()).isEqualTo(14);
                    assertThat(menuItemRepo.count()).isEqualTo(119);
                });
    }

    @Test
    @DisplayName("2. Startup activation backs off when opt-in property is missing")
    void test02_startupActivationBacksOff_whenOptInPropertyMissing() {
        baseContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=postgres-migration"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PostgresCatalogSynchronizationService.class);
                    assertThat(context).doesNotHaveBean("postgresCatalogCommandLineRunner");
                });
    }

    @Test
    @DisplayName("3. Startup activation backs off in default profile even with opt-in property")
    void test03_startupActivationBacksOff_inDefaultProfile() {
        baseContextRunner()
                .withPropertyValues(
                        "app.catalog.postgres-seed.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PostgresCatalogSynchronizationService.class);
                    assertThat(context).doesNotHaveBean("postgresCatalogCommandLineRunner");
                });
    }

    @Test
    @DisplayName("4. Coexisting MongoTransactionManager and JpaTransactionManager resolve unambiguously")
    void test04_transactionManagerSelectionWithCoexistingMongoAndJpa() {
        baseContextRunner()
                .withUserConfiguration(CoexistingMongoTransactionManagerConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=postgres-migration",
                        "app.catalog.postgres-seed.enabled=true",
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:" + testPort + "/" + testDbCoexist + "?sslmode=disable"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // Verify both transaction managers are present in the context
                    Map<String, PlatformTransactionManager> txManagers =
                            context.getBeansOfType(PlatformTransactionManager.class);
                    assertThat(txManagers).containsKey("transactionManager");
                    assertThat(txManagers).containsKey("mongoTransactionManager");
                    assertThat(txManagers.size()).isGreaterThanOrEqualTo(2);

                    PostgresCatalogSynchronizationService syncService =
                            context.getBean(PostgresCatalogSynchronizationService.class);
                    JpaCategoryRepository categoryRepo = context.getBean(JpaCategoryRepository.class);
                    JpaMenuItemRepository menuItemRepo = context.getBean(JpaMenuItemRepository.class);
                    JpaCatalogMigrationRepository migrationRepo = context.getBean(JpaCatalogMigrationRepository.class);

                    // Calling synchronizeCatalog must NOT fail with NoUniqueBeanDefinitionException
                    // because @Transactional(transactionManager = "transactionManager") explicitly binds
                    // to the relational JPA transaction manager.
                    boolean result = syncService.synchronizeCatalog();
                    assertThat(result).isTrue();

                    // Verify transactional commitment of catalog data in PostgreSQL
                    assertThat(categoryRepo.count()).isEqualTo(14);
                    assertThat(menuItemRepo.count()).isEqualTo(119);
                    assertThat(migrationRepo.existsById(CatalogSeedSpecifications.MIGRATION_ID)).isTrue();
                });
    }

    @Test
    @DisplayName("5. Disposable database session refuses unowned drops and enforces safe naming guardrails")
    void test05_disposableDatabaseSession_unownedAndCreationFailureGuardrails() throws Exception {
        String safeName = TEST_DB_PREFIX + "guard_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable";

        // 1. Manually create an existing database to simulate an existing database not owned by this session
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
            // Cleanup safeName manually
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + safeName + "' AND pid <> pg_backend_pid()");
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + safeName + " WITH (FORCE)");
            }
        }

        // 4. Assert protected names and invalid prefixes/patterns are rejected
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
