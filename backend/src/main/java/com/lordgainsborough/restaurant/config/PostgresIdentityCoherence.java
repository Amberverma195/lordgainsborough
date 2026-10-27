package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.controller.AdminCustomerController;
import com.lordgainsborough.restaurant.controller.ReviewController;
import com.lordgainsborough.restaurant.controller.ReservationController;
import com.lordgainsborough.restaurant.controller.KdsController;
import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;

/** Structural guards shared by opt-in persistence modules; flags never certify identities. */
final class PostgresIdentityCoherence {
    private PostgresIdentityCoherence() {}
    static void preflight(ConfigurableListableBeanFactory factory, Class<?>... consumers) {
        for (Class<?> consumer : consumers) {
            if (factory.getBeanNamesForType(consumer, true, false).length > 0
                    && factory.getBeanNamesForType(PostgresUserAuthStore.class, true, false).length == 0)
                throw incompatible("actual PostgreSQL identities required by " + consumer.getSimpleName());
        }
    }
    static void requireIfPresent(ApplicationContext context, EntityManagerFactory emf) {
        if (!context.getBeansOfType(AuthController.class).isEmpty()
                || !context.getBeansOfType(AdminAuthInterceptor.class).isEmpty()) require(context, emf);
    }
    static PostgresUserAuthStore require(ApplicationContext context, EntityManagerFactory emf) {
        if (!(context.getBean(UserAuthStore.class) instanceof PostgresUserAuthStore identity)
                || identity.getEntityManagerFactory() != emf) throw incompatible("selected identity persistence unit");
        for (AuthController controller : context.getBeansOfType(AuthController.class).values())
            if (controller.getUserAuthStore() != identity) throw incompatible("injected AuthController identity store");
        for (AdminAuthInterceptor interceptor : context.getBeansOfType(AdminAuthInterceptor.class).values())
            if (interceptor.getIdentityStore() != identity) throw incompatible("injected administrator identity store");
        for (OrderAccessService access : context.getBeansOfType(OrderAccessService.class).values())
            if (access.getIdentityStore() != identity) throw incompatible("injected order JWT identity store");
        return identity;
    }
    static void requireAdministratorHttp(ApplicationContext context, EntityManagerFactory emf) {
        if (context.getBeansOfType(AdminCustomerController.class).isEmpty()
                && context.getBeansOfType(ReviewController.class).isEmpty()
                && context.getBeansOfType(ReservationController.class).isEmpty()
                && context.getBeansOfType(KdsController.class).isEmpty()) return;
        var identity = require(context, emf);
        var configurations = context.getBeansOfType(WebConfig.class);
        if (configurations.isEmpty()) throw incompatible("administrator HTTP interceptor registration missing");
        for (WebConfig configuration : configurations.values()) {
            var interceptor = configuration.getAdminAuthInterceptor();
            if (interceptor.getIdentityStore() != identity
                    || !context.getBeansOfType(AdminAuthInterceptor.class).containsValue(interceptor))
                throw incompatible("registered admin search HTTP interceptor");
        }
    }
    static IllegalStateException incompatible(String detail) {
        return new IllegalStateException("Unsupported mixed-backend identity activation: " + detail);
    }
}
