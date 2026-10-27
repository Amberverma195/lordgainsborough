package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.*;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
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
import java.time.Clock;

/** Coordinated identities and PG-023 realtime remain opt-in pending final migration verification. */
@Configuration(proxyBeanMethods = false)
@Profile({"postgres-migration", "test"})
@ConditionalOnProperty(name = "app.security.postgres-user-auth.enabled", havingValue = "true")
public class PostgresUserAuthPersistenceConfiguration {
    @Bean @Primary
    PostgresUserAuthStore postgresUserAuthStore(JpaUserRepository users, JpaCustomerOrderRepository orders,
            JpaOrderReviewRepository reviews, @Qualifier("transactionManager") org.springframework.transaction.PlatformTransactionManager transactionManager, Clock clock) {
        if (!(transactionManager instanceof JpaTransactionManager manager))
            throw PostgresIdentityCoherence.incompatible("authentication requires the actual JPA security transaction manager");
        return new PostgresUserAuthStore(users, orders, reviews, manager, clock);
    }
    @Bean static BeanFactoryPostProcessor rejectUnmigratedIdentityConsumers() {
        return factory -> {
            PostgresIdentityCoherence.preflightSocket(factory);
            if (factory.getBeanNamesForType(PostgresCustomerOrderStore.class, true, false).length == 0)
                throw PostgresIdentityCoherence.incompatible("PostgreSQL auth requires actual PostgreSQL order history");
        };
    }
    @Bean SmartInitializingSingleton validatePostgresAuthentication(ApplicationContext context, JpaContext jpaContext) {
        return () -> {
            PostgresUserAuthStore identity = context.getBean(PostgresUserAuthStore.class);
            EntityManagerFactory emf = identity.getEntityManagerFactory();
            if (PostgresIdentityCoherence.require(context, emf) != identity) fail("selected identity store");
            PostgresIdentityCoherence.requireSocket(context, emf);
            var manager = identity.getTransactionManager();
            var ds = manager.getDataSource();
            if (context.getBean("transactionManager") != manager || ds == null) fail("security transaction manager");
            for (Class<?> type : new Class<?>[]{JpaUser.class, JpaCustomerOrder.class, JpaOrderReview.class})
                if (jpaContext.getEntityManagerByManagedType(type).getEntityManagerFactory() != emf) fail("repository persistence units");
            for (EntityManagerFactory candidate : context.getBeansOfType(EntityManagerFactory.class).values())
                if (candidate != emf) fail("ambiguous persistence units");
            if (!(context.getBean(CustomerOrderStore.class) instanceof PostgresCustomerOrderStore checkout)
                    || checkout.getEntityManagerFactory() != emf || checkout.getUserRepository() != identity.getUserRepository()
                    || checkout.getOrderRepository() != identity.getOrderRepository()) fail("checkout/history/user dataset");
            if (!(context.getBean(SignupOtpChallengeStore.class) instanceof PostgresSignupOtpChallengeStore signup)
                    || signup.getTransactionManager() != manager || signup.getDataSource() != ds) fail("signup OTP transaction/dataset");
            if (!(context.getBean(PasswordResetOtpChallengeStore.class) instanceof PostgresPasswordResetOtpChallengeStore reset)
                    || reset.getTransactionManager() != manager || reset.getDataSource() != ds) fail("password reset transaction/dataset");
            if (!(context.getBean(AbuseCounterStore.class) instanceof PostgresAbuseCounterStore abuse)
                    || abuse.getDataSource() != ds) fail("abuse counter dataset");
            if (!(context.getBean(LoginFailureStateStore.class) instanceof PostgresLoginFailureStateStore failures)
                    || failures.getTransactionManager() != manager || failures.getDataSource() != ds) fail("login cooldown transaction/dataset");
            for (SignupOtpChallengeService service : context.getBeansOfType(SignupOtpChallengeService.class).values())
                if (service.getPersistence().getStore() != context.getBean(SignupOtpChallengeStore.class)) fail("injected signup OTP store");
            for (LoginRateLimiter limiter : context.getBeansOfType(LoginRateLimiter.class).values())
                if (limiter.getCounterStore() != context.getBean(AbuseCounterStore.class)
                        || limiter.getFailureStore() != context.getBean(LoginFailureStateStore.class)) fail("injected login limiter stores");
            for (SignupAbuseLimiter limiter : context.getBeansOfType(SignupAbuseLimiter.class).values())
                if (limiter.getStore() != context.getBean(AbuseCounterStore.class)) fail("injected signup abuse store");
            for (PasswordResetService service : context.getBeansOfType(PasswordResetService.class).values())
                if (service.getPersistence().getStore() != context.getBean(PasswordResetOtpChallengeStore.class)
                        || service.getAbuseLimiter().getStore() != context.getBean(AbuseCounterStore.class)) fail("injected password reset stores");
            for (AuthController controller : context.getBeansOfType(AuthController.class).values()) {
                if (controller.getSignupOtpChallengeService() != context.getBean(SignupOtpChallengeService.class)
                        || controller.getSignupAbuseLimiter() != context.getBean(SignupAbuseLimiter.class)
                        || controller.getLoginRateLimiter() != context.getBean(LoginRateLimiter.class)
                        || controller.getPasswordResetService() != context.getBean(PasswordResetService.class)) fail("injected authentication services");
            }
            for (OrderController controller : context.getBeansOfType(OrderController.class).values())
                if (controller.getOrderStore() != context.getBean(CustomerOrderStore.class)
                        || controller.getOrderAccessService().getIdentityStore() != identity) fail("injected order controller/access dataset");
            for (CartController controller : context.getBeansOfType(CartController.class).values()) {
                if (!(controller.getCartStore() instanceof PostgresCartStore cart)
                        || cart.getEntityManagerFactory() != emf || cart.getUserRepository() != identity.getUserRepository()) fail("cart identities");
            }
            for (AdminCustomerSearchService service : context.getBeansOfType(AdminCustomerSearchService.class).values())
                if (!(service.getStore() instanceof PostgresAdminCustomerSearchStore search)
                        || search.getEntityManagerFactory() != emf || search.getUserRepository() != identity.getUserRepository()
                        || search.getOrderRepository() != identity.getOrderRepository()) fail("admin search identity dataset");
            for (ReviewController controller : context.getBeansOfType(ReviewController.class).values())
                if (!(controller.getReviewStore() instanceof PostgresReviewStore review) || review.getEntityManagerFactory() != emf
                        || review.getOrderAccessService().getIdentityStore() != identity) fail("review dataset");
            for (ReservationController controller : context.getBeansOfType(ReservationController.class).values())
                if (!(controller.getReservationStore() instanceof PostgresReservationStore reservation)
                        || reservation.getEntityManagerFactory() != emf) fail("reservation dataset");
            PostgresIdentityCoherence.requireAdministratorHttp(context, emf);
        };
    }
    private static void fail(String detail) { throw PostgresIdentityCoherence.incompatible(detail); }
}
