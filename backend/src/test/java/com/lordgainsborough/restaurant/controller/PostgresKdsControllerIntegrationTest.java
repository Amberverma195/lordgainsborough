package com.lordgainsborough.restaurant.controller;

import com.corundumstudio.socketio.SocketIOServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.config.PostgresOrderPersistenceConfiguration;
import com.lordgainsborough.restaurant.config.PostgresKdsPersistenceConfiguration;
import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import com.lordgainsborough.restaurant.dto.*;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Flyway/PostgreSQL/JPA transactions; no ambient test transaction or open-in-view. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresKdsControllerIntegrationTest {
    private String database, adminUrl;
    private boolean owned;
    private String jdbcUrl;
    private AnnotationConfigApplicationContext context;
    private PostgresCustomerOrderStore store;
    private OrderController controller;
    private JpaCustomerOrderRepository orders;
    private JpaMenuItemRepository menu;
    private JpaUserRepository users;
    private JpaStoreSettingsRepository settings;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private SocketIOServer sockets;
    private KdsController kds;
    private PostgresKdsOrderStore kitchenStore;
    private CustomerNoShowService noShows;
    private CustomerOrderPresentationService presentation;
    private KdsAccessService kdsAccess;
    private MutableClock clock;
    private SignupAbuseProperties proxyProperties;
    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-26T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() { public ZoneId getZone() { return zone; } public Clock withZone(ZoneId next) { return self.withZone(next); }
                public Instant instant() { return self.instant(); } };
        }
        public Instant instant() { return now; }
    }

    private OrderAccessService access;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.repository")
    static class JpaConfig {
        @Bean PGSimpleDataSource dataSource(org.springframework.core.env.Environment environment) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(environment.getRequiredProperty("test.jdbc-url")); ds.setUser("postgres"); ds.setPassword("postgres"); return ds;
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
        String port = com.lordgainsborough.restaurant.support.LocalPostgres.requirePort();
        boolean mandatory = Boolean.getBoolean("test.postgres.mandatory") || "true".equalsIgnoreCase(System.getenv("CI"));
        if (mandatory) assertThat(port).as("mandatory live PostgreSQL port").matches("[0-9]{1,5}");
        Assumptions.assumeTrue(port != null, "Set test.postgres.port for live PostgreSQL");
        database = "pg_kds_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("pg_kds_test_[a-f0-9]{32}");
        adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
        try (Connection c = DriverManager.getConnection(adminUrl, "postgres", "postgres"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE " + database); owned = true;
        }
        jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + database + "?sslmode=disable";
        Flyway.configure().dataSource(jdbcUrl, "postgres", "postgres").cleanDisabled(true).load().migrate();
        sockets = mock(SocketIOServer.class, RETURNS_DEEP_STUBS);
        clock = new MutableClock();
        proxyProperties = new SignupAbuseProperties();
        proxyProperties.setTrustedProxies(List.of("127.0.0.1"));

        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("test");
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "pg017", Map.of("app.orders.postgres.enabled", "true", "app.kds.postgres.enabled", "true")));
        context.registerBean(JwtUtil.class, () -> mock(JwtUtil.class));
        context.registerBean(OrderRealtimeService.class, () -> new OrderRealtimeService(sockets,context.getBean(CustomerOrderPresentationService.class)));
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(ClientIpResolver.class, () -> new ClientIpResolver(proxyProperties));
        context.registerBean(OrderAccessService.class, () -> new OrderAccessService(context.getBean(JwtUtil.class),context.getBean(PostgresUserAuthStore.class)));
        context.registerBean(PostgresUserAuthStore.class, () -> new PostgresUserAuthStore(context.getBean(JpaUserRepository.class),context.getBean(JpaCustomerOrderRepository.class),context.getBean(JpaOrderReviewRepository.class),context.getBean("orderJpaTransactionManager",org.springframework.orm.jpa.JpaTransactionManager.class),java.time.Clock.systemDefaultZone()));
        context.register(com.lordgainsborough.restaurant.config.WebConfig.class,AdminAuthInterceptor.class);
        context.register(JpaConfig.class, PostgresOrderPersistenceConfiguration.class, PostgresKdsPersistenceConfiguration.class,
                OrderController.class, KdsController.class, CustomerNoShowService.class, CustomerOrderPresentationService.class, KdsAccessService.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("jdbc", Map.of("test.jdbc-url",jdbcUrl,"jwt.expiration","86400000","jwt.secret","fixture-only-secret-at-least-32-bytes")));
        context.refresh(); access=context.getBean(OrderAccessService.class);
        store = context.getBean(PostgresCustomerOrderStore.class);
        controller = context.getBean(OrderController.class);
        orders = context.getBean(JpaCustomerOrderRepository.class);
        menu = context.getBean(JpaMenuItemRepository.class);
        users = context.getBean(JpaUserRepository.class);
        settings = context.getBean(JpaStoreSettingsRepository.class);
        jdbc = new JdbcTemplate(context.getBean(PGSimpleDataSource.class));
        transactions = new TransactionTemplate(context.getBean("orderJpaTransactionManager", JpaTransactionManager.class));
        kds = context.getBean(KdsController.class); kitchenStore = context.getBean(PostgresKdsOrderStore.class);
        noShows = context.getBean(CustomerNoShowService.class); presentation = context.getBean(CustomerOrderPresentationService.class);
        kdsAccess = context.getBean(KdsAccessService.class);
        mvc = MockMvcBuilders.standaloneSetup(controller,kds)
                .addInterceptors(context.getBean(AdminAuthInterceptor.class)).build();
    }

    @AfterAll void dispose() throws Exception {
        if (context != null) context.close();
        if (owned) {
            assertThat(database).matches("pg_kds_test_[a-f0-9]{32}");
            try (Connection c = DriverManager.getConnection(adminUrl, "postgres", "postgres"); Statement s = c.createStatement()) {
                s.executeUpdate("DROP DATABASE " + database + " WITH (FORCE)"); owned = false;
            }
        }
    }

    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE customer_orders, users, menu_items, store_settings CASCADE");
        clock.now = Instant.parse("2026-10-26T12:00:00Z");
        var row = new JpaStoreSettings(); row.setKdsAllowedIp("127.0.0.1"); settings.saveAndFlush(row);
        menu.saveAndFlush(new JpaMenuItem("dish","Steak","",new BigDecimal("17.99"),"Mains",null));
        var user = new JpaUser("owner","Owner","owner@example.com","5195551212","MEMBER");
        user.setPassword("unchanged-password-hash"); user.setTokenVersion(7); user.setPhoneNormalized("5195551212"); users.saveAndFlush(user);
        reset(sockets);
    }
    private CustomerOrder request() {
        var order = new CustomerOrder(); order.setCustomerName("Owner"); order.setCustomerEmail("owner@example.com");
        order.setCustomerPhone("5195551212"); order.setPickupDate(java.time.LocalDate.now(clock.withZone(ZoneId.systemDefault())).toString());
        var item = new CustomerOrder.OrderItem(); item.setMenuItemId("dish"); item.setQuantity(2); item.setNotes("No salt");
        order.getItems().add(item); return order;
    }
    private OrderCreationResponse create() {
        var response = controller.createOrder(request()); assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (OrderCreationResponse)response.getBody();
    }
    private CustomerOrder transition(String id, String value) {
        var response = kds.updateStatus(id,Map.of("status",value)); assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }
    private int counter() { return users.findById("owner").orElseThrow().getNoShowCount(); }
    private void race(Runnable first, Runnable second) throws Exception {
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); first.run(); return true; });
            var b = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); second.run(); return true; });
            assertThat(a.get(15,TimeUnit.SECONDS)).isTrue(); assertThat(b.get(15,TimeUnit.SECONDS)).isTrue();
        } finally { pool.shutdownNow(); }
    }
    private void allow(String ips) {
        var row = settings.findById("default").orElseThrow(); row.setKdsAllowedIp(ips); settings.saveAndFlush(row);
    }

    @Test void checkoutAndKdsShareRowsAndFullSafeJson() throws Exception {
        var created = create(); String id = created.getOrder().getId();
        mvc.perform(get("/api/kds/access")).andExpect(status().isOk()).andExpect(content().json("{\"allowed\":true}"));
        String response = mvc.perform(get("/api/kds/orders")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var order = json.readTree(response).get(0);
        var fields = new HashSet<String>(); order.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","customerName","customerEmail","customerPhone","pickupDate","pickupTime",
                "specialInstructions","status","averageReadyMinutes","items","messages","subtotal","tax","total","createdAt",
                "acceptedAt","readyAt","pickedUpAt","noShowAt","reviewed","customerOrderCount","customerNoShowCount");
        assertThat(order.get("id").asText()).isEqualTo(id); assertThat(order.get("items").size()).isEqualTo(1);
        assertThat(order.get("customerOrderCount").asInt()).isEqualTo(1); assertThat(order.get("customerNoShowCount").asInt()).isZero();
        assertThat(response).doesNotContain("trackingTokenHash",created.getTrackingToken(),access.hashTrackingToken(created.getTrackingToken()));

        // History remains admin-JWT gated, unlike the operational IP-only board.
        mvc.perform(get("/api/kds/orders/all")).andExpect(status().isNotFound());
        assertThat(kds.getAllOrders()).extracting(CustomerOrder::getId).containsExactly(id);
    }
    @Test void datesChronologyAndTieBreaksAreDeterministic() {
        String first = create().getOrder().getId(), second = create().getOrder().getId(), other = create().getOrder().getId();
        var time = LocalDateTime.of(2026,10,1,12,0);
        for (String id : List.of(first,second)) { var row=orders.findById(id).orElseThrow(); row.setCreatedAt(time); orders.saveAndFlush(row); }
        var row=orders.findById(other).orElseThrow(); row.setPickupDate("2027-01-01"); row.setCreatedAt(time.plusDays(1)); orders.saveAndFlush(row);
        var sorted = List.of(first,second).stream().sorted().toList();
        assertThat(kds.getOrders(null)).extracting(CustomerOrder::getId).containsExactlyElementsOf(sorted);
        assertThat(kds.getOrders(" ")).hasSize(2); assertThat(kds.getOrders("2027-01-01")).extracting(CustomerOrder::getId).containsExactly(other);
        assertThat(kds.getAllOrders()).extracting(CustomerOrder::getId).containsExactly(other,sorted.get(0),sorted.get(1));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"NEW","ACCEPTED","READY","PICKED_UP","NO_SHOW"})
    void allStatusesPreserveSnapshotsAndFirstTimes(String value) {
        var created=create(); String id=created.getOrder().getId();
        var before=store.findById(id).orElseThrow(); var first=transition(id," "+value.toLowerCase(Locale.ROOT)+" ");
        clock.now=clock.now.plusSeconds(60); var repeated=transition(id,value);
        assertThat(repeated.getStatus()).isEqualTo(value);
        assertThat(repeated.getAcceptedAt()).isEqualTo(first.getAcceptedAt()); assertThat(repeated.getReadyAt()).isEqualTo(first.getReadyAt());
        assertThat(repeated.getPickedUpAt()).isEqualTo(first.getPickedUpAt()); assertThat(repeated.getNoShowAt()).isEqualTo(first.getNoShowAt());
        var after=store.findById(id).orElseThrow();
        assertThat(after.getItems()).usingRecursiveComparison().isEqualTo(before.getItems());
        assertThat(after.getTrackingTokenHash()).isEqualTo(before.getTrackingTokenHash());
        assertThat(after.getSubtotal()).isEqualTo(before.getSubtotal()); assertThat(after.getTax()).isEqualTo(before.getTax());
        assertThat(after.getTotal()).isEqualTo(before.getTotal());
    }
    @Test void invalidAndMissingRequestsKeep400And404Contracts() throws Exception {
        mvc.perform(patch("/api/kds/orders/missing/status").contentType("application/json").content("{\"status\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/kds/orders/missing/status").contentType("application/json").content("{\"status\":\"READY\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/kds/orders/missing/ready-time").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/kds/orders/missing/messages").contentType("application/json").content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/kds/orders/missing/messages").contentType("application/json").content("{\"text\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }
    @Test void readyMinutesClampWithoutOverflowAndKeepFallbacks() throws Exception {
        String id=create().getOrder().getId();
        for (String number : List.of("999999999999999999999999","\"99999999999999999999999\""))
            mvc.perform(patch("/api/kds/orders/"+id+"/ready-time").contentType("application/json").content("{\"averageReadyMinutes\":"+number+"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("averageReadyMinutes").value(120));
        assertThat(kds.updateReadyTime(id,Map.of("averageReadyMinutes",Long.MIN_VALUE)).getBody().getAverageReadyMinutes()).isEqualTo(5);
        assertThat(kds.updateReadyTime(id,Map.of("averageReadyMinutes","invalid")).getBody().getAverageReadyMinutes()).isEqualTo(25);
        assertThat(kds.updateStatus(id,Map.of("status","READY","averageReadyMinutes","60")).getBody().getAverageReadyMinutes()).isEqualTo(60);
        assertThat(kds.updateStatus(id,Map.of("status","NEW","averageReadyMinutes","invalid")).getBody().getAverageReadyMinutes()).isEqualTo(60);
    }
    @Test void thresholdCountsAndTransitionAwayReconcileAtomically() {
        var ids=List.of(create().getOrder().getId(),create().getOrder().getId(),create().getOrder().getId());
        assertThat(counter()).isZero();
        for(int i=0;i<3;i++) {
            transition(ids.get(i),"NO_SHOW"); assertThat(counter()).isEqualTo(i+1);
            assertThat(noShows.isPickupDisabledForCustomer("OWNER@EXAMPLE.COM",null)).isEqualTo(i==2);
        }
        assertThat(controller.createOrder(request()).getStatusCode().value()).isEqualTo(403);
        LocalDateTime first=store.findById(ids.get(0)).orElseThrow().getNoShowAt();
        transition(ids.get(0),"READY"); assertThat(counter()).isEqualTo(2);
        assertThat(noShows.isPickupDisabledForCustomer("owner@example.com",null)).isFalse();
        transition(ids.get(0),"NO_SHOW"); assertThat(counter()).isEqualTo(3);
        assertThat(store.findById(ids.get(0)).orElseThrow().getNoShowAt()).isEqualTo(first);
        var user=users.findById("owner").orElseThrow(); assertThat(user.getPassword()).isEqualTo("unchanged-password-hash");
        assertThat(user.getTokenVersion()).isEqualTo(7); assertThat(user.getRole()).isEqualTo("MEMBER");
    }
    @Test void duplicateNoShowAndIndependentOrdersSerializeWithoutDoubleCounting() throws Exception {
        String a=create().getOrder().getId(), b=create().getOrder().getId();
        race(() -> transition(a,"NO_SHOW"),() -> transition(a,"NO_SHOW")); assertThat(counter()).isEqualTo(1);
        race(() -> transition(a,"NO_SHOW"),() -> transition(b,"NO_SHOW")); assertThat(counter()).isEqualTo(2);
        race(() -> transition(a,"READY"),() -> transition(b,"PICKED_UP")); assertThat(counter()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders WHERE status='NO_SHOW'",Long.class)).isZero();
    }
    @Test void noShowRacingWithReadyOrPickedUpEndsWithMatchingCounter() throws Exception {
        String id=create().getOrder().getId();
        for (String next:List.of("READY","PICKED_UP")) {
            race(() -> transition(id,"NO_SHOW"),() -> transition(id,next));
            var finalOrder=store.findById(id).orElseThrow();
            assertThat(counter()).isEqualTo("NO_SHOW".equals(finalOrder.getStatus()) ? 1 : 0);
            assertThat(finalOrder.getNoShowAt()).isNotNull();
        }
    }
    @Test void staleCountersAreReconciledWithoutCreatingGuestUsers() {
        String id=create().getOrder().getId(); var user=users.findById("owner").orElseThrow(); user.setNoShowCount(99); users.saveAndFlush(user);
        assertThat(noShows.isPickupDisabledForCustomer("owner@example.com",null)).isTrue(); // preserved fail-closed cached-counter policy
        transition(id,"NO_SHOW"); assertThat(counter()).isEqualTo(1); transition(id,"READY"); assertThat(counter()).isZero();
        var guest=request(); guest.setCustomerEmail("guest@example.com"); guest.setCustomerPhone("5195559999");
        var created=(OrderCreationResponse)controller.createOrder(guest).getBody(); transition(created.getOrder().getId(),"NO_SHOW");
        assertThat(users.count()).isEqualTo(1); assertThat(counter()).isZero();
    }
    @Test void emailLookupWinsAndPhoneFallbackUsesExistingCanonicalCountPolicy() {
        var phoneUser=new JpaUser("phone-owner","Phone","","+1 (519) 555-0000","MEMBER"); phoneUser.setPhoneNormalized("5195550000"); users.saveAndFlush(phoneUser);
        assertThat(noShows.findCustomer("owner@example.com",phoneUser.getPhone()).orElseThrow().getId()).isEqualTo("owner");
        var request=request(); request.setCustomerEmail("unmatched@example.com"); request.setCustomerPhone(phoneUser.getPhone());
        var created=(OrderCreationResponse)controller.createOrder(request).getBody(); transition(created.getOrder().getId(),"NO_SHOW");
        assertThat(users.findById("phone-owner").orElseThrow().getNoShowCount()).isEqualTo(1);
        assertThat(counter()).isZero();
    }
    @Test void failedCounterUpdateRollsBackStatusTimestampAndEvents() {
        String id=create().getOrder().getId(); reset(sockets);
        jdbc.execute("CREATE FUNCTION reject_no_show() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled counter failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_no_show BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION reject_no_show()");
        try {
            assertThatThrownBy(() -> kds.updateStatus(id,Map.of("status","NO_SHOW"))).isInstanceOf(RuntimeException.class);
            var loaded=store.findById(id).orElseThrow(); assertThat(loaded.getStatus()).isEqualTo("NEW"); assertThat(loaded.getNoShowAt()).isNull();
            assertThat(counter()).isZero(); verify(sockets.getRoomOperations("kds"),never()).sendEvent(anyString(),any());
        } finally { jdbc.execute("DROP TRIGGER reject_no_show ON users"); jdbc.execute("DROP FUNCTION reject_no_show()"); }
    }
    @Test void customerAndKitchenMessagesAppendToSameOrderedHistory() throws Exception {
        var created=create(); String id=created.getOrder().getId();
        race(() -> assertThat(controller.addCustomerMessage(id,null,created.getTrackingToken(),Map.of("text"," customer ")).getStatusCode().value()).isEqualTo(200),
                () -> assertThat(kds.addKdsMessage(id,Map.of("text"," kitchen ")).getStatusCode().value()).isEqualTo(200));
        var messages=store.findById(id).orElseThrow().getMessages();
        assertThat(messages).extracting(CustomerOrder.OrderMessage::getSenderRole).containsExactlyInAnyOrder("CUSTOMER","KDS");
        assertThat(messages).extracting(CustomerOrder.OrderMessage::getText).containsExactlyInAnyOrder("customer","kitchen");
        assertThat(messages).extracting(CustomerOrder.OrderMessage::getId).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT item_index FROM order_messages WHERE order_id=? ORDER BY item_index",Integer.class,id)).containsExactly(0,1);
        var kitchen=messages.stream().filter(m -> "KDS".equals(m.getSenderRole())).findFirst().orElseThrow();
        assertThat(kitchen.getSenderName()).isEqualTo("Kitchen"); assertThat(kitchen.getCreatedAt()).isNotNull();
        transition(id,"READY"); kds.updateReadyTime(id,Map.of("averageReadyMinutes",75));
        assertThat(store.findById(id).orElseThrow().getMessages()).hasSize(2);
    }
    @Test void readyTimeAndCustomerReadRacesKeepCommittedState() throws Exception {
        var created=create(); String id=created.getOrder().getId();
        race(() -> kds.updateReadyTime(id,Map.of("averageReadyMinutes",5)),() -> kds.updateReadyTime(id,Map.of("averageReadyMinutes",120)));
        assertThat(store.findById(id).orElseThrow().getAverageReadyMinutes()).isIn(5,120);
        race(() -> transition(id,"READY"),() -> assertThat(controller.getOrder(id,null,created.getTrackingToken()).getStatusCode().value()).isEqualTo(200));
        assertThat(store.findById(id).orElseThrow().getStatus()).isEqualTo("READY");
    }
    @Test void twoKitchenMessagesKeepEarlierCustomerHistoryAndHttpShape() throws Exception {
        var created=create(); String id=created.getOrder().getId();
        var original=controller.addCustomerMessage(id,null,created.getTrackingToken(),Map.of("text","original")).getBody();
        race(() -> kds.addKdsMessage(id,Map.of("text","left")),() -> kds.addKdsMessage(id,Map.of("text","right")));
        var messages=store.findById(id).orElseThrow().getMessages();
        assertThat(messages).hasSize(3); assertThat(messages.getFirst().getId()).isEqualTo(original.getId());
        assertThat(messages).extracting(CustomerOrder.OrderMessage::getText).containsExactlyInAnyOrder("original","left","right");
        assertThat(messages).extracting(CustomerOrder.OrderMessage::getId).doesNotHaveDuplicates();
        mvc.perform(post("/api/kds/orders/"+id+"/messages").contentType("application/json").content("{\"text\":\" HTTP \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("senderRole").value("KDS"))
                .andExpect(jsonPath("senderName").value("Kitchen")).andExpect(jsonPath("text").value("HTTP"))
                .andExpect(jsonPath("id").isString()).andExpect(jsonPath("createdAt").isString());
    }
    @Test void messageInsertFailureKeepsHistoryAndEmitsNothing() {
        var created=create(); String id=created.getOrder().getId();
        controller.addCustomerMessage(id,null,created.getTrackingToken(),Map.of("text","original")); reset(sockets);
        jdbc.execute("CREATE FUNCTION reject_kitchen_message() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled message failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_kitchen_message BEFORE INSERT ON order_messages FOR EACH ROW EXECUTE FUNCTION reject_kitchen_message()");
        try {
            assertThatThrownBy(() -> kds.addKdsMessage(id,Map.of("text","failed"))).isInstanceOf(RuntimeException.class);
            assertThat(store.findById(id).orElseThrow().getMessages()).extracting(CustomerOrder.OrderMessage::getText).containsExactly("original");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_messages WHERE order_id=?",Long.class,id)).isEqualTo(1L);
            verify(sockets.getRoomOperations("kds"),never()).sendEvent(anyString(),any());
        } finally { jdbc.execute("DROP TRIGGER reject_kitchen_message ON order_messages"); jdbc.execute("DROP FUNCTION reject_kitchen_message()"); }
    }
    @Test void uncommittedKitchenMutationHoldsSharedOrderLockAndDoesNotNotify() throws Exception {
        var created=create(); String id=created.getOrder().getId(); reset(sockets);
        var flushed=new CountDownLatch(1); var release=new CountDownLatch(1); var callbacks=new AtomicInteger();
        var delayed=mock(JpaCustomerOrderRepository.class,delegatesTo(orders));
        doAnswer(call -> { orders.flush(); flushed.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return null; }).when(delayed).flush();
        var kitchen=new PostgresKdsOrderStore(delayed,kitchenStore.getNoShowStore(),presentation,
                context.getBean("orderJpaTransactionManager",JpaTransactionManager.class),clock);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(() -> kitchen.readyTime(id,99,saved -> callbacks.incrementAndGet()));
            assertThat(flushed.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(callbacks).hasValue(0);
            assertThat(jdbc.queryForObject("SELECT average_ready_minutes FROM customer_orders WHERE id=?",Integer.class,id)).isEqualTo(25);
            var second=pool.submit(() -> controller.addCustomerMessage(id,null,created.getTrackingToken(),Map.of("text","waited")));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked=false;
            while (System.nanoTime()<deadline) {
                blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)>0;
                if (blocked) break; Thread.sleep(20);
            }
            assertThat(blocked).as("customer chat waits for KDS parent lock").isTrue();
            assertThat(second.isDone()).isFalse(); verify(sockets.getRoomOperations("kds"),never()).sendEvent(anyString(),any());
            release.countDown(); assertThat(first.get(10,TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(10,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200); assertThat(callbacks).hasValue(1);
            var persisted=store.findById(id).orElseThrow(); assertThat(persisted.getAverageReadyMinutes()).isEqualTo(99);
            assertThat(persisted.getMessages()).extracting(CustomerOrder.OrderMessage::getText).containsExactly("waited");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void listBatchLoadsChildrenAndCachesCountsPerIdentity() {
        for (int i=0;i<12;i++) create();
        var statistics=context.getBean(EntityManagerFactory.class).unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true); statistics.clear();
        var result=kds.getOrders(null); assertThat(result).hasSize(12);
        assertThat(result).allSatisfy(order -> { assertThat(order.getItems()).hasSize(1); assertThat(order.getCustomerOrderCount()).isEqualTo(12); });
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(4); statistics.setStatisticsEnabled(false);
    }
    @Test void postgresAllowlistAndProxyRulesFailClosed() throws Exception {
        allow(""); mvc.perform(get("/api/kds/access")).andExpect(status().isNotFound());
        settings.deleteById("default"); assertThat(kdsAccess.isIpAllowed("127.0.0.1")).isFalse();
        var row=new JpaStoreSettings(); row.setKdsAllowedIp("10.0.0.0/8;::1"); settings.saveAndFlush(row);
        assertThat(kdsAccess.isIpAllowed("10.2.3.4")).isTrue(); assertThat(kdsAccess.isIpAllowed("11.0.0.1")).isFalse();
        assertThat(kdsAccess.isIpAllowed("127.0.0.1")).isTrue();
        mvc.perform(get("/api/kds/access").with(req -> { req.setRemoteAddr("198.51.100.3"); req.addHeader("X-Forwarded-For","10.2.3.4"); return req; }))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/kds/access").with(req -> { req.setRemoteAddr("127.0.0.1"); req.addHeader("X-Forwarded-For","10.2.3.4"); return req; }))
                .andExpect(status().isOk());
        allow("0.0.0.0/-1"); assertThat(kdsAccess.isIpAllowed("10.1.2.3")).isFalse();
        assertThat(KdsAccessService.parseAllowedIps("1.1.1.1;2.2.2.2;3.3.3.3;4.4.4.4")).hasSize(3);

    }
    @Test void realtimeUsesExpectedRoomsAndCommittedPayloadWithoutDuplicates() {
        String id=create().getOrder().getId(); reset(sockets);
        var saved=transition(id,"READY");
        verify(sockets.getRoomOperations("kds"),times(1)).sendEvent(eq("order.updated"),any(CustomerOrder.class));
        verify(sockets.getRoomOperations("order:"+id),times(1)).sendEvent(eq("order.updated"),any(CustomerOrder.class));
        assertThat(saved.getCustomerOrderCount()).isEqualTo(1);
        var message=kds.addKdsMessage(id,Map.of("text","hello")).getBody();
        verify(sockets.getRoomOperations("kds"),times(1)).sendEvent("chat.message",Map.of("orderId",id,"message",message));
        verify(sockets.getRoomOperations("order:"+id),times(1)).sendEvent("chat.message",Map.of("orderId",id,"message",message));
        verify(sockets.getRoomOperations("kds"),times(2)).sendEvent(eq("order.updated"),any(CustomerOrder.class));
    }
    @Test void callbackSeesCommittedStatusFromSeparateConnection() {
        String id=create().getOrder().getId(); var callbacks=new AtomicInteger();
        kitchenStore.status(id,"READY",value -> value,saved -> {
            try (var c=DriverManager.getConnection(jdbcUrl,"postgres","postgres"); var query=c.prepareStatement("SELECT status FROM customer_orders WHERE id=?")) {
                query.setString(1,id); try(var result=query.executeQuery()) { result.next(); assertThat(result.getString(1)).isEqualTo("READY"); }
            } catch (SQLException failure) { throw new AssertionError(failure); }
            callbacks.incrementAndGet();
        });
        assertThat(callbacks).hasValue(1);
    }
    @Test void deferredCommitFailureDropsNotificationAndCounter() {
        String id=create().getOrder().getId(); reset(sockets);
        jdbc.execute("CREATE FUNCTION reject_kds_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled commit failure'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_kds_commit AFTER UPDATE ON customer_orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_kds_commit()");
        try {
            assertThatThrownBy(() -> transition(id,"NO_SHOW")).isInstanceOf(RuntimeException.class);
            assertThat(store.findById(id).orElseThrow().getStatus()).isEqualTo("NEW"); assertThat(counter()).isZero();
            verify(sockets.getRoomOperations("kds"),never()).sendEvent(anyString(),any());
        } finally { jdbc.execute("DROP TRIGGER reject_kds_commit ON customer_orders"); jdbc.execute("DROP FUNCTION reject_kds_commit()"); }
    }
    @Test void socketFailureDoesNotFailCommittedStatusReadyTimeOrMessage() {
        String id=create().getOrder().getId(); var room=sockets.getRoomOperations("kds");
        doThrow(new IllegalStateException("socket unavailable")).when(room).sendEvent(anyString(),any());
        assertThat(kds.updateStatus(id,Map.of("status","READY")).getStatusCode().value()).isEqualTo(200);
        assertThat(kds.updateReadyTime(id,Map.of("averageReadyMinutes",90)).getStatusCode().value()).isEqualTo(200);
        assertThat(kds.addKdsMessage(id,Map.of("text","saved")).getStatusCode().value()).isEqualTo(200);
        var persisted=store.findById(id).orElseThrow(); assertThat(persisted.getStatus()).isEqualTo("READY");
        assertThat(persisted.getAverageReadyMinutes()).isEqualTo(90); assertThat(persisted.getMessages()).hasSize(1);
    }
}
