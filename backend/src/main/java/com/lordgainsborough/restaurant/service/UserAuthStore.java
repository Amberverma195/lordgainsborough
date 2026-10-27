package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.security.JwtUtil;
import java.util.List;
import java.util.Optional;

/** Internal identity snapshots only: callers must return explicit response DTOs, never User. */
public interface UserAuthStore {
    record Registration(User user, SignupOtpChallengeService.VerifyOtpResult verification, boolean conflict) {}
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByPhone(String normalizedPhone);
    boolean existsByEmailIgnoreCase(String email);
    boolean phoneRegisteredByAnotherUser(String normalizedPhone, String currentUserId);
    Registration register(User draft, String rawPassword, String otp, SignupOtpChallengeService challenges);
    Optional<User> updateName(User user, String token, String name, JwtUtil jwt);
    Optional<List<CustomerOrder>> history(User user, String token, JwtUtil jwt);
    PasswordResetService.Result requestPasswordReset(PasswordResetService service, String email, String ip, String key);
    default Optional<User> confirmLogin(User checked) { return Optional.of(checked); }
    default Optional<User> authenticate(String token, JwtUtil jwt) {
        if (token == null || token.isBlank() || !jwt.validateToken(token)) return Optional.empty();
        return findByEmailIgnoreCase(jwt.getEmailFromToken(token))
                .filter(user -> jwt.validateTokenForUser(token, user));
    }
}
