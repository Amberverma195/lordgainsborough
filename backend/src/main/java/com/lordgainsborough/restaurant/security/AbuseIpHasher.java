package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Keyed hash of client IPs for abuse-limit document keys (never persist raw IPs).
 */
@Component
public class AbuseIpHasher {

    private final byte[] secret;

    public AbuseIpHasher(SignupAbuseProperties properties) {
        String configured = properties.getIpHashSecret();
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException(
                    "ABUSE_IP_HASH_SECRET must be configured; a predictable fallback is not permitted");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    public String hashIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return hashRaw("unknown");
        }
        return hashRaw(ip.trim().toLowerCase());
    }

    public String hashRaw(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(secret);
                return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception nested) {
                throw new IllegalStateException("Unable to hash abuse subject", nested);
            }
        }
    }
}
