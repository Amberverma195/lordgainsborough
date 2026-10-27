package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.AdminCustomerController;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.JpaContext;
import org.springframework.orm.jpa.JpaTransactionManager;

/** PG-021 is available only to isolated migration contexts until real PG-022 authorization exists. */
@Configuration(proxyBeanMethods = false)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.admin.customer-search.postgres.enabled", havingValue = "true")
public class PostgresAdminCustomerSearchPersistenceConfiguration {
    @Bean @Primary
    PostgresAdminCustomerSearchStore postgresAdminCustomerSearchStore(JpaUserRepository users,
            JpaCustomerOrderRepository orders,
            @Qualifier("orderJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresAdminCustomerSearchStore(users, orders, manager);
    }

    @Bean
    static BeanFactoryPostProcessor requireIsolatedAdminSearch() {
        return factory -> {
            if (factory.getBeanNamesForType(AdminAuthInterceptor.class, true, false).length > 0)
                throw new IllegalStateException("PostgreSQL admin search rejected: actual MongoDB AdminAuthInterceptor remains; PG-022 required");
            if (factory.getBeanNamesForType(AdminCustomerController.class, true, false).length > 0)
                // No public HTTP surface without real PostgreSQL admin authorization. There is no flag bypass.
                throw new IllegalStateException("PostgreSQL admin search is isolated only: HTTP activation awaits PG-022 administrator authorization");
            if (factory.getBeanNamesForType(PostgresCustomerOrderStore.class, true, false).length == 0)
                throw new IllegalStateException("PostgreSQL admin search requires actual PostgreSQL checkout/user persistence");
        };
    }

    @Bean
    SmartInitializingSingleton validateAdminSearchDataset(ApplicationContext context, JpaContext jpaContext) {
        return () -> {
            var selected = context.getBean(AdminCustomerSearchStore.class);
            if (!(selected instanceof PostgresAdminCustomerSearchStore search)) throw incoherent();
            if (!(context.getBean(CustomerOrderStore.class) instanceof PostgresCustomerOrderStore checkout))
                throw incoherent();
            EntityManagerFactory emf = search.getEntityManagerFactory();
            if (checkout.getEntityManagerFactory() != emf
                    || checkout.getUserRepository() != search.getUserRepository()
                    || checkout.getOrderRepository() != search.getOrderRepository()
                    || jpaContext.getEntityManagerByManagedType(JpaUser.class).getEntityManagerFactory() != emf
                    || jpaContext.getEntityManagerByManagedType(JpaCustomerOrder.class).getEntityManagerFactory() != emf)
                throw incoherent();
            // Ambiguous/alien persistence units cannot masquerade as a coherent migration context.
            for (EntityManagerFactory candidate : context.getBeansOfType(EntityManagerFactory.class).values())
                if (candidate != emf) throw incoherent();
            for (AdminCustomerSearchService service : context.getBeansOfType(AdminCustomerSearchService.class).values())
                if (service.getStore() != search) throw incoherent();
        };
    }

    private static IllegalStateException incoherent() {
        return new IllegalStateException("PostgreSQL admin search requires actual shared user/order repositories and EntityManagerFactory");
    }
}
