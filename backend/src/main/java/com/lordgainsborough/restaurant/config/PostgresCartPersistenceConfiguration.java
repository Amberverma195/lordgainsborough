package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCartRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.PostgresCartStore;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.Clock;

/** PG-018 is available to isolated migration fixtures, never a mixed live Mongo authentication flow. */
@Configuration(proxyBeanMethods = false)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.cart.postgres.enabled", havingValue = "true")
public class PostgresCartPersistenceConfiguration {
    @Bean("transactionManager")
    @ConditionalOnMissingBean(name = "transactionManager")
    JpaTransactionManager repositoryTransactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean("cartJpaTransactionManager")
    JpaTransactionManager cartJpaTransactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    @Primary
    PostgresCartStore postgresCartStore(JpaCartRepository carts, JpaUserRepository users, JwtUtil jwt,
            Clock clock, @Qualifier("cartJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresCartStore(carts, users, jwt, clock, manager);
    }

    @Bean
    static BeanFactoryPostProcessor validateCartIdentityCoherence() {
        return factory -> {
            PostgresIdentityCoherence.preflight(factory, AuthController.class);
        };
    }
    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton validateInjectedCartIdentity(
            org.springframework.context.ApplicationContext context, PostgresCartStore cart) {
        return () -> {
            PostgresIdentityCoherence.requireIfPresent(context, cart.getEntityManagerFactory());
            for (AuthController controller : context.getBeansOfType(AuthController.class).values()) {
                var identity = PostgresIdentityCoherence.require(context, cart.getEntityManagerFactory());
                if (identity.getUserRepository() != cart.getUserRepository())
                    throw PostgresIdentityCoherence.incompatible("cart/user repository mismatch");
            }
        };
    }
}
