package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.StoreSettings;

import org.springframework.stereotype.Service;

@Service
public class StoreSettingsService {
    private final PostgresCatalogStore storeSettingsRepository;

    public StoreSettingsService(PostgresCatalogStore storeSettingsRepository) {
        this.storeSettingsRepository = storeSettingsRepository;
    }

    public StoreSettings getSettings() {
        return storeSettingsRepository.settings()
                .orElseGet(this::createDefault);
    }

    public StoreSettings saveSettings(StoreSettings incoming) {
        StoreSettings settings = storeSettingsRepository.settings()
                .orElseGet(this::createDefault);

        settings.setTaxRate(normalizeTaxRate(incoming.getTaxRate()));
        settings.setDefaultReadyMinutes(normalizeReadyMinutes(incoming.getDefaultReadyMinutes()));
        settings.setStoreName(sanitize(incoming.getStoreName(), settings.getStoreName()));
        settings.setContactPhone(sanitize(incoming.getContactPhone(), settings.getContactPhone()));
        settings.setContactEmail(sanitize(incoming.getContactEmail(), settings.getContactEmail()));
        settings.setPickupOrdersEnabled(incoming.getPickupOrdersEnabled());
        settings.setKdsAllowedIp(KdsAccessService.normalizeAllowedIpInput(incoming.getKdsAllowedIp()));

        return storeSettingsRepository.saveSettings(settings);
    }

    private StoreSettings createDefault() {
        StoreSettings settings = new StoreSettings();
        settings.setId(StoreSettings.DEFAULT_ID);
        return storeSettingsRepository.saveSettings(settings);
    }

    private double normalizeTaxRate(double taxRate) {
        if (taxRate < 0) {
            return 0;
        }
        if (taxRate > 1) {
            return 1;
        }
        return taxRate;
    }

    private int normalizeReadyMinutes(int minutes) {
        if (minutes < 5) {
            return 5;
        }
        if (minutes > 120) {
            return 120;
        }
        return minutes;
    }

    private String sanitize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value.trim();
    }
}
