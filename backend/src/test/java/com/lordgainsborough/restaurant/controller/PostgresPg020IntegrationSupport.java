
package com.lordgainsborough.restaurant.controller;
import com.corundumstudio.socketio.SocketIOServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.config.*;
import com.lordgainsborough.restaurant.dto.*;
import com.lordgainsborough.restaurant.model.*;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.security.*;
import com.lordgainsborough.restaurant.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Owned database per subclass, real commits, and separate-connection assertions. No ambient transaction. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresPg020IntegrationSupport {
    private String database,adminUrl;
    private boolean owned;
    private String jdbcUrl;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    JpaCustomerOrderRepository orders;
    JpaOrderReviewRepository reviews;
    PostgresCustomerOrderStore checkout;
    PostgresReviewStore reviewStore;
    OrderController orderController;
    ReviewController reviewController;
    ReservationController reservationController;
    KdsController kds;
    SocketIOServer sockets;

    JwtUtil jwt;
    OrderAccessService access;
    MockMvc mvc;
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableJpaRepositories(basePackages="com.lordgainsborough.restaurant.persistence.jpa.repository")
    static class JpaConfig {
        @Bean PGSimpleDataSource dataSource(org.springframework.core.env.Environment environment) { var ds=new PGSimpleDataSource(); ds.setUrl(environment.getRequiredProperty("test.jdbc-url")); ds.setUser("postgres"); ds.setPassword("postgres"); return ds; }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(PGSimpleDataSource ds) {
            var factory=new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
            factory.setPackagesToScan("com.lordgainsborough.restaurant.persistence.jpa.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter()); factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate")); return factory;
        }
    }
    @BeforeAll void provision() throws Exception {
        String port=com.lordgainsborough.restaurant.support.LocalPostgres.requirePort();
        if (Boolean.getBoolean("test.postgres.mandatory") || "true".equalsIgnoreCase(System.getenv("CI"))) assertThat(port).matches("[0-9]{1,5}");
        Assumptions.assumeTrue(port!=null,"Set test.postgres.port for live PostgreSQL");
        database="pg020_test_"+UUID.randomUUID().toString().replace("-",""); assertThat(database).matches("pg020_test_[a-f0-9]{32}");
        adminUrl="jdbc:postgresql://127.0.0.1:"+port+"/postgres?sslmode=disable";
        try(var c=DriverManager.getConnection(adminUrl,"postgres","postgres");var stmt=c.createStatement()) { stmt.executeUpdate("CREATE DATABASE "+database);owned=true; }
        jdbcUrl="jdbc:postgresql://127.0.0.1:"+port+"/"+database+"?sslmode=disable";
        var v1=Flyway.configure().dataSource(jdbcUrl,"postgres","postgres").target("1").cleanDisabled(true).load(); v1.migrate();
        var v2=Flyway.configure().dataSource(jdbcUrl,"postgres","postgres").cleanDisabled(true).load();
        assertThat(v2.migrate().migrationsExecuted).isEqualTo(1); v2.validate();
        sockets=mock(SocketIOServer.class,RETURNS_DEEP_STUBS);  jwt=mock(JwtUtil.class);

        context=new AnnotationConfigApplicationContext();context.getEnvironment().setActiveProfiles("test");
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("pg020",Map.of(
            "app.orders.postgres.enabled","true","app.kds.postgres.enabled","true","app.reviews.postgres.enabled","true","app.reservations.postgres.enabled","true")));
        context.registerBean(Clock.class,Clock::systemDefaultZone);
        context.registerBean(JwtUtil.class,()->jwt);
        context.register(WebConfig.class,AdminAuthInterceptor.class);
        context.registerBean(OrderAccessService.class,()->new OrderAccessService(jwt,context.getBean(PostgresUserAuthStore.class)));
        context.registerBean(ClientIpResolver.class,()->new ClientIpResolver(new SignupAbuseProperties()));
        context.registerBean(OrderRealtimeService.class,()->new OrderRealtimeService(sockets,context.getBean(CustomerOrderPresentationService.class)));
        context.registerBean(PostgresUserAuthStore.class, () -> new PostgresUserAuthStore(context.getBean(JpaUserRepository.class),context.getBean(JpaCustomerOrderRepository.class),context.getBean(JpaOrderReviewRepository.class),context.getBean("orderJpaTransactionManager",org.springframework.orm.jpa.JpaTransactionManager.class),java.time.Clock.systemDefaultZone()));
        context.register(JpaConfig.class,PostgresOrderPersistenceConfiguration.class,PostgresKdsPersistenceConfiguration.class,
            PostgresReviewPersistenceConfiguration.class,PostgresReservationPersistenceConfiguration.class,
            OrderController.class,KdsController.class,ReviewController.class,ReservationController.class,
            CustomerNoShowService.class,CustomerOrderPresentationService.class,KdsAccessService.class);
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("jdbc", Map.of("test.jdbc-url",jdbcUrl,"jwt.expiration","86400000","jwt.secret","fixture-only-secret-at-least-32-bytes")));
        context.refresh(); access=context.getBean(OrderAccessService.class);jdbc=new JdbcTemplate(context.getBean(PGSimpleDataSource.class));
        orders=context.getBean(JpaCustomerOrderRepository.class);reviews=context.getBean(JpaOrderReviewRepository.class);
        checkout=context.getBean(PostgresCustomerOrderStore.class);reviewStore=context.getBean(PostgresReviewStore.class);
        orderController=context.getBean(OrderController.class);reviewController=context.getBean(ReviewController.class);
        reservationController=context.getBean(ReservationController.class);kds=context.getBean(KdsController.class);
        // Explicit administrator fixture exercises the unchanged interceptor, never production PG identity support.
        mvc=MockMvcBuilders.standaloneSetup(orderController,reviewController,reservationController,kds)
            .addInterceptors(context.getBean(AdminAuthInterceptor.class)).build();
    }
    @AfterAll void dispose() throws Exception {
        if(context!=null)context.close();
        if(owned) { assertThat(database).matches("pg020_test_[a-f0-9]{32}");
            try(var c=DriverManager.getConnection(adminUrl,"postgres","postgres");var s=c.createStatement()) {s.executeUpdate("DROP DATABASE "+database+" WITH (FORCE)");owned=false;}
        }
    }
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE customer_orders,users,menu_items,store_settings,reservations CASCADE");
        context.getBean(JpaStoreSettingsRepository.class).saveAndFlush(new JpaStoreSettings());
        context.getBean(JpaMenuItemRepository.class).saveAndFlush(new JpaMenuItem("dish","Steak","",new BigDecimal("17.99"),"Mains",null));
        reset(sockets,jwt);
        var dbAdmin=new JpaUser(); dbAdmin.setId("fixture-admin"); dbAdmin.setEmail("fixture-admin@example.com"); dbAdmin.setName("Admin"); dbAdmin.setPassword("unused"); dbAdmin.setRole("ADMIN"); context.getBean(JpaUserRepository.class).saveAndFlush(dbAdmin);
        when(jwt.validateTokenForIdentity("admin-fixture","fixture-admin",0L)).thenReturn(true);
        var admin=new User();admin.setId("fixture-admin");admin.setEmail("fixture-admin@example.com");admin.setRole("ADMIN");
        when(jwt.validateToken("admin-fixture")).thenReturn(true);when(jwt.getEmailFromToken("admin-fixture")).thenReturn(admin.getEmail());
        when(jwt.validateTokenForUser("admin-fixture",admin)).thenReturn(true);
    }
    OrderCreationResponse createOrder() {
        var order=new CustomerOrder();order.setCustomerName("Guest");order.setCustomerEmail("guest@example.com");order.setCustomerPhone("5195551212");
        var item=new CustomerOrder.OrderItem();item.setMenuItemId("dish");item.setQuantity(2);order.setItems(List.of(item));
        var response=orderController.createOrder(order);assertThat(response.getStatusCode().value()).isEqualTo(200);return (OrderCreationResponse)response.getBody();
    }
    OrderReview requestReview() {
        var review=new OrderReview();var item=new OrderReview.ReviewItem();item.setMenuItemId("dish");item.setItemName("client");item.setRating(4);item.setDescription(" good ");review.setItems(List.of(item));return review;
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    void race(Runnable a,Runnable b) throws Exception {
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {var x=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);a.run();return true;});var y=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);b.run();return true;});assertThat(x.get(15,TimeUnit.SECONDS)).isTrue();assertThat(y.get(15,TimeUnit.SECONDS)).isTrue();}
        finally {pool.shutdownNow();}
    }
    void failureTrigger(String table,String event,Runnable check) {
        jdbc.execute("CREATE FUNCTION pg020_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled failure'; END $$");
        jdbc.execute("CREATE TRIGGER pg020_reject BEFORE "+event+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION pg020_reject()");
        try {check.run();}finally {jdbc.execute("DROP TRIGGER pg020_reject ON "+table);jdbc.execute("DROP FUNCTION pg020_reject()");}
    }
}
