package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
public class PostgresKdsPersistenceConfiguration {
    @Bean @Primary
    PostgresCustomerNoShowStore postgresCustomerNoShowStore(JpaCustomerOrderRepository orders, JpaUserRepository users,
            @Qualifier("orderJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresCustomerNoShowStore(orders,users,manager);
    }
    @Bean @Primary
    PostgresCustomerOrderCountStore postgresCustomerOrderCountStore(JpaCustomerOrderRepository orders,
            @Qualifier("orderJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresCustomerOrderCountStore(orders,manager.getEntityManagerFactory());
    }
    @Bean @Primary
    PostgresKdsSettingsSource postgresKdsSettingsSource(JpaStoreSettingsRepository settings,
            @Qualifier("orderJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresKdsSettingsSource(settings,manager.getEntityManagerFactory());
    }
    @Bean @Primary
    PostgresKdsOrderStore postgresKdsOrderStore(JpaCustomerOrderRepository orders, PostgresCustomerNoShowStore noShows,
            CustomerOrderPresentationService presentation, Clock clock,
            @Qualifier("orderJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresKdsOrderStore(orders,noShows,presentation,manager,clock);
    }
    @Bean static BeanFactoryPostProcessor requireCheckoutDataset() {
        return factory -> {
            if (factory.getBeanNamesForType(PostgresCustomerOrderStore.class,true,false).length == 0)
                throw new IllegalStateException("Unsupported mixed-backend order activation: PostgreSQL KDS requires actual PostgreSQL checkout persistence");
        };
    }
}
