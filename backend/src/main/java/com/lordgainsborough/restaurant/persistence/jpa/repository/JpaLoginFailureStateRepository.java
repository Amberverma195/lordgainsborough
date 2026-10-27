package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaLoginFailureState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

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
