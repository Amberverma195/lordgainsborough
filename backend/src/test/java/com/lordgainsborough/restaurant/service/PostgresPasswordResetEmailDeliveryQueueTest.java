package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetDeliveryTask;
import com.lordgainsborough.restaurant.security.PasswordResetQueuePayloadEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PostgresPasswordResetEmailDeliveryQueue Unit Test")
class PostgresPasswordResetEmailDeliveryQueueTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData databaseMetaData;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    @Mock
    private EmailSenderService emailSenderService;

    @Mock
    private PasswordResetOtpChallengePersistence persistence;

    @Mock
    private AuthSecurityObservability observability;

    private PasswordResetProperties properties;
    private PasswordResetQueuePayloadEncryptor encryptor;
    private Executor directExecutor;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(databaseMetaData.getDriverName()).thenReturn("PostgreSQL JDBC Driver");
        lenient().when(databaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        lenient().when(connection.getMetaData()).thenReturn(databaseMetaData);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.getConnection()).thenReturn(connection);
        lenient().when(preparedStatement.executeUpdate()).thenReturn(1);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().when(persistence.now()).thenReturn(Instant.parse("2026-11-20T20:00:00Z"));

        properties = new PasswordResetProperties();
        encryptor = new PasswordResetQueuePayloadEncryptor();
        directExecutor = Runnable::run; // Execute synchronously in test thread
    }

    @Test
    @DisplayName("Queue initializes with default encryptor and provides accessors")
    void testInitializationAndAccessors() {
        PostgresPasswordResetEmailDeliveryQueue queue = new PostgresPasswordResetEmailDeliveryQueue(
                dataSource,
                transactionManager,
                emailSenderService,
                persistence,
                properties,
                observability,
                directExecutor,
                encryptor
        );

        assertTrue(queue.isDurableStore());
        assertSame(dataSource, queue.getDataSource());
        assertSame(transactionManager, queue.getTransactionManager());
        assertSame(encryptor, queue.getEncryptor());
    }

    @Test
    @DisplayName("Enqueue real task encrypts raw OTP before database insertion")
    void testEnqueueRealTaskEncryptsOtp() throws Exception {
        PostgresPasswordResetEmailDeliveryQueue queue = new PostgresPasswordResetEmailDeliveryQueue(
                dataSource,
                transactionManager,
                emailSenderService,
                persistence,
                properties,
                observability,
                directExecutor,
                encryptor
        );

        PasswordResetDeliveryTask realTask = new PasswordResetDeliveryTask(
                "task-uuid-1",
                "norm-email-key",
                "challenge-1",
                "customer@example.com",
                "584920",
                "send-op-1",
                Duration.ofMinutes(10),
                false,
                Instant.now()
        );

        queue.enqueue(realTask);

        // Verify that SQL insert was executed
        verify(connection, atLeastOnce()).prepareStatement(contains("INSERT INTO password_reset_email_queue"));
        // Verify setString was called for encrypted_code (index 5)
        ArgumentCaptor<String> insertedParamCaptor = ArgumentCaptor.forClass(String.class);
        verify(preparedStatement, atLeastOnce()).setString(eq(5), insertedParamCaptor.capture());

        String insertedPayload = insertedParamCaptor.getValue();
        assertNotNull(insertedPayload);
        // The persisted value must NOT be the plaintext OTP
        assertNotEquals("584920", insertedPayload);

        // Must decrypt back to original plaintext using the task ID as context AAD
        String decrypted = encryptor.decrypt(insertedPayload, "task-uuid-1");
        assertEquals("584920", decrypted);
    }

    @Test
    @DisplayName("Enqueue synthetic task skips encryption and stores null encrypted_code")
    void testEnqueueSyntheticTaskStoresNullCode() throws Exception {
        PostgresPasswordResetEmailDeliveryQueue queue = new PostgresPasswordResetEmailDeliveryQueue(
                dataSource,
                transactionManager,
                emailSenderService,
                persistence,
                properties,
                observability,
                directExecutor,
                encryptor
        );

        PasswordResetDeliveryTask syntheticTask = new PasswordResetDeliveryTask(
                "task-synth-1",
                "synth-email-key",
                "challenge-synth",
                "unknown@example.com",
                null,
                "send-op-synth",
                Duration.ofMinutes(10),
                true,
                Instant.now()
        );

        queue.enqueue(syntheticTask);

        verify(connection, atLeastOnce()).prepareStatement(contains("INSERT INTO password_reset_email_queue"));
        // For synthetic task, encrypted_code (parameter index 5) must be set to null via setNull
        verify(preparedStatement, atLeastOnce()).setNull(eq(5), anyInt());

        // Verify shadow challenge observability metric recorded
        verify(observability, times(1)).record(
                eq(AuthSecurityObservability.Flow.PASSWORD_RESET),
                eq(AuthSecurityObservability.Outcome.SUCCESS),
                eq(AuthSecurityObservability.Reason.RESET_SHADOW_CHALLENGE)
        );
    }

    @Test
    @DisplayName("deliverItem decrypts OTP in memory, sends email, and clears encrypted_code to minimize retention")
    void testDeliverItemDecryptsAndClearsRetentionOnSuccess() throws Exception {
        PostgresPasswordResetEmailDeliveryQueue queue = new PostgresPasswordResetEmailDeliveryQueue(
                dataSource,
                transactionManager,
                emailSenderService,
                persistence,
                properties,
                observability,
                directExecutor,
                encryptor
        );

        String taskId = "task-deliver-1";
        String expectedOtp = "729401";
        String encryptedPayload = encryptor.encrypt(expectedOtp, taskId);

        PostgresPasswordResetEmailDeliveryQueue.QueuedItem item = new PostgresPasswordResetEmailDeliveryQueue.QueuedItem(
                taskId,
                "norm-email-key",
                "challenge-deliver-1",
                "customer@example.com",
                encryptedPayload,
                "send-op-deliver-1",
                Duration.ofMinutes(10), 1
        );

        queue.deliverItem(item);

        // 1. Decrypted OTP sent to email provider
        verify(emailSenderService, times(1)).sendPasswordResetCodeEmail(
                eq("customer@example.com"),
                eq("729401"),
                eq("send-op-deliver-1"),
                eq(Duration.ofMinutes(10))
        );

        // 2. Database updated to clear encrypted_code (SET status = 'SENT', encrypted_code = NULL)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        boolean clearedRetention = sqlCaptor.getAllValues().stream()
                .anyMatch(sql -> sql.contains("status = ?") && sql.contains("ELSE NULL END"));
        assertTrue(clearedRetention, "Update statement must clear encrypted_code on success");

        // 3. Mark send outcome SENT
        verify(persistence, times(1)).markSendOutcome(
                eq("norm-email-key"),
                eq("send-op-deliver-1"),
                eq(OtpSendStatus.SENT),
                any(Instant.class)
        );
    }

    @Test
    @DisplayName("deliverItem on definite failure clears encrypted_code and marks challenge expired")
    void testDeliverItemClearsRetentionOnDefiniteFailure() throws Exception {
        PostgresPasswordResetEmailDeliveryQueue queue = new PostgresPasswordResetEmailDeliveryQueue(
                dataSource,
                transactionManager,
                emailSenderService,
                persistence,
                properties,
                observability,
                directExecutor,
                encryptor
        );

        String taskId = "task-deliver-fail-1";
        String expectedOtp = "839201";
        String encryptedPayload = encryptor.encrypt(expectedOtp, taskId);

        doThrow(new RuntimeException("Definite provider error: invalid recipient"))
                .when(emailSenderService).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());

        PostgresPasswordResetEmailDeliveryQueue.QueuedItem item = new PostgresPasswordResetEmailDeliveryQueue.QueuedItem(
                taskId,
                "norm-email-key",
                "challenge-deliver-fail",
                "bad-email@example.com",
                encryptedPayload,
                "send-op-fail-1",
                Duration.ofMinutes(10), 1
        );

        queue.deliverItem(item);

        // Database updated to mark FAILED and clear encrypted_code (SET status = 'FAILED', encrypted_code = NULL)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        boolean clearedRetention = sqlCaptor.getAllValues().stream()
                .anyMatch(sql -> sql.contains("status = ?") && sql.contains("ELSE NULL END"));
        assertTrue(clearedRetention, "Update statement must clear encrypted_code on definite failure");

        // Mark send outcome DEFINITELY_FAILED
        verify(persistence, times(1)).markSendOutcome(
                eq("norm-email-key"),
                eq("send-op-fail-1"),
                eq(OtpSendStatus.DEFINITELY_FAILED),
                any(Instant.class)
        );
    }
}
