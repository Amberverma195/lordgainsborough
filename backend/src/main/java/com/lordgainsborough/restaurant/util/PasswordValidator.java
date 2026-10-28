package com.lordgainsborough.restaurant.util;

import java.util.regex.Pattern;

/**
 * Strict password complexity validator shared across Lord Gainsborough signup and auth flows.
 * Requires:
 * - Minimum 8 characters
 * - Maximum 128 characters
 * - At least one lowercase letter (a-z)
 * - At least one uppercase letter (A-Z)
 * - At least one digit (0-9)
 * - At least one special character (!@#$%^&*()_+-=[]{};':"\\|,.<>/?`~)
 */
public final class PasswordValidator {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
    private static final Pattern DIGIT_PATTERN = Pattern.compile(".*\\d.*");
    private static final Pattern SPECIAL_CHAR_PATTERN = Pattern.compile(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~].*");

    private PasswordValidator() {}

    public static boolean isValid(String password) {
        if (password == null) {
            return false;
        }
        String sanitized = sanitize(password);
        if (sanitized.length() < MIN_LENGTH || sanitized.length() > MAX_LENGTH) {
            return false;
        }
        return LOWERCASE_PATTERN.matcher(sanitized).matches()
                && UPPERCASE_PATTERN.matcher(sanitized).matches()
                && DIGIT_PATTERN.matcher(sanitized).matches()
                && SPECIAL_CHAR_PATTERN.matcher(sanitized).matches();
    }

    public static String getValidationErrorMessage(String password) {
        if (password == null || password.isBlank()) {
            return "Password is required.";
        }
        String sanitized = sanitize(password);
        if (sanitized.length() < MIN_LENGTH) {
            return "Password must be at least 8 characters long.";
        }
        if (sanitized.length() > MAX_LENGTH) {
            return "Password must not exceed 128 characters.";
        }
        if (!LOWERCASE_PATTERN.matcher(sanitized).matches()) {
            return "Password must contain at least one lowercase letter.";
        }
        if (!UPPERCASE_PATTERN.matcher(sanitized).matches()) {
            return "Password must contain at least one uppercase letter.";
        }
        if (!DIGIT_PATTERN.matcher(sanitized).matches()) {
            return "Password must contain at least one number.";
        }
        if (!SPECIAL_CHAR_PATTERN.matcher(sanitized).matches()) {
            return "Password must contain at least one special character (!@#$…).";
        }
        return "Password must meet all complexity requirements.";
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\u0000-\\u001F\\u007F-\\u009F]", "")
                .replaceAll("[\\u200B-\\u200D\\uFEFF\\u202A-\\u202E]", "");
    }
}
