package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.OrderReview;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderReviewRepository extends MongoRepository<OrderReview, String> {
    List<OrderReview> findByOrderIdOrderByCreatedAtDesc(String orderId);
    boolean existsByOrderId(String orderId);
}
