package com.lordgainsborough.restaurant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Legal Policy Immutability & Content Hash Registry Verification")
class LegalPolicyIntegrityTest {

    private final LegalPolicyService service = new LegalPolicyService();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    @Test
    @DisplayName("loads packaged policy-registry.json from classpath with valid schema")
    void loadsPackagedRegistry() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/legal/policy-registry.json")) {
            assertThat(in).isNotNull();
            JsonNode root = mapper.readTree(in);
            assertThat(root.has("policies")).isTrue();
            assertThat(root.get("policies").isArray()).isTrue();
            assertThat(root.get("policies").size()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    @DisplayName("validates that all policies have canonical 64-char SHA-256 hashes")
    void validatesContentHashFormat() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/legal/policy-registry.json")) {
            JsonNode root = mapper.readTree(in);
            for (JsonNode policy : root.get("policies")) {
                String id = policy.get("id").asText();
                String hash = policy.get("contentSha256").asText();
                assertThat(hash)
                        .withFailMessage("Policy %s must have a 64-character hex SHA-256 hash", id)
                        .matches(SHA256_PATTERN);
            }
        }
    }

    @Test
    @DisplayName("verifies LegalPolicyService constants strictly match policy-registry.json")
    void verifiesServiceConstantsMatchRegistry() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/legal/policy-registry.json")) {
            JsonNode root = mapper.readTree(in);

            String termsCurrent = null;
            String termsCurrentHash = null;
            String privacyCurrent = null;
            String privacyCurrentHash = null;
            String privacySuperseded = null;
            String privacySupersededHash = null;

            for (JsonNode policy : root.get("policies")) {
                String type = policy.get("type").asText();
                String status = policy.get("status").asText();
                String version = policy.get("version").asText();
                String hash = policy.get("contentSha256").asText();

                if ("TERMS".equals(type) && "CURRENT".equals(status)) {
                    termsCurrent = version;
                    termsCurrentHash = hash;
                } else if ("PRIVACY".equals(type) && "CURRENT".equals(status)) {
                    privacyCurrent = version;
                    privacyCurrentHash = hash;
                } else if ("PRIVACY".equals(type) && "SUPERSEDED".equals(status)) {
                    privacySuperseded = version;
                    privacySupersededHash = hash;
                }
            }

            assertThat(service.getCurrentTermsVersion()).isEqualTo(termsCurrent);
            assertThat(service.getCurrentPrivacyVersion()).isEqualTo(privacyCurrent);
            assertThat(service.getPreviousPrivacyVersion()).isEqualTo(privacySuperseded);

            assertThat(LegalPolicyService.TERMS_V1_0_HASH).isEqualTo(termsCurrentHash);
            assertThat(LegalPolicyService.PRIVACY_V1_1_HASH).isEqualTo(privacyCurrentHash);
            assertThat(LegalPolicyService.PRIVACY_V1_0_HASH).isEqualTo(privacySupersededHash);

            assertThat(service.getExpectedContentHash(termsCurrent)).isEqualTo(termsCurrentHash);
            assertThat(service.getExpectedContentHash(privacyCurrent)).isEqualTo(privacyCurrentHash);
            assertThat(service.getExpectedContentHash(privacySuperseded)).isEqualTo(privacySupersededHash);
        }
    }

    @Test
    @DisplayName("ensures historical privacy-v1.0 remains published for audit lookups but rejected for new registrations")
    void ensuresHistoricalAuditability() {
        // v1.0 is published for auditability
        assertThat(service.isPublishedPrivacyVersion("privacy-v1.0")).isTrue();
        // v1.0 is NOT current and cannot be accepted for new signups
        assertThat(service.isCurrentPrivacyVersion("privacy-v1.0")).isFalse();

        // v1.1 is published and current
        assertThat(service.isPublishedPrivacyVersion("privacy-v1.1")).isTrue();
        assertThat(service.isCurrentPrivacyVersion("privacy-v1.1")).isTrue();
    }
}
