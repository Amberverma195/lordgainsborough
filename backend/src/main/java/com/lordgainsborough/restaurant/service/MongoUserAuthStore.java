package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.*;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import java.util.*;

/** Legacy Mongo operations, including the historical raw-phone fallback. */
@Component
public class MongoUserAuthStore implements UserAuthStore {
    private final UserRepository users;
    private final CustomerOrderRepository orders;
    private final OrderReviewRepository reviews;
    public MongoUserAuthStore(UserRepository users, CustomerOrderRepository orders, OrderReviewRepository reviews) {
        this.users = users; this.orders = orders; this.reviews = reviews;
    }
    public Optional<User> findByEmailIgnoreCase(String email) { return users.findByEmailIgnoreCase(email); }
    public boolean existsByEmailIgnoreCase(String email) { return users.existsByEmailIgnoreCase(email); }
    public Optional<User> findByPhone(String digits) {
        if (digits == null || digits.length() != 10) return Optional.empty();
        List<User> normalized = users.findByPhoneNormalized(digits);
        if (!normalized.isEmpty()) return Optional.of(normalized.get(0));
        return users.findByPhoneIsNotNull().stream().filter(u -> u.getPhone() != null && !u.getPhone().isBlank())
                .filter(u -> digits.equals(normalizePhone(u.getPhone()))).findFirst();
    }
    public boolean phoneRegisteredByAnotherUser(String digits, String currentId) {
        if (digits == null || digits.length() != 10) return false;
        if (users.findByPhoneNormalized(digits).stream().anyMatch(u -> different(u, currentId))) return true;
        return users.findByPhoneIsNotNull().stream().filter(u -> u.getPhone() != null && !u.getPhone().isBlank())
                .filter(u -> different(u, currentId)).anyMatch(u -> digits.equals(normalizePhone(u.getPhone())));
    }
    public Registration register(User draft, String rawPassword, String otp, SignupOtpChallengeService challenges) {
        var verification = challenges.verifyOtp(draft.getEmail(), otp);
        if (!(verification instanceof SignupOtpChallengeService.VerifyOtpResult.Verified))
            return new Registration(null, verification, false);
        draft.setCreatedAt(java.time.LocalDateTime.now());
        draft.setMemberSince(draft.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")));
        draft.setPassword(com.lordgainsborough.restaurant.security.PasswordUtil.hashPassword(rawPassword));
        try { users.save(draft); }
        catch (DuplicateKeyException conflict) {
            challenges.clear(draft.getEmail());
            return new Registration(null, verification, true);
        }
        challenges.clear(draft.getEmail());
        return new Registration(draft, verification, false);
    }
    public Optional<User> updateName(User user, String token, String name, JwtUtil jwt) {
        user.setName(name); users.save(user); return Optional.of(user);
    }
    public Optional<List<CustomerOrder>> history(User user, String token, JwtUtil jwt) {
        Map<String, CustomerOrder> distinct = new LinkedHashMap<>();
        orders.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(user.getEmail())
                .forEach(o -> distinct.putIfAbsent(o.getId(), o));
        String phone = user.getPhoneNormalized();
        if (phone == null || phone.isBlank()) phone = normalizePhone(user.getPhone());
        if (phone.length() == 10 && user.getPhone() != null && !user.getPhone().isBlank()) {
            for (CustomerOrder order : orders.findByCustomerPhoneOrderByCreatedAtDesc(user.getPhone()))
                if (phone.equals(normalizePhone(order.getCustomerPhone()))) distinct.putIfAbsent(order.getId(), order);
        }
        List<CustomerOrder> result = new ArrayList<>(distinct.values());
        result.forEach(o -> o.setReviewed(o.isReviewed() || reviews.existsByOrderId(o.getId())));
        result.sort(Comparator.comparing(CustomerOrder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return Optional.of(result);
    }
    public PasswordResetService.Result requestPasswordReset(PasswordResetService service, String email, String ip, String key) {
        return service.requestInitial(email, findByEmailIgnoreCase(email), ip, key);
    }
    private static boolean different(User user, String id) {
        return id == null || user.getId() == null || !user.getId().equals(id);
    }
    private static String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) digits = digits.substring(1);
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }
}
