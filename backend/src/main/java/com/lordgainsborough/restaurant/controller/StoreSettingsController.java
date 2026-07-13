package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.service.StoreSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/store-settings")
@CrossOrigin(origins = "*")
public class StoreSettingsController {
    private final StoreSettingsService storeSettingsService;

    public StoreSettingsController(StoreSettingsService storeSettingsService) {
        this.storeSettingsService = storeSettingsService;
    }

    @GetMapping
    public StoreSettings getSettings() {
        return storeSettingsService.getSettings();
    }

    @PutMapping
    public ResponseEntity<StoreSettings> updateSettings(@RequestBody Map<String, Object> body) {
        StoreSettings incoming = new StoreSettings();
        incoming.setTaxRate(toDouble(body.get("taxRate"), storeSettingsService.getSettings().getTaxRate()));
        incoming.setDefaultReadyMinutes(toInt(body.get("defaultReadyMinutes"), storeSettingsService.getSettings().getDefaultReadyMinutes()));
        incoming.setStoreName(toString(body.get("storeName"), storeSettingsService.getSettings().getStoreName()));
        incoming.setContactPhone(toString(body.get("contactPhone"), storeSettingsService.getSettings().getContactPhone()));
        incoming.setContactEmail(toString(body.get("contactEmail"), storeSettingsService.getSettings().getContactEmail()));
        incoming.setPickupOrdersEnabled(toBoolean(body.get("pickupOrdersEnabled"), storeSettingsService.getSettings().getPickupOrdersEnabled()));
        return ResponseEntity.ok(storeSettingsService.saveSettings(incoming));
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
