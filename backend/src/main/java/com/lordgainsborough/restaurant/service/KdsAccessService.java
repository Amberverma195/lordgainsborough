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
            if (entry.contains("/")) {
                if (matchesCidr(normalizedClient, entry)) {
                    return true;
                }
            } else if (normalizedClient.equalsIgnoreCase(ClientIpResolver.normalizeIp(entry))) {
                return true;
            }
        }
        return false;
    }

    public static List<String> parseAllowedIps(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("[,\\n;]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
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
