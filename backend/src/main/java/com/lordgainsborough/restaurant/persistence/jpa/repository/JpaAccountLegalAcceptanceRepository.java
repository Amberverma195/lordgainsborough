package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaAccountLegalAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaAccountLegalAcceptanceRepository extends JpaRepository<JpaAccountLegalAcceptance, String> {
    List<JpaAccountLegalAcceptance> findByUserId(String userId);
    List<JpaAccountLegalAcceptance> findByUserIdOrderByAcceptedAtDesc(String userId);
    Optional<JpaAccountLegalAcceptance> findByUserIdAndTermsVersionAndPrivacyVersion(
            String userId, String termsVersion, String privacyVersion);
    Optional<JpaAccountLegalAcceptance> findByUserIdAndTermsVersionAndPrivacyVersionAndAcceptanceSource(
            String userId, String termsVersion, String privacyVersion, String acceptanceSource);
    long countByUserId(String userId);
}
