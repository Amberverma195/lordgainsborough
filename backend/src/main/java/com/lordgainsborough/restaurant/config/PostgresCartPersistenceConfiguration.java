package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCartRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.PostgresCartStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.Clock;

/** PostgreSQL persistence for the application. */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
public class PostgresCartPersistenceConfiguration {

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
