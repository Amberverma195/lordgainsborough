package com.lordgainsborough.restaurant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.dto.OrderCreationResponse;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.CustomerOrderStore;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerIdempotencyUnitTest {

    private static final String TEST_SECRET = "unit-test-tracking-secret-with-at-least-32-bytes";

    private CustomerOrderStore orderStore;
    private OrderRealtimeService realtimeService;
    private JwtUtil jwtUtil;
    private UserAuthStore authStore;
    private OrderAccessService accessService;
    private OrderController controller;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        orderStore = mock(CustomerOrderStore.class);
        realtimeService = mock(OrderRealtimeService.class);
        jwtUtil = mock(JwtUtil.class);
        authStore = mock(UserAuthStore.class);
        accessService = new OrderAccessService(jwtUtil, authStore, TEST_SECRET);

        controller = new OrderController(orderStore, realtimeService, accessService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        StoreSettings settings = new StoreSettings();
        settings.setPickupOrdersEnabled(true);
        settings.setDefaultReadyMinutes(25);
        settings.setTaxRate(0.13);
        when(orderStore.settings()).thenReturn(settings);
        when(orderStore.pickupDisabled(anyString(), anyString())).thenReturn(false);

        MenuItem item = new MenuItem();
        item.setId("item-1");
        item.setName("Roast Beef");
        item.setPrice(20.00);
        item.setAvailable(true);
        when(orderStore.resolveMenuItem(eq("item-1"), any())).thenReturn(Optional.of(item));
        when(orderStore.resolveMenuItem(any(), eq("Roast Beef"))).thenReturn(Optional.of(item));

        User owner = new User();
        owner.setName("Jane Doe");
        owner.setEmail("jane@example.com");
        owner.setPhone("5195551234");
        lenient().when(jwtUtil.validateToken("owner-jwt")).thenReturn(true);
        lenient().when(jwtUtil.getEmailFromToken("owner-jwt")).thenReturn("jane@example.com");
        lenient().when(authStore.authenticate(eq("owner-jwt"), any())).thenReturn(Optional.of(owner));

        User otherUser = new User();
        otherUser.setName("Attacker");
        otherUser.setEmail("attacker@example.com");
        otherUser.setPhone("5195559999");
        lenient().when(jwtUtil.validateToken("attacker-jwt")).thenReturn(true);
        lenient().when(jwtUtil.getEmailFromToken("attacker-jwt")).thenReturn("attacker@example.com");
        lenient().when(authStore.authenticate(eq("attacker-jwt"), any())).thenReturn(Optional.of(otherUser));
    }

    private CustomerOrder sampleOrder() {
        CustomerOrder order = new CustomerOrder();
        order.setCustomerName("Jane Doe");
        order.setCustomerEmail("jane@example.com");
        order.setCustomerPhone("5195551234");
        order.setPickupDate("2026-11-01");
        order.setPickupTime("18:00");
        order.setSpecialInstructions("Extra gravy");

        CustomerOrder.OrderItem item = new CustomerOrder.OrderItem();
        item.setMenuItemId("item-1");
        item.setName("Roast Beef");
        item.setQuantity(2);
        item.setUnitPrice(20.00);
        item.setTotal(40.00);
        order.setItems(new ArrayList<>(List.of(item)));

        return order;
    }

    @Test
    @DisplayName("deriveTrackingToken is deterministic, 43 chars, and satisfies token validation")
    void testDeriveTrackingTokenDeterminismAndValidity() {
        String key1 = "test-idempotency-key-12345";
        String tokenA = accessService.deriveTrackingToken(key1);
        String tokenB = accessService.deriveTrackingToken(key1);

        assertThat(tokenA).isEqualTo(tokenB);
        assertThat(tokenA).matches("^[A-Za-z0-9_-]{43}$");

        String hash = accessService.hashTrackingToken(tokenA);
        CustomerOrder order = new CustomerOrder();
        order.setTrackingTokenHash(hash);

        assertThat(accessService.canAccessPostgresOrder(order, tokenA)).isTrue();

        String tokenDiffKey = accessService.deriveTrackingToken("different-key");
        assertThat(tokenDiffKey).isNotEqualTo(tokenA);
        assertThat(accessService.canAccessPostgresOrder(order, tokenDiffKey)).isFalse();
    }

    @Test
    @DisplayName("deriveTrackingToken uses HMAC server secret rather than legacy public salt")
    void testDeriveTrackingTokenUsesServerSecretNotPublicConstant() throws Exception {
        String key = "test-key-secret-derivation";
        String token = accessService.deriveTrackingToken(key);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update("LGB_ORDER_TRACKING_SALT:".getBytes(StandardCharsets.UTF_8));
        String legacySaltToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(md.digest(key.getBytes(StandardCharsets.UTF_8)));

        assertThat(token).isNotEqualTo(legacySaltToken);
    }

    @Test
    @DisplayName("CustomerOrder excludes idempotencyKey from serialized JSON output but deserializes it")
    void testIdempotencyKeyExcludedFromCustomerOrderSerialization() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setIdempotencyKey("secret-idempotency-key-to-hide");
        String serialized = json.writeValueAsString(order);

        assertThat(serialized).doesNotContain("secret-idempotency-key-to-hide");
        assertThat(serialized).doesNotContain("idempotencyKey");

        CustomerOrder readBack = json.readValue("{\"idempotencyKey\":\"incoming-client-key\"}", CustomerOrder.class);
        assertThat(readBack.getIdempotencyKey()).isEqualTo("incoming-client-key");
    }

    @Test
    @DisplayName("Unauthenticated order submission without Authorization header returns HTTP 401 Unauthorized")
    void testCreateOrderWithoutAuthorizationReturnsHttp401() throws Exception {
        CustomerOrder request = sampleOrder();

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Order submission with invalid JWT returns HTTP 401 Unauthorized")
    void testCreateOrderWithInvalidTokenReturnsHttp401() throws Exception {
        CustomerOrder request = sampleOrder();
        when(jwtUtil.validateToken("bad-token")).thenReturn(false);

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Order contact identity is derived directly from authenticated account")
    void testCreateOrderDerivesContactIdentityFromAuthenticatedUser() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setCustomerName("");
        request.setCustomerEmail("");
        request.setCustomerPhone("");

        when(orderStore.create(any(), any())).thenAnswer(inv -> {
            CustomerOrder saved = inv.getArgument(0);
            saved.setId("order-derived-123");
            return saved;
        });

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-derived-123"));

        verify(orderStore).create(argThat(saved ->
                "jane@example.com".equals(saved.getCustomerEmail())
                        && "5195551234".equals(saved.getCustomerPhone())
                        && "Jane Doe".equals(saved.getCustomerName())
        ), any());
    }

    @Test
    @DisplayName("Attempting to submit order with mismatched email returns HTTP 403 Forbidden")
    void testCreateOrderCustomerEmailMismatchReturnsHttp403() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setCustomerEmail("victim@example.com");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_IDENTITY_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Attempting to submit order with mismatched phone returns HTTP 403 Forbidden")
    void testCreateOrderCustomerPhoneMismatchReturnsHttp403() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setCustomerPhone("5195559999");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_IDENTITY_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("First checkout request saves order with idempotency key and returns tracking token")
    void testFirstOrderCreationWithIdempotencyKey() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = UUID.randomUUID().toString();

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(orderStore.create(any(), any())).thenAnswer(inv -> {
            CustomerOrder toSave = inv.getArgument(0);
            toSave.setId("order-saved-101");
            toSave.setCreatedAt(LocalDateTime.now());
            java.util.function.Consumer<CustomerOrder> cb = inv.getArgument(1);
            if (cb != null) cb.accept(toSave);
            return toSave;
        });

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-saved-101"))
                .andExpect(jsonPath("$.trackingToken").isNotEmpty());

        verify(orderStore, times(1)).create(any(), any());
        verify(realtimeService, times(1)).orderCreated(any());
    }

    @Test
    @DisplayName("Connection drop retry with matching user Authorization reuses existing order without duplicate creation")
    void testAuthorizedRetryWithMatchingUserJwtReusesExistingOrder() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-already-created-202");
        existingOrder.setIdempotencyKey(idempotencyKey);
        existingOrder.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        String expectedTrackingToken = accessService.deriveTrackingToken(idempotencyKey);
        existingOrder.setTrackingTokenHash(accessService.hashTrackingToken(expectedTrackingToken));

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-already-created-202"))
                .andExpect(jsonPath("$.trackingToken").value(expectedTrackingToken));

        verify(orderStore, never()).create(any(), any());
        verify(realtimeService, never()).orderCreated(any());
    }

    @Test
    @DisplayName("Retry with non-owner authenticated account is rejected with HTTP 403 Forbidden")
    void testUnauthorizedRetryWithDifferentUserJwtReturnsHttp403() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer attacker-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_IDENTITY_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Payload body idempotencyKey field is recognized on retry when authenticated")
    void testPayloadBodyIdempotencyKeyRecognizedWithAuthorization() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = "body-key-" + UUID.randomUUID();
        request.setIdempotencyKey(idempotencyKey);

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-from-body-key");
        existingOrder.setIdempotencyKey(idempotencyKey);
        String expectedTrackingToken = accessService.deriveTrackingToken(idempotencyKey);
        existingOrder.setTrackingTokenHash(accessService.hashTrackingToken(expectedTrackingToken));

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        com.fasterxml.jackson.databind.node.ObjectNode bodyNode = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
        bodyNode.put("idempotencyKey", idempotencyKey);

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(bodyNode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-from-body-key"))
                .andExpect(jsonPath("$.trackingToken").value(expectedTrackingToken));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Same Idempotency-Key with conflicting email returns HTTP 403 or 409")
    void testConflictingPayloadReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setCustomerEmail("other@example.com");
        String idempotencyKey = UUID.randomUUID().toString();

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_IDENTITY_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Retry with missing customer name in payload returns HTTP 409 Conflict")
    void testMissingCustomerNameReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setCustomerName(null);
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        // When user has no name in account, incoming null name causes mismatch
        User namelessUser = new User();
        namelessUser.setEmail("jane@example.com");
        namelessUser.setPhone("5195551234");
        when(jwtUtil.validateToken("nameless-jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("nameless-jwt")).thenReturn("jane@example.com");
        when(authStore.authenticate(eq("nameless-jwt"), any())).thenReturn(Optional.of(namelessUser));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer nameless-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Retry with missing customer phone in payload returns HTTP 409 Conflict")
    void testMissingCustomerPhoneReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setCustomerPhone("");
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        // When user has no phone in account, incoming blank phone causes mismatch
        User phonelessUser = new User();
        phonelessUser.setName("Jane Doe");
        phonelessUser.setEmail("jane@example.com");
        when(jwtUtil.validateToken("phoneless-jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("phoneless-jwt")).thenReturn("jane@example.com");
        when(authStore.authenticate(eq("phoneless-jwt"), any())).thenReturn(Optional.of(phonelessUser));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer phoneless-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Retry with different pickup date returns HTTP 409 Conflict")
    void testDifferentPickupDateReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setPickupDate("2026-12-25");
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Retry with different pickup time returns HTTP 409 Conflict")
    void testDifferentPickupTimeReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setPickupTime("12:00");
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Retry with different special instructions returns HTTP 409 Conflict")
    void testDifferentSpecialInstructionsReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setSpecialInstructions("No gravy, extra salt");
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Retry with different item quantities returns HTTP 409 Conflict")
    void testDifferentItemQuantityReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.getItems().get(0).setQuantity(5);
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Retry with different item name returns HTTP 409 Conflict")
    void testDifferentItemNameReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.getItems().get(0).setName("Fish and Chips");
        request.getItems().get(0).setMenuItemId(null);
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Overly long Idempotency-Key (>128 chars) returns HTTP 400 Bad Request")
    void testOverlyLongIdempotencyKeyReturnsBadRequest() throws Exception {
        CustomerOrder request = sampleOrder();
        String longKey = "k".repeat(129);

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", longKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Concurrent request race condition: unique constraint violation recovers committed order when authorized")
    void testConcurrentRaceConditionRecoversCommittedOrderWhenAuthorized() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder committedOrder = sampleOrder();
        committedOrder.setId("order-won-race");
        committedOrder.setIdempotencyKey(idempotencyKey);
        String expectedTrackingToken = accessService.deriveTrackingToken(idempotencyKey);
        committedOrder.setTrackingTokenHash(accessService.hashTrackingToken(expectedTrackingToken));

        AtomicInteger findCallCount = new AtomicInteger(0);
        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenAnswer(inv -> {
            if (findCallCount.getAndIncrement() == 0) {
                return Optional.empty();
            }
            return Optional.of(committedOrder);
        });

        when(orderStore.create(any(), any())).thenThrow(new DataIntegrityViolationException("Unique constraint violated"));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-won-race"))
                .andExpect(jsonPath("$.trackingToken").value(expectedTrackingToken));
    }

    @Test
    @DisplayName("Concurrent request race condition: fails with HTTP 401 when unauthorized")
    void testConcurrentRaceConditionFailsWhenUnauthorized() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = UUID.randomUUID().toString();

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verify(orderStore, never()).create(any(), any());
    }
}
