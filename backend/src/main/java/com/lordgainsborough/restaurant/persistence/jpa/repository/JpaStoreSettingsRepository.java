package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaStoreSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link JpaStoreSettings} entities in {@code store_settings}.
 * <p>
 * Mirrors all query methods defined on the MongoDB {@code StoreSettingsRepository}.
 * </p>
 */
@Repository
public interface JpaStoreSettingsRepository extends JpaRepository<JpaStoreSettings, String> {
}
