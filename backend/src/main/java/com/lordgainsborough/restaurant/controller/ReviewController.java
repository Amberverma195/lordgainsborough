package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.OrderReview;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class ReviewController {
    private final CustomerOrderRepository orderRepository;
    private final OrderReviewRepository reviewRepository;
    private final OrderRealtimeService realtimeService;
    private final OrderAccessService orderAccessService;

    public ReviewController(
            CustomerOrderRepository orderRepository,
            OrderReviewRepository reviewRepository,
            OrderRealtimeService realtimeService,
            OrderAccessService orderAccessService) {
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.realtimeService = realtimeService;
        this.orderAccessService = orderAccessService;
    }

    @PostMapping("/api/orders/{orderId}/reviews")
    public ResponseEntity<OrderReview> createReview(
            @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = OrderAccessService.TRACKING_TOKEN_HEADER, required = false) String trackingToken,
            @RequestBody OrderReview review) {
        if (review.getItems() == null || review.getItems().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        return orderRepository.findById(orderId)
                .filter(order -> orderAccessService.canAccess(order, authHeader, trackingToken))
                .map(order -> {
                    if (order.isReviewed() || reviewRepository.existsByOrderId(order.getId())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).<OrderReview>build();
                    }

                    List<OrderReview.ReviewItem> normalizedItems = normalizeItems(review.getItems(), order);
                    if (normalizedItems.isEmpty()) {
                        return ResponseEntity.badRequest().<OrderReview>build();
                    }

                    OrderReview savedReview = new OrderReview();
                    savedReview.setOrderId(order.getId());
                    savedReview.setCustomerName(order.getCustomerName());
                    savedReview.setCustomerEmail(order.getCustomerEmail());
                    savedReview.setCustomerPhone(order.getCustomerPhone());
                    savedReview.setStatus("PENDING");
                    savedReview.setItems(normalizedItems);
                    savedReview.setCreatedAt(LocalDateTime.now());
                    savedReview.setUpdatedAt(LocalDateTime.now());
                    OrderReview saved = reviewRepository.save(savedReview);
                    order.setReviewed(true);
                    CustomerOrder savedOrder = orderRepository.save(order);
                    realtimeService.orderUpdated(savedOrder);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/kds/reviews")
    public List<OrderReview> getAllReviews() {
        return reviewRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @PatchMapping("/api/kds/reviews/{id}/status")
    public ResponseEntity<OrderReview> updateReviewStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        String status = body.getOrDefault("status", "").trim().toUpperCase();
        if (!"PENDING".equals(status) && !"PUBLISHED".equals(status) && !"HIDDEN".equals(status)) {
            return ResponseEntity.badRequest().build();
        }

        return reviewRepository.findById(id)
                .map(review -> {
                    review.setStatus(status);
                    review.setUpdatedAt(LocalDateTime.now());
                    return ResponseEntity.ok(reviewRepository.save(review));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private List<OrderReview.ReviewItem> normalizeItems(List<OrderReview.ReviewItem> items, CustomerOrder order) {
        return items.stream()
                .filter(item -> item.getMenuItemId() != null && !item.getMenuItemId().isBlank())
                .filter(item -> item.getRating() > 0 || (item.getDescription() != null && !item.getDescription().trim().isEmpty()))
                .map(item -> {
                    OrderReview.ReviewItem normalized = new OrderReview.ReviewItem();
                    normalized.setMenuItemId(item.getMenuItemId());
                    normalized.setItemName(resolveItemName(order, item.getMenuItemId(), item.getItemName()));
                    normalized.setRating(clampRating(item.getRating()));
                    normalized.setDescription(item.getDescription() == null ? "" : item.getDescription().trim());
                    return normalized;
                })
                .toList();
    }

    private String resolveItemName(CustomerOrder order, String menuItemId, String fallback) {
        return order.getItems().stream()
                .filter(item -> menuItemId.equals(item.getMenuItemId()))
                .map(CustomerOrder.OrderItem::getName)
                .findFirst()
                .orElse(fallback == null ? "Item" : fallback);
    }

    private int clampRating(int rating) {
        if (rating < 0) {
            return 0;
        }
        if (rating > 5) {
            return 5;
        }
        return rating;
    }
}
