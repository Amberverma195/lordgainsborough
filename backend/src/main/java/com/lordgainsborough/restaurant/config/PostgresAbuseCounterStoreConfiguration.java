package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.PostgresAbuseCounterStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * PG-013: Opt-in configuration for PostgreSQL AbuseCounterStore.
 * <p>
 * Strictly opt-in via {@code app.security.postgres-abuse-store.enabled=true} under
 * {@code postgres-migration} or {@code test} profiles. In default/production runtime,
 * this configuration is never loaded, ensuring {@code MongoAbuseCounterStore} remains
 * the sole active bean.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.security.postgres-abuse-store.enabled", havingValue = "true", matchIfMissing = false)
public class PostgresAbuseCounterStoreConfiguration {

    @Bean
    public PostgresAbuseCounterStore postgresAbuseCounterStore(DataSource dataSource) {
        return new PostgresAbuseCounterStore(dataSource);
    }
}
