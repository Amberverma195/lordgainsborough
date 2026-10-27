package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.RoleAccess;
import com.lordgainsborough.restaurant.service.StoreSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/store-settings")
public class StoreSettingsController {
    private final StoreSettingsService storeSettingsService;
    private final JwtUtil jwtUtil;
    private final UserAuthStore identities;

    public StoreSettingsController(
            StoreSettingsService storeSettingsService,
            JwtUtil jwtUtil,
            UserAuthStore identities) {
        this.storeSettingsService = storeSettingsService;
        this.jwtUtil = jwtUtil;
        this.identities = identities;
    }

    @GetMapping
    public Object getSettings(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        StoreSettings settings = storeSettingsService.getSettings();
        if (isAdmin(authHeader)) {
            return settings;
        }
        return toPublicView(settings);
    }

    @PutMapping
    public ResponseEntity<StoreSettings> updateSettings(@RequestBody Map<String, Object> body) {
        StoreSettings current = storeSettingsService.getSettings();
        StoreSettings incoming = new StoreSettings();
        incoming.setTaxRate(toDouble(body.get("taxRate"), current.getTaxRate()));
        incoming.setDefaultReadyMinutes(toInt(body.get("defaultReadyMinutes"), current.getDefaultReadyMinutes()));
        incoming.setStoreName(toString(body.get("storeName"), current.getStoreName()));
        incoming.setContactPhone(toString(body.get("contactPhone"), current.getContactPhone()));
        incoming.setContactEmail(toString(body.get("contactEmail"), current.getContactEmail()));
        incoming.setPickupOrdersEnabled(toBoolean(body.get("pickupOrdersEnabled"), current.getPickupOrdersEnabled()));
        incoming.setKdsAllowedIp(body.containsKey("kdsAllowedIp")
                ? toString(body.get("kdsAllowedIp"), "")
                : current.getKdsAllowedIp());
        return ResponseEntity.ok(storeSettingsService.saveSettings(incoming));
    }

    private Map<String, Object> toPublicView(StoreSettings settings) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", settings.getId());
        view.put("taxRate", settings.getTaxRate());
        view.put("defaultReadyMinutes", settings.getDefaultReadyMinutes());
        view.put("storeName", settings.getStoreName());
        view.put("contactPhone", settings.getContactPhone());
        view.put("contactEmail", settings.getContactEmail());
        view.put("pickupOrdersEnabled", settings.getPickupOrdersEnabled());
        // Intentionally omit kdsAllowedIp from public responses.
        return view;
    }

    private boolean isAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7);
        return identities.authenticate(token, jwtUtil)
                .filter(value -> RoleAccess.isAdmin(value.getRole())).isPresent();
    }

    private double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private String toString(Object value, String fallback) {
        if (value instanceof String text) {
            return text;
        }
        return fallback;
    }

    private boolean toBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean enabled) {
            return enabled;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }
}
