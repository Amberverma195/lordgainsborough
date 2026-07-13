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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupOtpChallengeIdempotencyTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T09:00:00Z");
    private static final String EMAIL = "idempotency.user@example.com";

    @Mock
    private SignupOtpChallengeRepository repository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private EmailSenderService emailSenderService;
    @Mock
    private SignupOtpObservability observability;

    private final ConcurrentHashMap<String, SignupOtpChallenge> store = new ConcurrentHashMap<>();
    private final AtomicInteger emailsSent = new AtomicInteger();
    private SignupOtpChallengePersistence persistence;
    private SignupOtpChallengeService service;
    private String emailKey;

    @BeforeEach
    void setUp() {
        store.clear();
        emailsSent.set(0);

        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        persistence = new SignupOtpChallengePersistence(repository, mongoTemplate, clock);
        service = new SignupOtpChallengeService(persistence, emailSenderService, observability);
        emailKey = persistence.emailKeyFromNormalized(EMAIL);

        when(repository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(clone(store.get(inv.getArgument(0)))));

        when(mongoTemplate.insert(any(SignupOtpChallenge.class))).thenAnswer(inv -> {
            SignupOtpChallenge challenge = inv.getArgument(0);
            SignupOtpChallenge existing = store.putIfAbsent(challenge.getEmailKey(), clone(challenge));
            if (existing != null) {
                throw new DuplicateKeyException("dup");
            }
            return challenge;
        });

        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(SignupOtpChallenge.class))).thenAnswer(inv -> applyFindAndModify(
                inv.getArgument(0), inv.getArgument(1)));

        org.mockito.Mockito.lenient().doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(repository).deleteById(anyString());
    }

    @Test
    void repeatedIdenticalIdempotencyKeyDoesNotRotateOrReEmail() {
        doAnswer(inv -> {
            emailsSent.incrementAndGet();
            return null;
        }).when(emailSenderService).sendOtpEmail(anyString(), anyString(), any());

        String key = "same-click-" + UUID.randomUUID();
        var first = service.requestSignupOtp(EMAIL, key);
        var second = service.requestSignupOtp(EMAIL, key);

        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, first);
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, second);
        assertEquals(1, emailsSent.get());
        assertEquals(0, store.get(emailKey).getResendCount());
        assertEquals(OtpSendStatus.SENT, store.get(emailKey).getSendStatus());
        assertEquals(key, store.get(emailKey).getClientIdempotencyKey());
        verify(emailSenderService, times(1)).sendOtpEmail(anyString(), anyString(), any());
        verify(observability).record(
                eq(SignupOtpObservability.Event.INITIAL_SEND_ACCEPTED),
                any(SignupOtpObservability.Context.class));
        verify(observability).record(
                eq(SignupOtpObservability.Event.IDEMPOTENCY_REPLAY),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void providerDefiniteRejectionMarksFailedAndReplaysWithoutReEmail() {
        doThrow(new DefiniteEmailRejectionException("rejected"))
                .when(emailSenderService).sendOtpEmail(anyString(), anyString(), any());

        String key = "reject-key";
        var first = service.requestSignupOtp(EMAIL, key);
        var second = service.requestSignupOtp(EMAIL, key);

        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Failed.class, first);
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Failed.class, second);
        assertEquals(OtpSendStatus.DEFINITELY_FAILED, store.get(emailKey).getSendStatus());
        verify(emailSenderService, times(1)).sendOtpEmail(anyString(), anyString(), any());
        verify(observability).record(
                eq(SignupOtpObservability.Event.PROVIDER_DEFINITE_FAILURE),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void providerTimeoutAfterPossibleAcceptanceKeepsChallengeAndDoesNotAutoResend() {
        doThrow(new AmbiguousEmailDeliveryException("timeout"))
                .when(emailSenderService).sendOtpEmail(anyString(), anyString(), any());

        String key = "timeout-key";
        var first = service.requestSignupOtp(EMAIL, key);
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.DeliveryUnknown.class, first);

        SignupOtpChallenge stored = store.get(emailKey);
        assertTrue(stored != null, "challenge must remain after ambiguous timeout");
        assertEquals(OtpSendStatus.DELIVERY_UNKNOWN, stored.getSendStatus());
        assertTrue(stored.getChallengeExpiresAt().isAfter(FIXED_NOW));
        assertEquals(0, stored.getResendCount());

        var replay = service.requestSignupOtp(EMAIL, key);
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.DeliveryUnknown.class, replay);
        verify(emailSenderService, times(1)).sendOtpEmail(anyString(), anyString(), any());
        verify(observability).record(
                eq(SignupOtpObservability.Event.PROVIDER_TIMEOUT),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void responseLossFollowedByClientRetryReplaysSentWithoutSecondEmail() {
        doAnswer(inv -> {
            emailsSent.incrementAndGet();
            return null;
        }).when(emailSenderService).sendOtpEmail(anyString(), anyString(), any());

        String key = "lost-response-key";
        var first = service.requestSignupOtp(EMAIL, key);
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, first);
        // Simulate client never receiving the HTTP response, then retrying with the same key.
        var retry = service.requestSignupOtp(EMAIL, key);

        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, retry);
        assertEquals(((SignupOtpChallengeService.RequestOtpResult.Sent) first).initial(),
                ((SignupOtpChallengeService.RequestOtpResult.Sent) retry).initial());
        assertEquals(1, emailsSent.get());
        assertEquals(0, store.get(emailKey).getResendCount());
        verify(observability).record(
                eq(SignupOtpObservability.Event.IDEMPOTENCY_REPLAY),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void immediateDifferentKeyRecordsCooldownRejection() {
        doAnswer(inv -> null)
                .when(emailSenderService).sendOtpEmail(anyString(), anyString(), any());
        service.requestSignupOtp(EMAIL, "first-key");

        var rejected = service.requestSignupOtp(EMAIL, "different-key");

        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Denied.class, rejected);
        verify(observability).record(
                eq(SignupOtpObservability.Event.COOLDOWN_REJECTED),
                any(SignupOtpObservability.Context.class));
    }

    @Test
    void exhaustedChallengeRecordsResendLimitMetric() {
        doAnswer(inv -> null)
                .when(emailSenderService).sendOtpEmail(anyString(), anyString(), any());
        service.requestSignupOtp(EMAIL, "first-key");
        SignupOtpChallenge exhausted = store.get(emailKey);
        exhausted.setResendCount(SignupOtpChallengePersistence.MAX_RESENDS);
        exhausted.setNextResendAt(FIXED_NOW.minusSeconds(1));
        store.put(emailKey, exhausted);

        var rejected = service.resendOtp(EMAIL, "resend-after-limit");

        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Denied.class, rejected);
        verify(observability).record(
                eq(SignupOtpObservability.Event.RESEND_LIMIT_REACHED),
                any(SignupOtpObservability.Context.class));
    }

    private SignupOtpChallenge applyFindAndModify(Query query, Update update) {
        Document queryDoc = query.getQueryObject();
        Document updateDoc = update.getUpdateObject();
        String id = queryDoc.getString("_id");
        SignupOtpChallenge current = store.get(id);
        if (current == null) {
            return null;
        }

        Document set = (Document) updateDoc.get("$set");
        Document inc = (Document) updateDoc.get("$inc");

        if (set != null && set.get("sendStatus") != null && set.get("otpDigest") == null && inc == null) {
            // tryMarkSendOutcome / expireAfterDefiniteInitialFailure
            if (queryDoc.get("sendOperationId") != null
                    && !queryDoc.get("sendOperationId").equals(current.getSendOperationId())) {
                return null;
            }
            if (queryDoc.get("sendStatus") != null
                    && queryDoc.get("sendStatus") != current.getSendStatus()) {
                return null;
            }
            if (set.get("sendStatus") != null) {
                current.setSendStatus((OtpSendStatus) set.get("sendStatus"));
            }
            if (set.get("challengeExpiresAt") != null) {
                current.setChallengeExpiresAt((Instant) set.get("challengeExpiresAt"));
            }
            if (set.get("updatedAt") != null) {
                current.setUpdatedAt((Instant) set.get("updatedAt"));
            }
            store.put(id, current);
            return clone(current);
        }

        if (inc != null && inc.containsKey("resendCount")) {
            if (current.getStatus() != OtpChallengeStatus.ACTIVE
                    || !FIXED_NOW.isBefore(current.getChallengeExpiresAt())
                    || current.getResendCount() >= SignupOtpChallengePersistence.MAX_RESENDS
                    || current.getNextResendAt().isAfter(FIXED_NOW)) {
                return null;
            }
            current.setResendCount(current.getResendCount() + 1);
            current.setOtpDigest((String) set.get("otpDigest"));
            current.setOtpExpiresAt((Instant) set.get("otpExpiresAt"));
            current.setNextResendAt((Instant) set.get("nextResendAt"));
            current.setSendOperationId((String) set.get("sendOperationId"));
            current.setSendStatus((OtpSendStatus) set.get("sendStatus"));
            current.setSendLeaseUntil((Instant) set.get("sendLeaseUntil"));
            current.setClientIdempotencyKey((String) set.get("clientIdempotencyKey"));
            current.setLastSendWasInitial(Boolean.TRUE.equals(set.get("lastSendWasInitial")));
            current.setUpdatedAt((Instant) set.get("updatedAt"));
            store.put(id, current);
            return clone(current);
        }

        if (set != null && set.containsKey("challengeId")) {
            // replace expired
            Object expires = queryDoc.get("challengeExpiresAt");
            if (expires instanceof Document doc && doc.get("$lte") instanceof Instant threshold) {
                if (current.getChallengeExpiresAt().isAfter(threshold)) {
                    return null;
                }
            }
            applyFullReplace(current, set);
            store.put(id, current);
            return clone(current);
        }

        return null;
    }

    private static void applyFullReplace(SignupOtpChallenge current, Document set) {
        current.setNormalizedEmail((String) set.get("normalizedEmail"));
        current.setChallengeId((String) set.get("challengeId"));
        current.setStatus((OtpChallengeStatus) set.get("status"));
        current.setOtpDigest((String) set.get("otpDigest"));
        current.setResendCount(((Number) set.get("resendCount")).intValue());
        current.setFailedVerifyAttempts(((Number) set.get("failedVerifyAttempts")).intValue());
        current.setNextResendAt((Instant) set.get("nextResendAt"));
        current.setOtpExpiresAt((Instant) set.get("otpExpiresAt"));
        current.setChallengeExpiresAt((Instant) set.get("challengeExpiresAt"));
        current.setCreatedAt((Instant) set.get("createdAt"));
        current.setUpdatedAt((Instant) set.get("updatedAt"));
        current.setSendOperationId((String) set.get("sendOperationId"));
        current.setSendStatus((OtpSendStatus) set.get("sendStatus"));
        current.setSendLeaseUntil((Instant) set.get("sendLeaseUntil"));
        current.setClientIdempotencyKey((String) set.get("clientIdempotencyKey"));
        current.setLastSendWasInitial(Boolean.TRUE.equals(set.get("lastSendWasInitial")));
    }

    private static SignupOtpChallenge clone(SignupOtpChallenge source) {
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
        copy.setSendOperationId(source.getSendOperationId());
        copy.setSendStatus(source.getSendStatus());
        copy.setSendLeaseUntil(source.getSendLeaseUntil());
        copy.setClientIdempotencyKey(source.getClientIdempotencyKey());
        copy.setLastSendWasInitial(source.isLastSendWasInitial());
        return copy;
    }
}
