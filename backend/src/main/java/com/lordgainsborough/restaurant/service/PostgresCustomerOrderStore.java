package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Isolated PG-017 adapter; does not implement kitchen, review creation or user authentication. */
public class PostgresCustomerOrderStore implements CustomerOrderStore {
    private final JpaCustomerOrderRepository orders;
    private final JpaMenuItemRepository menu;
    private final JpaOrderReviewRepository reviews;
    private final JpaStoreSettingsRepository settings;
    private final JpaUserRepository users;
    private final CustomerOrderMapper mapper = new CustomerOrderMapper();
    private final TransactionTemplate transactions;
    private final JpaTransactionManager manager;
    private final PostgresCustomerNoShowStore pickupRestrictions;

    public PostgresCustomerOrderStore(JpaCustomerOrderRepository orders, JpaMenuItemRepository menu,
            JpaOrderReviewRepository reviews, JpaStoreSettingsRepository settings, JpaUserRepository users,
            JpaTransactionManager transactionManager) {
        this.manager = transactionManager;
        this.pickupRestrictions = new PostgresCustomerNoShowStore(orders,users,transactionManager);
        this.orders = orders;
        this.menu = menu;
        this.reviews = reviews;
        this.settings = settings;
        this.users = users;
        transactions = new TransactionTemplate(transactionManager);
        // A fresh persistence context is essential before locking/loading a message collection.
        // Also ensures notification callbacks never participate in an outer request transaction.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public boolean usesPostgres() { return true; }

    public Optional<CustomerOrder> findById(String id) {
        return transactions.execute(status -> orders.findWithItemsById(id).map(entity -> {
            // Two bounded collection loads avoid a Cartesian product of items x messages.
            entity.getMessages().size();
            return mapper.fromJpa(entity);
        }));
    }

    @Override
    public Optional<CustomerOrder> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return transactions.execute(status -> orders.findWithItemsByIdempotencyKey(idempotencyKey.trim()).map(entity -> {
            entity.getMessages().size();
            return mapper.fromJpa(entity);
        }));
    }

    public CustomerOrder create(CustomerOrder order, Consumer<CustomerOrder> afterCommit) {
        return transactions.execute(status -> {
            JpaCustomerOrder entity = mapper.toJpa(order);
            entity.setId(null); // Never merge a client-chosen parent identifier.
            CustomerOrder saved = mapper.fromJpa(orders.saveAndFlush(entity));
            attachCounts(saved);
            OrderNotifications.afterCommit(() -> afterCommit.accept(saved));
            return saved;
        });
    }

    public Optional<CustomerOrder.OrderMessage> appendMessage(String id, Predicate<CustomerOrder> authorized,
            CustomerOrder.OrderMessage message, Consumer<CustomerOrder> afterCommit) {
        return transactions.execute(status -> orders.findForMessageAppend(id).flatMap(entity -> {
            // Lock the parent BEFORE initializing either collection. Every append sees committed history.
            CustomerOrder current = mapper.fromJpa(entity);
            if (!authorized.test(current)) return Optional.empty();
            entity.addMessage(mapper.toJpa(message));
            orders.flush();
            CustomerOrder saved = mapper.fromJpa(entity);
            attachCounts(saved);
            OrderNotifications.afterCommit(() -> afterCommit.accept(saved));
            return Optional.of(message);
        }));
    }

    public Optional<MenuItem> resolveMenuItem(String id, String name) {
        Optional<JpaMenuItem> found = id == null || id.isBlank() ? Optional.empty() : menu.findById(id);
        if (found.isEmpty() && name != null && !name.isBlank()) {
            List<JpaMenuItem> matches = menu.findAllByNameIgnoreCase(name.trim());
            // An ID is authoritative; a stale ID may only fall back to an unambiguous name.
            if (matches.size() == 1) found = Optional.of(matches.getFirst());
        }
        return found.map(entity -> {
            MenuItem item = new MenuItem();
            item.setId(entity.getId());
            item.setName(entity.getName());
            item.setAvailable(entity.isAvailable());
            item.setPrice(entity.getPrice() == null ? null : OrderMoney.money(entity.getPrice()).doubleValue());
            return item;
        });
    }

    public StoreSettings settings() {
        JpaStoreSettings source = settings.findById(JpaStoreSettings.DEFAULT_ID)
                .orElseThrow(() -> new IllegalStateException("PostgreSQL order settings must be provisioned explicitly"));
        StoreSettings result = new StoreSettings();
        result.setPickupOrdersEnabled(source.isPickupOrdersEnabled());
        result.setDefaultReadyMinutes(source.getDefaultReadyMinutes());
        result.setTaxRate(source.getTaxRate().doubleValue());
        return result;
    }

    public JpaUserRepository getUserRepository() { return users; }
    public JpaCustomerOrderRepository getOrderRepository() { return orders; }

    public jakarta.persistence.EntityManagerFactory getEntityManagerFactory() { return manager.getEntityManagerFactory(); }

    public boolean pickupDisabled(String email, String phone) {
        return pickupRestrictions.isPickupDisabledForCustomer(email,phone);
    }

    private long noShows(String email, String phone) {
        if (email != null && !email.isBlank())
            return orders.countByCustomerEmailIgnoreCaseAndStatus(email.trim(), "NO_SHOW");
        return phone == null || phone.isBlank() ? 0 : orders.countByCustomerPhoneAndStatus(phone.trim(), "NO_SHOW");
    }

    private void attachCounts(CustomerOrder order) {
        String email = order.getCustomerEmail(), phone = order.getCustomerPhone();
        long count = email != null && !email.isBlank() ? orders.countByCustomerEmailIgnoreCase(email.trim())
                : phone != null && !phone.isBlank() ? orders.countByCustomerPhone(phone.trim()) : 1;
        order.setCustomerOrderCount((int) Math.min(Integer.MAX_VALUE, count));
        order.setCustomerNoShowCount((int) Math.min(Integer.MAX_VALUE, noShows(email, phone)));
    }

    private static String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) digits = digits.substring(1);
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }

    public boolean reviewExists(String id) { return reviews.existsByOrderId(id); }

    public JpaStoreSettingsRepository getSettingsRepository() { return settings; }
}
