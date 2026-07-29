package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.dto.SignupOtpChallengeView;
import com.lordgainsborough.restaurant.dto.SignupOtpCode;
import com.lordgainsborough.restaurant.dto.SignupOtpResponse;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
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
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerSignupOtpResponseTest {

    private static final Instant NOW = Instant.parse("2026-07-13T10:00:00Z");

    private final EmailSenderService emailSenderService = mock(EmailSenderService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomerOrderRepository orderRepository = mock(CustomerOrderRepository.class);
    private final OrderReviewRepository reviewRepository = mock(OrderReviewRepository.class);
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
            orderRepository,
            reviewRepository,
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
                orderRepository,
                reviewRepository,
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
        when(signupOtpChallengeService.looksLikeInitialSend(anyString())).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.findByPhoneNormalized(anyString())).thenReturn(List.of());
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of());
        when(signupOtpChallengeService.requestSignupOtp(anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
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
        when(userRepository.findByPhoneNormalized("5551234567")).thenReturn(List.of(existing));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "+1 (555)-123-4567"), httpRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_INVALID, response.getBody().getCode());
        assertEquals("Unable to complete signup with the provided details.", response.getBody().getMessage());
        verify(signupOtpChallengeService, never())
                .requestSignupOtp(anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void sendSignupOtpCooldownReturns429WithRetryAfter() {
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), org.mockito.ArgumentMatchers.nullable(String.class)))
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
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), org.mockito.ArgumentMatchers.nullable(String.class)))
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
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), org.mockito.ArgumentMatchers.nullable(String.class)))
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
        when(signupOtpChallengeService.requestSignupOtp(eq("new@example.com"), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Failed(sampleView()));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(null, null, signupOtpRequest("new@example.com", "5551234567"), httpRequest);

        assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_DELIVERY_UNKNOWN, response.getBody().getCode());
    }

    @Test
    void concurrentControllerCallsReturnStructuredMockServiceResponsesWithout500() throws Exception {
        AtomicInteger created = new AtomicInteger();
        when(signupOtpChallengeService.requestSignupOtp(eq("race@example.com"), org.mockito.ArgumentMatchers.nullable(String.class)))
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
        return request;
    }
}
