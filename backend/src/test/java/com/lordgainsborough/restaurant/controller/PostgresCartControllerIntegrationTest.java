package com.lordgainsborough.restaurant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lordgainsborough.restaurant.config.PostgresCartPersistenceConfiguration;
import com.lordgainsborough.restaurant.model.Cart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL cart contracts and competing transactions, without an ambient test transaction. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresCartControllerIntegrationTest {
    private static String jdbcUrl;
    private String database, adminUrl;
    private boolean owned;
    private AnnotationConfigApplicationContext context;
    private JpaUserRepository users;
    private JdbcTemplate jdbc;
    private CartStore store;
    private MockMvc mvc;
    private JwtUtil jwt;
    private MutableClock clock;
    private String ownerToken, otherToken;
    private final ObjectMapper json = new ObjectMapper();

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-26T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.repository")
    static class JpaConfig {
        @Bean PGSimpleDataSource dataSource() {
            var ds = new PGSimpleDataSource();
            ds.setUrl(jdbcUrl); ds.setUser("postgres"); ds.setPassword("postgres"); return ds;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(PGSimpleDataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.lordgainsborough.restaurant.persistence.jpa.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
    }

    @BeforeAll void provision() throws Exception {
        String port = System.getProperty("test.postgres.port");
        if (Boolean.getBoolean("test.postgres.mandatory") || "true".equalsIgnoreCase(System.getenv("CI")))
            assertThat(port).as("mandatory PostgreSQL port").matches("[0-9]{1,5}");
        Assumptions.assumeTrue(port != null, "Set test.postgres.port for PostgreSQL");
        database = "pg_cart_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("pg_cart_test_[a-f0-9]{32}");
        adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
        try (Connection c = DriverManager.getConnection(adminUrl, "postgres", "postgres"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE " + database); owned = true;
        }
        jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + database + "?sslmode=disable";
        Flyway.configure().dataSource(jdbcUrl,"postgres","postgres").cleanDisabled(true).load().migrate();
        clock = new MutableClock(); jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt,"secret","pg018-isolated-jwt-fixture-secret-at-least-32-bytes");
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("test");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("pg018", Map.of("app.cart.postgres.enabled","true", "jwt.expiration","60000", "jwt.secret","pg018-isolated-jwt-fixture-secret-at-least-32-bytes")));
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(JwtUtil.class, () -> jwt);
        context.register(JpaConfig.class, PostgresCartPersistenceConfiguration.class, CartController.class);
        context.refresh();
        users = context.getBean(JpaUserRepository.class); store = context.getBean(CartStore.class);
        jdbc = new JdbcTemplate(context.getBean(PGSimpleDataSource.class));
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(CartController.class)).build();
    }

    @AfterAll void dispose() throws Exception {
        if (context != null) context.close();
        if (owned) {
            assertThat(database).matches("pg_cart_test_[a-f0-9]{32}");
            try (Connection c = DriverManager.getConnection(adminUrl,"postgres","postgres"); Statement s = c.createStatement()) {
                s.executeUpdate("DROP DATABASE " + database + " WITH (FORCE)"); owned = false;
            }
        }
    }

    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE users CASCADE");
        clock.now = Instant.parse("2026-10-26T12:00:00Z");
        ReflectionTestUtils.setField(jwt,"expirationTime",60_000L);
        users.saveAndFlush(new JpaUser("owner","Owner","owner@example.com","5195551212","MEMBER"));
        users.saveAndFlush(new JpaUser("other","Other","other@example.com","5195553434","MEMBER"));
        ownerToken = jwt.generateToken("owner","OWNER@EXAMPLE.COM",0);
        otherToken = jwt.generateToken("other","other@example.com",0);
    }

    private Cart.CartItem item(String name, double price, int quantity) {
        var item = new Cart.CartItem(); item.setMenuItemId("same-menu-id"); item.setName(name);
        item.setPrice(price); item.setQuantity(quantity); item.setDescription("Snapshot description");
        item.setCategory("Food"); item.setImage("/snapshot.jpg"); return item;
    }
    private List<Cart.CartItem> items(String prefix, int count) {
        List<Cart.CartItem> result = new ArrayList<>();
        for (int i=0;i<count;i++) result.add(item(prefix+i,12.99,i+1));
        return result;
    }
    private void race(Supplier<?> first, Supplier<?> second) throws Exception {
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return first.get(); });
            var b = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return second.get(); });
            a.get(15,TimeUnit.SECONDS); b.get(15,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
    private void noOrphansOrDuplicateCarts() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items i LEFT JOIN carts c ON c.id=i.cart_id WHERE c.id IS NULL",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT user_id FROM carts GROUP BY user_id HAVING count(*) > 1) duplicate_carts",Long.class)).isZero();
    }

    @Test void allEndpointsPreserveUnauthorizedText() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized()).andExpect(content().string("Missing or invalid token."));
        mvc.perform(put("/api/cart").contentType("application/json").content("[]"))
                .andExpect(status().isUnauthorized()).andExpect(content().string("Missing or invalid token."));
        mvc.perform(delete("/api/cart")).andExpect(status().isUnauthorized()).andExpect(content().string("Missing or invalid token."));
    }
    @Test void rejectsInvalidExpiredWrongOwnerAndRevokedTokens() throws Exception {
        mvc.perform(get("/api/cart").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        String mismatched = jwt.generateToken("other","owner@example.com",0);
        mvc.perform(get("/api/cart").header("Authorization","Bearer "+mismatched)).andExpect(status().isUnauthorized());
        ReflectionTestUtils.setField(jwt,"expirationTime",-1000L);
        String expired = jwt.generateToken("owner","owner@example.com",0);
        mvc.perform(get("/api/cart").header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
        var owner = users.findById("owner").orElseThrow(); owner.setTokenVersion(1); users.saveAndFlush(owner);
        mvc.perform(put("/api/cart").header("Authorization","Bearer "+ownerToken).contentType("application/json").content("[]"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/cart").header("Authorization","Bearer "+ownerToken)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts",Long.class)).isZero();
    }
    @Test void rawArrayContractRoundTripsOnlySnapshotFields() throws Exception {
        mvc.perform(get("/api/cart").header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[]}"));
        var payload = items("Dish",3);
        String response = mvc.perform(put("/api/cart").header("Authorization","Bearer "+ownerToken)
                .contentType("application/json").content(json.writeValueAsString(payload)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response)).isEqualTo(json.valueToTree(Map.of("items",payload)));
        assertThat(store.read(ownerToken).orElseThrow().getItems()).usingRecursiveComparison().isEqualTo(payload);
        mvc.perform(put("/api/cart").header("Authorization","Bearer "+ownerToken).contentType("application/json").content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }
    @Test void fullReplacementsPreserveDuplicatesReorderingAndClock() {
        for (int count : new int[]{3,1,5,0,0}) {
            clock.now = clock.now.plusSeconds(1);
            var payload = items("Item",count);
            store.replace(ownerToken,payload).orElseThrow();
            var loaded = store.read(ownerToken).orElseThrow();
            assertThat(loaded.getItems()).usingRecursiveComparison().isEqualTo(payload);
            assertThat(loaded.getUpdatedAt()).isEqualTo(clock.instant());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items",Long.class)).isEqualTo((long)count);
        }
        var payload = new ArrayList<>(items("Item",3)); Collections.reverse(payload);
        store.replace(ownerToken,payload); store.replace(ownerToken,payload);
        payload.getFirst().setQuantity(99); payload.getFirst().setPrice(0);
        clock.now = clock.now.plusSeconds(1); store.replace(ownerToken,payload);
        assertThat(store.read(ownerToken).orElseThrow().getItems()).usingRecursiveComparison().isEqualTo(payload);
        store.replace(ownerToken,null);
        assertThat(store.read(ownerToken).orElseThrow().getItems()).isEmpty();
        noOrphansOrDuplicateCarts();
    }
    @Test void expiryIsInclusiveAndFreshPutRenewsExpiredCart() {
        store.replace(ownerToken,items("Old",1));
        clock.now = clock.now.plusSeconds(Cart.TTL_SECONDS).minusNanos(1);
        assertThat(store.read(ownerToken).orElseThrow().getItems()).hasSize(1);
        clock.now = clock.now.plusNanos(1);
        assertThat(store.read(ownerToken).orElseThrow().getItems()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts",Long.class)).isZero();
        store.replace(ownerToken,items("New",1));
        assertThat(store.read(ownerToken).orElseThrow().getItems().getFirst().getName()).isEqualTo("New0");
        var missingTimestamp = new JpaCart(); missingTimestamp.setUpdatedAt(null);
        assertThat(missingTimestamp.isExpired(clock.instant())).isTrue();
    }
    @Test void deletionIsIdempotentAndKeepsUserAndOtherCart() throws Exception {
        store.replace(ownerToken,items("Owner",2)); store.replace(otherToken,items("Other",1));
        for (int i=0;i<2;i++) mvc.perform(delete("/api/cart").header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(content().json("{\"message\":\"Cart cleared.\"}"));
        assertThat(users.count()).isEqualTo(2);
        assertThat(store.read(ownerToken).orElseThrow().getItems()).isEmpty();
        assertThat(store.read(otherToken).orElseThrow().getItems()).hasSize(1);
        users.deleteById("other");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts",Long.class)).isZero(); noOrphansOrDuplicateCarts();
    }
    @ParameterizedTest @ValueSource(doubles={-1,Double.NaN,Double.POSITIVE_INFINITY,0.001,100000000})
    void invalidMoneyLeavesPreviousSnapshotUntouched(double price) {
        store.replace(ownerToken,items("Old",1));
        assertThatThrownBy(() -> store.replace(ownerToken,List.of(item("Invalid",price,1)))).isInstanceOf(InvalidCartItemException.class);
        assertThat(store.read(ownerToken).orElseThrow().getItems().getFirst().getName()).isEqualTo("Old0");
    }
    @Test void invalidStringsQuantitiesAndNullItemsHaveStable400() throws Exception {
        var invalid = item("",1,1);
        for (List<Cart.CartItem> payload : List.of(List.of(invalid),List.of(item("Dish",1,0)),List.of(item("Dish",1,-1)),
                List.of(item("x".repeat(256),1,1)),Arrays.asList((Cart.CartItem)null))) {
            mvc.perform(put("/api/cart").header("Authorization","Bearer "+ownerToken).contentType("application/json")
                    .content(json.writeValueAsString(payload))).andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"code\":\"INVALID_CART_ITEM\",\"message\":\"One or more cart items are invalid.\"}"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts",Long.class)).isZero();
    }
    @Test void firstPutRaceAndReplacementRaceCommitOneWholeSnapshot() throws Exception {
        for (int i=0;i<3;i++) {
            store.clear(ownerToken);
            race(() -> store.replace(ownerToken,items("A",3)),() -> store.replace(ownerToken,items("B",5)));
            var names = store.read(ownerToken).orElseThrow().getItems().stream().map(Cart.CartItem::getName).toList();
            assertThat(names.equals(List.of("A0","A1","A2")) || names.equals(List.of("B0","B1","B2","B3","B4"))).isTrue();
            race(() -> store.replace(ownerToken,items("C",1)),() -> store.replace(ownerToken,items("D",2)));
            names = store.read(ownerToken).orElseThrow().getItems().stream().map(Cart.CartItem::getName).toList();
            assertThat(names.equals(List.of("C0")) || names.equals(List.of("D0","D1"))).isTrue();
            noOrphansOrDuplicateCarts();
        }
    }
    @Test void putDeleteGetDeleteAndExpiredCleanupPutStayCoherent() throws Exception {
        store.replace(ownerToken,items("Old",3));
        race(() -> store.replace(ownerToken,items("New",5)),() -> store.clear(ownerToken));
        var count = store.read(ownerToken).orElseThrow().getItems().size(); assertThat(count==0 || count==5).isTrue();
        race(() -> store.read(ownerToken),() -> store.clear(ownerToken));
        assertThat(store.read(ownerToken).orElseThrow().getItems()).isEmpty();
        store.replace(ownerToken,items("Old",1)); clock.now = clock.now.plusSeconds(Cart.TTL_SECONDS);
        race(() -> store.read(ownerToken),() -> store.replace(ownerToken,items("Fresh",2)));
        assertThat(store.read(ownerToken).orElseThrow().getItems()).extracting(Cart.CartItem::getName).containsExactly("Fresh0","Fresh1");
        noOrphansOrDuplicateCarts();
    }
    @Test void secondInitialPutWaitsForOwnerLockBeforeLoadingCart() throws Exception {
        var acquired = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delayedUsers = mock(JpaUserRepository.class,delegatesTo(users));
        doAnswer(call -> {
            var locked = users.findForCartByEmailIgnoreCase(call.getArgument(0)); acquired.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return locked;
        }).when(delayedUsers).findForCartByEmailIgnoreCase("OWNER@EXAMPLE.COM");
        var delayedStore = new PostgresCartStore(context.getBean(JpaCartRepository.class),delayedUsers,jwt,clock,
                context.getBean("cartJpaTransactionManager",org.springframework.orm.jpa.JpaTransactionManager.class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> delayedStore.replace(ownerToken,items("First",3)));
            assertThat(acquired.await(10,TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> store.replace(ownerToken,items("Second",5)));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked = false;
            while (System.nanoTime()<deadline) {
                blocked = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)>0;
                if (blocked) break;
                Thread.sleep(20);
            }
            assertThat(blocked).as("second PUT is waiting on the owner row").isTrue();
            assertThat(second.isDone()).isFalse(); release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isPresent(); assertThat(second.get(10,TimeUnit.SECONDS)).isPresent();
            assertThat(store.read(ownerToken).orElseThrow().getItems()).extracting(Cart.CartItem::getName)
                    .containsExactly("Second0","Second1","Second2","Second3","Second4");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id='owner'",Long.class)).isEqualTo(1L);
            noOrphansOrDuplicateCarts();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void childWriteFailureRollsBackEntireReplacement() {
        store.replace(ownerToken,items("Old",3));
        jdbc.execute("CREATE FUNCTION reject_cart_child() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled child failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_cart_child BEFORE INSERT ON cart_items FOR EACH ROW EXECUTE FUNCTION reject_cart_child()");
        try {
            assertThatThrownBy(() -> store.replace(ownerToken,items("New",5))).isInstanceOf(RuntimeException.class);
            assertThat(store.read(ownerToken).orElseThrow().getItems()).extracting(Cart.CartItem::getName).containsExactly("Old0","Old1","Old2");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items",Long.class)).isEqualTo(3L); noOrphansOrDuplicateCarts();
        } finally { jdbc.execute("DROP TRIGGER reject_cart_child ON cart_items"); jdbc.execute("DROP FUNCTION reject_cart_child()"); }
    }
}
