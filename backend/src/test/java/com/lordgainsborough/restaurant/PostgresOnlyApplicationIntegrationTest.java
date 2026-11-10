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
    static String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) digits = digits.substring(1);
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }
    OrderCreationResponse order(String address,String phone) {
        var existing = users.findByEmailIgnoreCase(address);
        String normPhone = normalizePhone(phone);
        JpaUser u = existing.orElseGet(() -> {
            boolean phoneTaken = !normPhone.isBlank() && users.existsByPhoneNormalized(normPhone);
            if (phoneTaken) {
                var user = new JpaUser();
                user.setId(UUID.randomUUID().toString());
                user.setEmail(address);
                user.setName("Member " + address);
                user.setPassword(PasswordUtil.hashPassword("CorrectPassword1!"));
                user.setRole("MEMBER");
                return users.saveAndFlush(user);
            }
            return account(UUID.randomUUID().toString(), address, normPhone.isBlank() ? "5195551212" : normPhone, "MEMBER");
        });
        var o=new CustomerOrder();o.setCustomerName(u.getName());o.setCustomerEmail(address);o.setCustomerPhone(phone);
        var item=new CustomerOrder.OrderItem();item.setMenuItemId(jdbc.queryForObject("SELECT id FROM menu_items ORDER BY id LIMIT 1",String.class));item.setQuantity(2);o.setItems(List.of(item));
        var result=orders.createOrder(null, "Bearer " + token(u), null, o);
        assertThat(result.getStatusCode().value()).isEqualTo(200);return (OrderCreationResponse)result.getBody();
    }
    String signupCode(String address,String phone) throws Exception {
        send("/api/auth/signup/send-otp",Map.of("email",address,"phone",phone),202);
        var code=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(email).sendOtpEmail(eq(address),code.capture(),anyString());return code.getValue();
    }

    @Test void signupStatusRequiresAttemptTokenAndNeverLeaksUnownedChallengeMetadata() throws Exception {
        var pending=send("/api/auth/signup/send-otp",Map.of("email","status-owner@example.com","phone","5195551191",
                "name","Status Owner","password","CorrectPassword1!"),202);
        String id=pending.get("challengeId").asText(), token=pending.get("attemptToken").asText();
        assertThat(token).isNotBlank();
        var anonymous=response(get("/api/auth/signup/otp-status").param("email","status-owner@example.com"),404);
        var identified=response(get("/api/auth/signup/otp-status").param("email","status-owner@example.com").param("challengeId",id),404);
        var wrong=response(get("/api/auth/signup/otp-status").param("email","status-owner@example.com").param("challengeId",id)
                .header("X-Signup-Attempt-Token","wrong-token"),404);
        var missing=response(get("/api/auth/signup/otp-status").param("email","missing@example.com")
                .header("X-Signup-Attempt-Token",token),404);
        assertThat(anonymous).isEqualTo(identified).isEqualTo(wrong).isEqualTo(missing);
        for(String field:List.of("challengeId","nextResendAt","otpExpiresAt","challengeExpiresAt","resendsRemaining","attemptToken"))
            assertThat(anonymous.hasNonNull(field)).as(field).isFalse();
        response(get("/api/auth/signup/otp-status").param("email","status-owner@example.com").param("challengeId","wrong-id")
                .header("X-Signup-Attempt-Token",token),404);
        var result=mvc.perform(get("/api/auth/signup/otp-status").param("email","status-owner@example.com").param("challengeId",id)
                .header("X-Signup-Attempt-Token",token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        var owned=json.readTree(result.andReturn().getResponse().getContentAsString());
        assertThat(owned.get("challengeId").asText()).isEqualTo(id);
        assertThat(owned.get("resendsRemaining").asInt()).isEqualTo(5);
        assertThat(owned.hasNonNull("attemptToken")).isFalse();
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder messageRequest(String path,String tracking,String text) throws Exception {
        var request=post(path).contentType("application/json").content(json.writeValueAsString(Map.of("text",text)));
        if(tracking!=null) request.header(OrderAccessService.TRACKING_TOKEN_HEADER,tracking);
        return request;
    }

    OrderCreationResponse messageOrder(String address,String phone) {
        var owner=account(UUID.randomUUID().toString(),address,phone,"MEMBER");
        String id=UUID.randomUUID().toString(), tracking=access.generateTrackingToken();
        // Chat tests need an existing order, independently of current opening hours.
        jdbc.update("insert into customer_orders(id,customer_name,customer_email,customer_phone,pickup_date,tracking_token_hash) values (?,?,?,?,?,?)",
                id,owner.getName(),address,phone,"2099-01-01",access.hashTrackingToken(tracking));
        var saved=context.getBean(CustomerOrderStore.class).findById(id).orElseThrow();
        return new OrderCreationResponse(com.lordgainsborough.restaurant.dto.OrderStatusResponse.from(saved),tracking);
    }

    @Test void socketTicketCannotAuthenticateAccountApisOrAnotherOrder() throws Exception {
        var first=messageOrder("ticket-one@example.com","5195551181");
        var second=messageOrder("ticket-two@example.com","5195551182");
        String id=first.getOrder().getId();
        mvc.perform(get("/api/orders/"+id+"/socket-ticket")).andExpect(status().isNotFound());
        var data=response(get("/api/orders/"+id+"/socket-ticket")
                .header(OrderAccessService.TRACKING_TOKEN_HEADER,first.getTrackingToken()),200);
        String ticket=data.get("ticket").asText();
        assertThat(jwt.validateToken(ticket)).isFalse();
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+ticket)).andExpect(status().isUnauthorized());
        var authorization=context.getBean(SocketOrderAuthorizationService.class);
        assertThat(authorization.authorize(id,null,ticket,null,"127.0.0.1")).isPresent();
        assertThat(authorization.authorize(second.getOrder().getId(),null,ticket,null,"127.0.0.1")).isEmpty();
        var socket=client("orderId="+id,Map.of("token",ticket));connect(socket);waitRoom(id,1);
        var event=new CountDownLatch(1);socket.on("order.updated",args->event.countDown());
        realtime.orderUpdated(context.getBean(CustomerOrderStore.class).findById(id).orElseThrow());
        assertThat(event.await(5,TimeUnit.SECONDS)).isTrue();
    }

    @Test void messagesEnforceTextLengthForCustomerAndKitchen() throws Exception {
        var created=messageOrder("message-length@example.com","5195551121");String id=created.getOrder().getId();
        String customer="/api/orders/"+id+"/messages", kitchen="/api/kds/orders/"+id+"/messages";
        for(String path:List.of(customer,kitchen)) {
            for(String invalid:List.of(" ","a".repeat(2001)," "+"a".repeat(2000)))
                mvc.perform(messageRequest(path,created.getTrackingToken(),invalid)).andExpect(status().isBadRequest());
            mvc.perform(messageRequest(path,created.getTrackingToken(),"a".repeat(2000))).andExpect(status().isOk());
            // JSON escaping expands the request bytes but must not reject legal message text.
            mvc.perform(post(path).header(OrderAccessService.TRACKING_TOKEN_HEADER,created.getTrackingToken())
                    .contentType("application/json").content("{\"text\":\""+"\\u0061".repeat(2000)+"\"}"))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select count(*) from order_messages",Integer.class)).isEqualTo(4);
    }

    @Test void messagesRejectOversizedBodiesBeforeSavingOrBroadcasting() throws Exception {
        var created=messageOrder("message-body@example.com","5195551122");String id=created.getOrder().getId();
        var kitchen=client("role=KDS",null);var events=new LinkedBlockingQueue<String>();
        kitchen.on("chat.message",args->events.add(args[0].toString()));connect(kitchen);
        for(String path:List.of("/api/orders/"+id+"/messages","/api/kds/orders/"+id+"/messages")) {
            mvc.perform(post(path).header(OrderAccessService.TRACKING_TOKEN_HEADER,created.getTrackingToken())
                    .contentType("application/json").content("{\"text\":\"hello\",\"padding\":\""+"x".repeat(16384)+"\"}"))
                    .andExpect(status().isPayloadTooLarge());
        }
        assertThat(jdbc.queryForObject("select count(*) from order_messages",Integer.class)).isZero();
        assertThat(events.poll(300,TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void messagesLimitEachSideOfConversationAndPreserveAuthorization() throws Exception {
        var created=messageOrder("message-rate@example.com","5195551123");String id=created.getOrder().getId();
        String customer="/api/orders/"+id+"/messages", kitchen="/api/kds/orders/"+id+"/messages";
        for(int i=0;i<3;i++) mvc.perform(messageRequest(customer,"invalid","hello")).andExpect(status().isNotFound());
        for(String path:List.of(customer,kitchen)) {
            for(int i=0;i<10;i++) mvc.perform(messageRequest(path,created.getTrackingToken(),"hello")).andExpect(status().isOk());
            mvc.perform(messageRequest(path,created.getTrackingToken(),"blocked"))
                    .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
        // A different IP cannot bypass the order quota. A bad token cannot spend it.
        mvc.perform(messageRequest(customer,created.getTrackingToken(),"blocked").with(r->{r.setRemoteAddr("198.51.100.21");return r;}))
                .andExpect(status().isTooManyRequests());
        mvc.perform(messageRequest(customer,"invalid","unauthorized")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from order_messages",Integer.class)).isEqualTo(20);
    }

    @Test void messagesIpLimitCannotBeBypassedWithForwardedHeaders() throws Exception {
        var created=messageOrder("message-ip@example.com","5195551124");String path="/api/orders/"+created.getOrder().getId()+"/messages";
        for(int i=0;i<60;i++) {
            mvc.perform(messageRequest(path,"invalid","hello").header("X-Forwarded-For","203.0.113."+i)
                    .with(r->{r.setRemoteAddr("198.51.100.20");return r;})).andExpect(status().isNotFound());
        }
        mvc.perform(messageRequest(path,created.getTrackingToken(),"blocked").header("X-Forwarded-For","203.0.113.250")
                .with(r->{r.setRemoteAddr("198.51.100.20");return r;}))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForObject("select count(*) from order_messages",Integer.class)).isZero();
    }

    @Test void messagesShareAtomicLimitsAcrossInstancesAndResetAfterWindow() throws Exception {
        var fixed=java.time.Clock.fixed(java.time.Instant.parse("2026-11-09T15:30:20Z"),java.time.ZoneOffset.UTC);
        var store=context.getBean(AbuseCounterStore.class);var hasher=context.getBean(AbuseIpHasher.class);
        var first=new OrderMessageRateLimiter(store,hasher,fixed,60,10);
        var second=new OrderMessageRateLimiter(store,hasher,fixed,60,10);
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<24;i++) {var instance=i%2==0?first:second;results.add(pool.submit(()->instance.consumeOrder("shared-order",false).isEmpty()));}
            int admitted=0;for(var result:results) if(result.get(10,TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(10);
            assertThat(second.consumeOrder("shared-order",true)).isEmpty();
            var next=new OrderMessageRateLimiter(store,hasher,java.time.Clock.offset(fixed,java.time.Duration.ofMinutes(1)),60,10);
            assertThat(next.consumeOrder("shared-order",false)).isEmpty();
        } finally {pool.shutdownNow();}
    }

    @Test void messagesFailClosedWhenDistributedCountersAreUnavailable() throws Exception {
        var created=messageOrder("message-failure@example.com","5195551125");String path="/api/orders/"+created.getOrder().getId()+"/messages";
        jdbc.execute("CREATE FUNCTION reject_message_counter() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled counter failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_message_counter BEFORE INSERT ON signup_abuse_limits FOR EACH ROW EXECUTE FUNCTION reject_message_counter()");
        try {
            assertThatThrownBy(()->mvc.perform(messageRequest(path,created.getTrackingToken(),"must not save"))).isInstanceOf(Exception.class);
            assertThat(jdbc.queryForObject("select count(*) from order_messages",Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_message_counter ON signup_abuse_limits");
            jdbc.execute("DROP FUNCTION reject_message_counter()");
        }
    }

    void deletionData(String id, String address, String orderId) {
        jdbc.update("insert into reservations(id,name,email,phone,date,time,party_size,special_requests) values (?,'Member',?,'5195551111','2099-01-01','12:00',2,'Personal reservation request')",
                "reservation-"+id,address);
        jdbc.update("insert into carts(id,user_id) values (?,?)", "cart-"+id, id);
        jdbc.update("insert into cart_items(id,cart_id,name,price,quantity) values (?,?, 'Dish',10,1)", "ci-"+id,"cart-"+id);
        jdbc.update("insert into order_messages(id,order_id,sender_role,sender_name,text) values (?,?,'MEMBER','Member','Hello')", "msg-"+id,orderId);
        jdbc.update("insert into order_reviews(id,order_id,customer_name,customer_email,customer_phone) values (?,?,'Member',?,'5195551111')", "review-"+id,orderId,address);
        jdbc.update("insert into order_review_items(id,review_id,item_name,rating) values (?,?,'Dish',5)", "ri-"+id,"review-"+id);
        jdbc.update("insert into password_reset_otp_challenges(email_key,challenge_id,user_id,purpose,status,challenge_expires_at) values (?,?,?,'PASSWORD_RESET','ACTIVE',now()+interval '1 hour')", "reset-"+id,"challenge-"+id,id);
        jdbc.update("insert into signup_otp_challenges(email_key,normalized_email,challenge_id,status,otp_digest,challenge_expires_at) values (?,?,?,'ACTIVE','digest',now()+interval '1 hour')", "signup-"+id,address,"signup-challenge-"+id);
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deleteRequest(String token, String password) throws Exception {
        return delete("/api/auth/me").header("Authorization","Bearer "+token)
                .contentType("application/json").content(json.writeValueAsString(Map.of("password",password)));
    }

    OrderCreationResponse deletionOrder(JpaUser owner,String status) {
        String id=UUID.randomUUID().toString(), tracking=access.generateTrackingToken();
        jdbc.update("insert into customer_orders(id,customer_name,customer_email,customer_phone,pickup_date,status,tracking_token_hash) values (?,?,?,?,?,?,?)",
                id,owner.getName(),owner.getEmail(),owner.getPhone(),"2099-01-01",status,access.hashTrackingToken(tracking));
        jdbc.update("insert into order_items(id,order_id,name,quantity,unit_price,total) values (?,?,'Dish',1,10,10)",UUID.randomUUID().toString(),id);
        return new OrderCreationResponse(com.lordgainsborough.restaurant.dto.OrderStatusResponse.from(context.getBean(CustomerOrderStore.class).findById(id).orElseThrow()),tracking);
    }

    @Test void deleteAccountBlocksEveryActiveStatusWithoutDeletingDataOrResettingPenalties() throws Exception {
        var owner=account("delete-active","active-delete@example.com","5195551189","MEMBER");
        String id=deletionOrder(owner,"NEW").getOrder().getId();
        deletionData(owner.getId(),owner.getEmail(),id);
        jdbc.update("update users set no_show_count=3 where id=?",owner.getId());
        // Orders are owned by email, including case-insensitive matches.
        jdbc.update("update customer_orders set customer_email=upper(customer_email) where id=?",id);
        var customer=client("orderId="+id,Map.of("token",token(owner)));connect(customer);waitRoom(id,1);
        var kitchen=client("role=KDS",null);var events=new LinkedBlockingQueue<String>();
        kitchen.on("order.deleted",args->events.add(args[0].toString()));connect(kitchen);
        for(String active:List.of("NEW","ACCEPTED","READY")) {
            jdbc.update("update customer_orders set status=? where id=?",active,id);
            mvc.perform(deleteRequest(token(owner),"CorrectPassword1!")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_ACTIVE_ORDERS"));
            for(String table:List.of("users","customer_orders","order_items","order_messages","order_reviews","order_review_items","carts","cart_items","password_reset_otp_challenges","signup_otp_challenges","reservations"))
                assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isEqualTo(1);
            assertThat(users.findById(owner.getId()).orElseThrow().getNoShowCount()).isEqualTo(3);
            assertThat(identities.authenticate(token(owner),jwt)).isPresent();
            waitRoom(id,1);
        }
        assertThat(events.poll(300,TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void deleteAccountRemovesAllDependentsAndRevokesIdentity() throws Exception {
        var owner=account("delete-owner","delete@example.com","5195551111","MEMBER");
        var other=account("delete-other","other@example.com","5195552222","MEMBER");
        String oldToken=token(owner);
        String ownOrder=deletionOrder(owner,"PICKED_UP").getOrder().getId();
        String otherOrder=deletionOrder(other,"NEW").getOrder().getId();
        deletionData(owner.getId(),owner.getEmail(),ownOrder);
        deletionData(other.getId(),other.getEmail(),otherOrder);
        jdbc.update("update customer_orders set customer_email='DELETE@EXAMPLE.COM' where id=?",ownOrder);
        // Matching a phone alone must not grant destructive ownership.
        jdbc.update("update customer_orders set customer_phone=? where id=?",owner.getPhone(),otherOrder);
        jdbc.update("update reservations set email=' DELETE@EXAMPLE.COM ',phone='5195559999' where id=?","reservation-"+owner.getId());
        jdbc.update("update reservations set phone=?,name=? where id=?",owner.getPhone(),owner.getName(),"reservation-"+other.getId());

        mvc.perform(deleteRequest(oldToken,"CorrectPassword1!")).andExpect(status().isNoContent());
        assertThat(users.existsById(owner.getId())).isFalse();
        assertThat(users.existsById(other.getId())).isTrue();
        for(String table:List.of("customer_orders","order_items","order_messages","order_reviews","order_review_items","carts","cart_items","password_reset_otp_challenges","signup_otp_challenges","reservations"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isEqualTo(1);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(deleteRequest(oldToken,"CorrectPassword1!")).andExpect(status().isUnauthorized());
        var replacement=account("delete-replacement",owner.getEmail(),"5195551111","MEMBER");
        assertThat(identities.authenticate(oldToken,jwt)).isEmpty();
        assertThat(identities.authenticate(token(replacement),jwt)).isPresent();
    }

    @Test void deleteAccountRejectsInvalidCredentialsAndThrottlesFailures() throws Exception {
        var owner=account("delete-credentials","credentials@example.com","5195553333","MEMBER");
        mvc.perform(delete("/api/auth/me").contentType("application/json").content("{\"password\":\"CorrectPassword1!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(deleteRequest("invalid","CorrectPassword1!")).andExpect(status().isUnauthorized());
        mvc.perform(deleteRequest(token(owner)," ")).andExpect(status().isBadRequest());
        mvc.perform(deleteRequest(token(owner),"wrong")).andExpect(status().isForbidden());
        boolean throttled=false;
        for(int i=0;i<20;i++) {
            var result=mvc.perform(deleteRequest(token(owner),"wrong")).andReturn().getResponse();
            if(result.getStatus()==429) { assertThat(result.getHeader("Retry-After")).isNotBlank();throttled=true;break; }
            assertThat(result.getStatus()).isEqualTo(403);
        }
        assertThat(throttled).isTrue();
        assertThat(users.existsById(owner.getId())).isTrue();
    }

    @Test void deleteAccountRollsBackAllDataWhenUserDeletionFails() throws Exception {
        var owner=account("delete-rollback","rollback@example.com","5195554444","MEMBER");
        String ownOrder=deletionOrder(owner,"PICKED_UP").getOrder().getId();
        deletionData(owner.getId(),owner.getEmail(),ownOrder);
        jdbc.execute("CREATE FUNCTION reject_account_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled deletion failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_account_delete BEFORE DELETE ON users FOR EACH ROW EXECUTE FUNCTION reject_account_delete()");
        try {
            assertThatThrownBy(()->mvc.perform(deleteRequest(token(owner),"CorrectPassword1!"))).isInstanceOf(Exception.class);
            for(String table:List.of("users","customer_orders","order_items","order_messages","order_reviews","order_review_items","carts","cart_items","password_reset_otp_challenges","signup_otp_challenges","reservations"))
                assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isEqualTo(1);
            assertThat(identities.authenticate(token(owner),jwt)).isPresent();
        } finally {
            jdbc.execute("DROP TRIGGER reject_account_delete ON users");
            jdbc.execute("DROP FUNCTION reject_account_delete()");
        }
    }

    @Test void deleteAccountRejectsStaleAuthenticatedCheckoutAndCartWrites() throws Exception {
        var owner=account("delete-stale","stale@example.com","5195555555","MEMBER");
        String savedToken=token(owner);
        var draft=new CustomerOrder();draft.setCustomerName(owner.getName());draft.setCustomerEmail(owner.getEmail());draft.setCustomerPhone(owner.getPhone());
        mvc.perform(deleteRequest(savedToken,"CorrectPassword1!")).andExpect(status().isNoContent());
        assertThatThrownBy(()->context.getBean(PostgresCustomerOrderStore.class).createAuthenticated(draft,savedToken,jwt,ignored->{}))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(put("/api/cart").header("Authorization","Bearer "+savedToken).contentType("application/json").content("[]"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from customer_orders",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from carts",Integer.class)).isZero();
    }

    @Test void deleteAccountRemovesPastAndUpcomingReservationsInEveryStatus() throws Exception {
        var owner=account("delete-bookings","bookings-delete@example.com","5195551187","MEMBER");
        for(String status:List.of("PENDING","CONFIRMED","CANCELLED")) {
            for(String date:List.of("2020-01-01","2099-01-01"))
                jdbc.update("insert into reservations(id,name,email,phone,date,time,party_size,status,special_requests) values (?, ?, ?, ?, ?, '12:00',2,?,'Private note')",
                        UUID.randomUUID().toString(),owner.getName()," BOOKINGS-DELETE@EXAMPLE.COM ","5195559999",date,status);
        }
        // No user FK is needed, and contact overlap alone must not authorize deletion.
        jdbc.update("insert into reservations(id,name,email,phone,date,time,party_size) values ('unrelated-booking',?,'different@example.com',?,'2099-01-01','12:00',2)",owner.getName(),owner.getPhone());
        mvc.perform(deleteRequest(token(owner),"CorrectPassword1!")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForList("select id from reservations",String.class)).containsExactly("unrelated-booking");
    }

    @Test void deleteAccountRollsBackWhenReservationCleanupFails() throws Exception {
        var owner=account("delete-booking-failure","booking-failure@example.com","5195551186","MEMBER");
        String id=deletionOrder(owner,"PICKED_UP").getOrder().getId();
        deletionData(owner.getId(),owner.getEmail(),id);
        jdbc.execute("CREATE FUNCTION reject_booking_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled reservation deletion failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_booking_delete BEFORE DELETE ON reservations FOR EACH ROW EXECUTE FUNCTION reject_booking_delete()");
        try {
            assertThatThrownBy(()->mvc.perform(deleteRequest(token(owner),"CorrectPassword1!"))).isInstanceOf(Exception.class);
            for(String table:List.of("users","customer_orders","order_items","order_messages","order_reviews","order_review_items","carts","cart_items","password_reset_otp_challenges","signup_otp_challenges","reservations"))
                assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isEqualTo(1);
            assertThat(identities.authenticate(token(owner),jwt)).isPresent();
        } finally {
            jdbc.execute("DROP TRIGGER reject_booking_delete ON reservations");
            jdbc.execute("DROP FUNCTION reject_booking_delete()");
        }
    }

    @Test void deleteAccountNotifiesKitchenAndRevokesLiveOrderRooms() throws Exception {
        var owner=account("delete-live","live-delete@example.com","5195556666","MEMBER");
        var created=deletionOrder(owner,"PICKED_UP");String id=created.getOrder().getId();
        var customer=client("orderId="+id,Map.of("token",token(owner)));
        var guest=client("orderId="+id,Map.of("trackingToken",created.getTrackingToken()));
        connect(customer);connect(guest);waitRoom(id,2);
        var kitchen=client("role=KDS",null);var events=new LinkedBlockingQueue<String>();
        kitchen.on("order.deleted",args->events.add(args[0].toString()));connect(kitchen);
        mvc.perform(deleteRequest(token(owner),"CorrectPassword1!")).andExpect(status().isNoContent());
        assertThat(events.poll(5,TimeUnit.SECONDS)).contains(id);
        waitRoom(id,0);
        assertThat(access.getAuthenticatedUserFromToken(token(owner))).isEmpty();
        assertThat(context.getBean(SocketOrderAuthorizationService.class).authorize(id,null,null,created.getTrackingToken(),"127.0.0.1")).isEmpty();
    }

    @Test void deleteAccountExcludesConcurrentCheckoutAndCartUpdates() throws Exception {
        var owner=account("delete-race","delete-race@example.com","5195557777","MEMBER");
        String auth=token(owner);
        var draft=new CustomerOrder();draft.setCustomerName(owner.getName());draft.setCustomerEmail(owner.getEmail());draft.setCustomerPhone(owner.getPhone());
        draft.setPickupDate("2099-01-01");
        var barrier=new CyclicBarrier(3);var pool=Executors.newFixedThreadPool(3);
        try {
            var deletion=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return mvc.perform(deleteRequest(auth,"CorrectPassword1!")).andReturn().getResponse().getStatus();});
            var checkout=pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try { context.getBean(PostgresCustomerOrderStore.class).createAuthenticated(draft,auth,jwt,ignored->{});return 200; }
                catch(org.springframework.web.server.ResponseStatusException expired) { return expired.getStatusCode().value(); }
            });
            var cart=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return mvc.perform(put("/api/cart").header("Authorization","Bearer "+auth).contentType("application/json").content("[]")).andReturn().getResponse().getStatus();});
            int deletionStatus=deletion.get(15,TimeUnit.SECONDS), checkoutStatus=checkout.get(15,TimeUnit.SECONDS);
            assertThat(cart.get(15,TimeUnit.SECONDS)).isIn(200,401);
            if(checkoutStatus==200) {
                assertThat(deletionStatus).isEqualTo(409);
                assertThat(users.existsById(owner.getId())).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from customer_orders where status='NEW'",Integer.class)).isEqualTo(1);
            } else {
                assertThat(checkoutStatus).isEqualTo(401);
                assertThat(deletionStatus).isEqualTo(204);
                assertThat(users.existsById(owner.getId())).isFalse();
                assertThat(jdbc.queryForObject("select count(*) from customer_orders",Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from carts",Integer.class)).isZero();
            }
        } finally {pool.shutdownNow();}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void deleteAccountRetriesDeadlockAgainstKitchenNoShowAndRechecksActiveOrders(boolean deletionVictim) throws Exception {
        var owner=account("delete-deadlock","delete-deadlock@example.com","5195551185","MEMBER");
        String id=deletionOrder(owner,"READY").getOrder().getId();
        String remaining=deletionOrder(owner,"NEW").getOrder().getId();
        deletionData(owner.getId(),owner.getEmail(),id);
        var kitchen=client("role=KDS",null);var updates=new LinkedBlockingQueue<String>();var deletions=new LinkedBlockingQueue<String>();
        kitchen.on("order.updated",args->updates.add(args[0].toString()));
        kitchen.on("order.deleted",args->deletions.add(args[0].toString()));connect(kitchen);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(
                "com.lordgainsborough.restaurant.service.PostgresDeadlockRetry");
        var retries=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        retries.start();logger.addAppender(retries);
        var pool=Executors.newFixedThreadPool(2);
        long gateKey=1357902468L;
        String dbName=jdbc.queryForObject("select current_database()",String.class);
        assertThat(dbName).matches("lgb_test_[a-f0-9]{32}");
        var connections=context.getBean(com.zaxxer.hikari.HikariDataSource.class);
        // This private fixture database controls which transaction detects the cycle
        // first. Evict idle connections so deletion receives the fixture timeout.
        jdbc.execute("ALTER DATABASE "+dbName+" SET deadlock_timeout='"+(deletionVictim?"500ms":"10s")+"'");
        connections.getHikariPoolMXBean().softEvictConnections();
        // An advisory-lock gate pauses the actual KDS transaction after its order
        // lock and before its user lock. No timing sleeps or mocked DB locks.
        jdbc.execute("CREATE FUNCTION gate_no_show_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                +"IF NEW.id = '"+id+"' AND NEW.status = 'NO_SHOW' THEN "
                +"PERFORM set_config('deadlock_timeout','"+(deletionVictim?"10s":"50ms")+"',true); PERFORM pg_advisory_xact_lock("+gateKey+"); "
                +"END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER gate_no_show_delete BEFORE UPDATE ON customer_orders FOR EACH ROW EXECUTE FUNCTION gate_no_show_delete()");
        try(var gate=java.sql.DriverManager.getConnection(database.jdbcUrl(),"postgres","postgres");var statement=gate.createStatement()) {
            statement.execute("select pg_advisory_lock("+gateKey+")");
            try {
                var noShow=pool.submit(()->context.getBean(com.lordgainsborough.restaurant.controller.KdsController.class)
                        .updateStatus(id,Map.of("status","NO_SHOW")).getStatusCode().value());
                await(()->Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' "
                        +"and objid="+gateKey+"::oid and not granted and database=(select oid from pg_database where datname=current_database()))",Boolean.class)),5000);
                assertThat(jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and objid="+gateKey+"::oid and not granted "
                        +"and database=(select oid from pg_database where datname=current_database())",Integer.class)).isEqualTo(1);
                var deletion=pool.submit(()->mvc.perform(deleteRequest(token(owner),"CorrectPassword1!")).andReturn().getResponse().getStatus());
                // Deletion now owns the user and waits for the kitchen's order lock.
                await(()->Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() "
                        +"and wait_event_type='Lock' and query like 'select id from customer_orders%for update%')",Boolean.class)),5000);
                assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        +"and wait_event_type='Lock' and query like 'select id from customer_orders%for update%'",Integer.class)).isEqualTo(1);
                // The kitchen now waits for that user, completing a real deadlock.
                // The fixture timeouts exercise both possible PostgreSQL victims.
                statement.execute("select pg_advisory_unlock("+gateKey+")");
                assertThat(noShow.get(15,TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(deletion.get(15,TimeUnit.SECONDS)).isEqualTo(409);
                String operation=deletionVictim?"account deletion":"kitchen status update";
                assertThat(retries.list.stream().filter(event->event.getFormattedMessage().startsWith(
                        "Retrying "+operation+" after PostgreSQL deadlock")).count()).isEqualTo(1);
                assertThat(users.findById(owner.getId()).orElseThrow().getNoShowCount()).isEqualTo(1);
                assertThat(jdbc.queryForObject("select status from customer_orders where id=?",String.class,id)).isEqualTo("NO_SHOW");
                assertThat(jdbc.queryForObject("select status from customer_orders where id=?",String.class,remaining)).isEqualTo("NEW");
                for(String table:List.of("carts","cart_items","order_messages","order_reviews","order_review_items","reservations"))
                    assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isEqualTo(1);
                assertThat(identities.authenticate(token(owner),jwt)).isPresent();
                assertThat(updates.poll(5,TimeUnit.SECONDS)).contains(id);
                assertThat(updates.poll(300,TimeUnit.MILLISECONDS)).isNull();
                assertThat(deletions.poll(300,TimeUnit.MILLISECONDS)).isNull();
            } finally {
                statement.execute("select pg_advisory_unlock("+gateKey+")");
                pool.shutdown();if(!pool.awaitTermination(15,TimeUnit.SECONDS))pool.shutdownNow();
            }
        } finally {
            pool.shutdownNow();logger.detachAppender(retries);retries.stop();
            jdbc.execute("DROP TRIGGER gate_no_show_delete ON customer_orders");
            jdbc.execute("DROP FUNCTION gate_no_show_delete()");
            jdbc.execute("ALTER DATABASE "+dbName+" RESET deadlock_timeout");
            connections.getHikariPoolMXBean().softEvictConnections();
        }
    }
    @Test void anonymousOrderSubmissionIsRejectedWithHttp401() throws Exception {
        var o = new CustomerOrder();
        o.setCustomerName("Anonymous");
        o.setCustomerEmail("anon@example.com");
        o.setCustomerPhone("5195551111");
        var item = new CustomerOrder.OrderItem();
        item.setMenuItemId(jdbc.queryForObject("SELECT id FROM menu_items ORDER BY id LIMIT 1", String.class));
        item.setQuantity(1);
        o.setItems(List.of(item));

        mvc.perform(post("/api/orders")
                .contentType("application/json")
                .content(json.writeValueAsString(o)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
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
        var own=order(member.getEmail(),member.getPhone());
        order("other@example.com","5195552222");
        order("different@example.com",member.getPhone());
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
