package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

public interface KdsOrderStore {
    List<CustomerOrder> byPickupDate(String date);
    List<CustomerOrder> all();
    Optional<CustomerOrder> status(String id, String status, IntUnaryOperator readyMinutes, Consumer<CustomerOrder> afterCommit);
    Optional<CustomerOrder> readyTime(String id, int minutes, Consumer<CustomerOrder> afterCommit);
    Optional<CustomerOrder.OrderMessage> message(String id, CustomerOrder.OrderMessage message, Consumer<CustomerOrder> afterCommit);
}
