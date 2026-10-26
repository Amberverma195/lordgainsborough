package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaSignupOtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link JpaSignupOtpChallenge} entities in {@code signup_otp_challenges}.
 * <p>
 * Primary key is {@code email_key}, an application-assigned SHA-256 hex digest of the normalized email.
 * Challenge primary keys are never randomly generated or re-hashed inside this repository.
 * </p>
 * <p>
 * <b>Uniqueness Semantics:</b>
 * The V1 relational schema defines a non-unique index on {@code challenge_id} ({@code idx_signup_otp_challenge_id}).
 * Therefore, queries by {@code challengeId} return {@link List} rather than {@code Optional}.
 * </p>
 * <p>
 * <b>Concurrency & Lifecycle Note:</b>
 * This repository provides standard CRUD and discovery operations. High-concurrency operations
 * (atomic challenge state transitions, lease acquisition, idempotency replay, and OTP verification)
 * are explicitly deferred to service migration (PG-015).
 * </p>
 */
@Repository
public interface JpaSignupOtpChallengeRepository extends JpaRepository<JpaSignupOtpChallenge, String> {

    /**
     * Finds all signup challenges matching the given challenge ID.
     * <p>
     * Note: Returns {@link List} because {@code challenge_id} is indexed but not constrained unique in V1.
     * </p>
     *
     * @param challengeId server-generated challenge identifier
     * @return list of matching challenges
     */
    List<JpaSignupOtpChallenge> findByChallengeId(String challengeId);

    /**
     * Finds all signup challenges in a given status.
     *
     * @param status challenge lifecycle status
     * @return list of matching challenges
     */
    List<JpaSignupOtpChallenge> findByStatus(OtpChallengeStatus status);

    /**
     * Finds all signup challenges whose absolute expiration timestamp is on or before the supplied cutoff.
     *
     * @param cutoff expiration threshold instant
     * @return list of expired challenges
     */
    List<JpaSignupOtpChallenge> findByChallengeExpiresAtLessThanEqual(Instant cutoff);

    /**
     * Finds all signup challenges matching a client idempotency key.
     *
     * @param clientIdempotencyKey client-supplied idempotency key
     * @return list of matching challenges
     */
    List<JpaSignupOtpChallenge> findByClientIdempotencyKey(String clientIdempotencyKey);
}
