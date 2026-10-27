package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderAccessServiceTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final UserAuthStore userRepository = mock(UserAuthStore.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final OrderAccessService service = new OrderAccessService(jwtUtil, userRepository);

    private CustomerOrder order;

    @BeforeEach
    void setUp() {
        order = new CustomerOrder();
        order.setId("order-1");
        order.setCustomerEmail("amber@example.com");
        order.setCustomerPhone("+1 (519)-614-3615");
    }

    @Test
    void grantsAccessWithMatchingTrackingToken() {
        String token = service.generateTrackingToken();
        order.setTrackingTokenHash(service.hashTrackingToken(token));

        assertTrue(service.canAccess(order, null, token));
    }

    @Test
    void deniesAccessWithWrongTrackingTokenAndNoAuth() {
        order.setTrackingTokenHash(service.hashTrackingToken(service.generateTrackingToken()));

        assertFalse(service.canAccess(order, null, "not-the-real-token"));
        assertFalse(service.canAccess(order, null, null));
    }

    @Test
    void grantsAccessToAuthenticatedOwnerByEmail() {
        User owner = new User();
        owner.setEmail("amber@example.com");
        when(jwtUtil.validateToken("jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("jwt")).thenReturn("amber@example.com");
        when(userRepository.findByEmailIgnoreCase("amber@example.com")).thenReturn(Optional.of(owner));
        when(jwtUtil.validateTokenForUser("jwt", owner)).thenReturn(true);

        assertTrue(service.canAccess(order, "Bearer jwt", null));
    }

    @Test
    void deniesAccessToAuthenticatedNonOwner() {
        User other = new User();
        other.setEmail("someone@example.com");
        other.setPhone("+1 (555)-000-0000");
        when(jwtUtil.validateToken("jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("jwt")).thenReturn("someone@example.com");
        when(userRepository.findByEmailIgnoreCase("someone@example.com")).thenReturn(Optional.of(other));
        when(jwtUtil.validateTokenForUser("jwt", other)).thenReturn(true);

        assertFalse(service.canAccess(order, "Bearer jwt", null));
    }

    @Test
    void normalizesEmailAndUsesPhoneOwnershipFallback() {
        User owner = new User();
        owner.setEmail(" AMBER@EXAMPLE.COM ");
        when(jwtUtil.validateToken("jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("jwt")).thenReturn("amber@example.com");
        when(userRepository.findByEmailIgnoreCase("amber@example.com")).thenReturn(Optional.of(owner));
        when(jwtUtil.validateTokenForUser("jwt", owner)).thenReturn(true);
        assertTrue(service.canAccess(order, "Bearer jwt", null));
        owner.setEmail("different@example.com"); owner.setPhone("+1 (519) 614-3615");
        assertTrue(service.canAccess(order, "Bearer jwt", null));
        owner.setPhone(null); owner.setPhoneNormalized("5196143615");
        assertTrue(service.canAccessWithJwtToken(order, "jwt", null));
    }

    @Test
    void rejectsTokenVersionMismatchEvenForOwner() {
        User owner = new User(); owner.setEmail("amber@example.com");
        when(jwtUtil.validateToken("jwt")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("jwt")).thenReturn("amber@example.com");
        when(userRepository.findByEmailIgnoreCase("amber@example.com")).thenReturn(Optional.of(owner));
        when(jwtUtil.validateTokenForUser("jwt", owner)).thenReturn(false);
        assertFalse(service.canAccess(order,"Bearer jwt",null));
    }

    @Test
    void malformedTrackingTokensCannotMatchEvenTheirOwnStoredHash() {
        for (String malformed : new String[]{"", " ", "short", "a".repeat(44), "!".repeat(43), "a".repeat(42)+"B"}) {
            order.setTrackingTokenHash(service.hashTrackingToken(malformed));
            assertFalse(service.canAccess(order,null,malformed));
        }
    }

    @Test
    void invalidJwtAndPostgresIsolationDoNotConsultLegacyUserStore() {
        assertFalse(service.canAccess(order,"Bearer invalid",null));
        assertFalse(service.canAccessPostgresOrder(order,null));
        org.mockito.Mockito.verifyNoInteractions(userRepository);
        String token = service.generateTrackingToken();
        order.setTrackingTokenHash(service.hashTrackingToken(token));
        assertTrue(service.canAccessPostgresOrder(order,token));
        assertTrue(service.canAccessWithJwtToken(order,null,token));
        org.mockito.Mockito.verifyNoInteractions(userRepository);
    }
}
