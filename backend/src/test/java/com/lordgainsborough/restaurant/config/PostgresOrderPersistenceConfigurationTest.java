package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.repository.*;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresOrderPersistenceConfigurationTest {
    private ApplicationContextRunner base() {
        return new ApplicationContextRunner().withUserConfiguration(PostgresOrderPersistenceConfiguration.class)
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withBean(JpaCustomerOrderRepository.class, () -> mock(JpaCustomerOrderRepository.class))
                .withBean(JpaMenuItemRepository.class, () -> mock(JpaMenuItemRepository.class))
                .withBean(JpaOrderReviewRepository.class, () -> mock(JpaOrderReviewRepository.class))
                .withBean(JpaStoreSettingsRepository.class, () -> mock(JpaStoreSettingsRepository.class))
                .withBean(JpaUserRepository.class, () -> mock(JpaUserRepository.class));
    }
    private ApplicationContextRunner enabled() {
        return base().withPropertyValues("spring.profiles.active=test", "app.orders.postgres.enabled=true");
    }

    @Test void disabledByDefaultWithoutRelationalInfrastructure() {
        new ApplicationContextRunner().withUserConfiguration(PostgresOrderPersistenceConfiguration.class)
                .withBean(CustomerOrderRepository.class, () -> mock(CustomerOrderRepository.class))
                .withBean(MenuItemRepository.class, () -> mock(MenuItemRepository.class))
                .withBean(OrderReviewRepository.class, () -> mock(OrderReviewRepository.class))
                .withBean(StoreSettingsService.class, () -> mock(StoreSettingsService.class))
                .withBean(CustomerNoShowService.class, () -> mock(CustomerNoShowService.class))
                .withUserConfiguration(MongoCustomerOrderStore.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(CustomerOrderStore.class);
                    assertThat(ctx.getBean(CustomerOrderStore.class)).isInstanceOf(MongoCustomerOrderStore.class);
                    assertThat(ctx).doesNotHaveBean(PostgresCustomerOrderStore.class);
                    assertThat(ctx).doesNotHaveBean(EntityManagerFactory.class);
                });
    }
    @Test void profileAloneDoesNotActivate() {
        base().withPropertyValues("spring.profiles.active=postgres-migration")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresCustomerOrderStore.class));
    }
    @Test void flagWithoutExplicitProfileDoesNotActivate() {
        base().withPropertyValues("app.orders.postgres.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresCustomerOrderStore.class));
    }
    @Test void explicitOptInResolvesPrimaryWithoutAmbiguity() {
        enabled().withBean("mongoCustomerOrderStore", CustomerOrderStore.class, () -> mock(MongoCustomerOrderStore.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(CustomerOrderStore.class)).isInstanceOf(PostgresCustomerOrderStore.class);
                    assertThat(ctx.getBean("orderJpaTransactionManager")).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
                    assertThat(ctx.getBean("transactionManager")).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
                });
    }
    @ParameterizedTest
    @ValueSource(classes={KdsController.class,ReviewController.class,SocketIoConfig.class,AuthController.class,
            CustomerNoShowService.class,CustomerOrderPresentationService.class})
    @SuppressWarnings({"rawtypes","unchecked"})
    void legacyConsumersRejectActivationEvenWithPretendCoordinationFlags(Class legacyType) {
        enabled().withPropertyValues("app.orders.postgres.allow-isolated-test=true", "app.orders.postgres-kds.enabled=true")
                .withBean(legacyType, () -> mock(legacyType))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("Unsupported mixed-backend order activation");
                });
    }
}
