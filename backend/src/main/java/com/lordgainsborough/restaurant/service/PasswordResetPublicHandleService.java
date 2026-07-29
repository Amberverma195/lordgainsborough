package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Component
public class PasswordResetPublicHandleService {

    public record Handle(
            String internalChallengeId,
            String emailKey,
            Instant nextResendAt,
            Instant challengeExpiresAt,
            int resendCount) {}

    private final AbuseIpHasher hasher;

    public PasswordResetPublicHandleService(AbuseIpHasher hasher) {
        this.hasher = hasher;
    }

    public String issue(Handle handle) {
        String payload = String.join(":",
                handle.internalChallengeId(),
                handle.emailKey(),
                String.valueOf(handle.nextResendAt().getEpochSecond()),
                String.valueOf(handle.challengeExpiresAt().getEpochSecond()),
                String.valueOf(handle.resendCount()));
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + signature(encoded);
    }

    public Optional<Handle> parse(String token) {
        if (token == null || token.isBlank() || token.length() > 1024) {
            return Optional.empty();
        }
        String[] tokenParts = token.split("\\.", -1);
        if (tokenParts.length != 2 || !constantTimeEquals(signature(tokenParts[0]), tokenParts[1])) {
            return Optional.empty();
        }
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(tokenParts[0]),
                    StandardCharsets.UTF_8);
            String[] parts = payload.split(":", -1);
            if (parts.length != 5 || parts[0].isBlank() || parts[1].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Handle(
                    parts[0],
                    parts[1],
                    Instant.ofEpochSecond(Long.parseLong(parts[2])),
                    Instant.ofEpochSecond(Long.parseLong(parts[3])),
                    Integer.parseInt(parts[4])));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private String signature(String encodedPayload) {
        return hasher.hashRaw("password-reset-public-handle-v1|" + encodedPayload);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }
}
