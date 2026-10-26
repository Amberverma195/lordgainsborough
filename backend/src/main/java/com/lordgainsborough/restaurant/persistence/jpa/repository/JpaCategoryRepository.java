package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link JpaCategory} entities in {@code categories}.
 * <p>
 * Mirrors all query methods defined on the MongoDB {@code CategoryRepository}.
 * </p>
 */
@Repository
public interface JpaCategoryRepository extends JpaRepository<JpaCategory, String> {

    Optional<JpaCategory> findByName(String name);

    Optional<JpaCategory> findByNameIgnoreCase(String name);

    boolean existsByName(String name);

    boolean existsByNameIgnoreCase(String name);

    List<JpaCategory> findAllByOrderByDisplayOrderAsc();
}
