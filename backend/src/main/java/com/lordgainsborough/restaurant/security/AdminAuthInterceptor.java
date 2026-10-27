package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.service.KdsAccessService;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import com.lordgainsborough.restaurant.service.MongoUserAuthStore;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.function.Predicate;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserAuthStore identityStore;
    private final KdsAccessService kdsAccessService;

    public AdminAuthInterceptor(JwtUtil jwtUtil, UserRepository userRepository, KdsAccessService kdsAccessService) {
        this.jwtUtil = jwtUtil;
        this.identityStore = new MongoUserAuthStore(userRepository, null, null);
        this.kdsAccessService = kdsAccessService;
    }

    @Autowired
    public AdminAuthInterceptor(JwtUtil jwtUtil, UserAuthStore identityStore, KdsAccessService kdsAccessService) {
        this.jwtUtil = jwtUtil; this.identityStore = identityStore; this.kdsAccessService = kdsAccessService;
    }

    public UserAuthStore getIdentityStore() { return identityStore; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("OPTIONS".equals(method)) {
            return true;
        }

        // Layer 2: operational KDS is IP-gated only (no login).
        if (isOperationalKdsPath(path)) {
            if (!kdsAccessService.isRequestAllowed(request)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return false;
            }
            return true;
        }

        Optional<Predicate<String>> roleCheck = requiredRoleCheck(method, path);
        if (roleCheck.isEmpty()) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        Predicate<String> allowedRoles = roleCheck.get();
        return identityStore.authenticate(token, jwtUtil)
                .filter(user -> allowedRoles.test(user.getRole()))
                .map(user -> true)
                .orElseGet(() -> {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return false;
                });
    }

    /**
     * Kitchen board endpoints: access check + order board mutations.
     * Admin history/reviews stay JWT-protected.
     */
    static boolean isOperationalKdsPath(String path) {
        if (path == null || !path.startsWith("/api/kds")) {
            return false;
        }
        if (path.startsWith("/api/kds/reviews")) {
            return false;
        }
        if ("/api/kds/orders/all".equals(path)) {
            return false;
        }
        return true;
    }

    private Optional<Predicate<String>> requiredRoleCheck(String method, String path) {
        if (path.startsWith("/api/kds/reviews") || "/api/kds/orders/all".equals(path)) {
            return Optional.of(RoleAccess::isAdmin);
        }

        if (path.startsWith("/api/admin")) {
            return Optional.of(RoleAccess::isAdmin);
        }

        if (path.startsWith("/api/store-settings")) {
            if ("GET".equals(method)) {
                return Optional.empty();
            }
            return Optional.of(RoleAccess::isAdmin);
        }

        if (path.startsWith("/api/menu")) {
            if ("GET".equals(method)) {
                return Optional.empty();
            }
            return Optional.of(RoleAccess::isAdmin);
        }

        if (path.startsWith("/api/categories")) {
            if ("GET".equals(method)) {
                return Optional.empty();
            }
            return Optional.of(RoleAccess::isAdmin);
        }

        if (path.startsWith("/api/reservations")) {
            if ("POST".equals(method) && "/api/reservations".equals(path)) {
                return Optional.empty();
            }
            return Optional.of(RoleAccess::isAdmin);
        }

        return Optional.empty();
    }
}
