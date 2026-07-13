package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.service.KdsAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminAuthInterceptorTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final KdsAccessService kdsAccessService = mock(KdsAccessService.class);
    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor(jwtUtil, userRepository, kdsAccessService);

    @Test
    void allowsAdminCorsPreflightWithoutAuthorization() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/kds/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
        verifyNoInteractions(jwtUtil, userRepository, kdsAccessService);
    }

    @Test
    void allowsOperationalKdsWhenIpAllowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kds/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(kdsAccessService.isRequestAllowed(any())).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
        verifyNoInteractions(jwtUtil, userRepository);
    }

    @Test
    void rejectsOperationalKdsWhenIpDenied() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kds/access");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(kdsAccessService.isRequestAllowed(any())).thenReturn(false);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(404, response.getStatus());
        verifyNoInteractions(jwtUtil, userRepository);
    }

    @Test
    void rejectsStaffOnAdminKdsOrderHistoryRoute() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kds/orders/all");
        request.addHeader("Authorization", "Bearer staff-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        User staff = new User();
        staff.setRole("STAFF");

        when(jwtUtil.validateToken("staff-token")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("staff-token")).thenReturn("staff@example.com");
        when(userRepository.findByEmailIgnoreCase("staff@example.com")).thenReturn(Optional.of(staff));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(404, response.getStatus());
    }

    @Test
    void rejectsStaffOnAdminReviewRoutes() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kds/reviews");
        request.addHeader("Authorization", "Bearer staff-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        User staff = new User();
        staff.setRole("STAFF");

        when(jwtUtil.validateToken("staff-token")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("staff-token")).thenReturn("staff@example.com");
        when(userRepository.findByEmailIgnoreCase("staff@example.com")).thenReturn(Optional.of(staff));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(404, response.getStatus());
    }

    @Test
    void allowsAdminOnAdminReviewRoutes() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kds/reviews");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        User admin = new User();
        admin.setRole("ADMIN");

        when(jwtUtil.validateToken("admin-token")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("admin-token")).thenReturn("admin@example.com");
        when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsStaffOnAdminMenuMutations() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/menu");
        request.addHeader("Authorization", "Bearer staff-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        User staff = new User();
        staff.setRole("STAFF");

        when(jwtUtil.validateToken("staff-token")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("staff-token")).thenReturn("staff@example.com");
        when(userRepository.findByEmailIgnoreCase("staff@example.com")).thenReturn(Optional.of(staff));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(404, response.getStatus());
    }
}
