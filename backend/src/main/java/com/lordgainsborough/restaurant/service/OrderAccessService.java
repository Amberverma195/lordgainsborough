package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class OrderAccessService {
    public static final String TRACKING_TOKEN_HEADER = "X-Order-Tracking-Token";

    private static final int TRACKING_TOKEN_BYTES = 32;

    private final JwtUtil jwtUtil;
    private final UserAuthStore identityStore;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] trackingTokenHmacKey;

    @Autowired
    public OrderAccessService(
            JwtUtil jwtUtil,
            UserAuthStore identityStore,
            @Value("${app.orders.tracking-token-secret:${jwt.secret:}}") String configuredSecret) {
        this.jwtUtil = jwtUtil;
        this.identityStore = identityStore;
        this.trackingTokenHmacKey = initializeHmacKey(configuredSecret);
    }

    public OrderAccessService(JwtUtil jwtUtil, UserAuthStore identityStore) {
        this(jwtUtil, identityStore, null);
    }

    public UserAuthStore getIdentityStore() { return identityStore; }
    public JwtUtil getJwtUtil() { return jwtUtil; }
    public boolean hasValidSocketTicket(String token, String orderId) {
        return jwtUtil.validateOrderSocketTicket(token, orderId);
    }

    private byte[] initializeHmacKey(String configuredSecret) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            return deriveKeyBytes(configuredSecret);
        }
        byte[] ephemeral = new byte[TRACKING_TOKEN_BYTES];
        secureRandom.nextBytes(ephemeral);
        return ephemeral;
    }

    private static byte[] deriveKeyBytes(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() == 64 && trimmed.matches("^[0-9a-fA-F]{64}$")) {
            try {
                return HexFormat.of().parseHex(trimmed);
            } catch (Exception ignored) {
                // fall through to digest
            }
        }
        byte[] utf8 = trimmed.getBytes(StandardCharsets.UTF_8);
        if (utf8.length == 32) {
            return utf8;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(utf8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public String generateTrackingToken() {
        byte[] token = new byte[TRACKING_TOKEN_BYTES];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public String deriveTrackingToken(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return generateTrackingToken();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.trackingTokenHmacKey, "HmacSHA256"));
            byte[] derived = mac.doFinal(idempotencyKey.trim().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
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

    /** Legacy signature remains guest-token-only. */
    public boolean canAccessPostgresOrder(CustomerOrder order, String trackingToken) {
        return hasValidTrackingToken(order, trackingToken);
    }

    public boolean canAccessPostgresOrder(CustomerOrder order, String authorization, String trackingToken) {
        if (hasValidTrackingToken(order, trackingToken)) return true;
        return identityStore instanceof PostgresUserAuthStore
                && getAuthenticatedUser(authorization).filter(user -> ownsOrder(user, order)).isPresent();
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
        if (expectedHash == null || expectedHash.isBlank() || trackingToken == null
                || !trackingToken.matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(trackingToken);
            if (decoded.length != TRACKING_TOKEN_BYTES || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded).equals(trackingToken)) return false;
        } catch (IllegalArgumentException invalid) { return false; }
        byte[] expected = expectedHash.getBytes(StandardCharsets.UTF_8);
        byte[] actual = hashTrackingToken(trackingToken).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public Optional<User> getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return getAuthenticatedUserFromToken(authHeader.substring(7));
    }

    public Optional<User> getAuthenticatedUserFromToken(String token) {
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            return Optional.empty();
        }
        return identityStore.authenticate(token, jwtUtil);
    }

    private boolean ownsOrder(User user, CustomerOrder order) {
        String userEmail = user.getEmail();
        return userEmail != null && order.getCustomerEmail() != null
                && userEmail.trim().equalsIgnoreCase(order.getCustomerEmail().trim());
    }
}
