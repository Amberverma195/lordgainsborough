package com.lordgainsborough.restaurant.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordValidatorTest {

    @Test
    void acceptsValidPasswordsMeetingAllCriteria() {
        assertTrue(PasswordValidator.isValid("Valid123!"));
        assertTrue(PasswordValidator.isValid("Secret@Word99"));
        assertTrue(PasswordValidator.isValid("P@ssw0rd2026"));
        assertTrue(PasswordValidator.isValid("A".repeat(20) + "a".repeat(20) + "1".repeat(20) + "!".repeat(20)));
    }

    @Test
    void rejectsNullOrEmptyPassword() {
        assertFalse(PasswordValidator.isValid(null));
        assertFalse(PasswordValidator.isValid(""));
        assertFalse(PasswordValidator.isValid("   "));
        assertEquals("Password is required.", PasswordValidator.getValidationErrorMessage(null));
        assertEquals("Password is required.", PasswordValidator.getValidationErrorMessage(""));
    }

    @Test
    void rejectsPasswordShorterThan8Characters() {
        assertFalse(PasswordValidator.isValid("Ab1!"));
        assertFalse(PasswordValidator.isValid("A1!a2@b"));
        assertEquals("Password must be at least 8 characters long.", PasswordValidator.getValidationErrorMessage("Ab1!"));
    }

    @Test
    void rejectsPasswordLongerThan128Characters() {
        String longPass = "Aa1!" + "x".repeat(125);
        assertFalse(PasswordValidator.isValid(longPass));
        assertEquals("Password must not exceed 128 characters.", PasswordValidator.getValidationErrorMessage(longPass));
    }

    @Test
    void rejectsPasswordMissingLowercase() {
        assertFalse(PasswordValidator.isValid("ALLUPPER123!"));
        assertEquals("Password must contain at least one lowercase letter.", PasswordValidator.getValidationErrorMessage("ALLUPPER123!"));
    }

    @Test
    void rejectsPasswordMissingUppercase() {
        assertFalse(PasswordValidator.isValid("alllower123!"));
        assertEquals("Password must contain at least one uppercase letter.", PasswordValidator.getValidationErrorMessage("alllower123!"));
    }

    @Test
    void rejectsPasswordMissingNumber() {
        assertFalse(PasswordValidator.isValid("NoDigitsHere!"));
        assertEquals("Password must contain at least one number.", PasswordValidator.getValidationErrorMessage("NoDigitsHere!"));
    }

    @Test
    void rejectsPasswordMissingSpecialCharacter() {
        assertFalse(PasswordValidator.isValid("NoSpecialChar123"));
        assertEquals("Password must contain at least one special character (!@#$…).", PasswordValidator.getValidationErrorMessage("NoSpecialChar123"));
    }

    @Test
    void sanitizesControlAndZeroWidthCharacters() {
        // Zero-width space embedded
        String withZeroWidth = "Valid\u200B123!";
        assertTrue(PasswordValidator.isValid(withZeroWidth));
        assertEquals("Valid123!", PasswordValidator.sanitize(withZeroWidth));
    }
}
