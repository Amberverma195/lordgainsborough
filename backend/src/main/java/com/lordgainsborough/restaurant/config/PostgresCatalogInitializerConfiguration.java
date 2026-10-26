package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCatalogMigrationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * PG-012: Startup configuration for PostgreSQL catalog seeding.
 * <p>
 * Strictly opt-in: activated only when all of the following conditions are met:
 * <ol>
 *   <li>The active Spring profile includes either {@code postgres-migration} or {@code test}.</li>
 *   <li>The explicit property {@code app.catalog.postgres-seed.enabled=true} is provided (defaults to {@code false}).</li>
 * </ol>
 * In the default runtime or standard {@code postgres-migration} connectivity testing, this configuration backs off completely.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.catalog.postgres-seed.enabled", havingValue = "true", matchIfMissing = false)
public class PostgresCatalogInitializerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PostgresCatalogInitializerConfiguration.class);

    @Bean(name = "transactionManager")
    @ConditionalOnMissingBean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(EntityManager entityManager) {
        log.info("Registering JPA transactionManager bean (ensuring coexistence with non-relational transaction managers).");
        return new JpaTransactionManager(entityManager.getEntityManagerFactory());
    }

    @Bean
    public PostgresCatalogSynchronizationService postgresCatalogSynchronizationService(
            JpaCategoryRepository categoryRepository,
            JpaMenuItemRepository menuItemRepository,
            JpaCatalogMigrationRepository migrationRepository,
            EntityManager entityManager
    ) {
        log.info("Registering PostgresCatalogSynchronizationService bean (opt-in enabled).");
        return new PostgresCatalogSynchronizationService(
                categoryRepository,
                menuItemRepository,
                migrationRepository,
                entityManager
        );
    }

    @Bean
    public CommandLineRunner postgresCatalogCommandLineRunner(PostgresCatalogSynchronizationService syncService) {
        return args -> {
            log.info("Executing opt-in PostgreSQL catalog initialization CommandLineRunner...");
            syncService.synchronizeCatalog();
        };
    }
}
