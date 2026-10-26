package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.repository.*;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresKdsPersistenceConfigurationTest {
    private ApplicationContextRunner base() {
        return new ApplicationContextRunner().withUserConfiguration(PostgresOrderPersistenceConfiguration.class,
                        PostgresKdsPersistenceConfiguration.class,KdsController.class,CustomerNoShowService.class,
                        CustomerOrderPresentationService.class,KdsAccessService.class)
                .withPropertyValues("spring.profiles.active=test","app.orders.postgres.enabled=true","app.kds.postgres.enabled=true")
                .withBean(EntityManagerFactory.class,() -> mock(EntityManagerFactory.class))
                .withBean(JpaCustomerOrderRepository.class,() -> mock(JpaCustomerOrderRepository.class))
                .withBean(JpaUserRepository.class,() -> mock(JpaUserRepository.class))
                .withBean(JpaStoreSettingsRepository.class,() -> mock(JpaStoreSettingsRepository.class))
                .withBean(JpaMenuItemRepository.class,() -> mock(JpaMenuItemRepository.class))
                .withBean(JpaOrderReviewRepository.class,() -> mock(JpaOrderReviewRepository.class))
                .withBean(Clock.class,Clock::systemUTC)
                .withBean(ClientIpResolver.class,() -> new ClientIpResolver(new SignupAbuseProperties()))
                .withBean(OrderRealtimeService.class,() -> mock(OrderRealtimeService.class));
    }
    @Test void isolatedCheckoutKitchenNoShowPresentationAndSecurityUseActualPostgresImplementations() {
        base().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(KdsController.class).getOrderStore()).isInstanceOf(PostgresKdsOrderStore.class);
            assertThat(ctx.getBean(CustomerNoShowService.class).getStore()).isInstanceOf(PostgresCustomerNoShowStore.class);
            assertThat(ctx.getBean(CustomerOrderPresentationService.class).getCountStore()).isInstanceOf(PostgresCustomerOrderCountStore.class);
            assertThat(ctx.getBean(KdsAccessService.class).getSettingsSource()).isInstanceOf(PostgresKdsSettingsSource.class);
            var kds=ctx.getBean(PostgresKdsOrderStore.class);
            assertThat(kds.getEntityManagerFactory()).isSameAs(ctx.getBean(PostgresCustomerOrderStore.class).getEntityManagerFactory());
        });
    }
    @Test void defaultMongoApplicationHasNoRelationalDependency() {
        new ApplicationContextRunner().withUserConfiguration(KdsController.class,MongoKdsOrderStore.class,
                        CustomerNoShowService.class,MongoCustomerNoShowStore.class,CustomerOrderPresentationService.class,
                        MongoCustomerOrderCountStore.class,KdsAccessService.class,MongoKdsSettingsSource.class,
                        PostgresKdsPersistenceConfiguration.class,PostgresOrderPersistenceConfiguration.class)
                .withBean(CustomerOrderRepository.class,() -> mock(CustomerOrderRepository.class))
                .withBean(UserRepository.class,() -> mock(UserRepository.class))
                .withBean(StoreSettingsService.class,() -> mock(StoreSettingsService.class))
                .withBean(Clock.class,Clock::systemUTC)
                .withBean(ClientIpResolver.class,() -> new ClientIpResolver(new SignupAbuseProperties()))
                .withBean(OrderRealtimeService.class,() -> mock(OrderRealtimeService.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().doesNotHaveBean(EntityManagerFactory.class);
                    assertThat(ctx.getBean(KdsController.class).getOrderStore()).isInstanceOf(MongoKdsOrderStore.class);
                    assertThat(ctx.getBean(CustomerNoShowService.class).getStore()).isInstanceOf(MongoCustomerNoShowStore.class);
                    assertThat(ctx.getBean(KdsAccessService.class).getSettingsSource()).isInstanceOf(MongoKdsSettingsSource.class);
                });
    }
    @Test void kdsCannotActivateAgainstMongoOrAbsentCheckout() {
        base().withPropertyValues("app.orders.postgres.enabled=false")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @ParameterizedTest @ValueSource(classes={ReviewController.class,AuthController.class,SocketIoConfig.class})
    @SuppressWarnings({"rawtypes","unchecked"})
    void actualRemainingLegacyConsumersStillBlockProduction(Class type) {
        base().withPropertyValues("app.security.postgres-user-auth.enabled=true","app.orders.postgres.allow-isolated-test=true")
                .withBean(type,() -> mock(type)).run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("Unsupported mixed-backend");
                });
    }
    @Test void actualMongoNoShowFacadeCannotHideBehindPostgresFlags() {
        base().withBean("legacyNoShows",CustomerNoShowService.class,
                () -> new CustomerNoShowService(mock(UserRepository.class),mock(CustomerOrderRepository.class)))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void actualPresentationFromAnotherPersistenceUnitIsRejected() {
        base().withBean("alienPresentation",CustomerOrderPresentationService.class,
                () -> new CustomerOrderPresentationService(new PostgresCustomerOrderCountStore(mock(JpaCustomerOrderRepository.class),mock(EntityManagerFactory.class))),
                definition -> definition.setPrimary(true)).run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("Unsupported mixed-backend order activation");
                });
    }
    @Test void actualMongoIpSettingsCannotHideBehindPostgresFlags() {
        base().withBean("legacyAccess",KdsAccessService.class,
                () -> new KdsAccessService(mock(StoreSettingsService.class),new ClientIpResolver(new SignupAbuseProperties())))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
