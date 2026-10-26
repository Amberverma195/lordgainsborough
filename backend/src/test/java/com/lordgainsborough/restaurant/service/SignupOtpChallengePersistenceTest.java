package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.repository.SignupOtpChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for generated Mongo query/update shapes and local validation.
 *
 * <p>These tests intentionally mock {@link MongoTemplate}; they do not claim to prove
 * MongoDB atomicity. Real concurrency guarantees live in
 * {@link SignupOtpChallengeMongoConcurrencyTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
class SignupOtpChallengePersistenceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T04:00:00Z");

    @Mock
    private SignupOtpChallengeRepository repository;

    @Mock
    private MongoTemplate mongoTemplate;

    private SignupOtpChallengePersistence persistence;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        persistence = new SignupOtpChallengePersistence(repository, mongoTemplate, clock);
    }

    @Test
    void normalizeEmailTrimsAndLowercases() {
        assertEquals("member@example.com", persistence.normalizeEmail("  Member@Example.COM "));
    }

    @Test
    void emailKeyIsStableAndDerivedFromNormalizedEmail() {
        String keyA = persistence.emailKeyFromNormalized("member@example.com");
        String keyB = persistence.emailKeyFromRawEmail("  Member@Example.COM ");
        String keyOther = persistence.emailKeyFromNormalized("other@example.com");

        assertEquals(64, keyA.length());
        assertEquals(keyA, keyB);
        assertNotEquals(keyA, keyOther);
    }

    @Test
    void digestOtpDoesNotEqualRawOtpAndCanBeVerified() {
        String raw = "123456";
        String digest = persistence.digestOtp(raw);

        assertNotEquals(raw, digest);
        assertTrue(persistence.otpMatches(raw, digest));
        assertFalse(persistence.otpMatches("000000", digest));
    }

    @Test
    void upsertNewActiveChallengeSavesSingleDocumentKeyedByEmailHash() {
        when(repository.save(any(SignupOtpChallenge.class))).thenAnswer(inv -> inv.getArgument(0));

        String digest = persistence.digestOtp("654321");
        SignupOtpChallenge saved = persistence.upsertNewActiveChallenge("Member@Example.com", digest);

        ArgumentCaptor<SignupOtpChallenge> captor = ArgumentCaptor.forClass(SignupOtpChallenge.class);
        verify(repository).save(captor.capture());
        SignupOtpChallenge stored = captor.getValue();

        assertEquals(persistence.emailKeyFromNormalized("member@example.com"), stored.getEmailKey());
        assertEquals(stored.getEmailKey(), saved.getEmailKey());
        assertEquals("member@example.com", stored.getNormalizedEmail());
        assertEquals(OtpChallengeStatus.ACTIVE, stored.getStatus());
        assertEquals(digest, stored.getOtpDigest());
        assertEquals(0, stored.getResendCount());
        assertEquals(0, stored.getFailedVerifyAttempts());
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.RESEND_COOLDOWN), stored.getNextResendAt());
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.OTP_TTL), stored.getOtpExpiresAt());
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.CHALLENGE_TTL), stored.getChallengeExpiresAt());
        assertEquals(FIXED_NOW, stored.getCreatedAt());
        assertEquals(FIXED_NOW, stored.getUpdatedAt());
        assertNotEquals(null, stored.getChallengeId());
        assertFalse(stored.getChallengeId().isBlank());
    }

    @Test
    void buildNewActiveChallengeUsesConfiguredTtls() {
        String digest = persistence.digestOtp("111222");
        SignupOtpChallenge challenge = persistence.buildNewActiveChallenge(
                "member@example.com", digest, "challenge-1");

        assertEquals(FIXED_NOW.plusSeconds(30), challenge.getNextResendAt());
        assertEquals(FIXED_NOW.plusSeconds(600), challenge.getOtpExpiresAt());
        assertEquals(FIXED_NOW.plusSeconds(1800), challenge.getChallengeExpiresAt());
        assertEquals(OtpChallengeStatus.ACTIVE, challenge.getStatus());
        assertEquals(0, challenge.getResendCount());
        assertEquals(0, challenge.getFailedVerifyAttempts());
    }

    @Test
    void findUsableByEmailReturnsEmptyWhenChallengeExpiredEvenIfStillInDatabase() {
        SignupOtpChallenge expired = new SignupOtpChallenge();
        expired.setEmailKey(persistence.emailKeyFromNormalized("member@example.com"));
        expired.setNormalizedEmail("member@example.com");
        expired.setStatus(OtpChallengeStatus.ACTIVE);
        expired.setChallengeExpiresAt(FIXED_NOW.minusSeconds(1));

        when(repository.findById(expired.getEmailKey())).thenReturn(Optional.of(expired));

        assertTrue(persistence.findActiveUnexpiredByEmail("member@example.com").isEmpty());
        assertTrue(persistence.isChallengeExpired(expired, FIXED_NOW));
    }

    @Test
    void findUsableByEmailReturnsChallengeWhenNotExpired() {
        SignupOtpChallenge active = new SignupOtpChallenge();
        active.setEmailKey(persistence.emailKeyFromNormalized("member@example.com"));
        active.setNormalizedEmail("member@example.com");
        active.setStatus(OtpChallengeStatus.ACTIVE);
        active.setChallengeExpiresAt(FIXED_NOW.plusSeconds(60));
        active.setOtpExpiresAt(FIXED_NOW.plusSeconds(30));

        when(repository.findById(active.getEmailKey())).thenReturn(Optional.of(active));

        Optional<SignupOtpChallenge> found = persistence.findActiveUnexpiredByEmail("Member@Example.com");
        assertTrue(found.isPresent());
        assertEquals(active.getEmailKey(), found.get().getEmailKey());
        assertFalse(persistence.isOtpExpired(active, FIXED_NOW));
    }

    @Test
    void findActiveUnexpiredIgnoresNonActiveStatus() {
        SignupOtpChallenge locked = new SignupOtpChallenge();
        locked.setEmailKey(persistence.emailKeyFromNormalized("member@example.com"));
        locked.setStatus(OtpChallengeStatus.LOCKED);
        locked.setChallengeExpiresAt(FIXED_NOW.plusSeconds(60));
        when(repository.findById(locked.getEmailKey())).thenReturn(Optional.of(locked));

        assertTrue(persistence.findActiveUnexpiredByEmail("member@example.com").isEmpty());
    }

    @Test
    void tryInsertReturnsFalseOnDuplicateKey() {
        SignupOtpChallenge challenge = persistence.buildNewActiveChallenge(
                "member@example.com", persistence.digestOtp("123456"), "c1");
        when(mongoTemplate.insert(challenge)).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        assertFalse(persistence.tryInsert(challenge));
    }

    @Test
    void isOtpExpiredUsesOtpExpiresAtNotTtlCleanup() {
        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setOtpExpiresAt(FIXED_NOW);
        challenge.setChallengeExpiresAt(FIXED_NOW.plusSeconds(600));

        assertTrue(persistence.isOtpExpired(challenge, FIXED_NOW));
        assertFalse(persistence.isChallengeExpired(challenge, FIXED_NOW));
    }

    @Test
    void saveRefreshesUpdatedAt() {
        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setEmailKey(persistence.emailKeyFromNormalized("member@example.com"));
        challenge.setUpdatedAt(FIXED_NOW.minusSeconds(30));
        when(repository.save(any(SignupOtpChallenge.class))).thenAnswer(inv -> inv.getArgument(0));

        SignupOtpChallenge saved = persistence.save(challenge);

        assertEquals(FIXED_NOW, saved.getUpdatedAt());
        verify(repository).save(challenge);
    }

    @Test
    void deleteByEmailUsesDerivedEmailKey() {
        String key = persistence.emailKeyFromNormalized("member@example.com");
        persistence.deleteByEmail("Member@Example.com");
        verify(repository).deleteById(key);
    }

    @Test
    void upsertRejectsBlankDigest() {
        assertThrows(IllegalArgumentException.class,
                () -> persistence.buildNewActiveChallenge("member@example.com", " ", "c1"));
    }

    @Test
    void resendUpdateBuildsFindAndModifyWithRequiredGuards() {
        String emailKey = persistence.emailKeyFromNormalized("member@example.com");
        String newDigest = persistence.digestOtp("999888");
        SignupOtpChallenge updated = new SignupOtpChallenge();
        updated.setEmailKey(emailKey);
        updated.setResendCount(1);
        updated.setOtpDigest(newDigest);
        updated.setOtpExpiresAt(FIXED_NOW.plus(SignupOtpChallengePersistence.OTP_TTL));
        updated.setNextResendAt(FIXED_NOW.plus(SignupOtpChallengePersistence.RESEND_COOLDOWN));
        updated.setUpdatedAt(FIXED_NOW);

        when(mongoTemplate.findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class)))
                .thenReturn(updated);

        Optional<SignupOtpChallenge> result =
                persistence.tryAtomicResend(emailKey, newDigest, "send-op-1", "idem-1", FIXED_NOW);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getResendCount());
        assertEquals(newDigest, result.get().getOtpDigest());

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).findAndModify(
                queryCaptor.capture(),
                updateCaptor.capture(),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class));

        org.bson.Document queryDoc = queryCaptor.getValue().getQueryObject();
        assertEquals(emailKey, queryDoc.get("_id"));
        assertEquals(OtpChallengeStatus.ACTIVE, queryDoc.get("status"));
        assertTrue(queryDoc.containsKey("challengeExpiresAt"));
        assertTrue(queryDoc.containsKey("failedVerifyAttempts"));
        assertTrue(queryDoc.containsKey("resendCount"));
        assertTrue(queryDoc.containsKey("nextResendAt"));

        org.bson.Document updateDoc = updateCaptor.getValue().getUpdateObject();
        org.bson.Document inc = (org.bson.Document) updateDoc.get("$inc");
        org.bson.Document set = (org.bson.Document) updateDoc.get("$set");
        assertEquals(1, inc.getInteger("resendCount"));
        assertEquals(newDigest, set.get("otpDigest"));
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.OTP_TTL), set.get("otpExpiresAt"));
        assertEquals(FIXED_NOW.plus(SignupOtpChallengePersistence.RESEND_COOLDOWN), set.get("nextResendAt"));
        assertEquals(FIXED_NOW, set.get("updatedAt"));
        assertEquals("send-op-1", set.get("sendOperationId"));
        assertEquals(OtpSendStatus.RESERVED, set.get("sendStatus"));
        assertEquals("idem-1", set.get("clientIdempotencyKey"));
        assertEquals(false, set.get("lastSendWasInitial"));
    }

    @Test
    void diagnoseResendDenialMapsChallengeStates() {
        String emailKey = persistence.emailKeyFromNormalized("member@example.com");

        when(repository.findById(emailKey)).thenReturn(Optional.empty());
        assertEquals(
                SignupOtpChallengePersistence.ResendDenialReason.CHALLENGE_MISSING,
                persistence.diagnoseResendDenial(emailKey, FIXED_NOW));

        SignupOtpChallenge locked = baseChallenge(emailKey);
        locked.setStatus(OtpChallengeStatus.LOCKED);
        when(repository.findById(emailKey)).thenReturn(Optional.of(locked));
        assertEquals(
                SignupOtpChallengePersistence.ResendDenialReason.CHALLENGE_LOCKED,
                persistence.diagnoseResendDenial(emailKey, FIXED_NOW));

        SignupOtpChallenge maxFailed = baseChallenge(emailKey);
        maxFailed.setFailedVerifyAttempts(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS);
        when(repository.findById(emailKey)).thenReturn(Optional.of(maxFailed));
        assertEquals(
                SignupOtpChallengePersistence.ResendDenialReason.CHALLENGE_LOCKED,
                persistence.diagnoseResendDenial(emailKey, FIXED_NOW));

        SignupOtpChallenge expired = baseChallenge(emailKey);
        expired.setChallengeExpiresAt(FIXED_NOW.minusSeconds(1));
        when(repository.findById(emailKey)).thenReturn(Optional.of(expired));
        assertEquals(
                SignupOtpChallengePersistence.ResendDenialReason.CHALLENGE_EXPIRED,
                persistence.diagnoseResendDenial(emailKey, FIXED_NOW));

        SignupOtpChallenge maxed = baseChallenge(emailKey);
        maxed.setResendCount(SignupOtpChallengePersistence.MAX_RESENDS);
        when(repository.findById(emailKey)).thenReturn(Optional.of(maxed));
        assertEquals(
                SignupOtpChallengePersistence.ResendDenialReason.RESEND_LIMIT_REACHED,
                persistence.diagnoseResendDenial(emailKey, FIXED_NOW));

        SignupOtpChallenge cooling = baseChallenge(emailKey);
        cooling.setNextResendAt(FIXED_NOW.plusSeconds(20));
        when(repository.findById(emailKey)).thenReturn(Optional.of(cooling));
        assertEquals(
                SignupOtpChallengePersistence.ResendDenialReason.COOLDOWN_ACTIVE,
                persistence.diagnoseResendDenial(emailKey, FIXED_NOW));
    }

    @Test
    void verifiedTransitionBuildsConditionalFindAndModify() {
        String emailKey = persistence.emailKeyFromNormalized("member@example.com");
        String digest = persistence.digestOtp("123456");
        SignupOtpChallenge verified = baseChallenge(emailKey);
        verified.setStatus(OtpChallengeStatus.VERIFIED);
        verified.setOtpDigest(digest);

        when(mongoTemplate.findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class)))
                .thenReturn(verified);

        Optional<SignupOtpChallenge> result =
                persistence.tryAtomicMarkVerified(emailKey, digest, FIXED_NOW);

        assertTrue(result.isPresent());
        assertEquals(OtpChallengeStatus.VERIFIED, result.get().getStatus());

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).findAndModify(
                queryCaptor.capture(),
                updateCaptor.capture(),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class));

        org.bson.Document queryDoc = queryCaptor.getValue().getQueryObject();
        assertEquals(emailKey, queryDoc.get("_id"));
        assertEquals(OtpChallengeStatus.ACTIVE, queryDoc.get("status"));
        assertEquals(digest, queryDoc.get("otpDigest"));
        assertTrue(queryDoc.containsKey("challengeExpiresAt"));
        assertTrue(queryDoc.containsKey("otpExpiresAt"));
        assertTrue(queryDoc.containsKey("failedVerifyAttempts"));
        assertEquals(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS,
                ((org.bson.Document) queryDoc.get("failedVerifyAttempts")).get("$lt"));

        org.bson.Document set =
                (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertEquals(OtpChallengeStatus.VERIFIED, set.get("status"));
        assertEquals(FIXED_NOW, set.get("updatedAt"));
    }

    @Test
    void failedVerifyUpdateBuildsIncrementWithCapPredicate() {
        String emailKey = persistence.emailKeyFromNormalized("member@example.com");
        String challengeId = "ch-test-123";
        String otpDigest = persistence.digestOtp("123456");
        SignupOtpChallenge updated = baseChallenge(emailKey);
        updated.setFailedVerifyAttempts(1);

        when(mongoTemplate.findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class)))
                .thenReturn(updated);

        Optional<SignupOtpChallenge> result =
                persistence.tryAtomicRecordFailedVerify(emailKey, challengeId, otpDigest, FIXED_NOW);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getFailedVerifyAttempts());

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).findAndModify(
                queryCaptor.capture(),
                updateCaptor.capture(),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class));

        org.bson.Document queryDoc = queryCaptor.getValue().getQueryObject();
        assertEquals(emailKey, queryDoc.get("_id"));
        assertEquals(challengeId, queryDoc.get("challengeId"));
        assertEquals(otpDigest, queryDoc.get("otpDigest"));
        assertEquals(OtpChallengeStatus.ACTIVE, queryDoc.get("status"));
        assertTrue(queryDoc.containsKey("challengeExpiresAt"));
        assertTrue(queryDoc.containsKey("otpExpiresAt"));
        assertTrue(queryDoc.containsKey("failedVerifyAttempts"));

        org.bson.Document inc =
                (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$inc");
        assertEquals(1, inc.getInteger("failedVerifyAttempts"));
    }

    @Test
    void fifthFailedVerifyAttemptAtomicallySetsStatusLocked() {
        String emailKey = persistence.emailKeyFromNormalized("member@example.com");
        String challengeId = "ch-test-123";
        String otpDigest = persistence.digestOtp("123456");
        SignupOtpChallenge locked = baseChallenge(emailKey);
        locked.setFailedVerifyAttempts(5);
        locked.setStatus(OtpChallengeStatus.LOCKED);

        // When failed attempts is 4, inc query (< 4) returns null and lock query (== 4) returns locked
        when(mongoTemplate.findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class)))
                .thenReturn(null)
                .thenReturn(locked);

        Optional<SignupOtpChallenge> result =
                persistence.tryAtomicRecordFailedVerify(emailKey, challengeId, otpDigest, FIXED_NOW);

        assertTrue(result.isPresent());
        assertEquals(5, result.get().getFailedVerifyAttempts());
        assertEquals(OtpChallengeStatus.LOCKED, result.get().getStatus());

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2)).findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                updateCaptor.capture(),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class));

        // The second findAndModify update (the lockUpdate) contains both $inc and $set status = LOCKED
        org.bson.Document updateDoc = updateCaptor.getAllValues().get(1).getUpdateObject();
        org.bson.Document inc = (org.bson.Document) updateDoc.get("$inc");
        org.bson.Document set = (org.bson.Document) updateDoc.get("$set");
        assertEquals(1, inc.getInteger("failedVerifyAttempts"));
        assertEquals(OtpChallengeStatus.LOCKED, set.get("status"));
    }

    @Test
    void expiredReplacementBuildsConditionalResetUpdate() {
        String emailKey = persistence.emailKeyFromNormalized("member@example.com");
        SignupOtpChallenge fresh = persistence.buildNewActiveChallenge(
                "member@example.com", persistence.digestOtp("424242"), "fresh-challenge");
        SignupOtpChallenge replaced = fresh;

        when(mongoTemplate.findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class)))
                .thenReturn(replaced);

        Optional<SignupOtpChallenge> result =
                persistence.tryAtomicReplaceExpired(emailKey, fresh, FIXED_NOW);

        assertTrue(result.isPresent());
        assertEquals("fresh-challenge", result.get().getChallengeId());
        assertEquals(0, result.get().getResendCount());
        assertEquals(0, result.get().getFailedVerifyAttempts());

        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).findAndModify(
                queryCaptor.capture(),
                updateCaptor.capture(),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class));

        org.bson.Document queryDoc = queryCaptor.getValue().getQueryObject();
        assertEquals(emailKey, queryDoc.get("_id"));
        assertTrue(queryDoc.containsKey("challengeExpiresAt"));

        org.bson.Document set =
                (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertEquals(OtpChallengeStatus.ACTIVE, set.get("status"));
        assertEquals("fresh-challenge", set.get("challengeId"));
        assertEquals(0, set.get("resendCount"));
        assertEquals(0, set.get("failedVerifyAttempts"));
        assertEquals(fresh.getOtpDigest(), set.get("otpDigest"));
        assertEquals(fresh.getNextResendAt(), set.get("nextResendAt"));
        assertEquals(fresh.getOtpExpiresAt(), set.get("otpExpiresAt"));
        assertEquals(fresh.getChallengeExpiresAt(), set.get("challengeExpiresAt"));
        assertEquals(fresh.getCreatedAt(), set.get("createdAt"));
        assertEquals(fresh.getUpdatedAt(), set.get("updatedAt"));
    }

    @Test
    void tryInsertOrReplaceExpiredFallsBackToReplaceWhenInsertConflicts() {
        SignupOtpChallenge challenge = persistence.buildNewActiveChallenge(
                "member@example.com", persistence.digestOtp("111111"), "c-replace");
        when(mongoTemplate.insert(challenge))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        when(mongoTemplate.findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class)))
                .thenReturn(challenge);

        assertTrue(persistence.tryInsertOrReplaceExpired(challenge));
        verify(mongoTemplate).insert(challenge);
        verify(mongoTemplate).findAndModify(
                any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(SignupOtpChallenge.class));
    }

    private SignupOtpChallenge baseChallenge(String emailKey) {
        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setEmailKey(emailKey);
        challenge.setStatus(OtpChallengeStatus.ACTIVE);
        challenge.setResendCount(0);
        challenge.setFailedVerifyAttempts(0);
        challenge.setNextResendAt(FIXED_NOW.minusSeconds(1));
        challenge.setChallengeExpiresAt(FIXED_NOW.plusSeconds(600));
        challenge.setOtpExpiresAt(FIXED_NOW.plusSeconds(300));
        return challenge;
    }
}
