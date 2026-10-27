package com.lordgainsborough.restaurant.config;
import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.controller.ReservationController;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaReservationRepository;
import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.Clock;
@Configuration(proxyBeanMethods=false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
public class PostgresReservationPersistenceConfiguration {

    @Bean @Primary PostgresReservationStore postgresReservationStore(JpaReservationRepository repository, Clock clock,
            @Qualifier("reservationJpaTransactionManager") JpaTransactionManager manager) {
        return new PostgresReservationStore(repository,manager,clock);
    }
    @Bean static BeanFactoryPostProcessor guardReservationIdentities() {
        return factory -> {
            PostgresIdentityCoherence.preflight(factory, AuthController.class, AdminAuthInterceptor.class);
        };
    }
    @Bean SmartInitializingSingleton validateReservationSelection(ApplicationContext context) {
        return () -> {
            var selected=context.getBean(PostgresReservationStore.class);
            PostgresIdentityCoherence.requireIfPresent(context, selected.getEntityManagerFactory());
            PostgresIdentityCoherence.requireAdministratorHttp(context, selected.getEntityManagerFactory());
            for (ReservationController controller:context.getBeansOfType(ReservationController.class).values())
                if (controller.getReservationStore()!=selected)
                    throw new IllegalStateException("Unsupported mixed-backend reservation activation: injected store differs");
        };
    }
}
