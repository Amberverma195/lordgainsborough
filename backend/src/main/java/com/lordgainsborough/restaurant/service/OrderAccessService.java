package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
public class OrderAccessService {
    public static final String TRACKING_TOKEN_HEADER = "X-Order-Tracking-Token";

    private static final int TRACKING_TOKEN_BYTES = 32;

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public OrderAccessService(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    public String generateTrackingToken() {
        byte[] token = new byte[TRACKING_TOKEN_BYTES];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public String hashTrackingToken(String trackingToken) {
        if (trackingToken == null || trackingToken.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(trackingToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public boolean canAccess(CustomerOrder order, String authHeader, String trackingToken) {
        if (hasValidTrackingToken(order, trackingToken)) {
            return true;
        }
        return getAuthenticatedUser(authHeader)
                .filter(user -> ownsOrder(user, order))
                .isPresent();
    }

    public boolean canAccessWithJwtToken(CustomerOrder order, String jwtToken, String trackingToken) {
        if (hasValidTrackingToken(order, trackingToken)) {
            return true;
        }
        return getAuthenticatedUserFromToken(jwtToken)
                .filter(user -> ownsOrder(user, order))
                .isPresent();
    }

    private boolean hasValidTrackingToken(CustomerOrder order, String trackingToken) {
        String expectedHash = order.getTrackingTokenHash();
        if (expectedHash == null || expectedHash.isBlank() || trackingToken == null || trackingToken.isBlank()) {
            return false;
        }
        byte[] expected = expectedHash.getBytes(StandardCharsets.UTF_8);
        byte[] actual = hashTrackingToken(trackingToken).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private Optional<User> getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return getAuthenticatedUserFromToken(authHeader.substring(7));
    }

    private Optional<User> getAuthenticatedUserFromToken(String token) {
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            return Optional.empty();
        }
        return userRepository.findByEmailIgnoreCase(jwtUtil.getEmailFromToken(token))
                .filter(user -> jwtUtil.validateTokenForUser(token, user));
    }

    private boolean ownsOrder(User user, CustomerOrder order) {
        String userEmail = user.getEmail();
        if (userEmail != null && order.getCustomerEmail() != null
                && userEmail.trim().equalsIgnoreCase(order.getCustomerEmail().trim())) {
            return true;
        }

        String userPhone = user.getPhoneNormalized();
        if (userPhone == null || userPhone.isBlank()) {
            userPhone = normalizePhone(user.getPhone());
        }
        String orderPhone = normalizePhone(order.getCustomerPhone());
        return userPhone.length() == 10 && userPhone.equals(orderPhone);
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) {
            digits = digits.substring(1);
        }
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }
}
