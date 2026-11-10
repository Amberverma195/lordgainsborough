package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationTime;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String userId, String email) {
        return generateToken(userId, email, 0L);
    }

    public String generateToken(String userId, String email, long tokenVersion) {
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .claim("tokenVersion", tokenVersion)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    public String getEmailFromToken(String token) {
        return claims(token).getSubject();
    }

    public String getUserIdFromToken(String token) {
        return claims(token).get("userId", String.class);
    }

    public long getTokenVersionFromToken(String token) {
        Object value = claims(token).get("tokenVersion");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public boolean validateToken(String token) {
        try {
            return claims(token).get("purpose") == null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateTokenForUser(String token, User user) {
        if (user == null) {
            return false;
        }
        return validateTokenForIdentity(token, user.getId(), user.getTokenVersion());
    }

    public boolean validateTokenForIdentity(String token, String userId, long currentTokenVersion) {
        try {
            Claims claims = claims(token);
            String tokenUserId = claims.get("userId", String.class);
            Object versionClaim = claims.get("tokenVersion");
            long tokenVersion = versionClaim instanceof Number number ? number.longValue() : 0L;
            return claims.get("purpose") == null && userId != null
                    && userId.equals(tokenUserId)
                    && currentTokenVersion == tokenVersion;
        } catch (Exception e) {
            return false;
        }
    }

    /** A short-lived, read-only room credential; deliberately invalid as an account JWT. */
    public String generateOrderSocketTicket(String orderId) {
        return Jwts.builder().subject("order:" + orderId).claim("purpose", "order-socket")
                .claim("orderId", orderId).issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(getSigningKey()).compact();
    }

    public boolean validateOrderSocketTicket(String token, String orderId) {
        try {
            Claims ticket = claims(token);
            return "order-socket".equals(ticket.get("purpose")) && orderId.equals(ticket.get("orderId"));
        } catch (Exception invalid) { return false; }
    }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
