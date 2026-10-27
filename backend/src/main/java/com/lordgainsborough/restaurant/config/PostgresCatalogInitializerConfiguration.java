package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCatalogMigrationRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PostgreSQL persistence for the application. */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
public class PostgresCatalogInitializerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PostgresCatalogInitializerConfiguration.class);

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
    @Bean
    public CommandLineRunner postgresStoreDefaults(javax.sql.DataSource dataSource) {
        return args -> new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "INSERT INTO store_settings (id) VALUES ('default') ON CONFLICT (id) DO NOTHING");
    }
}
