package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.PostgresSignupOtpChallengeStore;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeStore;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Spring Boot configuration for the opt-in PostgreSQL-backed signup OTP challenge store.
 * <p>
 * Activates exclusively when property {@code app.security.postgres-signup-otp.enabled=true}
 * is set under profiles {@code postgres-migration} or {@code test}. In standard runtime
 * (without this property), default MongoDB signup OTP persistence remains untouched.
 * </p>
 * <p>
 * When active, designates {@link PostgresSignupOtpChallengeStore} as {@link Primary},
 * resolving unqualified {@code SignupOtpChallengeStore} injection points unambiguously
 * to the PostgreSQL store while allowing both implementations to coexist.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.security.postgres-signup-otp.enabled", havingValue = "true", matchIfMissing = false)
public class PostgresSignupOtpPersistenceConfiguration {

    @Bean(name = "postgresSignupOtpChallengeStore")
    @Primary
    public SignupOtpChallengeStore postgresSignupOtpChallengeStore(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        return new PostgresSignupOtpChallengeStore(dataSource, transactionManager);
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
