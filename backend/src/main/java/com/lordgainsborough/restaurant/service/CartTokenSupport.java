package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.security.JwtUtil;
import io.jsonwebtoken.JwtException;
import java.util.Optional;

/** Only handles token parsing errors; database failures must not masquerade as invalid tokens. */
final class CartTokenSupport {
    private CartTokenSupport() {}

    static Optional<String> email(JwtUtil jwt, String token) {
        if (token == null || token.isBlank() || !jwt.validateToken(token)) return Optional.empty();
        try {
            return Optional.ofNullable(jwt.getEmailFromToken(token)).filter(email -> !email.isBlank());
        } catch (JwtException | IllegalArgumentException expiredOrMalformed) {
            return Optional.empty();
        }
    }
}
