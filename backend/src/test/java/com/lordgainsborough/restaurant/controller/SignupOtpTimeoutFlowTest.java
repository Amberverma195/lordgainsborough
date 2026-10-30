package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.dto.SignupOtpCode;
import com.lordgainsborough.restaurant.dto.SignupOtpResponse;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SignupOtpTimeoutFlowTest {

    private final ResendEmailService resendEmailService = mock(ResendEmailService.class);
    private final UserAuthStore userAuthStore = mock(UserAuthStore.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final OtpService otpService = mock(OtpService.class);
    private final SignupAbuseLimiter abuseLimiter = mock(SignupAbuseLimiter.class);
    private final ClientIpResolver ipResolver = mock(ClientIpResolver.class);
    private final LoginRateLimiter loginRateLimiter = mock(LoginRateLimiter.class);
    private final PasswordResetService passwordResetService = mock(PasswordResetService.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final SignupOtpChallengeStore store = mock(SignupOtpChallengeStore.class);

    private final ConcurrentHashMap<String, SignupOtpChallenge> db = new ConcurrentHashMap<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);

    private AuthController controller;

    @BeforeEach
    void setUp() {
        EmailSenderService emailSender = new EmailSenderService(resendEmailService);
        when(resendEmailService.isConfigured()).thenReturn(true);

        when(store.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.getArgument(0))));
        when(store.save(any(SignupOtpChallenge.class))).thenAnswer(inv -> {
            SignupOtpChallenge c = inv.getArgument(0);
            db.put(c.getEmailKey(), c);
            return c;
        });
        when(store.tryInsert(any(SignupOtpChallenge.class))).thenAnswer(inv -> {
            SignupOtpChallenge c = inv.getArgument(0);
            db.put(c.getEmailKey(), c);
            return true;
        });
        when(store.tryAtomicReplaceExpired(anyString(), any(SignupOtpChallenge.class), any(Instant.class))).thenAnswer(inv -> {
            SignupOtpChallenge c = inv.getArgument(1);
            db.put(c.getEmailKey(), c);
            return Optional.of(c);
        });
        when(store.tryMarkSendOutcome(anyString(), anyString(), any(OtpSendStatus.class), any(Instant.class))).thenAnswer(inv -> {
            String emailKey = inv.getArgument(0);
            OtpSendStatus outcome = inv.getArgument(2);
            SignupOtpChallenge c = db.get(emailKey);
            if (c != null) {
                c.setSendStatus(outcome);
                return Optional.of(c);
            }
            return Optional.empty();
        });

        SignupOtpChallengePersistence persistence = new SignupOtpChallengePersistence(store, clock);
        SignupOtpObservability observability = new SignupOtpObservability(new SimpleMeterRegistry());
        AuthSecurityObservability secObservability = new AuthSecurityObservability(new SimpleMeterRegistry());
        SignupOtpChallengeService challengeService = new SignupOtpChallengeService(
                persistence, emailSender, observability);

        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(ipResolver.resolve(any(), any(), any())).thenReturn("127.0.0.1");
        when(abuseLimiter.checkAndConsumeSend(any(), anyBoolean(), any())).thenReturn(new SignupAbuseLimiter.Decision.Allowed());
        when(userAuthStore.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(userAuthStore.phoneRegisteredByAnotherUser(any(), any())).thenReturn(false);

        controller = new AuthController(
                emailSender,
                userAuthStore,
                jwtUtil,
                otpService,
                challengeService,
                abuseLimiter,
                ipResolver,
                observability,
                loginRateLimiter,
                passwordResetService,
                secObservability);
    }

    @Test
    void emailTimeoutReturns202WithDeliveryUnknown() {
        SocketTimeoutException timeout = new SocketTimeoutException("connect timed out");
        ResourceAccessException accessException = new ResourceAccessException("Resend API timed out", timeout);
        doThrow(new AmbiguousEmailDeliveryException(
                "Resend API timed out or connection failed; delivery status unknown", accessException))
                .when(resendEmailService).sendEmail(any(), any(), any(), any(), any());

        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("member@example.com");
        request.setName("John Doe");
        request.setPhone("5551234567");
        request.setPassword("StrongPass123!");

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(
                "idem-123", null, null, request, httpRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_DELIVERY_UNKNOWN, response.getBody().getCode());
        assertNotNull(response.getBody().getAttemptToken(), "Initiating browser must receive attemptToken on DELIVERY_UNKNOWN");
        String attemptToken = response.getBody().getAttemptToken();

        // Replayed request with same Idempotency-Key after lost HTTP response
        ResponseEntity<SignupOtpResponse> replayed = controller.sendSignupOtp(
                "idem-123", null, null, request, httpRequest);
        assertEquals(HttpStatus.ACCEPTED, replayed.getStatusCode());
        assertEquals(SignupOtpCode.OTP_DELIVERY_UNKNOWN, replayed.getBody().getCode());
        assertEquals(attemptToken, replayed.getBody().getAttemptToken(), "Replayed initial request must return matching attemptToken");
    }
}
