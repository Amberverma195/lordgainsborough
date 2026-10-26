package com.lordgainsborough.restaurant.persistence.jpa;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCartItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderMessage;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReview;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReviewItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaReservation;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCartRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaOrderReviewRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaReservationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
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
import java.sql.PreparedStatement;
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
 * PG-010: Integration test suite for Operational Spring Data JPA repositories.
 * <p>
 * Tests {@link JpaCustomerOrderRepository}, {@link JpaCartRepository},
 * {@link JpaOrderReviewRepository}, and {@link JpaReservationRepository}
 * against a disposable PostgreSQL 17 instance initialized via Flyway V1.
 * </p>
 * <p>
 * Validates all 30 specific query, cascading deletion, relationship integrity,
 * unique constraints, lifecycle methods, and configuration isolation requirements.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OperationalJpaRepositoryIntegrationTest {

    private static final String TEST_DB_PREFIX = "op_jpa_repo_test_";
    private String testDb;
    private String testPort;
    private static String testJdbcUrl;

    private AnnotationConfigApplicationContext context;
    private EntityManagerFactory entityManagerFactory;
    private JpaCustomerOrderRepository orderRepository;
    private JpaCartRepository cartRepository;
    private JpaOrderReviewRepository reviewRepository;
    private JpaReservationRepository reservationRepository;
    private JpaUserRepository userRepository;

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
                .withFailMessage("test.postgres.port system property is required for PG-010 integration tests")
                .isNotBlank();
        return port;
    }

    @BeforeAll
    void setUpAll() throws Exception {
        testPort = getRequiredPort();
        testDb = TEST_DB_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        testJdbcUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/" + testDb + "?sslmode=disable";

        assertSafeTestEnvironment(testPort, testDb);

        // 1. Provision uniquely identified disposable test database
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE " + testDb);
        }

        // 2. Initialize Flyway schema
        Flyway flyway = Flyway.configure()
                .dataSource(testJdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
        flyway.migrate();

        // 3. Initialize Spring application context with JPA repository proxies
        context = new AnnotationConfigApplicationContext(TestJpaRepoConfig.class);
        entityManagerFactory = context.getBean(EntityManagerFactory.class);
        orderRepository = context.getBean(JpaCustomerOrderRepository.class);
        cartRepository = context.getBean(JpaCartRepository.class);
        reviewRepository = context.getBean(JpaOrderReviewRepository.class);
        reservationRepository = context.getBean(JpaReservationRepository.class);
        userRepository = context.getBean(JpaUserRepository.class);
    }

    @AfterAll
    void tearDownAll() {
        if (context != null) {
            context.close();
        }
        dropDisposableDatabaseSafely(testPort, testDb);
    }

    private void assertSafeTestEnvironment(String port, String dbName) {
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

    private void dropDisposableDatabaseSafely(String port, String dbName) {
        if (port == null || dbName == null) {
            return;
        }
        assertSafeTestEnvironment(port, dbName);

        String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid()");
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName);
        } catch (Exception e) {
            System.err.println("Warning: Failed to drop disposable test database " + dbName + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // Requirement 1, 2, 3: Context, Isolation & Metamodel Validation
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Req 1: All four repository proxies initialize in isolated Spring context")
    void test01_allFourRepositoryProxiesInitialize() {
        assertThat(orderRepository).isNotNull();
        assertThat(cartRepository).isNotNull();
        assertThat(reviewRepository).isNotNull();
        assertThat(reservationRepository).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Req 2: No MongoDB repository is registered as a JPA repository")
    void test02_noMongoRepositoryRegisteredAsJpaRepository() {
        assertThat(context.containsBean("customerOrderRepository")).isFalse();
        assertThat(context.containsBean("cartRepository")).isFalse();
        assertThat(context.containsBean("orderReviewRepository")).isFalse();
        assertThat(context.containsBean("reservationRepository")).isFalse();

        assertThat(context.containsBean("jpaCustomerOrderRepository")).isTrue();
        assertThat(context.containsBean("jpaCartRepository")).isTrue();
        assertThat(context.containsBean("jpaOrderReviewRepository")).isTrue();
        assertThat(context.containsBean("jpaReservationRepository")).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("Req 3: All 17 entities validate against Flyway V1")
    void test03_allSeventeenEntitiesValidateAgainstFlywayV1() {
        var metamodel = entityManagerFactory.getMetamodel();
        assertThat(metamodel.getEntities()).hasSizeGreaterThanOrEqualTo(17);

        List<String> expectedEntityNames = List.of(
                "JpaUser", "JpaCategory", "JpaMenuItem", "JpaCatalogMigration", "JpaStoreSettings",
                "JpaCustomerOrder", "JpaOrderItem", "JpaOrderMessage",
                "JpaCart", "JpaCartItem", "JpaOrderReview", "JpaOrderReviewItem",
                "JpaReservation", "JpaSignupOtpChallenge", "JpaPasswordResetOtpChallenge",
                "JpaSignupAbuseLimit", "JpaLoginFailureState"
        );

        for (String entityName : expectedEntityNames) {
            boolean found = metamodel.getEntities().stream()
                    .anyMatch(e -> e.getJavaType().getSimpleName().equals(entityName));
            assertThat(found)
                    .withFailMessage("Entity " + entityName + " not found in JPA metamodel")
                    .isTrue();
        }
    }

    // =========================================================================
    // Requirement 4 - 14: Customer Order Repositories & Order Queries
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("Req 4: Customer order can be saved with ordered items and messages")
    void test04_customerOrderSavedWithOrderedItemsAndMessages() {
        String orderId = "ord-req4-" + UUID.randomUUID();
        JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Alice Walker", "alice.w." + UUID.randomUUID() + "@example.com", "555-0104", "2026-11-01");
        order.setPickupTime("18:30");
        order.setSubtotal(new BigDecimal("45.00"));
        order.setTax(new BigDecimal("5.85"));
        order.setTotal(new BigDecimal("50.85"));
        order.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        JpaOrderItem item1 = new JpaOrderItem(null, "Fish & Chips", 2, new BigDecimal("15.00"), new BigDecimal("30.00"));
        JpaOrderItem item2 = new JpaOrderItem(null, "Yorkshire Pudding", 1, new BigDecimal("15.00"), new BigDecimal("15.00"));
        order.addItem(item1);
        order.addItem(item2);

        JpaOrderMessage msg1 = new JpaOrderMessage("CUSTOMER", "Alice", "Please pack condiments");
        JpaOrderMessage msg2 = new JpaOrderMessage("STAFF", "Kitchen", "Understood, added tartar sauce");
        order.addMessage(msg1);
        order.addMessage(msg2);

        JpaCustomerOrder saved = orderRepository.save(order);
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(orderId);

        // Verify round-trip in fresh persistence context
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            JpaCustomerOrder reloaded = em.find(JpaCustomerOrder.class, orderId);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getItems()).hasSize(2);
            assertThat(reloaded.getItems().get(0).getName()).isEqualTo("Fish & Chips");
            assertThat(reloaded.getItems().get(1).getName()).isEqualTo("Yorkshire Pudding");
            assertThat(reloaded.getMessages()).hasSize(2);
            assertThat(reloaded.getMessages().get(0).getText()).isEqualTo("Please pack condiments");
            assertThat(reloaded.getMessages().get(1).getText()).isEqualTo("Understood, added tartar sauce");
        } finally {
            em.close();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Req 5: Find orders by pickup date in createdAt descending order")
    void test05_findOrdersByPickupDateInCreatedAtDescendingOrder() {
        String pickupDate = "2026-11-05";
        LocalDateTime baseTime = LocalDateTime.of(2026, 11, 1, 10, 0, 0);

        String id1 = "ord-pud-1-" + UUID.randomUUID();
        String id2 = "ord-pud-2-" + UUID.randomUUID();
        String id3 = "ord-pud-3-" + UUID.randomUUID();

        JpaCustomerOrder o1 = new JpaCustomerOrder(id1, "Early Customer", "early." + UUID.randomUUID() + "@example.com", "555-0105", pickupDate);
        o1.setCreatedAt(baseTime.plusMinutes(10));

        JpaCustomerOrder o2 = new JpaCustomerOrder(id2, "Middle Customer", "mid." + UUID.randomUUID() + "@example.com", "555-0105", pickupDate);
        o2.setCreatedAt(baseTime.plusMinutes(20));

        JpaCustomerOrder o3 = new JpaCustomerOrder(id3, "Late Customer", "late." + UUID.randomUUID() + "@example.com", "555-0105", pickupDate);
        o3.setCreatedAt(baseTime.plusMinutes(30));

        orderRepository.saveAll(List.of(o1, o2, o3));

        List<JpaCustomerOrder> results = orderRepository.findByPickupDateOrderByCreatedAtDesc(pickupDate);
        assertThat(results).isNotEmpty();

        List<String> matchingIds = results.stream().map(JpaCustomerOrder::getId).filter(id -> id.equals(id1) || id.equals(id2) || id.equals(id3)).toList();
        assertThat(matchingIds).containsExactly(id3, id2, id1);
    }

    @Test
    @Order(6)
    @DisplayName("Req 6: Find orders by exact customer email")
    void test06_findOrdersByExactCustomerEmail() {
        String exactEmail = "Specific.Case.Order." + UUID.randomUUID() + "@Example.COM";
        String id = "ord-exact-" + UUID.randomUUID();

        JpaCustomerOrder order = new JpaCustomerOrder(id, "Exact Client", exactEmail, "555-0106", "2026-11-06");
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        List<JpaCustomerOrder> foundExact = orderRepository.findByCustomerEmailOrderByCreatedAtDesc(exactEmail);
        assertThat(foundExact).extracting(JpaCustomerOrder::getId).contains(id);

        List<JpaCustomerOrder> foundLower = orderRepository.findByCustomerEmailOrderByCreatedAtDesc(exactEmail.toLowerCase());
        assertThat(foundLower).extracting(JpaCustomerOrder::getId).doesNotContain(id);
    }

    @Test
    @Order(7)
    @DisplayName("Req 7: Find orders by case-insensitive customer email")
    void test07_findOrdersByCaseInsensitiveCustomerEmail() {
        String email = "Case.Insensitive." + UUID.randomUUID() + "@Domain.COM";
        String id1 = "ord-ci-1-" + UUID.randomUUID();
        String id2 = "ord-ci-2-" + UUID.randomUUID();

        JpaCustomerOrder o1 = new JpaCustomerOrder(id1, "Client 1", email, "555-0107", "2026-11-07");
        o1.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        JpaCustomerOrder o2 = new JpaCustomerOrder(id2, "Client 2", email.toLowerCase(), "555-0107", "2026-11-07");
        o2.setCreatedAt(LocalDateTime.now());

        orderRepository.saveAll(List.of(o1, o2));

        List<JpaCustomerOrder> resultsLower = orderRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(email.toLowerCase());
        assertThat(resultsLower).extracting(JpaCustomerOrder::getId).contains(id2, id1);

        List<JpaCustomerOrder> resultsUpper = orderRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(email.toUpperCase());
        assertThat(resultsUpper).extracting(JpaCustomerOrder::getId).contains(id2, id1);
    }

    @Test
    @Order(8)
    @DisplayName("Req 8: Find orders by phone")
    void test08_findOrdersByPhone() {
        String phone = "555-0188-" + UUID.randomUUID().toString().substring(0, 4);
        String id = "ord-phone-" + UUID.randomUUID();

        JpaCustomerOrder order = new JpaCustomerOrder(id, "Phone User", "phone." + UUID.randomUUID() + "@test.com", phone, "2026-11-08");
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        List<JpaCustomerOrder> results = orderRepository.findByCustomerPhoneOrderByCreatedAtDesc(phone);
        assertThat(results).extracting(JpaCustomerOrder::getId).contains(id);
    }

    @Test
    @Order(9)
    @DisplayName("Req 9: Count orders by case-insensitive email")
    void test09_countOrdersByCaseInsensitiveEmail() {
        String email = "Count.Orders." + UUID.randomUUID() + "@Test.com";
        JpaCustomerOrder o1 = new JpaCustomerOrder("ord-cnt-1-" + UUID.randomUUID(), "U1", email, "555-0109", "2026-11-09");
        o1.setCreatedAt(LocalDateTime.now());
        JpaCustomerOrder o2 = new JpaCustomerOrder("ord-cnt-2-" + UUID.randomUUID(), "U2", email.toLowerCase(), "555-0109", "2026-11-09");
        o2.setCreatedAt(LocalDateTime.now());

        orderRepository.saveAll(List.of(o1, o2));

        long count = orderRepository.countByCustomerEmailIgnoreCase(email.toLowerCase());
        assertThat(count).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @Order(10)
    @DisplayName("Req 10: Count orders by phone")
    void test10_countOrdersByPhone() {
        String phone = "555-0110-" + UUID.randomUUID().toString().substring(0, 4);
        JpaCustomerOrder o1 = new JpaCustomerOrder("ord-phcnt-1-" + UUID.randomUUID(), "U1", "p1." + UUID.randomUUID() + "@test.com", phone, "2026-11-10");
        o1.setCreatedAt(LocalDateTime.now());
        JpaCustomerOrder o2 = new JpaCustomerOrder("ord-phcnt-2-" + UUID.randomUUID(), "U2", "p2." + UUID.randomUUID() + "@test.com", phone, "2026-11-10");
        o2.setCreatedAt(LocalDateTime.now());

        orderRepository.saveAll(List.of(o1, o2));

        long count = orderRepository.countByCustomerPhone(phone);
        assertThat(count).isEqualTo(2L);
    }

    @Test
    @Order(11)
    @DisplayName("Req 11: Count orders by email and status")
    void test11_countOrdersByEmailAndStatus() {
        String email = "Status.Email." + UUID.randomUUID() + "@Test.com";
        JpaCustomerOrder o1 = new JpaCustomerOrder("ord-st-1-" + UUID.randomUUID(), "U1", email, "555-0111", "2026-11-11");
        o1.setStatus("NEW");
        o1.setCreatedAt(LocalDateTime.now());

        JpaCustomerOrder o2 = new JpaCustomerOrder("ord-st-2-" + UUID.randomUUID(), "U2", email.toLowerCase(), "555-0111", "2026-11-11");
        o2.setStatus("NO_SHOW");
        o2.setCreatedAt(LocalDateTime.now());

        orderRepository.saveAll(List.of(o1, o2));

        long countNew = orderRepository.countByCustomerEmailIgnoreCaseAndStatus(email, "NEW");
        assertThat(countNew).isEqualTo(1L);

        long countNoShow = orderRepository.countByCustomerEmailIgnoreCaseAndStatus(email, "NO_SHOW");
        assertThat(countNoShow).isEqualTo(1L);

        long countCancelled = orderRepository.countByCustomerEmailIgnoreCaseAndStatus(email, "CANCELLED");
        assertThat(countCancelled).isEqualTo(0L);
    }

    @Test
    @Order(12)
    @DisplayName("Req 12: Count orders by phone and status")
    void test12_countOrdersByPhoneAndStatus() {
        String phone = "555-0112-" + UUID.randomUUID().toString().substring(0, 4);
        JpaCustomerOrder o1 = new JpaCustomerOrder("ord-phst-1-" + UUID.randomUUID(), "U1", "phst1." + UUID.randomUUID() + "@test.com", phone, "2026-11-12");
        o1.setStatus("READY");
        o1.setCreatedAt(LocalDateTime.now());

        JpaCustomerOrder o2 = new JpaCustomerOrder("ord-phst-2-" + UUID.randomUUID(), "U2", "phst2." + UUID.randomUUID() + "@test.com", phone, "2026-11-12");
        o2.setStatus("PICKED_UP");
        o2.setCreatedAt(LocalDateTime.now());

        orderRepository.saveAll(List.of(o1, o2));

        assertThat(orderRepository.countByCustomerPhoneAndStatus(phone, "READY")).isEqualTo(1L);
        assertThat(orderRepository.countByCustomerPhoneAndStatus(phone, "PICKED_UP")).isEqualTo(1L);
        assertThat(orderRepository.countByCustomerPhoneAndStatus(phone, "NO_SHOW")).isEqualTo(0L);
    }

    @Test
    @Order(13)
    @DisplayName("Req 13: Missing orders produce empty results and zero counts")
    void test13_missingOrdersProduceEmptyResultsAndZeroCounts() {
        String nonExistentEmail = "nonexistent.order." + UUID.randomUUID() + "@example.com";
        String nonExistentPhone = "555-9999-9999";
        String nonExistentDate = "1999-01-01";

        assertThat(orderRepository.findByPickupDateOrderByCreatedAtDesc(nonExistentDate)).isEmpty();
        assertThat(orderRepository.findByCustomerEmailOrderByCreatedAtDesc(nonExistentEmail)).isEmpty();
        assertThat(orderRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(nonExistentEmail)).isEmpty();
        assertThat(orderRepository.findByCustomerPhoneOrderByCreatedAtDesc(nonExistentPhone)).isEmpty();
        assertThat(orderRepository.countByCustomerEmailIgnoreCase(nonExistentEmail)).isEqualTo(0L);
        assertThat(orderRepository.countByCustomerPhone(nonExistentPhone)).isEqualTo(0L);
        assertThat(orderRepository.countByCustomerEmailIgnoreCaseAndStatus(nonExistentEmail, "NEW")).isEqualTo(0L);
        assertThat(orderRepository.countByCustomerPhoneAndStatus(nonExistentPhone, "NEW")).isEqualTo(0L);
    }

    @Test
    @Order(14)
    @DisplayName("Req 14: Order children persist and reload correctly through repository save")
    void test14_orderChildrenPersistAndReloadCorrectlyThroughRepositorySave() throws Exception {
        String orderId = "ord-children-" + UUID.randomUUID();
        JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Child Test", "child." + UUID.randomUUID() + "@test.com", "555-0114", "2026-11-14");
        order.setCreatedAt(LocalDateTime.now());

        JpaOrderItem item = new JpaOrderItem(null, "Prime Rib", 1, new BigDecimal("34.50"), new BigDecimal("34.50"));
        order.addItem(item);
        JpaOrderMessage msg = new JpaOrderMessage("CUSTOMER", "Client", "Rare please");
        order.addMessage(msg);

        orderRepository.save(order);

        // Verify direct relational insertion via JDBC
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres")) {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM order_items WHERE order_id = ?")) {
                stmt.setString(1, orderId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM order_messages WHERE order_id = ?")) {
                stmt.setString(1, orderId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }

        // Verify repository round-trip using targeted entity graph queries (safe detached navigation, no N+1)
        JpaCustomerOrder reloadedWithItems = orderRepository.findWithItemsById(orderId).orElseThrow();
        assertThat(reloadedWithItems.getItems()).hasSize(1);
        assertThat(reloadedWithItems.getItems().get(0).getName()).isEqualTo("Prime Rib");

        JpaCustomerOrder reloadedWithMessages = orderRepository.findWithMessagesById(orderId).orElseThrow();
        assertThat(reloadedWithMessages.getMessages()).hasSize(1);
        assertThat(reloadedWithMessages.getMessages().get(0).getText()).isEqualTo("Rare please");

        // Also verify standard lazy loading within active EntityManager persistence session
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            JpaCustomerOrder emLoaded = em.find(JpaCustomerOrder.class, orderId);
            assertThat(emLoaded).isNotNull();
            assertThat(emLoaded.getItems()).hasSize(1);
            assertThat(emLoaded.getMessages()).hasSize(1);
        } finally {
            em.close();
        }
    }

    // =========================================================================
    // Requirement 15 - 22: Cart Repositories, Ownership, Cascade & TTL
    // =========================================================================

    @Test
    @Order(15)
    @DisplayName("Req 15: Find cart by associated user ID")
    void test15_findCartByAssociatedUserId() {
        String userId = "usr-cart15-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Cart Owner", "cartowner." + UUID.randomUUID() + "@example.com", "555-0115", "MEMBER");
        userRepository.save(user);

        String cartId = "cart-15-" + UUID.randomUUID();
        JpaCart cart = new JpaCart(cartId, user);
        cartRepository.save(cart);

        Optional<JpaCart> foundJpql = cartRepository.findByUserId(userId);
        assertThat(foundJpql).isPresent();
        assertThat(foundJpql.get().getId()).isEqualTo(cartId);

        Optional<JpaCart> foundDerived = cartRepository.findByUser_Id(userId);
        assertThat(foundDerived).isPresent();
        assertThat(foundDerived.get().getId()).isEqualTo(cartId);
    }

    @Test
    @Order(16)
    @DisplayName("Req 16: User without cart returns Optional.empty()")
    void test16_userWithoutCartReturnsOptionalEmpty() {
        String userId = "usr-nocart-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "No Cart User", "nocart." + UUID.randomUUID() + "@example.com", "555-0116", "MEMBER");
        userRepository.save(user);

        assertThat(cartRepository.findByUserId(userId)).isEmpty();
        assertThat(cartRepository.findByUser_Id(userId)).isEmpty();
    }

    @Test
    @Order(17)
    @DisplayName("Req 17: One-cart-per-user uniqueness is enforced")
    void test17_oneCartPerUserUniquenessEnforced() {
        String userId = "usr-dupcart-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Dup Cart User", "dupcart." + UUID.randomUUID() + "@example.com", "555-0117", "MEMBER");
        userRepository.save(user);

        JpaCart cart1 = new JpaCart("cart-dup-1-" + UUID.randomUUID(), user);
        cartRepository.saveAndFlush(cart1);

        JpaCart cart2 = new JpaCart("cart-dup-2-" + UUID.randomUUID(), user);
        assertThatThrownBy(() -> cartRepository.saveAndFlush(cart2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(18)
    @DisplayName("Req 18: Delete cart by associated user ID via JPQL and derived methods")
    void test18_deleteCartByAssociatedUserId() {
        // 1. Verify deleteByUserId (explicit JPQL)
        String userId1 = "usr-delcart1-" + UUID.randomUUID();
        JpaUser user1 = new JpaUser(userId1, "Del Cart User 1", "delcart1." + UUID.randomUUID() + "@example.com", "555-0118", "MEMBER");
        userRepository.save(user1);

        String cartId1 = "cart-del1-" + UUID.randomUUID();
        JpaCart cart1 = new JpaCart(cartId1, user1);
        cartRepository.saveAndFlush(cart1);

        assertThat(cartRepository.findByUserId(userId1)).isPresent();
        cartRepository.deleteByUserId(userId1);
        assertThat(cartRepository.findByUserId(userId1)).isEmpty();

        // 2. Verify deleteByUser_Id (Spring Data derived traversal delete)
        String userId2 = "usr-delcart2-" + UUID.randomUUID();
        JpaUser user2 = new JpaUser(userId2, "Del Cart User 2", "delcart2." + UUID.randomUUID() + "@example.com", "555-0119", "MEMBER");
        userRepository.save(user2);

        String cartId2 = "cart-del2-" + UUID.randomUUID();
        JpaCart cart2 = new JpaCart(cartId2, user2);
        cartRepository.saveAndFlush(cart2);

        assertThat(cartRepository.findByUser_Id(userId2)).isPresent();
        cartRepository.deleteByUser_Id(userId2);
        assertThat(cartRepository.findByUser_Id(userId2)).isEmpty();
    }

    @Test
    @Order(19)
    @DisplayName("Req 19: Cart deletion removes all child cart items")
    void test19_cartDeletionRemovesAllChildCartItems() throws Exception {
        String userId = "usr-cascade-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Cascade User", "cascade." + UUID.randomUUID() + "@example.com", "555-0119", "MEMBER");
        userRepository.save(user);

        String cartId = "cart-cascade-" + UUID.randomUUID();
        JpaCart cart = new JpaCart(cartId, user);
        cart.addItem(new JpaCartItem(null, "Burger", "Mains", new BigDecimal("18.00"), "Mains", null, 2));
        cart.addItem(new JpaCartItem(null, "Fries", "Sides", new BigDecimal("6.00"), "Sides", null, 1));
        cartRepository.saveAndFlush(cart);

        // Verify items inserted in DB
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM cart_items WHERE cart_id = ?")) {
            stmt.setString(1, cartId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }

        // Delete cart by user ID
        cartRepository.deleteByUserId(userId);

        // Verify child cart items are removed via CASCADE
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM cart_items WHERE cart_id = ?")) {
            stmt.setString(1, cartId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(20)
    @DisplayName("Req 20: Cart deletion does not remove user")
    void test20_cartDeletionDoesNotRemoveUser() {
        String userId = "usr-survive-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Surviving User", "survive." + UUID.randomUUID() + "@example.com", "555-0120", "MEMBER");
        userRepository.save(user);

        JpaCart cart = new JpaCart("cart-survive-" + UUID.randomUUID(), user);
        cartRepository.saveAndFlush(cart);

        cartRepository.deleteByUserId(userId);

        // User must remain intact
        Optional<JpaUser> survivingUser = userRepository.findById(userId);
        assertThat(survivingUser).isPresent();
        assertThat(survivingUser.get().getName()).isEqualTo("Surviving User");
    }

    @Test
    @Order(21)
    @DisplayName("Req 21: Cart item list order survives repository round-trip")
    void test21_cartItemListOrderSurvivesRepositoryRoundTrip() {
        String userId = "usr-cartorder-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Order Test", "cartorder." + UUID.randomUUID() + "@example.com", "555-0121", "MEMBER");
        userRepository.save(user);

        String cartId = "cart-order-" + UUID.randomUUID();
        JpaCart cart = new JpaCart(cartId, user);
        cart.addItem(new JpaCartItem(null, "First Item", "Desc", new BigDecimal("10.00"), "Cat", null, 1));
        cart.addItem(new JpaCartItem(null, "Second Item", "Desc", new BigDecimal("20.00"), "Cat", null, 2));
        cart.addItem(new JpaCartItem(null, "Third Item", "Desc", new BigDecimal("30.00"), "Cat", null, 3));
        cartRepository.saveAndFlush(cart);

        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            JpaCart reloaded = em.find(JpaCart.class, cartId);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getItems()).hasSize(3);
            assertThat(reloaded.getItems().get(0).getName()).isEqualTo("First Item");
            assertThat(reloaded.getItems().get(1).getName()).isEqualTo("Second Item");
            assertThat(reloaded.getItems().get(2).getName()).isEqualTo("Third Item");
        } finally {
            em.close();
        }
    }

    @Test
    @Order(22)
    @DisplayName("Req 22: Cart updatedAt persists and item modifications refresh it through approved helpers")
    void test22_cartUpdatedAtPersistsAndItemModificationsRefreshItThroughApprovedHelpers() {
        String userId = "usr-ttl-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "TTL User", "ttl." + UUID.randomUUID() + "@example.com", "555-0122", "MEMBER");
        userRepository.save(user);

        String cartId = "cart-ttl-" + UUID.randomUUID();
        Instant originalInstant = Instant.now().minus(8, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        JpaCart cart = new JpaCart(cartId, user);
        cart.touch(originalInstant);
        cartRepository.saveAndFlush(cart);

        JpaCart reloaded = cartRepository.findById(cartId).orElseThrow();
        assertThat(reloaded.isExpired(Instant.now())).isTrue();

        // Advance updatedAt using touch() helper
        Instant refreshedInstant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        reloaded.touch(refreshedInstant);
        cartRepository.saveAndFlush(reloaded);

        JpaCart freshReload = cartRepository.findById(cartId).orElseThrow();
        assertThat(freshReload.isExpired(Instant.now())).isFalse();
        assertThat(freshReload.getUpdatedAt()).isAfterOrEqualTo(refreshedInstant);

        // Verify exact 7-day expiration boundary semantics (inclusive at 7-day mark, fail-closed on null)
        Instant fixedUpdated = Instant.parse("2026-10-01T12:00:00Z");
        freshReload.touch(fixedUpdated);
        Instant exactBoundary = fixedUpdated.plusSeconds(7L * 24 * 60 * 60);

        // 1 millisecond before boundary: unexpired
        assertThat(freshReload.isExpired(exactBoundary.minusMillis(1))).isFalse();

        // Exact boundary instant: expired (inclusive boundary)
        assertThat(freshReload.isExpired(exactBoundary)).isTrue();

        // 1 millisecond after boundary: expired
        assertThat(freshReload.isExpired(exactBoundary.plusMillis(1))).isTrue();

        // Null updatedAt: fail-closed (expired)
        freshReload.setUpdatedAt(null);
        assertThat(freshReload.isExpired()).isTrue();
        assertThat(freshReload.isExpired(exactBoundary)).isTrue();
    }

    // =========================================================================
    // Requirement 23 - 26: Order Reviews, Lookup, Cascade & Uniqueness
    // =========================================================================

    @Test
    @Order(23)
    @DisplayName("Req 23: Review lookup by order ID works")
    void test23_reviewLookupByOrderIdWorks() {
        String orderId = "ord-rev23-" + UUID.randomUUID();
        JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Rev Cust", "rev." + UUID.randomUUID() + "@test.com", "555-0123", "2026-11-23");
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        String reviewId = "rev-23-" + UUID.randomUUID();
        JpaOrderReview review = new JpaOrderReview(reviewId, order, "Rev Cust", "rev@test.com", "555-0123");
        reviewRepository.saveAndFlush(review);

        List<JpaOrderReview> foundJpql = reviewRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        assertThat(foundJpql).hasSize(1);
        assertThat(foundJpql.get(0).getId()).isEqualTo(reviewId);

        List<JpaOrderReview> foundDerived = reviewRepository.findByOrder_IdOrderByCreatedAtDesc(orderId);
        assertThat(foundDerived).hasSize(1);
        assertThat(foundDerived.get(0).getId()).isEqualTo(reviewId);
    }

    @Test
    @Order(24)
    @DisplayName("Req 24: Review existence query works")
    void test24_reviewExistenceQueryWorks() {
        String orderId = "ord-exist24-" + UUID.randomUUID();
        JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Exist Cust", "exist." + UUID.randomUUID() + "@test.com", "555-0124", "2026-11-24");
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        assertThat(reviewRepository.existsByOrderId(orderId)).isFalse();
        assertThat(reviewRepository.existsByOrder_Id(orderId)).isFalse();

        JpaOrderReview review = new JpaOrderReview("rev-exist-" + UUID.randomUUID(), order, "Exist Cust", "exist@test.com", "555-0124");
        reviewRepository.saveAndFlush(review);

        assertThat(reviewRepository.existsByOrderId(orderId)).isTrue();
        assertThat(reviewRepository.existsByOrder_Id(orderId)).isTrue();
    }

    @Test
    @Order(25)
    @DisplayName("Req 25: Review deletion removes child review items but preserves underlying order")
    void test25_reviewDeletionRemovesChildReviewItemsButPreservesUnderlyingOrder() throws Exception {
        String orderId = "ord-delrev-" + UUID.randomUUID();
        JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Order Parent", "orderp." + UUID.randomUUID() + "@test.com", "555-0125", "2026-11-25");
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        String reviewId = "rev-del-" + UUID.randomUUID();
        JpaOrderReview review = new JpaOrderReview(reviewId, order, "Order Parent", "orderp@test.com", "555-0125");
        JpaOrderReviewItem item1 = new JpaOrderReviewItem(null, "Steak", 5, "Exquisite flavor");
        JpaOrderReviewItem item2 = new JpaOrderReviewItem(null, "Wine", 4, "Good vintage");
        review.addItem(item1);
        review.addItem(item2);
        reviewRepository.saveAndFlush(review);

        // Verify review items exist in DB
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM order_review_items WHERE review_id = ?")) {
            stmt.setString(1, reviewId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }

        // Delete review
        reviewRepository.deleteById(reviewId);

        // Verify review items removed
        try (Connection conn = DriverManager.getConnection(testJdbcUrl, "postgres", "postgres");
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM order_review_items WHERE review_id = ?")) {
            stmt.setString(1, reviewId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }

        // Verify underlying order is preserved
        assertThat(orderRepository.findById(orderId)).isPresent();
    }

    @Test
    @Order(26)
    @DisplayName("Req 26: One-review-per-order constraint is enforced")
    void test26_oneReviewPerOrderConstraintEnforced() {
        String orderId = "ord-uqrev-" + UUID.randomUUID();
        JpaCustomerOrder order = new JpaCustomerOrder(orderId, "One Review Cust", "uqrev." + UUID.randomUUID() + "@test.com", "555-0126", "2026-11-26");
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        JpaOrderReview rev1 = new JpaOrderReview("rev-uq-1-" + UUID.randomUUID(), order, "Cust", "c@test.com", "555-0126");
        reviewRepository.saveAndFlush(rev1);

        JpaOrderReview rev2 = new JpaOrderReview("rev-uq-2-" + UUID.randomUUID(), order, "Cust", "c@test.com", "555-0126");
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(rev2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // Requirement 27 - 29: Reservations CRUD & Validation
    // =========================================================================

    @Test
    @Order(27)
    @DisplayName("Req 27: Guest reservation CRUD works")
    void test27_guestReservationCrudWorks() {
        String resId = "res-guest-" + UUID.randomUUID();
        JpaReservation reservation = new JpaReservation(resId, "Guest Booker", "guest.booker." + UUID.randomUUID() + "@example.com", "555-0127", "2026-12-25", "19:00", 4);
        reservation.setSpecialRequests("Window booth please");

        JpaReservation saved = reservationRepository.save(reservation);
        assertThat(saved.getId()).isEqualTo(resId);

        JpaReservation loaded = reservationRepository.findById(resId).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Guest Booker");
        assertThat(loaded.getSpecialRequests()).isEqualTo("Window booth please");

        // Update status
        loaded.setStatus("CONFIRMED");
        reservationRepository.save(loaded);

        JpaReservation updated = reservationRepository.findById(resId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("CONFIRMED");

        // Delete
        reservationRepository.deleteById(resId);
        assertThat(reservationRepository.findById(resId)).isEmpty();
    }

    @Test
    @Order(28)
    @DisplayName("Req 28: Reservation date/time strings round-trip exactly")
    void test28_reservationDateTimeStringsRoundTripExactly() {
        String resId = "res-dt-" + UUID.randomUUID();
        String dateStr = "2026-11-30";
        String timeStr = "20:45";

        JpaReservation res = new JpaReservation(resId, "Timer User", "time." + UUID.randomUUID() + "@example.com", "555-0128", dateStr, timeStr, 2);
        reservationRepository.save(res);

        JpaReservation loaded = reservationRepository.findById(resId).orElseThrow();
        assertThat(loaded.getDate()).isEqualTo(dateStr);
        assertThat(loaded.getTime()).isEqualTo(timeStr);
    }

    @Test
    @Order(29)
    @DisplayName("Req 29: Reservation supplementary finders (byDate, byEmailIgnoreCase, byStatus)")
    void test29_reservationSupplementaryFinders_byDateByEmailAndByStatus() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        String targetDate = "2026-12-25";
        String otherDate = "2026-12-26";
        String targetEmail = "Guest." + testSuffix + "@Example.COM";

        // 1. Test findByDateOrderByTimeAsc
        JpaReservation res1 = new JpaReservation("res-sup-1-" + testSuffix, "Guest 1", targetEmail, "555-0201", targetDate, "20:00", 2);
        JpaReservation res2 = new JpaReservation("res-sup-2-" + testSuffix, "Guest 2", "other1." + testSuffix + "@test.com", "555-0202", targetDate, "17:30", 4);
        JpaReservation res3 = new JpaReservation("res-sup-3-" + testSuffix, "Guest 3", targetEmail, "555-0203", targetDate, "19:15", 3);
        JpaReservation res4 = new JpaReservation("res-sup-4-" + testSuffix, "Guest 4", "other2." + testSuffix + "@test.com", "555-0204", otherDate, "18:00", 2);

        res1.setStatus("CONFIRMED");
        res2.setStatus("PENDING");
        res3.setStatus("CONFIRMED");
        res4.setStatus("CANCELLED");

        LocalDateTime baseTime = LocalDateTime.now().minusMinutes(10);
        res1.setCreatedAt(baseTime);
        res3.setCreatedAt(baseTime.plusMinutes(5));

        reservationRepository.saveAllAndFlush(List.of(res1, res2, res3, res4));

        List<JpaReservation> byDate = reservationRepository.findByDateOrderByTimeAsc(targetDate);
        List<String> idsForDate = byDate.stream().map(JpaReservation::getId).filter(id -> id.contains(testSuffix)).toList();
        assertThat(idsForDate).containsExactly("res-sup-2-" + testSuffix, "res-sup-3-" + testSuffix, "res-sup-1-" + testSuffix);

        // 2. Test findByEmailIgnoreCaseOrderByCreatedAtDesc
        List<JpaReservation> byEmail = reservationRepository.findByEmailIgnoreCaseOrderByCreatedAtDesc("guest." + testSuffix + "@example.com");
        List<String> idsForEmail = byEmail.stream().map(JpaReservation::getId).filter(id -> id.contains(testSuffix)).toList();
        assertThat(idsForEmail).containsExactly("res-sup-3-" + testSuffix, "res-sup-1-" + testSuffix);

        // 3. Test findByStatus
        List<JpaReservation> confirmed = reservationRepository.findByStatus("CONFIRMED");
        List<String> idsConfirmed = confirmed.stream().map(JpaReservation::getId).filter(id -> id.contains(testSuffix)).toList();
        assertThat(idsConfirmed).containsExactlyInAnyOrder("res-sup-1-" + testSuffix, "res-sup-3-" + testSuffix);

        List<JpaReservation> pending = reservationRepository.findByStatus("PENDING");
        List<String> idsPending = pending.stream().map(JpaReservation::getId).filter(id -> id.contains(testSuffix)).toList();
        assertThat(idsPending).containsExactly("res-sup-2-" + testSuffix);
    }

    @Test
    @Order(30)
    @DisplayName("Req 30: Invalid reservation party size is rejected by PostgreSQL")
    void test30_invalidReservationPartySizeRejectedByPostgreSQL() {
        String resIdZero = "res-invalid-0-" + UUID.randomUUID();
        JpaReservation resZero = new JpaReservation(resIdZero, "Zero Party", "zero." + UUID.randomUUID() + "@example.com", "555-0129", "2026-12-01", "18:00", 0);
        assertThatThrownBy(() -> reservationRepository.saveAndFlush(resZero))
                .isInstanceOf(DataIntegrityViolationException.class);

        String resIdNegative = "res-invalid-neg-" + UUID.randomUUID();
        JpaReservation resNeg = new JpaReservation(resIdNegative, "Neg Party", "neg." + UUID.randomUUID() + "@example.com", "555-0129", "2026-12-01", "18:00", -2);
        assertThatThrownBy(() -> reservationRepository.saveAndFlush(resNeg))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // Requirement 31: Isolation and No Bean Collisions
    // =========================================================================

    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    static class DefaultProfileTestConfig {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DefaultProfileIsolationConfig {}

    @Test
    @Order(31)
    @DisplayName("Req 31: Default MongoDB profile isolation and no repository bean collisions")
    void test31_noRepositoryBeanNameCollisionsOrUnintendedMongoDBProfileActivation() {
        // 1. Verify JPA repositories have unique bean names in active JPA context
        assertThat(context.containsBean("jpaCustomerOrderRepository")).isTrue();
        assertThat(context.containsBean("jpaCartRepository")).isTrue();
        assertThat(context.containsBean("jpaOrderReviewRepository")).isTrue();
        assertThat(context.containsBean("jpaReservationRepository")).isTrue();

        // 2. Verify MongoDB repository beans do not collide
        assertThat(context.containsBean("customerOrderRepository")).isFalse();
        assertThat(context.containsBean("cartRepository")).isFalse();
        assertThat(context.containsBean("orderReviewRepository")).isFalse();
        assertThat(context.containsBean("reservationRepository")).isFalse();

        // 3. Meaningful repository auto-configuration test:
        // When DataJpaRepositoriesAutoConfiguration executes against the repository package,
        // verify that without DataSource / EntityManagerFactory beans (as in the default profile),
        // it cleanly backs off and activates ZERO JPA repository beans.
        new ApplicationContextRunner()
                .withUserConfiguration(DefaultProfileTestConfig.class)
                .withConfiguration(AutoConfigurations.of(DataJpaRepositoriesAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(JpaCustomerOrderRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCartRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaOrderReviewRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaReservationRepository.class);
                    assertThat(ctx.getBeanNamesForType(JpaCustomerOrderRepository.class)).isEmpty();
                    assertThat(ctx.getBeanNamesForType(JpaCartRepository.class)).isEmpty();
                    assertThat(ctx.getBeanNamesForType(JpaOrderReviewRepository.class)).isEmpty();
                    assertThat(ctx.getBeanNamesForType(JpaReservationRepository.class)).isEmpty();
                });

        // 4. Meaningful DataSource auto-configuration test:
        // Verify that DataSourceAutoConfiguration without PostgreSQL connection settings fails,
        // proving that unexcluded relational auto-configuration cannot start in the default environment.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                });

        // 5. Meaningful Spring Boot @EnableAutoConfiguration test using actual application settings:
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
                    assertThat(ctx).doesNotHaveBean(JpaCustomerOrderRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaCartRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaOrderReviewRepository.class);
                    assertThat(ctx).doesNotHaveBean(JpaReservationRepository.class);
                });
    }
}
