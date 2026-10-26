package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** PG-017 storage boundary. Related reads must belong to the same dataset as orders. */
public interface CustomerOrderStore {
    Optional<CustomerOrder> findById(String id);
    CustomerOrder create(CustomerOrder order, Consumer<CustomerOrder> afterCommit);
    Optional<CustomerOrder.OrderMessage> appendMessage(String id, Predicate<CustomerOrder> authorized,
            CustomerOrder.OrderMessage message, Consumer<CustomerOrder> afterCommit);
    Optional<MenuItem> resolveMenuItem(String id, String name);
    StoreSettings settings();
    boolean pickupDisabled(String email, String phone);
    boolean reviewExists(String id);
    default boolean usesPostgres() { return false; }
}
