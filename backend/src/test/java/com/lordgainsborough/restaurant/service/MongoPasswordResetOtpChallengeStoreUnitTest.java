package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.PasswordResetOtpChallengeRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MongoPasswordResetOtpChallengeStoreUnitTest {

    private static final Instant NOW = Instant.parse("2026-07-13T14:00:00Z");
    private static final String EMAIL_KEY = "test-email-key-hash";
    private static final String CHALLENGE_ID = "ch-mongo-unit-101";
    private static final String USER_ID = "usr-mongo-101";
    private static final String DIGEST = "digest-abc-123";
    private static final String RAW_PASSWORD = "NewPassword123@";
    private static final String OP_ID = "op-reset-999";

    private PasswordResetOtpChallengeRepository repository;
    private MongoTemplate mongoTemplate;
    private UserRepository userRepository;
    private PasswordResetProperties properties;
    private PlatformTransactionManager transactionManager;
    private SimpleTransactionStatus transactionStatus;

    @BeforeEach
    void setUp() {
        repository = mock(PasswordResetOtpChallengeRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        userRepository = mock(UserRepository.class);

        properties = new PasswordResetProperties();
        properties.setCodeTtl(Duration.ofMinutes(10));
        properties.setChallengeTtl(Duration.ofMinutes(30));
        properties.setMaxVerificationFailures(5);

        transactionManager = mock(PlatformTransactionManager.class);
        transactionStatus = spy(new SimpleTransactionStatus());
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
    }

    private PasswordResetOtpChallenge createActiveChallenge() {
        PasswordResetOtpChallenge ch = new PasswordResetOtpChallenge();
        ch.setEmailKey(EMAIL_KEY);
        ch.setChallengeId(CHALLENGE_ID);
        ch.setUserId(USER_ID);
        ch.setPurpose(PasswordResetOtpChallenge.Purpose.PASSWORD_RESET);
        ch.setStatus(PasswordResetChallengeStatus.ACTIVE);
        ch.setCodeDigest(DIGEST);
        ch.setChallengeExpiresAt(NOW.plus(Duration.ofMinutes(30)));
        ch.setCodeExpiresAt(NOW.plus(Duration.ofMinutes(10)));
        ch.setFailedVerificationAttempts(0);
        return ch;
    }

    @Test
    @DisplayName("MongoDB atomic transaction marks rollback-only when finalizeConsumed fails after user update")
    void testRollbackOnlyOnFinalizeConsumedFailure() {
        PasswordResetOtpChallenge challenge = createActiveChallenge();
        when(repository.findById(EMAIL_KEY)).thenReturn(Optional.of(challenge));

        PasswordResetOtpChallenge claimed = createActiveChallenge();
        claimed.setStatus(PasswordResetChallengeStatus.PROCESSING);
        claimed.setResetOperationId(OP_ID);

        when(mongoTemplate.findAndModify(any(), any(), any(), eq(PasswordResetOtpChallenge.class)))
                .thenReturn(claimed);

        User updatedUser = new User();
        updatedUser.setId(USER_ID);
        updatedUser.setLastPasswordResetOperationId(OP_ID);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(User.class)))
                .thenReturn(updatedUser);

        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, transactionManager);
        store.setSimulateFinalizeConsumedFailure(true);

        PasswordResetExecutionResult result = store.executePasswordResetTransaction(
                EMAIL_KEY, CHALLENGE_ID, DIGEST, OP_ID, RAW_PASSWORD, NOW);

        assertEquals(PasswordResetExecutionResult.Status.TRANSACTION_FAILED, result.status());
        verify(transactionStatus, atLeastOnce()).setRollbackOnly();
        assertTrue(transactionStatus.isRollbackOnly());
    }

    @Test
    @DisplayName("MongoDB atomic transaction throws IllegalStateException when requireTransaction=true without tx manager")
    void testRequireTransactionThrowsWhenTxManagerMissing() {
        properties.setRequireTransaction(true);
        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, (PlatformTransactionManager) null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                store.executePasswordResetTransaction(EMAIL_KEY, CHALLENGE_ID, DIGEST, OP_ID, RAW_PASSWORD, NOW));
        assertTrue(ex.getMessage().contains("app.auth.password-reset.require-transaction=true"));
    }

    @Test
    @DisplayName("MongoDB atomic transaction rejects null expectedDigest on ACTIVE challenge fail-closed")
    void testRejectsNullExpectedDigestFailClosed() {
        PasswordResetOtpChallenge challenge = createActiveChallenge();
        when(repository.findById(EMAIL_KEY)).thenReturn(Optional.of(challenge));

        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, transactionManager);

        PasswordResetExecutionResult result = store.executePasswordResetTransaction(
                EMAIL_KEY, CHALLENGE_ID, null, OP_ID, RAW_PASSWORD, NOW);

        assertEquals(PasswordResetExecutionResult.Status.INVALID_CHALLENGE, result.status());
        verify(transactionStatus, atLeastOnce()).setRollbackOnly();
        assertTrue(transactionStatus.isRollbackOnly());
    }

    @Test
    @DisplayName("MongoDB atomic transaction rejects null challenge expiration timestamp fail-closed")
    void testRejectsNullChallengeExpiresAtFailClosed() {
        PasswordResetOtpChallenge challenge = createActiveChallenge();
        challenge.setChallengeExpiresAt(null);
        when(repository.findById(EMAIL_KEY)).thenReturn(Optional.of(challenge));

        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, transactionManager);

        PasswordResetExecutionResult result = store.executePasswordResetTransaction(
                EMAIL_KEY, CHALLENGE_ID, DIGEST, OP_ID, RAW_PASSWORD, NOW);

        assertEquals(PasswordResetExecutionResult.Status.CHALLENGE_EXPIRED, result.status());
        verify(transactionStatus, atLeastOnce()).setRollbackOnly();
        assertTrue(transactionStatus.isRollbackOnly());
    }

    @Test
    @DisplayName("MongoDB atomic transaction rejects null code expiration timestamp fail-closed")
    void testRejectsNullCodeExpiresAtFailClosed() {
        PasswordResetOtpChallenge challenge = createActiveChallenge();
        challenge.setCodeExpiresAt(null);
        when(repository.findById(EMAIL_KEY)).thenReturn(Optional.of(challenge));

        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, transactionManager);

        PasswordResetExecutionResult result = store.executePasswordResetTransaction(
                EMAIL_KEY, CHALLENGE_ID, DIGEST, OP_ID, RAW_PASSWORD, NOW);

        assertEquals(PasswordResetExecutionResult.Status.CODE_EXPIRED, result.status());
        verify(transactionStatus, atLeastOnce()).setRollbackOnly();
        assertTrue(transactionStatus.isRollbackOnly());
    }

    @Test
    @DisplayName("MongoDB atomic transaction rejects invalid challenge purpose fail-closed")
    void testRejectsInvalidPurposeFailClosed() {
        PasswordResetOtpChallenge challenge = createActiveChallenge();
        challenge.setPurpose(null);
        when(repository.findById(EMAIL_KEY)).thenReturn(Optional.of(challenge));

        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, transactionManager);

        PasswordResetExecutionResult result = store.executePasswordResetTransaction(
                EMAIL_KEY, CHALLENGE_ID, DIGEST, OP_ID, RAW_PASSWORD, NOW);

        assertEquals(PasswordResetExecutionResult.Status.INVALID_CHALLENGE, result.status());
        verify(transactionStatus, atLeastOnce()).setRollbackOnly();
        assertTrue(transactionStatus.isRollbackOnly());
    }

    @Test
    @DisplayName("MongoDB transaction failure hook before user update marks rollback-only")
    void testFailureHookBeforeUserUpdateMarksRollbackOnly() {
        PasswordResetOtpChallenge challenge = createActiveChallenge();
        when(repository.findById(EMAIL_KEY)).thenReturn(Optional.of(challenge));

        PasswordResetOtpChallenge claimed = createActiveChallenge();
        claimed.setStatus(PasswordResetChallengeStatus.PROCESSING);
        claimed.setResetOperationId(OP_ID);

        when(mongoTemplate.findAndModify(any(), any(), any(), eq(PasswordResetOtpChallenge.class)))
                .thenReturn(claimed);

        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, transactionManager);
        store.setFailureInjectorBeforeUserUpdate((status, uid) -> status.setRollbackOnly());

        PasswordResetExecutionResult result = store.executePasswordResetTransaction(
                EMAIL_KEY, CHALLENGE_ID, DIGEST, OP_ID, RAW_PASSWORD, NOW);

        verify(transactionStatus, atLeastOnce()).setRollbackOnly();
        assertTrue(transactionStatus.isRollbackOnly());
    }

    @Test
    @DisplayName("MongoDB transaction failure hook before challenge finalize marks rollback-only")
    void testFailureHookBeforeChallengeFinalizeMarksRollbackOnly() {
        PasswordResetOtpChallenge challenge = createActiveChallenge();
        when(repository.findById(EMAIL_KEY)).thenReturn(Optional.of(challenge));

        PasswordResetOtpChallenge claimed = createActiveChallenge();
        claimed.setStatus(PasswordResetChallengeStatus.PROCESSING);
        claimed.setResetOperationId(OP_ID);

        when(mongoTemplate.findAndModify(any(), any(), any(), eq(PasswordResetOtpChallenge.class)))
                .thenReturn(claimed);

        User updatedUser = new User();
        updatedUser.setId(USER_ID);
        updatedUser.setLastPasswordResetOperationId(OP_ID);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(User.class)))
                .thenReturn(updatedUser);

        MongoPasswordResetOtpChallengeStore store = new MongoPasswordResetOtpChallengeStore(
                repository, mongoTemplate, properties, userRepository, transactionManager);
        store.setFailureInjectorBeforeChallengeFinalize((status, cid) -> status.setRollbackOnly());

        PasswordResetExecutionResult result = store.executePasswordResetTransaction(
                EMAIL_KEY, CHALLENGE_ID, DIGEST, OP_ID, RAW_PASSWORD, NOW);

        assertEquals(PasswordResetExecutionResult.Status.TRANSACTION_FAILED, result.status());
        verify(transactionStatus, atLeastOnce()).setRollbackOnly();
        assertTrue(transactionStatus.isRollbackOnly());
    }
}
