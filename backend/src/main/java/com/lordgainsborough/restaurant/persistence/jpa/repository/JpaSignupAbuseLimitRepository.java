package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaSignupAbuseLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link JpaSignupAbuseLimit} entities in {@code signup_abuse_limits}.
 * <p>
 * Primary key is an application-assigned string (VARCHAR(128)) formatted as a keyed composite hash.
 * Subject hashes are keyed cryptographic digests; raw client IP addresses must never be used as query inputs.
 * </p>
 * <p>
 * <b>Uniqueness & Concurrency Semantics:</b>
 * The composite index {@code idx_abuse_limits_bucket_subject (bucket_type, subject_hash)} is non-unique in V1.
 * Methods querying by bucket type and subject hash return {@link List}.
 * Standard {@link #save(Object)} does not provide atomic upsert semantics; atomic rate-limiting
 * increments are explicitly deferred to PG-013.
 * </p>
 */
@Repository
public interface JpaSignupAbuseLimitRepository extends JpaRepository<JpaSignupAbuseLimit, String> {

    /**
     * Finds all abuse limit records matching the specified bucket type and keyed subject hash.
     * <p>
     * Returns {@link List} because {@code (bucket_type, subject_hash)} is indexed but not constrained unique.
     * </p>
     *
     * @param bucketType rate limit bucket category
     * @param subjectHash keyed cryptographic digest of the client identifier
     * @return list of matching abuse limit records
     */
    List<JpaSignupAbuseLimit> findByBucketTypeAndSubjectHash(SignupAbuseLimit.BucketType bucketType, String subjectHash);

    /**
     * Finds all abuse limit records matching the specified keyed subject hash.
     *
     * @param subjectHash keyed cryptographic digest of the client identifier
     * @return list of matching abuse limit records
     */
    List<JpaSignupAbuseLimit> findBySubjectHash(String subjectHash);

    /**
     * Finds all abuse limit records whose window expiration timestamp is on or before the supplied cutoff.
     *
     * @param cutoff expiration threshold instant
     * @return list of expired abuse limit records
     */
    List<JpaSignupAbuseLimit> findByExpiresAtLessThanEqual(Instant cutoff);
}
