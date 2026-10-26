package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.CategoryController;
import com.lordgainsborough.restaurant.controller.MenuController;
import com.lordgainsborough.restaurant.controller.OrderController;
import com.lordgainsborough.restaurant.model.Category;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCatalogMigration;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCatalogMigrationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-012: Comprehensive integration test suite for PostgreSQL Catalog Initialization
 * and Transactional Seeding.
 * <p>
 * Tests {@link PostgresCatalogSynchronizationService} and {@link PostgresCatalogInitializerConfiguration}
 * against disposable PostgreSQL 17 databases provisioned on 127.0.0.1 and validated against
 * the Flyway V1 schema.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresCatalogInitializerIntegrationTest {

    private static final String TEST_DB_PREFIX = "pg_cat_init_test_";
    private String testPort;
    private String testDb;
    private static String testJdbcUrl;

    private DisposableDatabaseSession mainDatabaseSession;
    private AnnotationConfigApplicationContext context;

    private PostgresCatalogSynchronizationService syncService;
    private JpaCategoryRepository categoryRepository;
    private JpaMenuItemRepository menuItemRepository;
    private JpaCatalogMigrationRepository migrationRepository;
    private EntityManagerFactory entityManagerFactory;

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

    static class IsolatedTestEnvironment implements AutoCloseable {
        final DisposableDatabaseSession session;
        final String url;
        final EntityManagerFactory emf;
        final EntityManager em;
        final PlatformTransactionManager txManager;
        final JpaCategoryRepository categoryRepo;
        final JpaMenuItemRepository menuItemRepo;
        final JpaCatalogMigrationRepository migrationRepo;
        final PostgresCatalogSynchronizationService syncService;

        IsolatedTestEnvironment(String port, String dbName) throws Exception {
            this.session = new DisposableDatabaseSession(port, dbName);
            this.session.provision();

            this.url = "jdbc:postgresql://127.0.0.1:" + port + "/" + dbName + "?sslmode=disable";
            Flyway.configure().dataSource(url, "postgres", "postgres").locations("classpath:db/migration").load().migrate();

            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(url);
            ds.setUser("postgres");
            ds.setPassword("postgres");

            LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
            emfBean.setDataSource(ds);
            emfBean.setPackagesToScan("com.lordgainsborough.restaurant.persistence.jpa.entity");
            emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties props = new Properties();
            props.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            props.setProperty("hibernate.hbm2ddl.auto", "validate");
            emfBean.setJpaProperties(props);
            emfBean.afterPropertiesSet();

            this.emf = emfBean.getObject();
            this.em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            this.txManager = new JpaTransactionManager(emf);

            JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
            this.categoryRepo = factory.getRepository(JpaCategoryRepository.class);
            this.menuItemRepo = factory.getRepository(JpaMenuItemRepository.class);
            this.migrationRepo = factory.getRepository(JpaCatalogMigrationRepository.class);

            this.syncService = new PostgresCatalogSynchronizationService(categoryRepo, menuItemRepo, migrationRepo, em);
        }

        @Override
        public void close() {
            try {
                if (emf != null && emf.isOpen()) {
                    emf.close();
                }
            } finally {
                session.drop();
            }
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.repository")
    @EntityScan(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.entity")
    static class TestCatalogJpaConfig {

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
        public EntityManager entityManager(EntityManagerFactory emf) {
            return SharedEntityManagerCreator.createSharedEntityManager(emf);
        }

        @Bean
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        public PostgresCatalogSynchronizationService postgresCatalogSynchronizationService(
                JpaCategoryRepository categoryRepo,
                JpaMenuItemRepository menuItemRepo,
                JpaCatalogMigrationRepository migrationRepo,
                EntityManager entityManager
        ) {
            return new PostgresCatalogSynchronizationService(categoryRepo, menuItemRepo, migrationRepo, entityManager);
        }
    }

    private String getRequiredPort() {
        String port = System.getProperty("test.postgres.port");
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-012 integration tests")
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

        // Execute Flyway V1 migration
        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        // Initialize Spring context
        context = new AnnotationConfigApplicationContext(TestCatalogJpaConfig.class);
        entityManagerFactory = context.getBean(EntityManagerFactory.class);
        categoryRepository = context.getBean(JpaCategoryRepository.class);
        menuItemRepository = context.getBean(JpaMenuItemRepository.class);
        migrationRepository = context.getBean(JpaCatalogMigrationRepository.class);
        syncService = context.getBean(PostgresCatalogSynchronizationService.class);
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

    // =========================================================================
    // Requirements 1 - 5: Service, Auto-Configuration, Profiles & Validation
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Req 1: PostgreSQL catalog sync service initializes successfully")
    void test01_postgresCatalogSyncServiceInitializes() {
        assertThat(syncService).isNotNull();
        assertThat(categoryRepository).isNotNull();
        assertThat(menuItemRepository).isNotNull();
        assertThat(migrationRepository).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Req 2: Default MongoDB profile does not activate PostgreSQL seeding")
    void test02_defaultMongoProfileDoesNotActivatePostgresSeeding() {
        new ApplicationContextRunner()
                .withUserConfiguration(PostgresCatalogInitializerConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(PostgresCatalogSynchronizationService.class);
                    assertThat(ctx).doesNotHaveBean("postgresCatalogCommandLineRunner");
                });
    }

    @Test
    @Order(3)
    @DisplayName("Req 3: PostgreSQL seeding does not activate without explicit opt-in property")
    void test03_postgresSeedingDoesNotActivateWithoutExplicitOptIn() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=postgres-migration")
                .withUserConfiguration(PostgresCatalogInitializerConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(PostgresCatalogSynchronizationService.class);
                    assertThat(ctx).doesNotHaveBean("postgresCatalogCommandLineRunner");
                });
    }

    @Test
    @Order(4)
    @DisplayName("Req 4: Explicit test activation invokes PostgreSQL initializer configuration")
    void test04_explicitTestActivationInvokesPostgresInitializerCorrectly() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "app.catalog.postgres-seed.enabled=true"
                )
                .withBean(JpaCategoryRepository.class, () -> categoryRepository)
                .withBean(JpaMenuItemRepository.class, () -> menuItemRepository)
                .withBean(JpaCatalogMigrationRepository.class, () -> migrationRepository)
                .withBean(EntityManager.class, () -> entityManagerFactory.createEntityManager())
                .withUserConfiguration(PostgresCatalogInitializerConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(PostgresCatalogSynchronizationService.class);
                    assertThat(ctx).hasBean("postgresCatalogCommandLineRunner");
                });
    }

    @Test
    @Order(5)
    @DisplayName("Req 5: Flyway V1 schema validation succeeds against Hibernate metamodel")
    void test05_flywayV1SchemaValidationSucceeds() {
        var metamodel = entityManagerFactory.getMetamodel();
        assertThat(metamodel.entity(JpaCategory.class)).isNotNull();
        assertThat(metamodel.entity(JpaMenuItem.class)).isNotNull();
        assertThat(metamodel.entity(JpaCatalogMigration.class)).isNotNull();
    }

    // =========================================================================
    // Requirements 6 - 10: Shared Specification, Categories & Pizza Sizing
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("Req 6: Shared source contains all expected categories")
    void test06_sharedSourceContainsAllExpectedCategories() {
        List<CatalogSeedSpecifications.CategorySpec> categories = CatalogSeedSpecifications.categories();
        assertThat(categories).hasSize(14);
        List<String> names = categories.stream().map(CatalogSeedSpecifications.CategorySpec::name).toList();
        assertThat(names).containsExactly(
                "Appetizers",
                "Pizzas",
                "Salads",
                "Sandwiches",
                "Homemade Burgers",
                "Classics",
                "Pasta",
                "Panzerotti",
                "Kids Menu",
                "Beverages",
                "Hot Sandwiches",
                "Sides",
                "Gainsborough Melts",
                "A Taste of Greece"
        );
    }

    @Test
    @Order(7)
    @DisplayName("Req 7: Pre-seeding setup: Alias and operational preservation pre-insert")
    void test07_preSeedingAliasAndAdminPreload() {
        // Pre-insert an item with the documented alias "Garlic Bread and Cheese"
        // with custom administrative settings to verify they survive rename during sync.
        JpaMenuItem aliasItem = new JpaMenuItem(
                "pre-seeded-garlic-bread-id",
                "Garlic Bread and Cheese",
                "Pre-existing legacy description",
                new BigDecimal("9.99"),
                "Appetizers",
                "https://images.example/garlic-bread-custom.jpg",
                false, // available = false
                true   // isMostLoved = true
        );
        menuItemRepository.saveAndFlush(aliasItem);

        // Pre-insert an unrelated category to test non-destructive synchronization
        JpaCategory unrelatedCategory = new JpaCategory(
                "unrelated-cat-id",
                "Chef Specials",
                "Seasonal creations by the executive chef",
                99
        );
        categoryRepository.saveAndFlush(unrelatedCategory);

        // Pre-insert an unrelated menu item outside seed specification
        JpaMenuItem unrelatedItem = new JpaMenuItem(
                "unrelated-item-id",
                "Vintage Special Pizza",
                "An archival secret recipe",
                new BigDecimal("28.50"),
                "Chef Specials",
                "https://images.example/vintage.jpg",
                true,
                false
        );
        menuItemRepository.saveAndFlush(unrelatedItem);

        // Run the catalog synchronization
        boolean executed = syncService.synchronizeCatalog();
        assertThat(executed).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("Req 8: All 14 catalog categories are synchronized")
    void test08_all14CategoriesAreSynchronized() {
        // 14 seed categories + 1 unrelated category = 15 total
        List<JpaCategory> allCategories = categoryRepository.findAll();
        assertThat(allCategories).hasSize(15);

        for (CatalogSeedSpecifications.CategorySpec spec : CatalogSeedSpecifications.categories()) {
            JpaCategory found = categoryRepository.findByName(spec.name());
            assertThat(found).withFailMessage("Missing category: " + spec.name()).isNotNull();
            assertThat(found.getDescription()).isEqualTo(spec.description());
            assertThat(found.getDisplayOrder()).isEqualTo(spec.displayOrder());
        }
    }

    @Test
    @Order(9)
    @DisplayName("Req 9: Full existing menu catalog is seeded without omissions")
    void test09_fullExistingMenuCatalogIsSeededWithoutOmissions() {
        List<CatalogSeedSpecifications.MenuSpec> menuSpecs = CatalogSeedSpecifications.menuCatalog();
        assertThat(menuSpecs).hasSize(119);

        for (CatalogSeedSpecifications.MenuSpec spec : menuSpecs) {
            Optional<JpaMenuItem> match = menuItemRepository.findFirstByNameIgnoreCase(spec.name());
            assertThat(match).withFailMessage("Missing menu item: " + spec.name()).isPresent();
            JpaMenuItem item = match.get();
            assertThat(item.getDescription()).isEqualTo(spec.description());
            assertThat(item.getCategory()).isEqualTo(spec.category());
            assertThat(item.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(spec.price()));
        }
    }

    @Test
    @Order(10)
    @DisplayName("Req 10: Each pizza size is stored as a separately orderable item")
    void test10_eachPizzaSizeIsStoredAsSeparatelyOrderableItem() {
        List<String> pizzaNames = List.of(
                "Cheese", "Pepperoni", "Meat Lovers", "Canadian", "Deluxe",
                "Hawaiian", "Vegetarian", "Greek", "BBQ Chicken", "Butter Chicken"
        );

        for (String baseName : pizzaNames) {
            Optional<JpaMenuItem> ten = menuItemRepository.findFirstByNameIgnoreCase(baseName + " Pizza (10\")");
            Optional<JpaMenuItem> twelve = menuItemRepository.findFirstByNameIgnoreCase(baseName + " Pizza (12\")");
            Optional<JpaMenuItem> sixteen = menuItemRepository.findFirstByNameIgnoreCase(baseName + " Pizza (16\")");

            assertThat(ten).isPresent();
            assertThat(twelve).isPresent();
            assertThat(sixteen).isPresent();

            // Each size must have a distinct primary key
            assertThat(ten.get().getId()).isNotEqualTo(twelve.get().getId());
            assertThat(twelve.get().getId()).isNotEqualTo(sixteen.get().getId());
            assertThat(ten.get().getId()).isNotEqualTo(sixteen.get().getId());
        }
    }

    // =========================================================================
    // Requirements 11 - 16: ID Preservation, Alias Renaming & Admin Settings
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("Req 11: Alias match 'Garlic Bread and Cheese' -> 'Cheesy Garlic Bread' preserved ID")
    void test11_aliasMatchPreservesId() {
        JpaMenuItem item = menuItemRepository.findById("pre-seeded-garlic-bread-id").orElseThrow();
        assertThat(item.getName()).isEqualTo("Cheesy Garlic Bread");
        assertThat(item.getPrice()).isEqualByComparingTo(new BigDecimal("13.99"));
        assertThat(item.getDescription()).isEqualTo("Garlic butter, cheddar, and mozzarella melted over a toasted hoagie roll. Add bacon for $3.00.");
        assertThat(item.getCategory()).isEqualTo("Appetizers");

        // No duplicate Cheesy Garlic Bread item exists
        List<JpaMenuItem> matches = menuItemRepository.findAllByNameIgnoreCase("Cheesy Garlic Bread");
        assertThat(matches).hasSize(1);
    }

    @Test
    @Order(12)
    @DisplayName("Req 12: Alias match preserved custom image")
    void test12_aliasMatchPreservedCustomImage() {
        JpaMenuItem item = menuItemRepository.findById("pre-seeded-garlic-bread-id").orElseThrow();
        assertThat(item.getImage()).isEqualTo("https://images.example/garlic-bread-custom.jpg");
    }

    @Test
    @Order(13)
    @DisplayName("Req 13: Alias match preserved available = false")
    void test13_aliasMatchPreservedAvailableFalse() {
        JpaMenuItem item = menuItemRepository.findById("pre-seeded-garlic-bread-id").orElseThrow();
        assertThat(item.isAvailable()).isFalse();
    }

    @Test
    @Order(14)
    @DisplayName("Req 14: Alias match preserved isMostLoved = true")
    void test14_aliasMatchPreservedIsMostLovedTrue() {
        JpaMenuItem item = menuItemRepository.findById("pre-seeded-garlic-bread-id").orElseThrow();
        assertThat(item.isMostLoved()).isTrue();
    }

    @Test
    @Order(15)
    @DisplayName("Req 15: New menu items receive valid non-blank String UUID IDs")
    void test15_newMenuItemsReceiveValidStringIds() {
        List<JpaMenuItem> items = menuItemRepository.findAll();
        for (JpaMenuItem item : items) {
            assertThat(item.getId()).isNotBlank();
            assertThat(item.getId().length()).isLessThanOrEqualTo(64);
        }
    }

    @Test
    @Order(16)
    @DisplayName("Req 16: Category displayOrder values are correct and >= 0")
    void test16_categoryDisplayOrdersAreCorrect() {
        List<JpaCategory> categories = categoryRepository.findAllByOrderByDisplayOrderAsc();
        assertThat(categories).isNotEmpty();
        for (JpaCategory cat : categories) {
            assertThat(cat.getDisplayOrder()).isGreaterThanOrEqualTo(0);
        }
    }

    // =========================================================================
    // Requirements 17 - 20: BigDecimal Monetary Precision & Unrelated Retention
    // =========================================================================

    @Test
    @Order(17)
    @DisplayName("Req 17: BigDecimal prices persist with exact NUMERIC(10,2) scale 2")
    void test17_bigDecimalPricesPersistWithExactScale2() throws Exception {
        List<Double> testPrices = List.of(1.50, 2.00, 9.99, 13.99, 17.00, 29.00, 40.00);

        for (Double expectedDouble : testPrices) {
            BigDecimal expectedDecimal = BigDecimal.valueOf(expectedDouble).setScale(2);
            // Verify via direct JDBC from PostgreSQL NUMERIC(10,2) column
            try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
                 PreparedStatement stmt = conn.prepareStatement("SELECT price FROM menu_items WHERE price = ?")) {
                stmt.setBigDecimal(1, expectedDecimal);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next())
                            .withFailMessage("Expected price " + expectedDecimal + " not found in PostgreSQL")
                            .isTrue();
                    BigDecimal actualPrice = rs.getBigDecimal(1);
                    assertThat(actualPrice.scale()).isEqualTo(2);
                    assertThat(actualPrice).isEqualByComparingTo(expectedDecimal);
                }
            }
        }
    }

    @Test
    @Order(18)
    @DisplayName("Req 18: Existing unrelated categories remain intact")
    void test18_existingUnrelatedCategoriesRemainIntact() {
        Optional<JpaCategory> unrelated = categoryRepository.findById("unrelated-cat-id");
        assertThat(unrelated).isPresent();
        assertThat(unrelated.get().getName()).isEqualTo("Chef Specials");
        assertThat(unrelated.get().getDescription()).isEqualTo("Seasonal creations by the executive chef");
        assertThat(unrelated.get().getDisplayOrder()).isEqualTo(99);
    }

    @Test
    @Order(19)
    @DisplayName("Req 19: Existing unrelated menu items remain intact")
    void test19_existingUnrelatedMenuItemsRemainIntact() {
        Optional<JpaMenuItem> unrelated = menuItemRepository.findById("unrelated-item-id");
        assertThat(unrelated).isPresent();
        assertThat(unrelated.get().getName()).isEqualTo("Vintage Special Pizza");
        assertThat(unrelated.get().getCategory()).isEqualTo("Chef Specials");
        assertThat(unrelated.get().getPrice()).isEqualByComparingTo(new BigDecimal("28.50"));
    }

    @Test
    @Order(20)
    @DisplayName("Req 20: No broad deleteAll or destructive synchronization occurred")
    void test20_noBroadDeleteAllOccurred() {
        // Both unrelated records survived
        assertThat(categoryRepository.existsById("unrelated-cat-id")).isTrue();
        assertThat(menuItemRepository.existsById("unrelated-item-id")).isTrue();
    }

    // =========================================================================
    // Requirements 21 - 25: Migration Marker & Repeated Startup Idempotency
    // =========================================================================

    @Test
    @Order(21)
    @DisplayName("Req 21: Exact catalog migration marker is created with ID '2026-08-lord-gains-menu'")
    void test21_exactCatalogMigrationMarkerIsCreated() {
        Optional<JpaCatalogMigration> marker = migrationRepository.findById("2026-08-lord-gains-menu");
        assertThat(marker).isPresent();
        assertThat(marker.get().getId()).isEqualTo("2026-08-lord-gains-menu");
    }

    @Test
    @Order(22)
    @DisplayName("Req 22: Migration marker appliedAt is persisted as a valid Instant")
    void test22_appliedAtIsPersistedAsInstant() {
        JpaCatalogMigration marker = migrationRepository.findById("2026-08-lord-gains-menu").orElseThrow();
        assertThat(marker.getAppliedAt()).isNotNull();
        assertThat(marker.getAppliedAt()).isBeforeOrEqualTo(Instant.now().plus(1, ChronoUnit.MINUTES));
        assertThat(marker.getAppliedAt()).isAfter(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    @Test
    @Order(23)
    @DisplayName("Req 23: Repeated initialization creates no duplicate records and returns false")
    void test23_repeatedInitializationCreatesNoDuplicateRecords() {
        long initialCategoryCount = categoryRepository.count();
        long initialItemCount = menuItemRepository.count();

        boolean executed = syncService.synchronizeCatalog();
        assertThat(executed).isFalse(); // skipped because marker is present

        assertThat(categoryRepository.count()).isEqualTo(initialCategoryCount);
        assertThat(menuItemRepository.count()).isEqualTo(initialItemCount);
    }

    @Test
    @Order(24)
    @DisplayName("Req 24: Repeated initialization preserves original marker timestamp")
    void test24_repeatedInitializationPreservesOriginalMarkerTimestamp() {
        Instant originalTimestamp = migrationRepository.findById("2026-08-lord-gains-menu").orElseThrow().getAppliedAt();

        syncService.synchronizeCatalog();

        Instant currentTimestamp = migrationRepository.findById("2026-08-lord-gains-menu").orElseThrow().getAppliedAt();
        assertThat(currentTimestamp).isEqualTo(originalTimestamp);
    }

    @Test
    @Order(25)
    @DisplayName("Req 25: Repeated initialization preserves later administrator changes")
    void test25_repeatedInitializationPreservesLaterAdministratorChanges() {
        // 1. Simulate administrator changes
        JpaMenuItem burger = menuItemRepository.findFirstByNameIgnoreCase("Banquet Burger").orElseThrow();
        burger.setPrice(new BigDecimal("18.50")); // changed price
        burger.setDescription("Chef modified banquet burger description");
        burger.setAvailable(false);
        burger.setMostLoved(true);
        burger.setImage("https://images.example/admin-burger.png");
        menuItemRepository.saveAndFlush(burger);

        JpaCategory sandwiches = categoryRepository.findByName("Sandwiches");
        sandwiches.setDescription("Admin custom sandwich note");
        sandwiches.setDisplayOrder(44);
        categoryRepository.saveAndFlush(sandwiches);

        // 2. Re-run startup synchronization
        boolean executed = syncService.synchronizeCatalog();
        assertThat(executed).isFalse();

        // 3. Verify all administrator edits remained completely untouched
        JpaMenuItem reloadedBurger = menuItemRepository.findById(burger.getId()).orElseThrow();
        assertThat(reloadedBurger.getPrice()).isEqualByComparingTo(new BigDecimal("18.50"));
        assertThat(reloadedBurger.getDescription()).isEqualTo("Chef modified banquet burger description");
        assertThat(reloadedBurger.isAvailable()).isFalse();
        assertThat(reloadedBurger.isMostLoved()).isTrue();
        assertThat(reloadedBurger.getImage()).isEqualTo("https://images.example/admin-burger.png");

        JpaCategory reloadedSandwiches = categoryRepository.findById(sandwiches.getId()).orElseThrow();
        assertThat(reloadedSandwiches.getDescription()).isEqualTo("Admin custom sandwich note");
        assertThat(reloadedSandwiches.getDisplayOrder()).isEqualTo(44);
    }

    // =========================================================================
    // Requirements 26 - 29: Transactional Rollback & Ambiguous Match Protection
    // =========================================================================

    @Test
    @Order(26)
    @DisplayName("Req 26: Category sync failure rolls back whole transaction")
    void test26_categorySyncFailureRollsBackWholeTransaction() throws Exception {
        String rollbackDb = TEST_DB_PREFIX + "rb_cat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (IsolatedTestEnvironment env = new IsolatedTestEnvironment(testPort, rollbackDb)) {
            PostgresCatalogSynchronizationService failingService = new PostgresCatalogSynchronizationService(
                    env.categoryRepo, env.menuItemRepo, env.migrationRepo, env.em
            ) {
                @Override
                void syncCategories() {
                    // Trigger CHECK constraint violation: display_order >= 0
                    env.em.createNativeQuery("INSERT INTO categories (id, name, display_order) VALUES ('fail-cat', 'Bad Cat', -10)")
                            .executeUpdate();
                }
            };

            TransactionTemplate tt = new TransactionTemplate(env.txManager);
            assertThatThrownBy(() -> tt.execute(status -> failingService.synchronizeCatalog()))
                    .isInstanceOf(Exception.class);

            // Verify 0 categories and 0 markers persisted
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM categories")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(27)
    @DisplayName("Req 27: Menu item sync failure rolls back whole transaction")
    void test27_menuItemSyncFailureRollsBackWholeTransaction() throws Exception {
        String rollbackDb = TEST_DB_PREFIX + "rb_menu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (IsolatedTestEnvironment env = new IsolatedTestEnvironment(testPort, rollbackDb)) {
            PostgresCatalogSynchronizationService failingService = new PostgresCatalogSynchronizationService(
                    env.categoryRepo, env.menuItemRepo, env.migrationRepo, env.em
            ) {
                @Override
                void syncMenuItems() {
                    throw new RuntimeException("Simulated catastrophic menu sync failure");
                }
            };

            TransactionTemplate tt = new TransactionTemplate(env.txManager);
            assertThatThrownBy(() -> tt.execute(status -> failingService.synchronizeCatalog()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Simulated catastrophic menu sync failure");

            // Verify categories rolled back to 0
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM categories")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(28)
    @DisplayName("Req 28: Late synchronization exception rolls back catalog changes")
    void test28_lateExceptionRollsBackCatalogChanges() throws Exception {
        String rollbackDb = TEST_DB_PREFIX + "rb_mark_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (IsolatedTestEnvironment env = new IsolatedTestEnvironment(testPort, rollbackDb)) {
            PostgresCatalogSynchronizationService failingService = new PostgresCatalogSynchronizationService(
                    env.categoryRepo, env.menuItemRepo, env.migrationRepo, env.em
            ) {
                @Override
                public boolean synchronizeCatalog() {
                    super.syncCategories();
                    super.syncMenuItems();
                    throw new RuntimeException("Simulated late synchronization failure");
                }
            };

            TransactionTemplate tt = new TransactionTemplate(env.txManager);
            assertThatThrownBy(() -> tt.execute(status -> failingService.synchronizeCatalog()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Simulated late synchronization failure");

            // Verify categories, menu items, and migration markers rolled back to 0
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM categories")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM menu_items")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM catalog_migrations")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
            }
        }
    }

    @Test
    @Order(29)
    @DisplayName("Req 29: Ambiguous duplicate-name matches are handled safely with clear diagnostic")
    void test29_ambiguousDuplicateNameMatchesAreHandledSafely() throws Exception {
        String dbName = TEST_DB_PREFIX + "ambig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (IsolatedTestEnvironment env = new IsolatedTestEnvironment(testPort, dbName)) {
            // Insert duplicate items with identical canonical name via JDBC
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO menu_items (id, name, price, category) VALUES ('dup1', 'Cheesy Garlic Bread', 10.00, 'Appetizers')");
                stmt.executeUpdate("INSERT INTO menu_items (id, name, price, category) VALUES ('dup2', 'cheesy garlic bread', 11.00, 'Appetizers')");
            }

            TransactionTemplate tt = new TransactionTemplate(env.txManager);
            assertThatThrownBy(() -> tt.execute(status -> env.syncService.synchronizeCatalog()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ambiguous canonical menu item match for 'Cheesy Garlic Bread'");
        }
    }

    // =========================================================================
    // Requirements 30 - 34: Concurrency, MongoDB Safety, Reflection & Isolation
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("Req 30: Concurrent initialization cannot create duplicate seeded catalog entries")
    void test30_concurrentInitializationCannotCreateDuplicateSeededCatalogEntries() throws Exception {
        String concurrentDb = TEST_DB_PREFIX + "conc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (IsolatedTestEnvironment env = new IsolatedTestEnvironment(testPort, concurrentDb)) {
            TransactionTemplate tt = new TransactionTemplate(env.txManager);

            // Execute two concurrent initialization calls across separate threads
            CompletableFuture<Boolean> future1 = CompletableFuture.supplyAsync(() -> tt.execute(status -> env.syncService.synchronizeCatalog()));
            CompletableFuture<Boolean> future2 = CompletableFuture.supplyAsync(() -> tt.execute(status -> env.syncService.synchronizeCatalog()));

            CompletableFuture.allOf(future1, future2).join();

            boolean result1 = Boolean.TRUE.equals(future1.get());
            boolean result2 = Boolean.TRUE.equals(future2.get());

            // Exactly one thread must perform the migration (true) and the other must skip (false)
            assertThat(result1 ^ result2).isTrue();

            // Verify database state: exactly 14 categories, 119 menu items, 1 migration marker
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM categories")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(14);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM menu_items")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(119);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM catalog_migrations")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    @Order(31)
    @DisplayName("Req 31: Existing MongoDB DataInitializer still functions with shared specifications")
    void test31_existingMongoDataInitializerStillFunctions() {
        DataInitializer mongoInitializer = new DataInitializer();
        List<Category> savedCategories = new ArrayList<>();
        List<MenuItem> savedItems = new ArrayList<>();

        com.lordgainsborough.restaurant.repository.CategoryRepository mockCatRepo =
                org.mockito.Mockito.mock(com.lordgainsborough.restaurant.repository.CategoryRepository.class);
        org.mockito.Mockito.when(mockCatRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            savedCategories.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        com.lordgainsborough.restaurant.repository.MenuItemRepository mockMenuRepo =
                org.mockito.Mockito.mock(com.lordgainsborough.restaurant.repository.MenuItemRepository.class);
        org.mockito.Mockito.when(mockMenuRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            savedItems.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        mongoInitializer.syncCategories(mockCatRepo);
        mongoInitializer.syncMenu(mockMenuRepo);

        assertThat(savedCategories).hasSize(14);
        assertThat(savedItems).hasSize(119);
    }

    @Test
    @Order(32)
    @DisplayName("Req 32: No existing controller or service is switched to PostgreSQL")
    void test32_noExistingControllerOrServiceIsSwitchedToPostgres() {
        // MenuController must still declare com.lordgainsborough.restaurant.repository.MenuItemRepository (MongoDB)
        Field[] menuFields = MenuController.class.getDeclaredFields();
        boolean hasMongoMenuRepo = false;
        boolean hasJpaMenuRepo = false;
        for (Field f : menuFields) {
            if (f.getType().equals(com.lordgainsborough.restaurant.repository.MenuItemRepository.class)) {
                hasMongoMenuRepo = true;
            }
            if (f.getType().equals(JpaMenuItemRepository.class)) {
                hasJpaMenuRepo = true;
            }
        }
        assertThat(hasMongoMenuRepo).isTrue();
        assertThat(hasJpaMenuRepo).isFalse();

        // CategoryController must still declare com.lordgainsborough.restaurant.repository.CategoryRepository (MongoDB)
        Field[] catFields = CategoryController.class.getDeclaredFields();
        boolean hasMongoCatRepo = false;
        boolean hasJpaCatRepo = false;
        for (Field f : catFields) {
            if (f.getType().equals(com.lordgainsborough.restaurant.repository.CategoryRepository.class)) {
                hasMongoCatRepo = true;
            }
            if (f.getType().equals(JpaCategoryRepository.class)) {
                hasJpaCatRepo = true;
            }
        }
        assertThat(hasMongoCatRepo).isTrue();
        assertThat(hasJpaCatRepo).isFalse();

        // OrderController must still declare com.lordgainsborough.restaurant.repository.MenuItemRepository (MongoDB)
        Field[] orderFields = OrderController.class.getDeclaredFields();
        boolean hasMongoOrderMenuRepo = false;
        for (Field f : orderFields) {
            if (f.getType().equals(com.lordgainsborough.restaurant.repository.MenuItemRepository.class)) {
                hasMongoOrderMenuRepo = true;
            }
        }
        assertThat(hasMongoOrderMenuRepo).isTrue();
    }

    @Test
    @Order(33)
    @DisplayName("Req 33: Isolated relational test context contains correct JPA repositories and no MongoDB beans")
    void test33_isolatedRelationalTestContextContainsCorrectJpaRepositories() {
        assertThat(context.containsBean("jpaCategoryRepository")).isTrue();
        assertThat(context.containsBean("jpaMenuItemRepository")).isTrue();
        assertThat(context.containsBean("jpaCatalogMigrationRepository")).isTrue();

        assertThat(context.containsBean("categoryRepository")).isFalse();
        assertThat(context.containsBean("menuItemRepository")).isFalse();
        assertThat(context.containsBean("catalogMigrationRepository")).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    static class DefaultProfileTestPackageConfig {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DefaultProfileIsolationConfig {}

    @Test
    @Order(34)
    @DisplayName("Req 34: Default MongoDB configuration remains unaffected by relational exclusions")
    void test34_defaultMongoConfigurationRemainsUnaffected() {
        new ApplicationContextRunner()
                .withUserConfiguration(DefaultProfileTestPackageConfig.class)
                .withConfiguration(AutoConfigurations.of(DataJpaRepositoriesAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(JpaCategoryRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaMenuItemRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCatalogMigrationRepository.class);
                });

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
                    assertThat(ctx).doesNotHaveBean(PostgresCatalogSynchronizationService.class);
                    assertThat(ctx).doesNotHaveBean(JpaCategoryRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaMenuItemRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCatalogMigrationRepository.class);
                });
    }

    @Test
    @Order(35)
    @DisplayName("Req 35: Real JpaCatalogMigrationRepository.save() failure rolls back catalog changes")
    void test35_realMigrationMarkerSaveFailureRollsBackCatalogChanges() throws Exception {
        String rollbackDb = TEST_DB_PREFIX + "rb_save_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (IsolatedTestEnvironment env = new IsolatedTestEnvironment(testPort, rollbackDb)) {
            // Create a dynamic proxy delegating to env.migrationRepo, but intercepting save() to throw
            JpaCatalogMigrationRepository failingMigrationRepo = (JpaCatalogMigrationRepository) Proxy.newProxyInstance(
                    JpaCatalogMigrationRepository.class.getClassLoader(),
                    new Class<?>[]{JpaCatalogMigrationRepository.class},
                    (proxy, method, args) -> {
                        if ("save".equals(method.getName())) {
                            throw new DataIntegrityViolationException("Simulated JpaCatalogMigrationRepository.save() persistence failure");
                        }
                        return method.invoke(env.migrationRepo, args);
                    }
            );

            // Use the real PostgresCatalogSynchronizationService class (no methods overridden)
            PostgresCatalogSynchronizationService serviceWithFailingRepo = new PostgresCatalogSynchronizationService(
                    env.categoryRepo, env.menuItemRepo, failingMigrationRepo, env.em
            );

            TransactionTemplate tt = new TransactionTemplate(env.txManager);
            assertThatThrownBy(() -> tt.execute(status -> serviceWithFailingRepo.synchronizeCatalog()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Simulated JpaCatalogMigrationRepository.save() persistence failure");

            // Verify all category and menu changes rolled back and no marker remains
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM categories")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM menu_items")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM catalog_migrations")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
            }
        }
    }

    @Test
    @Order(36)
    @DisplayName("Req 36: Migration marker database flush failure rolls back catalog changes")
    void test36_migrationMarkerDatabaseFlushFailureRollsBackCatalogChanges() throws Exception {
        String rollbackDb = TEST_DB_PREFIX + "rb_flush_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (IsolatedTestEnvironment env = new IsolatedTestEnvironment(testPort, rollbackDb)) {
            // Install a database trigger on catalog_migrations that fails BEFORE INSERT
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE OR REPLACE FUNCTION trg_fail_marker_insert() RETURNS trigger AS $$\n"
                        + "BEGIN\n"
                        + "    RAISE EXCEPTION 'Simulated database flush failure during catalog migration marker insert';\n"
                        + "END;\n"
                        + "$$ LANGUAGE plpgsql;\n"
                        + "CREATE TRIGGER fail_marker_insert_trg\n"
                        + "BEFORE INSERT ON catalog_migrations\n"
                        + "FOR EACH ROW EXECUTE FUNCTION trg_fail_marker_insert();"
                );
            }

            // Run real unmodified syncService with real repos - save() and database flush are executed
            TransactionTemplate tt = new TransactionTemplate(env.txManager);
            assertThatThrownBy(() -> tt.execute(status -> env.syncService.synchronizeCatalog()))
                    .isInstanceOf(Exception.class);

            // Verify all category and menu changes rolled back and no marker remains
            try (Connection conn = DriverManager.getConnection(env.url, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM categories")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM menu_items")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM catalog_migrations")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
            }
        }
    }
}
