package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.dto.AdminCustomerSearchResult;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository.AdminSearchUser;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Isolated PG-021 reader. Mapping finishes before the read transaction ends. */
public class PostgresAdminCustomerSearchStore implements AdminCustomerSearchStore {
    private static final int MAX_RESULTS = 25;
    private final JpaUserRepository users;
    private final JpaCustomerOrderRepository orders;
    private final TransactionTemplate reads;
    private final EntityManagerFactory emf;

    public PostgresAdminCustomerSearchStore(JpaUserRepository users, JpaCustomerOrderRepository orders,
            JpaTransactionManager manager) {
        this.users = users;
        this.orders = orders;
        this.emf = Objects.requireNonNull(manager.getEntityManagerFactory());
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        // Do not inherit a caller's write transaction or an older persistence context.
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public EntityManagerFactory getEntityManagerFactory() { return emf; }
    public JpaUserRepository getUserRepository() { return users; }
    public JpaCustomerOrderRepository getOrderRepository() { return orders; }

    @Override public List<AdminCustomerSearchResult> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) return List.of();
        return reads.execute(status -> searchInTransaction(query));
    }

    private List<AdminCustomerSearchResult> searchInTransaction(String query) {
        Map<String, AdminSearchUser> matched = new LinkedHashMap<>();
        var limit = PageRequest.of(0, MAX_RESULTS);
        // No name/email column can contain a longer literal. Keep phone normalization independent.
        if (isSearchableText(query)) {
            add(matched, users.findAdminByNameContainingIgnoreCaseOrderByIdAsc(query, limit));
            if (matched.size() < MAX_RESULTS)
                add(matched, users.findAdminByEmailContainingIgnoreCaseOrderByIdAsc(query, limit));
        }
        String digits = normalizePhone(query);
        if (matched.size() < MAX_RESULTS && digits.length() >= 3)
            add(matched, users.findAdminByPhoneNormalizedContainingOrderByIdAsc(digits, limit));
        if (matched.isEmpty()) return List.of();

        List<String> emails = matched.values().stream().map(u -> emailKey(u.getEmail()))
                .filter(Objects::nonNull).distinct().toList();
        List<String> phones = matched.values().stream().filter(u -> validPhone(u))
                .map(AdminSearchUser::getPhone).distinct().toList();
        Map<String, List<CustomerOrder>> byEmail = new HashMap<>();
        Map<String, List<CustomerOrder>> byPhone = new HashMap<>();
        for (CustomerOrder order : new PostgresCustomerHistoryReader(orders).load(emails, phones)) {
            String key = order.getCustomerEmail() == null ? null : order.getCustomerEmail().toLowerCase(Locale.ROOT);
            if (key != null) byEmail.computeIfAbsent(key, ignored -> new ArrayList<>()).add(order);
            byPhone.computeIfAbsent(order.getCustomerPhone(), ignored -> new ArrayList<>()).add(order);
        }
        List<AdminCustomerSearchResult> results = new ArrayList<>();
        for (AdminSearchUser user : matched.values()) {
            Map<String, CustomerOrder> distinct = new LinkedHashMap<>();
            for (CustomerOrder order : byEmail.getOrDefault(emailKey(user.getEmail()), List.of()))
                distinct.putIfAbsent(order.getId(), order);
            if (validPhone(user)) {
                for (CustomerOrder order : byPhone.getOrDefault(user.getPhone(), List.of())) {
                    if (phoneKey(user).equals(normalizePhone(order.getCustomerPhone())))
                        distinct.putIfAbsent(order.getId(), order);
                }
            }
            List<CustomerOrder> customerOrders = new ArrayList<>(distinct.values());
            // Legacy comparator deliberately places null timestamps first after reversal.
            customerOrders.sort(Comparator.comparing(CustomerOrder::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed());
            AdminCustomerSearchResult result = new AdminCustomerSearchResult();
            result.setId(user.getId());
            result.setName(user.getName());
            result.setEmail(user.getEmail());
            result.setPhone(user.getPhone());
            result.setRole(user.getRole());
            result.setMemberSince(user.getMemberSince());
            result.setNoShowCount(user.getNoShowCount());
            result.setPickupDisabled(user.getNoShowCount() >= CustomerNoShowService.NO_SHOW_THRESHOLD);
            // Historical search returns stored orders with their default transient count fields.
            result.setOrders(customerOrders);
            results.add(result);
        }
        return results;
    }

    private static void add(Map<String, AdminSearchUser> matched, List<AdminSearchUser> candidates) {
        // 25 per category suffices: at most the already-selected users can be duplicates.
        for (AdminSearchUser user : candidates) {
            if (matched.size() == MAX_RESULTS) break;
            matched.putIfAbsent(user.getId(), user);
        }
    }
    private static boolean isSearchableText(String query) {
        if (query.codePointCount(0, query.length()) > 255 || query.indexOf(0) >= 0) return false;
        for (int i = 0; i < query.length(); i++) {
            char character = query.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i == query.length() || !Character.isLowSurrogate(query.charAt(i))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }
    private static String emailKey(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }
    private static String phoneKey(AdminSearchUser user) {
        return user.getPhoneNormalized() == null || user.getPhoneNormalized().isBlank()
                ? normalizePhone(user.getPhone()) : user.getPhoneNormalized();
    }
    private static boolean validPhone(AdminSearchUser user) {
        return phoneKey(user).length() == 10 && user.getPhone() != null && !user.getPhone().isBlank();
    }
    private static String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) digits = digits.substring(1);
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }
}
