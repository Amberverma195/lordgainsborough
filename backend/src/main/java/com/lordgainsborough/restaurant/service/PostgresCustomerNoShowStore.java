package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Comparator;
import java.util.Optional;

/** Active NO_SHOW counts; record joins the caller's KDS transaction rather than committing independently. */
public class PostgresCustomerNoShowStore implements CustomerNoShowStore {
    private final JpaCustomerOrderRepository orders;
    private final JpaUserRepository users;
    private final JpaTransactionManager manager;
    private final TransactionTemplate transactions;
    public PostgresCustomerNoShowStore(JpaCustomerOrderRepository orders, JpaUserRepository users, JpaTransactionManager manager) {
        this.orders = orders; this.users = users; this.manager = manager;
        transactions = new TransactionTemplate(manager); // REQUIRED: shares status update and rollback.
    }
    public EntityManagerFactory getEntityManagerFactory() { return manager.getEntityManagerFactory(); }
    public JpaTransactionManager getTransactionManager() { return manager; }

    @Override public Optional<User> findCustomer(String email, String phone) {
        Optional<JpaUser> found = hasEmail(email) ? users.findByEmailIgnoreCase(email.trim()) : Optional.empty();
        if (found.isEmpty() && !normalizePhone(phone).isEmpty())
            found = users.findByPhoneNormalized(normalizePhone(phone)).stream().min(Comparator.comparing(JpaUser::getId));
        return found.map(this::projection);
    }
    @Override public int countNoShowsForCustomer(User user) {
        if (user == null) return 0;
        long count = hasEmail(user.getEmail()) ? orders.countByCustomerEmailIgnoreCaseAndStatus(user.getEmail().trim(), "NO_SHOW")
                : user.getPhone() != null && !user.getPhone().isBlank() ? orders.countByCustomerPhoneAndStatus(user.getPhone().trim(), "NO_SHOW") : 0;
        return (int) Math.min(Integer.MAX_VALUE, count);
    }
    @Override public boolean isPickupDisabledForCustomer(String email, String phone) {
        return findCustomer(email, phone).map(user -> user.getNoShowCount() >= CustomerNoShowService.NO_SHOW_THRESHOLD
                || countNoShowsForCustomer(user) >= CustomerNoShowService.NO_SHOW_THRESHOLD).orElse(false);
    }
    @Override public void recordNoShow(CustomerOrder order) {
        transactions.executeWithoutResult(status -> {
            // Global KDS order: lock order parent first, then the selected user. Never lock another order here.
            Optional<JpaUser> found = hasEmail(order.getCustomerEmail())
                    ? users.findForNoShowByEmailIgnoreCase(order.getCustomerEmail().trim()) : Optional.empty();
            String phone = normalizePhone(order.getCustomerPhone());
            if (found.isEmpty() && !phone.isEmpty()) found = users.findFirstByPhoneNormalizedOrderByIdAsc(phone);
            found.ifPresent(user -> {
                int count = countNoShowsForCustomer(projection(user));
                if (users.updateNoShowCount(user.getId(),count) != 1) throw new IllegalStateException("No-show counter synchronization failed");
                // Keep the locked managed entity consistent after the targeted JPQL update.
                var entityManager = org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(getEntityManagerFactory());
                if (entityManager == null) throw new IllegalStateException("No-show update requires the active JPA transaction");
                entityManager.refresh(user);
            });
        });
    }
    private User projection(JpaUser source) {
        // Compatibility projection for no-show APIs only, never a PostgreSQL authentication identity.
        User user = new User(); user.setId(source.getId()); user.setName(source.getName());
        user.setEmail(source.getEmail()); user.setPhone(source.getPhone()); user.setPhoneNormalized(source.getPhoneNormalized());
        user.setNoShowCount(source.getNoShowCount()); return user;
    }
    private static boolean hasEmail(String email) { return email != null && !email.isBlank(); }
    private static String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) digits = digits.substring(1);
        return digits.length() > 10 ? digits.substring(0,10) : digits;
    }

    public JpaCustomerOrderRepository getOrderRepository() { return orders; }
    public JpaUserRepository getUserRepository() { return users; }
}
