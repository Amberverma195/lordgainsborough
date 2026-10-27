package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.*;
import java.util.*;
import java.util.function.Consumer;
public interface ReviewStore {
    record Creation(int status, OrderReview review) {}
    Creation create(String orderId, String authorization, String token, OrderReview request, Consumer<CustomerOrder> committed);
    List<OrderReview> all();
    Optional<OrderReview> moderate(String id, String status);
    default boolean usesPostgres() { return false; }
}
