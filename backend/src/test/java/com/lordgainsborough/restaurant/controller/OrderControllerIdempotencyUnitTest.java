package com.lordgainsborough.restaurant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.dto.OrderCreationResponse;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerIdempotencyUnitTest {

    private CustomerOrderStore orderStore;
    private OrderRealtimeService realtimeService;
    private OrderAccessService accessService;
    private OrderController controller;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        orderStore = mock(CustomerOrderStore.class);
        realtimeService = mock(OrderRealtimeService.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserAuthStore authStore = mock(UserAuthStore.class);
        accessService = new OrderAccessService(jwtUtil, authStore);

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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-saved-101"))
                .andExpect(jsonPath("$.trackingToken").isNotEmpty());

        verify(orderStore, times(1)).create(any(), any());
        verify(realtimeService, times(1)).orderCreated(any());
    }

    @Test
    @DisplayName("Connection drop retry with same Idempotency-Key returns existing order without creating second order")
    void testLostResponseRetryReusesExistingOrderWithoutDuplicateCreation() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-already-created-202");
        existingOrder.setIdempotencyKey(idempotencyKey);
        existingOrder.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        String expectedTrackingToken = accessService.deriveTrackingToken(idempotencyKey);
        existingOrder.setTrackingTokenHash(accessService.hashTrackingToken(expectedTrackingToken));

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        // Client retries POST /api/orders after experiencing connection drop
        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-already-created-202"))
                .andExpect(jsonPath("$.trackingToken").value(expectedTrackingToken));

        // Verify orderStore.create was NEVER called on retry
        verify(orderStore, never()).create(any(), any());
        // Verify no second notification was sent to kitchen
        verify(realtimeService, never()).orderCreated(any());
    }

    @Test
    @DisplayName("Payload body idempotencyKey field is recognized when header is absent")
    void testPayloadBodyIdempotencyKeyRecognized() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = "body-key-" + UUID.randomUUID();
        request.setIdempotencyKey(idempotencyKey);

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-from-body-key");
        existingOrder.setIdempotencyKey(idempotencyKey);
        String expectedTrackingToken = accessService.deriveTrackingToken(idempotencyKey);
        existingOrder.setTrackingTokenHash(accessService.hashTrackingToken(expectedTrackingToken));

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-from-body-key"))
                .andExpect(jsonPath("$.trackingToken").value(expectedTrackingToken));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Same Idempotency-Key with conflicting payload details returns HTTP 409 Conflict")
    void testConflictingPayloadReturnsHttp409() throws Exception {
        CustomerOrder request = sampleOrder();
        request.setCustomerEmail("different-customer@example.com"); // modified payload
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder existingOrder = sampleOrder(); // original had jane@example.com
        existingOrder.setId("order-existing");
        existingOrder.setIdempotencyKey(idempotencyKey);

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Overly long Idempotency-Key (>128 chars) returns HTTP 400 Bad Request")
    void testOverlyLongIdempotencyKeyReturnsBadRequest() throws Exception {
        CustomerOrder request = sampleOrder();
        String longKey = "k".repeat(129);

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", longKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Concurrent request race condition: unique constraint violation recovers committed order")
    void testConcurrentRaceConditionRecoversCommittedOrder() throws Exception {
        CustomerOrder request = sampleOrder();
        String idempotencyKey = UUID.randomUUID().toString();

        CustomerOrder committedOrder = sampleOrder();
        committedOrder.setId("order-won-race");
        committedOrder.setIdempotencyKey(idempotencyKey);
        String expectedTrackingToken = accessService.deriveTrackingToken(idempotencyKey);
        committedOrder.setTrackingTokenHash(accessService.hashTrackingToken(expectedTrackingToken));

        AtomicInteger findCallCount = new AtomicInteger(0);
        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenAnswer(inv -> {
            // First lookup before create: empty
            if (findCallCount.getAndIncrement() == 0) {
                return Optional.empty();
            }
            // Second lookup inside catch block: finds the concurrently committed order
            return Optional.of(committedOrder);
        });

        // Simulate unique index constraint violation thrown by database
        when(orderStore.create(any(), any())).thenThrow(new DataIntegrityViolationException("Unique constraint idx_orders_idempotency_key violated"));

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-won-race"))
                .andExpect(jsonPath("$.trackingToken").value(expectedTrackingToken));
    }
}
