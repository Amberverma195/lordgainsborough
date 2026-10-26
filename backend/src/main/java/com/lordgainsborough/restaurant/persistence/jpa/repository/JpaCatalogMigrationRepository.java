package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCatalogMigration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link JpaCatalogMigration} entities in {@code catalog_migrations}.
 * <p>
 * Mirrors all query methods defined on the MongoDB {@code CatalogMigrationRepository}.
 * </p>
 */
@Repository
public interface JpaCatalogMigrationRepository extends JpaRepository<JpaCatalogMigration, String> {
}
