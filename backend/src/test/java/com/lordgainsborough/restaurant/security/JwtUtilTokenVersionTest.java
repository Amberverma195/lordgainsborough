package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTokenVersionTest {

    private JwtUtil jwtUtil;
    private User user;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(
                jwtUtil,
                "secret",
                "jwt-token-version-test-secret-that-is-at-least-32-bytes-long");
        ReflectionTestUtils.setField(jwtUtil, "expirationTime", 60_000L);

        user = new User();
        user.setId("user-1");
        user.setEmail("member@example.com");
        user.setTokenVersion(0L);
    }

    @Test
    void socketTicketsAreShortLivedAndCannotAuthenticateAccountApis() {
        String ticket = jwtUtil.generateOrderSocketTicket("order-1");
        assertTrue(jwtUtil.validateOrderSocketTicket(ticket, "order-1"));
        assertFalse(jwtUtil.validateOrderSocketTicket(ticket, "order-2"));
        assertFalse(jwtUtil.validateOrderSocketTicket("invalid", "order-1"));
        assertFalse(jwtUtil.validateToken(ticket));
        assertFalse(jwtUtil.validateTokenForUser(ticket, user));
        assertFalse(jwtUtil.validateOrderSocketTicket(jwtUtil.generateToken(user.getId(), user.getEmail()), "order-1"));

        String kdsTicket = jwtUtil.generateKdsSocketTicket();
        assertTrue(jwtUtil.validateKdsSocketTicket(kdsTicket));
        assertFalse(jwtUtil.validateKdsSocketTicket("invalid"));
        assertFalse(jwtUtil.validateKdsSocketTicket(null));
        assertFalse(jwtUtil.validateKdsSocketTicket(""));
        assertFalse(jwtUtil.validateToken(kdsTicket));
        assertFalse(jwtUtil.validateOrderSocketTicket(kdsTicket, "order-1"));
        assertFalse(jwtUtil.validateKdsSocketTicket(ticket));

        javax.crypto.SecretKey key = (javax.crypto.SecretKey) ReflectionTestUtils.invokeMethod(jwtUtil, "getSigningKey");
        String expiredKdsTicket = io.jsonwebtoken.Jwts.builder().subject("kds").claim("purpose", "kds-socket")
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 120_000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 60_000))
                .signWith(key).compact();
        assertFalse(jwtUtil.validateKdsSocketTicket(expiredKdsTicket));
    }

    @Test
    void incrementingUserTokenVersionInvalidatesAnOlderJwt() {
        String oldToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());

        assertTrue(jwtUtil.validateToken(oldToken));
        assertTrue(jwtUtil.validateTokenForUser(oldToken, user));
        assertEquals(0L, jwtUtil.getTokenVersionFromToken(oldToken));

        user.setTokenVersion(user.getTokenVersion() + 1);

        assertTrue(jwtUtil.validateToken(oldToken), "signature and expiry remain valid");
        assertFalse(jwtUtil.validateTokenForUser(oldToken, user), "user version revokes old JWT");

        String newToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        assertEquals(1L, jwtUtil.getTokenVersionFromToken(newToken));
        assertTrue(jwtUtil.validateTokenForUser(newToken, user));
    }
}
