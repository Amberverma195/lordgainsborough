package com.lordgainsborough.restaurant.config;
import com.lordgainsborough.restaurant.controller.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.Clock;
@Configuration(proxyBeanMethods=false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
public class PostgresReviewPersistenceConfiguration {
    @Bean @Primary PostgresReviewStore postgresReviewStore(JpaCustomerOrderRepository orders, JpaOrderReviewRepository reviews,
            PostgresCustomerOrderStore checkout, OrderAccessService access, Clock clock,
            @Qualifier("orderJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresReviewStore(orders,reviews,checkout,access,manager,clock);
    }
    @Bean static BeanFactoryPostProcessor requireReviewDataset() {
        return factory -> {
            if (factory.getBeanNamesForType(PostgresCustomerOrderStore.class,true,false).length==0)
                throw new IllegalStateException("Unsupported mixed-backend review activation: PostgreSQL checkout required");
            PostgresIdentityCoherence.preflight(factory, AdminAuthInterceptor.class, AuthController.class);
        };
    }
    @Bean SmartInitializingSingleton validateReviewSelection(ApplicationContext context) {
        return () -> {
            var selected=context.getBean(CustomerOrderStore.class);
            PostgresIdentityCoherence.requireIfPresent(context, context.getBean(PostgresReviewStore.class).getEntityManagerFactory());
            PostgresIdentityCoherence.requireAdministratorHttp(context, context.getBean(PostgresReviewStore.class).getEntityManagerFactory());
            for (ReviewController controller:context.getBeansOfType(ReviewController.class).values()) {
                if (!(controller.getReviewStore() instanceof PostgresReviewStore review) || review.getOrderStore()!=selected)
                    throw new IllegalStateException("Unsupported mixed-backend review activation: actual injected review/order stores differ");
            }
        };
    }
}
