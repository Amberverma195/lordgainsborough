package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.dto.PasswordResetResponse;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T14:00:00Z");

    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static class InMemoryPasswordResetOtpChallengeStore implements PasswordResetOtpChallengeStore {
        final Map<String, PasswordResetOtpChallenge> challenges = new ConcurrentHashMap<>();
        final Map<String, String> userEmails = new ConcurrentHashMap<>();

        private PasswordResetOtpChallenge copy(PasswordResetOtpChallenge src) {
            PasswordResetOtpChallenge c = new PasswordResetOtpChallenge();
            c.setEmailKey(src.getEmailKey());
            c.setChallengeId(src.getChallengeId());
            c.setUserId(src.getUserId());
            c.setPurpose(src.getPurpose());
            c.setStatus(src.getStatus());
            c.setCodeDigest(src.getCodeDigest());
            c.setCodeExpiresAt(src.getCodeExpiresAt());
            c.setChallengeExpiresAt(src.getChallengeExpiresAt());
            c.setNextResendAt(src.getNextResendAt());
            c.setResendCount(src.getResendCount());
            c.setFailedVerificationAttempts(src.getFailedVerificationAttempts());
            c.setClientIdempotencyKey(src.getClientIdempotencyKey());
            c.setSendOperationId(src.getSendOperationId());
            c.setSendStatus(src.getSendStatus());
            c.setSendLeaseUntil(src.getSendLeaseUntil());
            c.setResetOperationId(src.getResetOperationId());
            c.setPasswordUpdated(src.isPasswordUpdated());
            c.setConsumedAt(src.getConsumedAt());
            c.setCreatedAt(src.getCreatedAt());
            c.setUpdatedAt(src.getUpdatedAt());
            return c;
        }

        @Override
        public Optional<PasswordResetOtpChallenge> find(String emailKey, String challengeId) {
            PasswordResetOtpChallenge ch = challenges.get(emailKey);
            if (ch != null && challengeId.equals(ch.getChallengeId())) {
                return Optional.of(copy(ch));
            }
            return Optional.empty();
        }

        @Override
        public Optional<PasswordResetOtpChallenge> findByEmailKey(String emailKey) {
            PasswordResetOtpChallenge ch = challenges.get(emailKey);
            return ch != null ? Optional.of(copy(ch)) : Optional.empty();
        }

        @Override
        public boolean tryInsert(PasswordResetOtpChallenge challenge) {
            return challenges.putIfAbsent(challenge.getEmailKey(), copy(challenge)) == null;
        }

        @Override
        public Optional<PasswordResetOtpChallenge> tryAtomicReplaceExpired(PasswordResetOtpChallenge challenge, Instant now) {
            PasswordResetOtpChallenge existing = challenges.get(challenge.getEmailKey());
            if (existing != null && (existing.getChallengeExpiresAt() == null
                    || !existing.getChallengeExpiresAt().isAfter(now)
                    || existing.getStatus() == PasswordResetChallengeStatus.CONSUMED)) {
                challenges.put(challenge.getEmailKey(), copy(challenge));
                return Optional.of(copy(challenge));
            }
            return Optional.empty();
        }

        @Override
        public Optional<PasswordResetOtpChallenge> tryReserveResend(
                String emailKey,
                String challengeId,
                String newDigest,
                String clientIdempotencyKey,
                String sendOperationId,
                Instant now) {
            PasswordResetOtpChallenge ch = challenges.get(emailKey);
            if (ch == null || !challengeId.equals(ch.getChallengeId())) return Optional.empty();
            if (ch.getStatus() != PasswordResetChallengeStatus.ACTIVE) return Optional.empty();
            if (ch.getChallengeExpiresAt() != null && !ch.getChallengeExpiresAt().isAfter(now)) return Optional.empty();
            if (ch.getNextResendAt() != null && ch.getNextResendAt().isAfter(now)) return Optional.empty();
            if (ch.getResendCount() >= 5) return Optional.empty();

            ch.setCodeDigest(newDigest);
            Instant cappedCodeExpiry = ch.getChallengeExpiresAt() != null && now.plus(Duration.ofMinutes(10)).isAfter(ch.getChallengeExpiresAt())
                    ? ch.getChallengeExpiresAt()
                    : now.plus(Duration.ofMinutes(10));
            ch.setCodeExpiresAt(cappedCodeExpiry);
            ch.setNextResendAt(now.plus(Duration.ofSeconds(30)));
            ch.setResendCount(ch.getResendCount() + 1);
            ch.setClientIdempotencyKey(clientIdempotencyKey);
            ch.setSendOperationId(sendOperationId);
            ch.setSendStatus(OtpSendStatus.RESERVED);
            ch.setUpdatedAt(now);
            return Optional.of(copy(ch));
        }

        @Override
        public Optional<PasswordResetOtpChallenge> markSendOutcome(String emailKey, String sendOperationId, OtpSendStatus outcome, Instant now) {
            PasswordResetOtpChallenge ch = challenges.get(emailKey);
            if (ch != null && sendOperationId.equals(ch.getSendOperationId())) {
                ch.setSendStatus(outcome);
                ch.setUpdatedAt(now);
                return Optional.of(copy(ch));
            }
            return Optional.empty();
        }

        @Override
        public Optional<PasswordResetOtpChallenge> expireAfterDefiniteInitialFailure(String emailKey, String sendOperationId, Instant now) {
            PasswordResetOtpChallenge ch = challenges.get(emailKey);
            if (ch != null) {
                ch.setChallengeExpiresAt(now.minusSeconds(1));
                ch.setUpdatedAt(now);
                return Optional.of(copy(ch));
            }
            return Optional.empty();
        }

        @Override
        public Optional<PasswordResetOtpChallenge> recordFailedVerification(String emailKey, String challengeId, String currentDigest, Instant now) {
            return Optional.empty();
        }

        @Override
        public Optional<PasswordResetOtpChallenge> lockAfterFailures(String emailKey, String challengeId, Instant now) {
            return Optional.empty();
        }

        @Override
        public Optional<PasswordResetOtpChallenge> tryClaimCorrectCode(String emailKey, String challengeId, String currentDigest, String resetOperationId, Instant now) {
            return Optional.empty();
        }

        @Override
        public Optional<PasswordResetOtpChallenge> finalizeConsumed(String emailKey, String challengeId, String resetOperationId, Instant now) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findUserEmailById(String userId) {
            return Optional.ofNullable(userEmails.get(userId));
        }

        @Override
        public PasswordResetExecutionResult executePasswordResetTransaction(String emailKey, String challengeId, String expectedDigest, String resetOperationId, String rawPassword, Instant now) {
            return PasswordResetExecutionResult.success(new PasswordResetOtpChallenge());
        }
    }

    private MutableClock clock;
    private PasswordResetProperties properties;
    private AbuseIpHasher hasher;
    private InMemoryPasswordResetOtpChallengeStore store;
    private PasswordResetOtpChallengePersistence persistence;
    private PasswordResetAbuseLimiter abuseLimiter;
    private PasswordResetPublicHandleService handleService;
    private EmailSenderService emailSenderService;
    private AuthSecurityObservability observability;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(FIXED_NOW);
        properties = new PasswordResetProperties();
        properties.setCodeTtl(Duration.ofMinutes(10));
        properties.setChallengeTtl(Duration.ofMinutes(30));
        properties.setResendCooldown(Duration.ofSeconds(30));
        properties.setMaxResends(5);
        properties.setMinRemainingChallengeLifetime(Duration.ofMinutes(1));
        properties.setEmailOperationsPerIp(100);
        properties.setNewChallengesPerEmail(10);

        SignupAbuseProperties abuseProperties = new SignupAbuseProperties();
        abuseProperties.setIpHashSecret("test-secret-salt-key-12345");
        hasher = new AbuseIpHasher(abuseProperties);

        store = new InMemoryPasswordResetOtpChallengeStore();
        persistence = new PasswordResetOtpChallengePersistence(store, properties, hasher, clock);
        abuseLimiter = new PasswordResetAbuseLimiter(new InMemoryAbuseCounterStore(), properties, hasher, clock);
        handleService = new PasswordResetPublicHandleService(hasher);
        emailSenderService = mock(EmailSenderService.class);
        doNothing().when(emailSenderService).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
        observability = new AuthSecurityObservability(new SimpleMeterRegistry());

        service = new PasswordResetService(
                persistence,
                abuseLimiter,
                handleService,
                properties,
                emailSenderService,
                hasher,
                observability);
    }

    @Test
    @DisplayName("Account enumeration prevention: Email response sequences for registered vs nonexistent accounts are externally indistinguishable")
    void accountEnumerationIndistinguishableEmail() {
        String regEmail = "alice@example.com";
        String regUserId = "usr-alice";
        store.userEmails.put(regUserId, regEmail);
        String nonEmail = "nobody@example.com";

        // Step 1: Initial request
        PasswordResetService.Result reg1 = service.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-001");
        PasswordResetService.Result non1 = service.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-001");

        assertEquals(HttpStatus.ACCEPTED, reg1.status());
        assertEquals(HttpStatus.ACCEPTED, non1.status());
        assertEquals("RESET_CODE_SENT", reg1.body().getCode());
        assertEquals("RESET_CODE_SENT", non1.body().getCode());
        assertEquals(reg1.body().getMessage(), non1.body().getMessage());
        assertEquals(5, reg1.body().getResendsRemaining());
        assertEquals(5, non1.body().getResendsRemaining());
        assertNotNull(reg1.body().getChallengeId());
        assertNotNull(non1.body().getChallengeId());
        verify(emailSenderService, times(1)).sendPasswordResetCodeEmail(eq(regEmail), anyString(), anyString(), any());

        // Step 2: Idempotent replay with identical Idempotency-Key within challenge lifetime
        // Both return 202 ACCEPTED with RESET_CHALLENGE_REUSED, same challenge handle, no new email
        clock.advance(Duration.ofSeconds(5));
        PasswordResetService.Result regReplay = service.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-001");
        PasswordResetService.Result nonReplay = service.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-001");

        assertEquals(HttpStatus.ACCEPTED, regReplay.status());
        assertEquals(HttpStatus.ACCEPTED, nonReplay.status());
        assertEquals("RESET_CHALLENGE_REUSED", regReplay.body().getCode());
        assertEquals("RESET_CHALLENGE_REUSED", nonReplay.body().getCode());
        assertEquals(regReplay.body().getMessage(), nonReplay.body().getMessage());
        assertEquals(reg1.body().getChallengeId(), regReplay.body().getChallengeId());
        assertEquals(non1.body().getChallengeId(), nonReplay.body().getChallengeId());
        // Verify NO additional email was sent on replay
        verify(emailSenderService, times(1)).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());

        // Step 3: Request with NEW Idempotency-Key during 30s cooldown
        // Both return 429 TOO_MANY_REQUESTS with RESET_CODE_RESEND_COOLDOWN and matching retryAfterSeconds (25s)
        PasswordResetService.Result regCooldown = service.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-002");
        PasswordResetService.Result nonCooldown = service.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-002");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, regCooldown.status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, nonCooldown.status());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", regCooldown.body().getCode());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", nonCooldown.body().getCode());
        assertEquals(regCooldown.body().getMessage(), nonCooldown.body().getMessage());
        assertEquals(25L, regCooldown.retryAfterSeconds());
        assertEquals(25L, nonCooldown.retryAfterSeconds());

        // Step 4: Advance clock past cooldown (30s) -> requesting initial with new key delivers new code and returns 202
        clock.advance(Duration.ofSeconds(30));
        PasswordResetService.Result regResend = service.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-003");
        PasswordResetService.Result nonResend = service.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-003");

        assertEquals(HttpStatus.ACCEPTED, regResend.status());
        assertEquals(HttpStatus.ACCEPTED, nonResend.status());
        assertEquals("RESET_CODE_SENT", regResend.body().getCode());
        assertEquals("RESET_CODE_SENT", nonResend.body().getCode());
        assertEquals(4, regResend.body().getResendsRemaining());
        assertEquals(4, nonResend.body().getResendsRemaining());

        // Step 5: Advance past max resends (5 resends total)
        for (int i = 2; i <= 5; i++) {
            clock.advance(Duration.ofSeconds(35));
            service.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-loop-00" + i);
            service.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-loop-00" + i);
        }

        // Step 6: Next resend exceeds max resends -> both return 429 RESET_CODE_RESEND_LIMIT_REACHED
        clock.advance(Duration.ofSeconds(35));
        PasswordResetService.Result regExhausted = service.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-exhaust");
        PasswordResetService.Result nonExhausted = service.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-exhaust");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, regExhausted.status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, nonExhausted.status());
        assertEquals("RESET_CODE_RESEND_LIMIT_REACHED", regExhausted.body().getCode());
        assertEquals("RESET_CODE_RESEND_LIMIT_REACHED", nonExhausted.body().getCode());
        assertEquals(regExhausted.body().getMessage(), nonExhausted.body().getMessage());
        assertEquals(regExhausted.retryAfterSeconds(), nonExhausted.retryAfterSeconds());
    }

    @Test
    @DisplayName("Account enumeration prevention: Phone number response sequences are externally indistinguishable")
    void accountEnumerationIndistinguishablePhone() {
        String regPhone = "4165551234";
        String regUserId = "usr-phone-user";
        String regEmail = "phone.user@example.com";
        store.userEmails.put(regUserId, regEmail);
        String nonPhone = "4165559999";

        // Step 1: Initial request
        PasswordResetService.Result reg1 = service.requestInitial(regPhone, regUserId, regEmail, "192.168.1.1", "p-idemp-1");
        PasswordResetService.Result non1 = service.requestInitial(nonPhone, null, null, "192.168.1.2", "p-idemp-1");

        assertEquals(HttpStatus.ACCEPTED, reg1.status());
        assertEquals(HttpStatus.ACCEPTED, non1.status());
        assertEquals("RESET_CODE_SENT", reg1.body().getCode());
        assertEquals("RESET_CODE_SENT", non1.body().getCode());

        // Step 2: During cooldown
        clock.advance(Duration.ofSeconds(10));
        PasswordResetService.Result regCooldown = service.requestInitial(regPhone, regUserId, regEmail, "192.168.1.1", "p-idemp-2");
        PasswordResetService.Result nonCooldown = service.requestInitial(nonPhone, null, null, "192.168.1.2", "p-idemp-2");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, regCooldown.status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, nonCooldown.status());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", regCooldown.body().getCode());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", nonCooldown.body().getCode());
        assertEquals(20L, regCooldown.retryAfterSeconds());
        assertEquals(20L, nonCooldown.retryAfterSeconds());

        // Step 3: Identical replay returns RESET_CHALLENGE_REUSED
        PasswordResetService.Result regReplay = service.requestInitial(regPhone, regUserId, regEmail, "192.168.1.1", "p-idemp-1");
        PasswordResetService.Result nonReplay = service.requestInitial(nonPhone, null, null, "192.168.1.2", "p-idemp-1");

        assertEquals(HttpStatus.ACCEPTED, regReplay.status(), () -> "regReplay returned: " + regReplay.status() + " " + (regReplay.body() != null ? regReplay.body().getCode() + " msg=" + regReplay.body().getMessage() : "null"));
        assertEquals(HttpStatus.ACCEPTED, nonReplay.status());
        assertEquals("RESET_CHALLENGE_REUSED", regReplay.body().getCode());
        assertEquals("RESET_CHALLENGE_REUSED", nonReplay.body().getCode());
    }

    @Test
    @DisplayName("Fake-clock: Remaining session lifetimes of 15m, 9m, 3m, and 30s enforce bounded OTP validity and server-side expiry")
    void fakeClockRemainingSessionLifetimes() {
        ArgumentCaptor<Duration> validityCaptor = ArgumentCaptor.forClass(Duration.class);
        String email = "lifetimes@example.com";
        String userId = "usr-lifetime";
        store.userEmails.put(userId, email);

        // Start challenge at T=0 (challenge TTL = 30m)
        PasswordResetService.Result init = service.requestInitial(email, userId, email, "192.168.1.10", "init-lifetime");
        assertEquals(HttpStatus.ACCEPTED, init.status());
        String handle = init.body().getChallengeId();

        // 1. Session lifetime remaining: 15 minutes (advance 15 minutes)
        // 15m remaining > 10m code TTL -> code valid for 10 minutes
        clock.advance(Duration.ofMinutes(15));
        PasswordResetService.Result res15m = service.resend(email, handle, "192.168.1.10", "resend-15m");
        assertEquals(HttpStatus.ACCEPTED, res15m.status());
        assertEquals(Duration.ofMinutes(10), Duration.between(clock.instant(), res15m.body().getCodeExpiresAt()));
        verify(emailSenderService, atLeastOnce()).sendPasswordResetCodeEmail(
                eq(email), anyString(), anyString(), validityCaptor.capture());
        assertEquals("10 minutes", EmailSenderService.formatValidity(validityCaptor.getValue()));

        // 2. Session lifetime remaining: 9 minutes (advance 6 minutes, clock at 21m)
        // 9m remaining < 10m code TTL -> code expires at parent challenge deadline (9m)
        clock.advance(Duration.ofMinutes(6));
        reset(emailSenderService);
        PasswordResetService.Result res9m = service.resend(email, handle, "192.168.1.10", "resend-9m");
        assertEquals(HttpStatus.ACCEPTED, res9m.status());
        assertEquals(Duration.ofMinutes(9), Duration.between(clock.instant(), res9m.body().getCodeExpiresAt()));
        verify(emailSenderService).sendPasswordResetCodeEmail(
                eq(email), anyString(), anyString(), validityCaptor.capture());
        assertEquals("9 minutes", EmailSenderService.formatValidity(validityCaptor.getValue()));

        // 3. Session lifetime remaining: 3 minutes (advance 6 minutes, clock at 27m)
        // 3m remaining < 10m code TTL -> code expires at parent challenge deadline (3m)
        clock.advance(Duration.ofMinutes(6));
        reset(emailSenderService);
        PasswordResetService.Result res3m = service.resend(email, handle, "192.168.1.10", "resend-3m");
        assertEquals(HttpStatus.ACCEPTED, res3m.status());
        assertEquals(Duration.ofMinutes(3), Duration.between(clock.instant(), res3m.body().getCodeExpiresAt()));
        verify(emailSenderService).sendPasswordResetCodeEmail(
                eq(email), anyString(), anyString(), validityCaptor.capture());
        assertEquals("3 minutes", EmailSenderService.formatValidity(validityCaptor.getValue()));

        // 4. Session lifetime remaining: 30 seconds (advance 2m 30s, clock at 29m 30s)
        // 30s remaining < 1m min remaining challenge lifetime -> rejected with 410 RESET_CHALLENGE_EXPIRED
        clock.advance(Duration.ofMinutes(2).plusSeconds(30));
        PasswordResetService.Result res30s = service.resend(email, handle, "192.168.1.10", "resend-30s");
        assertEquals(HttpStatus.GONE, res30s.status());
        assertEquals("RESET_CHALLENGE_EXPIRED", res30s.body().getCode());
    }

    @Test
    @DisplayName("Delivery definite failure expires challenge immediately allowing retry without 30m lock")
    void definiteDeliveryFailureAllowsImmediateRetry() {
        String email = "fail@example.com";
        String userId = "usr-fail";
        store.userEmails.put(userId, email);

        doThrow(new RuntimeException("Simulated provider rejection"))
                .when(emailSenderService).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());

        PasswordResetService.Result failResult = service.requestInitial(email, userId, email, "192.168.1.20", "idemp-fail-1");
        assertEquals(HttpStatus.ACCEPTED, failResult.status());

        // In store, send status is DEFINITELY_FAILED and challenge is expired
        PasswordResetOtpChallenge ch = store.findByEmailKey(persistence.emailKey(email)).orElseThrow();
        assertEquals(OtpSendStatus.DEFINITELY_FAILED, ch.getSendStatus());
        assertFalse(ch.getChallengeExpiresAt().isAfter(clock.instant()));

        // Immediate retry succeeds
        doNothing().when(emailSenderService).sendPasswordResetCodeEmail(anyString(), anyString(), anyString(), any());
        PasswordResetService.Result retryResult = service.requestInitial(email, userId, email, "192.168.1.20", "idemp-fail-2");
        assertEquals(HttpStatus.ACCEPTED, retryResult.status());
        PasswordResetOtpChallenge retriedCh = store.findByEmailKey(persistence.emailKey(email)).orElseThrow();
        assertEquals(OtpSendStatus.SENT, retriedCh.getSendStatus());
    }

    @Test
    @DisplayName("Restart safety: Unknown-account cooldowns and replays survive backend restarts identically to registered accounts")
    void restartSafetyPreservesCooldownAndResendLimitsForBothKnownAndUnknownAccounts() {
        String regEmail = "restart-reg@example.com";
        String regUserId = "usr-restart-reg";
        store.userEmails.put(regUserId, regEmail);
        String nonEmail = "restart-non@example.com";

        // Step 1: Initial request on service before restart
        PasswordResetService.Result reg1 = service.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-rst-001");
        PasswordResetService.Result non1 = service.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-rst-001");

        assertEquals(HttpStatus.ACCEPTED, reg1.status());
        assertEquals(HttpStatus.ACCEPTED, non1.status());
        assertEquals("RESET_CODE_SENT", reg1.body().getCode());
        assertEquals("RESET_CODE_SENT", non1.body().getCode());

        // Step 2: Advance clock by 10s (during 30s cooldown)
        clock.advance(Duration.ofSeconds(10));

        // Step 3: Simulate backend restart: instantiate a fresh PasswordResetService connected to the same shared persistence store
        PasswordResetService serviceAfterRestart = new PasswordResetService(
                persistence,
                abuseLimiter,
                handleService,
                properties,
                emailSenderService,
                hasher,
                observability);

        // Step 4: Cooldown survives restart for BOTH accounts with identical status (429) and retryAfterSeconds (20s)
        PasswordResetService.Result regCooldown = serviceAfterRestart.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-rst-002");
        PasswordResetService.Result nonCooldown = serviceAfterRestart.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-rst-002");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, regCooldown.status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, nonCooldown.status());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", regCooldown.body().getCode());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", nonCooldown.body().getCode());
        assertEquals(20L, regCooldown.retryAfterSeconds());
        assertEquals(20L, nonCooldown.retryAfterSeconds());
        assertEquals(regCooldown.body().getMessage(), nonCooldown.body().getMessage());

        // Step 5: Idempotent replay of initial request also survives restart
        PasswordResetService.Result regReplay = serviceAfterRestart.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-rst-001");
        PasswordResetService.Result nonReplay = serviceAfterRestart.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-rst-001");

        assertEquals(HttpStatus.ACCEPTED, regReplay.status());
        assertEquals(HttpStatus.ACCEPTED, nonReplay.status());
        assertEquals("RESET_CHALLENGE_REUSED", regReplay.body().getCode());
        assertEquals("RESET_CHALLENGE_REUSED", nonReplay.body().getCode());
        assertEquals(reg1.body().getChallengeId(), regReplay.body().getChallengeId());
        assertEquals(non1.body().getChallengeId(), nonReplay.body().getChallengeId());

        // Step 6: Advance clock past 30s cooldown -> both deliver next code and decrement resendsRemaining to 4
        clock.advance(Duration.ofSeconds(25));
        PasswordResetService.Result regResend = serviceAfterRestart.requestInitial(regEmail, regUserId, regEmail, "192.168.1.1", "idemp-rst-003");
        PasswordResetService.Result nonResend = serviceAfterRestart.requestInitial(nonEmail, null, null, "192.168.1.2", "idemp-rst-003");

        assertEquals(HttpStatus.ACCEPTED, regResend.status());
        assertEquals(HttpStatus.ACCEPTED, nonResend.status());
        assertEquals("RESET_CODE_SENT", regResend.body().getCode());
        assertEquals("RESET_CODE_SENT", nonResend.body().getCode());
        assertEquals(4, regResend.body().getResendsRemaining());
        assertEquals(4, nonResend.body().getResendsRemaining());
    }

    @Test
    @DisplayName("Multi-server safety: Challenge metadata and rate limiting are shared across multiple service instances")
    void multiInstanceConcurrencySafetyAcrossNodes() {
        String regEmail = "multi-reg@example.com";
        String regUserId = "usr-multi-reg";
        store.userEmails.put(regUserId, regEmail);
        String nonEmail = "multi-non@example.com";

        // Create two service instances simulating two application server nodes sharing the same database
        PasswordResetService node1 = new PasswordResetService(
                persistence, abuseLimiter, handleService, properties, emailSenderService, hasher, observability);
        PasswordResetService node2 = new PasswordResetService(
                persistence, abuseLimiter, handleService, properties, emailSenderService, hasher, observability);

        // Initial request on node1
        PasswordResetService.Result reg1 = node1.requestInitial(regEmail, regUserId, regEmail, "192.168.1.10", "idemp-node1-1");
        PasswordResetService.Result non1 = node1.requestInitial(nonEmail, null, null, "192.168.1.20", "idemp-node1-1");
        assertEquals(HttpStatus.ACCEPTED, reg1.status());
        assertEquals(HttpStatus.ACCEPTED, non1.status());

        // Subsequent request routed by load balancer to node2 during cooldown (after 5s)
        clock.advance(Duration.ofSeconds(5));
        PasswordResetService.Result reg2 = node2.requestInitial(regEmail, regUserId, regEmail, "192.168.1.10", "idemp-node2-2");
        PasswordResetService.Result non2 = node2.requestInitial(nonEmail, null, null, "192.168.1.20", "idemp-node2-2");

        // Both node2 calls enforce cooldown identically
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, reg2.status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, non2.status());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", reg2.body().getCode());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", non2.body().getCode());
        assertEquals(25L, reg2.retryAfterSeconds());
        assertEquals(25L, non2.retryAfterSeconds());

        // Resend on node2 via public handle
        clock.advance(Duration.ofSeconds(30));
        PasswordResetService.Result regResend = node2.resend(regEmail, reg1.body().getChallengeId(), "192.168.1.10", "idemp-resend-n2");
        PasswordResetService.Result nonResend = node2.resend(nonEmail, non1.body().getChallengeId(), "192.168.1.20", "idemp-resend-n2");
        assertEquals(HttpStatus.ACCEPTED, regResend.status());
        assertEquals(HttpStatus.ACCEPTED, nonResend.status());
        assertEquals(4, regResend.body().getResendsRemaining());
        assertEquals(4, nonResend.body().getResendsRemaining());
    }

    @Test
    @DisplayName("Bounded storage: Shared store contains at most one active challenge per email and replaces expired challenges without accumulation")
    void boundedStoreGuaranteesAtMostOneActiveChallengePerEmailAndZeroMemoryAccumulation() {
        for (int i = 1; i <= 5; i++) {
            String fakeEmail = "bounded-test-" + i + "@example.com";
            PasswordResetService.Result res = service.requestInitial(fakeEmail, null, null, "192.168.1.30", "idemp-bnd-001");
            assertEquals(HttpStatus.ACCEPTED, res.status());
        }

        // 5 distinct emails produced exactly 5 entries in store
        assertEquals(5, store.challenges.size());

        // Advance past challenge TTL (30 minutes)
        clock.advance(Duration.ofMinutes(35));

        // Start new resets for the same 5 emails
        for (int i = 1; i <= 5; i++) {
            String fakeEmail = "bounded-test-" + i + "@example.com";
            PasswordResetService.Result res = service.requestInitial(fakeEmail, null, null, "192.168.1.30", "idemp-bnd-002");
            assertEquals(HttpStatus.ACCEPTED, res.status());
        }

        // Store still contains exactly 5 entries (atomic replace expired, bounded storage, zero memory accumulation)
        assertEquals(5, store.challenges.size());
    }
}
