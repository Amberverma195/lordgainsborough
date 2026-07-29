package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;

/**
 * Resolves the client IP without trusting arbitrary forwarding headers.
 * {@code X-Forwarded-For} / {@code X-Real-IP} are used only when the immediate
 * peer ({@code remoteAddr}) is in the configured trusted-proxy list.
 */
@Component
public class ClientIpResolver {

    private final SignupAbuseProperties properties;

    public ClientIpResolver(SignupAbuseProperties properties) {
        this.properties = properties;
    }

    public String resolve(String remoteAddr, String xForwardedFor, String xRealIp) {
        String peer = normalizeIp(remoteAddr);
        if (peer == null || peer.isBlank()) {
            return "unknown";
        }

        if (!isTrustedProxy(peer)) {
            return peer;
        }

        String fromXff = firstUntrustedForwardedClient(xForwardedFor);
        if (fromXff != null) {
            return fromXff;
        }

        String realIp = normalizeIp(xRealIp);
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        return peer;
    }

    public boolean isTrustedProxy(String ip) {
        String normalized = normalizeIp(ip);
        if (normalized == null) {
            return false;
        }
        List<String> trusted = properties.getTrustedProxies();
        if (trusted == null || trusted.isEmpty()) {
            return false;
        }
        for (String entry : trusted) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.contains("/")) {
                if (matchesCidr(normalized, trimmed)) {
                    return true;
                }
            } else if (normalized.equalsIgnoreCase(normalizeIp(trimmed))) {
                return true;
            }
        }
        return false;
    }

    private String firstUntrustedForwardedClient(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        // Walk from the trusted edge toward the client. This prevents an attacker-supplied
        // leftmost value from winning when a trusted proxy appends to X-Forwarded-For.
        String[] hops = xForwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = normalizeIp(hops[i]);
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        String value = ip.trim();
        if (value.isEmpty()) {
            return null;
        }
        // Strip IPv6 zone id / brackets.
        if (value.startsWith("[") && value.contains("]")) {
            value = value.substring(1, value.indexOf(']'));
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        // Map IPv4-mapped IPv6.
        if (value.startsWith("::ffff:")) {
            value = value.substring("::ffff:".length());
        }
        return value;
    }

    private static boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }
            InetAddress target = InetAddress.getByName(ip);
            InetAddress network = InetAddress.getByName(parts[0].trim());
            int prefix = Integer.parseInt(parts[1].trim());
            byte[] targetBytes = target.getAddress();
            byte[] networkBytes = network.getAddress();
            if (targetBytes.length != networkBytes.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (targetBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remBits == 0) {
                return true;
            }
            int mask = (~0) << (8 - remBits);
            return (targetBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        } catch (Exception ex) {
            return false;
        }
    }
}
