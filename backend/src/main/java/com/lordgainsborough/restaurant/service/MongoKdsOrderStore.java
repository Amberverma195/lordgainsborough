package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

@Component("mongoKdsOrderStore")
public class MongoKdsOrderStore implements KdsOrderStore {
    private final CustomerOrderRepository orders;
    private final CustomerOrderPresentationService presentation;
    private final CustomerNoShowService noShows;
    public MongoKdsOrderStore(CustomerOrderRepository orders, CustomerOrderPresentationService presentation, CustomerNoShowService noShows) {
        this.orders = orders; this.presentation = presentation; this.noShows = noShows;
    }
    public List<CustomerOrder> byPickupDate(String date) { return presentation.attachCustomerOrderCounts(orders.findByPickupDateOrderByCreatedAtDesc(date)); }
    public List<CustomerOrder> all() { return orders.findAll(Sort.by(Sort.Direction.DESC,"createdAt")); }
    public Optional<CustomerOrder> status(String id, String next, IntUnaryOperator minutes, Consumer<CustomerOrder> afterCommit) {
        return orders.findById(id).map(order -> {
            boolean firstNoShow = KdsOrderTransitions.status(order, next, LocalDateTime.now());
            order.setAverageReadyMinutes(minutes.applyAsInt(order.getAverageReadyMinutes()));
            CustomerOrder saved = orders.save(order);
            if (firstNoShow) noShows.recordNoShow(saved);
            OrderNotifications.deliver(() -> afterCommit.accept(saved));
            return presentation.attachCustomerOrderCount(saved);
        });
    }
    public Optional<CustomerOrder> readyTime(String id, int minutes, Consumer<CustomerOrder> afterCommit) {
        return orders.findById(id).map(order -> {
            order.setAverageReadyMinutes(minutes); CustomerOrder saved = orders.save(order);
            OrderNotifications.deliver(() -> afterCommit.accept(saved));
            return presentation.attachCustomerOrderCount(saved);
        });
    }
    public Optional<CustomerOrder.OrderMessage> message(String id, CustomerOrder.OrderMessage message, Consumer<CustomerOrder> afterCommit) {
        return orders.findById(id).map(order -> {
            if (order.getMessages() == null) order.setMessages(new ArrayList<>());
            order.getMessages().add(message); CustomerOrder saved = orders.save(order);
            OrderNotifications.deliver(() -> afterCommit.accept(saved)); return message;
        });
    }
}
