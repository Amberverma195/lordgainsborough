package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import com.lordgainsborough.restaurant.service.LoginRateLimiter;
import com.lordgainsborough.restaurant.service.OrderRealtimeService;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AccountController {
    public record DeleteAccountRequest(String password) {}
    private final UserAuthStore users;
    private final JwtUtil jwt;
    private final LoginRateLimiter limiter;
    private final ClientIpResolver ips;
    private final OrderRealtimeService realtime;

    public AccountController(UserAuthStore users, JwtUtil jwt, LoginRateLimiter limiter,
            ClientIpResolver ips, OrderRealtimeService realtime) {
        this.users = users; this.jwt = jwt; this.limiter = limiter; this.ips = ips; this.realtime = realtime;
    }

    @DeleteMapping("/me")
    public ResponseEntity<?> deleteAccount(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DeleteAccountRequest request, HttpServletRequest http) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return invalidSession();
        String token = authorization.substring(7);
        var found = users.authenticate(token, jwt);
        if (found.isEmpty()) return invalidSession();
        if (request.password() == null || request.password().isBlank() || request.password().length() > 128)
            return ResponseEntity.badRequest().body(Map.of("message", "Enter your current password."));

        var user = found.get();
        String ip = ips.resolve(http.getRemoteAddr(), http.getHeader("X-Forwarded-For"), http.getHeader("X-Real-IP"));
        String identifier = "delete-account:" + user.getId();
        var ipLimit = limiter.consumeIpRequest(ip);
        var cooldown = limiter.activeCooldown(identifier, ip);
        if (ipLimit.isPresent() || cooldown.isPresent()) {
            long seconds = Math.max(ipLimit.orElse(0), cooldown.orElse(0));
            return ResponseEntity.status(429).header("Retry-After", Long.toString(seconds))
                    .body(Map.of("message", "Too many attempts. Try again in " + seconds + " seconds."));
        }
        if (!PasswordUtil.checkPassword(request.password(), user.getPassword())) {
            limiter.recordFailure(identifier, ip);
            return ResponseEntity.status(403).body(Map.of("message", "Your current password is incorrect."));
        }
        Optional<List<String>> deleted;
        try {
            deleted = users.deleteAccount(user, token, jwt);
        } catch (UserAuthStore.ActiveOrdersException activeOrders) {
            return ResponseEntity.status(409).body(Map.of(
                    "code", "ACCOUNT_HAS_ACTIVE_ORDERS", "message", activeOrders.getMessage()));
        }
        if (deleted.isEmpty()) return invalidSession();
        // deleteAccount returns after its transaction commits. Delivery must not undo a successful response.
        realtime.ordersDeleted(deleted.get());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> invalidSession() {
        return ResponseEntity.status(401).body(Map.of("message", "Your session has expired. Please sign in again."));
    }
}
