package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.OrderCreationResponse;
import com.lordgainsborough.restaurant.dto.OrderStatusResponse;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.service.CustomerNoShowService;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import com.lordgainsborough.restaurant.service.CustomerOrderStore;
import com.lordgainsborough.restaurant.service.OrderMoney;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
    public ResponseEntity<?> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = OrderAccessService.TRACKING_TOKEN_HEADER, required = false) String trackingTokenHeader,
            @RequestBody CustomerOrder order) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", "AUTHENTICATION_REQUIRED",
                    "message", "You must be signed in to place an order."));
        }

        Optional<User> authenticatedUser = orderAccessService.getAuthenticatedUser(authHeader);
        if (authenticatedUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", "INVALID_TOKEN",
                    "message", "Your session is invalid or expired. Please sign in again."));
        }

        User user = authenticatedUser.get();
        ResponseEntity<?> identityMismatch = applyAuthenticatedContactIdentity(order, user);
        if (identityMismatch != null) {
            return identityMismatch;
        }

        String idempotencyKey = idempotencyKeyHeader;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = order.getIdempotencyKey();
        }
        if (idempotencyKey != null) {
            idempotencyKey = idempotencyKey.trim();
            if (idempotencyKey.isBlank()) {
                idempotencyKey = null;
            } else if (idempotencyKey.length() > 128) {
                return ResponseEntity.badRequest().body(Map.of(
                        "code", "INVALID_IDEMPOTENCY_KEY",
                        "message", "Idempotency-Key must not exceed 128 characters."));
            }
        }

        if (idempotencyKey != null) {
            Optional<CustomerOrder> existing = orderStore.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                String trackingToken = orderAccessService.deriveTrackingToken(idempotencyKey);
                return handleIdempotentMatch(order, existing.get(), authHeader, trackingTokenHeader, trackingToken);
            }
        }

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

        // Customer contact details must be present (derived or supplied)
        if (order.getCustomerName() == null || order.getCustomerName().isBlank()
                || order.getCustomerEmail() == null || order.getCustomerEmail().isBlank()
                || order.getCustomerPhone() == null || order.getCustomerPhone().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "MISSING_CUSTOMER_DETAILS",
                    "message", "Customer name, email and phone are required."));
        }

        order.setId(null);
        order.setIdempotencyKey(idempotencyKey);
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

        String trackingToken = idempotencyKey != null
                ? orderAccessService.deriveTrackingToken(idempotencyKey)
                : orderAccessService.generateTrackingToken();
        order.setTrackingTokenHash(orderAccessService.hashTrackingToken(trackingToken));

        CustomerOrder saved;
        try {
            saved = orderStore.create(order, this::notifyCreated);
        } catch (DataIntegrityViolationException duplicate) {
            if (idempotencyKey != null) {
                Optional<CustomerOrder> existing = orderStore.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    return handleIdempotentMatch(order, existing.get(), authHeader, trackingTokenHeader, trackingToken);
                }
            }
            throw duplicate;
        }
        return ResponseEntity.ok(new OrderCreationResponse(OrderStatusResponse.from(saved), trackingToken));
    }

    private ResponseEntity<?> applyAuthenticatedContactIdentity(CustomerOrder order, User user) {
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            if (!order.getCustomerEmail().trim().equalsIgnoreCase(user.getEmail().trim())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", "CUSTOMER_IDENTITY_MISMATCH",
                        "message", "Order customer email does not match authenticated account."));
            }
        }
        order.setCustomerEmail(user.getEmail().trim());

        String userPhone = (user.getPhoneNormalized() != null && !user.getPhoneNormalized().isBlank())
                ? user.getPhoneNormalized().trim()
                : (user.getPhone() != null ? user.getPhone().trim() : "");
        if (!userPhone.isBlank()) {
            String incomingPhoneDigits = normalizePhoneDigits(order.getCustomerPhone());
            String accountPhoneDigits = normalizePhoneDigits(userPhone);
            if (!incomingPhoneDigits.isBlank() && !accountPhoneDigits.isBlank()
                    && !incomingPhoneDigits.equals(accountPhoneDigits)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", "CUSTOMER_IDENTITY_MISMATCH",
                        "message", "Order customer phone does not match authenticated account."));
            }
            order.setCustomerPhone(userPhone);
        }

        if (user.getName() != null && !user.getName().isBlank()) {
            order.setCustomerName(user.getName().trim());
        }

        return null;
    }

    private ResponseEntity<?> handleIdempotentMatch(
            CustomerOrder incoming,
            CustomerOrder existing,
            String authHeader,
            String trackingTokenHeader,
            String trackingToken) {
        if (!matchesExistingOrder(incoming, existing)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "IDEMPOTENT_PAYLOAD_MISMATCH",
                    "message", "An order already exists for this idempotency key with different details."));
        }
        if (authHeader == null && (trackingTokenHeader == null || trackingTokenHeader.isBlank())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", "ORDER_RETRY_UNAUTHORIZED",
                    "message", "Authentication or recovery credential required to retry order."));
        }
        if (!canAccess(existing, authHeader, trackingTokenHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "code", "ORDER_RETRY_UNAUTHORIZED",
                    "message", "You are not authorized to retry or access this existing order."));
        }
        applyReviewState(existing);
        return ResponseEntity.ok(new OrderCreationResponse(OrderStatusResponse.from(existing), trackingToken));
    }

    public ResponseEntity<?> createOrder(
            String idempotencyKeyHeader,
            CustomerOrder order) {
        return createOrder(idempotencyKeyHeader, null, null, order);
    }

    public ResponseEntity<?> createOrder(CustomerOrder order) {
        return createOrder(null, null, null, order);
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

    private boolean matchesExistingOrder(CustomerOrder incoming, CustomerOrder existing) {
        if (incoming == null || existing == null) {
            return false;
        }

        if (incoming.getCustomerName() == null || incoming.getCustomerName().isBlank()
                || existing.getCustomerName() == null || existing.getCustomerName().isBlank()) {
            return false;
        }
        if (!incoming.getCustomerName().trim().equalsIgnoreCase(existing.getCustomerName().trim())) {
            return false;
        }

        if (incoming.getCustomerEmail() == null || incoming.getCustomerEmail().isBlank()
                || existing.getCustomerEmail() == null || existing.getCustomerEmail().isBlank()) {
            return false;
        }
        if (!incoming.getCustomerEmail().trim().equalsIgnoreCase(existing.getCustomerEmail().trim())) {
            return false;
        }

        String incomingPhone = normalizePhoneDigits(incoming.getCustomerPhone());
        String existingPhone = normalizePhoneDigits(existing.getCustomerPhone());
        if (incomingPhone.isBlank() || existingPhone.isBlank() || !incomingPhone.equals(existingPhone)) {
            return false;
        }

        String inDate = (incoming.getPickupDate() == null || incoming.getPickupDate().isBlank())
                ? LocalDate.now().toString()
                : incoming.getPickupDate().trim();
        String exDate = existing.getPickupDate() == null ? "" : existing.getPickupDate().trim();
        if (!inDate.equals(exDate)) {
            return false;
        }

        String inTime = incoming.getPickupTime() == null ? "" : incoming.getPickupTime().trim();
        String exTime = existing.getPickupTime() == null ? "" : existing.getPickupTime().trim();
        if (!inTime.equals(exTime)) {
            return false;
        }

        String inInstructions = normalizeInstructions(incoming.getSpecialInstructions());
        String exInstructions = normalizeInstructions(existing.getSpecialInstructions());
        if (inInstructions == null ? exInstructions != null : !inInstructions.equals(exInstructions)) {
            return false;
        }

        if (incoming.getItems() == null || incoming.getItems().isEmpty()
                || existing.getItems() == null || existing.getItems().isEmpty()) {
            return false;
        }
        if (incoming.getItems().size() != existing.getItems().size()) {
            return false;
        }
        for (int i = 0; i < incoming.getItems().size(); i++) {
            CustomerOrder.OrderItem inItem = incoming.getItems().get(i);
            CustomerOrder.OrderItem exItem = existing.getItems().get(i);
            if (inItem == null || exItem == null) return false;
            if (inItem.getQuantity() != exItem.getQuantity()) return false;

            String inItemId = inItem.getMenuItemId() == null ? "" : inItem.getMenuItemId().trim();
            String exItemId = exItem.getMenuItemId() == null ? "" : exItem.getMenuItemId().trim();
            if (!inItemId.isBlank() && !exItemId.isBlank()) {
                if (!inItemId.equals(exItemId)) return false;
            }

            String inName = inItem.getName() == null ? "" : inItem.getName().trim();
            String exName = exItem.getName() == null ? "" : exItem.getName().trim();
            if (!inName.isBlank() && !exName.isBlank()) {
                if (!inName.equalsIgnoreCase(exName)) return false;
            }

            if (inItemId.isBlank() && inName.isBlank()) return false;
            if (exItemId.isBlank() && exName.isBlank()) return false;

            String inNotes = inItem.getNotes() == null ? "" : inItem.getNotes().trim();
            String exNotes = exItem.getNotes() == null ? "" : exItem.getNotes().trim();
            if (!inNotes.equals(exNotes)) return false;
        }
        return true;
    }

    private static String normalizePhoneDigits(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) digits = digits.substring(1);
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }
}
