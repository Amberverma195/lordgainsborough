
package com.lordgainsborough.restaurant.config;
import com.lordgainsborough.restaurant.controller.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaReservationRepository;
import com.lordgainsborough.restaurant.repository.ReservationRepository;
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
class PostgresReservationPersistenceConfigurationTest {
    private ApplicationContextRunner base() {
        return new ApplicationContextRunner().withUserConfiguration(PostgresReservationPersistenceConfiguration.class,ReservationController.class)
            .withPropertyValues("spring.profiles.active=test","app.reservations.postgres.enabled=true")
            .withBean(EntityManagerFactory.class,()->mock(EntityManagerFactory.class)).withBean(JpaReservationRepository.class,()->mock(JpaReservationRepository.class)).withBean(Clock.class,Clock::systemUTC);
    }
    @Test void explicitPrimarySelectionUsesQualifiedManager() {
        base().withBean("mongo",ReservationStore.class,()->mock(MongoReservationStore.class)).run(ctx->{
            assertThat(ctx).hasNotFailed();assertThat(ctx.getBean(ReservationController.class).getReservationStore()).isInstanceOf(PostgresReservationStore.class);
            assertThat(ctx.getBean("reservationJpaTransactionManager")).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
        });
    }
    @Test void defaultMongoHasNoPostgresDependency() {
        new ApplicationContextRunner().withUserConfiguration(PostgresReservationPersistenceConfiguration.class,ReservationController.class,MongoReservationStore.class)
            .withBean(ReservationRepository.class,()->mock(ReservationRepository.class)).run(ctx->{assertThat(ctx).hasNotFailed().doesNotHaveBean(EntityManagerFactory.class);assertThat(ctx.getBean(ReservationController.class).getReservationStore()).isInstanceOf(MongoReservationStore.class);});
    }
    @Test void profileAloneDoesNotEnable() {
        base().withPropertyValues("app.reservations.postgres.enabled=false").withBean(ReservationStore.class,()->mock(MongoReservationStore.class))
            .run(ctx->assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresReservationStore.class));
    }
    @Test void flagAloneDoesNotEnable() {
        base().withPropertyValues("spring.profiles.active=default").withBean(ReservationStore.class,()->mock(MongoReservationStore.class))
            .run(ctx->assertThat(ctx).hasNotFailed().doesNotHaveBean(PostgresReservationStore.class));
    }
    @ParameterizedTest @ValueSource(classes={AuthController.class,AdminAuthInterceptor.class})
    @SuppressWarnings({"rawtypes","unchecked"}) void actualMongoIdentityComponentsBlockProduction(Class type) {
        base().withPropertyValues("app.reservations.migration-complete=true").withBean(type,()->mock(type)).run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void injectedMongoCannotHideBehindEnabledFlag() {
        base().withBean("alien",ReservationStore.class,()->mock(MongoReservationStore.class),def->def.setPrimary(true))
            .run(ctx->assertThat(ctx).hasFailed());
    }
}
