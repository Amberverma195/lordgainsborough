package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.repository.SignupOtpChallengeRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupOtpChallengeVerificationTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T07:00:00Z");
    private static final String EMAIL = "verify.user@example.com";
    private static final String CORRECT_OTP = "123456";
    private static final String WRONG_OTP = "000000";

    @Mock
    private SignupOtpChallengeRepository repository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private EmailSenderService emailSenderService;
    @Mock
    private SignupOtpObservability observability;

    private final ConcurrentHashMap<String, SignupOtpChallenge> store = new ConcurrentHashMap<>();
    private SignupOtpChallengePersistence persistence;
    private SignupOtpChallengeService service;
    private String emailKey;
    private String correctDigest;

    @BeforeEach
    void setUp() {
        store.clear();
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        persistence = new SignupOtpChallengePersistence(repository, mongoTemplate, clock);
        service = new SignupOtpChallengeService(persistence, emailSenderService, observability);
        emailKey = persistence.emailKeyFromNormalized(EMAIL);
        correctDigest = persistence.digestOtp(CORRECT_OTP);

        lenient().when(repository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(cloneChallenge(store.get(inv.getArgument(0)))));

        lenient().when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(SignupOtpChallenge.class))).thenAnswer(inv -> {
            synchronized (store) {
                return applyFindAndModify(inv.getArgument(0), inv.getArgument(1));
            }
        });
    }

    @Test
    void correctOtpMarksChallengeVerified() {
        seedActiveChallenge(correctDigest, 0);

        assertTrue(service.verifyAndConsume(EMAIL, CORRECT_OTP));
        assertEquals(OtpChallengeStatus.VERIFIED, store.get(emailKey).getStatus());
        verify(observability).record(
                eq(SignupOtpObservability.Event.VERIFICATION_SUCCESS),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void wrongOtpIncrementsFailedAttemptsAndDoesNotVerify() {
        seedActiveChallenge(correctDigest, 0);

        assertFalse(service.verifyAndConsume(EMAIL, WRONG_OTP));
        SignupOtpChallenge challenge = store.get(emailKey);
        assertEquals(OtpChallengeStatus.ACTIVE, challenge.getStatus());
        assertEquals(1, challenge.getFailedVerifyAttempts());
        verify(observability).record(
                eq(SignupOtpObservability.Event.VERIFICATION_FAILURE),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void expiredOtpIsRejected() {
        SignupOtpChallenge challenge = seedActiveChallenge(correctDigest, 0);
        challenge.setOtpExpiresAt(FIXED_NOW.minusSeconds(1));
        store.put(emailKey, challenge);

        assertFalse(service.verifyAndConsume(EMAIL, CORRECT_OTP));
        assertEquals(OtpChallengeStatus.ACTIVE, store.get(emailKey).getStatus());
        verify(observability).record(
                eq(SignupOtpObservability.Event.VERIFICATION_FAILURE),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void expiredChallengeIsRejected() {
        SignupOtpChallenge challenge = seedActiveChallenge(correctDigest, 0);
        challenge.setChallengeExpiresAt(FIXED_NOW.minusSeconds(1));
        store.put(emailKey, challenge);

        assertFalse(service.verifyAndConsume(EMAIL, CORRECT_OTP));
        assertEquals(OtpChallengeStatus.ACTIVE, store.get(emailKey).getStatus());
        verify(observability).record(
                eq(SignupOtpObservability.Event.CHALLENGE_EXPIRED),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void fiveWrongAttemptsLocksChallenge() {
        seedActiveChallenge(correctDigest, 0);

        for (int i = 0; i < SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS; i++) {
            assertFalse(service.verifyAndConsume(EMAIL, WRONG_OTP));
        }

        SignupOtpChallenge challenge = store.get(emailKey);
        assertEquals(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS,
                challenge.getFailedVerifyAttempts());
        assertEquals(OtpChallengeStatus.LOCKED, challenge.getStatus());
        assertFalse(service.verifyAndConsume(EMAIL, CORRECT_OTP));
        verify(observability, times(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS))
                .record(
                        eq(SignupOtpObservability.Event.VERIFICATION_FAILURE),
                        any(SignupOtpObservability.Context.class));
        verify(observability, times(2)).record(
                eq(SignupOtpObservability.Event.VERIFICATION_LOCK),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void verifiedOtpCannotBeReused() {
        seedActiveChallenge(correctDigest, 0);

        assertTrue(service.verifyAndConsume(EMAIL, CORRECT_OTP));
        assertFalse(service.verifyAndConsume(EMAIL, CORRECT_OTP));
        assertEquals(OtpChallengeStatus.VERIFIED, store.get(emailKey).getStatus());
    }

    @Test
    void previousOtpStopsWorkingAfterResend() {
        seedActiveChallenge(correctDigest, 0);
        String previousOtp = CORRECT_OTP;
        String newOtp = "654321";
        String newDigest = persistence.digestOtp(newOtp);

        SignupOtpChallenge afterResend = store.get(emailKey);
        afterResend.setOtpDigest(newDigest);
        afterResend.setResendCount(1);
        afterResend.setOtpExpiresAt(FIXED_NOW.plusSeconds(600));
        afterResend.setNextResendAt(FIXED_NOW.plusSeconds(30));
        store.put(emailKey, afterResend);

        assertFalse(service.verifyAndConsume(EMAIL, previousOtp));
        assertTrue(service.verifyAndConsume(EMAIL, newOtp));
    }

    @Test
    void clearDeletesChallengeAfterSuccessfulSignup() {
        seedActiveChallenge(correctDigest, 0);
        assertTrue(service.verifyAndConsume(EMAIL, CORRECT_OTP));

        org.mockito.Mockito.doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(repository).deleteById(anyString());

        service.clear(EMAIL);
        assertFalse(store.containsKey(emailKey));
    }

    @Test
    void validOtpRacingAgainstFifthInvalidAttemptIsLockedOnceCounterReachesMax() {
        seedActiveChallenge(correctDigest, SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS - 1);

        // 5th wrong attempt triggers lock
        SignupOtpChallengeService.VerifyOtpResult wrongResult = service.verifyOtp(EMAIL, WRONG_OTP);
        assertTrue(wrongResult instanceof SignupOtpChallengeService.VerifyOtpResult.Locked);

        // Concurrent/subsequent valid OTP verification cannot succeed
        SignupOtpChallengeService.VerifyOtpResult validResult = service.verifyOtp(EMAIL, CORRECT_OTP);
        assertTrue(validResult instanceof SignupOtpChallengeService.VerifyOtpResult.Locked);

        SignupOtpChallenge current = store.get(emailKey);
        assertEquals(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS, current.getFailedVerifyAttempts());
        assertEquals(OtpChallengeStatus.LOCKED, current.getStatus());
    }

    @Test
    void validOtpRejectedWhenAttemptsAlreadyAtMaxEvenIfStatusStillActive() {
        // Simulates the intermediate race window: 5 failed attempts reached while status is still ACTIVE
        SignupOtpChallenge challenge = seedActiveChallenge(correctDigest,
                SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS);
        challenge.setStatus(OtpChallengeStatus.ACTIVE);
        store.put(emailKey, challenge);

        SignupOtpChallengeService.VerifyOtpResult result = service.verifyOtp(EMAIL, CORRECT_OTP);
        assertTrue(result instanceof SignupOtpChallengeService.VerifyOtpResult.Locked);

        // Status must NOT have transitioned to VERIFIED
        assertNotEquals(OtpChallengeStatus.VERIFIED, store.get(emailKey).getStatus());
    }

    @Test
    void concurrentValidOtpAndFifthInvalidAttemptRaceNeverAllowsVerifiedAfterLock() throws Exception {
        seedActiveChallenge(correctDigest, SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS - 1);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);

        java.util.concurrent.Future<SignupOtpChallengeService.VerifyOtpResult> wrongFuture = executor.submit(() -> {
            barrier.await();
            return service.verifyOtp(EMAIL, WRONG_OTP);
        });

        java.util.concurrent.Future<SignupOtpChallengeService.VerifyOtpResult> validFuture = executor.submit(() -> {
            barrier.await();
            return service.verifyOtp(EMAIL, CORRECT_OTP);
        });

        SignupOtpChallengeService.VerifyOtpResult wrongRes = wrongFuture.get();
        SignupOtpChallengeService.VerifyOtpResult validRes = validFuture.get();
        executor.shutdown();

        SignupOtpChallenge current = store.get(emailKey);
        if (wrongRes instanceof SignupOtpChallengeService.VerifyOtpResult.Locked) {
            // If the 5th wrong attempt locked the challenge, valid OTP verification MUST NOT succeed
            assertFalse(validRes instanceof SignupOtpChallengeService.VerifyOtpResult.Verified);
            assertEquals(OtpChallengeStatus.LOCKED, current.getStatus());
        } else {
            // Valid OTP won before the 5th wrong attempt was processed
            assertTrue(validRes instanceof SignupOtpChallengeService.VerifyOtpResult.Verified);
            assertEquals(OtpChallengeStatus.VERIFIED, current.getStatus());
        }
    }

    @Test
    void concurrentResendAndFifthInvalidAttemptRaceNeverAllowsResendAfterLock() throws Exception {
        seedActiveChallenge(correctDigest, SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS - 1);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);

        java.util.concurrent.Future<SignupOtpChallengeService.VerifyOtpResult> wrongFuture = executor.submit(() -> {
            barrier.await();
            return service.verifyOtp(EMAIL, WRONG_OTP);
        });

        java.util.concurrent.Future<SignupOtpChallengeService.RequestOtpResult> resendFuture = executor.submit(() -> {
            barrier.await();
            return service.resendOtp(EMAIL, "concurrent-resend-key");
        });

        SignupOtpChallengeService.VerifyOtpResult wrongRes = wrongFuture.get();
        SignupOtpChallengeService.RequestOtpResult resendRes = resendFuture.get();
        executor.shutdown();

        SignupOtpChallenge current = store.get(emailKey);

        if (wrongRes instanceof SignupOtpChallengeService.VerifyOtpResult.Locked) {
            // The 5th wrong attempt atomically locked the challenge.
            // Any racing or subsequent resend MUST be denied as locked.
            assertTrue(resendRes instanceof SignupOtpChallengeService.RequestOtpResult.Denied denied
                    && denied.reason() == SignupOtpChallengePersistence.ResendDenialReason.CHALLENGE_LOCKED);
            assertEquals(OtpChallengeStatus.LOCKED, current.getStatus());
            assertEquals(SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS, current.getFailedVerifyAttempts());
        } else {
            // Resend won before the 5th wrong attempt locked the challenge.
            // In this case, resend rotated the digest, so the challenge has resendCount = 1.
            assertTrue(resendRes instanceof SignupOtpChallengeService.RequestOtpResult.Sent);
            assertEquals(1, current.getResendCount());
        }

        // Critical safety invariant: challenge must never be ACTIVE with 5 failed attempts
        assertFalse(current.getStatus() == OtpChallengeStatus.ACTIVE
                && current.getFailedVerifyAttempts() >= SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS);
    }

    private SignupOtpChallenge seedActiveChallenge(String otpDigest, int failedAttempts) {
        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setEmailKey(emailKey);
        challenge.setNormalizedEmail(EMAIL);
        challenge.setChallengeId("challenge-verify");
        challenge.setStatus(OtpChallengeStatus.ACTIVE);
        challenge.setOtpDigest(otpDigest);
        challenge.setResendCount(0);
        challenge.setFailedVerifyAttempts(failedAttempts);
        challenge.setNextResendAt(FIXED_NOW.minusSeconds(1));
        challenge.setOtpExpiresAt(FIXED_NOW.plusSeconds(600));
        challenge.setChallengeExpiresAt(FIXED_NOW.plusSeconds(1800));
        challenge.setCreatedAt(FIXED_NOW.minusSeconds(60));
        challenge.setUpdatedAt(FIXED_NOW.minusSeconds(60));
        store.put(emailKey, challenge);
        return challenge;
    }

    private SignupOtpChallenge applyFindAndModify(Query query, Update update) {
        Document queryDoc = query.getQueryObject();
        Document updateDoc = update.getUpdateObject();
        String id = queryDoc.getString("_id");
        SignupOtpChallenge current = store.get(id);
        if (current == null) {
            return null;
        }

        if (!matchesStatus(queryDoc, current)
                || !matchesInstantGt(queryDoc, "challengeExpiresAt", current.getChallengeExpiresAt())
                || !matchesInstantGt(queryDoc, "otpExpiresAt", current.getOtpExpiresAt())
                || !matchesDigest(queryDoc, current)
                || !matchesChallengeId(queryDoc, current)
                || !matchesFailedAttempts(queryDoc, current)
                || !matchesResendCountLt(queryDoc, current)
                || !matchesNextResendAtLte(queryDoc, current)) {
            return null;
        }

        Document set = (Document) updateDoc.get("$set");
        Document inc = (Document) updateDoc.get("$inc");

        if (set != null && set.get("status") == OtpChallengeStatus.VERIFIED) {
            current.setStatus(OtpChallengeStatus.VERIFIED);
            current.setUpdatedAt((Instant) set.get("updatedAt"));
            store.put(id, current);
            return cloneChallenge(current);
        }

        if (inc != null && inc.containsKey("failedVerifyAttempts")) {
            current.setFailedVerifyAttempts(
                    current.getFailedVerifyAttempts() + inc.getInteger("failedVerifyAttempts"));
            if (set != null && set.containsKey("status")) {
                current.setStatus((OtpChallengeStatus) set.get("status"));
            }
            if (set != null && set.get("updatedAt") != null) {
                current.setUpdatedAt((Instant) set.get("updatedAt"));
            }
            store.put(id, current);
            return cloneChallenge(current);
        }

        if (inc != null && inc.containsKey("resendCount")) {
            current.setResendCount(current.getResendCount() + inc.getInteger("resendCount"));
            if (set != null) {
                if (set.containsKey("otpDigest")) current.setOtpDigest(set.getString("otpDigest"));
                if (set.containsKey("otpExpiresAt")) current.setOtpExpiresAt((Instant) set.get("otpExpiresAt"));
                if (set.containsKey("nextResendAt")) current.setNextResendAt((Instant) set.get("nextResendAt"));
                if (set.containsKey("sendOperationId")) current.setSendOperationId(set.getString("sendOperationId"));
                if (set.containsKey("sendStatus")) current.setSendStatus((OtpSendStatus) set.get("sendStatus"));
                if (set.containsKey("sendLeaseUntil")) current.setSendLeaseUntil((Instant) set.get("sendLeaseUntil"));
                if (set.containsKey("clientIdempotencyKey")) current.setClientIdempotencyKey(set.getString("clientIdempotencyKey"));
                if (set.containsKey("lastSendWasInitial")) current.setLastSendWasInitial((Boolean) set.get("lastSendWasInitial"));
                if (set.containsKey("updatedAt")) current.setUpdatedAt((Instant) set.get("updatedAt"));
            }
            store.put(id, current);
            return cloneChallenge(current);
        }

        if (set != null && set.get("status") == OtpChallengeStatus.LOCKED) {
            current.setStatus(OtpChallengeStatus.LOCKED);
            current.setUpdatedAt((Instant) set.get("updatedAt"));
            store.put(id, current);
            return cloneChallenge(current);
        }

        return null;
    }

    private static boolean matchesStatus(Document queryDoc, SignupOtpChallenge current) {
        Object status = queryDoc.get("status");
        return status == null || status == current.getStatus();
    }

    private static boolean matchesInstantGt(Document queryDoc, String field, Instant actual) {
        if (!queryDoc.containsKey(field)) {
            return true;
        }
        Object raw = queryDoc.get(field);
        if (raw instanceof Document doc && doc.containsKey("$gt")) {
            Instant threshold = (Instant) doc.get("$gt");
            return actual != null && actual.isAfter(threshold);
        }
        return true;
    }

    private static boolean matchesDigest(Document queryDoc, SignupOtpChallenge current) {
        if (!queryDoc.containsKey("otpDigest")) {
            return true;
        }
        return queryDoc.getString("otpDigest").equals(current.getOtpDigest());
    }

    private static boolean matchesChallengeId(Document queryDoc, SignupOtpChallenge current) {
        if (!queryDoc.containsKey("challengeId")) {
            return true;
        }
        return queryDoc.getString("challengeId").equals(current.getChallengeId());
    }

    private static boolean matchesFailedAttempts(Document queryDoc, SignupOtpChallenge current) {
        if (!queryDoc.containsKey("failedVerifyAttempts")) {
            return true;
        }
        Object raw = queryDoc.get("failedVerifyAttempts");
        if (raw instanceof Number num) {
            return current.getFailedVerifyAttempts() == num.intValue();
        }
        if (raw instanceof Document doc) {
            if (doc.containsKey("$lt")) {
                int max = ((Number) doc.get("$lt")).intValue();
                if (current.getFailedVerifyAttempts() >= max) {
                    return false;
                }
            }
            if (doc.containsKey("$gte")) {
                int min = ((Number) doc.get("$gte")).intValue();
                if (current.getFailedVerifyAttempts() < min) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    private static boolean matchesResendCountLt(Document queryDoc, SignupOtpChallenge current) {
        if (!queryDoc.containsKey("resendCount")) {
            return true;
        }
        Object raw = queryDoc.get("resendCount");
        if (raw instanceof Document doc && doc.containsKey("$lt")) {
            int max = ((Number) doc.get("$lt")).intValue();
            return current.getResendCount() < max;
        }
        return true;
    }

    private static boolean matchesNextResendAtLte(Document queryDoc, SignupOtpChallenge current) {
        if (!queryDoc.containsKey("nextResendAt")) {
            return true;
        }
        Object raw = queryDoc.get("nextResendAt");
        if (raw instanceof Document doc && doc.containsKey("$lte")) {
            Instant threshold = (Instant) doc.get("$lte");
            return current.getNextResendAt() != null && !current.getNextResendAt().isAfter(threshold);
        }
        return true;
    }

    private static SignupOtpChallenge cloneChallenge(SignupOtpChallenge source) {
        if (source == null) {
            return null;
        }
        SignupOtpChallenge copy = new SignupOtpChallenge();
        copy.setEmailKey(source.getEmailKey());
        copy.setNormalizedEmail(source.getNormalizedEmail());
        copy.setChallengeId(source.getChallengeId());
        copy.setStatus(source.getStatus());
        copy.setOtpDigest(source.getOtpDigest());
        copy.setResendCount(source.getResendCount());
        copy.setFailedVerifyAttempts(source.getFailedVerifyAttempts());
        copy.setNextResendAt(source.getNextResendAt());
        copy.setOtpExpiresAt(source.getOtpExpiresAt());
        copy.setChallengeExpiresAt(source.getChallengeExpiresAt());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
