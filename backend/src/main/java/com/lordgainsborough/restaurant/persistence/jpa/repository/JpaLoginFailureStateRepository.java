package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaLoginFailureState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link JpaLoginFailureState} entities in {@code login_failure_states}.
 * <p>
 * <b>Architectural Note:</b>
 * In the legacy MongoDB architecture, failed login state was managed directly via {@code MongoTemplate}
 * without a dedicated {@code MongoRepository} interface. {@link JpaLoginFailureStateRepository} is an
 * original PostgreSQL repository introduced to establish typed Spring Data JPA persistence for
 * login failure tracking.
 * </p>
 * <p>
 * Primary key is an application-assigned keyed hash string (VARCHAR(128)).
 * Subject hashes are keyed cryptographic digests of the client IP and normalized login identifier.
 * Raw IP addresses and plaintext credentials must never be passed to query methods.
 * </p>
 * <p>
 * <b>Concurrency & Locking Note:</b>
 * Atomic CAS increments, cooldown mutations, and lockout transitions are explicitly deferred to PG-014.
 * </p>
 */
@Repository
public interface JpaLoginFailureStateRepository extends JpaRepository<JpaLoginFailureState, String> {

    /**
     * Finds all login failure records matching the specified keyed subject hash.
     *
     * @param subjectHash keyed cryptographic digest of the client IP and login identifier
     * @return list of matching login failure records
     */
    List<JpaLoginFailureState> findBySubjectHash(String subjectHash);

    /**
     * Finds all login failure records whose overall expiration timestamp is on or before the supplied cutoff.
     *
     * @param cutoff expiration threshold instant
     * @return list of expired login failure records
     */
    List<JpaLoginFailureState> findByExpiresAtLessThanEqual(Instant cutoff);

    /**
     * Finds all login failure records actively in a lockout state at the given reference instant.
     * <p>
     * An active lockout requires {@code blocked_until > :now} AND non-expired record state ({@code expires_at > :now}).
     * </p>
     *
     * @param now reference instant to evaluate active lockout against
     * @return list of actively blocked failure states
     */
    @Query("SELECT s FROM JpaLoginFailureState s WHERE s.blockedUntil IS NOT NULL AND s.blockedUntil > :now AND s.expiresAt > :now")
    List<JpaLoginFailureState> findActiveBlocks(@Param("now") Instant now);

    /**
     * Finds login failure states where {@code blockedUntil} is after the specified cutoff
     * and {@code expiresAt} is after the specified cutoff.
     *
     * @param blockedUntilCutoff threshold for blockedUntil
     * @param expiresAtCutoff threshold for expiresAt
     * @return list of matching login failure states
     */
    List<JpaLoginFailureState> findByBlockedUntilAfterAndExpiresAtAfter(Instant blockedUntilCutoff, Instant expiresAtCutoff);
}
