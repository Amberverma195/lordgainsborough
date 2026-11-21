package com.lordgainsborough.restaurant.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative registry of published legal policy versions for Lord Gainsborough Restaurant.
 * Terms of Service and Privacy Policy versions must be published here to be valid for customer consent.
 */
@Service
public class LegalPolicyService {

    public static final String CURRENT_TERMS_VERSION = "terms-v1.0";
    public static final String CURRENT_PRIVACY_VERSION = "privacy-v1.1";
    public static final String PREVIOUS_PRIVACY_VERSION = "privacy-v1.0";
    public static final LocalDate CURRENT_POLICY_EFFECTIVE_DATE = LocalDate.of(2026, 11, 20);

    // Canonical content SHA-256 hashes (normalized LF)
    public static final String TERMS_V1_0_HASH = "5738fec4fe38394d00b786446e084f3369838ff13a150dc4fb783fb712673c7b";
    public static final String PRIVACY_V1_0_HASH = "5334d8935908ca78d84f80436f363909f039f1b01a47a1f96bba835a657c6945";
    public static final String PRIVACY_V1_1_HASH = "fd2a4f17f70ce0604c1dc1099febf191850e423921c5b85815fc2037782e4f23";

    private static final Set<String> PUBLISHED_TERMS_VERSIONS = Set.of(CURRENT_TERMS_VERSION);
    private static final Set<String> PUBLISHED_PRIVACY_VERSIONS = Set.of(PREVIOUS_PRIVACY_VERSION, CURRENT_PRIVACY_VERSION);

    private static final Map<String, String> POLICY_HASHES = Map.of(
            CURRENT_TERMS_VERSION, TERMS_V1_0_HASH,
            PREVIOUS_PRIVACY_VERSION, PRIVACY_V1_0_HASH,
            CURRENT_PRIVACY_VERSION, PRIVACY_V1_1_HASH
    );

    public String getCurrentTermsVersion() {
        return CURRENT_TERMS_VERSION;
    }

    public String getCurrentPrivacyVersion() {
        return CURRENT_PRIVACY_VERSION;
    }

    public String getPreviousPrivacyVersion() {
        return PREVIOUS_PRIVACY_VERSION;
    }

    public LocalDate getCurrentPolicyEffectiveDate() {
        return CURRENT_POLICY_EFFECTIVE_DATE;
    }

    public Set<String> getPublishedTermsVersions() {
        return Collections.unmodifiableSet(PUBLISHED_TERMS_VERSIONS);
    }

    public Set<String> getPublishedPrivacyVersions() {
        return Collections.unmodifiableSet(PUBLISHED_PRIVACY_VERSIONS);
    }

    public boolean isPublishedTermsVersion(String version) {
        return version != null && PUBLISHED_TERMS_VERSIONS.contains(version.trim());
    }

    public boolean isPublishedPrivacyVersion(String version) {
        return version != null && PUBLISHED_PRIVACY_VERSIONS.contains(version.trim());
    }

    public boolean isCurrentTermsVersion(String version) {
        return CURRENT_TERMS_VERSION.equals(version != null ? version.trim() : null);
    }

    public boolean isCurrentPrivacyVersion(String version) {
        return CURRENT_PRIVACY_VERSION.equals(version != null ? version.trim() : null);
    }

    public String getExpectedContentHash(String version) {
        return version != null ? POLICY_HASHES.get(version.trim()) : null;
    }
}
