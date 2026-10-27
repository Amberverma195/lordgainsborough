package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaCategoryRepository extends JpaRepository<JpaCategory, String> {

    /**
     * Finds a category by its exact unique name.
     *
     * @param name category name
     * @return the matching category, or {@code null} if not found (matching legacy contract)
     */
    JpaCategory findByName(String name);

    Optional<JpaCategory> findByNameIgnoreCase(String name);

    boolean existsByName(String name);

    boolean existsByNameIgnoreCase(String name);

    List<JpaCategory> findAllByOrderByDisplayOrderAsc();
}
