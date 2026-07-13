package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Three-layer KDS gate: page access-check, REST interceptor, and Socket.IO all share this IP allowlist.
 */
@Service
public class KdsAccessService {

    private final StoreSettingsService storeSettingsService;
    private final ClientIpResolver clientIpResolver;

    public KdsAccessService(StoreSettingsService storeSettingsService, ClientIpResolver clientIpResolver) {
        this.storeSettingsService = storeSettingsService;
        this.clientIpResolver = clientIpResolver;
    }

    public boolean isRequestAllowed(HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"));
        return isIpAllowed(clientIp);
    }

    public boolean isIpAllowed(String clientIp) {
        String normalizedClient = ClientIpResolver.normalizeIp(clientIp);
        if (normalizedClient == null || normalizedClient.isBlank() || "unknown".equalsIgnoreCase(normalizedClient)) {
            return false;
        }

        List<String> allowed = parseAllowedIps(storeSettingsService.getSettings().getKdsAllowedIp());
        if (allowed.isEmpty()) {
            return false;
        }

        for (String entry : allowed) {
            String normalizedEntry = ClientIpResolver.normalizeIp(entry);
            if (entry.contains("/")) {
                if (matchesCidr(normalizedClient, entry)) {
                    return true;
                }
            } else if (normalizedClient.equalsIgnoreCase(normalizedEntry)) {
                return true;
            } else if (isLoopback(normalizedClient) && isLoopback(normalizedEntry)) {
                // Local browser may report 127.0.0.1 while allowlist has ::1 (or the reverse).
                return true;
            }
        }
        return false;
    }

    public String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"));
    }

    private static boolean isLoopback(String ip) {
        String normalized = ClientIpResolver.normalizeIp(ip);
        if (normalized == null) {
            return false;
        }
        return "127.0.0.1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || "::1".equals(normalized);
    }

    public static final int MAX_ALLOWED_IPS = 3;

    public static List<String> parseAllowedIps(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("[,\\n;]+")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean duplicate = result.stream().anyMatch(existing -> existing.equalsIgnoreCase(trimmed));
            if (!duplicate) {
                result.add(trimmed);
            }
            if (result.size() >= MAX_ALLOWED_IPS) {
                break;
            }
        }
        return result;
    }

    public static String normalizeAllowedIpInput(String raw) {
        List<String> parsed = parseAllowedIps(raw);
        if (parsed.isEmpty()) {
            return "";
        }
        return String.join("\n", parsed);
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
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (targetBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (~0) << (8 - remainingBits);
            return (targetBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        } catch (Exception ignored) {
            return false;
        }
    }
}
