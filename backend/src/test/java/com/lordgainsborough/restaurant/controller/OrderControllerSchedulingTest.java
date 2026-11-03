package com.lordgainsborough.restaurant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerSchedulingTest {

    private static final String TEST_SECRET = "unit-test-tracking-secret-with-at-least-32-bytes";
    private static final ZoneId TORONTO_ZONE = ZoneId.of("America/Toronto");
    // Wednesday Nov 4, 2026 at 14:00 (2:00 PM) in America/Toronto (EST, UTC-5)
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2026, 11, 4, 14, 0, 0, 0, TORONTO_ZONE);

    private CustomerOrderStore orderStore;
    private OrderRealtimeService realtimeService;
    private JwtUtil jwtUtil;
    private UserAuthStore authStore;
    private OrderAccessService accessService;
    private OrderSchedulingValidator schedulingValidator;
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

        Clock clock = Clock.fixed(FIXED_NOW.toInstant(), TORONTO_ZONE);
        schedulingValidator = new OrderSchedulingValidator(clock);

        controller = new OrderController(orderStore, realtimeService, accessService, null, null, null, schedulingValidator);
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
        owner.setId("user-1");
        owner.setName("Jane Doe");
        owner.setEmail("jane@example.com");
        owner.setPhone("5195551234");
        when(jwtUtil.validateToken("owner-jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("owner-jwt")).thenReturn("jane@example.com");
        when(authStore.authenticate(eq("owner-jwt"), any())).thenReturn(Optional.of(owner));

        when(orderStore.create(any(), any())).thenAnswer(inv -> {
            CustomerOrder toSave = inv.getArgument(0);
            toSave.setId("order-saved-101");
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
    @DisplayName("Valid pickup scheduling returns HTTP 200 OK with formatted pickup time")
    void testValidPickupSchedulingAccepted() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("2026-11-05");
        order.setPickupTime("12:30 PM");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value("order-saved-101"))
                .andExpect(jsonPath("$.order.pickupDate").value("2026-11-05"))
                .andExpect(jsonPath("$.order.pickupTime").value("12:30 PM"));

        verify(orderStore).create(any(), any());
    }

    @Test
    @DisplayName("Explicit 'asap' option is accepted and persisted as 'asap'")
    void testAsapOptionAccepted() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("2026-11-04");
        order.setPickupTime("asap");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.pickupTime").value("asap"));

        verify(orderStore).create(any(), any());
    }

    @Test
    @DisplayName("Past pickup date is rejected with HTTP 400 INVALID_PICKUP_DATE")
    void testPastPickupDateRejected() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("2026-11-03"); // Yesterday relative to FIXED_NOW (2026-11-04)
        order.setPickupTime("18:00");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PICKUP_DATE"))
                .andExpect(jsonPath("$.message").value("Pickup date cannot be in the past."));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Past pickup time on today is rejected with HTTP 400 PAST_PICKUP_TIME")
    void testPastPickupTimeTodayRejected() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("2026-11-04"); // Today
        order.setPickupTime("11:30 AM"); // 11:30 AM is before current time 14:00 (2:00 PM)

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAST_PICKUP_TIME"))
                .andExpect(jsonPath("$.message").value("Pickup time cannot be in the past."));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Pickup time outside opening hours (before 11 AM) is rejected with HTTP 400 INVALID_PICKUP_TIME")
    void testPickupTimeBeforeOpeningRejected() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("2026-11-05"); // Tomorrow
        order.setPickupTime("10:00 AM");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PICKUP_TIME"))
                .andExpect(jsonPath("$.message").value("Pickup time must be between 11:00 AM and 9:00 PM."));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Pickup time outside closing hours (after 9 PM) is rejected with HTTP 400 INVALID_PICKUP_TIME")
    void testPickupTimeAfterClosingRejected() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("2026-11-05");
        order.setPickupTime("9:30 PM");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PICKUP_TIME"))
                .andExpect(jsonPath("$.message").value("Pickup time must be between 11:00 AM and 9:00 PM."));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Pickup time not in 30-minute intervals is rejected with HTTP 400 INVALID_PICKUP_TIME")
    void testNon30MinuteIntervalRejected() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("2026-11-05");
        order.setPickupTime("12:15 PM");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PICKUP_TIME"))
                .andExpect(jsonPath("$.message").value("Pickup time must be in 30-minute intervals (e.g. 11:00, 11:30)."));

        verify(orderStore, never()).create(any(), any());
    }

    @Test
    @DisplayName("Arbitrary unparseable pickup date is rejected with HTTP 400 INVALID_PICKUP_DATE")
    void testArbitraryPickupDateRejected() throws Exception {
        CustomerOrder order = sampleOrder();
        order.setPickupDate("Sat Nov 1");
        order.setPickupTime("18:00");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer owner-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PICKUP_DATE"))
                .andExpect(jsonPath("$.message").value("Pickup date must be in YYYY-MM-DD format."));

        verify(orderStore, never()).create(any(), any());
    }
}
