package com.lordgainsborough.restaurant.model;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupOtpChallengeDocumentTest {

    @Test
    void documentUsesSignupOtpChallengesCollection() {
        Document document = SignupOtpChallenge.class.getAnnotation(Document.class);
        assertNotNull(document);
        assertEquals("signup_otp_challenges", document.collection());
    }

    @Test
    void challengeExpiresAtHasTtlIndexAtExpiryInstant() throws Exception {
        Field field = SignupOtpChallenge.class.getDeclaredField("challengeExpiresAt");
        Indexed indexed = field.getAnnotation(Indexed.class);
        assertNotNull(indexed);
        assertEquals("signup_otp_challenge_ttl", indexed.name());
        assertEquals(0, indexed.expireAfterSeconds());
    }

    @Test
    void emailKeyIsMongoId() throws Exception {
        Field field = SignupOtpChallenge.class.getDeclaredField("emailKey");
        assertTrue(field.isAnnotationPresent(org.springframework.data.annotation.Id.class));
    }

    @Test
    void toStringExcludesEmailAndOtpDigest() {
        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setNormalizedEmail("member@example.com");
        challenge.setOtpDigest("$2a$12$sensitive-digest");
        challenge.setEmailKey("a".repeat(64));
        challenge.setChallengeId("challenge-safe");

        String rendered = challenge.toString();

        assertFalse(rendered.contains("member@example.com"));
        assertFalse(rendered.contains("sensitive-digest"));
        assertTrue(rendered.contains("challenge-safe"));
    }
}
