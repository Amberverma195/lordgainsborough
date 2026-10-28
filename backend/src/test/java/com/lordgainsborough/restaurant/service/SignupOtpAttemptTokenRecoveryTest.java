package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.dto.SignupOtpChallengeView;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupOtpAttemptTokenRecoveryTest {

    @Mock
    private SignupOtpChallengePersistence persistence;

    @Mock
    private EmailSenderService emailSenderService;

    @Mock
    private SignupOtpObservability observability;

    private static final String SECRET_V1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SECRET_V2 = "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4";

    @BeforeEach
    void setUp() {
        lenient().when(persistence.normalizeEmail(anyString())).thenAnswer(invocation -> {
            String arg = invocation.getArgument(0);
            return arg == null ? "" : arg.trim().toLowerCase();
        });
        lenient().when(persistence.emailKeyFromNormalized(anyString())).thenAnswer(invocation -> {
            String arg = invocation.getArgument(0);
            return "key:" + arg;
        });
        lenient().when(persistence.now()).thenReturn(Instant.parse("2026-10-28T18:00:00Z"));
        lenient().when(persistence.hashAttemptToken(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            return "hash(" + token + ")";
        });
        lenient().when(persistence.verifyAttemptToken(any(), any())).thenAnswer(invocation -> {
            String candidate = invocation.getArgument(0);
            String storedHash = invocation.getArgument(1);
            if (candidate == null || storedHash == null) return false;
            return storedHash.equals("hash(" + candidate + ")");
        });
    }

    @Test
    @DisplayName("Deterministic attempt-token derivation survives restart with stable secret")
    void deterministicTokenDerivationSurvivesRestartWithStableSecret() {
        SignupOtpChallengeService instanceA = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, SECRET_V1);
        SignupOtpChallengeService instanceB = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, SECRET_V1);

        String email = "member@example.com";
        String idempotencyKey = "client-uuid-12345";

        String tokenA = instanceA.deriveAttemptToken(email, idempotencyKey);
        String tokenB = instanceB.deriveAttemptToken(email, idempotencyKey);

        assertNotNull(tokenA);
        assertEquals(tokenA, tokenB, "Both backend instances must derive the exact same attempt token");
    }

    @Test
    @DisplayName("Idempotent replay recovers attempt token on replayed request after restart")
    void idempotentReplayRecoversAttemptTokenAcrossRestart() {
        SignupOtpChallengeService instanceA = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, SECRET_V1);
        SignupOtpChallengeService instanceB = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, SECRET_V1);

        String email = "recovery@example.com";
        String idempotencyKey = "idem-replay-test";

        String expectedToken = instanceA.deriveAttemptToken(email, idempotencyKey);
        String expectedHash = "hash(" + expectedToken + ")";

        SignupOtpChallenge storedChallenge = new SignupOtpChallenge();
        storedChallenge.setEmailKey("key:recovery@example.com");
        storedChallenge.setNormalizedEmail(email);
        storedChallenge.setClientIdempotencyKey(idempotencyKey);
        storedChallenge.setSendStatus(OtpSendStatus.SENT);
        storedChallenge.setLastSendWasInitial(true);
        storedChallenge.setAttemptTokenHash(expectedHash);
        storedChallenge.setStatus(OtpChallengeStatus.ACTIVE);
        storedChallenge.setChallengeExpiresAt(Instant.parse("2026-10-28T18:30:00Z"));
        storedChallenge.setNextResendAt(Instant.parse("2026-10-28T18:00:30Z"));
        storedChallenge.setOtpExpiresAt(Instant.parse("2026-10-28T18:10:00Z"));

        when(persistence.findByEmailKey("key:recovery@example.com")).thenReturn(Optional.of(storedChallenge));

        // Instance B receives replayed request after restart without client having attempt token yet
        SignupOtpChallengeService.RequestOtpResult replayResult = instanceB.requestSignupOtp(email, idempotencyKey);

        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, replayResult);
        SignupOtpChallengeService.RequestOtpResult.Sent sent = (SignupOtpChallengeService.RequestOtpResult.Sent) replayResult;
        assertEquals(expectedToken, sent.attemptToken(),
                "Replayed response must recover the original attempt token across restarts");
    }

    @Test
    @DisplayName("Key rotation recovers tokens using secondary secret while new challenges use primary")
    void keyRotationRecoversTokensUsingSecondarySecretWhileNewChallengesUsePrimary() {
        // Active deployment with rotated keys: SECRET_V2 is primary, SECRET_V1 is fallback
        String rotatedSecrets = SECRET_V2 + "," + SECRET_V1;
        SignupOtpChallengeService rotatedService = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, rotatedSecrets);

        String email = "rotation@example.com";
        String idempotencyKey = "idem-rotation-test";

        // Previous token generated under SECRET_V1
        SignupOtpChallengeService oldService = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, SECRET_V1);
        String tokenV1 = oldService.deriveAttemptToken(email, idempotencyKey);

        SignupOtpChallenge activeOldChallenge = new SignupOtpChallenge();
        activeOldChallenge.setEmailKey("key:rotation@example.com");
        activeOldChallenge.setNormalizedEmail(email);
        activeOldChallenge.setClientIdempotencyKey(idempotencyKey);
        activeOldChallenge.setSendStatus(OtpSendStatus.SENT);
        activeOldChallenge.setLastSendWasInitial(true);
        activeOldChallenge.setAttemptTokenHash("hash(" + tokenV1 + ")");
        activeOldChallenge.setStatus(OtpChallengeStatus.ACTIVE);
        activeOldChallenge.setChallengeExpiresAt(Instant.parse("2026-10-28T18:30:00Z"));
        activeOldChallenge.setNextResendAt(Instant.parse("2026-10-28T18:00:30Z"));
        activeOldChallenge.setOtpExpiresAt(Instant.parse("2026-10-28T18:10:00Z"));

        when(persistence.findByEmailKey("key:rotation@example.com")).thenReturn(Optional.of(activeOldChallenge));

        // Replay on rotated service successfully recovers the token generated with SECRET_V1
        SignupOtpChallengeService.RequestOtpResult replay = rotatedService.requestSignupOtp(email, idempotencyKey);
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, replay);
        assertEquals(tokenV1, ((SignupOtpChallengeService.RequestOtpResult.Sent) replay).attemptToken());

        // A new send on rotated service derives using primary SECRET_V2
        String newIdempotency = "idem-fresh";
        String tokenV2Expected = rotatedService.deriveAttemptToken("fresh@example.com", newIdempotency);
        SignupOtpChallengeService serviceV2Only = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, SECRET_V2);
        assertEquals(serviceV2Only.deriveAttemptToken("fresh@example.com", newIdempotency), tokenV2Expected,
                "New challenges must be derived with the primary rotated key");
    }

    @Test
    @DisplayName("Ephemeral key fallback ensures service starts without error when secret is absent")
    void ephemeralKeyFallbackEnsuresServiceStartsGracefully() {
        SignupOtpChallengeService defaultService = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, "");
        assertNotNull(defaultService.deriveAttemptToken("test@example.com", "key-1"));

        SignupOtpChallengeService nullService = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, (String) null);
        assertNotNull(nullService.deriveAttemptToken("test@example.com", "key-2"));
    }

    @Test
    @DisplayName("Arbitrary passphrases and 32-byte strings derive uniform 32-byte hex tokens")
    void arbitraryPassphraseNormalizesCorrectly() {
        SignupOtpChallengeService serviceWithPassphrase = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, "my-production-long-secure-passphrase-phrase-12345");

        String token = serviceWithPassphrase.deriveAttemptToken("user@example.com", "idem-pass");
        assertNotNull(token);
        assertEquals(64, token.length(), "Derived token must be 64-char hex string (32 bytes HMAC output)");
        assertTrue(token.matches("^[0-9a-f]{64}$"));
    }

    @Test
    @DisplayName("Existing issued tokens remain valid when candidate token is provided by client")
    void existingIssuedTokensRemainValidWhenCandidateTokenProvided() {
        SignupOtpChallengeService service = new SignupOtpChallengeService(
                persistence, emailSenderService, observability, SECRET_V2);

        SignupOtpChallenge challenge = new SignupOtpChallenge();
        challenge.setAttemptTokenHash("hash(valid-browser-token)");

        // Client holding token in sessionStorage supplies candidate token
        assertTrue(service.verifyAttemptToken(challenge, "valid-browser-token"));
        assertFalse(service.verifyAttemptToken(challenge, "wrong-browser-token"));
    }
}
