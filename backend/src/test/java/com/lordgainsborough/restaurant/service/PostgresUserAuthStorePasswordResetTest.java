package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaOrderReviewRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresUserAuthStorePasswordResetTest {

    private final JpaUserRepository users = mock(JpaUserRepository.class);
    private final JpaCustomerOrderRepository orders = mock(JpaCustomerOrderRepository.class);
    private final JpaOrderReviewRepository reviews = mock(JpaOrderReviewRepository.class);
    private final JpaTransactionManager manager = mock(JpaTransactionManager.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final PostgresPasswordResetOtpChallengeStore challengeStore =
            mock(PostgresPasswordResetOtpChallengeStore.class);
    private final PasswordResetOtpChallengePersistence persistence =
            mock(PasswordResetOtpChallengePersistence.class);
    private final PasswordResetService service = mock(PasswordResetService.class);

    private PostgresUserAuthStore authStore;

    @BeforeEach
    void setUp() {
        when(manager.getDataSource()).thenReturn(dataSource);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(challengeStore.getTransactionManager()).thenReturn(manager);
        when(challengeStore.getDataSource()).thenReturn(dataSource);
        when(persistence.getStore()).thenReturn(challengeStore);
        when(service.getPersistence()).thenReturn(persistence);

        authStore = new PostgresUserAuthStore(
                users, orders, reviews, manager, Clock.systemUTC());
    }

    @Test
    void requestPasswordReset_withPhoneNumber_queriesByPhoneNormalizedNotEmail() {
        JpaUser user = new JpaUser();
        user.setId("u-phone-1");
        user.setEmail("realuser@example.com");
        user.setPhone("+1 (416)-555-1234");
        user.setPhoneNormalized("4165551234");

        when(users.findByPhoneNormalized("4165551234")).thenReturn(List.of(user));

        authStore.requestPasswordReset(service, "4165551234", "192.0.2.1", "key-1");

        // Verify phone lookup was performed
        verify(users).findByPhoneNormalized("4165551234");
        // Verify email lookup was NOT performed on a phone number
        verify(users, never()).findByEmailIgnoreCase(anyString());
        // Verify real user found by phone is forwarded to the challenge service
        verify(service).requestInitialForJpaUser(eq("4165551234"), eq(Optional.of(user)), eq("192.0.2.1"), eq("key-1"));
    }

    @Test
    void requestPasswordReset_withFormattedPhone_normalizesAndQueriesPhone() {
        JpaUser user = new JpaUser();
        user.setId("u-phone-2");
        user.setEmail("formatted@example.com");
        user.setPhone("+1 (416)-555-4321");
        user.setPhoneNormalized("4165554321");

        when(users.findByPhoneNormalized("4165554321")).thenReturn(List.of(user));

        authStore.requestPasswordReset(service, "+1 (416) 555-4321", "192.0.2.1", "key-2");

        verify(users).findByPhoneNormalized("4165554321");
        verify(users, never()).findByEmailIgnoreCase(anyString());
        verify(service).requestInitialForJpaUser(eq("+1 (416) 555-4321"), eq(Optional.of(user)), eq("192.0.2.1"), eq("key-2"));
    }

    @Test
    void requestPasswordReset_withEmail_queriesByEmailIgnoreCase() {
        JpaUser user = new JpaUser();
        user.setId("u-email-1");
        user.setEmail("alice@example.com");

        when(users.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));

        authStore.requestPasswordReset(service, "alice@example.com", "192.0.2.1", "key-3");

        verify(users).findByEmailIgnoreCase("alice@example.com");
        verify(users, never()).findByPhoneNormalized(anyString());
        verify(service).requestInitialForJpaUser(eq("alice@example.com"), eq(Optional.of(user)), eq("192.0.2.1"), eq("key-3"));
    }

    @Test
    void requestPasswordReset_withUnknownPhone_passesEmptyForShadowChallenge() {
        when(users.findByPhoneNormalized("4165559999")).thenReturn(List.of());

        authStore.requestPasswordReset(service, "4165559999", "192.0.2.1", "key-4");

        verify(users).findByPhoneNormalized("4165559999");
        verify(users, never()).findByEmailIgnoreCase(anyString());
        verify(service).requestInitialForJpaUser(eq("4165559999"), eq(Optional.empty()), eq("192.0.2.1"), eq("key-4"));
    }
}
