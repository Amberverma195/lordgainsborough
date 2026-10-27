package com.lordgainsborough.restaurant.config;
import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.controller.ReservationController;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaReservationRepository;
import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.Clock;
@Configuration(proxyBeanMethods=false)
@Profile({"postgres-migration","test"})
@ConditionalOnProperty(name="app.reservations.postgres.enabled",havingValue="true")
public class PostgresReservationPersistenceConfiguration {
    @Bean("reservationJpaTransactionManager") JpaTransactionManager reservationJpaTransactionManager(EntityManagerFactory emf) { return new JpaTransactionManager(emf); }
    @Bean("transactionManager") @ConditionalOnMissingBean(name="transactionManager")
    JpaTransactionManager reservationRepositoryTransactionManager(EntityManagerFactory emf) { return new JpaTransactionManager(emf); }
    @Bean @Primary PostgresReservationStore postgresReservationStore(JpaReservationRepository repository, Clock clock,
            @Qualifier("reservationJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresReservationStore(repository,manager,clock);
    }
    @Bean static BeanFactoryPostProcessor guardReservationIdentities() {
        return factory -> {
            for (Class<?> legacy:new Class<?>[]{AuthController.class,AdminAuthInterceptor.class})
                if (factory.getBeanNamesForType(legacy,true,false).length>0)
                    throw new IllegalStateException("Unsupported mixed-backend reservation activation: MongoDB authentication/roles remain deferred to PG-022");
        };
    }
    @Bean SmartInitializingSingleton validateReservationSelection(ApplicationContext context) {
        return () -> {
            var selected=context.getBean(PostgresReservationStore.class);
            for (ReservationController controller:context.getBeansOfType(ReservationController.class).values())
                if (controller.getReservationStore()!=selected)
                    throw new IllegalStateException("Unsupported mixed-backend reservation activation: injected store differs");
        };
    }
}
