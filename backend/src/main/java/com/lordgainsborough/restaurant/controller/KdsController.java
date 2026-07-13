package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.service.CustomerNoShowService;
import com.lordgainsborough.restaurant.service.CustomerOrderPresentationService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/kds")
@CrossOrigin(origins = "*")
public class KdsController {
    private final CustomerOrderRepository orderRepository;
    private final OrderRealtimeService realtimeService;
    private final CustomerOrderPresentationService presentationService;
    private final CustomerNoShowService customerNoShowService;

    public KdsController(
            CustomerOrderRepository orderRepository,
            OrderRealtimeService realtimeService,
            CustomerOrderPresentationService presentationService,
            CustomerNoShowService customerNoShowService) {
        this.orderRepository = orderRepository;
        this.realtimeService = realtimeService;
        this.presentationService = presentationService;
        this.customerNoShowService = customerNoShowService;
    }

    @GetMapping("/access")
    public ResponseEntity<Map<String, Object>> checkAccess() {
        // Interceptor already enforced the IP allowlist (Layer 2). Reaching here means allowed.
        return ResponseEntity.ok(Map.of("allowed", true));
    }

    @GetMapping("/orders")
    public List<CustomerOrder> getOrders(@RequestParam(required = false) String date) {
        String pickupDate = date == null || date.isBlank() ? LocalDate.now().toString() : date;
        return presentationService.attachCustomerOrderCounts(
                orderRepository.findByPickupDateOrderByCreatedAtDesc(pickupDate));
    }

    @GetMapping("/orders/all")
    public List<CustomerOrder> getAllOrders() {
        return orderRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @PatchMapping("/orders/{id}/status")
    public ResponseEntity<CustomerOrder> updateStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", "")).trim().toUpperCase();
        if (!isValidStatus(status)) {
            return ResponseEntity.badRequest().build();
        }

        return orderRepository.findById(id)
                .map(order -> {
                    String previousStatus = order.getStatus();
                    boolean firstNoShow = "NO_SHOW".equals(status)
                            && !"NO_SHOW".equalsIgnoreCase(previousStatus == null ? "" : previousStatus)
                            && order.getNoShowAt() == null;

                    order.setStatus(status);
                    if (body.containsKey("averageReadyMinutes")) {
                        order.setAverageReadyMinutes(OrderController.normalizeReadyMinutes(toInt(body.get("averageReadyMinutes"), order.getAverageReadyMinutes())));
                    }

                    LocalDateTime now = LocalDateTime.now();
                    if ("ACCEPTED".equals(status) && order.getAcceptedAt() == null) {
                        order.setAcceptedAt(now);
                    }
                    if ("READY".equals(status) && order.getReadyAt() == null) {
                        order.setReadyAt(now);
                    }
                    if ("PICKED_UP".equals(status) && order.getPickedUpAt() == null) {
                        order.setPickedUpAt(now);
                    }
                    if (firstNoShow) {
                        order.setNoShowAt(now);
                    }

                    CustomerOrder saved = orderRepository.save(order);
                    if (firstNoShow) {
                        customerNoShowService.recordNoShow(saved);
                    }
                    realtimeService.orderUpdated(saved);
                    return ResponseEntity.ok(presentationService.attachCustomerOrderCount(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/orders/{id}/ready-time")
    public ResponseEntity<CustomerOrder> updateReadyTime(@PathVariable String id, @RequestBody Map<String, Object> body) {
        int averageReadyMinutes = OrderController.normalizeReadyMinutes(toInt(body.get("averageReadyMinutes"), 25));
        return orderRepository.findById(id)
                .map(order -> {
                    order.setAverageReadyMinutes(averageReadyMinutes);
                    CustomerOrder saved = orderRepository.save(order);
                    realtimeService.orderUpdated(saved);
                    return ResponseEntity.ok(presentationService.attachCustomerOrderCount(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/orders/{id}/messages")
    public ResponseEntity<CustomerOrder.OrderMessage> addKdsMessage(@PathVariable String id, @RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return orderRepository.findById(id)
                .map(order -> {
                    CustomerOrder.OrderMessage message = new CustomerOrder.OrderMessage();
                    message.setId(UUID.randomUUID().toString());
                    message.setSenderRole("KDS");
                    message.setSenderName("Kitchen");
                    message.setText(text.trim());
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
                    return ResponseEntity.ok(message);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private boolean isValidStatus(String status) {
        return "NEW".equals(status)
                || "ACCEPTED".equals(status)
                || "READY".equals(status)
                || "PICKED_UP".equals(status)
                || "NO_SHOW".equals(status);
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
