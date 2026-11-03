package com.lordgainsborough.restaurant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.config.OrderRateLimitProperties;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerAbuseTest {

    private static final String TEST_SECRET = "unit-test-tracking-secret-with-at-least-32-bytes";

    private CustomerOrderStore orderStore;
    private OrderRealtimeService realtimeService;
    private JwtUtil jwtUtil;
    private UserAuthStore authStore;
    private OrderAccessService accessService;
    private OrderRateLimiter rateLimiter;
    private ClientIpResolver clientIpResolver;
    private OrderRateLimitProperties properties;
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

        properties = new OrderRateLimitProperties();
        properties.setMaxCartItems(30);
        properties.setMinItemQuantity(1);
        properties.setMaxItemQuantity(20);
        properties.setMaxTotalQuantity(50);
        properties.setMaxOrderTotal(1000.00);
        properties.setMaxActiveOrders(3);
        properties.setAccountRequests(2);
        properties.setAccountWindow(Duration.ofMinutes(15));
        properties.setIpRequests(3);
        properties.setIpWindow(Duration.ofMinutes(15));

        Clock clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
        rateLimiter = new OrderRateLimiter(null, properties, null, clock);
        com.lordgainsborough.restaurant.config.SignupAbuseProperties abuseProps = new com.lordgainsborough.restaurant.config.SignupAbuseProperties();
        clientIpResolver = new ClientIpResolver(abuseProps);

        controller = new OrderController(orderStore, realtimeService, accessService, rateLimiter, clientIpResolver, properties);
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

        MenuItem expensiveItem = new MenuItem();
        expensiveItem.setId("item-exp");
        expensiveItem.setName("Premium Wine");
        expensiveItem.setPrice(600.00);
        expensiveItem.setAvailable(true);
        when(orderStore.resolveMenuItem(eq("item-exp"), any())).thenReturn(Optional.of(expensiveItem));
        when(orderStore.resolveMenuItem(any(), eq("Premium Wine"))).thenReturn(Optional.of(expensiveItem));

        User owner = new User();
        owner.setId("user-1");
        owner.setName("Jane Doe");
        owner.setEmail("jane@example.com");
        owner.setPhone("5195551234");
        when(jwtUtil.validateToken("owner-jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("owner-jwt")).thenReturn("jane@example.com");
        when(authStore.authenticate(eq("owner-jwt"), any())).thenReturn(Optional.of(owner));

        when(orderStore.create(any(), any())).thenAnswer(inv -> {
            CustomerOrder toSave = inv.getArgument(0);
            toSave.setId("order-" + System.nanoTime());
            toSave.setCreatedAt(LocalDateTime.now());
            return toSave;
        });
    }

    private CustomerOrder sampleOrder() {
        CustomerOrder order = new CustomerOrder();
        order.setCustomerName("Jane Doe");
        order.setCustomerEmail("jane@example.com");
        order.setCustomerPhone("5195551234");
        order.setPickupDate("2026-11-04");
        order.setPickupTime("18:00");

        CustomerOrder.OrderItem item = new CustomerOrder.OrderItem();
        item.setMenuItemId("item-1");
        item.setName("Roast Beef");
        item.setQuantity(2);
        item.setUnitPrice(20.00);
        order.setItems(new ArrayList<>(List.of(item)));

        return order;
    }

    @Test
    @DisplayName("Exceeding max distinct cart lines returns HTTP 400 CART_SIZE_EXCEEDED")
    void testCartLinesExceeded() throws Exception {
        CustomerOrder order = sampleOrder();
        List<CustomerOrder.OrderItem> items = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            CustomerOrder.OrderItem line = new CustomerOrder.OrderItem();
            line.setMenuItemId("item-1");
            line.setName("Roast Beef");
            line.setQuantity(1);
            items.add(line);
        }
        order.setItems(items);

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CART_SIZE_EXCEEDED"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Single item exceeding max item quantity returns HTTP 400 INVALID_ITEM_QUANTITY")
    void testItemQuantityExceeded() throws Exception {
        CustomerOrder order = sampleOrder();
        order.getItems().get(0).setQuantity(21);

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ITEM_QUANTITY"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Sum of quantities exceeding max total quantity returns HTTP 400 TOTAL_QUANTITY_EXCEEDED")
    void testTotalQuantityExceeded() throws Exception {
        CustomerOrder order = sampleOrder();
        List<CustomerOrder.OrderItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            CustomerOrder.OrderItem line = new CustomerOrder.OrderItem();
            line.setMenuItemId("item-1");
            line.setName("Roast Beef");
            line.setQuantity(20); // 3 * 20 = 60 > 50
            items.add(line);
        }
        order.setItems(items);

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOTAL_QUANTITY_EXCEEDED"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Order total exceeding max dollar cap returns HTTP 400 ORDER_TOTAL_EXCEEDED")
    void testOrderTotalExceeded() throws Exception {
        CustomerOrder order = sampleOrder();
        CustomerOrder.OrderItem line = new CustomerOrder.OrderItem();
        line.setMenuItemId("item-exp");
        line.setName("Premium Wine");
        line.setQuantity(2); // 2 * $600 = $1200 + tax > $1000 limit
        order.setItems(new ArrayList<>(List.of(line)));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_TOTAL_EXCEEDED"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Customer exceeding simultaneous active orders cap receives HTTP 429 ACTIVE_ORDERS_LIMIT_EXCEEDED")
    void testActiveOrdersLimitExceeded() throws Exception {
        CustomerOrder order = sampleOrder();
        when(orderStore.countActiveOrders(eq("jane@example.com"), eq("5195551234"))).thenReturn(3L);

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACTIVE_ORDERS_LIMIT_EXCEEDED"));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Per-account rate limit returns HTTP 429 RATE_LIMIT_EXCEEDED with Retry-After header")
    void testAccountRateLimitExceeded() throws Exception {
        CustomerOrder order = sampleOrder();

        // 1st request succeeds
        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isOk());

        // 2nd request succeeds
        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isOk());

        // 3rd request exceeds account limit of 2
        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("Too many orders placed for this account. Please try again later."));
    }

    @Test
    @DisplayName("IP-based rate limit returns HTTP 429 RATE_LIMIT_EXCEEDED with Retry-After header")
    void testIpRateLimitExceeded() throws Exception {
        CustomerOrder order = sampleOrder();

        // Use different user accounts from the same IP to isolate IP rate limiting
        for (int i = 1; i <= 3; i++) {
            User user = new User();
            user.setId("user-" + i);
            user.setName("User " + i);
            user.setEmail("user" + i + "@example.com");
            user.setPhone("519555000" + i);
            String token = "jwt-" + i;
            when(jwtUtil.validateToken(token)).thenReturn(true);
            when(jwtUtil.getEmailFromToken(token)).thenReturn(user.getEmail());
            when(authStore.authenticate(eq(token), any())).thenReturn(Optional.of(user));

            order.setCustomerEmail(user.getEmail());
            order.setCustomerPhone(user.getPhone());
            order.setCustomerName(user.getName());

            mvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + token)
                            .with(req -> {
                                req.setRemoteAddr("203.0.113.42");
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(order)))
                    .andExpect(status().isOk());
        }

        // 4th request from same IP exceeds IP limit of 3
        User user4 = new User();
        user4.setId("user-4");
        user4.setName("User 4");
        user4.setEmail("user4@example.com");
        user4.setPhone("5195550004");
        String token4 = "jwt-4";
        when(jwtUtil.validateToken(token4)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token4)).thenReturn(user4.getEmail());
        when(authStore.authenticate(eq(token4), any())).thenReturn(Optional.of(user4));

        order.setCustomerEmail(user4.getEmail());
        order.setCustomerPhone(user4.getPhone());
        order.setCustomerName(user4.getName());

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token4)
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.42");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("Too many order requests from this IP address. Please try again later."));
    }

    @Test
    @DisplayName("Idempotent retry is NOT blocked even when active orders limit is reached")
    void testIdempotentRetryNotBlockedByActiveOrdersLimit() throws Exception {
        CustomerOrder order = sampleOrder();
        String idempotencyKey = "existing-key-123";
        order.setIdempotencyKey(idempotencyKey);

        CustomerOrder existingOrder = sampleOrder();
        existingOrder.setId("order-existing-999");
        existingOrder.setIdempotencyKey(idempotencyKey);
        String expectedTrackingToken = accessService.deriveTrackingToken(idempotencyKey);
        existingOrder.setTrackingTokenHash(accessService.hashTrackingToken(expectedTrackingToken));

        when(orderStore.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));
        when(orderStore.countActiveOrders(eq("jane@example.com"), eq("5195551234"))).thenReturn(10L); // Over limit

        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-existing-999"))
                .andExpect(jsonPath("$.trackingToken").value(expectedTrackingToken));

        verify(orderStore, never()).create(any(), any());
    }
}
