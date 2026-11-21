package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PasswordResetQueuePayloadEncryptor Test")
class PasswordResetQueuePayloadEncryptorTest {

    private static final String SAMPLE_OTP = "482915";
    private static final String TASK_ID = "task-uuid-88319-abc";

    @Test
    @DisplayName("Ephemeral key encrypts and decrypts round trip successfully")
    void testEphemeralKeyEncryptDecryptRoundTrip() {
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor();
        assertTrue(encryptor.isEphemeralKey());

        String ciphertext = encryptor.encrypt(SAMPLE_OTP, TASK_ID);
        assertNotNull(ciphertext);
        assertNotEquals(SAMPLE_OTP, ciphertext);

        String decrypted = encryptor.decrypt(ciphertext, TASK_ID);
        assertEquals(SAMPLE_OTP, decrypted);
    }

    @Test
    @DisplayName("64-character hex key derives 256-bit AES key and round-trips")
    void testConfiguredHexKey256Bit() {
        String hexKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor(hexKey);
        assertFalse(encryptor.isEphemeralKey());

        String ciphertext = encryptor.encrypt(SAMPLE_OTP, TASK_ID);
        String decrypted = encryptor.decrypt(ciphertext, TASK_ID);
        assertEquals(SAMPLE_OTP, decrypted);
    }

    @Test
    @DisplayName("32-byte Base64 key round-trips")
    void testConfiguredBase64Key() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor(base64Key);
        assertFalse(encryptor.isEphemeralKey());

        String ciphertext = encryptor.encrypt("998877", TASK_ID);
        String decrypted = encryptor.decrypt(ciphertext, TASK_ID);
        assertEquals("998877", decrypted);
    }

    @Test
    @DisplayName("Passphrase string derives 256-bit key via SHA-256 and round-trips")
    void testConfiguredPassphraseKey() {
        String passphrase = "LordGainsborough-Super-Secret-Passphrase-2026!";
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor(passphrase);
        assertFalse(encryptor.isEphemeralKey());

        String ciphertext = encryptor.encrypt("112233", TASK_ID);
        String decrypted = encryptor.decrypt(ciphertext, TASK_ID);
        assertEquals("112233", decrypted);
    }

    @Test
    @DisplayName("PasswordResetProperties resolves configured key")
    void testPasswordResetPropertiesResolution() {
        PasswordResetProperties props = new PasswordResetProperties();
        props.setQueueEncryptionKey("test-property-key-value-12345");

        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor(props);
        assertFalse(encryptor.isEphemeralKey());

        String ciphertext = encryptor.encrypt("654321", TASK_ID);
        String decrypted = encryptor.decrypt(ciphertext, TASK_ID);
        assertEquals("654321", decrypted);
    }

    @Test
    @DisplayName("Different context AAD prevents cross-task substitution tampering")
    void testAssociatedDataTamperingDetection() {
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor();
        String ciphertext = encryptor.encrypt(SAMPLE_OTP, "task-legitimate-1");

        // Attempting to decrypt with a different task ID must fail authentication
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                encryptor.decrypt(ciphertext, "task-tampered-2")
        );
        assertTrue(ex.getMessage().contains("authentication failed") || ex.getMessage().contains("corrupted"));
    }

    @Test
    @DisplayName("Ciphertext byte tampering causes authentication tag failure")
    void testPayloadTamperingDetection() {
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor();
        String ciphertext = encryptor.encrypt(SAMPLE_OTP, TASK_ID);

        byte[] raw = Base64.getUrlDecoder().decode(ciphertext);
        // Tamper with last byte (part of GCM auth tag)
        raw[raw.length - 1] ^= 0x5A;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                encryptor.decrypt(tampered, TASK_ID)
        );
        assertTrue(ex.getMessage().contains("authentication failed") || ex.getMessage().contains("corrupted"));
    }

    @Test
    @DisplayName("Null or empty plaintext and ciphertext return null safely")
    void testNullAndBlankInputs() {
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor();
        assertNull(encryptor.encrypt(null, TASK_ID));
        assertNull(encryptor.decrypt(null, TASK_ID));
        assertNull(encryptor.decrypt("", TASK_ID));
        assertNull(encryptor.decrypt("   ", TASK_ID));
    }

    @Test
    @DisplayName("Truncated payload length fails cleanly")
    void testTruncatedPayloadFails() {
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor();
        byte[] tooShort = new byte[10]; // Minimum is 12 (IV) + 16 (Tag) = 28 bytes
        String shortCiphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(tooShort);

        assertThrows(IllegalStateException.class, () ->
                encryptor.decrypt(shortCiphertext, TASK_ID)
        );
    }

    @Test
    @DisplayName("Probabilistic encryption: same plaintext produces unique ciphertexts due to random IV")
    void testProbabilisticEncryption() {
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor();
        String ciphertext1 = encryptor.encrypt(SAMPLE_OTP, TASK_ID);
        String ciphertext2 = encryptor.encrypt(SAMPLE_OTP, TASK_ID);

        assertNotEquals(ciphertext1, ciphertext2);
        assertEquals(SAMPLE_OTP, encryptor.decrypt(ciphertext1, TASK_ID));
        assertEquals(SAMPLE_OTP, encryptor.decrypt(ciphertext2, TASK_ID));
    }
}
