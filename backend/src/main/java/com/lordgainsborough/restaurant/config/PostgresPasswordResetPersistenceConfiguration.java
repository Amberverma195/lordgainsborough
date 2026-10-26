package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.service.PasswordResetOtpChallengeStore;
import com.lordgainsborough.restaurant.service.PostgresPasswordResetOtpChallengeStore;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Spring Boot configuration for the opt-in PostgreSQL-backed password reset OTP challenge store.
 * <p>
 * Activates exclusively when property {@code app.security.postgres-password-reset.enabled=true}
 * is set under profiles {@code postgres-migration} or {@code test}. In standard runtime
 * (without this property), default MongoDB password reset persistence remains untouched.
 * </p>
 * <p>
 * When active, designates {@link PostgresPasswordResetOtpChallengeStore} as {@link Primary},
 * resolving unqualified {@code PasswordResetOtpChallengeStore} injection points unambiguously
 * to the PostgreSQL store while allowing both implementations to coexist.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.security.postgres-password-reset.enabled", havingValue = "true", matchIfMissing = false)
public class PostgresPasswordResetPersistenceConfiguration {

    private final boolean postgresUserAuthEnabled;
    private final boolean allowIsolatedTest;

    public PostgresPasswordResetPersistenceConfiguration(
            @Value("${app.security.postgres-user-auth.enabled:false}") boolean postgresUserAuthEnabled,
            @Value("${app.security.postgres-password-reset.allow-isolated-test:false}") boolean allowIsolatedTest) {
        this.postgresUserAuthEnabled = postgresUserAuthEnabled;
        this.allowIsolatedTest = allowIsolatedTest;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void validateCoordinatedActivation(ContextRefreshedEvent event) {
        if (!postgresUserAuthEnabled && !allowIsolatedTest) {
            ApplicationContext context = event.getApplicationContext();
            if (context.containsBean("authController")
                    || context.getBeanNamesForType(AuthController.class).length > 0) {
                throw new IllegalStateException(
                        "Unsupported mixed-backend activation: 'app.security.postgres-password-reset.enabled=true' cannot be activated " +
                        "while AuthController authenticates through MongoDB. Coordinated PostgreSQL user authentication (PG-022) " +
                        "must be enabled via 'app.security.postgres-user-auth.enabled=true' before activating PostgreSQL password reset in live auth flow.");
            }
        }
    }

    @Bean(name = "postgresPasswordResetOtpChallengeStore")
    @Primary
    public PasswordResetOtpChallengeStore postgresPasswordResetOtpChallengeStore(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            PasswordResetProperties properties) {
        return new PostgresPasswordResetOtpChallengeStore(dataSource, transactionManager, properties);
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
