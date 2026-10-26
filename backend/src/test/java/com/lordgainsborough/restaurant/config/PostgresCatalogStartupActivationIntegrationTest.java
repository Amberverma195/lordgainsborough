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
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
 * </ol>
 * </p>
 */
public class PostgresCatalogStartupActivationIntegrationTest {

    private static final String TEST_DB = "pg_seed_activation_test_db";
    private static final String TEST_DB_COEXIST = "pg_seed_coexist_test_db";
    private static String port;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        port = System.getProperty("test.postgres.port", "54353");
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/postgres", "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + TEST_DB + " WITH (FORCE)");
            stmt.execute("CREATE DATABASE " + TEST_DB);
            stmt.execute("DROP DATABASE IF EXISTS " + TEST_DB_COEXIST + " WITH (FORCE)");
            stmt.execute("CREATE DATABASE " + TEST_DB_COEXIST);
        }
    }

    @AfterAll
    static void tearDownDatabase() throws Exception {
        if (port != null) {
            try (Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/postgres", "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS " + TEST_DB + " WITH (FORCE)");
                stmt.execute("DROP DATABASE IF EXISTS " + TEST_DB_COEXIST + " WITH (FORCE)");
            }
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
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:" + port + "/" + TEST_DB + "?sslmode=disable",
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
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:" + port + "/" + TEST_DB_COEXIST + "?sslmode=disable"
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
}
