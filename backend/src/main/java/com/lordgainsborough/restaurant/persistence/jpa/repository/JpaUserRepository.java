package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link JpaUser} entities in {@code users}.
 * <p>
 * Mirrors all query methods defined on the MongoDB {@code UserRepository}.
 * </p>
 */
@Repository
public interface JpaUserRepository extends JpaRepository<JpaUser, String> {

    /** Closed projection: search must never select credentials or reset metadata. */
    interface AdminSearchUser {
        String getId();
        String getName();
        String getEmail();
        String getPhone();
        String getPhoneNormalized();
        String getRole();
        String getMemberSince();
        int getNoShowCount();
    }

    // Spring Data Containing escapes LIKE metacharacters and binds the literal input.
    List<AdminSearchUser> findAdminByNameContainingIgnoreCaseOrderByIdAsc(
            String name, org.springframework.data.domain.Pageable limit);
    List<AdminSearchUser> findAdminByEmailContainingIgnoreCaseOrderByIdAsc(
            String email, org.springframework.data.domain.Pageable limit);
    List<AdminSearchUser> findAdminByPhoneNormalizedContainingOrderByIdAsc(
            String phone, org.springframework.data.domain.Pageable limit);

    /** Counter-only write: never rewrite password, role, token version or profile columns. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update JpaUser u set u.noShowCount = :count where u.id = :id")
    int updateNoShowCount(@org.springframework.data.repository.query.Param("id") String id,
            @org.springframework.data.repository.query.Param("count") int count);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from JpaUser u where lower(u.email) = lower(:email)")
    Optional<JpaUser> findForNoShowByEmailIgnoreCase(@org.springframework.data.repository.query.Param("email") String email);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<JpaUser> findFirstByPhoneNormalizedOrderByIdAsc(String phoneNormalized);

    /** All PG-018 operations lock this stable owner row, even before the user's first cart exists. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from JpaUser u where lower(u.email) = lower(:email)")
    Optional<JpaUser> findForCartByEmailIgnoreCase(@org.springframework.data.repository.query.Param("email") String email);

    Optional<JpaUser> findByEmail(String email);

    Optional<JpaUser> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    boolean existsByPhoneNormalized(String phoneNormalized);

    List<JpaUser> findByPhoneNormalized(String phoneNormalized);

    List<JpaUser> findByPhoneNormalizedContaining(String phoneNormalized);

    List<JpaUser> findByPhoneIsNotNull();

    List<JpaUser> findByNameContainingIgnoreCase(String name);

    List<JpaUser> findByEmailContainingIgnoreCase(String email);

    List<JpaUser> findByRole(String role);
}
