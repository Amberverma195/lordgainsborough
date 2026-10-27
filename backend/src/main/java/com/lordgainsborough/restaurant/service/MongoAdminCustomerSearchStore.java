package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.dto.AdminCustomerSearchResult;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MongoAdminCustomerSearchStore implements AdminCustomerSearchStore {

    private static final int MAX_RESULTS = 25;

    private final UserRepository userRepository;
    private final CustomerOrderRepository orderRepository;

    public MongoAdminCustomerSearchStore(UserRepository userRepository, CustomerOrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }

    public List<AdminCustomerSearchResult> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) {
            return List.of();
        }

        Map<String, User> matched = new LinkedHashMap<>();
        String digits = normalizePhone(query);

        for (User user : userRepository.findByNameContainingIgnoreCase(query)) {
            matched.putIfAbsent(user.getId(), user);
        }
        for (User user : userRepository.findByEmailContainingIgnoreCase(query)) {
            matched.putIfAbsent(user.getId(), user);
        }
        if (digits.length() >= 3) {
            for (User user : userRepository.findByPhoneNormalizedContaining(digits)) {
                matched.putIfAbsent(user.getId(), user);
            }
        }

        List<AdminCustomerSearchResult> results = new ArrayList<>();
        for (User user : matched.values()) {
            if (results.size() >= MAX_RESULTS) {
                break;
            }
            results.add(toResult(user));
        }
        return results;
    }

    private AdminCustomerSearchResult toResult(User user) {
        AdminCustomerSearchResult result = new AdminCustomerSearchResult();
        result.setId(user.getId());
        result.setName(user.getName());
        result.setEmail(user.getEmail());
        result.setPhone(user.getPhone());
        result.setRole(user.getRole());
        result.setMemberSince(user.getMemberSince());
        result.setNoShowCount(user.getNoShowCount());
        result.setPickupDisabled(user.getNoShowCount() >= CustomerNoShowService.NO_SHOW_THRESHOLD);
        result.setOrders(loadOrdersForUser(user));
        return result;
    }

    private List<CustomerOrder> loadOrdersForUser(User user) {
        Map<String, CustomerOrder> byId = new LinkedHashMap<>();

        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            orderRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(user.getEmail().trim())
                    .forEach(order -> byId.putIfAbsent(order.getId(), order));
        }

        String normalizedPhone = user.getPhoneNormalized();
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            normalizedPhone = normalizePhone(user.getPhone());
        }
        if (normalizedPhone.length() == 10 && user.getPhone() != null && !user.getPhone().isBlank()) {
            for (CustomerOrder order : orderRepository.findByCustomerPhoneOrderByCreatedAtDesc(user.getPhone())) {
                if (normalizedPhone.equals(normalizePhone(order.getCustomerPhone()))) {
                    byId.putIfAbsent(order.getId(), order);
                }
            }
        }

        List<CustomerOrder> orders = new ArrayList<>(byId.values());
        orders.sort(Comparator.comparing(
                CustomerOrder::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return orders;
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) {
            digits = digits.substring(1);
        }
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }
}
