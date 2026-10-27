package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaStoreSettings;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaStoreSettingsRepository;
import jakarta.persistence.EntityManagerFactory;

public class PostgresKdsSettingsSource implements KdsSettingsSource {
    private final JpaStoreSettingsRepository settings;
    private final EntityManagerFactory emf;
    public PostgresKdsSettingsSource(JpaStoreSettingsRepository settings, EntityManagerFactory emf) { this.settings = settings; this.emf = emf; }
    public EntityManagerFactory getEntityManagerFactory() { return emf; }
    public String allowedIps() {
        return settings.findById(JpaStoreSettings.DEFAULT_ID).map(JpaStoreSettings::getKdsAllowedIp).orElse("");
    }

    public JpaStoreSettingsRepository getSettingsRepository() { return settings; }
}
