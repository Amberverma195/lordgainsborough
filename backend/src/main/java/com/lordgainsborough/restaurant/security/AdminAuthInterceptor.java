package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.function.Predicate;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public AdminAuthInterceptor(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Optional<Predicate<String>> roleCheck = requiredRoleCheck(request);
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

        String email = jwtUtil.getEmailFromToken(token);
        Predicate<String> allowedRoles = roleCheck.get();
        return userRepository.findByEmailIgnoreCase(email)
                .filter(user -> allowedRoles.test(user.getRole()))
                .map(user -> true)
                .orElseGet(() -> {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return false;
                });
    }

    private Optional<Predicate<String>> requiredRoleCheck(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("OPTIONS".equals(method)) {
            return Optional.empty();
        }

        if (path.startsWith("/api/kds/reviews") || path.equals("/api/kds/orders/all")) {
            return Optional.of(RoleAccess::isAdmin);
        }

        if (path.startsWith("/api/kds")) {
            return Optional.of(RoleAccess::canAccessKds);
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
