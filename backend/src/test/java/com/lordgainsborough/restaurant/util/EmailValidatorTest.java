package com.lordgainsborough.restaurant.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailValidatorTest {

    @Test
    void rejectsMalformedEmailMissingDomainSuffix() {
        assertFalse(EmailValidator.isValidEmail("amberverma@gma"));
        assertFalse(EmailValidator.isValidEmail("test@localhost"));
        assertFalse(EmailValidator.isValidEmail("user@domain"));
    }

    @Test
    void rejectsInvalidLocalPartsAndPunctuation() {
        assertFalse(EmailValidator.isValidEmail(""));
        assertFalse(EmailValidator.isValidEmail("   "));
        assertFalse(EmailValidator.isValidEmail(null));
        assertFalse(EmailValidator.isValidEmail("@example.com"));
        assertFalse(EmailValidator.isValidEmail("user@"));
        assertFalse(EmailValidator.isValidEmail(".user@example.com"));
        assertFalse(EmailValidator.isValidEmail("user.@example.com"));
        assertFalse(EmailValidator.isValidEmail("user..name@example.com"));
        assertFalse(EmailValidator.isValidEmail("user name@example.com"));
        assertFalse(EmailValidator.isValidEmail("user@ example.com"));
    }

    @Test
    void rejectsInvalidDomainParts() {
        assertFalse(EmailValidator.isValidEmail("user@.example.com"));
        assertFalse(EmailValidator.isValidEmail("user@example..com"));
        assertFalse(EmailValidator.isValidEmail("user@example.com."));
        assertFalse(EmailValidator.isValidEmail("user@-example.com"));
        assertFalse(EmailValidator.isValidEmail("user@example-.com"));
        assertFalse(EmailValidator.isValidEmail("user@example.c"));
        assertFalse(EmailValidator.isValidEmail("user@example.123"));
    }

    @Test
    void acceptsValidAddresses() {
        assertTrue(EmailValidator.isValidEmail("amberverma@gmail.com"));
        assertTrue(EmailValidator.isValidEmail("amber+test@example.com"));
        assertTrue(EmailValidator.isValidEmail("user.name@sub.example.co.uk"));
        assertTrue(EmailValidator.isValidEmail("chef@lordgainsborough.restaurant"));
        assertTrue(EmailValidator.isValidEmail("guest_123-special@dept.hospitality.org"));
    }
}
