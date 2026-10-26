package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaPasswordResetOtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link JpaPasswordResetOtpChallenge} entities in {@code password_reset_otp_challenges}.
 * <p>
 * Primary key is {@code email_key}, an assigned string derived from the normalized email address.
 * </p>
 * <p>
 * <b>Uniqueness Semantics:</b>
 * The V1 relational schema enforces {@code uq_pwd_reset_challenge_id UNIQUE (challenge_id)}.
 * Therefore, {@link #findByChallengeId(String)} safely returns {@link Optional}, preserving
 * the legacy {@code PasswordResetOtpChallengeRepository} contract.
 * </p>
 * <p>
 * <b>User Relationship:</b>
 * The user association is mapped through {@link JpaPasswordResetOtpChallenge#getUser()} with foreign key
 * {@code fk_pwd_reset_user REFERENCES users(id) ON DELETE CASCADE}. Lookups by user ID navigate
 * through the relationship safely via parameterized JPQL and Spring Data nested property traversal.
 * </p>
 * <p>
 * <b>Concurrency & Lifecycle Note:</b>
 * Atomic transitions, verification attempts, and password reset transactions belong to PG-016.
 * </p>
 */
@Repository
public interface JpaPasswordResetOtpChallengeRepository extends JpaRepository<JpaPasswordResetOtpChallenge, String> {

    /**
     * Resolves a password reset challenge by its unique server-generated challenge ID.
     * Guaranteed at most one result by PostgreSQL unique constraint {@code uq_pwd_reset_challenge_id}.
     *
     * @param challengeId unique challenge identifier
     * @return an {@link Optional} containing the challenge, or empty if none matches
     */
    Optional<JpaPasswordResetOtpChallenge> findByChallengeId(String challengeId);

    /**
     * Finds all password reset challenges associated with the given user ID via explicit JPQL.
     *
     * @param userId primary key of the associated user
     * @return list of matching challenges
     */
    @Query("SELECT c FROM JpaPasswordResetOtpChallenge c WHERE c.user.id = :userId")
    List<JpaPasswordResetOtpChallenge> findByUserId(@Param("userId") String userId);

    /**
     * Finds all password reset challenges associated with the given user ID via Spring Data property traversal.
     *
     * @param userId primary key of the associated user
     * @return list of matching challenges
     */
    List<JpaPasswordResetOtpChallenge> findByUser_Id(String userId);

    /**
     * Finds all password reset challenges in a given status.
     *
     * @param status challenge status
     * @return list of matching challenges
     */
    List<JpaPasswordResetOtpChallenge> findByStatus(PasswordResetChallengeStatus status);

    /**
     * Finds all password reset challenges whose absolute expiration timestamp is on or before the supplied cutoff.
     *
     * @param cutoff expiration threshold instant
     * @return list of expired challenges
     */
    List<JpaPasswordResetOtpChallenge> findByChallengeExpiresAtLessThanEqual(Instant cutoff);

    /**
     * Finds all password reset challenges matching a client idempotency key.
     *
     * @param clientIdempotencyKey client-supplied idempotency key
     * @return list of matching challenges
     */
    List<JpaPasswordResetOtpChallenge> findByClientIdempotencyKey(String clientIdempotencyKey);
}
