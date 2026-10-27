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
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

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
            PostgresIdentityCoherence.preflight(factory, AuthController.class);
            // The live Socket.IO handler still injects Mongo repositories. No boolean can make them coherent.
            // There is deliberately no allow-isolated-test bypass when legacy consumers are present.
            for (Class<?> legacyConsumer : new Class<?>[] {
                    SocketIoConfig.class}) {
                if (factory.getBeanNamesForType(legacyConsumer, true, false).length > 0) {
                    throw new IllegalStateException("Unsupported mixed-backend order activation: "
                            + legacyConsumer.getSimpleName() + " still uses MongoDB order state; "
                            + "PG-017 is isolated until coordinated PG-019/PG-020/PG-022/PG-023 rollout");
                }
            }
        };
    }

    @Bean
    SmartInitializingSingleton validateInjectedOrderConsumers(ApplicationContext context) {
        return () -> {
            CustomerOrderStore selected = context.getBean(CustomerOrderStore.class);
            if (!(selected instanceof PostgresCustomerOrderStore checkout)) throw incoherent("checkout store");
            EntityManagerFactory emf = checkout.getEntityManagerFactory();
            PostgresIdentityCoherence.requireIfPresent(context, emf);
            PostgresIdentityCoherence.requireAdministratorHttp(context, emf);
            for (ReviewController controller : context.getBeansOfType(ReviewController.class).values()) {
                if (!(controller.getReviewStore() instanceof PostgresReviewStore review)
                        || review.getOrderStore() != checkout || review.getEntityManagerFactory() != emf)
                    throw incoherent("injected review store/order linkage");
            }
            for (KdsController controller : context.getBeansOfType(KdsController.class).values()) {
                if (!(controller.getOrderStore() instanceof PostgresKdsOrderStore kds)
                        || kds.getEntityManagerFactory() != emf
                        || kds.getNoShowStore().getEntityManagerFactory() != emf
                        || !(kds.getPresentationService().getCountStore() instanceof PostgresCustomerOrderCountStore counts)
                        || counts.getEntityManagerFactory() != emf) throw incoherent("injected KDS store/dependencies");
                if (context.getBeansOfType(KdsAccessService.class).isEmpty()) throw incoherent("missing KDS IP authorization");
            }
            for (CustomerNoShowService service : context.getBeansOfType(CustomerNoShowService.class).values()) {
                if (!(service.getStore() instanceof PostgresCustomerNoShowStore store)
                        || store.getEntityManagerFactory() != emf) throw incoherent("injected no-show store");
            }
            for (CustomerOrderPresentationService service : context.getBeansOfType(CustomerOrderPresentationService.class).values()) {
                if (!(service.getCountStore() instanceof PostgresCustomerOrderCountStore counts)
                        || counts.getEntityManagerFactory() != emf) throw incoherent("injected presentation count store");
            }
            for (KdsAccessService access : context.getBeansOfType(KdsAccessService.class).values()) {
                if (!(access.getSettingsSource() instanceof PostgresKdsSettingsSource settings)
                        || settings.getEntityManagerFactory() != emf) throw incoherent("injected KDS IP settings source");
            }
        };
    }

    private static IllegalStateException incoherent(String dependency) {
        return new IllegalStateException("Unsupported mixed-backend order activation: " + dependency
                + " must use the same actual PostgreSQL persistence unit as checkout");
    }
}
