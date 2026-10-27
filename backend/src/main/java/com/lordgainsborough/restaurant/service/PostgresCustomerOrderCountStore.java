package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import jakarta.persistence.EntityManagerFactory;

public class PostgresCustomerOrderCountStore implements CustomerOrderCountStore {
    private final JpaCustomerOrderRepository orders;
    private final EntityManagerFactory emf;
    public PostgresCustomerOrderCountStore(JpaCustomerOrderRepository orders, EntityManagerFactory emf) {
        this.orders = orders; this.emf = emf;
    }
    public EntityManagerFactory getEntityManagerFactory() { return emf; }
    public long byEmail(String email, boolean noShowsOnly) {
        return noShowsOnly ? orders.countByCustomerEmailIgnoreCaseAndStatus(email, "NO_SHOW") : orders.countByCustomerEmailIgnoreCase(email);
    }
    public long byPhone(String phone, boolean noShowsOnly) {
        return noShowsOnly ? orders.countByCustomerPhoneAndStatus(phone, "NO_SHOW") : orders.countByCustomerPhone(phone);
    }

    public JpaCustomerOrderRepository getOrderRepository() { return orders; }
}
