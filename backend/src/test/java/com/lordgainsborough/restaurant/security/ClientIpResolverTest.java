package com.lordgainsborough.restaurant.security;

import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {

    private SignupAbuseProperties properties;
    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new SignupAbuseProperties();
        properties.setTrustedProxies(List.of("127.0.0.1", "::1", "10.0.0.0/8"));
        resolver = new ClientIpResolver(properties);
    }

    @Test
    void ignoresSpoofedForwardingHeadersFromUntrustedPeer() {
        String resolved = resolver.resolve(
                "203.0.113.50",
                "198.51.100.1, 10.0.0.1",
                "198.51.100.1");

        assertEquals("203.0.113.50", resolved);
    }

    @Test
    void trustsForwardedForWhenPeerIsTrustedProxy() {
        String resolved = resolver.resolve(
                "10.0.0.5",
                "198.51.100.20, 10.0.0.5",
                null);

        assertEquals("198.51.100.20", resolved);
    }

    @Test
    void trustsXRealIpWhenPeerIsLoopbackAndNoXff() {
        String resolved = resolver.resolve("127.0.0.1", null, "203.0.113.9");
        assertEquals("203.0.113.9", resolved);
    }

    @Test
    void fallsBackToPeerWhenTrustedButHeadersMissing() {
        assertEquals("127.0.0.1", resolver.resolve("127.0.0.1", null, null));
    }

    @Test
    void cidrMatchingWorksForPrivateProxyRange() {
        assertTrue(resolver.isTrustedProxy("10.1.2.3"));
        assertFalse(resolver.isTrustedProxy("203.0.113.1"));
    }
}
