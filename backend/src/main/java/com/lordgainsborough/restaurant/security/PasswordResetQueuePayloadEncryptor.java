package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Application-level authenticated encryption (AES-256-GCM) for queued password-reset OTP payloads.
 *
 * <p>Guarantees that plaintext OTP values are NEVER persisted to PostgreSQL, avoiding
 * plaintext exposure in database tables, replication streams, transaction logs (WAL), or backups.
 * The cryptographic key is stored separately from PostgreSQL in application configuration or memory.
 */
@Component
public class PasswordResetQueuePayloadEncryptor {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetQueuePayloadEncryptor.class);
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom;
    private final boolean ephemeralKey;

    @Autowired
    public PasswordResetQueuePayloadEncryptor(PasswordResetProperties properties) {
        this(resolveConfiguredKey(properties));
    }

    public PasswordResetQueuePayloadEncryptor() {
        this((String) null);
    }

    public PasswordResetQueuePayloadEncryptor(String configuredKey) {
        this.secureRandom = new SecureRandom();
        if (configuredKey != null && !configuredKey.trim().isBlank()) {
            this.secretKey = new SecretKeySpec(deriveKeyBytes(configuredKey.trim()), ALGORITHM);
            this.ephemeralKey = false;
        } else {
            byte[] generatedKey = new byte[32];
            this.secureRandom.nextBytes(generatedKey);
            this.secretKey = new SecretKeySpec(generatedKey, ALGORITHM);
            this.ephemeralKey = true;
            log.info("Initialized PasswordResetQueuePayloadEncryptor with secure in-memory ephemeral key (separate from PostgreSQL)");
        }
    }

    private static String resolveConfiguredKey(PasswordResetProperties properties) {
        if (properties != null && properties.getQueueEncryptionKey() != null
                && !properties.getQueueEncryptionKey().trim().isBlank()) {
            return properties.getQueueEncryptionKey().trim();
        }
        String envKey = System.getenv("PASSWORD_RESET_QUEUE_ENCRYPTION_KEY");
        if (envKey != null && !envKey.trim().isBlank()) {
            return envKey.trim();
        }
        return System.getProperty("app.auth.password-reset.queue-encryption-key");
    }

    private static byte[] deriveKeyBytes(String raw) {
        // 1. 64-character hex string (32 bytes)
        if (raw.length() == 64 && raw.matches("^[0-9a-fA-F]+$")) {
            return HexFormat.of().parseHex(raw);
        }
        // 2. Base64 32-byte key
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
        }
        // 3. Derive 32-byte key from arbitrary passphrase via SHA-256
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES-256 encryption key", e);
        }
    }

    public boolean isEphemeralKey() {
        return ephemeralKey;
    }

    /**
     * Encrypts plaintext OTP payload with AES-256-GCM and optional authenticated associated data (AAD).
     *
     * @param plaintext OTP code to encrypt (never null)
     * @param associatedData context binding identifier (e.g. task ID or challenge ID)
     * @return URL-safe Base64-encoded payload containing [12-byte IV][ciphertext + 16-byte auth tag]
     */
    public String encrypt(String plaintext, String associatedData) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            if (associatedData != null && !associatedData.isBlank()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt queued password-reset OTP payload", e);
        }
    }

    /**
     * Decrypts AES-256-GCM encrypted payload and verifies integrity via AAD.
     *
     * @param encryptedPayload URL-safe Base64 string produced by {@link #encrypt(String, String)}
     * @param associatedData expected context binding identifier
     * @return plaintext OTP code
     */
    public String decrypt(String encryptedPayload, String associatedData) {
        if (encryptedPayload == null || encryptedPayload.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getUrlDecoder().decode(encryptedPayload.trim());
            if (combined.length < IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
                throw new IllegalArgumentException("Encrypted payload has insufficient length for AES-GCM");
            }

            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            if (associatedData != null && !associatedData.isBlank()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt queued password-reset OTP payload: authentication failed or payload corrupted", e);
        }
    }
}
