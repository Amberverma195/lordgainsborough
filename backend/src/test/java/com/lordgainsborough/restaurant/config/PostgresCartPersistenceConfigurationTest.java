package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.controller.CartController;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCartRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import com.lordgainsborough.restaurant.repository.CartRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Backend selection and fail-closed authentication activation. */
class PostgresCartPersistenceConfigurationTest {
    private ApplicationContextRunner base() {
        return new ApplicationContextRunner().withPropertyValues("jwt.expiration=60000", "jwt.secret=pg018-isolated-jwt-fixture-secret-at-least-32-bytes").withUserConfiguration(PostgresCartPersistenceConfiguration.class)
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withBean(JpaCartRepository.class, () -> mock(JpaCartRepository.class))
                .withBean(JpaUserRepository.class, () -> mock(JpaUserRepository.class))
                .withBean(Clock.class, Clock::systemUTC).withBean(JwtUtil.class, () -> mock(JwtUtil.class));
    }
    @Test void defaultRuntimeSelectsMongoWithoutPostgresInfrastructure() {
        new ApplicationContextRunner().withPropertyValues("jwt.expiration=60000", "jwt.secret=pg018-isolated-jwt-fixture-secret-at-least-32-bytes").withUserConfiguration(PostgresCartPersistenceConfiguration.class,MongoCartStore.class,CartController.class)
                .withBean(CartRepository.class, () -> mock(CartRepository.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(Clock.class, Clock::systemUTC).withBean(JwtUtil.class, () -> mock(JwtUtil.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(CartStore.class).hasSingleBean(CartController.class);
                    assertThat(ctx.getBean(CartStore.class)).isInstanceOf(MongoCartStore.class);
                    assertThat(ctx).doesNotHaveBean(PostgresCartStore.class).doesNotHaveBean(EntityManagerFactory.class);
                });
    }
    @Test void explicitProfileAndFlagSelectPrimaryPostgresAlongsideMongo() {
        base().withPropertyValues("spring.profiles.active=test","app.cart.postgres.enabled=true")
                .withBean("mongoCartStore",CartStore.class, () -> mock(MongoCartStore.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(CartStore.class)).isInstanceOf(PostgresCartStore.class);
                    assertThat(ctx.getBean("cartJpaTransactionManager")).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
                });
    }
    @Test void profileAloneOrFlagAloneCannotActivatePostgres() {
        base().withPropertyValues("spring.profiles.active=postgres-migration")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresCartStore.class));
        base().withPropertyValues("app.cart.postgres.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresCartStore.class));
    }
    @Test void legacyAuthenticationRejectedEvenWithPretendCoordinationFlag() {
        base().withPropertyValues("spring.profiles.active=test","app.cart.postgres.enabled=true",
                        "app.security.postgres-user-auth.enabled=true","app.cart.postgres.allow-isolated-test=true")
                .withBean(AuthController.class, () -> mock(AuthController.class))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("Unsupported mixed-backend cart activation");
                });
    }
}
