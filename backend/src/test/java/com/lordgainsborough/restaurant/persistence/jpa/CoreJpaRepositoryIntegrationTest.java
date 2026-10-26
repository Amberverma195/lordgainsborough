package com.lordgainsborough.restaurant.persistence.jpa;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCatalogMigration;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaStoreSettings;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCatalogMigrationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaStoreSettingsRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
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
import org.springframework.boot.persistence.autoconfigure.EntityScan;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-009: Integration test suite for Core Domain and Catalog Spring Data JPA repositories.
 * <p>
 * Tests {@link JpaUserRepository}, {@link JpaCategoryRepository}, {@link JpaMenuItemRepository},
 * {@link JpaCatalogMigrationRepository}, and {@link JpaStoreSettingsRepository}
 * against a disposable PostgreSQL 17.11 instance initialized via Flyway V1.
 * </p>
 * <p>
 * Validates all 25 specific query, constraint, precision, uniqueness, and configuration
 * requirements defined in PG-009 specification.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CoreJpaRepositoryIntegrationTest {

    private static final String TEST_DB = "core_jpa_repo_test";
    private static String testJdbcUrl;

    private AnnotationConfigApplicationContext context;
    private EntityManagerFactory entityManagerFactory;
    private JpaUserRepository userRepository;
    private JpaCategoryRepository categoryRepository;
    private JpaMenuItemRepository menuItemRepository;
    private JpaCatalogMigrationRepository catalogMigrationRepository;
    private JpaStoreSettingsRepository storeSettingsRepository;

    @Configuration
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
            props.put("hibernate.hbm2ddl.auto", "validate");
            props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            props.put("hibernate.show_sql", "false");
            emfBean.setJpaProperties(props);
            return emfBean;
        }

        @Bean
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    private String getRequiredPort() {
        String port = System.getProperty("test.postgres.port");
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-009 integration tests")
                .isNotBlank();
        return port;
    }

    @BeforeAll
    void setUpAll() throws Exception {
        String testPort = getRequiredPort();
        testJdbcUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/" + TEST_DB + "?sslmode=disable";

        // 1. Ensure test database exists
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + TEST_DB + "'");
            if (!rs.next()) {
                stmt.executeUpdate("CREATE DATABASE " + TEST_DB);
            }
        }

        // 2. Initialize Flyway schema
        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
        flyway.migrate();

        // 3. Clean any existing records from prior test runs
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM menu_items");
            stmt.executeUpdate("DELETE FROM categories");
            stmt.executeUpdate("DELETE FROM catalog_migrations");
            stmt.executeUpdate("DELETE FROM users");
            stmt.executeUpdate("DELETE FROM store_settings");
        }

        // 4. Initialize Spring application context with JPA repository support
        context = new AnnotationConfigApplicationContext(TestJpaRepoConfig.class);
        entityManagerFactory = context.getBean(EntityManagerFactory.class);
        userRepository = context.getBean(JpaUserRepository.class);
        categoryRepository = context.getBean(JpaCategoryRepository.class);
        menuItemRepository = context.getBean(JpaMenuItemRepository.class);
        catalogMigrationRepository = context.getBean(JpaCatalogMigrationRepository.class);
        storeSettingsRepository = context.getBean(JpaStoreSettingsRepository.class);
    }

    @AfterAll
    void tearDownAll() {
        if (context != null) {
            context.close();
        }
    }

    // =========================================================================
    // Requirement 1, 2, 3: Context, Isolation & Metamodel Validation
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Req 1: All five JPA repository interfaces are instantiated in isolated Spring context")
    void test01_allFiveJpaRepositoriesInstantiated() {
        assertThat(userRepository).isNotNull();
        assertThat(categoryRepository).isNotNull();
        assertThat(menuItemRepository).isNotNull();
        assertThat(catalogMigrationRepository).isNotNull();
        assertThat(storeSettingsRepository).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Req 2: No MongoDB repository is registered as a JPA repository")
    void test02_noMongoRepositoriesRegisteredInJpaContext() {
        String[] mongoRepoBeans = context.getBeanNamesForType(com.lordgainsborough.restaurant.repository.UserRepository.class);
        assertThat(mongoRepoBeans).isEmpty();

        String[] categoryMongoBeans = context.getBeanNamesForType(com.lordgainsborough.restaurant.repository.CategoryRepository.class);
        assertThat(categoryMongoBeans).isEmpty();

        String[] menuItemMongoBeans = context.getBeanNamesForType(com.lordgainsborough.restaurant.repository.MenuItemRepository.class);
        assertThat(menuItemMongoBeans).isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("Req 3: Hibernate schema validation succeeds against Flyway V1 schema")
    void test03_hibernateSchemaValidationSucceeds() {
        assertThat(entityManagerFactory).isNotNull();
        assertThat(entityManagerFactory.isOpen()).isTrue();
    }

    // =========================================================================
    // Requirement 4: JpaUserRepository CRUD lifecycle
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("Req 4: JpaUserRepository can save, load, update and delete a user")
    void test04_userRepository_crudLifecycle() {
        String id = "user-crud-" + UUID.randomUUID();
        String email = "crud.user." + UUID.randomUUID() + "@example.com";
        JpaUser user = new JpaUser(id, "Initial Name", email, "519-555-0101", "MEMBER");
        user.setPhoneNormalized("5195550101");
        user.setPassword("$2a$10$hashedpassword");
        user.setMemberSince("October 2026");
        user.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        // Save
        userRepository.save(user);

        // Load
        Optional<JpaUser> loaded = userRepository.findById(id);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getName()).isEqualTo("Initial Name");

        // Update
        loaded.get().setName("Updated Name");
        userRepository.save(loaded.get());

        Optional<JpaUser> reloaded = userRepository.findById(id);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getName()).isEqualTo("Updated Name");

        // Delete
        userRepository.deleteById(id);
        assertThat(userRepository.findById(id)).isEmpty();
    }

    // =========================================================================
    // Requirement 5, 6, 7, 8: JpaUserRepository Email Finder & Unique Constraints
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Req 5: Exact email finder matches exact casing and returns empty on mismatched case")
    void test05_exactEmailFinder_matchesExactCasing() {
        String id = "user-exact-email-" + UUID.randomUUID();
        String mixedEmail = "Arthur.Pendelton." + UUID.randomUUID() + "@Gainsborough.COM";
        JpaUser user = new JpaUser(id, "Arthur Pendelton", mixedEmail, "519-555-0102", "MEMBER");
        userRepository.save(user);

        // Exact casing match
        Optional<JpaUser> exact = userRepository.findByEmail(mixedEmail);
        assertThat(exact).isPresent();
        assertThat(exact.get().getId()).isEqualTo(id);

        // Mismatched casing returns empty for exact method
        Optional<JpaUser> mismatched = userRepository.findByEmail(mixedEmail.toLowerCase());
        assertThat(mismatched).isEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("Req 6: Case-insensitive email finder resolves mixed-case addresses")
    void test06_caseInsensitiveEmailFinder_resolvesMixedCase() {
        String id = "user-case-email-" + UUID.randomUUID();
        String mixedEmail = "Lady.Genevieve." + UUID.randomUUID() + "@LordGainsborough.COM";
        JpaUser user = new JpaUser(id, "Lady Genevieve", mixedEmail, "519-555-0103", "MEMBER");
        userRepository.save(user);

        assertThat(userRepository.findByEmailIgnoreCase(mixedEmail.toLowerCase())).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase(mixedEmail.toUpperCase())).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase(mixedEmail)).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("nonexistent." + UUID.randomUUID() + "@example.com")).isEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("Req 7: Email existence queries preserve exact versus case-insensitive semantics")
    void test07_emailExistenceQueries_preserveExactVsCaseInsensitive() {
        String id = "user-exists-email-" + UUID.randomUUID();
        String mixedEmail = "Sir.Galahad." + UUID.randomUUID() + "@Domain.ORG";
        JpaUser user = new JpaUser(id, "Sir Galahad", mixedEmail, "519-555-0104", "MEMBER");
        userRepository.save(user);

        // Exact existence
        assertThat(userRepository.existsByEmail(mixedEmail)).isTrue();
        assertThat(userRepository.existsByEmail(mixedEmail.toLowerCase())).isFalse();

        // Case-insensitive existence
        assertThat(userRepository.existsByEmailIgnoreCase(mixedEmail.toLowerCase())).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase(mixedEmail.toUpperCase())).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("nobody." + UUID.randomUUID() + "@domain.org")).isFalse();
    }

    @Test
    @Order(8)
    @DisplayName("Req 8: PostgreSQL functional email uniqueness rejects mixed-case duplicates")
    void test08_functionalEmailUniqueness_rejectsMixedCaseDuplicates() {
        String email = "DupeCheck." + UUID.randomUUID() + "@Example.com";
        JpaUser user1 = new JpaUser("u1-" + UUID.randomUUID(), "First", email, "519-555-0111", "MEMBER");
        userRepository.save(user1);

        JpaUser user2 = new JpaUser("u2-" + UUID.randomUUID(), "Second", email.toLowerCase(), "519-555-0112", "MEMBER");
        assertThatThrownBy(() -> userRepository.saveAndFlush(user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // Requirement 9, 10: Phone Queries
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("Req 9: Exact phone and normalized-phone finders work")
    void test09_exactPhoneAndNormalizedPhoneFinders() {
        String id = "user-phone-" + UUID.randomUUID();
        String rawPhone = "519-555-0188";
        String normalizedPhone = "5195550188";
        JpaUser user = new JpaUser(id, "Phone User", "phone." + UUID.randomUUID() + "@example.com", rawPhone, "MEMBER");
        user.setPhoneNormalized(normalizedPhone);
        userRepository.save(user);

        assertThat(userRepository.existsByPhone(rawPhone)).isTrue();
        assertThat(userRepository.existsByPhone("999-999-9999")).isFalse();

        assertThat(userRepository.existsByPhoneNormalized(normalizedPhone)).isTrue();
        assertThat(userRepository.existsByPhoneNormalized("0000000000")).isFalse();

        List<JpaUser> byNormalized = userRepository.findByPhoneNormalized(normalizedPhone);
        assertThat(byNormalized).hasSize(1);
        assertThat(byNormalized.get(0).getId()).isEqualTo(id);

        List<JpaUser> withPhone = userRepository.findByPhoneIsNotNull();
        assertThat(withPhone).isNotEmpty();
    }

    @Test
    @Order(10)
    @DisplayName("Req 10: Partial normalized-phone searches work")
    void test10_partialNormalizedPhoneSearches() {
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String normalized = "519888" + suffix;
        String id = "user-partial-phone-" + UUID.randomUUID();
        JpaUser user = new JpaUser(id, "Partial Phone", "partial.phone." + UUID.randomUUID() + "@example.com", "519-888-" + suffix, "MEMBER");
        user.setPhoneNormalized(normalized);
        userRepository.save(user);

        List<JpaUser> matches = userRepository.findByPhoneNormalizedContaining(suffix);
        assertThat(matches).anyMatch(u -> u.getId().equals(id));
    }

    // =========================================================================
    // Requirement 11, 12: Substring Search & SQL Wildcard Safety
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("Req 11: Name and email substring searches work regardless of casing")
    void test11_nameAndEmailSubstringSearches() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String id = "user-sub-" + UUID.randomUUID();
        String name = "Duke of Wellington " + marker;
        String email = "wellington." + marker + "@lordgainsborough.com";

        JpaUser user = new JpaUser(id, name, email, "519-555-0105", "MEMBER");
        user.setPhoneNormalized("5195550105");
        userRepository.save(user);

        List<JpaUser> byName = userRepository.findByNameContainingIgnoreCase("WELLINGTON " + marker.toLowerCase());
        assertThat(byName).isNotEmpty();
        assertThat(byName.get(0).getId()).isEqualTo(id);

        List<JpaUser> byEmail = userRepository.findByEmailContainingIgnoreCase("WELLINGTON." + marker.toUpperCase());
        assertThat(byEmail).isNotEmpty();
        assertThat(byEmail.get(0).getId()).isEqualTo(id);
    }

    @Test
    @Order(12)
    @DisplayName("Req 12: SQL wildcard characters are handled safely without unintended wildcard expansion")
    void test12_sqlWildcardInputHandledSafely() {
        String token = UUID.randomUUID().toString().substring(0, 6);
        String name1 = "Special 100% Chef " + token;
        String name2 = "Special 1000 Chef " + token;

        JpaUser user1 = new JpaUser("u-wild1-" + UUID.randomUUID(), name1, "wild1." + token + "@example.com", null, "MEMBER");
        JpaUser user2 = new JpaUser("u-wild2-" + UUID.randomUUID(), name2, "wild2." + token + "@example.com", null, "MEMBER");
        userRepository.saveAll(List.of(user1, user2));

        // Searching for exact substring with % should not throw SQL exception
        List<JpaUser> results = userRepository.findByNameContainingIgnoreCase("100% Chef " + token);
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(u -> u.getName().contains("100%"));
    }

    // =========================================================================
    // Requirement 13, 14, 15: Category Repository Queries & Semantics
    // =========================================================================

    @Test
    @Order(13)
    @DisplayName("Req 13: Category repository finds by exact name")
    void test13_categoryRepository_findByExactName() {
        String catId = "cat-exact-" + UUID.randomUUID();
        String catName = "Exclusive Wines " + UUID.randomUUID();
        JpaCategory category = new JpaCategory(catId, catName, "Finest vintage selections", 5);
        categoryRepository.save(category);

        JpaCategory found = categoryRepository.findByName(catName);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(catId);
        assertThat(found.getName()).isEqualTo(catName);
    }

    @Test
    @Order(14)
    @DisplayName("Req 14: Missing category returns null where required by the existing contract")
    void test14_categoryRepository_missingReturnsNull() {
        JpaCategory missing = categoryRepository.findByName("NonExistentCategory_" + UUID.randomUUID());
        assertThat(missing).isNull();
    }

    @Test
    @Order(15)
    @DisplayName("Req 15: Category CRUD and displayOrder updates work")
    void test15_categoryRepository_crudAndDisplayOrderUpdates() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JpaCategory cat3 = new JpaCategory("cat-ord-3-" + suffix, "Desserts " + suffix, "Sweet finishes", 30);
        JpaCategory cat1 = new JpaCategory("cat-ord-1-" + suffix, "Starters " + suffix, "Beginning dishes", 10);
        JpaCategory cat2 = new JpaCategory("cat-ord-2-" + suffix, "Mains " + suffix, "Main entrees", 20);
        categoryRepository.saveAll(List.of(cat3, cat1, cat2));

        List<JpaCategory> ordered = categoryRepository.findAllByOrderByDisplayOrderAsc();
        assertThat(ordered).isNotEmpty();
        for (int i = 0; i < ordered.size() - 1; i++) {
            assertThat(ordered.get(i).getDisplayOrder())
                    .isLessThanOrEqualTo(ordered.get(i + 1).getDisplayOrder());
        }

        // Update display order
        cat3.setDisplayOrder(5);
        categoryRepository.save(cat3);

        JpaCategory updatedCat3 = categoryRepository.findByName("Desserts " + suffix);
        assertThat(updatedCat3).isNotNull();
        assertThat(updatedCat3.getDisplayOrder()).isEqualTo(5);
    }

    // =========================================================================
    // Requirement 16, 17, 18, 19: Menu Item Repository Queries & Behavior
    // =========================================================================

    @Test
    @Order(16)
    @DisplayName("Req 16: Menu items can be found by category")
    void test16_menuItems_findByCategory() {
        String catName = "Steaks & Grills " + UUID.randomUUID();
        String itemId = "item-cat-" + UUID.randomUUID();
        JpaMenuItem item = new JpaMenuItem(itemId, "Wagyu Ribeye " + UUID.randomUUID(), "Desc", new BigDecimal("65.00"), catName, null, true, true);
        menuItemRepository.save(item);

        List<JpaMenuItem> byCategory = menuItemRepository.findByCategory(catName);
        assertThat(byCategory).hasSize(1);
        assertThat(byCategory.get(0).getId()).isEqualTo(itemId);

        // Empty category returns empty list
        List<JpaMenuItem> emptyCategory = menuItemRepository.findByCategory("Nonexistent Category " + UUID.randomUUID());
        assertThat(emptyCategory).isEmpty();
    }

    @Test
    @Order(17)
    @DisplayName("Req 17: Case-insensitive menu name finder works")
    void test17_menuItems_caseInsensitiveNameFinder() {
        String name = "Atlantic Lobster Thermidor " + UUID.randomUUID();
        String itemId = "item-lob-" + UUID.randomUUID();
        JpaMenuItem item = new JpaMenuItem(itemId, name, "Lobster with cheese reduction", new BigDecimal("48.00"), "Seafood", null, true, false);
        menuItemRepository.save(item);

        Optional<JpaMenuItem> found = menuItemRepository.findFirstByNameIgnoreCase(name.toLowerCase());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(itemId);

        Optional<JpaMenuItem> foundUpper = menuItemRepository.findFirstByNameIgnoreCase(name.toUpperCase());
        assertThat(foundUpper).isPresent();
        assertThat(foundUpper.get().getId()).isEqualTo(itemId);
    }

    @Test
    @Order(18)
    @DisplayName("Req 18: Multiple matching menu names have stable, documented first-match behavior")
    void test18_menuItems_multipleMatchingNamesStableFirstMatch() {
        String sharedName = "Special Soup " + UUID.randomUUID();
        String id1 = "item-soup-1-" + UUID.randomUUID();
        String id2 = "item-soup-2-" + UUID.randomUUID();

        // SQL allows duplicate names since menu_items.name is not UNIQUE
        JpaMenuItem item1 = new JpaMenuItem(id1, sharedName, "Version 1", new BigDecimal("12.00"), "Soups", null, true, false);
        JpaMenuItem item2 = new JpaMenuItem(id2, sharedName, "Version 2", new BigDecimal("14.00"), "Soups", null, true, false);
        menuItemRepository.saveAll(List.of(item1, item2));

        // findFirstByNameIgnoreCase returns a non-empty result
        Optional<JpaMenuItem> first = menuItemRepository.findFirstByNameIgnoreCase(sharedName);
        assertThat(first).isPresent();
        assertThat(List.of(id1, id2)).contains(first.get().getId());

        // findFirstByNameIgnoreCaseOrderByIdAsc provides deterministic ordering
        Optional<JpaMenuItem> stable = menuItemRepository.findFirstByNameIgnoreCaseOrderByIdAsc(sharedName);
        assertThat(stable).isPresent();
        String expectedId = id1.compareTo(id2) < 0 ? id1 : id2;
        assertThat(stable.get().getId()).isEqualTo(expectedId);
    }

    @Test
    @Order(19)
    @DisplayName("Req 19: Menu price BigDecimal precision remains exact")
    void test19_menuItems_priceBigDecimalPrecision() {
        BigDecimal exactPrice = new BigDecimal("42.99");
        String itemId = "item-price-" + UUID.randomUUID();
        JpaMenuItem item = new JpaMenuItem(itemId, "Precision Steak " + UUID.randomUUID(), "Desc", exactPrice, "Mains", null, true, false);
        menuItemRepository.save(item);

        Optional<JpaMenuItem> loaded = menuItemRepository.findById(itemId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getPrice()).isEqualByComparingTo(exactPrice);
        assertThat(loaded.get().getPrice().scale()).isEqualTo(2);
    }

    // =========================================================================
    // Requirement 20, 21: CatalogMigration Repository
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("Req 20: CatalogMigration assigned IDs are preserved without alteration or UUID generation")
    void test20_catalogMigration_assignedIdsPreserved() {
        String assignedId = "2026-10-lord-gains-autumn-" + UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JpaCatalogMigration migration = new JpaCatalogMigration(assignedId, now);

        catalogMigrationRepository.save(migration);

        Optional<JpaCatalogMigration> loaded = catalogMigrationRepository.findById(assignedId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo(assignedId);
        assertThat(loaded.get().getAppliedAt()).isEqualTo(now);
    }

    @Test
    @Order(21)
    @DisplayName("Req 21: CatalogMigration existsById works correctly before and after save")
    void test21_catalogMigration_existsById() {
        String testMigrationId = "2026-11-winter-menu-" + UUID.randomUUID();
        assertThat(catalogMigrationRepository.existsById(testMigrationId)).isFalse();

        catalogMigrationRepository.save(new JpaCatalogMigration(testMigrationId, Instant.now()));
        assertThat(catalogMigrationRepository.existsById(testMigrationId)).isTrue();
    }

    // =========================================================================
    // Requirement 22, 23: StoreSettings Singleton & Numeric Precision
    // =========================================================================

    @Test
    @Order(22)
    @DisplayName("Req 22: StoreSettings singleton can be inserted, updated and retrieved")
    void test22_storeSettings_singletonInsertUpdateRetrieve() {
        JpaStoreSettings settings = new JpaStoreSettings(
                JpaStoreSettings.DEFAULT_ID,
                new BigDecimal("0.1300"),
                25,
                "Lord Gainsborough"
        );
        settings.setContactEmail("info@lordgainsborough.com");
        settings.setPickupOrdersEnabled(true);
        settings.setKdsAllowedIp("192.168.1.100");
        storeSettingsRepository.save(settings);

        Optional<JpaStoreSettings> loaded = storeSettingsRepository.findById(JpaStoreSettings.DEFAULT_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDefaultReadyMinutes()).isEqualTo(25);

        // Update operational parameters
        loaded.get().setDefaultReadyMinutes(35);
        loaded.get().setPickupOrdersEnabled(false);
        storeSettingsRepository.save(loaded.get());

        Optional<JpaStoreSettings> updated = storeSettingsRepository.findById(JpaStoreSettings.DEFAULT_ID);
        assertThat(updated).isPresent();
        assertThat(updated.get().getDefaultReadyMinutes()).isEqualTo(35);
        assertThat(updated.get().isPickupOrdersEnabled()).isFalse();
    }

    @Test
    @Order(23)
    @DisplayName("Req 23: Tax rate precision (precision 5, scale 4) survives repository save and reload")
    void test23_storeSettings_taxRatePrecisionSurvivesSaveAndReload() {
        BigDecimal taxRate = new BigDecimal("0.1300");
        JpaStoreSettings settings = new JpaStoreSettings(
                JpaStoreSettings.DEFAULT_ID,
                taxRate,
                30,
                "Lord Gainsborough Fine Dining"
        );
        storeSettingsRepository.save(settings);

        Optional<JpaStoreSettings> loaded = storeSettingsRepository.findById(JpaStoreSettings.DEFAULT_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getTaxRate()).isEqualByComparingTo(taxRate);
        assertThat(loaded.get().getTaxRate().scale()).isEqualTo(4);
    }

    // =========================================================================
    // Requirement 24, 25: Isolation & Bean Name Collision Protection
    // =========================================================================

    @Test
    @Order(24)
    @DisplayName("Req 24: JPA repository beans do not collide with existing MongoDB repository names")
    void test24_jpaRepositoryBeansDoNotCollideWithMongoNames() {
        // All JPA repositories are prefixed with Jpa...
        assertThat(context.containsBean("jpaUserRepository")).isTrue();
        assertThat(context.containsBean("jpaCategoryRepository")).isTrue();
        assertThat(context.containsBean("jpaMenuItemRepository")).isTrue();
        assertThat(context.containsBean("jpaCatalogMigrationRepository")).isTrue();
        assertThat(context.containsBean("jpaStoreSettingsRepository")).isTrue();

        // Legacy MongoDB repository bean names must NOT be present in this context
        assertThat(context.containsBean("userRepository")).isFalse();
        assertThat(context.containsBean("categoryRepository")).isFalse();
        assertThat(context.containsBean("menuItemRepository")).isFalse();
        assertThat(context.containsBean("catalogMigrationRepository")).isFalse();
        assertThat(context.containsBean("storeSettingsRepository")).isFalse();
    }

    @Test
    @Order(25)
    @DisplayName("Req 25: The default MongoDB configuration remains isolated from JPA repository activation")
    void test25_defaultMongoConfigurationRemainsIsolated() {
        // Assert that legacy repository classes are in a completely distinct package
        assertThat(com.lordgainsborough.restaurant.repository.UserRepository.class.getPackage().getName())
                .isEqualTo("com.lordgainsborough.restaurant.repository");
        assertThat(JpaUserRepository.class.getPackage().getName())
                .isEqualTo("com.lordgainsborough.restaurant.persistence.jpa.repository");
    }
}
