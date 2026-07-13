package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.OrderCreationResponse;
import com.lordgainsborough.restaurant.dto.OrderStatusResponse;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.MenuItemRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import com.lordgainsborough.restaurant.service.StoreSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderControllerTest {

    private final CustomerOrderRepository orderRepository = mock(CustomerOrderRepository.class);
    private final OrderReviewRepository reviewRepository = mock(OrderReviewRepository.class);
    private final MenuItemRepository menuItemRepository = mock(MenuItemRepository.class);
    private final OrderRealtimeService realtimeService = mock(OrderRealtimeService.class);
    private final OrderAccessService orderAccessService = mock(OrderAccessService.class);
    private final StoreSettingsService storeSettingsService = mock(StoreSettingsService.class);
    private final OrderController controller = new OrderController(
            orderRepository, reviewRepository, menuItemRepository, realtimeService, orderAccessService, storeSettingsService);

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(
                orderRepository, reviewRepository, menuItemRepository, realtimeService, orderAccessService, storeSettingsService);
        StoreSettings settings = new StoreSettings();
        settings.setPickupOrdersEnabled(true);
        settings.setTaxRate(0.13);
        settings.setDefaultReadyMinutes(25);
        when(storeSettingsService.getSettings()).thenReturn(settings);
    }

    @Test
    void getOrderWithoutAccessReturnsNotFound() {
        CustomerOrder order = new CustomerOrder();
        order.setId("order-1");
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderAccessService.canAccess(any(), any(), any())).thenReturn(false);

        ResponseEntity<OrderStatusResponse> response = controller.getOrder("order-1", null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getOrderWithAccessReturnsStatus() {
        CustomerOrder order = new CustomerOrder();
        order.setId("order-1");
        order.setStatus("NEW");
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(orderAccessService.canAccess(any(), any(), any())).thenReturn(true);
        when(reviewRepository.existsByOrderId("order-1")).thenReturn(false);

        ResponseEntity<OrderStatusResponse> response = controller.getOrder("order-1", null, "token");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("order-1", response.getBody().getId());
    }

    @Test
    void createOrderRejectsWhenPickupOrdersDisabled() {
        StoreSettings settings = new StoreSettings();
        settings.setPickupOrdersEnabled(false);
        settings.setTaxRate(0.13);
        settings.setDefaultReadyMinutes(25);
        when(storeSettingsService.getSettings()).thenReturn(settings);

        CustomerOrder order = orderWith("pizza-1", 17.99);
        MenuItem menuItem = new MenuItem("pizza-1", "Pepperoni Pizza", "", 17.99, "Pizzas", "", true);
        when(menuItemRepository.findById("pizza-1")).thenReturn(Optional.of(menuItem));

        ResponseEntity<OrderCreationResponse> response = controller.createOrder(order);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrderRejectsUnknownMenuItem() {
        CustomerOrder order = orderWith("ghost-item", 5.0);
        when(menuItemRepository.findById("ghost-item")).thenReturn(Optional.empty());

        ResponseEntity<OrderCreationResponse> response = controller.createOrder(order);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrderRepricesFromMenuAndReturnsTrackingToken() {
        CustomerOrder order = orderWith("pizza-1", 1.00); // client tries to pay $1
        MenuItem menuItem = new MenuItem("pizza-1", "Pepperoni Pizza", "", 17.99, "Pizzas", "", true);
        when(menuItemRepository.findById("pizza-1")).thenReturn(Optional.of(menuItem));
        when(orderAccessService.generateTrackingToken()).thenReturn("plain-token");
        when(orderAccessService.hashTrackingToken("plain-token")).thenReturn("hashed-token");
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(invocation -> {
            CustomerOrder saved = invocation.getArgument(0);
            saved.setId("order-1");
            return saved;
        });

        ResponseEntity<OrderCreationResponse> response = controller.createOrder(order);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("plain-token", response.getBody().getTrackingToken());
        // Authoritative menu price (17.99), not client-supplied 1.00.
        assertEquals(17.99, response.getBody().getOrder().getSubtotal());
    }

    private CustomerOrder orderWith(String menuItemId, double clientUnitPrice) {
        CustomerOrder order = new CustomerOrder();
        CustomerOrder.OrderItem item = new CustomerOrder.OrderItem();
        item.setMenuItemId(menuItemId);
        item.setName("Tampered Name");
        item.setQuantity(1);
        item.setUnitPrice(clientUnitPrice);
        order.setItems(List.of(item));
        order.setPickupDate("2026-06-06");
        order.setPickupTime("12:00 PM");
        return order;
    }
}
