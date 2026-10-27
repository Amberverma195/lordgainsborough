package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import java.util.*;

/** Shared PG-021/PG-022 reader. Caller owns the read transaction and contact authorization. */
final class PostgresCustomerHistoryReader {
    private final JpaCustomerOrderRepository orders;
    private final CustomerOrderMapper mapper = new CustomerOrderMapper();
    PostgresCustomerHistoryReader(JpaCustomerOrderRepository orders) { this.orders = orders; }
    List<CustomerOrder> load(List<String> emails, List<String> phones) {
        if (emails.isEmpty() && phones.isEmpty()) return List.of();
        List<JpaCustomerOrder> history = orders.findAdminHistoryByContacts(
                emails.isEmpty() ? Collections.singletonList(null) : emails,
                phones.isEmpty() ? Collections.singletonList(null) : phones);
        List<CustomerOrder> result = new ArrayList<>();
        for (int start = 0; start < history.size(); start += 128) {
            var batch = history.subList(start, Math.min(start + 128, history.size()));
            var ids = batch.stream().map(JpaCustomerOrder::getId).toList();
            orders.findAdminHistoryItemsByIds(ids);
            orders.findKdsMessagesByIds(ids);
            for (var entity : batch) {
                var order = mapper.fromJpa(entity);
                order.setTrackingTokenHash(null);
                result.add(order);
            }
        }
        return result;
    }
}
