package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CustomerOrderPresentationService {
    private static final String NO_SHOW_STATUS = "NO_SHOW";

    private final CustomerOrderCountStore counts;

    public CustomerOrderPresentationService(CustomerOrderRepository orderRepository) {
        this(new MongoCustomerOrderCountStore(orderRepository));
    }

    @Autowired
    public CustomerOrderPresentationService(CustomerOrderCountStore counts) { this.counts = counts; }
    public CustomerOrderCountStore getCountStore() { return counts; }

    public CustomerOrder attachCustomerOrderCount(CustomerOrder order) {
        order.setCustomerOrderCount(resolveCustomerOrderCount(order));
        order.setCustomerNoShowCount(resolveCustomerNoShowCount(order));
        return order;
    }

    public List<CustomerOrder> attachCustomerOrderCounts(List<CustomerOrder> orders) {
        Map<String, Integer> orderCountCache = new HashMap<>();
        Map<String, Integer> noShowCountCache = new HashMap<>();
        for (CustomerOrder order : orders) {
            String key = customerKey(order);
            order.setCustomerOrderCount(
                    orderCountCache.computeIfAbsent(key, ignored -> resolveCustomerOrderCount(order)));
            order.setCustomerNoShowCount(
                    noShowCountCache.computeIfAbsent(key, ignored -> resolveCustomerNoShowCount(order)));
        }
        return orders;
    }

    private String customerKey(CustomerOrder order) {
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            return "email:" + order.getCustomerEmail().trim().toLowerCase();
        }
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            return "phone:" + order.getCustomerPhone().trim();
        }
        return "order:" + order.getId();
    }

    private int resolveCustomerOrderCount(CustomerOrder order) {
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            return (int) counts.byEmail(order.getCustomerEmail().trim(), false);
        }
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            return (int) counts.byPhone(order.getCustomerPhone().trim(), false);
        }
        return 1;
    }

    private int resolveCustomerNoShowCount(CustomerOrder order) {
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            return (int) counts.byEmail(order.getCustomerEmail().trim(), true);
        }
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            return (int) counts.byPhone(order.getCustomerPhone().trim(), true);
        }
        return NO_SHOW_STATUS.equals(order.getStatus()) ? 1 : 0;
    }
}
