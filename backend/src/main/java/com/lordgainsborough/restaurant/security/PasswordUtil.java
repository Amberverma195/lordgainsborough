package com.lordgainsborough.restaurant.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordUtil {
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private static final String DUMMY_PASSWORD_HASH =
            encoder.encode("dummy-password-that-is-never-an-account-password");

    public static String hashPassword(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public static boolean checkPassword(String rawPassword, String hashedPassword) {
        return encoder.matches(rawPassword, hashedPassword);
    }

    /**
     * Performs the same BCrypt work used for real accounts without exposing account existence.
     */
    public static void dummyPasswordCheck(String rawPassword) {
        encoder.matches(rawPassword == null ? "" : rawPassword, DUMMY_PASSWORD_HASH);
    }
}
