package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.config.PostgresPasswordResetPersistenceConfiguration;
import com.lordgainsborough.restaurant.model.PasswordResetDeliveryTask;
import com.lordgainsborough.restaurant.security.PasswordResetQueuePayloadEncryptor;
import com.lordgainsborough.restaurant.support.LocalPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordResetQueueRecoveryIntegrationTest {
    LocalPostgres database;
    PGSimpleDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    PasswordResetProperties properties;
    PasswordResetOtpChallengePersistence persistence;
    EmailSenderService sender;
    AtomicReference<Instant> time;
    final Instant start = Instant.parse("2026-11-21T12:00:00Z");

    @BeforeAll void database() {
        database = new LocalPostgres();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(database.jdbcUrl());
        dataSource.setUser("postgres");
        dataSource.setPassword("postgres");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
    }

    @AfterAll void close() { if (database != null) database.close(); }

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE password_reset_email_queue, password_reset_otp_challenges");
        properties = new PasswordResetProperties();
        properties.setQueueEncryptionKey("queue-recovery-integration-test-shared-secret");
        persistence = mock(PasswordResetOtpChallengePersistence.class);
        time = new AtomicReference<>(start);
        when(persistence.now()).thenAnswer(invocation -> time.get());
        sender = mock(EmailSenderService.class);
    }

    PostgresPasswordResetEmailDeliveryQueue queue() {
        return queue(persistence);
    }

    PostgresPasswordResetEmailDeliveryQueue queue(PasswordResetOtpChallengePersistence challengePersistence) {
        return new PostgresPasswordResetEmailDeliveryQueue(dataSource, transactions, sender,
                challengePersistence, properties, null, ignored -> {}, new PasswordResetQueuePayloadEncryptor(properties));
    }

    void pending(String id) {
        jdbc.update("""
            INSERT INTO password_reset_otp_challenges
                (email_key, challenge_id, purpose, status, code_expires_at, challenge_expires_at, send_operation_id, send_status)
            VALUES (?, ?, 'PASSWORD_RESET', 'ACTIVE', ?, ?, ?, 'RESERVED')
            """, id, id, Timestamp.from(start.plusSeconds(600)), Timestamp.from(start.plusSeconds(1800)), id);
        // Simulate termination after persistence but before the executor gets to run.
        queue().enqueue(new PasswordResetDeliveryTask(id, id, id, "queue-test@example.com",
                "123456", id, Duration.ofMinutes(10), false, start));
    }

    String status(String id) {
        return jdbc.queryForObject("SELECT status FROM password_reset_email_queue WHERE id = ?", String.class, id);
    }

    @Test void newInstanceRecoversCommittedPendingAndExpiredLeases() {
        pending("pending");
        pending("crashed");
        jdbc.update("UPDATE password_reset_email_queue SET status='PROCESSING', attempts=1, lease_until=? WHERE id='crashed'",
                Timestamp.from(start.minusSeconds(1)));
        assertThat(queue().processPending()).isEqualTo(2);
        assertThat(status("pending")).isEqualTo("SENT");
        assertThat(status("crashed")).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_email_queue WHERE encrypted_code IS NOT NULL", Integer.class)).isZero();
        verify(sender, times(2)).sendPasswordResetCodeEmail(anyString(), eq("123456"), anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test void productionWiringRejectsEphemeralEncryptionKeys() {
        try (var ignored = mockConstruction(PasswordResetQueuePayloadEncryptor.class,
                (encryptor, context) -> when(encryptor.isEphemeralKey()).thenReturn(true))) {
            assertThatThrownBy(() -> new PostgresPasswordResetPersistenceConfiguration()
                    .passwordResetQueuePayloadEncryptor(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PASSWORD_RESET_QUEUE_ENCRYPTION_KEY");
        }
    }

    @Test void retriesUseBackoffStablePayloadAndStopAtLimit() {
        pending("retry");
        properties.setQueueMaxRetryBackoff(Duration.ofSeconds(30));
        doThrow(new AmbiguousEmailDeliveryException("timeout")).when(sender)
                .sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
        var worker = queue();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(worker.processPending()).isEqualTo(1);
            if (attempt < 5) {
                Duration delay = Duration.ofSeconds(Math.min(30, 10L << (attempt - 1)));
                assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM password_reset_email_queue", Timestamp.class).toInstant())
                        .isEqualTo(time.get().plus(delay));
                assertThat(worker.processPending()).isZero();
                time.set(time.get().plus(delay));
            }
        }
        assertThat(status("retry")).isEqualTo("FAILED");
        assertThat(worker.processPending()).isZero();
        assertThat(jdbc.queryForObject("SELECT encrypted_code FROM password_reset_email_queue", String.class)).isNull();
        verify(sender, times(5)).sendPasswordResetCodeEmail("queue-test@example.com", "123456", "retry", Duration.ofMinutes(10));
        verify(persistence, never()).expireAfterDefiniteInitialFailure(anyString(), anyString(), any());
    }

    @Test void definiteRejectionFailsOnceAndClearsPayload() {
        pending("rejected");
        doThrow(new DefiniteEmailRejectionException("invalid recipient")).when(sender)
                .sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
        assertThat(queue().processPending()).isEqualTo(1);
        assertThat(status("rejected")).isEqualTo("FAILED");
        assertThat(queue().processPending()).isZero();
        assertThat(jdbc.queryForObject("SELECT encrypted_code FROM password_reset_email_queue", String.class)).isNull();
        verify(persistence).expireAfterDefiniteInitialFailure(eq("rejected"), eq("rejected"), any());
        verify(sender).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
    }

    @Test void databaseFailureAfterAcceptanceLeavesTaskRecoverable() {
        pending("accepted");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(persistence).markSendOutcome(anyString(), anyString(), any(), any());
        assertThatThrownBy(() -> queue().processPending())
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(status("accepted")).isEqualTo("PROCESSING");
        verify(persistence, never()).expireAfterDefiniteInitialFailure(anyString(), anyString(), any());
        doReturn(java.util.Optional.empty()).when(persistence).markSendOutcome(anyString(), anyString(), any(), any());
        time.set(start.plusSeconds(61));
        assertThat(queue().processPending()).isEqualTo(1);
        assertThat(status("accepted")).isEqualTo("SENT");
        verify(sender, times(2)).sendPasswordResetCodeEmail("queue-test@example.com", "123456", "accepted", Duration.ofMinutes(10));
    }

    @Test void successfulRetryUpdatesRealChallengeFromUnknownToSent() {
        pending("uncertain");
        var hashProperties = new com.lordgainsborough.restaurant.config.SignupAbuseProperties();
        hashProperties.setIpHashSecret("queue-recovery-test-hashing-secret");
        var clock = mock(java.time.Clock.class);
        when(clock.instant()).thenAnswer(call -> time.get());
        var realPersistence = new PasswordResetOtpChallengePersistence(
                new PostgresPasswordResetOtpChallengeStore(dataSource, transactions, properties),
                properties, new com.lordgainsborough.restaurant.security.AbuseIpHasher(hashProperties), clock);
        doThrow(new AmbiguousEmailDeliveryException("timeout")).doNothing().when(sender)
                .sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
        var worker = queue(realPersistence);
        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT send_status FROM password_reset_otp_challenges", String.class))
                .isEqualTo("DELIVERY_UNKNOWN");
        time.set(start.plusSeconds(10));
        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT send_status FROM password_reset_otp_challenges", String.class))
                .isEqualTo("SENT");
    }

    @Test void expiredSupersededConsumedMissingAndExhaustedTasksAreRetired() {
        for (String id : new String[]{"expired", "superseded", "consumed", "missing", "exhausted", "old"}) pending(id);
        jdbc.update("UPDATE password_reset_otp_challenges SET code_expires_at=? WHERE email_key='expired'", Timestamp.from(start));
        jdbc.update("UPDATE password_reset_otp_challenges SET send_operation_id='new' WHERE email_key='superseded'");
        jdbc.update("UPDATE password_reset_otp_challenges SET status='CONSUMED' WHERE email_key='consumed'");
        jdbc.update("DELETE FROM password_reset_otp_challenges WHERE email_key='missing'");
        jdbc.update("UPDATE password_reset_email_queue SET attempts=5 WHERE id='exhausted'");
        jdbc.update("UPDATE password_reset_email_queue SET created_at=? WHERE id='old'", Timestamp.from(start.minusSeconds(600)));
        assertThat(queue().processPending()).isZero();
        verifyNoInteractions(sender);
        assertThat(status("exhausted")).isEqualTo("FAILED");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM password_reset_email_queue", String.class))
                .containsExactlyInAnyOrder("EXPIRED", "FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_email_queue WHERE encrypted_code IS NOT NULL", Integer.class)).isZero();
        time.set(start.plus(Duration.ofDays(2)));
        queue().processPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_email_queue", Integer.class)).isZero();
    }

    @Test void concurrentInstancesSendOnlyOnceDuringLiveLease() throws Exception {
        pending("concurrent");
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { sending.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(sender).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = pool.submit(() -> queue().processPending());
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(queue().processPending()).isZero();
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        verify(sender).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
    }

    @Test void lateOwnerCannotOverwriteRecoveredAttempt() throws Exception {
        pending("stale");
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            sending.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            throw new DefiniteEmailRejectionException("late failure");
        }).doNothing().when(sender).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = pool.submit(() -> queue().processPending());
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                time.set(start.plusSeconds(61));
                assertThat(queue().processPending()).isEqualTo(1);
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(status("stale")).isEqualTo("SENT");
        verify(persistence, never()).expireAfterDefiniteInitialFailure(anyString(), anyString(), any());
    }

    @Test void productionConfigurationSchedulesRecoveryAutomatically() throws Exception {
        pending("scheduled");
        // A failed sweep must not cancel subsequent scheduled executions.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("temporary outage"))
                .doAnswer(call -> time.get()).when(persistence).now();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "app.auth.password-reset.queue-initial-delay", "PT0.01S",
                    "app.auth.password-reset.queue-poll-interval", "PT0.02S")));
            context.registerBean(javax.sql.DataSource.class, () -> dataSource);
            context.registerBean("transactionManager", org.springframework.transaction.PlatformTransactionManager.class, () -> transactions);
            context.registerBean(PasswordResetProperties.class, () -> properties);
            context.registerBean(EmailSenderService.class, () -> sender);
            context.registerBean(PasswordResetOtpChallengePersistence.class, () -> persistence);
            context.register(PostgresPasswordResetPersistenceConfiguration.class);
            context.refresh();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!status("scheduled").equals("SENT") && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(status("scheduled")).isEqualTo("SENT");
        }
    }
}
