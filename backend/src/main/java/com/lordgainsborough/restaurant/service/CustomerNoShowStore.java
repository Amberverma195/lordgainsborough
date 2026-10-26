package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import java.util.Optional;

public interface CustomerNoShowStore {
    Optional<User> findCustomer(String email, String phone);
    int countNoShowsForCustomer(User user);
    void recordNoShow(CustomerOrder order);
    boolean isPickupDisabledForCustomer(String email, String phone);
}
