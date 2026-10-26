package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.MenuItemRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Component("mongoCustomerOrderStore")
public class MongoCustomerOrderStore implements CustomerOrderStore {
    private final CustomerOrderRepository orders;
    private final MenuItemRepository menu;
    private final OrderReviewRepository reviews;
    private final StoreSettingsService settings;
    private final CustomerNoShowService noShows;

    public MongoCustomerOrderStore(CustomerOrderRepository orders, MenuItemRepository menu,
            OrderReviewRepository reviews, StoreSettingsService settings, CustomerNoShowService noShows) {
        this.orders = orders;
        this.menu = menu;
        this.reviews = reviews;
        this.settings = settings;
        this.noShows = noShows;
    }

    public Optional<CustomerOrder> findById(String id) { return orders.findById(id); }
    public CustomerOrder create(CustomerOrder order, Consumer<CustomerOrder> afterCommit) {
        CustomerOrder saved = orders.save(order);
        OrderNotifications.deliver(() -> afterCommit.accept(saved));
        return saved;
    }
    public Optional<CustomerOrder.OrderMessage> appendMessage(String id, Predicate<CustomerOrder> authorized,
            CustomerOrder.OrderMessage message, Consumer<CustomerOrder> afterCommit) {
        return orders.findById(id).filter(authorized).map(order -> {
            if (order.getMessages() == null) order.setMessages(new ArrayList<>());
            order.getMessages().add(message);
            CustomerOrder saved = orders.save(order);
            OrderNotifications.deliver(() -> afterCommit.accept(saved));
            return message;
        });
    }
    public Optional<MenuItem> resolveMenuItem(String id, String name) {
        Optional<MenuItem> found = id == null || id.isBlank() ? Optional.empty() : menu.findById(id);
        return found.isPresent() || name == null || name.isBlank()
                ? found : menu.findFirstByNameIgnoreCase(name.trim());
    }
    public StoreSettings settings() { return settings.getSettings(); }
    public boolean pickupDisabled(String email, String phone) { return noShows.isPickupDisabledForCustomer(email, phone); }
    public boolean reviewExists(String id) { return reviews.existsByOrderId(id); }
}
