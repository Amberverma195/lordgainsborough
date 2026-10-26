package com.lordgainsborough.restaurant.persistence.jpa;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCatalogMigration;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CoreJpaEntityIntegrationTest {

    private static final String TEST_DB = "core_jpa_test";
    private String testPort;
    private String jdbcUrl;
    private PGSimpleDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean emfBean;
    private EntityManagerFactory emf;

    @Configuration
    @EntityScan(basePackageClasses = JpaUser.class)
    static class TestEntityScanConfig {}

    private String getRequiredPort() {
        String port = System.getProperty("test.postgres.port");
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-004 PostgreSQL integration tests")
                .isNotBlank();
        return port;
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

        // 2. Initialize schema using Flyway (Flyway owns the database schema)
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
    @DisplayName("Hibernate metadata validation passes with ddl-auto=validate against V1 Flyway schema")
    void test1_hibernateMetadataValidation_succeedsAgainstV1Schema() {
        // If emf initialized in @BeforeAll with hibernate.hbm2ddl.auto=validate,
        // all 5 core JPA entity mappings have been strictly validated by Hibernate against PostgreSQL tables.
        assertThat(emf.isOpen()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("JpaUser: persist, flush, clear, reload, update, and reject case-insensitive email collision")
    void test2_userEntity_lifecycleAndCaseInsensitiveEmailUniqueness() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();

        String userId = UUID.randomUUID().toString();
        String initialEmail = "User.One@LordGainsborough.com";
        String normalizedPhone = "5195550101";

        try {
            tx.begin();
            JpaUser user = new JpaUser(userId, "Lord User", initialEmail, "519-555-0101", "MEMBER");
            user.setPhoneNormalized(normalizedPhone);
            user.setPassword("$2a$10$hashedpassword");
            user.setMemberSince("October 2026");
            user.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
            user.setTokenVersion(0L);
            user.setNoShowCount(0);

            em.persist(user);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Reload in new session
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaUser reloaded = em.find(JpaUser.class, userId);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getName()).isEqualTo("Lord User");
            assertThat(reloaded.getEmail()).isEqualTo(initialEmail);
            assertThat(reloaded.getPhoneNormalized()).isEqualTo(normalizedPhone);
            assertThat(reloaded.getRole()).isEqualTo("MEMBER");
            assertThat(reloaded.getTokenVersion()).isEqualTo(0L);
            assertThat(reloaded.getNoShowCount()).isEqualTo(0);
            assertThat(reloaded.getCreatedAt()).isNotNull();

            // Update user security attributes
            reloaded.setTokenVersion(1L);
            reloaded.setNoShowCount(1);
            reloaded.setLastProfileUpdateAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
            reloaded.setPasswordChangedAt(Instant.now());
            reloaded.setLastPasswordResetOperationId(UUID.randomUUID().toString());

            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify update persisted
        em = emf.createEntityManager();
        try {
            JpaUser updated = em.find(JpaUser.class, userId);
            assertThat(updated).isNotNull();
            assertThat(updated.getTokenVersion()).isEqualTo(1L);
            assertThat(updated.getNoShowCount()).isEqualTo(1);
            assertThat(updated.getLastProfileUpdateAt()).isNotNull();
            assertThat(updated.getPasswordChangedAt()).isNotNull();
            assertThat(updated.getLastPasswordResetOperationId()).isNotNull();
        } finally {
            em.close();
        }

        // Verify case-insensitive duplicate email rejection by PostgreSQL functional unique index
        EntityManager collEm = emf.createEntityManager();
        EntityTransaction collTx = collEm.getTransaction();
        try {
            collTx.begin();
            JpaUser duplicateUser = new JpaUser(
                    UUID.randomUUID().toString(),
                    "Another Name",
                    initialEmail.toLowerCase(), // lower-case version of User.One@LordGainsborough.com
                    "519-555-0999",
                    "MEMBER"
            );
            duplicateUser.setPhoneNormalized("5195550999");
            collEm.persist(duplicateUser);

            assertThatThrownBy(collEm::flush)
                    .isInstanceOf(PersistenceException.class);
            collTx.rollback();
        } finally {
            collEm.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("JpaCategory: persist, flush, clear, reload, update display order and description")
    void test3_categoryEntity_lifecycleAndDisplayOrder() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String catId = "cat-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCategory category = new JpaCategory(catId, "Signature Mains", "Our premier chef selections", 1);
            em.persist(category);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCategory loaded = em.find(JpaCategory.class, catId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getName()).isEqualTo("Signature Mains");
            assertThat(loaded.getDescription()).isEqualTo("Our premier chef selections");
            assertThat(loaded.getDisplayOrder()).isEqualTo(1);

            // Update display order and description
            loaded.setDisplayOrder(2);
            loaded.setDescription("Updated chef selections");
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
            JpaCategory verified = em.find(JpaCategory.class, catId);
            assertThat(verified).isNotNull();
            assertThat(verified.getDisplayOrder()).isEqualTo(2);
            assertThat(verified.getDescription()).isEqualTo("Updated chef selections");
        } finally {
            em.close();
        }
    }

    @Test
    @Order(4)
    @DisplayName("JpaMenuItem: exact BigDecimal(10,2) precision, availability, and is_most_loved mapping")
    void test4_menuItemEntity_bigDecimalPrecisionAndBooleanMappings() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String itemId = "item-" + UUID.randomUUID();
        BigDecimal price = new BigDecimal("24.95");

        try {
            tx.begin();
            JpaMenuItem item = new JpaMenuItem(
                    itemId,
                    "Prime Rib Roast",
                    "Slow-roasted prime rib with au jus and Yorkshire pudding",
                    price,
                    "Signature Mains",
                    "https://images.example.com/prime-rib.jpg",
                    true,
                    true
            );
            em.persist(item);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaMenuItem loaded = em.find(JpaMenuItem.class, itemId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getName()).isEqualTo("Prime Rib Roast");
            assertThat(loaded.getPrice().compareTo(price)).isEqualTo(0);
            assertThat(loaded.getPrice().setScale(2, RoundingMode.UNNECESSARY)).isEqualTo(new BigDecimal("24.95"));
            assertThat(loaded.isAvailable()).isTrue();
            assertThat(loaded.isMostLoved()).isTrue();

            // Update availability and price
            loaded.setAvailable(false);
            loaded.setMostLoved(false);
            loaded.setPrice(new BigDecimal("26.50"));
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
            JpaMenuItem verified = em.find(JpaMenuItem.class, itemId);
            assertThat(verified).isNotNull();
            assertThat(verified.isAvailable()).isFalse();
            assertThat(verified.isMostLoved()).isFalse();
            assertThat(verified.getPrice().compareTo(new BigDecimal("26.50"))).isEqualTo(0);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(5)
    @DisplayName("JpaCatalogMigration: explicit string ID preservation and Instant timestamp mapping")
    void test5_catalogMigrationEntity_explicitIdAndInstantTimestamp() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String migrationId = "2026-10-lord-gains-autumn-menu";
        Instant appliedTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaCatalogMigration migration = new JpaCatalogMigration(migrationId, appliedTime);
            em.persist(migration);
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
            JpaCatalogMigration loaded = em.find(JpaCatalogMigration.class, migrationId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getId()).isEqualTo(migrationId); // Must preserve explicit ID without UUID generation
            assertThat(loaded.getAppliedAt()).isNotNull();
            assertThat(ChronoUnit.SECONDS.between(loaded.getAppliedAt(), appliedTime)).isEqualTo(0);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(6)
    @DisplayName("JpaStoreSettings: singleton ID 'default' and exact BigDecimal(5,4) tax rate precision")
    void test6_storeSettingsEntity_singletonIdAndTaxRatePrecision() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();

        try {
            tx.begin();
            // Use existing or create singleton
            JpaStoreSettings settings = em.find(JpaStoreSettings.class, JpaStoreSettings.DEFAULT_ID);
            if (settings == null) {
                settings = new JpaStoreSettings(
                        JpaStoreSettings.DEFAULT_ID,
                        new BigDecimal("0.1300"),
                        25,
                        "Lord Gainsborough"
                );
                settings.setContactEmail("info@lordgainsborough.com");
                settings.setContactPhone("519-555-0100");
                settings.setKdsAllowedIp("192.168.1.100");
                em.persist(settings);
            } else {
                settings.setTaxRate(new BigDecimal("0.1300"));
                settings.setDefaultReadyMinutes(25);
            }
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaStoreSettings loaded = em.find(JpaStoreSettings.class, JpaStoreSettings.DEFAULT_ID);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getId()).isEqualTo("default");
            assertThat(loaded.getTaxRate().compareTo(new BigDecimal("0.1300"))).isEqualTo(0);
            assertThat(loaded.getTaxRate().setScale(4, RoundingMode.UNNECESSARY)).isEqualTo(new BigDecimal("0.1300"));
            assertThat(loaded.getDefaultReadyMinutes()).isEqualTo(25);

            // Update tax rate to 4 decimal places
            loaded.setTaxRate(new BigDecimal("0.1425"));
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
            JpaStoreSettings updated = em.find(JpaStoreSettings.class, JpaStoreSettings.DEFAULT_ID);
            assertThat(updated).isNotNull();
            assertThat(updated.getTaxRate().compareTo(new BigDecimal("0.1425"))).isEqualTo(0);
            assertThat(updated.getTaxRate().setScale(4, RoundingMode.UNNECESSARY)).isEqualTo(new BigDecimal("0.1425"));
        } finally {
            em.close();
        }
    }

    @Test
    @Order(7)
    @DisplayName("Spring Boot Minimal Context: Boots cleanly and validates all 5 JPA entities with ddl-auto=validate")
    void test7_springBootMinimalContext_bootsAndValidatesEntities() {
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

            // Verify metadata inspection across all 5 entity types
            assertThat(springEmf.getMetamodel().entity(JpaUser.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCategory.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaMenuItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCatalogMigration.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaStoreSettings.class)).isNotNull();
        });
    }
}
