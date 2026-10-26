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

    /**
     * Resolves the first menu item matching the specified name ignoring casing.
     * <p>
     * <b>Deferred Behavior / Duplicate Name Caveat:</b> Because {@code menu_items.name}
     * does not have a database unique constraint, multiple menu items may share the exact
     * same name. This method does not define an explicit SQL {@code ORDER BY} clause,
     * so PostgreSQL may return any matching record. Callers requiring stable, reproducible
     * first-match selection should use {@link #findFirstByNameIgnoreCaseOrderByIdAsc(String)}.
     * </p>
     *
     * @param name menu item name to match case-insensitively
     * @return an {@link Optional} containing a matching item, or empty if none found
     */
    Optional<JpaMenuItem> findFirstByNameIgnoreCase(String name);

    /**
     * Deterministically resolves the first menu item matching the specified name
     * ignoring casing, ordered by primary key {@code id} ascending.
     * <p>
     * Preferred over {@link #findFirstByNameIgnoreCase(String)} when stable, repeatable
     * selection across duplicate menu items is required.
     * </p>
     *
     * @param name menu item name to match case-insensitively
     * @return an {@link Optional} containing the deterministic first matching item by ID, or empty
     */
    Optional<JpaMenuItem> findFirstByNameIgnoreCaseOrderByIdAsc(String name);

    /**
     * Finds all menu items matching the specified name ignoring casing.
     * Used by catalog synchronization to detect ambiguous duplicate matches safely.
     *
     * @param name menu item name to match case-insensitively
     * @return list of all matching menu items
     */
    List<JpaMenuItem> findAllByNameIgnoreCase(String name);

    List<JpaMenuItem> findByAvailableTrue();

    List<JpaMenuItem> findByIsMostLovedTrue();

    boolean existsByName(String name);

    boolean existsByNameIgnoreCase(String name);
}
