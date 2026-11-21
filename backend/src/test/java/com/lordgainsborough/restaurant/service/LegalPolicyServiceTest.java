package com.lordgainsborough.restaurant.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LegalPolicyService Registry & Verification Tests")
class LegalPolicyServiceTest {

    private final LegalPolicyService service = new LegalPolicyService();

    @Test
    @DisplayName("publishes current terms-v1.0 and privacy-v1.0 with effective date")
    void publishesCurrentVersions() {
        assertThat(service.getCurrentTermsVersion()).isEqualTo("terms-v1.0");
        assertThat(service.getCurrentPrivacyVersion()).isEqualTo("privacy-v1.0");
        assertThat(service.getCurrentPolicyEffectiveDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(service.getPublishedTermsVersions()).containsExactly("terms-v1.0");
        assertThat(service.getPublishedPrivacyVersions()).containsExactly("privacy-v1.0");
    }

    @Test
    @DisplayName("validates published terms versions with whitespace trimming and null safety")
    void validatesPublishedTerms() {
        assertThat(service.isPublishedTermsVersion("terms-v1.0")).isTrue();
        assertThat(service.isPublishedTermsVersion(" terms-v1.0 ")).isTrue();
        assertThat(service.isPublishedTermsVersion("terms-v0.9")).isFalse();
        assertThat(service.isPublishedTermsVersion("terms-v2.0")).isFalse();
        assertThat(service.isPublishedTermsVersion(null)).isFalse();
        assertThat(service.isPublishedTermsVersion("")).isFalse();
    }

    @Test
    @DisplayName("validates published privacy versions with whitespace trimming and null safety")
    void validatesPublishedPrivacy() {
        assertThat(service.isPublishedPrivacyVersion("privacy-v1.0")).isTrue();
        assertThat(service.isPublishedPrivacyVersion(" privacy-v1.0 ")).isTrue();
        assertThat(service.isPublishedPrivacyVersion("privacy-v0.9")).isFalse();
        assertThat(service.isPublishedPrivacyVersion("privacy-v2.0")).isFalse();
        assertThat(service.isPublishedPrivacyVersion(null)).isFalse();
        assertThat(service.isPublishedPrivacyVersion("")).isFalse();
    }

    @Test
    @DisplayName("strictly verifies current version matching to prevent silent reuse of legacy policies")
    void strictlyVerifiesCurrentVersions() {
        assertThat(service.isCurrentTermsVersion("terms-v1.0")).isTrue();
        assertThat(service.isCurrentTermsVersion(" terms-v1.0 ")).isTrue();
        assertThat(service.isCurrentTermsVersion("terms-v1.1")).isFalse();
        assertThat(service.isCurrentTermsVersion(null)).isFalse();

        assertThat(service.isCurrentPrivacyVersion("privacy-v1.0")).isTrue();
        assertThat(service.isCurrentPrivacyVersion(" privacy-v1.0 ")).isTrue();
        assertThat(service.isCurrentPrivacyVersion("privacy-v1.1")).isFalse();
        assertThat(service.isCurrentPrivacyVersion(null)).isFalse();
    }
}
