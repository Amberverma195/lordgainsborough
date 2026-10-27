
package com.lordgainsborough.restaurant.config;
import com.lordgainsborough.restaurant.controller.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.repository.*;
import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PostgresReviewPersistenceConfigurationTest {
    private ApplicationContextRunner base() {
        return new ApplicationContextRunner().withUserConfiguration(PostgresOrderPersistenceConfiguration.class,PostgresReviewPersistenceConfiguration.class,ReviewController.class)
            .withPropertyValues("spring.profiles.active=test","app.orders.postgres.enabled=true","app.reviews.postgres.enabled=true")
            .withBean(EntityManagerFactory.class,()->mock(EntityManagerFactory.class))
            .withBean(JpaCustomerOrderRepository.class,()->mock(JpaCustomerOrderRepository.class))
            .withBean(JpaOrderReviewRepository.class,()->mock(JpaOrderReviewRepository.class))
            .withBean(JpaMenuItemRepository.class,()->mock(JpaMenuItemRepository.class))
            .withBean(JpaStoreSettingsRepository.class,()->mock(JpaStoreSettingsRepository.class))
            .withBean(JpaUserRepository.class,()->mock(JpaUserRepository.class))
            .withBean(OrderAccessService.class,()->mock(OrderAccessService.class))
            .withBean(OrderRealtimeService.class,()->mock(OrderRealtimeService.class))
            .withBean(Clock.class,Clock::systemUTC);
    }
    @Test void actualInjectedReviewAndCheckoutSharePersistenceUnitAndMongoRemainsUnselected() {
        base().withBean("mongoReviewStore",ReviewStore.class,()->mock(MongoReviewStore.class)).run(ctx->{
            assertThat(ctx).hasNotFailed();var review=ctx.getBean(ReviewController.class).getReviewStore();assertThat(review).isInstanceOf(PostgresReviewStore.class);
            assertThat(((PostgresReviewStore)review).getOrderStore()).isSameAs(ctx.getBean(CustomerOrderStore.class));
            assertThat(((PostgresReviewStore)review).getEntityManagerFactory()).isSameAs(ctx.getBean(EntityManagerFactory.class));
        });
    }
    @Test void defaultMongoGraphNeedsNoRelationalInfrastructure() {
        new ApplicationContextRunner().withUserConfiguration(PostgresReviewPersistenceConfiguration.class,ReviewController.class,MongoReviewStore.class)
            .withBean(CustomerOrderRepository.class,()->mock(CustomerOrderRepository.class)).withBean(OrderReviewRepository.class,()->mock(OrderReviewRepository.class))
            .withBean(OrderAccessService.class,()->mock(OrderAccessService.class)).withBean(OrderRealtimeService.class,()->mock(OrderRealtimeService.class))
            .run(ctx->{assertThat(ctx).hasNotFailed().doesNotHaveBean(EntityManagerFactory.class);assertThat(ctx.getBean(ReviewController.class).getReviewStore()).isInstanceOf(MongoReviewStore.class);});
    }
    @Test void profileWithoutFlagLeavesDefaultMongoSelected() {
        base().withPropertyValues("app.reviews.postgres.enabled=false","app.orders.postgres.enabled=false")
            .withBean(ReviewStore.class,()->mock(MongoReviewStore.class)).run(ctx->{assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresReviewStore.class);});
    }
    @Test void flagWithoutProfileCannotSelectPostgres() {
        base().withPropertyValues("spring.profiles.active=default").withBean(ReviewStore.class,()->mock(MongoReviewStore.class))
            .run(ctx->assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresReviewStore.class));
    }
    @Test void reviewCannotActivateWithoutPostgresCheckout() {
        base().withPropertyValues("app.orders.postgres.enabled=false").run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void mongoReviewCannotBeInjectedIntoPostgresCheckout() {
        base().withPropertyValues("app.reviews.postgres.enabled=false").withBean(ReviewStore.class,()->mock(MongoReviewStore.class)).run(ctx->{
            assertThat(ctx).hasFailed();assertThat(ctx.getStartupFailure()).hasMessageContaining("Unsupported mixed-backend");
        });
    }
    @ParameterizedTest @ValueSource(classes={AuthController.class,SocketIoConfig.class,AdminAuthInterceptor.class})
    @SuppressWarnings({"unchecked","rawtypes"}) void actualUnmigratedIdentityAndSocketConsumersStillRejectPretendFlags(Class type) {
        base().withPropertyValues("app.security.postgres-user-auth.enabled=true","app.orders.postgres.allow-isolated-test=true")
            .withBean(type,()->mock(type)).run(ctx->{assertThat(ctx).hasFailed();assertThat(ctx.getStartupFailure()).hasMessageContaining("Unsupported mixed-backend");});
    }
    @Test void reviewCannotLinkAnAlienPersistenceUnit() {
        var checkout=mock(PostgresCustomerOrderStore.class);when(checkout.getEntityManagerFactory()).thenReturn(mock(EntityManagerFactory.class));
        assertThatThrownBy(()->new PostgresReviewStore(mock(JpaCustomerOrderRepository.class),mock(JpaOrderReviewRepository.class),checkout,mock(OrderAccessService.class),new org.springframework.orm.jpa.JpaTransactionManager(mock(EntityManagerFactory.class)),Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }
}
