package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import org.springframework.stereotype.Component;

@Component("mongoCustomerOrderCountStore")
public class MongoCustomerOrderCountStore implements CustomerOrderCountStore {
    private final CustomerOrderRepository orders;
    public MongoCustomerOrderCountStore(CustomerOrderRepository orders) { this.orders = orders; }
    public long byEmail(String email, boolean noShowsOnly) {
        return noShowsOnly ? orders.countByCustomerEmailIgnoreCaseAndStatus(email, "NO_SHOW") : orders.countByCustomerEmailIgnoreCase(email);
    }
    public long byPhone(String phone, boolean noShowsOnly) {
        return noShowsOnly ? orders.countByCustomerPhoneAndStatus(phone, "NO_SHOW") : orders.countByCustomerPhone(phone);
    }
}
