package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link JpaMenuItem} entities in {@code menu_items}.
 * <p>
 * Mirrors all query methods defined on the MongoDB {@code MenuItemRepository}.
 * </p>
 */
@Repository
public interface JpaMenuItemRepository extends JpaRepository<JpaMenuItem, String> {

    List<JpaMenuItem> findByCategory(String category);

    Optional<JpaMenuItem> findFirstByNameIgnoreCase(String name);

    List<JpaMenuItem> findByAvailableTrue();

    boolean existsByName(String name);

    boolean existsByNameIgnoreCase(String name);
}
