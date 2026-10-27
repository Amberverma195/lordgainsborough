package com.lordgainsborough.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.corundumstudio.socketio.SocketIOServer;
import com.lordgainsborough.restaurant.controller.OrderController;
import com.lordgainsborough.restaurant.dto.OrderCreationResponse;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.security.*;
import com.lordgainsborough.restaurant.service.*;
import com.lordgainsborough.restaurant.support.LocalPostgres;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual default Spring wiring, PostgreSQL commits, registered HTTP guards and Socket.IO transport. */
@SpringBootTest(classes=RestaurantApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"management.server.port=0", "socketio.host=127.0.0.1", "spring.mail.username=test", "spring.mail.password=test",
                "jwt.secret=pg-only-integration-test-secret-with-at-least-32-bytes", "jwt.expiration=86400000",
                "app.abuse.ip-hash-secret=pg-only-integration-test-hash-secret-with-32-bytes"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners=PostgresOnlyApplicationIntegrationTest.DatabaseCleanup.class,mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresOnlyApplicationIntegrationTest {
    static final LocalPostgres database=new LocalPostgres();
    static final int socketPort=reservePort();
    static int reservePort() { try(var socket=new ServerSocket(0)) { return socket.getLocalPort(); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",database::jdbcUrl); p.add("spring.datasource.username",()->"postgres");
        p.add("spring.datasource.password",()->"postgres"); p.add("socketio.port",()->socketPort);
    }
    @Autowired ConfigurableApplicationContext context;
    @Autowired WebApplicationContext web;
    @Autowired JdbcTemplate jdbc;
    @Autowired JpaUserRepository users;
    @Autowired JwtUtil jwt;
    @Autowired UserAuthStore identities;
    @Autowired OrderController orders;
    @Autowired OrderAccessService access;
    @Autowired OrderRealtimeService realtime;
    @Autowired SocketIOServer socketServer;
    @MockitoBean EmailSenderService email;
    final ObjectMapper json=new ObjectMapper();
    final List<Socket> clients=new CopyOnWriteArrayList<>();
    MockMvc mvc;
    @BeforeAll void http() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_settings WHERE id='default'",Integer.class)).isEqualTo(1);
        mvc=MockMvcBuilders.webAppContextSetup(web).build();
    }
    @BeforeEach void resetData() {
        jdbc.execute("TRUNCATE users,customer_orders,carts,order_reviews,reservations,signup_otp_challenges,password_reset_otp_challenges,signup_abuse_limits,login_failure_states CASCADE");
        context.getBean(JpaStoreSettingsRepository.class).saveAndFlush(new com.lordgainsborough.restaurant.persistence.jpa.entity.JpaStoreSettings());
        jdbc.update("UPDATE store_settings SET kds_allowed_ip='127.0.0.1', pickup_orders_enabled=true");
        reset(email);
    }
    @AfterEach void disconnectClients() { clients.forEach(s->{s.off();s.disconnect();s.close();});clients.clear(); }
    public static class DatabaseCleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
        @Override public int getOrder() { return 2500; }
        @Override public void afterTestClass(org.springframework.test.context.TestContext context) { database.close(); }
    }
    JpaUser account(String id,String address,String phone,String role) {
        var u=new JpaUser();u.setId(id);u.setEmail(address);u.setName("Member "+id);u.setPhoneNormalized(phone);
        u.setPhone("+1 ("+phone.substring(0,3)+")-"+phone.substring(3,6)+"-"+phone.substring(6));
        u.setPassword(PasswordUtil.hashPassword("CorrectPassword1!"));u.setRole(role);return users.saveAndFlush(u);
    }
    String token(JpaUser u) { return jwt.generateToken(u.getId(),u.getEmail(),u.getTokenVersion()); }
    JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int status) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode send(String path,Map<String,?> body,int status) throws Exception {
        return response(post(path).header("Idempotency-Key","integration-operation").contentType("application/json").content(json.writeValueAsString(body)),status);
    }
    OrderCreationResponse order(String address,String phone) {
        var o=new CustomerOrder();o.setCustomerName("Guest");o.setCustomerEmail(address);o.setCustomerPhone(phone);
        var item=new CustomerOrder.OrderItem();item.setMenuItemId(jdbc.queryForObject("SELECT id FROM menu_items ORDER BY id LIMIT 1",String.class));item.setQuantity(2);o.setItems(List.of(item));
        var result=orders.createOrder(o);assertThat(result.getStatusCode().value()).isEqualTo(200);return (OrderCreationResponse)result.getBody();
    }
    String signupCode(String address,String phone) throws Exception {
        send("/api/auth/signup/send-otp",Map.of("email",address,"phone",phone),202);
        var code=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(email).sendOtpEmail(eq(address),code.capture(),anyString());return code.getValue();
    }
    @Test void defaultApplicationHasOnlyPostgresStoresAndCurrentValidSchema() {
        context.getBean(Flyway.class).validate();
        assertThat(identities).isInstanceOf(PostgresUserAuthStore.class);
        assertThat(context.getBean(CustomerOrderStore.class)).isInstanceOf(PostgresCustomerOrderStore.class);
        assertThat(context.getBean(CartStore.class)).isInstanceOf(PostgresCartStore.class);
        assertThat(context.getBean(ReviewStore.class)).isInstanceOf(PostgresReviewStore.class);
        assertThat(context.getBean(ReservationStore.class)).isInstanceOf(PostgresReservationStore.class);
        assertThat(context.getBean(AdminCustomerSearchStore.class)).isInstanceOf(PostgresAdminCustomerSearchStore.class);
        assertThat(context.getBean(SignupOtpChallengeStore.class)).isInstanceOf(PostgresSignupOtpChallengeStore.class);
        assertThat(context.getBean(PasswordResetOtpChallengeStore.class)).isInstanceOf(PostgresPasswordResetOtpChallengeStore.class);
        assertThat(context.getBean(AbuseCounterStore.class)).isInstanceOf(PostgresAbuseCounterStore.class);
        assertThat(context.getBean(LoginFailureStateStore.class)).isInstanceOf(PostgresLoginFailureStateStore.class);
        assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class)).hasSize(1);
        assertThat(context.getBean("transactionManager")).isSameAs(context.getBean("orderJpaTransactionManager"))
                .isSameAs(context.getBean("cartJpaTransactionManager")).isSameAs(context.getBean("reservationJpaTransactionManager"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories",Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items",Integer.class)).isEqualTo(119);
    }
    @Test void signupPersistsServerOwnedIdentityConsumesOtpAndLogsInByEmailAndPhone() throws Exception {
        String code=signupCode("new@example.com","5195551111");
        var created=send("/api/auth/signup",Map.of("email"," NEW@example.com ","name"," New Member ","phone","5195551111","password","CorrectPassword1!","otp",code,"role","ADMIN","tokenVersion",55),201);
        assertThat(created.toString()).doesNotContain("password","tokenVersion");
        var stored=users.findByEmailIgnoreCase("new@example.com").orElseThrow();
        assertThat(stored.getRole()).isEqualTo("MEMBER");assertThat(stored.getTokenVersion()).isZero();
        assertThat(PasswordUtil.checkPassword("CorrectPassword1!",stored.getPassword())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM signup_otp_challenges",Integer.class)).isZero();
        send("/api/auth/login",Map.of("email","NEW@example.com","password","CorrectPassword1!"),200);
        send("/api/auth/login",Map.of("emailOrPhone","5195551111","password","CorrectPassword1!"),200);
    }
    @Test void wrongOtpDoesNotCreateAccountAndDuplicatePhoneDoesNotConsumeAnotherChallenge() throws Exception {
        String code=signupCode("new@example.com","5195551111");
        send("/api/auth/signup",Map.of("email","new@example.com","name","New","phone","5195551111","password","CorrectPassword1!","otp","not-a-code"),400);
        assertThat(users.count()).isZero();
        account("existing","existing@example.com","5195551111","MEMBER");
        send("/api/auth/signup",Map.of("email","new@example.com","name","New","phone","5195551111","password","CorrectPassword1!","otp",code),409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM signup_otp_challenges",Integer.class)).isEqualTo(1);
    }
    @Test void unknownAccountAndWrongPasswordReturnSamePublicFailure() throws Exception {
        account("member","known@example.com","5195551111","MEMBER");
        var missing=send("/api/auth/login",Map.of("email","missing@example.com","password","WrongPassword1!"),401);
        var wrong=send("/api/auth/login",Map.of("email","known@example.com","password","WrongPassword1!"),401);
        assertThat(missing.get("code")).isEqualTo(wrong.get("code"));assertThat(missing.get("message")).isEqualTo(wrong.get("message"));
    }
    @Test void resetThroughHttpRevokesJwtAndChangesPasswordAtomically() throws Exception {
        var member=account("member","member@example.com","5195551111","MEMBER");String previous=token(member);
        var sent=send("/api/auth/forgot-password/send-otp",Map.of("email",member.getEmail()),202);
        var code=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(email).sendPasswordResetCodeEmail(eq(member.getEmail()),code.capture(),anyString());
        send("/api/auth/forgot-password/reset",Map.of("email",member.getEmail(),"challengeId",sent.get("challengeId").asText(),"otp",code.getValue(),"password","ChangedPassword2!"),200);
        assertThat(users.findById("member").orElseThrow().getTokenVersion()).isEqualTo(1);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+previous)).andExpect(status().isUnauthorized());
        send("/api/auth/login",Map.of("email",member.getEmail(),"password","CorrectPassword1!"),401);
        send("/api/auth/login",Map.of("email",member.getEmail(),"password","ChangedPassword2!"),200);
    }
    @Test void signupRaceForSamePhoneCreatesOnlyOneUser() throws Exception {
        String a=signupCode("one@example.com","5195551111"),b=signupCode("two@example.com","5195551111");
        var gate=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var signup=context.getBean(SignupOtpChallengeService.class);
            java.util.function.BiFunction<String,String,UserAuthStore.Registration> register=(address,code)->{
                var draft=new com.lordgainsborough.restaurant.model.User();draft.setEmail(address);draft.setName("Member");draft.setPhoneNormalized("5195551111");
                try { gate.await(5,TimeUnit.SECONDS);return identities.register(draft,"CorrectPassword1!",code,signup); }
                catch(Exception e) { throw new IllegalStateException(e); }
            };
            var first=pool.submit(()->register.apply("one@example.com",a));var second=pool.submit(()->register.apply("two@example.com",b));
            var results=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertThat(results.stream().filter(r->r.user()!=null).count()).isEqualTo(1);
            assertThat(results.stream().filter(UserAuthStore.Registration::conflict).count()).isEqualTo(1);assertThat(users.count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void accountHistoryDeduplicatesEmailAndPhoneAndExcludesOtherCustomers() throws Exception {
        var member=account("member","member@example.com","5195551111","MEMBER");
        var own=order(member.getEmail(),member.getPhone());order("other@example.com","5195552222");
        var history=response(get("/api/auth/orders").header("Authorization","Bearer "+token(member)),200);
        assertThat(history.size()).isEqualTo(1);assertThat(history.get(0).get("id").asText()).isEqualTo(own.getOrder().getId());
        assertThat(history.toString()).doesNotContain("trackingTokenHash","hibernateLazyInitializer");
    }
    @Test void tokenRevocationAndCurrentRolesAreCheckedAgainstDatabase() throws Exception {
        var admin=account("admin","admin@example.com","5195551111","ADMIN");String token=token(admin);
        response(get("/api/admin/customers/search").param("q","admin").header("Authorization","Bearer "+token),200);
        jdbc.update("UPDATE users SET role='MEMBER' WHERE id='admin'");
        mvc.perform(get("/api/admin/customers/search").param("q","admin").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id='admin'");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void profileNameUpdatePreservesCredentialsRoleAndCounters() throws Exception {
        var member=account("member","member@example.com","5195551111","MEMBER");String token=token(member);
        mvc.perform(put("/api/auth/profile").header("Authorization","Bearer "+token).contentType("application/json").content("{\"name\":\"Changed\",\"role\":\"ADMIN\",\"password\":\"forged\"}"))
                .andExpect(status().isOk());
        var changed=users.findById("member").orElseThrow();assertThat(changed.getName()).isEqualTo("Changed");
        assertThat(changed.getRole()).isEqualTo("MEMBER");assertThat(changed.getPassword()).isEqualTo(member.getPassword());assertThat(changed.getTokenVersion()).isZero();
    }
    @Test void administratorSearchEscapesSqlWildcardsAndNeverReturnsSecrets() throws Exception {
        var admin=account("admin","admin@example.com","5195551111","ADMIN");account("member","literal%_member@example.com","5195552222","MEMBER");
        var result=response(get("/api/admin/customers/search").param("q","%_").header("Authorization","Bearer "+token(admin)),200);
        assertThat(result.size()).isEqualTo(1);assertThat(result.toString()).doesNotContain("password","tokenVersion","trackingTokenHash");
        mvc.perform(get("/api/admin/customers/search").param("q","member")).andExpect(status().isNotFound());
    }
    @Test void catalogAndSettingsWritesUseSameDatasetAsCheckoutAndKitchen() throws Exception {
        var admin=account("admin","admin@example.com","5195551111","ADMIN");String token=token(admin);
        var created=response(post("/api/menu").header("Authorization","Bearer "+token).contentType("application/json").content("{\"name\":\"Integration dish\",\"category\":\"Mains\",\"price\":12.34,\"available\":true}"),200);
        String id=created.get("id").asText();assertThat(jdbc.queryForObject("SELECT price FROM menu_items WHERE id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("12.34");
        response(patch("/api/menu/"+id+"/most-loved").header("Authorization","Bearer "+token),200);
        assertThat(jdbc.queryForObject("SELECT is_most_loved FROM menu_items WHERE id=?",Boolean.class,id)).isTrue();
        mvc.perform(delete("/api/menu/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        var category=response(post("/api/categories").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"name\":\"Integration category\",\"description\":\"Description\",\"displayOrder\":99}"),200);
        assertThat(jdbc.queryForObject("SELECT display_order FROM categories WHERE id=?",Integer.class,category.get("id").asText())).isEqualTo(99);
        mvc.perform(delete("/api/categories/"+category.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        var settings=response(get("/api/store-settings"),200);assertThat(settings.has("kdsAllowedIp")).isFalse();
        response(put("/api/store-settings").header("Authorization","Bearer "+token).contentType("application/json").content("{\"kdsAllowedIp\":\"192.0.2.1\",\"taxRate\":0.15}"),200);
        assertThat(context.getBean(KdsAccessService.class).isIpAllowed("127.0.0.1")).isFalse();
        assertThat(jdbc.queryForObject("SELECT tax_rate FROM store_settings WHERE id='default'",java.math.BigDecimal.class)).isEqualByComparingTo("0.15");
    }
    Socket client(String query,Map<String,String> auth) throws Exception {
        var options=new IO.Options(); options.forceNew=true;options.reconnection=false;options.timeout=3000;
        options.transports=new String[]{"websocket"};options.query=query;options.auth=auth;
        Socket socket=IO.socket("http://127.0.0.1:"+socketPort,options);clients.add(socket);return socket;
    }
    void connect(Socket socket) throws Exception {
        var connected=new CountDownLatch(1);socket.on(Socket.EVENT_CONNECT,args->connected.countDown());socket.connect();
        assertThat(connected.await(5,TimeUnit.SECONDS)).as("live Socket.IO connection").isTrue();
    }
    void denied(Socket socket) throws Exception {
        var rejected=new CountDownLatch(1);socket.on(Socket.EVENT_CONNECT_ERROR,args->rejected.countDown());socket.on(Socket.EVENT_DISCONNECT,args->rejected.countDown());socket.connect();
        assertThat(rejected.await(5,TimeUnit.SECONDS)).as("connection rejection").isTrue();
        assertThat(socketServer.getAllClients()).allSatisfy(c->assertThat(c.getAllRooms()).noneMatch(r->r.startsWith("order:")));
    }
    static void await(java.util.function.BooleanSupplier condition,long timeout) throws Exception {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);
    }
    void waitRoom(String id,int count) throws Exception {
        await(()->socketServer.getRoomOperations("order:"+id).getClients().size()==count,3000);
        assertThat(socketServer.getRoomOperations("order:"+id).getClients()).hasSize(count);
    }
    @Test void guestTokenAdmitsOnlyMatchingRoomAndDeliversSerializedUpdate() throws Exception {
        var order=order("guest@example.com","5195551111");String id=order.getOrder().getId();
        assertThat(context.getBean(SocketOrderAuthorizationService.class).authorize(id,null,null,order.getTrackingToken(),"127.0.0.1")).isPresent();
        var socket=client("orderId="+id,Map.of("trackingToken",order.getTrackingToken()));
        var delivered=new CountDownLatch(1);List<String> payloads=new CopyOnWriteArrayList<>();
        socket.on("order.updated",args->{payloads.add(args[0].toString());delivered.countDown();});connect(socket);waitRoom(id,1);
        realtime.orderUpdated(context.getBean(CustomerOrderStore.class).findById(id).orElseThrow());assertThat(delivered.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(payloads.getFirst()).contains(id).doesNotContain("trackingTokenHash",order.getTrackingToken());
        assertThat(json.readTree(payloads.getFirst()).get("createdAt").isTextual()).isTrue();
        assertThat(socketServer.getRoomOperations("kds").getClients()).isEmpty();
    }
    @Test void absentAuthPayloadCannotBypassRoomAdmission() throws Exception {
        var order=order("guest@example.com","5195551111");denied(client("orderId="+order.getOrder().getId(),null));
    }
    @Test void wrongTokenAndCrossOrderTokenAreRejected() throws Exception {
        OrderCreationResponse first=order("one@example.com","5195551111"), second=order("two@example.com","5195552222");
        denied(client("orderId="+first.getOrder().getId(),Map.of("trackingToken",second.getTrackingToken())));
        denied(client("orderId="+first.getOrder().getId(),Map.of("trackingToken","malformed")));
    }
    @Test void customerJwtUsesPersistedOwnershipAndRevocationOnReconnect() throws Exception {
        var owner=account("owner","owner@example.com","5195551111","MEMBER");String token=token(owner);
        var order=order(owner.getEmail(),owner.getPhone());String id=order.getOrder().getId();
        var socket=client("orderId="+id,Map.of("token",token));connect(socket);waitRoom(id,1);
        socket.disconnect();waitRoom(id,0);jdbc.update("UPDATE users SET token_version=1 WHERE id='owner'");
        denied(client("orderId="+id,Map.of("token",token)));
    }
    @Test void strangerJwtCannotReadOrderEvenWithAdministratorRole() throws Exception {
        var stranger=account("stranger","other@example.com","5195552222","ADMIN");var order=order("owner@example.com","5195551111");
        denied(client("orderId="+order.getOrder().getId(),Map.of("token",token(stranger))));
    }
    @Test void roomIsolationReconnectAndArbitraryJoinEventAreEnforced() throws Exception {
        OrderCreationResponse first=order("one@example.com","5195551111"),second=order("two@example.com","5195552222");
        String firstId=first.getOrder().getId(),secondId=second.getOrder().getId();
        var one=client("orderId="+firstId,Map.of("trackingToken",first.getTrackingToken()));
        var two=client("orderId="+secondId,Map.of("trackingToken",second.getTrackingToken()));
        connect(one);connect(two);waitRoom(firstId,1);waitRoom(secondId,1);
        var delivered=new CountDownLatch(1);var leaked=new CountDownLatch(1);one.on("chat.message",args->delivered.countDown());two.on("chat.message",args->leaked.countDown());
        two.emit("join","order:"+firstId);one.emit("join","kds");realtime.chatMessage(context.getBean(CustomerOrderStore.class).findById(firstId).orElseThrow(),new CustomerOrder.OrderMessage());
        assertThat(delivered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(leaked.await(400,TimeUnit.MILLISECONDS)).isFalse();
        one.disconnect();waitRoom(firstId,0);connect(one);waitRoom(firstId,1);
        assertThat(socketServer.getRoomOperations("kds").getClients()).isEmpty();
    }
    @Test void kitchenUsesIpAllowlistForBothHandshakeAndRoomAdmission() throws Exception {
        var kitchen=client("role=KDS",null);connect(kitchen);
        await(()->socketServer.getRoomOperations("kds").getClients().size()==1,3000);
        assertThat(socketServer.getRoomOperations("kds").getClients()).hasSize(1);
        kitchen.disconnect();jdbc.update("UPDATE store_settings SET kds_allowed_ip='192.0.2.1'");
        denied(client("role=KDS",null));
    }
    @Test void kitchenEventsObserveCommittedWritesAndRollbackEmitsNothing() throws Exception {
        var kitchen=client("role=KDS",null);var events=new LinkedBlockingQueue<String>();
        kitchen.on("order.created",args->events.add(args[0].toString()));connect(kitchen);
        await(()->socketServer.getRoomOperations("kds").getClients().size()==1,3000);
        var created=order("guest@example.com","5195551111");String payload=events.poll(5,TimeUnit.SECONDS);
        assertThat(payload).isNotNull().contains(created.getOrder().getId());
        try(var connection=java.sql.DriverManager.getConnection(database.jdbcUrl(),"postgres","postgres");var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM customer_orders")) {
            assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(1);
        }
        jdbc.execute("CREATE FUNCTION reject_order() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled rollback'; END $$");
        jdbc.execute("CREATE TRIGGER reject_order BEFORE INSERT ON customer_orders FOR EACH ROW EXECUTE FUNCTION reject_order()");
        try {
            assertThatThrownBy(()->order("failed@example.com","5195552222")).isInstanceOf(RuntimeException.class);
            assertThat(events.poll(400,TimeUnit.MILLISECONDS)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders",Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER reject_order ON customer_orders");jdbc.execute("DROP FUNCTION reject_order()"); }
    }
}
