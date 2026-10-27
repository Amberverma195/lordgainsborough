package com.lordgainsborough.restaurant.controller;
import com.lordgainsborough.restaurant.model.OrderReview;
import com.lordgainsborough.restaurant.repository.*;
import com.lordgainsborough.restaurant.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class ReviewController {
    private final ReviewStore store;
    private final OrderRealtimeService realtime;
    @Autowired public ReviewController(ReviewStore store, OrderRealtimeService realtime) { this.store=store; this.realtime=realtime; }
    public ReviewController(CustomerOrderRepository orders, OrderReviewRepository reviews, OrderRealtimeService realtime, OrderAccessService access) {
        this(new MongoReviewStore(orders,reviews,access),realtime);
    }
    public ReviewStore getReviewStore() { return store; }
    @PostMapping("/api/orders/{orderId}/reviews")
    public ResponseEntity<OrderReview> createReview(@PathVariable String orderId,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value=OrderAccessService.TRACKING_TOKEN_HEADER,required=false) String token,
            @RequestBody OrderReview request) {
        if (request==null || request.getItems()==null || request.getItems().isEmpty()) return ResponseEntity.badRequest().build();
        var result=store.create(orderId,authorization,token,request,saved -> {
            if (store.usesPostgres()) realtime.orderUpdatedWithCustomerCounts(saved); else realtime.orderUpdated(saved);
        });
        return ResponseEntity.status(result.status()).body(result.review());
    }
    @GetMapping("/api/kds/reviews") public List<OrderReview> getAllReviews() { return store.all(); }
    @PatchMapping("/api/kds/reviews/{id}/status")
    public ResponseEntity<OrderReview> updateReviewStatus(@PathVariable String id,@RequestBody Map<String,String> body) {
        String value=body==null ? null : body.get("status");
        String status=value==null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING","PUBLISHED","HIDDEN").contains(status)) return ResponseEntity.badRequest().build();
        return store.moderate(id,status).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
