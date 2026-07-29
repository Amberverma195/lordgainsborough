package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.OrderCreationResponse;
import com.lordgainsborough.restaurant.dto.OrderStatusResponse;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.MenuItemRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.service.CustomerNoShowService;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import com.lordgainsborough.restaurant.service.StoreSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final CustomerOrderRepository orderRepository;
    private final OrderReviewRepository reviewRepository;
    private final MenuItemRepository menuItemRepository;
    private final OrderRealtimeService realtimeService;
    private final OrderAccessService orderAccessService;
    private final StoreSettingsService storeSettingsService;
    private final CustomerNoShowService customerNoShowService;

    public OrderController(
            CustomerOrderRepository orderRepository,
            OrderReviewRepository reviewRepository,
            MenuItemRepository menuItemRepository,
            OrderRealtimeService realtimeService,
            OrderAccessService orderAccessService,
            StoreSettingsService storeSettingsService,
            CustomerNoShowService customerNoShowService) {
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.menuItemRepository = menuItemRepository;
        this.realtimeService = realtimeService;
        this.orderAccessService = orderAccessService;
        this.storeSettingsService = storeSettingsService;
        this.customerNoShowService = customerNoShowService;
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CustomerOrder order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "EMPTY_CART",
                    "message", "Your cart is empty."));
        }

        if (!storeSettingsService.getSettings().getPickupOrdersEnabled()) {
            return ResponseEntity.status(503).body(Map.of(
                    "code", "PICKUP_ORDERS_PAUSED",
                    "message", "Pickup orders are currently paused. Please check back later."));
        }

        if (customerNoShowService.isPickupDisabledForCustomer(order.getCustomerEmail(), order.getCustomerPhone())) {
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

        order.setId(null);
        order.setStatus("NEW");
        order.setAverageReadyMinutes(normalizeReadyMinutes(storeSettingsService.getSettings().getDefaultReadyMinutes()));
        order.setCreatedAt(LocalDateTime.now());
        order.setAcceptedAt(null);
        order.setReadyAt(null);
        order.setPickedUpAt(null);
        order.setReviewed(false);
        order.setMessages(new ArrayList<>());
        order.setSpecialInstructions(normalizeInstructions(order.getSpecialInstructions()));
        normalizeItemsAndTotals(order, storeSettingsService.getSettings().getTaxRate());

        String trackingToken = orderAccessService.generateTrackingToken();
        order.setTrackingTokenHash(orderAccessService.hashTrackingToken(trackingToken));

        CustomerOrder saved = orderRepository.save(order);
        realtimeService.orderCreated(saved);
        return ResponseEntity.ok(new OrderCreationResponse(OrderStatusResponse.from(saved), trackingToken));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderStatusResponse> getOrder(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = OrderAccessService.TRACKING_TOKEN_HEADER, required = false) String trackingToken) {
        return orderRepository.findById(id)
                .filter(order -> orderAccessService.canAccess(order, authHeader, trackingToken))
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

        Optional<CustomerOrder> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CustomerOrder order = orderOpt.get();
        if (!orderAccessService.canAccess(order, authHeader, trackingToken)) {
            // Avoid leaking existence to unauthorized callers.
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(appendMessage(order, "CUSTOMER", "Customer", text.trim()));
    }

    private CustomerOrder.OrderMessage appendMessage(CustomerOrder order, String senderRole, String senderName, String text) {
        CustomerOrder.OrderMessage message = new CustomerOrder.OrderMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSenderRole(senderRole);
        message.setSenderName(senderName);
        message.setText(text);
        message.setCreatedAt(LocalDateTime.now());

        List<CustomerOrder.OrderMessage> messages = order.getMessages();
        if (messages == null) {
            messages = new ArrayList<>();
            order.setMessages(messages);
        }
        messages.add(message);

        CustomerOrder saved = orderRepository.save(order);
        realtimeService.chatMessage(saved, message);
        realtimeService.orderUpdated(saved);
        return message;
    }

    /**
     * Overrides client-supplied item names and prices with authoritative menu data so
     * persisted totals cannot be manipulated by the client. Returns an error payload
     * if an item is unknown or unavailable, otherwise {@code null}.
     */
    private Map<String, String> repriceFromMenu(CustomerOrder order) {
        for (CustomerOrder.OrderItem item : order.getItems()) {
            Optional<MenuItem> menuItemOpt = Optional.empty();
            if (item.getMenuItemId() != null && !item.getMenuItemId().isBlank()) {
                menuItemOpt = menuItemRepository.findById(item.getMenuItemId());
            }

            if (menuItemOpt.isEmpty() && item.getName() != null && !item.getName().isBlank()) {
                menuItemOpt = menuItemRepository.findFirstByNameIgnoreCase(item.getName().trim());
            }

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
        order.setReviewed(order.isReviewed() || reviewRepository.existsByOrderId(order.getId()));
    }
}
