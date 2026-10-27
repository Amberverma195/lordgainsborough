package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.OrderCreationResponse;
import com.lordgainsborough.restaurant.dto.OrderStatusResponse;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.service.CustomerNoShowService;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import com.lordgainsborough.restaurant.service.CustomerOrderStore;
import com.lordgainsborough.restaurant.service.OrderMoney;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final CustomerOrderStore orderStore;
    private final OrderRealtimeService realtimeService;
    private final OrderAccessService orderAccessService;

    @Autowired
    public OrderController(CustomerOrderStore orderStore, OrderRealtimeService realtimeService,
            OrderAccessService orderAccessService) {
        this.orderStore = orderStore;
        this.realtimeService = realtimeService;
        this.orderAccessService = orderAccessService;
    }

    public CustomerOrderStore getOrderStore() { return orderStore; }
    public OrderAccessService getOrderAccessService() { return orderAccessService; }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CustomerOrder order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "EMPTY_CART",
                    "message", "Your cart is empty."));
        }

        if (!orderStore.settings().getPickupOrdersEnabled()) {
            return ResponseEntity.status(503).body(Map.of(
                    "code", "PICKUP_ORDERS_PAUSED",
                    "message", "Pickup orders are currently paused. Please check back later."));
        }

        if (orderStore.pickupDisabled(order.getCustomerEmail(), order.getCustomerPhone())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "code", "PICKUP_DISABLED_NO_SHOW",
                    "message", CustomerNoShowService.PICKUP_DISABLED_MESSAGE));
        }

        if (order.getPickupDate() == null || order.getPickupDate().isBlank()) {
            order.setPickupDate(LocalDate.now().toString());
        }

        // Reprice against authoritative menu data. Name fallback repairs carts saved
        // before an older application version regenerated all menu item IDs.
        Map<String, String> menuValidationError = repriceFromMenu(order);
        if (menuValidationError != null) {
            return ResponseEntity.badRequest().body(menuValidationError);
        }

        // Guest orders need contact details, but never require a registered account.
        // Keep existing cart/availability errors ahead of this new validation.
        if (order.getCustomerName() == null || order.getCustomerName().isBlank()
                || order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()
                || order.getCustomerPhone() == null || order.getCustomerPhone().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "MISSING_CUSTOMER_DETAILS",
                    "message", "Customer name, email and phone are required."));
        }

        order.setId(null);
        order.setStatus("NEW");
        order.setAverageReadyMinutes(normalizeReadyMinutes(orderStore.settings().getDefaultReadyMinutes()));
        order.setCreatedAt(LocalDateTime.now());
        order.setAcceptedAt(null);
        order.setReadyAt(null);
        order.setPickedUpAt(null);
        order.setNoShowAt(null);
        order.setReviewed(false);
        order.setMessages(new ArrayList<>());
        order.setSpecialInstructions(normalizeInstructions(order.getSpecialInstructions()));
        try {
            if (orderStore.usesPostgres()) OrderMoney.calculate(order, orderStore.settings().getTaxRate());
            else normalizeItemsAndTotals(order, orderStore.settings().getTaxRate());
        } catch (IllegalArgumentException invalidMoney) {
            return ResponseEntity.badRequest().body(cartChanged());
        }

        String trackingToken = orderAccessService.generateTrackingToken();
        order.setTrackingTokenHash(orderAccessService.hashTrackingToken(trackingToken));

        CustomerOrder saved = orderStore.create(order, this::notifyCreated);
        return ResponseEntity.ok(new OrderCreationResponse(OrderStatusResponse.from(saved), trackingToken));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderStatusResponse> getOrder(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = OrderAccessService.TRACKING_TOKEN_HEADER, required = false) String trackingToken) {
        return orderStore.findById(id)
                .filter(order -> canAccess(order, authHeader, trackingToken))
                .map(order -> {
                    applyReviewState(order);
                    return ResponseEntity.ok(OrderStatusResponse.from(order));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<CustomerOrder.OrderMessage> addCustomerMessage(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = OrderAccessService.TRACKING_TOKEN_HEADER, required = false) String trackingToken,
            @RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        CustomerOrder.OrderMessage message = new CustomerOrder.OrderMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSenderRole("CUSTOMER");
        message.setSenderName("Customer");
        message.setText(text.trim());
        message.setCreatedAt(LocalDateTime.now());
        return orderStore.appendMessage(id, order -> canAccess(order, authHeader, trackingToken), message,
                saved -> {
                    realtimeService.chatMessage(saved, message);
                    if (orderStore.usesPostgres()) realtimeService.orderUpdatedWithCustomerCounts(saved);
                    else realtimeService.orderUpdated(saved);
                }).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    private boolean canAccess(CustomerOrder order, String authHeader, String trackingToken) {
        return orderStore.usesPostgres() ? orderAccessService.canAccessPostgresOrder(order, authHeader, trackingToken)
                : orderAccessService.canAccess(order, authHeader, trackingToken);
    }

    private void notifyCreated(CustomerOrder saved) {
        if (orderStore.usesPostgres()) realtimeService.orderCreatedWithCustomerCounts(saved);
        else realtimeService.orderCreated(saved);
    }

    private static Map<String, String> cartChanged() {
        return Map.of("code", "CART_ITEM_CHANGED",
                "message", "An item in your cart is no longer on the menu. Please remove it and try again.");
    }

    /**
     * Overrides client-supplied item names and prices with authoritative menu data so
     * persisted totals cannot be manipulated by the client. Returns an error payload
     * if an item is unknown or unavailable, otherwise {@code null}.
     */
    private Map<String, String> repriceFromMenu(CustomerOrder order) {
        for (CustomerOrder.OrderItem item : order.getItems()) {
            if (item == null) return cartChanged();
            Optional<MenuItem> menuItemOpt;
            try {
                menuItemOpt = orderStore.resolveMenuItem(item.getMenuItemId(), item.getName());
            } catch (IllegalArgumentException invalidPrice) { return cartChanged(); }

            if (menuItemOpt.isEmpty()) {
                return Map.of(
                        "code", "CART_ITEM_CHANGED",
                        "message", "An item in your cart is no longer on the menu. Please remove it and try again.");
            }

            MenuItem menuItem = menuItemOpt.get();
            if (!menuItem.isAvailable() || menuItem.getPrice() == null) {
                return Map.of(
                        "code", "CART_ITEM_UNAVAILABLE",
                        "message", menuItem.getName() + " is currently unavailable. Please remove it and try again.");
            }

            item.setMenuItemId(menuItem.getId());
            item.setName(menuItem.getName());
            item.setUnitPrice(menuItem.getPrice());
        }
        return null;
    }

    private static final int MAX_INSTRUCTIONS_LENGTH = 500;

    private String normalizeInstructions(String instructions) {
        if (instructions == null) {
            return null;
        }
        String trimmed = instructions.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_INSTRUCTIONS_LENGTH
                ? trimmed.substring(0, MAX_INSTRUCTIONS_LENGTH)
                : trimmed;
    }

    static void normalizeItemsAndTotals(CustomerOrder order, double taxRate) {
        double subtotal = 0;
        for (CustomerOrder.OrderItem item : order.getItems()) {
            if (item.getQuantity() < 1) {
                item.setQuantity(1);
            }
            item.setTotal(roundMoney(item.getUnitPrice() * item.getQuantity()));
            subtotal += item.getTotal();
        }

        order.setSubtotal(roundMoney(subtotal));
        order.setTax(roundMoney(order.getSubtotal() * taxRate));
        order.setTotal(roundMoney(order.getSubtotal() + order.getTax()));
    }

    static double roundMoney(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static int normalizeReadyMinutes(int minutes) {
        if (minutes < 5) {
            return 5;
        }
        if (minutes > 120) {
            return 120;
        }
        return minutes;
    }

    private void applyReviewState(CustomerOrder order) {
        order.setReviewed(order.isReviewed() || orderStore.reviewExists(order.getId()));
    }
}
