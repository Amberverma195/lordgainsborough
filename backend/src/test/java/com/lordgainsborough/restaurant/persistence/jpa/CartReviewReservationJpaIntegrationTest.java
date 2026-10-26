package com.lordgainsborough.restaurant.persistence.jpa;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCartItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderMessage;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReview;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReviewItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaReservation;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CartReviewReservationJpaIntegrationTest {

    private static final String TEST_DB = "cart_review_res_jpa_test";
    private String testPort;
    private String jdbcUrl;
    private PGSimpleDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean emfBean;
    private EntityManagerFactory emf;

    @Configuration
    @EntityScan(basePackageClasses = {JpaCart.class, JpaUser.class})
    static class TestEntityScanConfig {}

    private String getRequiredPort() {
        String port = System.getProperty("test.postgres.port");
        assertThat(port)
                .withFailMessage("test.postgres.port system property is required for PG-006 PostgreSQL integration tests")
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

    private JpaUser createAndPersistUser(EntityManager em, String emailSuffix) {
        String userId = "user-" + UUID.randomUUID();
        JpaUser user = new JpaUser(userId, "Cart User " + emailSuffix, "cart." + emailSuffix + "." + UUID.randomUUID() + "@example.com", "519-555-0100", "MEMBER");
        em.persist(user);
        return user;
    }

    private JpaCustomerOrder createAndPersistOrder(EntityManager em) {
        String orderId = "order-" + UUID.randomUUID();
        JpaCustomerOrder order = new JpaCustomerOrder(
                orderId,
                "Review Customer",
                "customer." + UUID.randomUUID() + "@example.com",
                "519-555-8899",
                "2026-11-20"
        );
        order.setSubtotal(new BigDecimal("45.00"));
        order.setTax(new BigDecimal("5.85"));
        order.setTotal(new BigDecimal("50.85"));
        em.persist(order);
        return order;
    }

    @Test
    @Order(1)
    @DisplayName("Hibernate metadata validation: All 13 JPA entities pass ddl-auto=validate against V1 schema")
    void test1_hibernateMetadataValidation_succeedsAgainstV1Schema() {
        assertThat(emf.isOpen()).isTrue();
        assertThat(emf.getMetamodel().entity(JpaCart.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaCartItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderReview.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaOrderReviewItem.class)).isNotNull();
        assertThat(emf.getMetamodel().entity(JpaReservation.class)).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Cart Persistence: Cart persists successfully referencing an existing JpaUser")
    void test2_cartPersistence_withExistingUser() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-" + UUID.randomUUID();
        String userId;

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "test2");
            userId = user.getId();

            JpaCart cart = new JpaCart(cartId, user);
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify in fresh session
        em = emf.createEntityManager();
        try {
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getUser()).isNotNull();
            assertThat(loaded.getUser().getId()).isEqualTo(userId);
            assertThat(loaded.getUserId()).isEqualTo(userId);
            assertThat(loaded.getUpdatedAt()).isNotNull();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Unique User Cart: Second cart for same user is rejected by uq_carts_user_id")
    void test3_uniqueUserCart_rejected() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        JpaUser user;

        try {
            tx.begin();
            user = createAndPersistUser(em, "test3");
            JpaCart cart1 = new JpaCart("cart-1-" + UUID.randomUUID(), user);
            em.persist(cart1);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Attempt second cart for the same user
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaUser managedUser = em.find(JpaUser.class, user.getId());
            JpaCart cart2 = new JpaCart("cart-2-" + UUID.randomUUID(), managedUser);
            em.persist(cart2);

            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(4)
    @DisplayName("Cart Items Cascade Persistence & Ordering: Items cascade-persist and maintain item_index order")
    void test4_cartItemsCascadePersistenceAndOrdering() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-order-" + UUID.randomUUID();
        String item1Id = "citem-1-" + UUID.randomUUID();
        String item2Id = "citem-2-" + UUID.randomUUID();
        String item3Id = "citem-3-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "test4");
            JpaCart cart = new JpaCart(cartId, user);

            JpaCartItem item1 = new JpaCartItem(item1Id, "m1", "Fish & Chips", "Classic pub fare", new BigDecimal("18.50"), "Mains", "img1.png", 1);
            JpaCartItem item2 = new JpaCartItem(item2Id, "m2", "Yorkshire Pudding", "Traditional side", new BigDecimal("6.00"), "Sides", "img2.png", 2);
            JpaCartItem item3 = new JpaCartItem(item3Id, "m3", "Sticky Toffee Pudding", "Warm dessert", new BigDecimal("9.50"), "Desserts", "img3.png", 1);

            cart.addItem(item1);
            cart.addItem(item2);
            cart.addItem(item3);

            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify in fresh session
        em = emf.createEntityManager();
        try {
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getItems()).hasSize(3);
            assertThat(loaded.getItems().get(0).getId()).isEqualTo(item1Id);
            assertThat(loaded.getItems().get(1).getId()).isEqualTo(item2Id);
            assertThat(loaded.getItems().get(2).getId()).isEqualTo(item3Id);
        } finally {
            em.close();
        }

        // Verify item_index values via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM cart_items WHERE cart_id = '" + cartId + "' ORDER BY item_index")) {
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
    }

    @Test
    @Order(5)
    @DisplayName("Cart Items Modification: Append, reorder, and remove operations persist correct item_index")
    void test5_cartItemsAppendReorderRemove() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-mod-" + UUID.randomUUID();
        String item1Id = "citem-mod-1-" + UUID.randomUUID();
        String item2Id = "citem-mod-2-" + UUID.randomUUID();
        String item3Id = "citem-mod-3-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "test5");
            JpaCart cart = new JpaCart(cartId, user);
            cart.addItem(new JpaCartItem(item1Id, "m1", "Item 1", "Desc", new BigDecimal("10.00"), "Cat", null, 1));
            cart.addItem(new JpaCartItem(item2Id, "m2", "Item 2", "Desc", new BigDecimal("20.00"), "Cat", null, 1));
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // 1. Append third item
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart cart = em.find(JpaCart.class, cartId);
            cart.addItem(new JpaCartItem(item3Id, "m3", "Item 3", "Desc", new BigDecimal("30.00"), "Cat", null, 1));
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // 2. Remove item 1 (orphan removal) and swap items 2 and 3
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart cart = em.find(JpaCart.class, cartId);
            JpaCartItem itemToRemove = cart.getItems().get(0);
            assertThat(itemToRemove.getId()).isEqualTo(item1Id);
            cart.removeItem(itemToRemove);

            // Now list has [item2, item3]. Swap them to [item3, item2]
            Collections.swap(cart.getItems(), 0, 1);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify via JDBC: item1 deleted, item3 at index 0, item2 at index 1
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM cart_items WHERE id = '" + item1Id + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }

            try (ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM cart_items WHERE cart_id = '" + cartId + "' ORDER BY item_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(item3Id);
                assertThat(rs.getInt("item_index")).isEqualTo(0);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(item2Id);
                assertThat(rs.getInt("item_index")).isEqualTo(1);

                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("Cart Deletion Cascade: Deleting a cart cascades removal to its cart items")
    void test6_cartDeletionCascade_removesItems() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-del-" + UUID.randomUUID();
        String itemId = "citem-del-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "test6");
            JpaCart cart = new JpaCart(cartId, user);
            cart.addItem(new JpaCartItem(itemId, "m1", "Item", "Desc", new BigDecimal("15.00"), "Cat", null, 1));
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Delete the cart
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart cart = em.find(JpaCart.class, cartId);
            em.remove(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify rows deleted via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM carts WHERE id = '" + cartId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM cart_items WHERE id = '" + itemId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("User Deletion Cascade: Deleting a user cascades via foreign key to cart and cart items")
    void test7_userDeletionCascade_removesCartAndItems() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-user-del-" + UUID.randomUUID();
        String itemId = "citem-user-del-" + UUID.randomUUID();
        String userId;

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "test7");
            userId = user.getId();

            JpaCart cart = new JpaCart(cartId, user);
            cart.addItem(new JpaCartItem(itemId, "m1", "Item", "Desc", new BigDecimal("15.00"), "Cat", null, 1));
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Delete the user via JDBC (testing relational ON DELETE CASCADE from users -> carts -> cart_items)
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM users WHERE id = '" + userId + "'");

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM carts WHERE id = '" + cartId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM cart_items WHERE id = '" + itemId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("Instant Timestamp Semantics: updatedAt persists with timezone and updates properly")
    void test8_updatedAtTimestampSemantics() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-time-" + UUID.randomUUID();
        Instant specificTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "test8");
            JpaCart cart = new JpaCart(cartId, user);
            cart.setUpdatedAt(specificTime);
            em.persist(cart);
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
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(specificTime);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(9)
    @DisplayName("7-Day Expiration: isExpired() correctly reflects the 7-day TTL semantics")
    void test9_sevenDayExpirationCalculation() {
        JpaCart freshCart = new JpaCart();
        freshCart.setUpdatedAt(Instant.now().minus(6, ChronoUnit.DAYS));
        assertThat(freshCart.isExpired()).isFalse();

        JpaCart expiredCart = new JpaCart();
        expiredCart.setUpdatedAt(Instant.now().minus(8, ChronoUnit.DAYS));
        assertThat(expiredCart.isExpired()).isTrue();

        JpaCart nullCart = new JpaCart();
        nullCart.setUpdatedAt(null);
        assertThat(nullCart.isExpired()).isTrue();

        // Specific reference timestamp
        Instant referenceTime = Instant.parse("2026-11-01T12:00:00Z");
        freshCart.setUpdatedAt(referenceTime.minus(6, ChronoUnit.DAYS));
        assertThat(freshCart.isExpired(referenceTime)).isFalse();
        expiredCart.setUpdatedAt(referenceTime.minus(7, ChronoUnit.DAYS).minusSeconds(1));
        assertThat(expiredCart.isExpired(referenceTime)).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("Review Persistence: Order review persists against an existing JpaCustomerOrder")
    void test10_orderReviewPersistence() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String reviewId = "rev-" + UUID.randomUUID();
        String orderId;

        try {
            tx.begin();
            JpaCustomerOrder order = createAndPersistOrder(em);
            orderId = order.getId();

            JpaOrderReview review = new JpaOrderReview(
                    reviewId,
                    order,
                    order.getCustomerName(),
                    order.getCustomerEmail(),
                    order.getCustomerPhone()
            );
            review.setStatus("APPROVED");
            em.persist(review);
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
            JpaOrderReview loaded = em.find(JpaOrderReview.class, reviewId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getOrder()).isNotNull();
            assertThat(loaded.getOrder().getId()).isEqualTo(orderId);
            assertThat(loaded.getOrderId()).isEqualTo(orderId);
            assertThat(loaded.getStatus()).isEqualTo("APPROVED");
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getUpdatedAt()).isNotNull();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(11)
    @DisplayName("Unique Order Review: Second review for same order is rejected by uq_order_reviews_order_id")
    void test11_uniqueOrderReview_rejected() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        JpaCustomerOrder order;

        try {
            tx.begin();
            order = createAndPersistOrder(em);
            JpaOrderReview review1 = new JpaOrderReview("rev-1-" + UUID.randomUUID(), order, "Alice", "a@example.com", "519-555-1111");
            em.persist(review1);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Attempt second review for the same order
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder managedOrder = em.find(JpaCustomerOrder.class, order.getId());
            JpaOrderReview review2 = new JpaOrderReview("rev-2-" + UUID.randomUUID(), managedOrder, "Bob", "b@example.com", "519-555-2222");
            em.persist(review2);

            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(12)
    @DisplayName("Review Items Ordering: Review items cascade-persist and maintain item_index order")
    void test12_reviewItemsOrdering() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String reviewId = "rev-items-" + UUID.randomUUID();
        String ritem1Id = "ritem-1-" + UUID.randomUUID();
        String ritem2Id = "ritem-2-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = createAndPersistOrder(em);
            JpaOrderReview review = new JpaOrderReview(reviewId, order, "Reviewer", "rev@example.com", "519-555-3333");

            JpaOrderReviewItem item1 = new JpaOrderReviewItem(ritem1Id, "m1", "Fish & Chips", 5, "Crispy and flavorful!");
            JpaOrderReviewItem item2 = new JpaOrderReviewItem(ritem2Id, "m2", "Sticky Toffee Pudding", 4, "Rich and sweet.");

            review.addItem(item1);
            review.addItem(item2);

            em.persist(review);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, item_index, rating FROM order_review_items WHERE review_id = '" + reviewId + "' ORDER BY item_index")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(ritem1Id);
            assertThat(rs.getInt("item_index")).isEqualTo(0);
            assertThat(rs.getInt("rating")).isEqualTo(5);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(ritem2Id);
            assertThat(rs.getInt("item_index")).isEqualTo(1);
            assertThat(rs.getInt("rating")).isEqualTo(4);

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @Order(13)
    @DisplayName("Review Item Orphan Removal: Removing review item deletes row and reindexes")
    void test13_reviewItemOrphanRemoval() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String reviewId = "rev-orphan-" + UUID.randomUUID();
        String itemToRemoveId = "ritem-rem-" + UUID.randomUUID();
        String itemToKeepId = "ritem-keep-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaCustomerOrder order = createAndPersistOrder(em);
            JpaOrderReview review = new JpaOrderReview(reviewId, order, "Critic", "critic@example.com", "519-555-4444");
            review.addItem(new JpaOrderReviewItem(itemToRemoveId, "m1", "Item A", 3, "Average"));
            review.addItem(new JpaOrderReviewItem(itemToKeepId, "m2", "Item B", 5, "Outstanding"));
            em.persist(review);
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
            JpaOrderReview review = em.find(JpaOrderReview.class, reviewId);
            JpaOrderReviewItem item = review.getItems().get(0);
            assertThat(item.getId()).isEqualTo(itemToRemoveId);
            review.removeItem(item);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_review_items WHERE id = '" + itemToRemoveId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM order_review_items WHERE review_id = '" + reviewId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(itemToKeepId);
                assertThat(rs.getInt("item_index")).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(14)
    @DisplayName("Order Deletion Cascade: Deleting order cascades to review and review items")
    void test14_orderDeletionCascade_removesReviewAndItems() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String reviewId = "rev-casc-del-" + UUID.randomUUID();
        String ritemId = "ritem-casc-del-" + UUID.randomUUID();
        String orderId;

        try {
            tx.begin();
            JpaCustomerOrder order = createAndPersistOrder(em);
            orderId = order.getId();

            JpaOrderReview review = new JpaOrderReview(reviewId, order, "Guest", "g@example.com", "519-555-9999");
            review.addItem(new JpaOrderReviewItem(ritemId, "m1", "Food", 5, "Great"));
            em.persist(review);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Delete order via JDBC to verify database foreign key cascade (customer_orders -> order_reviews -> order_review_items)
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM customer_orders WHERE id = '" + orderId + "'");

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_reviews WHERE id = '" + reviewId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM order_review_items WHERE id = '" + ritemId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }
    }

    @Test
    @Order(15)
    @DisplayName("Review Rating Constraint: Rating < 1 or > 5 is rejected by chk_order_review_items_rating")
    void test15_invalidReviewRating_rejected() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();

        // 1. Rating 0 rejected
        try {
            tx.begin();
            JpaCustomerOrder order = createAndPersistOrder(em);
            JpaOrderReview review = new JpaOrderReview("rev-chk-0-" + UUID.randomUUID(), order, "Rater", "r@example.com", "519-555-0000");
            review.addItem(new JpaOrderReviewItem("m1", "Food", 0, "Too low")); // rating 0!
            em.persist(review);

            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }

        // 2. Rating 6 rejected
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCustomerOrder order = createAndPersistOrder(em);
            JpaOrderReview review = new JpaOrderReview("rev-chk-6-" + UUID.randomUUID(), order, "Rater", "r@example.com", "519-555-0000");
            review.addItem(new JpaOrderReviewItem("m1", "Food", 6, "Too high")); // rating 6!
            em.persist(review);

            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(16)
    @DisplayName("Guest Reservation Persistence: Reservation persists without user foreign key")
    void test16_guestReservationPersistence() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String resId = "res-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaReservation reservation = new JpaReservation(
                    resId,
                    "Lord Hamilton",
                    "hamilton@example.com",
                    "519-555-7766",
                    "2026-12-25",
                    "19:00",
                    4
            );
            reservation.setSpecialRequests("Window booth please.");
            em.persist(reservation);
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
            JpaReservation loaded = em.find(JpaReservation.class, resId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getName()).isEqualTo("Lord Hamilton");
            assertThat(loaded.getEmail()).isEqualTo("hamilton@example.com");
            assertThat(loaded.getDate()).isEqualTo("2026-12-25");
            assertThat(loaded.getTime()).isEqualTo("19:00");
            assertThat(loaded.getPartySize()).isEqualTo(4);
            assertThat(loaded.getSpecialRequests()).isEqualTo("Window booth please.");
            assertThat(loaded.getStatus()).isEqualTo("PENDING");
            assertThat(loaded.getCreatedAt()).isNotNull();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(17)
    @DisplayName("Reservation Date/Time Round-Trip: Date and time strings round-trip correctly")
    void test17_reservationDateTimeStringRoundTrip() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String resId = "res-str-" + UUID.randomUUID();

        try {
            tx.begin();
            JpaReservation reservation = new JpaReservation(
                    resId,
                    "Lady Catherine",
                    "catherine@example.com",
                    "519-555-2468",
                    "2026-11-15",
                    "20:30",
                    2
            );
            em.persist(reservation);
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
            JpaReservation loaded = em.find(JpaReservation.class, resId);
            assertThat(loaded.getDate()).isEqualTo("2026-11-15");
            assertThat(loaded.getTime()).isEqualTo("20:30");
        } finally {
            em.close();
        }
    }

    @Test
    @Order(18)
    @DisplayName("Reservation Party Size Constraint: Party size <= 0 is rejected by chk_reservations_party_size")
    void test18_invalidPartySize_rejected() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();

        try {
            tx.begin();
            JpaReservation invalid = new JpaReservation(
                    "res-invalid-" + UUID.randomUUID(),
                    "Ghost",
                    "ghost@example.com",
                    "519-555-0000",
                    "2026-11-16",
                    "18:00",
                    0 // party size 0!
            );
            em.persist(invalid);

            assertThatThrownBy(em::flush)
                    .isInstanceOf(PersistenceException.class);
            tx.rollback();
        } finally {
            em.close();
        }
    }

    @Test
    @Order(19)
    @DisplayName("BigDecimal Precision: Cart item price maintains exact NUMERIC(10, 2) precision")
    void test19_bigDecimalPricePrecision() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-price-" + UUID.randomUUID();
        BigDecimal price = new BigDecimal("29.95");

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "test19");
            JpaCart cart = new JpaCart(cartId, user);
            cart.addItem(new JpaCartItem("m1", "Filet Mignon", "Tender cut", price, "Mains", null, 1));
            em.persist(cart);
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
            JpaCart loaded = em.find(JpaCart.class, cartId);
            BigDecimal loadedPrice = loaded.getItems().get(0).getPrice();
            assertThat(loadedPrice.setScale(2, RoundingMode.UNNECESSARY)).isEqualTo(price);
        } finally {
            em.close();
        }
    }

    @Test
    @Order(20)
    @DisplayName("Spring Boot Minimal Context: Boots and validates all 13 JPA entities with ddl-auto=validate")
    void test20_springBootContext_bootsAndValidatesAllEntities() {
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

            // Verify all 13 JPA entity types are registered and validated
            assertThat(springEmf.getMetamodel().entity(JpaUser.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCategory.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCustomerOrder.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderMessage.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCart.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaCartItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderReview.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaOrderReviewItem.class)).isNotNull();
            assertThat(springEmf.getMetamodel().entity(JpaReservation.class)).isNotNull();
        });
    }

    @Test
    @Order(21)
    @DisplayName("Cart Touch on Add Item: Adding an item without changing parent scalar fields advances carts.updated_at")
    void test21_cartTimestampAdvances_onAddItemWithoutParentScalarChanges() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-touch-add-" + UUID.randomUUID();
        Instant pastTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "touch-add");
            JpaCart cart = new JpaCart(cartId, user);
            cart.setUpdatedAt(pastTime);
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Verify initial past timestamp via JDBC
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT updated_at FROM carts WHERE id = '" + cartId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp("updated_at").toInstant().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(pastTime);
        }

        // Fresh persistence context: add item without modifying any parent scalar fields
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            // Add item via helper; do NOT set any scalar fields on loaded
            loaded.addItem(new JpaCartItem("m-touch-1", "Crispy Calamari", "Appetizer", new BigDecimal("14.50"), "Starters", null, 2));
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Direct SQL assertion: carts.updated_at must have advanced
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT updated_at FROM carts WHERE id = '" + cartId + "'")) {
            assertThat(rs.next()).isTrue();
            Instant dbUpdatedAt = rs.getTimestamp("updated_at").toInstant();
            assertThat(dbUpdatedAt).isAfter(pastTime);
        }
    }

    @Test
    @Order(22)
    @DisplayName("Cart Touch on Remove Item: Removing an item without changing parent scalar fields advances carts.updated_at")
    void test22_cartTimestampAdvances_onRemoveItem() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-touch-rm-" + UUID.randomUUID();
        Instant pastTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "touch-rm");
            JpaCart cart = new JpaCart(cartId, user);
            cart.setUpdatedAt(pastTime);
            cart.addItem(new JpaCartItem("m-rm-1", "Item A", "Desc A", new BigDecimal("10.00"), "Cat", null, 1));
            cart.addItem(new JpaCartItem("m-rm-2", "Item B", "Desc B", new BigDecimal("20.00"), "Cat", null, 1));
            // Reset to pastTime after initial addItem calls
            cart.setUpdatedAt(pastTime);
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Fresh persistence context: remove an item
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getItems()).hasSize(2);
            loaded.removeItem(loaded.getItems().get(0));
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Direct SQL assertion: carts.updated_at advanced in database
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT updated_at FROM carts WHERE id = '" + cartId + "'")) {
            assertThat(rs.next()).isTrue();
            Instant dbUpdatedAt = rs.getTimestamp("updated_at").toInstant();
            assertThat(dbUpdatedAt).isAfter(pastTime);
        }
    }

    @Test
    @Order(23)
    @DisplayName("Cart Touch on Quantity Change: Modifying cart item quantity advances carts.updated_at")
    void test23_cartTimestampAdvances_onChangeItemQuantity() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-touch-qty-" + UUID.randomUUID();
        Instant pastTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "touch-qty");
            JpaCart cart = new JpaCart(cartId, user);
            cart.setUpdatedAt(pastTime);
            cart.addItem(new JpaCartItem("m-qty-1", "Steak", "Sirloin", new BigDecimal("32.00"), "Mains", null, 1));
            cart.setUpdatedAt(pastTime);
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Fresh persistence context: modify item quantity directly
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            JpaCartItem item = loaded.getItems().get(0);
            item.setQuantity(5); // Mutates child quantity; automatically touches parent
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Direct SQL assertion: item quantity is 5 and carts.updated_at advanced
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT quantity FROM cart_items WHERE cart_id = '" + cartId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("quantity")).isEqualTo(5);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT updated_at FROM carts WHERE id = '" + cartId + "'")) {
                assertThat(rs.next()).isTrue();
                Instant dbUpdatedAt = rs.getTimestamp("updated_at").toInstant();
                assertThat(dbUpdatedAt).isAfter(pastTime);
            }
        }
    }

    @Test
    @Order(24)
    @DisplayName("Cart Touch on Price Change: Modifying cart item price advances carts.updated_at")
    void test24_cartTimestampAdvances_onChangeItemPrice() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-touch-price-" + UUID.randomUUID();
        Instant pastTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "touch-price");
            JpaCart cart = new JpaCart(cartId, user);
            cart.setUpdatedAt(pastTime);
            cart.addItem(new JpaCartItem("m-price-1", "Roast Beef", "Special", new BigDecimal("25.00"), "Mains", null, 1));
            cart.setUpdatedAt(pastTime);
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Fresh persistence context: modify item price directly
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            JpaCartItem item = loaded.getItems().get(0);
            item.setPrice(new BigDecimal("29.95")); // Repricing touches parent cart
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Direct SQL assertion: item price is 29.95 and carts.updated_at advanced
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT price FROM cart_items WHERE cart_id = '" + cartId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal("price")).isEqualByComparingTo(new BigDecimal("29.95"));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT updated_at FROM carts WHERE id = '" + cartId + "'")) {
                assertThat(rs.next()).isTrue();
                Instant dbUpdatedAt = rs.getTimestamp("updated_at").toInstant();
                assertThat(dbUpdatedAt).isAfter(pastTime);
            }
        }
    }

    @Test
    @Order(25)
    @DisplayName("Cart Touch on Reorder: Reordering items advances carts.updated_at and persists new order")
    void test25_cartTimestampAdvances_onReorderItems() throws Exception {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String cartId = "cart-touch-reorder-" + UUID.randomUUID();
        String itemAId = "citem-a-" + UUID.randomUUID();
        String itemBId = "citem-b-" + UUID.randomUUID();
        String itemCId = "citem-c-" + UUID.randomUUID();
        Instant pastTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        try {
            tx.begin();
            JpaUser user = createAndPersistUser(em, "touch-reorder");
            JpaCart cart = new JpaCart(cartId, user);
            cart.setUpdatedAt(pastTime);
            cart.addItem(new JpaCartItem(itemAId, "m-a", "A", "Desc", new BigDecimal("10.00"), "Cat", null, 1));
            cart.addItem(new JpaCartItem(itemBId, "m-b", "B", "Desc", new BigDecimal("20.00"), "Cat", null, 1));
            cart.addItem(new JpaCartItem(itemCId, "m-c", "C", "Desc", new BigDecimal("30.00"), "Cat", null, 1));
            cart.setUpdatedAt(pastTime);
            em.persist(cart);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Fresh persistence context: reorder items (move item 0 to index 2: [B, C, A])
        em = emf.createEntityManager();
        tx = em.getTransaction();
        try {
            tx.begin();
            JpaCart loaded = em.find(JpaCart.class, cartId);
            assertThat(loaded).isNotNull();
            loaded.reorderItems(0, 2);
            em.flush();
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        } finally {
            em.close();
        }

        // Direct SQL assertion: carts.updated_at advanced and positions updated in database
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT updated_at FROM carts WHERE id = '" + cartId + "'")) {
                assertThat(rs.next()).isTrue();
                Instant dbUpdatedAt = rs.getTimestamp("updated_at").toInstant();
                assertThat(dbUpdatedAt).isAfter(pastTime);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT id, item_index FROM cart_items WHERE cart_id = '" + cartId + "' ORDER BY item_index")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(itemBId);
                assertThat(rs.getInt("item_index")).isEqualTo(0);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(itemCId);
                assertThat(rs.getInt("item_index")).isEqualTo(1);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo(itemAId);
                assertThat(rs.getInt("item_index")).isEqualTo(2);

                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @Order(26)
    @DisplayName("Jackson Serialization Safeguards: Serializes userId without leaking user credentials or causing circular recursion")
    void test26_cartJacksonSerialization_safeguardsUserCredentialsAndExposesUserId() throws Exception {
        JpaUser user = new JpaUser(
                "user-sec-" + UUID.randomUUID(),
                "Alice Security",
                "alice." + UUID.randomUUID() + "@example.com",
                "519-555-0199",
                "MEMBER"
        );
        user.setPassword("SuperSecretBcryptHash$2a$10$abcdef1234567890");
        user.setTokenVersion(42L);
        user.setLastPasswordResetOperationId("op-reset-xyz-777");

        JpaCart cart = new JpaCart("cart-sec-" + UUID.randomUUID(), user);
        cart.addItem(new JpaCartItem("m-sec-1", "Pot Pie", "Savory pie", new BigDecimal("17.00"), "Mains", null, 2));

        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();

        String json = mapper.writeValueAsString(cart);
        assertThat(json).isNotNull();

        // 1. Exposes userId matching MongoDB REST contract
        assertThat(json).contains("\"userId\":\"" + user.getId() + "\"");

        // 2. Safeguards: Never leaks user password, token version, or reset operation ID
        assertThat(json).doesNotContain("SuperSecretBcryptHash");
        assertThat(json).doesNotContain("\"password\":");
        assertThat(json).doesNotContain("\"tokenVersion\":");
        assertThat(json).doesNotContain("op-reset-xyz-777");
        assertThat(json).doesNotContain("\"lastPasswordResetOperationId\":");

        // 3. Never serializes nested user object
        assertThat(json).doesNotContain("\"user\":");

        // 4. Never causes circular back-reference in child cart items
        assertThat(json).doesNotContain("\"cart\":");

        // 5. Normal cart fields are intact
        assertThat(json).contains("\"id\":\"" + cart.getId() + "\"");
        assertThat(json).contains("\"items\":[");
        assertThat(json).contains("\"name\":\"Pot Pie\"");
    }
}

