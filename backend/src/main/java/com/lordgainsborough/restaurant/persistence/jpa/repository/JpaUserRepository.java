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
}
