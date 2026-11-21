package com.lordgainsborough.restaurant.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;

/**
 * Authoritative registry of published legal policy versions for Lord Gainsborough Restaurant.
 * Terms of Service and Privacy Policy versions must be published here to be valid for customer consent.
 */
@Service
public class LegalPolicyService {

    public static final String CURRENT_TERMS_VERSION = "terms-v1.0";
    public static final String CURRENT_PRIVACY_VERSION = "privacy-v1.0";
    public static final LocalDate CURRENT_POLICY_EFFECTIVE_DATE = LocalDate.of(2026, 11, 20);

    private static final Set<String> PUBLISHED_TERMS_VERSIONS = Set.of(CURRENT_TERMS_VERSION);
    private static final Set<String> PUBLISHED_PRIVACY_VERSIONS = Set.of(CURRENT_PRIVACY_VERSION);

    public String getCurrentTermsVersion() {
        return CURRENT_TERMS_VERSION;
    }

    public String getCurrentPrivacyVersion() {
        return CURRENT_PRIVACY_VERSION;
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
}
