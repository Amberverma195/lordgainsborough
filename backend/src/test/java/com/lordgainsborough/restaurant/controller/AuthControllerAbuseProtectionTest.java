package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.dto.SignupOtpChallengeView;
import com.lordgainsborough.restaurant.dto.SignupOtpCode;
import com.lordgainsborough.restaurant.dto.SignupOtpResponse;
import com.lordgainsborough.restaurant.dto.SignupRequest;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.AuthSecurityObservability;
import com.lordgainsborough.restaurant.service.LoginRateLimiter;
import com.lordgainsborough.restaurant.service.OtpService;
import com.lordgainsborough.restaurant.service.PasswordResetService;
import com.lordgainsborough.restaurant.service.SignupAbuseLimiter;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeService;
import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerAbuseProtectionTest {

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
                authSecurityObservability);
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.findByPhoneNormalized(anyString())).thenReturn(List.of());
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of());
        when(signupOtpChallengeService.looksLikeInitialSend(anyString())).thenReturn(true);
        when(clientIpResolver.resolve(anyString(), nullable(String.class), nullable(String.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(signupAbuseLimiter.checkAndConsumeSend(anyString(), anyBoolean(), nullable(String.class)))
                .thenReturn(new SignupAbuseLimiter.Decision.Allowed());
        when(signupAbuseLimiter.checkAndConsumeVerification(anyString()))
                .thenReturn(new SignupAbuseLimiter.Decision.Allowed());
        when(signupAbuseLimiter.hashClientIp(anyString())).thenReturn("a".repeat(64));
        when(signupOtpChallengeService.requestSignupOtp(anyString(), nullable(String.class)))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Sent(true, true, sampleView()));
    }

    @Test
    void sendOtpReturnsGeneric429WithRetryAfterWhenIpLimited() {
        when(signupAbuseLimiter.checkAndConsumeSend(eq("203.0.113.9"), eq(true), isNull()))
                .thenReturn(new SignupAbuseLimiter.Decision.Denied(
                        42L, SignupAbuseLimiter.SignupAbuseDenyReason.INITIAL_SEND_IP));

        HttpServletRequest http = httpRequest("203.0.113.9", null, null);
        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(
                null, null, signupOtpRequest("a@example.com", "5551112222"), http);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("42", response.getHeaders().getFirst("Retry-After"));
        assertEquals(SignupOtpCode.OTP_RATE_LIMITED, response.getBody().getCode());
        assertEquals("Too many requests. Please try again later.", response.getBody().getMessage());
        verify(signupOtpChallengeService, never()).requestSignupOtp(anyString(), nullable(String.class));
        verify(signupOtpObservability).record(
                eq(SignupOtpObservability.Event.IP_LIMIT_REJECTED),
                org.mockito.ArgumentMatchers.any(SignupOtpObservability.Context.class));
    }

    @Test
    void spoofedForwardingHeadersAreResolvedOnlyViaTrustedProxyLogic() {
        when(clientIpResolver.resolve(eq("203.0.113.50"), eq("1.2.3.4"), isNull()))
                .thenReturn("203.0.113.50");
        when(signupAbuseLimiter.checkAndConsumeSend(eq("203.0.113.50"), eq(true), isNull()))
                .thenReturn(new SignupAbuseLimiter.Decision.Allowed());

        HttpServletRequest http = httpRequest("203.0.113.50", "1.2.3.4", null);
        controller.sendSignupOtp(null, null, signupOtpRequest("b@example.com", "5551113333"), http);

        verify(clientIpResolver).resolve("203.0.113.50", "1.2.3.4", null);
        verify(signupAbuseLimiter).checkAndConsumeSend("203.0.113.50", true, null);
    }

    @Test
    void verificationFloodReturnsGeneric429() {
        when(signupAbuseLimiter.checkAndConsumeVerification("198.51.100.7"))
                .thenReturn(new SignupAbuseLimiter.Decision.Denied(
                        15L, SignupAbuseLimiter.SignupAbuseDenyReason.VERIFY_IP));

        SignupRequest request = new SignupRequest();
        request.setName("Test");
        request.setEmail("c@example.com");
        request.setPassword("Password1!");
        request.setPhone("5551114444");
        request.setOtp("123456");

        ResponseEntity<?> response = controller.signup(request, httpRequest("198.51.100.7", null, null));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        SignupOtpResponse body = (SignupOtpResponse) response.getBody();
        assertNotNull(body);
        assertEquals(SignupOtpCode.OTP_RATE_LIMITED, body.getCode());
        assertNull(body.getCaptchaRequired());
        verify(signupOtpChallengeService, never()).verifyOtp(anyString(), anyString());
        verify(signupOtpObservability).record(
                eq(SignupOtpObservability.Event.IP_LIMIT_REJECTED),
                org.mockito.ArgumentMatchers.any(SignupOtpObservability.Context.class));
    }

    @Test
    void captchaGateReturnsCaptchaRequiredWithoutLeakingAccountState() {
        when(signupAbuseLimiter.checkAndConsumeSend(anyString(), anyBoolean(), isNull()))
                .thenReturn(new SignupAbuseLimiter.Decision.Denied(
                        60L, SignupAbuseLimiter.SignupAbuseDenyReason.CAPTCHA_REQUIRED));

        ResponseEntity<SignupOtpResponse> response = controller.sendSignupOtp(
                null, null, signupOtpRequest("d@example.com", "5551115555"),
                httpRequest("203.0.113.1", null, null));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals(SignupOtpCode.OTP_CAPTCHA_REQUIRED, response.getBody().getCode());
        assertEquals(Boolean.TRUE, response.getBody().getCaptchaRequired());
    }

    private static HttpServletRequest httpRequest(String remote, String xff, String realIp) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remote);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(request.getHeader("X-Real-IP")).thenReturn(realIp);
        return request;
    }

    private static SendOtpRequest signupOtpRequest(String email, String phone) {
        SendOtpRequest request = new SendOtpRequest();
        request.setEmail(email);
        request.setPhone(phone);
        return request;
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
}
