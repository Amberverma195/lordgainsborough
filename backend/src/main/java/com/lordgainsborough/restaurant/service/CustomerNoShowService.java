package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class CustomerNoShowService {
    public static final int NO_SHOW_THRESHOLD = 3;
    public static final String NO_SHOW_STATUS = "NO_SHOW";
    public static final String PICKUP_DISABLED_MESSAGE = "Pickup disabled due to recent no-show history";
    private final CustomerNoShowStore store;
    @Autowired public CustomerNoShowService(CustomerNoShowStore store) { this.store = store; }
    public CustomerNoShowService(UserRepository users, CustomerOrderRepository orders) {
        this(new MongoCustomerNoShowStore(users, orders));
    }
    public CustomerNoShowStore getStore() { return store; }
    public boolean isPickupDisabled(User user) { return user != null && user.getNoShowCount() >= NO_SHOW_THRESHOLD; }
    public boolean isPickupDisabledForCustomer(String email, String phone) { return store.isPickupDisabledForCustomer(email, phone); }
    public void recordNoShow(CustomerOrder order) { store.recordNoShow(order); }
    public int countNoShowsForCustomer(User user) { return store.countNoShowsForCustomer(user); }
    public Optional<User> findCustomer(String email, String phone) { return store.findCustomer(email, phone); }
}
