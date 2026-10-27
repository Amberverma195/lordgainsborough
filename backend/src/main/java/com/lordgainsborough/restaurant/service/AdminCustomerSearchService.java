package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.dto.AdminCustomerSearchResult;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AdminCustomerSearchService {
    private final AdminCustomerSearchStore store;

    public AdminCustomerSearchService(UserRepository users, CustomerOrderRepository orders) {
        this(new MongoAdminCustomerSearchStore(users, orders));
    }

    @Autowired
    public AdminCustomerSearchService(AdminCustomerSearchStore store) { this.store = store; }

    public AdminCustomerSearchStore getStore() { return store; }

    public List<AdminCustomerSearchResult> search(String rawQuery) { return store.search(rawQuery); }
}
