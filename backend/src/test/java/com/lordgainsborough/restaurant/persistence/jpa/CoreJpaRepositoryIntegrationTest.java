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
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CoreJpaRepositoryIntegrationTest {

    private static final String TEST_DB = "core_jpa_repo_test";
    private static String testJdbcUrl;

    private AnnotationConfigApplicationContext context;
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
        userRepository = context.getBean(JpaUserRepository.class);
        categoryRepository = context.getBean(JpaCategoryRepository.class);
        menuItemRepository = context.getBean(JpaMenuItemRepository.class);
        catalogMigrationRepository = context.getBean(JpaCatalogMigrationRepository.class);
        storeSettingsRepository = context.getBean(JpaStoreSettingsRepository.class);

        assertThat(userRepository).isNotNull();
        assertThat(categoryRepository).isNotNull();
        assertThat(menuItemRepository).isNotNull();
        assertThat(catalogMigrationRepository).isNotNull();
        assertThat(storeSettingsRepository).isNotNull();
    }

    @AfterAll
    void tearDownAll() {
        if (context != null) {
            context.close();
        }
    }

    // =========================================================================
    // JpaUserRepository Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("JpaUserRepository: save, findById, and verify core attributes")
    void test1_userRepository_saveAndFindById() {
        String id = "user-repo-" + UUID.randomUUID();
        String email = "repo.user." + UUID.randomUUID() + "@example.com";
        JpaUser user = new JpaUser(id, "Repo User", email, "519-555-0101", "MEMBER");
        user.setPhoneNormalized("5195550101");
        user.setPassword("$2a$10$hashedpassword");
        user.setMemberSince("October 2026");
        user.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        JpaUser saved = userRepository.save(user);
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(id);

        Optional<JpaUser> found = userRepository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Repo User");
        assertThat(found.get().getEmail()).isEqualTo(email);
        assertThat(found.get().getPhoneNormalized()).isEqualTo("5195550101");
    }

    @Test
    @Order(2)
    @DisplayName("JpaUserRepository: findByEmail and findByEmailIgnoreCase")
    void test2_userRepository_findByEmailVariations() {
        String id = "user-email-" + UUID.randomUUID();
        String mixedEmail = "MixedCase." + UUID.randomUUID() + "@Example.COM";
        JpaUser user = new JpaUser(id, "Mixed User", mixedEmail, "519-555-0102", "MEMBER");
        user.setPhoneNormalized("5195550102");
        userRepository.save(user);

        // Exact match
        assertThat(userRepository.findByEmail(mixedEmail)).isPresent();

        // Case-insensitive match
        assertThat(userRepository.findByEmailIgnoreCase(mixedEmail.toLowerCase())).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase(mixedEmail.toUpperCase())).isPresent();

        // Non-existent
        assertThat(userRepository.findByEmail("nonexistent@example.com")).isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("JpaUserRepository: existsByEmail and existsByEmailIgnoreCase")
    void test3_userRepository_existsByEmailVariations() {
        String id = "user-exists-" + UUID.randomUUID();
        String email = "Exists." + UUID.randomUUID() + "@Example.com";
        JpaUser user = new JpaUser(id, "Exists User", email, "519-555-0103", "MEMBER");
        user.setPhoneNormalized("5195550103");
        userRepository.save(user);

        assertThat(userRepository.existsByEmail(email)).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase(email.toLowerCase())).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase(email.toUpperCase())).isTrue();
        assertThat(userRepository.existsByEmail("doesnotexist@example.com")).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("JpaUserRepository: phone query methods (existsByPhone, normalized, and containing)")
    void test4_userRepository_phoneQueries() {
        String id = "user-phone-" + UUID.randomUUID();
        String rawPhone = "519-555-0199";
        String normalizedPhone = "5195550199";
        JpaUser user = new JpaUser(id, "Phone User", "phone." + UUID.randomUUID() + "@example.com", rawPhone, "MEMBER");
        user.setPhoneNormalized(normalizedPhone);
        userRepository.save(user);

        assertThat(userRepository.existsByPhone(rawPhone)).isTrue();
        assertThat(userRepository.existsByPhone("999-999-9999")).isFalse();

        assertThat(userRepository.existsByPhoneNormalized(normalizedPhone)).isTrue();
        assertThat(userRepository.existsByPhoneNormalized("0000000000")).isFalse();

        List<JpaUser> byNormalized = userRepository.findByPhoneNormalized(normalizedPhone);
        assertThat(byNormalized).isNotEmpty();
        assertThat(byNormalized.get(0).getId()).isEqualTo(id);

        List<JpaUser> bySubstr = userRepository.findByPhoneNormalizedContaining("5550199");
        assertThat(bySubstr).isNotEmpty();

        List<JpaUser> withPhone = userRepository.findByPhoneIsNotNull();
        assertThat(withPhone).isNotEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("JpaUserRepository: search methods (findByNameContainingIgnoreCase, findByEmailContainingIgnoreCase)")
    void test5_userRepository_adminCustomerSearchMethods() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String id = "user-search-" + UUID.randomUUID();
        String name = "Lord Bartholomew " + marker;
        String email = "bart." + marker + "@lordgainsborough.com";

        JpaUser user = new JpaUser(id, name, email, "519-555-0105", "MEMBER");
        user.setPhoneNormalized("5195550105");
        userRepository.save(user);

        // Name containing ignore case
        List<JpaUser> byName = userRepository.findByNameContainingIgnoreCase("BARTHOLOMEW " + marker.toLowerCase());
        assertThat(byName).isNotEmpty();
        assertThat(byName.get(0).getId()).isEqualTo(id);

        // Email containing ignore case
        List<JpaUser> byEmail = userRepository.findByEmailContainingIgnoreCase("BART." + marker.toUpperCase());
        assertThat(byEmail).isNotEmpty();
        assertThat(byEmail.get(0).getId()).isEqualTo(id);
    }

    @Test
    @Order(6)
    @DisplayName("JpaUserRepository: case-insensitive email duplicate rejected by PostgreSQL unique constraint")
    void test6_userRepository_duplicateEmailRejected() {
        String email = "Unique." + UUID.randomUUID() + "@example.com";
        JpaUser user1 = new JpaUser("u1-" + UUID.randomUUID(), "First", email, "519-555-0111", "MEMBER");
        userRepository.save(user1);

        JpaUser user2 = new JpaUser("u2-" + UUID.randomUUID(), "Second", email.toLowerCase(), "519-555-0112", "MEMBER");
        assertThatThrownBy(() -> userRepository.saveAndFlush(user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // JpaCategoryRepository Tests
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("JpaCategoryRepository: save, findById, findByName, and existsByName")
    void test7_categoryRepository_basicQueries() {
        String catId = "cat-" + UUID.randomUUID();
        String catName = "Starters & Appetizers " + UUID.randomUUID();
        JpaCategory category = new JpaCategory(catId, catName, "Crispy and fresh starters", 1);
        categoryRepository.save(category);

        Optional<JpaCategory> byId = categoryRepository.findById(catId);
        assertThat(byId).isPresent();
        assertThat(byId.get().getName()).isEqualTo(catName);

        Optional<JpaCategory> byName = categoryRepository.findByName(catName);
        assertThat(byName).isPresent();
        assertThat(byName.get().getId()).isEqualTo(catId);

        Optional<JpaCategory> byNameIgnoreCase = categoryRepository.findByNameIgnoreCase(catName.toUpperCase());
        assertThat(byNameIgnoreCase).isPresent();

        assertThat(categoryRepository.existsByName(catName)).isTrue();
        assertThat(categoryRepository.existsByNameIgnoreCase(catName.toLowerCase())).isTrue();
        assertThat(categoryRepository.existsByName("Nonexistent Category")).isFalse();
    }

    @Test
    @Order(8)
    @DisplayName("JpaCategoryRepository: findAllByOrderByDisplayOrderAsc returns categories in ascending display order")
    void test8_categoryRepository_findAllByOrderByDisplayOrderAsc() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JpaCategory cat3 = new JpaCategory("cat-ord-3-" + suffix, "Desserts " + suffix, "Sweet finishes", 3);
        JpaCategory cat1 = new JpaCategory("cat-ord-1-" + suffix, "Starters " + suffix, "Beginning dishes", 1);
        JpaCategory cat2 = new JpaCategory("cat-ord-2-" + suffix, "Mains " + suffix, "Main entrees", 2);

        categoryRepository.saveAll(List.of(cat3, cat1, cat2));

        List<JpaCategory> ordered = categoryRepository.findAllByOrderByDisplayOrderAsc();
        assertThat(ordered).isNotEmpty();

        // Verify strictly non-decreasing order of displayOrder
        for (int i = 0; i < ordered.size() - 1; i++) {
            assertThat(ordered.get(i).getDisplayOrder())
                    .isLessThanOrEqualTo(ordered.get(i + 1).getDisplayOrder());
        }
    }

    // =========================================================================
    // JpaMenuItemRepository Tests
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("JpaMenuItemRepository: save, findByCategory, and findFirstByNameIgnoreCase")
    void test9_menuItemRepository_categoryAndNameQueries() {
        String catName = "Seasonal Mains " + UUID.randomUUID();
        String itemId = "item-repo-" + UUID.randomUUID();
        String itemName = "Crispy Duck Breast " + UUID.randomUUID();
        BigDecimal price = new BigDecimal("32.50");

        JpaMenuItem item = new JpaMenuItem(
                itemId,
                itemName,
                "Pan-seared duck breast with cherry reduction",
                price,
                catName,
                "https://images.example.com/duck.jpg",
                true,
                true
        );
        menuItemRepository.save(item);

        List<JpaMenuItem> byCategory = menuItemRepository.findByCategory(catName);
        assertThat(byCategory).hasSize(1);
        assertThat(byCategory.get(0).getName()).isEqualTo(itemName);

        Optional<JpaMenuItem> byName = menuItemRepository.findFirstByNameIgnoreCase(itemName.toLowerCase());
        assertThat(byName).isPresent();
        assertThat(byName.get().getId()).isEqualTo(itemId);
        assertThat(byName.get().getPrice().compareTo(price)).isEqualTo(0);

        assertThat(menuItemRepository.existsByName(itemName)).isTrue();
        assertThat(menuItemRepository.existsByNameIgnoreCase(itemName.toUpperCase())).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("JpaMenuItemRepository: findByAvailableTrue returns only available items")
    void test10_menuItemRepository_availableQuery() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JpaMenuItem availItem = new JpaMenuItem(
                "item-avail-" + suffix,
                "Available Item " + suffix,
                "Desc",
                new BigDecimal("15.00"),
                "Mains",
                null,
                true,
                false
        );
        JpaMenuItem unavailItem = new JpaMenuItem(
                "item-unavail-" + suffix,
                "Unavailable Item " + suffix,
                "Desc",
                new BigDecimal("15.00"),
                "Mains",
                null,
                false,
                false
        );

        menuItemRepository.saveAll(List.of(availItem, unavailItem));

        List<JpaMenuItem> available = menuItemRepository.findByAvailableTrue();
        assertThat(available).anyMatch(i -> i.getId().equals("item-avail-" + suffix));
        assertThat(available).noneMatch(i -> i.getId().equals("item-unavail-" + suffix));
    }

    // =========================================================================
    // JpaCatalogMigrationRepository Tests
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("JpaCatalogMigrationRepository: save, existsById, and findById")
    void test11_catalogMigrationRepository_crudAndExists() {
        String migrationId = "migration-repo-" + UUID.randomUUID();
        Instant appliedTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        assertThat(catalogMigrationRepository.existsById(migrationId)).isFalse();

        JpaCatalogMigration migration = new JpaCatalogMigration(migrationId, appliedTime);
        catalogMigrationRepository.save(migration);

        assertThat(catalogMigrationRepository.existsById(migrationId)).isTrue();

        Optional<JpaCatalogMigration> loaded = catalogMigrationRepository.findById(migrationId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo(migrationId);
        assertThat(loaded.get().getAppliedAt()).isEqualTo(appliedTime);
    }

    // =========================================================================
    // JpaStoreSettingsRepository Tests
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("JpaStoreSettingsRepository: save, findById('default'), and tax rate numeric precision")
    void test12_storeSettingsRepository_singletonAndPrecision() {
        BigDecimal taxRate = new BigDecimal("0.1300");
        JpaStoreSettings settings = new JpaStoreSettings(
                JpaStoreSettings.DEFAULT_ID,
                taxRate,
                30,
                "Lord Gainsborough Fine Dining"
        );
        settings.setContactEmail("hospitality@lordgainsborough.com");
        settings.setContactPhone("519-555-0100");
        settings.setKdsAllowedIp("10.0.0.50");

        storeSettingsRepository.save(settings);

        Optional<JpaStoreSettings> loaded = storeSettingsRepository.findById(JpaStoreSettings.DEFAULT_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo("default");
        assertThat(loaded.get().getTaxRate().compareTo(taxRate)).isEqualTo(0);
        assertThat(loaded.get().getDefaultReadyMinutes()).isEqualTo(30);
        assertThat(loaded.get().getStoreName()).isEqualTo("Lord Gainsborough Fine Dining");
        assertThat(loaded.get().getContactEmail()).isEqualTo("hospitality@lordgainsborough.com");
        assertThat(loaded.get().getKdsAllowedIp()).isEqualTo("10.0.0.50");
    }
}
