package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;

/** No production cutover until all order consumers share PostgreSQL storage. */
@Configuration(proxyBeanMethods = false)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.orders.postgres.enabled", havingValue = "true")
public class PostgresOrderPersistenceConfiguration {
    @Bean("transactionManager")
    @ConditionalOnMissingBean(name = "transactionManager")
    JpaTransactionManager repositoryTransactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean("orderJpaTransactionManager")
    JpaTransactionManager orderJpaTransactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    @Primary
    PostgresCustomerOrderStore postgresCustomerOrderStore(JpaCustomerOrderRepository orders,
            JpaMenuItemRepository menu, JpaOrderReviewRepository reviews, JpaStoreSettingsRepository settings,
            JpaUserRepository users, @Qualifier("orderJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresCustomerOrderStore(orders, menu, reviews, settings, users, manager);
    }

    @Bean
    static BeanFactoryPostProcessor validateOrderStorageCoherence() {
        return factory -> {
            // These concrete handlers still inject Mongo repositories. No boolean can make them coherent.
            // There is deliberately no allow-isolated-test bypass when legacy consumers are present.
            for (Class<?> legacyConsumer : new Class<?>[] {KdsController.class, ReviewController.class,
                    SocketIoConfig.class, AuthController.class, CustomerNoShowService.class,
                    CustomerOrderPresentationService.class}) {
                if (factory.getBeanNamesForType(legacyConsumer, true, false).length > 0) {
                    throw new IllegalStateException("Unsupported mixed-backend order activation: "
                            + legacyConsumer.getSimpleName() + " still uses MongoDB order state; "
                            + "PG-017 is isolated until coordinated PG-019/PG-020/PG-022/PG-023 rollout");
                }
            }
        };
    }
}
