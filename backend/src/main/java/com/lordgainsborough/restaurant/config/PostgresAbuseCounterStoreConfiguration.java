package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.PostgresAbuseCounterStore;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * PG-013: Opt-in configuration for PostgreSQL AbuseCounterStore.
 * <p>
 * Strictly opt-in via {@code app.security.postgres-abuse-store.enabled=true} under
 * {@code postgres-migration} or {@code test} profiles. In default/production runtime,
 * this configuration is never loaded, ensuring {@code MongoAbuseCounterStore} remains
 * the sole active bean.
 * </p>
 * <p>
 * When enabled alongside {@code MongoAbuseCounterStore}, this bean is designated as
 * {@link Primary}, resolving unqualified {@code AbuseCounterStore} injection points
 * unambiguously to the PostgreSQL store while allowing both implementations to coexist.
 * </p>
 * <p>
 * Provides an independent fallback {@code transactionManager} bean when coexisting with
 * {@code MongoTransactionManager}, ensuring Spring's transactional proxying for
 * {@code @Transactional(transactionManager = "transactionManager")} operates reliably
 * even when catalog seeding configuration is inactive.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.security.postgres-abuse-store.enabled", havingValue = "true", matchIfMissing = false)
public class PostgresAbuseCounterStoreConfiguration {

    @Bean(name = {"postgresAbuseCounterStore", "abuseCounterStore"})
    @Primary
    public PostgresAbuseCounterStore postgresAbuseCounterStore(DataSource dataSource) {
        return new PostgresAbuseCounterStore(dataSource);
    }

    @Bean(name = "transactionManager")
    @ConditionalOnMissingBean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(
            ObjectProvider<EntityManagerFactory> emfProvider,
            DataSource dataSource) {
        EntityManagerFactory emf = emfProvider.getIfAvailable();
        if (emf != null) {
            return new JpaTransactionManager(emf);
        }
        return new DataSourceTransactionManager(dataSource);
    }
}
