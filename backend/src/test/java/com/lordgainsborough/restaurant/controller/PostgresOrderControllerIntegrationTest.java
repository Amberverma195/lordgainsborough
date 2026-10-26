package com.lordgainsborough.restaurant.controller;

import com.corundumstudio.socketio.SocketIOServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.config.PostgresOrderPersistenceConfiguration;
import com.lordgainsborough.restaurant.dto.*;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
class PostgresOrderControllerIntegrationTest {
    private String database, adminUrl;
    private boolean owned;
    private static String jdbcUrl;
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
    private CustomerOrderPresentationService mongoPresentation;
    private UserRepository mongoUsers;
    private OrderAccessService access;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackages = "com.lordgainsborough.restaurant.persistence.jpa.repository")
    static class JpaConfig {
        @Bean PGSimpleDataSource dataSource() {
            PGSimpleDataSource ds = new PGSimpleDataSource();
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
        boolean mandatory = Boolean.getBoolean("test.postgres.mandatory") || "true".equalsIgnoreCase(System.getenv("CI"));
        if (mandatory) assertThat(port).as("mandatory live PostgreSQL port").matches("[0-9]{1,5}");
        Assumptions.assumeTrue(port != null, "Set test.postgres.port for live PostgreSQL");
        database = "pg_order_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("pg_order_test_[a-f0-9]{32}");
        adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
        try (Connection c = DriverManager.getConnection(adminUrl, "postgres", "postgres"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE " + database); owned = true;
        }
        jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + database + "?sslmode=disable";
        Flyway.configure().dataSource(jdbcUrl, "postgres", "postgres").cleanDisabled(true).load().migrate();
        sockets = mock(SocketIOServer.class, RETURNS_DEEP_STUBS);
        mongoPresentation = mock(CustomerOrderPresentationService.class);
        mongoUsers = mock(UserRepository.class);
        access = new OrderAccessService(mock(JwtUtil.class), mongoUsers);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("test");
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "pg017", Map.of("app.orders.postgres.enabled", "true")));
        context.registerBean(OrderRealtimeService.class, () -> new OrderRealtimeService(sockets, mongoPresentation));
        context.registerBean(OrderAccessService.class, () -> access);
        context.register(JpaConfig.class, PostgresOrderPersistenceConfiguration.class, OrderController.class);
        context.refresh();
        store = context.getBean(PostgresCustomerOrderStore.class);
        controller = context.getBean(OrderController.class);
        orders = context.getBean(JpaCustomerOrderRepository.class);
        menu = context.getBean(JpaMenuItemRepository.class);
        users = context.getBean(JpaUserRepository.class);
        settings = context.getBean(JpaStoreSettingsRepository.class);
        jdbc = new JdbcTemplate(context.getBean(PGSimpleDataSource.class));
        transactions = new TransactionTemplate(context.getBean("orderJpaTransactionManager", JpaTransactionManager.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterAll void dispose() throws Exception {
        if (context != null) context.close();
        if (owned) {
            assertThat(database).matches("pg_order_test_[a-f0-9]{32}");
            try (Connection c = DriverManager.getConnection(adminUrl, "postgres", "postgres"); Statement s = c.createStatement()) {
                s.executeUpdate("DROP DATABASE " + database + " WITH (FORCE)"); owned = false;
            }
        }
    }

    @BeforeEach void resetFixture() {
        jdbc.execute("TRUNCATE customer_orders, menu_items, users, store_settings CASCADE");
        settings.saveAndFlush(new JpaStoreSettings());
        menu.saveAndFlush(new JpaMenuItem("dish", "Steak", "", new BigDecimal("17.99"), "Mains", null));
        clearInvocations(sockets.getRoomOperations("kds"), sockets);
        reset(mongoUsers, mongoPresentation);
    }

    private CustomerOrder request() {
        CustomerOrder order = new CustomerOrder();
        order.setCustomerName("Guest"); order.setCustomerEmail("guest@example.com"); order.setCustomerPhone("5195551212");
        order.setPickupDate("2026-11-01"); order.setPickupTime("18:30");
        order.getItems().add(item("dish", "Forged", 2)); return order;
    }
    private CustomerOrder.OrderItem item(String id, String name, int quantity) {
        var item = new CustomerOrder.OrderItem(); item.setMenuItemId(id); item.setName(name);
        item.setQuantity(quantity); item.setUnitPrice(0.01); item.setTotal(-9); item.setNotes("No salt"); return item;
    }
    private OrderCreationResponse create() {
        var response = controller.createOrder(request()); assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (OrderCreationResponse) response.getBody();
    }
    private void error(CustomerOrder order, int status, String code, String message) {
        var response = controller.createOrder(order);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(Map.of("code", code, "message", message));
        assertThat(orders.count()).isZero();
    }

    static java.util.stream.Stream<Arguments> missingCustomerDetails() {
        return java.util.stream.Stream.of("customerName", "customerEmail", "customerPhone")
                .flatMap(field -> java.util.stream.Stream.of(
                        Arguments.of(field, null, true), Arguments.of(field, null, false),
                        Arguments.of(field, "", false), Arguments.of(field, " \t ", false)));
    }

    @ParameterizedTest
    @MethodSource("missingCustomerDetails")
    void missingContactFieldReturnsConsistentHttp400WithoutWriting(String field, String value, boolean omit) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = json.valueToTree(request());
        if (omit) body.remove(field);
        else if (value == null) body.putNull(field);
        else body.put(field, value);
        String response = mvc.perform(post("/api/orders").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response)).isEqualTo(json.valueToTree(Map.of(
                "code", "MISSING_CUSTOMER_DETAILS", "message", "Customer name, email and phone are required.")));
        assertThat(orders.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_messages", Long.class)).isZero();
        verify(sockets.getRoomOperations("kds"), never()).sendEvent(anyString(), any());
    }

    @Test void validCreationRepricesAndReloadsOrderedChildrenWithoutUserAssociation() {
        var request = request(); request.getItems().add(item("dish", "Other forgery", 3));
        request.setSubtotal(-5); request.setTax(Double.NaN); request.setTotal(Double.POSITIVE_INFINITY);
        var response = (OrderCreationResponse) controller.createOrder(request).getBody();
        String id = response.getOrder().getId();
        for (int i = 0; i < 3; i++) {
            CustomerOrder loaded = store.findById(id).orElseThrow();
            assertThat(loaded.getItems()).extracting(CustomerOrder.OrderItem::getQuantity).containsExactly(2, 3);
            assertThat(loaded.getItems()).extracting(CustomerOrder.OrderItem::getName).containsExactly("Steak", "Steak");
            assertThat(loaded.getItems()).extracting(CustomerOrder.OrderItem::getNotes).containsExactly("No salt", "No salt");
            assertThat(loaded.getSubtotal()).isEqualTo(89.95); assertThat(loaded.getTax()).isEqualTo(11.69);
            assertThat(loaded.getTotal()).isEqualTo(101.64); assertThat(loaded.getMessages()).isEmpty();
        }
        assertThat(users.count()).isZero();
        assertThat(jdbc.queryForList("SELECT item_index FROM order_items WHERE order_id=? ORDER BY item_index", Integer.class, id))
                .containsExactly(0, 1);
        assertThat(jdbc.queryForObject("SELECT subtotal FROM customer_orders WHERE id=?", BigDecimal.class, id))
                .isEqualByComparingTo("89.95");
        verifyNoInteractions(mongoUsers, mongoPresentation);
    }

    @Test void resetsAllPrivilegedStateAndBoundsInstructionsAndReadyTime() {
        var settingsRow = settings.findById("default").orElseThrow(); settingsRow.setDefaultReadyMinutes(999); settings.saveAndFlush(settingsRow);
        var order = request(); order.setId("attacker"); order.setStatus("PICKED_UP"); order.setReviewed(true);
        order.setCreatedAt(LocalDateTime.of(2000,1,1,0,0)); order.setAcceptedAt(order.getCreatedAt());
        order.setReadyAt(order.getCreatedAt()); order.setPickedUpAt(order.getCreatedAt()); order.setNoShowAt(order.getCreatedAt());
        order.setTrackingTokenHash("attacker"); order.getMessages().add(new CustomerOrder.OrderMessage());
        order.setSpecialInstructions("  " + "x".repeat(501) + "  "); order.setPickupDate(" ");
        var result = (OrderCreationResponse) controller.createOrder(order).getBody();
        var saved = store.findById(result.getOrder().getId()).orElseThrow();
        assertThat(saved.getId()).isNotEqualTo("attacker"); assertThat(saved.getStatus()).isEqualTo("NEW");
        assertThat(saved.isReviewed()).isFalse(); assertThat(saved.getMessages()).isEmpty();
        assertThat(saved.getCreatedAt()).isAfter(LocalDateTime.of(2026,1,1,0,0));
        assertThat(saved.getAcceptedAt()).isNull(); assertThat(saved.getReadyAt()).isNull();
        assertThat(saved.getPickedUpAt()).isNull(); assertThat(saved.getNoShowAt()).isNull();
        assertThat(saved.getSpecialInstructions()).hasSize(500); assertThat(saved.getAverageReadyMinutes()).isEqualTo(120);
        assertThat(saved.getPickupDate()).isEqualTo(java.time.LocalDate.now().toString());
        assertThat(saved.getTrackingTokenHash()).isEqualTo(access.hashTrackingToken(result.getTrackingToken()));
    }

    @Test void lowerReadyBoundaryAndBlankInstructionsAndQuantityNormalization() {
        var row = settings.findById("default").orElseThrow(); row.setDefaultReadyMinutes(1); settings.saveAndFlush(row);
        var order = request(); order.setSpecialInstructions("  "); order.getItems().getFirst().setQuantity(-2);
        var result = (OrderCreationResponse) controller.createOrder(order).getBody();
        assertThat(result.getOrder().getAverageReadyMinutes()).isEqualTo(5);
        assertThat(result.getOrder().getSpecialInstructions()).isNull();
        assertThat(result.getOrder().getItems().getFirst().getQuantity()).isEqualTo(1);
        assertThat(result.getOrder().getTotal()).isEqualTo(20.33);
    }

    @Test void emptyCartPrecedesPickupAndNoShowChecks() {
        var row = settings.findById("default").orElseThrow(); row.setPickupOrdersEnabled(false); settings.saveAndFlush(row);
        error(new CustomerOrder(),400,"EMPTY_CART","Your cart is empty.");
    }
    @Test void pickupDisabledPrecedesMenuValidation() {
        var row = settings.findById("default").orElseThrow(); row.setPickupOrdersEnabled(false); settings.saveAndFlush(row);
        error(request(),503,"PICKUP_ORDERS_PAUSED","Pickup orders are currently paused. Please check back later.");
    }
    @Test void relationalUserNoShowCounterBlocksBeforeMenu() {
        JpaUser user = new JpaUser("member","Guest","guest@example.com","5195551212","MEMBER");
        user.setNoShowCount(3); users.saveAndFlush(user);
        error(request(),403,"PICKUP_DISABLED_NO_SHOW",CustomerNoShowService.PICKUP_DISABLED_MESSAGE);
        verifyNoInteractions(mongoUsers);
    }
    @Test void relationalOrderNoShowCountsCannotBeBypassedByStaleUserCounter() {
        users.saveAndFlush(new JpaUser("member","Guest","guest@example.com","5195551212","MEMBER"));
        for (int i=0;i<3;i++) { var order = new JpaCustomerOrder(null,"Guest","guest@example.com","5195551212","2026-01-01");
            order.setStatus("NO_SHOW"); orders.saveAndFlush(order); }
        assertThat(controller.createOrder(request()).getStatusCode().value()).isEqualTo(403);
    }
    @Test void noShowLookupUsesNormalizedPhoneFallback() {
        var user = new JpaUser("member","Guest","other@example.com","+1 (519) 555-1212","MEMBER");
        user.setPhoneNormalized("5195551212"); user.setNoShowCount(3); users.saveAndFlush(user);
        error(request(),403,"PICKUP_DISABLED_NO_SHOW",CustomerNoShowService.PICKUP_DISABLED_MESSAGE);
    }
    @Test void unknownItemRetainsExactErrorContract() {
        var order=request(); order.setItems(List.of(item("unknown","Unknown",1)));
        error(order,400,"CART_ITEM_CHANGED","An item in your cart is no longer on the menu. Please remove it and try again.");
    }
    @Test void unavailableItemRetainsExactErrorContract() {
        var row=menu.findById("dish").orElseThrow(); row.setAvailable(false); menu.saveAndFlush(row);
        error(request(),400,"CART_ITEM_UNAVAILABLE","Steak is currently unavailable. Please remove it and try again.");
    }
    @Test void staleIdUsesTrimmedCaseInsensitiveUnambiguousName() {
        var order=request(); order.setItems(List.of(item("stale"," sTeAk ",1)));
        var response=(OrderCreationResponse)controller.createOrder(order).getBody();
        assertThat(response.getOrder().getItems().getFirst().getMenuItemId()).isEqualTo("dish");
    }
    @Test void duplicateNameFallbackFailsClosedEvenIfOneProductUnavailable() {
        menu.saveAndFlush(new JpaMenuItem("cheaper","STEAK","",new BigDecimal("1.00"),"Mains",null,false,false));
        var order=request(); order.setItems(List.of(item("stale","steak",1)));
        error(order,400,"CART_ITEM_CHANGED","An item in your cart is no longer on the menu. Please remove it and try again.");
    }
    @Test void validIdWinsOverDuplicateName() {
        menu.saveAndFlush(new JpaMenuItem("cheaper","STEAK","",new BigDecimal("1.00"),"Mains",null));
        assertThat(create().getOrder().getSubtotal()).isEqualTo(35.98);
    }
    @Test void staleIdFallbackStillRejectsUnavailableProduct() {
        var row=menu.findById("dish").orElseThrow(); row.setAvailable(false); menu.saveAndFlush(row);
        var order=request(); order.setItems(List.of(item("stale","steak",1)));
        error(order,400,"CART_ITEM_UNAVAILABLE","Steak is currently unavailable. Please remove it and try again.");
    }
    @Test void nullItemFailsWithCartChangeContract() {
        var order=request(); order.setItems(Arrays.asList((CustomerOrder.OrderItem)null));
        error(order,400,"CART_ITEM_CHANGED","An item in your cart is no longer on the menu. Please remove it and try again.");
    }
    @Test void httpCreationPreservesEnvelopeAndStatusFields() throws Exception {
        var response=mvc.perform(post("/api/orders").contentType("application/json").content(json.writeValueAsString(request())))
                .andExpect(status().isOk()).andExpect(jsonPath("order.status").value("NEW"))
                .andExpect(jsonPath("order.items[0].unitPrice").value(17.99))
                .andExpect(jsonPath("trackingToken").isString()).andExpect(jsonPath("order.trackingTokenHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var root=json.readTree(response); assertThat(root.size()).isEqualTo(2);
        var names=new HashSet<String>(); root.get("order").fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id","pickupDate","pickupTime","specialInstructions","status",
                "averageReadyMinutes","items","messages","subtotal","tax","total","createdAt","acceptedAt","readyAt","pickedUpAt","reviewed");
    }
    @Test void taxRoundingUsesConfiguredDecimalRate() {
        var row=menu.findById("dish").orElseThrow(); row.setPrice(new BigDecimal("0.05")); menu.saveAndFlush(row);
        var tax=settings.findById("default").orElseThrow(); tax.setTaxRate(new BigDecimal("0.1500")); settings.saveAndFlush(tax);
        var order=request(); order.getItems().getFirst().setQuantity(1);
        var result=(OrderCreationResponse)controller.createOrder(order).getBody();
        assertThat(result.getOrder().getTax()).isEqualTo(0.01); assertThat(result.getOrder().getTotal()).isEqualTo(0.06);
    }
    @Test void overflowRejectedBeforeAnyWrite() {
        var order=request(); order.getItems().getFirst().setQuantity(Integer.MAX_VALUE);
        error(order,400,"CART_ITEM_CHANGED","An item in your cart is no longer on the menu. Please remove it and try again.");
    }
    @Test void grandTotalOverflowRejected() {
        var row=menu.findById("dish").orElseThrow(); row.setPrice(new BigDecimal("99999999.99")); menu.saveAndFlush(row);
        var order=request(); order.getItems().getFirst().setQuantity(1);
        error(order,400,"CART_ITEM_CHANGED","An item in your cart is no longer on the menu. Please remove it and try again.");
    }
    @Test void guestGetAndPrivacyPreserving404OverHttp() throws Exception {
        var response=create(); String id=response.getOrder().getId();
        mvc.perform(get("/api/orders/"+id).header(OrderAccessService.TRACKING_TOKEN_HEADER,response.getTrackingToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("id").value(id)).andExpect(jsonPath("trackingTokenHash").doesNotExist())
                .andExpect(jsonPath("customerEmail").doesNotExist()).andExpect(jsonPath("trackingToken").doesNotExist());
        mvc.perform(get("/api/orders/"+id)).andExpect(status().isNotFound()).andExpect(content().string(""));
        mvc.perform(get("/api/orders/missing")).andExpect(status().isNotFound()).andExpect(content().string(""));
    }
    @Test void legacyJwtCannotAuthorizePostgresOrder() {
        var response=create();
        assertThat(controller.getOrder(response.getOrder().getId(),"Bearer valid-looking-legacy-token",null).getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(mongoUsers);
    }
    @Test void rawTokenOnlyInCreationEnvelopeAndHashOnlyInDatabase() throws Exception {
        var response=create(); var saved=store.findById(response.getOrder().getId()).orElseThrow();
        assertThat(Base64.getUrlDecoder().decode(response.getTrackingToken())).hasSize(32);
        assertThat(saved.getTrackingTokenHash()).isNotEqualTo(response.getTrackingToken());
        String status=json.writeValueAsString(response.getOrder()), event=json.writeValueAsString(saved);
        assertThat(status).doesNotContain(response.getTrackingToken(),saved.getTrackingTokenHash(),"trackingToken");
        assertThat(event).doesNotContain(response.getTrackingToken(),saved.getTrackingTokenHash(),"trackingToken");
        assertThat(json.writeValueAsString(response)).contains("trackingToken");
    }
    @Test void reviewStateUsesPostgresExistenceAndPreservesPersistedTrue() {
        var response=create(); String id=response.getOrder().getId();
        var repository=context.getBean(JpaOrderReviewRepository.class);
        repository.saveAndFlush(new JpaOrderReview(null,orders.findById(id).orElseThrow(),"Guest","guest@example.com","5195551212"));
        assertThat(controller.getOrder(id,null,response.getTrackingToken()).getBody().isReviewed()).isTrue();
        repository.deleteAll(); var order=orders.findById(id).orElseThrow(); order.setReviewed(true); orders.saveAndFlush(order);
        assertThat(controller.getOrder(id,null,response.getTrackingToken()).getBody().isReviewed()).isTrue();
    }
    @Test void messageValidationPrecedesLookup() throws Exception {
        mvc.perform(post("/api/orders/missing/messages").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/missing/messages").contentType("application/json").content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void unauthorizedMessageCannotInsertAndMissingIs404() {
        var response=create(); String id=response.getOrder().getId();
        assertThat(controller.addCustomerMessage(id,null,"wrong",Map.of("text","hi")).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.addCustomerMessage("missing",null,response.getTrackingToken(),Map.of("text","hi")).getStatusCode().value()).isEqualTo(404);
        assertThat(store.findById(id).orElseThrow().getMessages()).isEmpty();
    }
    @Test void messagesRoundTripWithGeneratedIdsSenderAndTimestamps() throws Exception {
        var response=create(); String id=response.getOrder().getId();
        var first=controller.addCustomerMessage(id,null,response.getTrackingToken(),Map.of("text","  First  ")).getBody();
        var second=controller.addCustomerMessage(id,null,response.getTrackingToken(),Map.of("text","Second")).getBody();
        assertThat(first.getId()).isNotBlank().isNotEqualTo(second.getId());
        assertThat(first.getSenderRole()).isEqualTo("CUSTOMER"); assertThat(first.getSenderName()).isEqualTo("Customer");
        assertThat(first.getCreatedAt()).isNotNull(); assertThat(first.getText()).isEqualTo("First");
        var reloaded=store.findById(id).orElseThrow();
        assertThat(reloaded.getMessages()).extracting(CustomerOrder.OrderMessage::getId).containsExactly(first.getId(),second.getId());
        mvc.perform(post("/api/orders/"+id+"/messages").header(OrderAccessService.TRACKING_TOKEN_HEADER,response.getTrackingToken())
                .contentType("application/json").content("{\"text\":\"Third\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("senderRole").value("CUSTOMER"));
    }
    @Test void simultaneousMessagesAppendWithoutLossOrDuplicates() throws Exception {
        var response=create(); String id=response.getOrder().getId(); var pool=Executors.newFixedThreadPool(2);
        var barrier=new CyclicBarrier(2);
        try {
            List<Future<CustomerOrder.OrderMessage>> futures=new ArrayList<>();
            for (String text:List.of("A","B")) futures.add(pool.submit(() -> {
                barrier.await(5,TimeUnit.SECONDS);
                return controller.addCustomerMessage(id,null,response.getTrackingToken(),Map.of("text",text)).getBody();
            }));
            var a=futures.get(0).get(15,TimeUnit.SECONDS); var b=futures.get(1).get(15,TimeUnit.SECONDS);
            var loaded=store.findById(id).orElseThrow();
            assertThat(loaded.getMessages()).extracting(CustomerOrder.OrderMessage::getId).containsExactlyInAnyOrder(a.getId(),b.getId());
            assertThat(loaded.getMessages()).extracting(CustomerOrder.OrderMessage::getText).containsExactlyInAnyOrder("A","B");
            assertThat(jdbc.queryForList("SELECT item_index FROM order_messages WHERE order_id=? ORDER BY item_index",Integer.class,id))
                    .containsExactly(0,1);
        } finally { pool.shutdownNow(); }
    }

    @Test void competingAppendWaitsForParentLockAndRetainsFirstCommit() throws Exception {
        var response=create(); String id=response.getOrder().getId();
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var delayed=mock(JpaCustomerOrderRepository.class, delegatesTo(orders));
        doAnswer(call -> {
            var row=orders.findForMessageAppend(call.getArgument(0)); locked.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return row;
        }).when(delayed).findForMessageAppend(id);
        var isolated=new PostgresCustomerOrderStore(delayed,menu,context.getBean(JpaOrderReviewRepository.class),
                settings,users,context.getBean("orderJpaTransactionManager",JpaTransactionManager.class));
        var first=new CustomerOrder.OrderMessage(); first.setId(UUID.randomUUID().toString());
        first.setSenderRole("CUSTOMER"); first.setSenderName("Customer"); first.setText("First"); first.setCreatedAt(LocalDateTime.now());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(() -> isolated.appendMessage(id,o -> true,first,o -> {}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(() -> controller.addCustomerMessage(id,null,response.getTrackingToken(),Map.of("text","Second")));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked=false;
            while (System.nanoTime()<deadline) {
                blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)>0;
                if (blocked) break;
                Thread.sleep(20);
            }
            assertThat(blocked).as("competing append is blocked by the PostgreSQL parent lock").isTrue();
            assertThat(b.isDone()).isFalse(); release.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isPresent(); assertThat(b.get(10,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(store.findById(id).orElseThrow().getMessages()).extracting(CustomerOrder.OrderMessage::getText).containsExactly("First","Second");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void childInsertFailureRollsBackWholeOrderAndEmitsNothing() {
        jdbc.execute("CREATE FUNCTION reject_order_item() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled child failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_order_item BEFORE INSERT ON order_items FOR EACH ROW EXECUTE FUNCTION reject_order_item()");
        try {
            assertThatThrownBy(() -> controller.createOrder(request())).isInstanceOf(RuntimeException.class);
            assertThat(orders.count()).isZero(); assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items",Long.class)).isZero();
            verify(sockets.getRoomOperations("kds"),never()).sendEvent(eq("order.created"),any());
        } finally { jdbc.execute("DROP TRIGGER reject_order_item ON order_items"); jdbc.execute("DROP FUNCTION reject_order_item()"); }
    }
    @Test void messageInsertFailurePreservesExistingHistoryAndEmitsNothing() {
        var response=create(); String id=response.getOrder().getId();
        controller.addCustomerMessage(id,null,response.getTrackingToken(),Map.of("text","existing"));
        clearInvocations(sockets.getRoomOperations("kds"));
        jdbc.execute("CREATE FUNCTION reject_order_message() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled message failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_order_message BEFORE INSERT ON order_messages FOR EACH ROW EXECUTE FUNCTION reject_order_message()");
        try {
            assertThatThrownBy(() -> controller.addCustomerMessage(id,null,response.getTrackingToken(),Map.of("text","failed")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(store.findById(id).orElseThrow().getMessages()).extracting(CustomerOrder.OrderMessage::getText).containsExactly("existing");
            verify(sockets.getRoomOperations("kds"),never()).sendEvent(eq("chat.message"),any());
        } finally { jdbc.execute("DROP TRIGGER reject_order_message ON order_messages"); jdbc.execute("DROP FUNCTION reject_order_message()"); }
    }
    @Test void deferredCommitFailureDiscardsAlreadyRegisteredNotification() {
        jdbc.execute("CREATE FUNCTION reject_order_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled commit failure'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_order_commit AFTER INSERT ON customer_orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_order_commit()");
        try {
            assertThatThrownBy(() -> controller.createOrder(request())).isInstanceOf(RuntimeException.class);
            assertThat(orders.count()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items",Long.class)).isZero();
            verify(sockets.getRoomOperations("kds"),never()).sendEvent(eq("order.created"),any());
        } finally { jdbc.execute("DROP TRIGGER reject_order_commit ON customer_orders"); jdbc.execute("DROP FUNCTION reject_order_commit()"); }
    }
    @Test void notificationsObserveCommittedRowsAndExactlyOneCallback() {
        AtomicInteger notifications=new AtomicInteger(); var order=request(); OrderMoney.calculate(order,0.13);
        store.create(order,saved -> {
            try (Connection connection=DriverManager.getConnection(jdbcUrl,"postgres","postgres");
                 PreparedStatement query=connection.prepareStatement("SELECT count(*) FROM customer_orders WHERE id=?")) {
                query.setString(1,saved.getId());
                try (ResultSet result=query.executeQuery()) { result.next(); assertThat(result.getLong(1)).isEqualTo(1); }
            } catch (SQLException failure) { throw new AssertionError(failure); }
            notifications.incrementAndGet();
        });
        assertThat(notifications).hasValue(1);
    }
    @Test void noEventWhileOrderWriteIsUncommitted() throws Exception {
        var flushed=new CountDownLatch(1); var release=new CountDownLatch(1);
        var delayed=mock(JpaCustomerOrderRepository.class, delegatesTo(orders));
        doAnswer(call -> {
            var saved=orders.saveAndFlush(call.getArgument(0)); flushed.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return saved;
        }).when(delayed).saveAndFlush(any(JpaCustomerOrder.class));
        var isolated=new PostgresCustomerOrderStore(delayed,menu,context.getBean(JpaOrderReviewRepository.class),
                settings,users,context.getBean("orderJpaTransactionManager",JpaTransactionManager.class));
        var order=request(); OrderMoney.calculate(order,0.13); var callbacks=new AtomicInteger();
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(() -> isolated.create(order,saved -> callbacks.incrementAndGet()));
            assertThat(flushed.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(callbacks).hasValue(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders",Long.class)).isZero();
            release.countDown(); pending.get(10,TimeUnit.SECONDS);
            assertThat(callbacks).hasValue(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void socketFailuresDoNotChangeSuccessfulHttpCreationOrMessage() {
        var room=sockets.getRoomOperations("kds");
        doThrow(new IllegalStateException("socket unavailable")).when(room).sendEvent(anyString(),any());
        try {
            var response=create();
            assertThat(controller.addCustomerMessage(response.getOrder().getId(),null,response.getTrackingToken(),Map.of("text","hello"))
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(store.findById(response.getOrder().getId()).orElseThrow().getMessages()).hasSize(1);
        } finally { reset(sockets.getRoomOperations("kds")); }
    }
    @Test void realtimeEventsKeepRoomsPayloadAndCountsWithoutMongoReads() {
        var response=create(); String id=response.getOrder().getId();
        var orderCaptor=org.mockito.ArgumentCaptor.forClass(CustomerOrder.class);
        verify(sockets.getRoomOperations("kds"),times(1)).sendEvent(eq("order.created"),orderCaptor.capture());
        verify(sockets.getRoomOperations("order:"+id),times(1)).sendEvent(eq("order.updated"),any());
        assertThat(orderCaptor.getValue().getCustomerOrderCount()).isEqualTo(1);
        assertThat(orderCaptor.getValue().getCustomerNoShowCount()).isZero();
        var message=controller.addCustomerMessage(id,null,response.getTrackingToken(),Map.of("text","hello")).getBody();
        verify(sockets.getRoomOperations("kds"),times(1)).sendEvent("chat.message",Map.of("orderId",id,"message",message));
        verify(sockets.getRoomOperations("order:"+id),times(1)).sendEvent("chat.message",Map.of("orderId",id,"message",message));
        verify(sockets.getRoomOperations("kds"),times(1)).sendEvent(eq("order.updated"),any());
        verifyNoInteractions(mongoPresentation,mongoUsers);
    }
}
