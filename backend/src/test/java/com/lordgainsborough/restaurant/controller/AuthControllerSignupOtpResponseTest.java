package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.dto.SignupOtpChallengeView;
import com.lordgainsborough.restaurant.dto.SignupOtpCode;
import com.lordgainsborough.restaurant.dto.SignupOtpResponse;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.AuthSecurityObservability;
import com.lordgainsborough.restaurant.service.LoginRateLimiter;
import com.lordgainsborough.restaurant.service.OtpService;
import com.lordgainsborough.restaurant.service.PasswordResetService;
import com.lordgainsborough.restaurant.service.SignupAbuseLimiter;
import com.lordgainsborough.restaurant.service.SignupOtpChallengePersistence;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeService;
import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerSignupOtpResponseTest {

    private static final Instant NOW = Instant.parse("2026-07-13T10:00:00Z");

    private final EmailSenderService emailSenderService = mock(EmailSenderService.class);
    private final UserAuthStore userRepository = mock(UserAuthStore.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final OtpService otpService = mock(OtpService.class);
    private final SignupOtpChallengeService signupOtpChallengeService = mock(SignupOtpChallengeService.class);
    private final SignupAbuseLimiter signupAbuseLimiter = mock(SignupAbuseLimiter.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final SignupOtpObservability signupOtpObservability =
            mock(SignupOtpObservability.class);
    private final LoginRateLimiter loginRateLimiter = mock(LoginRateLimiter.class);
    private final PasswordResetService passwordResetService = mock(PasswordResetService.class);
    private final AuthSecurityObservability authSecurityObservability =
            mock(AuthSecurityObservability.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final AuthController controller = new AuthController(
            emailSenderService,
            userRepository,
            jwtUtil,
            otpService,
            signupOtpChallengeService,
            signupAbuseLimiter,
            clientIpResolver,
            signupOtpObservability,
            loginRateLimiter,
            passwordResetService,
            authSecurityObservability);

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(
                emailSenderService,
                userRepository,
                jwtUtil,
                otpService,
                signupOtpChallengeService,
                signupAbuseLimiter,
                clientIpResolver,
                signupOtpObservability,
                loginRateLimiter,
                passwordResetService,
                authSecurityObservability,
                httpRequest);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(clientIpResolver.resolve(anyString(), nullable(String.class), nullable(String.class)))
                .thenReturn("127.0.0.1");
        when(signupAbuseLimiter.checkAndConsumeSend(anyString(), anyBoolean(), nullable(String.class)))
                .thenReturn(new SignupAbuseLimiter.Decision.Allowed());
        when(signupAbuseLimiter.checkAndConsumeVerification(anyString()))
                .thenReturn(new SignupAbuseLimiter.Decision.Allowed());
        when(signupOtpChallengeService.looksLikeInitialSend(anyString())).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(signupOtpChallengeService.requestSignupOtp(anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Sent(true, true, sampleView()));
        when(signupOtpChallengeService.requestSignupOtp(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Sent(true, true, sampleView()));
    }

    @Test
    void sendSignupOtpReturnsAcceptedStructuredBodyOnSuccess() {
        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp("idem-1", null, signupOtpRequest("new@example.com", "5551234567"), httpRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        SignupOtpResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(SignupOtpCode.OTP_SENT, body.getCode());
        assertEquals("challenge-1", body.getChallengeId());
        assertEquals(NOW, body.getServerTime());
        assertEquals(NOW.plusSeconds(30), body.getNextResendAt());
        assertEquals(NOW.plusSeconds(600), body.getOtpExpiresAt());
        assertEquals(NOW.plusSeconds(1800), body.getChallengeExpiresAt());
        assertEquals(5, body.getResendsRemaining());
    }

    @Test
    void sendSignupOtpConflictUsesGenericWordingWithoutRevealingEmailOrPhone() {
        User existing = new User();
        existing.setPhoneNormalized("5551234567");
        when(userRepository.phoneRegisteredByAnotherUser("5551234567", null)).thenReturn(true);

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "+1 (555)-123-4567"), httpRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_INVALID, response.getBody().getCode());
        assertEquals("Unable to complete signup with the provided details.", response.getBody().getMessage());
        verify(signupOtpChallengeService, never())
                .requestSignupOtp(anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void sendSignupOtpCooldownReturns429WithRetryAfter() {
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), any(), any(), any(), any(), any()))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Denied(
                        SignupOtpChallengePersistence.ResendDenialReason.COOLDOWN_ACTIVE,
                        17,
                        "Please wait before requesting another code.",
                        sampleView()));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "5551234567"), httpRequest);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("17", response.getHeaders().getFirst("Retry-After"));
        assertEquals(SignupOtpCode.OTP_RESEND_COOLDOWN, response.getBody().getCode());
    }

    @Test
    void sendSignupOtpResendLimitReturns429() {
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), any(), any(), any(), any(), any()))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Denied(
                        SignupOtpChallengePersistence.ResendDenialReason.RESEND_LIMIT_REACHED,
                        0,
                        "No more verification codes can be sent for this signup attempt.",
                        sampleView()));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "5551234567"), httpRequest);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_RESEND_LIMIT_REACHED, response.getBody().getCode());
    }

    @Test
    void sendSignupOtpExpiredChallengeReturns410() {
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), any(), any(), any(), any(), any()))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Denied(
                        SignupOtpChallengePersistence.ResendDenialReason.CHALLENGE_EXPIRED,
                        0,
                        "This verification session has expired. Please start again.",
                        sampleView()));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "5551234567"), httpRequest);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_CHALLENGE_EXPIRED, response.getBody().getCode());
    }

    @Test
    void sendSignupOtpProviderFailureDoesNotReturn500() {
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), any(), any(), any(), any(), any()))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Failed(sampleView()));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "5551234567"), httpRequest);

        assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_DELIVERY_UNKNOWN, response.getBody().getCode());
    }

    @Test
    void concurrentControllerCallsReturnStructuredMockServiceResponsesWithout500() throws Exception {
        AtomicInteger created = new AtomicInteger();
        when(signupOtpChallengeService.requestSignupOtp(eq("race@example.com"), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (created.compareAndSet(0, 1)) {
                        return new SignupOtpChallengeService.RequestOtpResult.Sent(true, true, sampleView());
                    }
                    return new SignupOtpChallengeService.RequestOtpResult.Sent(false, false, sampleView());
                });

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<SignupOtpResponse>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return controller.sendSignupOtp(null, null, signupOtpRequest("race@example.com", "+1 (555)-999-8888"), httpRequest);
            }));
        }
        start.countDown();

        for (Future<ResponseEntity<SignupOtpResponse>> future : futures) {
            ResponseEntity<SignupOtpResponse> response = future.get(10, TimeUnit.SECONDS);
            assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, created.get());
    }

    @Test
    void getSignupOtpStatusReturnsActiveChallengeMetadata() {
        when(signupOtpChallengeService.getChallengeStatus(eq("new@example.com"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new SignupOtpChallengeService.ChallengeStatusResult.Active(sampleView()));

        ResponseEntity<SignupOtpResponse> response = controller.getSignupOtpStatus("new@example.com", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_ALREADY_ACTIVE, response.getBody().getCode());
        assertEquals("challenge-1", response.getBody().getChallengeId());
        assertEquals(5, response.getBody().getResendsRemaining());
    }

    @Test
    void malformedPhoneReturns400() {
        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "123"), httpRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_INVALID, response.getBody().getCode());
        assertEquals("Please enter a valid 10-digit phone number.", response.getBody().getMessage());
    }

    @Test
    void malformedEmailWithoutDomainSuffixReturns400WithoutCallingServices() {
        SendOtpRequest request = signupOtpRequest("amberverma@gma", "5551234567");
        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, request, httpRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_INVALID, response.getBody().getCode());
        assertEquals("Please enter a valid email address.", response.getBody().getMessage());
        verify(signupAbuseLimiter, never()).checkAndConsumeSend(anyString(), anyBoolean(), nullable(String.class));
        verify(signupOtpChallengeService, never()).requestSignupOtp(anyString(), anyString());
    }

    @Test
    void sendSignupOtpWithPasswordStagesPendingRegistrationWithBcryptHash() {
        SendOtpRequest request = signupOtpRequest("amber@example.com", "5551234567");
        request.setName("Amber Verma");
        request.setPassword("SecretPassword123!");

        when(signupOtpChallengeService.requestSignupOtp(
                eq("amber@example.com"),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq("Amber Verma"),
                eq("5551234567"),
                eq("5551234567"),
                argThat(hash -> hash != null && hash.startsWith("$2a$"))))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Sent(true, true, sampleView()));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp("idem-123", null, request, httpRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_SENT, response.getBody().getCode());
        verify(signupOtpChallengeService).requestSignupOtp(
                eq("amber@example.com"),
                eq("idem-123"),
                eq("Amber Verma"),
                eq("5551234567"),
                eq("5551234567"),
                argThat(hash -> hash != null && hash.startsWith("$2a$")));
    }

    @Test
    void signupWithPendingRegistrationCompletesWithoutPassword() {
        com.lordgainsborough.restaurant.dto.SignupRequest signupReq = new com.lordgainsborough.restaurant.dto.SignupRequest();
        signupReq.setEmail("amber@example.com");
        signupReq.setOtp("123456");
        signupReq.setChallengeId("challenge-1");
        // Note: password is deliberately null

        User registeredUser = new User();
        registeredUser.setId("user-1");
        registeredUser.setName("Amber Verma");
        registeredUser.setEmail("amber@example.com");
        registeredUser.setPhone("+1 (555)-123-4567");
        registeredUser.setRole("MEMBER");
        registeredUser.setTokenVersion(1);

        org.mockito.Mockito.doReturn(new UserAuthStore.Registration(
                        registeredUser,
                        new SignupOtpChallengeService.VerifyOtpResult.Verified(sampleView()),
                        false))
                .when(userRepository).registerPending(
                        eq("amber@example.com"),
                        eq("123456"),
                        eq("challenge-1"),
                        eq(signupOtpChallengeService));

        ResponseEntity<?> response = controller.signup(signupReq, httpRequest);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void signupWithMalformedEmailFailsBeforeVerification() {
        com.lordgainsborough.restaurant.dto.SignupRequest signupReq = new com.lordgainsborough.restaurant.dto.SignupRequest();
        signupReq.setEmail("amberverma@gma");
        signupReq.setOtp("123456");

        ResponseEntity<?> response = controller.signup(signupReq, httpRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        SignupOtpResponse body = (SignupOtpResponse) response.getBody();
        assertEquals("Please enter a valid email address.", body.getMessage());
        verify(userRepository, never()).registerPending(anyString(), anyString(), anyString(), any());
    }

    @Test
    void initialSendRequiresName() {
        SendOtpRequest request = signupOtpRequest("valid@example.com", "5551234567");
        request.setName(null);

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, request, httpRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Full name is required.", response.getBody().getMessage());
    }

    @Test
    void initialSendRequiresPassword() {
        SendOtpRequest request = signupOtpRequest("valid@example.com", "5551234567");
        request.setPassword(null);

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, request, httpRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Password is required.", response.getBody().getMessage());
    }

    @Test
    void passwordComplexityIsEnforced() {
        SendOtpRequest request = signupOtpRequest("valid@example.com", "5551234567");
        request.setPassword("weakpass");

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, request, httpRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("uppercase letter"));
    }

    @Test
    void resendRequestDoesNotRequirePassword() {
        when(signupOtpChallengeService.looksLikeInitialSend("existing@example.com")).thenReturn(false);
        when(signupOtpChallengeService.requestSignupOtp(eq("existing@example.com"), any()))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Sent(false, true, sampleView()));

        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("existing@example.com");
        request.setPhone("5551234567");

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp("idem-2", null, request, httpRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_SENT, response.getBody().getCode());
    }

    @Test
    void activeSendLeaseReturnsConflictWithRetryAfter() {
        when(signupOtpChallengeService.looksLikeInitialSend("lease@example.com")).thenReturn(false);
        when(signupOtpChallengeService.requestSignupOtp(eq("lease@example.com"), any()))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.InFlight(42, sampleView()));

        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("lease@example.com");
        request.setPhone("5551234567");

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, request, httpRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("42", response.getHeaders().getFirst("Retry-After"));
    }

    private static SignupOtpChallengeView sampleView() {
        return new SignupOtpChallengeView(
                "challenge-1",
                NOW,
                NOW.plusSeconds(30),
                NOW.plusSeconds(600),
                NOW.plusSeconds(1800),
                5);
    }

    private SendOtpRequest signupOtpRequest(String email, String phone) {
        SendOtpRequest request = new SendOtpRequest();
        request.setEmail(email);
        request.setPhone(phone);
        request.setName("John Doe");
        request.setPassword("Secret@123");
        return request;
    }
}
