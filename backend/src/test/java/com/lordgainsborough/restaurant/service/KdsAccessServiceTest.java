package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.model.StoreSettings;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KdsAccessServiceTest {

    private final StoreSettingsService storeSettingsService = mock(StoreSettingsService.class);
    private final SignupAbuseProperties abuseProperties = new SignupAbuseProperties();
    private final ClientIpResolver clientIpResolver = new ClientIpResolver(abuseProperties);
    private final KdsAccessService service = new KdsAccessService(storeSettingsService, clientIpResolver);

    @BeforeEach
    void setTrustedProxies() {
        abuseProperties.setTrustedProxies(List.of("127.0.0.1", "::1"));
    }

    @Test
    void deniesWhenAllowlistEmpty() {
        StoreSettings settings = new StoreSettings();
        settings.setKdsAllowedIp("");
        when(storeSettingsService.getSettings()).thenReturn(settings);

        assertFalse(service.isIpAllowed("203.0.113.10"));
    }

    @Test
    void allowsExactConfiguredIp() {
        StoreSettings settings = new StoreSettings();
        settings.setKdsAllowedIp("203.0.113.10\n127.0.0.1");
        when(storeSettingsService.getSettings()).thenReturn(settings);

        assertTrue(service.isIpAllowed("203.0.113.10"));
        assertTrue(service.isIpAllowed("127.0.0.1"));
        assertFalse(service.isIpAllowed("198.51.100.1"));
    }

    @Test
    void allowsCidrRange() {
        StoreSettings settings = new StoreSettings();
        settings.setKdsAllowedIp("10.0.0.0/8");
        when(storeSettingsService.getSettings()).thenReturn(settings);

        assertTrue(service.isIpAllowed("10.1.2.3"));
        assertFalse(service.isIpAllowed("11.0.0.1"));
    }
}
