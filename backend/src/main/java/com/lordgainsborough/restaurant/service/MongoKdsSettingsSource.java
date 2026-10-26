package com.lordgainsborough.restaurant.service;

import org.springframework.stereotype.Component;

@Component("mongoKdsSettingsSource")
public class MongoKdsSettingsSource implements KdsSettingsSource {
    private final StoreSettingsService settings;
    public MongoKdsSettingsSource(StoreSettingsService settings) { this.settings = settings; }
    public String allowedIps() { return settings.getSettings().getKdsAllowedIp(); }
}
