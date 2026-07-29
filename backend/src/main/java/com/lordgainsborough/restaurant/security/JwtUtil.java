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
            claims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateTokenForUser(String token, User user) {
        if (user == null) {
            return false;
        }
        try {
            Claims claims = claims(token);
            String tokenUserId = claims.get("userId", String.class);
            Object versionClaim = claims.get("tokenVersion");
            long tokenVersion = versionClaim instanceof Number number ? number.longValue() : 0L;
            return user.getId() != null
                    && user.getId().equals(tokenUserId)
                    && user.getTokenVersion() == tokenVersion;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
