package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.config.CatalogSeedSpecifications.CategorySpec;
import com.lordgainsborough.restaurant.config.CatalogSeedSpecifications.MenuSpec;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCatalogMigration;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCatalogMigrationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PostgresCatalogSynchronizationService {

    private static final Logger log = LoggerFactory.getLogger(PostgresCatalogSynchronizationService.class);

    /**
     * Deterministic 64-bit transaction advisory lock key:
     * ASCII values of "LORD_MEN": 0x4C4F52445F4D454EL = 5498725841443472718L.
     */
    public static final long CATALOG_SYNC_ADVISORY_LOCK_KEY = 5498725841443472718L;

    private final JpaCategoryRepository categoryRepository;
    private final JpaMenuItemRepository menuItemRepository;
    private final JpaCatalogMigrationRepository migrationRepository;
    private final EntityManager entityManager;

    public PostgresCatalogSynchronizationService(
            JpaCategoryRepository categoryRepository,
            JpaMenuItemRepository menuItemRepository,
            JpaCatalogMigrationRepository migrationRepository,
            EntityManager entityManager
    ) {
        this.categoryRepository = categoryRepository;
        this.menuItemRepository = menuItemRepository;
        this.migrationRepository = migrationRepository;
        this.entityManager = entityManager;
    }

    /**
     * Synchronizes categories and menu items transactionally.
     *
     * @return {@code true} if synchronization was executed and committed;
     *         {@code false} if skipped because the migration marker was already present
     */
    @Transactional(transactionManager = "transactionManager")
    public boolean synchronizeCatalog() {
        // 1. Acquire transaction-level advisory lock to coordinate concurrent application instances
        acquireAdvisoryLock();

        // 2. Check if migration was already applied (verified after acquiring lock)
        if (migrationRepository.existsById(CatalogSeedSpecifications.MIGRATION_ID)) {
            log.info("PostgreSQL catalog migration '{}' already applied; skipping synchronization.",
                    CatalogSeedSpecifications.MIGRATION_ID);
            return false;
        }

        log.info("Starting PostgreSQL catalog synchronization for migration '{}'...",
                CatalogSeedSpecifications.MIGRATION_ID);

        // 3. Synchronize categories
        syncCategories();

        // 4. Synchronize menu items
        syncMenuItems();

        // 5. Persist the migration marker
        JpaCatalogMigration marker = new JpaCatalogMigration(CatalogSeedSpecifications.MIGRATION_ID, Instant.now());
        migrationRepository.save(marker);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("PostgreSQL catalog synchronization successfully committed for migration '{}'.",
                            CatalogSeedSpecifications.MIGRATION_ID);
                }
            });
            log.info("PostgreSQL catalog synchronization work complete; pending transaction commit for migration '{}'.",
                    CatalogSeedSpecifications.MIGRATION_ID);
        } else {
            log.info("PostgreSQL catalog synchronization work complete for migration '{}'.",
                    CatalogSeedSpecifications.MIGRATION_ID);
        }
        return true;
    }

    private void acquireAdvisoryLock() {
        try {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
                    .setParameter("lockKey", CATALOG_SYNC_ADVISORY_LOCK_KEY)
                    .getResultList();
            log.debug("Acquired transaction-scoped PostgreSQL advisory lock ({})", CATALOG_SYNC_ADVISORY_LOCK_KEY);
        } catch (Exception e) {
            log.error("Failed to acquire PostgreSQL advisory lock: {}", e.getMessage());
            throw new IllegalStateException("Failed to acquire PostgreSQL advisory lock for catalog synchronization", e);
        }
    }

    void syncCategories() {
        for (CategorySpec spec : CatalogSeedSpecifications.categories()) {
            JpaCategory existing = categoryRepository.findByName(spec.name());
            if (existing == null) {
                // Check if a case-variant match exists to avoid duplicate constraint violations
                Optional<JpaCategory> caseMatch = categoryRepository.findByNameIgnoreCase(spec.name());
                if (caseMatch.isPresent()) {
                    existing = caseMatch.get();
                    existing.setName(spec.name());
                    existing.setDescription(spec.description());
                    existing.setDisplayOrder(spec.displayOrder());
                    categoryRepository.save(existing);
                } else {
                    JpaCategory newCategory = new JpaCategory(
                            UUID.randomUUID().toString(),
                            spec.name(),
                            spec.description(),
                            spec.displayOrder()
                    );
                    categoryRepository.save(newCategory);
                }
            } else {
                existing.setDescription(spec.description());
                existing.setDisplayOrder(spec.displayOrder());
                categoryRepository.save(existing);
            }
        }
    }

    void syncMenuItems() {
        for (MenuSpec spec : CatalogSeedSpecifications.menuCatalog()) {
            // Step 1: Exact canonical-name search (case-insensitive)
            List<JpaMenuItem> canonicalMatches = menuItemRepository.findAllByNameIgnoreCase(spec.name());
            if (canonicalMatches.size() > 1) {
                throw new IllegalStateException(
                        "Ambiguous canonical menu item match for '" + spec.name() + "': found "
                                + canonicalMatches.size() + " matching records"
                );
            }

            JpaMenuItem targetItem = null;
            if (canonicalMatches.size() == 1) {
                targetItem = canonicalMatches.get(0);
            } else if (spec.aliases() != null && spec.aliases().length > 0) {
                // Step 2: Alias searches
                List<JpaMenuItem> aliasMatches = new ArrayList<>();
                for (String alias : spec.aliases()) {
                    List<JpaMenuItem> matches = menuItemRepository.findAllByNameIgnoreCase(alias);
                    aliasMatches.addAll(matches);
                }
                if (aliasMatches.size() > 1) {
                    throw new IllegalStateException(
                            "Ambiguous alias menu item match for '" + spec.name() + "': found "
                                    + aliasMatches.size() + " matching records for aliases"
                    );
                }
                if (aliasMatches.size() == 1) {
                    targetItem = aliasMatches.get(0);
                }
            }

            BigDecimal price = toNumericScale2(spec.price());

            if (targetItem != null) {
                // Preserve ID, image, available, and isMostLoved operational fields
                targetItem.setName(spec.name());
                targetItem.setDescription(spec.description());
                targetItem.setPrice(price);
                targetItem.setCategory(spec.category());
                menuItemRepository.save(targetItem);
            } else {
                // Insert new menu item with specified defaults
                JpaMenuItem newItem = new JpaMenuItem(
                        UUID.randomUUID().toString(),
                        spec.name(),
                        spec.description(),
                        price,
                        spec.category(),
                        "",     // default empty image string
                        true,   // available = true
                        false   // isMostLoved = false
                );
                menuItemRepository.save(newItem);
            }
        }
    }

    public static BigDecimal toNumericScale2(double price) {
        return BigDecimal.valueOf(price).setScale(2, RoundingMode.UNNECESSARY);
    }
}
