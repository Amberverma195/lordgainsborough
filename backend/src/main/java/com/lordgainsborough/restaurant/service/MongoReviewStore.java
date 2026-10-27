package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.*;
import com.lordgainsborough.restaurant.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Sort;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
@Component
public class MongoReviewStore implements ReviewStore {
    private final CustomerOrderRepository orders;
    private final OrderReviewRepository reviews;
    private final OrderAccessService access;
    public MongoReviewStore(CustomerOrderRepository orders, OrderReviewRepository reviews, OrderAccessService access) {
        this.orders=orders; this.reviews=reviews; this.access=access;
    }
    public Creation create(String id, String authorization, String token, OrderReview request, Consumer<CustomerOrder> committed) {
        return orders.findById(id).filter(order -> access.canAccess(order,authorization,token)).map(order -> {
            if (order.isReviewed() || reviews.existsByOrderId(id)) return new Creation(409,null);
            var items=ReviewItems.normalize(request,order); if (items.isEmpty()) return new Creation(400,null);
            var saved=reviews.save(ReviewItems.serverReview(order,items,LocalDateTime.now()));
            order.setReviewed(true); var updated=orders.save(order);
            OrderNotifications.deliver(() -> committed.accept(updated)); return new Creation(200,saved);
        }).orElseGet(() -> new Creation(404,null));
    }
    public List<OrderReview> all() { return reviews.findAll(Sort.by(Sort.Direction.DESC,"createdAt")); }
    public Optional<OrderReview> moderate(String id,String status) {
        return reviews.findById(id).map(review -> {
            review.setStatus(status); review.setUpdatedAt(LocalDateTime.now()); return reviews.save(review);
        });
    }
}
