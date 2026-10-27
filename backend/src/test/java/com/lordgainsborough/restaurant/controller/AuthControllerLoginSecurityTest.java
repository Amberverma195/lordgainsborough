package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.LoginRequest;
import com.lordgainsborough.restaurant.dto.LoginResponse;
import com.lordgainsborough.restaurant.dto.PasswordResetResponse;
import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import com.lordgainsborough.restaurant.service.AuthSecurityObservability;
import com.lordgainsborough.restaurant.service.EmailSenderService;
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
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerLoginSecurityTest {

    private static final String IP = "203.0.113.30";

    private final UserAuthStore userRepository = mock(UserAuthStore.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final LoginRateLimiter loginRateLimiter = mock(LoginRateLimiter.class);
    private final PasswordResetService passwordResetService = mock(PasswordResetService.class);
    private final AuthSecurityObservability observability =
            mock(AuthSecurityObservability.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    private final AuthController controller = new AuthController(
            mock(EmailSenderService.class),
            userRepository,
            jwtUtil,
            mock(OtpService.class),
            mock(SignupOtpChallengeService.class),
            mock(SignupAbuseLimiter.class),
            clientIpResolver,
            mock(SignupOtpObservability.class),
            loginRateLimiter,
            passwordResetService,
            observability);

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(
                userRepository,
                jwtUtil,
                clientIpResolver,
                loginRateLimiter,
                passwordResetService,
                observability,
                httpRequest);
        when(httpRequest.getRemoteAddr()).thenReturn(IP);
        when(clientIpResolver.resolve(anyString(), nullable(String.class), nullable(String.class)))
                .thenReturn(IP);
        when(loginRateLimiter.consumeIpRequest(IP)).thenReturn(OptionalLong.empty());
        when(loginRateLimiter.activeCooldown(anyString(), anyString()))
                .thenReturn(OptionalLong.empty());
        when(loginRateLimiter.recordFailure(anyString(), anyString()))
                .thenReturn(new LoginRateLimiter.FailureResult(false, 0L));
    }

    @Test
    void unknownAccountAndWrongPasswordHaveEquivalentPublicResponses() {
        User existing = new User();
        existing.setId("user-1");
        existing.setEmail("known@example.com");
        existing.setPassword(PasswordUtil.hashPassword("CorrectPassword1!"));
        when(userRepository.findByEmailIgnoreCase("missing@example.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("known@example.com"))
                .thenReturn(Optional.of(existing));

        ResponseEntity<?> unknown =
                controller.login(loginRequest("missing@example.com", "WrongPassword1!"), httpRequest);
        ResponseEntity<?> wrong =
                controller.login(loginRequest("known@example.com", "WrongPassword1!"), httpRequest);

        assertEquivalentInvalidCredentialResponse(unknown);
        assertEquivalentInvalidCredentialResponse(wrong);
        LoginResponse unknownBody = (LoginResponse) unknown.getBody();
        LoginResponse wrongBody = (LoginResponse) wrong.getBody();
        assertEquals(unknownBody.getCode(), wrongBody.getCode());
        assertEquals(unknownBody.getMessage(), wrongBody.getMessage());
        assertEquals(unknownBody.getRetryAfterSeconds(), wrongBody.getRetryAfterSeconds());

        verify(loginRateLimiter).recordFailure("missing@example.com", IP);
        verify(loginRateLimiter).recordFailure("known@example.com", IP);
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void fixedIpDenialReturns429AndRetryAfterHeader() {
        when(loginRateLimiter.consumeIpRequest(IP)).thenReturn(OptionalLong.of(17L));

        ResponseEntity<?> response =
                controller.login(loginRequest("member@example.com", "Password1!"), httpRequest);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("17", response.getHeaders().getFirst("Retry-After"));
        LoginResponse body = (LoginResponse) response.getBody();
        assertNotNull(body);
        assertEquals("LOGIN_RATE_LIMITED", body.getCode());
        assertEquals(17L, body.getRetryAfterSeconds());
        assertEquals("Too many login attempts. Please try again later.", body.getMessage());
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void activeIdentifierIpCooldownReturns429AndRetryAfterHeader() {
        when(loginRateLimiter.activeCooldown("member@example.com", IP))
                .thenReturn(OptionalLong.of(30L));

        ResponseEntity<?> response =
                controller.login(loginRequest(" MEMBER@example.com ", "Password1!"), httpRequest);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("30", response.getHeaders().getFirst("Retry-After"));
        LoginResponse body = (LoginResponse) response.getBody();
        assertNotNull(body);
        assertEquals("LOGIN_RATE_LIMITED", body.getCode());
        assertEquals(30L, body.getRetryAfterSeconds());
        verify(loginRateLimiter, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void passwordResetRateLimitIsMappedTo429AndRetryAfterHeader() {
        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("member@example.com");
        when(userRepository.findByEmailIgnoreCase("member@example.com"))
                .thenReturn(Optional.empty());
        PasswordResetResponse body = PasswordResetResponse.of(
                "RESET_RATE_LIMITED",
                "Too many reset requests. Please try again later.",
                Instant.parse("2026-07-13T15:00:00Z"));
        body.setRetryAfterSeconds(44L);
        when(userRepository.requestPasswordReset(passwordResetService,"member@example.com",IP,"reset-request-key"))
                .thenReturn(new PasswordResetService.Result(
                        HttpStatus.TOO_MANY_REQUESTS,
                        body,
                        44L));

        ResponseEntity<PasswordResetResponse> response =
                controller.sendPasswordResetOtp(
                        "reset-request-key",
                        request,
                        httpRequest);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("44", response.getHeaders().getFirst("Retry-After"));
        assertEquals("RESET_RATE_LIMITED", response.getBody().getCode());
        assertEquals(44L, response.getBody().getRetryAfterSeconds());
    }

    private static void assertEquivalentInvalidCredentialResponse(ResponseEntity<?> response) {
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        LoginResponse body = (LoginResponse) response.getBody();
        assertNotNull(body);
        assertEquals("LOGIN_INVALID_CREDENTIALS", body.getCode());
        assertEquals("Invalid email, phone number, or password.", body.getMessage());
        assertNull(body.getRetryAfterSeconds());
        assertNotNull(body.getServerTime());
    }

    private static LoginRequest loginRequest(String identifier, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmailOrPhone(identifier);
        request.setPassword(password);
        return request;
    }
}
