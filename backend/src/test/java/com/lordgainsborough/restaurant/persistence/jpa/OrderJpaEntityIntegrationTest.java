package com.lordgainsborough.restaurant.persistence.jpa;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderMessage;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrderJpaEntityIntegrationTest {

    private static final String TEST_DB = "order_jpa_test";
    private String testPort;
    private String jdbcUrl;
    private PGSimpleDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean emfBean;
    private EntityManagerFactory emf;

    @Configuration
    @EntityScan(basePackageClasses = {JpaCustomerOrder.class, JpaUser.class})
    static class TestEntityScanConfig {}

    private String getRequiredPort() {
        String port = System.getProperty("test.postgres.port");
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-005 PostgreSQL integration tests")
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

    @Test
    @Order(1)
    @DisplayName("Hibernate metadata validation: All order entities pass ddl-auto=validate against V1 schema")
    void test1_hibernateMetadataValidation_succeedsAgainstV1Schema() {
        assertThat(emf.isOpen()).isTrue();
        assertThat(emf.getMetamodel().entity(JpaCustomerOrder.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderMessage.class)).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Guest Order Persistence: Order can be inserted without associated registered user")
    void test2_guestOrder_insertedWithoutRegisteredUser() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(
                    orderId,
                    "Guest Customer",
                    "guest@example.com",
                    "519-555-1234",
                    "2026-10-31"
            );
            order.setPickupTime("18:30");
            order.setSpecialInstructions("Please pack extra napkins.");
            order.setSubtotal(new BigDecimal("35.50"));
            order.setTax(new BigDecimal("4.62"));
            order.setTotal(new BigDecimal("40.12"));
            order.setTrackingTokenHash("hash_abc123");

            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify guest order loaded in fresh session
        em = emf.createEntityManager();
        try {
            JpaCustomerOrder loaded = em.find(JpaCustomerOrder.class, orderId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getCustomerName()).isEqualTo("Guest Customer");
            assertThat(loaded.getCustomerEmail()).isEqualTo("guest@example.com");
            assertThat(loaded.getTrackingTokenHash()).isEqualTo("hash_abc123");
            assertThat(loaded.getStatus()).isEqualTo("NEW");
            assertThat(loaded.getAverageReadyMinutes()).isEqualTo(25);
            assertThat(loaded.getCreatedAt()).isNotNull();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Cascade Persistence & Ordered Collections: Order items and messages persist and maintain order")
    void test3_cascadePersistenceAndOrdering_itemsAndMessages() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-cascade-" + UUID.randomUUID();

        String item1Id = "item-1-" + UUID.randomUUID();
        String item2Id = "item-2-" + UUID.randomUUID();
        String item3Id = "item-3-" + UUID.randomUUID();

        String msg1Id = "msg-1-" + UUID.randomUUID();
        String msg2Id = "msg-2-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(
                    orderId,
                    "Alice Walker",
                    "alice@example.com",
                    "519-555-2233",
                    "2026-11-01"
            );
            order.setSubtotal(new BigDecimal("65.00"));
            order.setTax(new BigDecimal("8.45"));
            order.setTotal(new BigDecimal("73.45"));

            // Add 3 order items in specific order
            JpaOrderItem item1 = new JpaOrderItem(item1Id, "menu-1", "Crispy Calamari", 1, new BigDecimal("15.00"), new BigDecimal("15.00"));
            JpaOrderItem item2 = new JpaOrderItem(item2Id, "menu-2", "Prime Rib Steak", 1, new BigDecimal("35.00"), new BigDecimal("35.00"));
            JpaOrderItem item3 = new JpaOrderItem(item3Id, "menu-3", "Chocolate Lava Cake", 1, new BigDecimal("15.00"), new BigDecimal("15.00"));

            order.addItem(item1);
            order.addItem(item2);
            order.addItem(item3);

            // Add 2 order messages
            JpaOrderMessage msg1 = new JpaOrderMessage(msg1Id, "CUSTOMER", "Alice Walker", "Could you prepare it medium-rare?");
            JpaOrderMessage msg2 = new JpaOrderMessage(msg2Id, "KITCHEN", "Chef Pierre", "Understood, will prepare medium-rare.");

            order.addMessage(msg1);
            order.addMessage(msg2);

            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify ordering in fresh session
        em = emf.createEntityManager();
        try {
            JpaCustomerOrder reloaded = em.find(JpaCustomerOrder.class, orderId);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getItems()).hasSize(3);
            assertThat(reloaded.getItems().get(0).getId()).isEqualTo(item1Id);
            assertThat(reloaded.getItems().get(0).getName()).isEqualTo("Crispy Calamari");
            assertThat(reloaded.getItems().get(1).getId()).isEqualTo(item2Id);
            assertThat(reloaded.getItems().get(1).getName()).isEqualTo("Prime Rib Steak");
            assertThat(reloaded.getItems().get(2).getId()).isEqualTo(item3Id);
            assertThat(reloaded.getItems().get(2).getName()).isEqualTo("Chocolate Lava Cake");

            assertThat(reloaded.getMessages()).hasSize(2);
            assertThat(reloaded.getMessages().get(0).getId()).isEqualTo(msg1Id);
            assertThat(reloaded.getMessages().get(0).getText()).isEqualTo("Could you prepare it medium-rare?");
            assertThat(reloaded.getMessages().get(1).getId()).isEqualTo(msg2Id);
            assertThat(reloaded.getMessages().get(1).getText()).isEqualTo("Understood, will prepare medium-rare.");
        } finally {
            em.close();
        }

        // Verify underlying database item_index values via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, item_index, name FROM order_items WHERE order_id = '" + orderId + "' ORDER BY item_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(item1Id);
                assertThat(rs.getInt("item_index")).isEqualTo(0);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(item2Id);
                assertThat(rs.getInt("item_index")).isEqualTo(1);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(item3Id);
                assertThat(rs.getInt("item_index")).isEqualTo(2);

                assertThat(rs.next()).isFalse();
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, item_index, text FROM order_messages WHERE order_id = '" + orderId + "' ORDER BY item_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(msg1Id);
                assertThat(rs.getInt("item_index")).isEqualTo(0);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(msg2Id);
                assertThat(rs.getInt("item_index")).isEqualTo(1);

                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("Append Message: Adding a message to an existing order succeeds and receives next item_index")
    void test4_appendMessage_toExistingOrder() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-msg-" + UUID.randomUUID();

        String msg1Id = "msg-bob-1-" + UUID.randomUUID();
        String msg2Id = "msg-bob-2-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Bob", "bob@example.com", "519-555-3344", "2026-11-02");
            order.addMessage(new JpaOrderMessage(msg1Id, "CUSTOMER", "Bob", "First message"));
            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Add second message
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            order.addMessage(new JpaOrderMessage(msg2Id, "KITCHEN", "Kitchen Staff", "Second message"));
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify both messages via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM order_messages WHERE order_id = '" + orderId + "' ORDER BY item_index")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(msg1Id);
            assertThat(rs.getInt("item_index")).isEqualTo(0);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(msg2Id);
            assertThat(rs.getInt("item_index")).isEqualTo(1);

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Orphan Removal: Removing an order item deletes the orphaned row from database")
    void test5_orphanRemoval_removesItemRow() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-orphan-" + UUID.randomUUID();
        String itemToRemoveId = "item-remove-" + UUID.randomUUID();
        String itemToKeepId = "item-keep-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Charlie", "charlie@example.com", "519-555-5566", "2026-11-03");
            order.addItem(new JpaOrderItem(itemToRemoveId, "m1", "Soup", 1, new BigDecimal("8.00"), new BigDecimal("8.00")));
            order.addItem(new JpaOrderItem(itemToKeepId, "m2", "Salad", 1, new BigDecimal("12.00"), new BigDecimal("12.00")));
            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Remove itemToRemove
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            JpaOrderItem itemToRemove = order.getItems().get(0);
            assertThat(itemToRemove.getId()).isEqualTo(itemToRemoveId);
            order.removeItem(itemToRemove);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify orphan was deleted from database
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_items WHERE id = '" + itemToRemoveId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }

            try (ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM order_items WHERE order_id = '" + orderId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(itemToKeepId);
                assertThat(rs.getInt("item_index")).isEqualTo(0); // re-indexed to 0
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("Reordering Items: Reordering items updates item_index in PostgreSQL")
    void test6_reorderItems_updatesItemIndex() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-reorder-" + UUID.randomUUID();
        String firstItemId = "item-reorder-A-" + UUID.randomUUID();
        String secondItemId = "item-reorder-B-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Dave", "dave@example.com", "519-555-7788", "2026-11-04");
            order.addItem(new JpaOrderItem(firstItemId, "m1", "First Choice", 1, new BigDecimal("10.00"), new BigDecimal("10.00")));
            order.addItem(new JpaOrderItem(secondItemId, "m2", "Second Choice", 1, new BigDecimal("20.00"), new BigDecimal("20.00")));
            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Swap order in list
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            List<JpaOrderItem> items = order.getItems();
            Collections.swap(items, 0, 1);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify swapped index in database via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM order_items WHERE order_id = '" + orderId + "' ORDER BY item_index")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(secondItemId);
            assertThat(rs.getInt("item_index")).isEqualTo(0);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(firstItemId);
            assertThat(rs.getInt("item_index")).isEqualTo(1);

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @Order(7)
    @DisplayName("Status & Timestamp Updates: Updating order lifecycle timestamps persists correctly")
    void test7_statusAndTimestamps_updatePersists() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-status-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Emma", "emma@example.com", "519-555-9900", "2026-11-05");
            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            order.setStatus("ACCEPTED");
            order.setAcceptedAt(now);
            order.setReadyAt(now.plusMinutes(25));
            order.setPickedUpAt(now.plusMinutes(35));
            order.setReviewed(true);
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
            JpaCustomerOrder verified = em.find(JpaCustomerOrder.class, orderId);
            assertThat(verified.getStatus()).isEqualTo("ACCEPTED");
            assertThat(verified.getAcceptedAt()).isEqualTo(now);
            assertThat(verified.getReadyAt()).isEqualTo(now.plusMinutes(25));
            assertThat(verified.getPickedUpAt()).isEqualTo(now.plusMinutes(35));
            assertThat(verified.isReviewed()).isTrue();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(8)
    @DisplayName("Explicitly Assigned Child IDs: Explicit IDs are preserved without regeneration")
    void test8_explicitChildIds_preservedWithoutRegeneration() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-explicit-" + UUID.randomUUID();
        String customItemId = "custom-item-uuid-" + UUID.randomUUID();
        String customMsgId = "custom-msg-uuid-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Frank", "frank@example.com", "519-555-0011", "2026-11-06");
            order.addItem(new JpaOrderItem(customItemId, "m1", "Fish & Chips", 1, new BigDecimal("18.50"), new BigDecimal("18.50")));
            order.addMessage(new JpaOrderMessage(customMsgId, "CUSTOMER", "Frank", "Extra tartar sauce please."));
            em.persist(order);
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
            JpaCustomerOrder reloaded = em.find(JpaCustomerOrder.class, orderId);
            assertThat(reloaded.getItems().get(0).getId()).isEqualTo(customItemId);
            assertThat(reloaded.getMessages().get(0).getId()).isEqualTo(customMsgId);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(9)
    @DisplayName("BigDecimal Precision: Subtotal, tax, total, unitPrice and item total round-trip exactly")
    void test9_bigDecimalPrecision_roundTripExact() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-money-" + UUID.randomUUID();

        BigDecimal unitPrice = new BigDecimal("17.49");
        BigDecimal itemTotal = new BigDecimal("34.98");
        BigDecimal subtotal = new BigDecimal("34.98");
        BigDecimal tax = new BigDecimal("4.55");
        BigDecimal total = new BigDecimal("39.53");

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Grace", "grace@example.com", "519-555-1122", "2026-11-07");
            order.setSubtotal(subtotal);
            order.setTax(tax);
            order.setTotal(total);

            order.addItem(new JpaOrderItem("m1", "Shepherd's Pie", 2, unitPrice, itemTotal));
            em.persist(order);
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
            JpaCustomerOrder loaded = em.find(JpaCustomerOrder.class, orderId);
            assertThat(loaded.getSubtotal().setScale(2, RoundingMode.UNNECESSARY)).isEqualTo(subtotal);
            assertThat(loaded.getTax().setScale(2, RoundingMode.UNNECESSARY)).isEqualTo(tax);
            assertThat(loaded.getTotal().setScale(2, RoundingMode.UNNECESSARY)).isEqualTo(total);

            JpaOrderItem item = loaded.getItems().get(0);
            assertThat(item.getUnitPrice().setScale(2, RoundingMode.UNNECESSARY)).isEqualTo(unitPrice);
            assertThat(item.getTotal().setScale(2, RoundingMode.UNNECESSARY)).isEqualTo(itemTotal);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(10)
    @DisplayName("Cascading Deletion: Deleting parent order cascades removal to all child items and messages")
    void test10_cascadeDelete_removesChildren() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-delete-" + UUID.randomUUID();
        String itemId = "item-del-" + UUID.randomUUID();
        String msgId = "msg-del-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Henry", "henry@example.com", "519-555-3344", "2026-11-08");
            order.addItem(new JpaOrderItem(itemId, "m1", "Bangers & Mash", 1, new BigDecimal("16.00"), new BigDecimal("16.00")));
            order.addMessage(new JpaOrderMessage(msgId, "CUSTOMER", "Henry", "On my way."));
            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Delete parent order
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            em.remove(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify all 3 rows deleted via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customer_orders WHERE id = '" + orderId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_items WHERE id = '" + itemId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_messages WHERE id = '" + msgId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(11)
    @DisplayName("CHECK Constraints: Database rejects invalid quantity and negative monetary values")
    void test11_checkConstraints_rejectedByDatabase() {
        // 1. Invalid item quantity <= 0 rejected by chk_order_items_quantity
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder("order-invalid-qty-" + UUID.randomUUID(), "Ivy", "ivy@example.com", "519-555-5555", "2026-11-09");
            JpaOrderItem invalidItem = new JpaOrderItem("m1", "Item", 0, new BigDecimal("10.00"), new BigDecimal("0.00")); // quantity = 0!
            order.addItem(invalidItem);
            em.persist(order);

            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }

        // 2. Negative subtotal rejected by chk_orders_subtotal
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder("order-neg-subtotal-" + UUID.randomUUID(), "Jack", "jack@example.com", "519-555-6666", "2026-11-09");
            order.setSubtotal(new BigDecimal("-5.00")); // negative!
            em.persist(order);

            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(12)
    @DisplayName("Spring Boot Minimal Context: Boots and validates all 8 entities with ddl-auto=validate")
    void test12_springBootContext_bootsAndValidatesAllEntities() {
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

            // Verify metadata inspection across both Core and Order entity types
            assertThat(springEmf.getMetamodel().entity(JpaCustomerOrder.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderMessage.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaUser.class)).isNotNull();
        });
    }

    @Test
    @Order(13)
    @DisplayName("Self-Assignment: order.setItems(getItems()) and setMessages(getMessages()) do not clear collections")
    void test13_selfAssignment_preservesCollectionsWithoutClearing() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-self-assign-" + UUID.randomUUID();
        String item1Id = "item-self-1-" + UUID.randomUUID();
        String item2Id = "item-self-2-" + UUID.randomUUID();
        String msg1Id = "msg-self-1-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Sam", "sam@example.com", "519-555-1212", "2026-11-10");
            order.addItem(new JpaOrderItem(item1Id, "m1", "Item 1", 1, new BigDecimal("10.00"), new BigDecimal("10.00")));
            order.addItem(new JpaOrderItem(item2Id, "m2", "Item 2", 1, new BigDecimal("15.00"), new BigDecimal("15.00")));
            order.addMessage(new JpaOrderMessage(msg1Id, "CUSTOMER", "Sam", "Hello"));
            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Perform self-assignment on managed entities
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            assertThat(order.getItems()).hasSize(2);
            assertThat(order.getMessages()).hasSize(1);

            // Self-assignment must not wipe out collections!
            order.setItems(order.getItems());
            order.setMessages(order.getMessages());

            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify elements survived in database and fresh session
        em = emf.createEntityManager();
        try {
            JpaCustomerOrder loaded = em.find(JpaCustomerOrder.class, orderId);
            assertThat(loaded.getItems()).hasSize(2);
            assertThat(loaded.getItems().get(0).getId()).isEqualTo(item1Id);
            assertThat(loaded.getItems().get(1).getId()).isEqualTo(item2Id);
            assertThat(loaded.getMessages()).hasSize(1);
            assertThat(loaded.getMessages().get(0).getId()).isEqualTo(msg1Id);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(14)
    @DisplayName("Managed Collection Replacement: Retained items stay managed, dropped items deleted as orphans")
    void test14_replacementOfManagedCollection_preservesRetainedAndRemovesOrphans() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-replace-" + UUID.randomUUID();
        String itemAId = "item-rep-A-" + UUID.randomUUID();
        String itemBId = "item-rep-B-" + UUID.randomUUID();
        String itemCId = "item-rep-C-" + UUID.randomUUID();

        String msg1Id = "msg-rep-1-" + UUID.randomUUID();
        String msg2Id = "msg-rep-2-" + UUID.randomUUID();
        String msg3Id = "msg-rep-3-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Tina", "tina@example.com", "519-555-3434", "2026-11-11");
            order.addItem(new JpaOrderItem(itemAId, "m1", "Item A", 1, new BigDecimal("10.00"), new BigDecimal("10.00")));
            order.addItem(new JpaOrderItem(itemBId, "m2", "Item B", 1, new BigDecimal("20.00"), new BigDecimal("20.00")));
            order.addMessage(new JpaOrderMessage(msg1Id, "CUSTOMER", "Tina", "Msg 1"));
            order.addMessage(new JpaOrderMessage(msg2Id, "KITCHEN", "Chef", "Msg 2"));
            em.persist(order);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Replace items with [B, C] (drop A, retain B, add C) and messages with [M2, M3]
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            JpaOrderItem managedB = order.getItems().get(1);
            assertThat(managedB.getId()).isEqualTo(itemBId);

            JpaOrderItem newC = new JpaOrderItem(itemCId, "m3", "Item C", 1, new BigDecimal("30.00"), new BigDecimal("30.00"));
            List<JpaOrderItem> replacementItems = new ArrayList<>();
            replacementItems.add(managedB);
            replacementItems.add(newC);
            order.setItems(replacementItems);

            JpaOrderMessage managedM2 = order.getMessages().get(1);
            assertThat(managedM2.getId()).isEqualTo(msg2Id);
            JpaOrderMessage newM3 = new JpaOrderMessage(msg3Id, "CUSTOMER", "Tina", "Msg 3");
            List<JpaOrderMessage> replacementMessages = new ArrayList<>();
            replacementMessages.add(managedM2);
            replacementMessages.add(newM3);
            order.setMessages(replacementMessages);

            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify in database: A and M1 are deleted, B, C, M2, M3 exist in correct order
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {

            // A is deleted
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_items WHERE id = '" + itemAId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            // M1 is deleted
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_messages WHERE id = '" + msg1Id + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            // B and C exist with item_index 0 and 1
            try (ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM order_items WHERE order_id = '" + orderId + "' ORDER BY item_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(itemBId);
                assertThat(rs.getInt("item_index")).isEqualTo(0);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(itemCId);
                assertThat(rs.getInt("item_index")).isEqualTo(1);

                assertThat(rs.next()).isFalse();
            }
            // M2 and M3 exist with item_index 0 and 1
            try (ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM order_messages WHERE order_id = '" + orderId + "' ORDER BY item_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(msg2Id);
                assertThat(rs.getInt("item_index")).isEqualTo(0);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(msg3Id);
                assertThat(rs.getInt("item_index")).isEqualTo(1);

                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @Order(15)
    @DisplayName("Unrelated Child Removal: Attempting to remove a child belonging to another order does not detach it")
    void test15_attemptedRemovalOfUnrelatedChild_doesNotDetachChild() {
        JpaCustomerOrder order1 = new JpaCustomerOrder("order-1-" + UUID.randomUUID(), "User 1", "u1@example.com", "519-555-1111", "2026-11-12");
        JpaCustomerOrder order2 = new JpaCustomerOrder("order-2-" + UUID.randomUUID(), "User 2", "u2@example.com", "519-555-2222", "2026-11-12");

        JpaOrderItem itemOrder1 = new JpaOrderItem("item-1-" + UUID.randomUUID(), "m1", "Dish 1", 1, new BigDecimal("10.00"), new BigDecimal("10.00"));
        JpaOrderItem itemOrder2 = new JpaOrderItem("item-2-" + UUID.randomUUID(), "m2", "Dish 2", 1, new BigDecimal("20.00"), new BigDecimal("20.00"));

        order1.addItem(itemOrder1);
        order2.addItem(itemOrder2);

        JpaOrderMessage msgOrder1 = new JpaOrderMessage("msg-1-" + UUID.randomUUID(), "CUSTOMER", "U1", "Text 1");
        JpaOrderMessage msgOrder2 = new JpaOrderMessage("msg-2-" + UUID.randomUUID(), "CUSTOMER", "U2", "Text 2");

        order1.addMessage(msgOrder1);
        order2.addMessage(msgOrder2);

        // Attempt to remove order2's item and message from order1
        order1.removeItem(itemOrder2);
        order1.removeMessage(msgOrder2);

        // Crucial verification: itemOrder2 and msgOrder2 must NOT have their parent order detached!
        assertThat(itemOrder2.getOrder()).isSameAs(order2);
        assertThat(msgOrder2.getOrder()).isSameAs(order2);
        assertThat(order1.getItems()).containsExactly(itemOrder1);
        assertThat(order2.getItems()).containsExactly(itemOrder2);
        assertThat(order1.getMessages()).containsExactly(msgOrder1);
        assertThat(order2.getMessages()).containsExactly(msgOrder2);
    }

    @Test
    @Order(16)
    @DisplayName("Equality and HashCode: Distinct transient entities with null IDs never compare equal, hashCode remains stable")
    void test16_distinctTransientEntitiesWithNullIds_equalityAndHashCodeContract() {
        // 1. JpaOrderItem
        JpaOrderItem item1 = new JpaOrderItem("m1", "Item 1", 1, new BigDecimal("10.00"), new BigDecimal("10.00"));
        JpaOrderItem item2 = new JpaOrderItem("m2", "Item 2", 1, new BigDecimal("20.00"), new BigDecimal("20.00"));
        assertThat(item1.getId()).isNull();
        assertThat(item2.getId()).isNull();

        // Must NOT compare equal solely because both IDs are null
        assertThat(item1.equals(item2)).isFalse();
        assertThat(item2.equals(item1)).isFalse();
        assertThat(item1.equals(item1)).isTrue(); // reflexive
        assertThat(item1.equals(null)).isFalse();
        assertThat(item1.equals(new Object())).isFalse();

        Set<JpaOrderItem> itemSet = new HashSet<>();
        itemSet.add(item1);
        itemSet.add(item2);
        assertThat(itemSet).hasSize(2);

        // HashCode stability across persistence
        int item1HashBefore = item1.hashCode();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-eq-" + UUID.randomUUID();
        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Ursula", "ursula@example.com", "519-555-4545", "2026-11-13");
            order.addItem(item1);
            em.persist(order);
            em.flush();
            tx.commit();
        } finally {
            em.close();
        }

        assertThat(item1.getId()).isNotNull();
        assertThat(item1.hashCode()).isEqualTo(item1HashBefore);
        assertThat(itemSet.contains(item1)).isTrue(); // Set lookup remains valid after persistence

        // 2. JpaOrderMessage
        JpaOrderMessage msg1 = new JpaOrderMessage("CUSTOMER", "Ursula", "Msg 1");
        JpaOrderMessage msg2 = new JpaOrderMessage("KITCHEN", "Chef", "Msg 2");
        assertThat(msg1.getId()).isNull();
        assertThat(msg2.getId()).isNull();
        assertThat(msg1.equals(msg2)).isFalse();
        assertThat(msg1.equals(msg1)).isTrue();

        Set<JpaOrderMessage> msgSet = new HashSet<>();
        msgSet.add(msg1);
        msgSet.add(msg2);
        assertThat(msgSet).hasSize(2);
        int msg1HashBefore = msg1.hashCode();

        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = em.find(JpaCustomerOrder.class, orderId);
            order.addMessage(msg1);
            em.flush();
            tx.commit();
        } finally {
            em.close();
        }
        assertThat(msg1.getId()).isNotNull();
        assertThat(msg1.hashCode()).isEqualTo(msg1HashBefore);
        assertThat(msgSet.contains(msg1)).isTrue();

        // 3. JpaCustomerOrder
        JpaCustomerOrder orderTransient1 = new JpaCustomerOrder();
        JpaCustomerOrder orderTransient2 = new JpaCustomerOrder();
        assertThat(orderTransient1.getId()).isNull();
        assertThat(orderTransient2.getId()).isNull();
        assertThat(orderTransient1.equals(orderTransient2)).isFalse();
        assertThat(orderTransient1.equals(orderTransient1)).isTrue();
    }

    @Test
    @Order(17)
    @DisplayName("Repeated Execution Isolation: Multiple independent order lifecycle cycles execute without ID collisions")
    void test17_repeatedExecutionIsolation_multipleCyclesSucceed() {
        for (int cycle = 0; cycle < 3; cycle++) {
            EntityManager em = emf.createEntityManager();
            EntityTransaction tx = em.getTransaction();
            String orderId = "order-repeat-cycle-" + cycle + "-" + UUID.randomUUID();
            String itemId = "item-repeat-cycle-" + cycle + "-" + UUID.randomUUID();
            String msgId = "msg-repeat-cycle-" + cycle + "-" + UUID.randomUUID();

            try {
                tx.begin();
                JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Repeat Customer", "repeat@example.com", "519-555-0000", "2026-11-14");
                order.addItem(new JpaOrderItem(itemId, "m1", "Repeated Dish", 1, new BigDecimal("12.00"), new BigDecimal("12.00")));
                order.addMessage(new JpaOrderMessage(msgId, "CUSTOMER", "Repeat Customer", "Cycle " + cycle));
                em.persist(order);
                em.flush();
                tx.commit();
            } finally {
                em.close();
            }

            em = emf.createEntityManager();
            try {
                JpaCustomerOrder loaded = em.find(JpaCustomerOrder.class, orderId);
                assertThat(loaded).isNotNull();
                assertThat(loaded.getItems()).hasSize(1);
                assertThat(loaded.getItems().get(0).getId()).isEqualTo(itemId);
                assertThat(loaded.getMessages()).hasSize(1);
                assertThat(loaded.getMessages().get(0).getId()).isEqualTo(msgId);
            } finally {
                em.close();
            }
        }
    }

    @Test
    @Order(18)
    @DisplayName("ItemIndex Read-Only: JpaOrderItem and JpaOrderMessage do not expose public setItemIndex, collection order is authoritative")
    void test18_readOnlyItemIndex_collectionOrderAuthoritative() throws Exception {
        // Verify setItemIndex is not public on JpaOrderItem or JpaOrderMessage
        assertThat(java.lang.reflect.Modifier.isPublic(
                JpaOrderItem.class.getDeclaredMethod("setItemIndex", Integer.class).getModifiers()))
                .isFalse();
        assertThat(java.lang.reflect.Modifier.isPublic(
                JpaOrderMessage.class.getDeclaredMethod("setItemIndex", Integer.class).getModifiers()))
                .isFalse();

        // Verify collection order authoritatively sets item_index in PostgreSQL
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String orderId = "order-index-auth-" + UUID.randomUUID();
        String item1Id = "item-idx-1-" + UUID.randomUUID();
        String item2Id = "item-idx-2-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = new JpaCustomerOrder(orderId, "Victor", "victor@example.com", "519-555-7878", "2026-11-15");
            JpaOrderItem item1 = new JpaOrderItem(item1Id, "m1", "First", 1, new BigDecimal("10.00"), new BigDecimal("10.00"));
            JpaOrderItem item2 = new JpaOrderItem(item2Id, "m2", "Second", 1, new BigDecimal("20.00"), new BigDecimal("20.00"));

            order.addItem(item1);
            order.addItem(item2);
            em.persist(order);
            em.flush();
            tx.commit();
        } finally {
            em.close();
        }

        // Verify that database item_index reflects list position (0, 1)
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM order_items WHERE order_id = '" + orderId + "' ORDER BY item_index")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(item1Id);
            assertThat(rs.getInt("item_index")).isEqualTo(0);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(item2Id);
            assertThat(rs.getInt("item_index")).isEqualTo(1);

            assertThat(rs.next()).isFalse();
        }
    }
}
