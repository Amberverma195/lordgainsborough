package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.dto.PasswordResetResponse;
import com.lordgainsborough.restaurant.dto.SignupOtpCode;
import com.lordgainsborough.restaurant.dto.SignupOtpResponse;
import com.lordgainsborough.restaurant.dto.SignupRequest;
import com.lordgainsborough.restaurant.model.CustomerOrder;
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
import com.lordgainsborough.restaurant.service.SignupOtpChallengeService;
import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

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
        when(signupAbuseLimiter.checkAndConsumeVerification(anyString()))
                .thenReturn(new SignupAbuseLimiter.Decision.Allowed());
        when(signupOtpChallengeService.looksLikeInitialSend(anyString())).thenReturn(true);
        when(signupOtpChallengeService.requestSignupOtp(anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new SignupOtpChallengeService.RequestOtpResult.Sent(true, true, null));
    }

    @Test
    void sendSignupOtpRejectsDuplicateNormalizedPhoneBeforeEmailing() {
        User existing = new User();
        existing.setId("existing-user");
        existing.setPhoneNormalized("5551234567");

        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(userRepository.findByPhoneNormalized("5551234567")).thenReturn(List.of(existing));
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of());

        var response = controller.sendSignupOtp(null, null, signupOtpRequest(
                "new@example.com",
                "+1 (555)-123-4567"), httpRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Unable to complete signup with the provided details.", response.getBody().getMessage());
        verify(signupOtpChallengeService, never()).requestSignupOtp(anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
        verify(emailSenderService, never()).sendOtpEmail(anyString(), anyString());
    }

    @Test
    void passwordResetRequestUsesGenericServiceResponseForExistingAccount() {
        User existing = new User();
        existing.setEmail("Member@Example.com");
        when(userRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(existing));
        PasswordResetResponse body = PasswordResetResponse.of(
                "RESET_CODE_SENT",
                "If an account exists for that email, a reset code has been sent.",
                java.time.Instant.parse("2026-07-13T12:00:00Z"));
        when(passwordResetService.requestInitial(
                eq("member@example.com"),
                eq(Optional.of(existing)),
                eq("127.0.0.1"),
                eq("request-key")))
                .thenReturn(new PasswordResetService.Result(HttpStatus.ACCEPTED, body, 0L));

        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("  Member@Example.com\u200B  ");
        ResponseEntity<PasswordResetResponse> response =
                controller.sendPasswordResetOtp("request-key", request, httpRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("RESET_CODE_SENT", response.getBody().getCode());
    }

    @Test
    void passwordResetRequestUsesSamePublicShapeForUnknownAccount() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());
        PasswordResetResponse body = PasswordResetResponse.of(
                "RESET_CODE_SENT",
                "If an account exists for that email, a reset code has been sent.",
                java.time.Instant.parse("2026-07-13T12:00:00Z"));
        when(passwordResetService.requestInitial(
                eq("missing@example.com"),
                eq(Optional.empty()),
                eq("127.0.0.1"),
                eq("request-key")))
                .thenReturn(new PasswordResetService.Result(HttpStatus.ACCEPTED, body, 0L));

        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("missing@example.com");
        ResponseEntity<PasswordResetResponse> response =
                controller.sendPasswordResetOtp("request-key", request, httpRequest);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("RESET_CODE_SENT", response.getBody().getCode());
        assertEquals(
                "If an account exists for that email, a reset code has been sent.",
                response.getBody().getMessage());
    }

    @Test
    void getMyOrdersReturnsOrdersForAuthenticatedUser() {
        User existing = new User();
        existing.setEmail("member@example.com");
        existing.setPhone("+1 (555)-123-4567");
        existing.setPhoneNormalized("5551234567");

        CustomerOrder order = new CustomerOrder();
        order.setId("order-1");
        order.setCustomerEmail("member@example.com");
        order.setCustomerPhone("+1 (555)-123-4567");

        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.getEmailFromToken("valid-token")).thenReturn("member@example.com");
        when(userRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(existing));
        when(jwtUtil.validateTokenForUser("valid-token", existing)).thenReturn(true);
        when(orderRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc("member@example.com")).thenReturn(List.of(order));
        when(orderRepository.findByCustomerPhoneOrderByCreatedAtDesc("+1 (555)-123-4567")).thenReturn(List.of(order));

        ResponseEntity<?> response = controller.getMyOrders("Bearer valid-token");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(order), response.getBody());
        verify(orderRepository).findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc("member@example.com");
        verify(orderRepository).findByCustomerPhoneOrderByCreatedAtDesc("+1 (555)-123-4567");
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    void sendSignupOtpRejectsDuplicateLegacyFormattedPhoneBeforeEmailing() {
        User existing = new User();
        existing.setId("existing-user");
        existing.setPhone("+1 (555)-123-4567");

        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(userRepository.findByPhoneNormalized("5551234567")).thenReturn(List.of());
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of(existing));

        var response = controller.sendSignupOtp(null, null, signupOtpRequest(
                "new@example.com",
                "1 555 123 4567"), httpRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Unable to complete signup with the provided details.", response.getBody().getMessage());
        verify(signupOtpChallengeService, never()).requestSignupOtp(anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
        verify(emailSenderService, never()).sendOtpEmail(anyString(), anyString());
    }

    @Test
    void concurrentControllerCallsMapControlledMockServiceResultsWithout500() throws Exception {
        AtomicInteger created = new AtomicInteger();
        when(userRepository.existsByEmailIgnoreCase("race@example.com")).thenReturn(false);
        when(userRepository.findByPhoneNormalized("5559998888")).thenReturn(List.of());
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of());
        when(signupOtpChallengeService.requestSignupOtp(eq("race@example.com"), org.mockito.ArgumentMatchers.nullable(String.class))).thenAnswer(inv -> {
            if (created.compareAndSet(0, 1)) {
                return new SignupOtpChallengeService.RequestOtpResult.Sent(true, true, null);
            }
            return new SignupOtpChallengeService.RequestOtpResult.Sent(false, false, null);
        });

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return controller.sendSignupOtp(null, null, signupOtpRequest("race@example.com", "+1 (555)-999-8888"), httpRequest);
            }));
        }
        start.countDown();

        for (Future<?> future : futures) {
            @SuppressWarnings("unchecked")
            ResponseEntity<?> response = (ResponseEntity<?>) future.get(10, TimeUnit.SECONDS);
            assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, created.get());
    }

    @Test
    void signupDuplicateEmailRaceAfterVerificationClearsChallengeAndReturnsConflict() {
        when(userRepository.existsByEmailIgnoreCase("race@example.com")).thenReturn(false);
        when(userRepository.findByPhoneNormalized("5551112222")).thenReturn(List.of());
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of());
        when(signupOtpChallengeService.verifyOtp("race@example.com", "123456"))
                .thenReturn(new SignupOtpChallengeService.VerifyOtpResult.Verified(null));
        when(userRepository.save(any(User.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key email"));
        User existing = new User();
        existing.setId("existing-user");
        existing.setEmail("race@example.com");
        when(userRepository.findByEmailIgnoreCase("race@example.com")).thenReturn(Optional.of(existing));

        ResponseEntity<?> response = controller.signup(signupRequest(
                "race@example.com", "5551112222", "123456"), httpRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        SignupOtpResponse body = assertInstanceOf(SignupOtpResponse.class, response.getBody());
        assertEquals(SignupOtpCode.OTP_INVALID, body.getCode());
        assertEquals("Unable to complete signup with the provided details.", body.getMessage());
        verify(signupOtpChallengeService).clear("race@example.com");
        verify(userRepository, times(1)).save(any(User.class));
        verify(jwtUtil, never()).generateToken(anyString(), anyString());
    }

    @Test
    void signupDuplicatePhoneRaceAfterVerificationClearsChallengeAndReturnsConflict() {
        when(userRepository.existsByEmailIgnoreCase("phone.race@example.com")).thenReturn(false);
        when(userRepository.findByPhoneNormalized("5553334444")).thenReturn(List.of());
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of());
        when(signupOtpChallengeService.verifyOtp("phone.race@example.com", "654321"))
                .thenReturn(new SignupOtpChallengeService.VerifyOtpResult.Verified(null));
        when(userRepository.save(any(User.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key phoneNormalized"));
        when(userRepository.findByEmailIgnoreCase("phone.race@example.com")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.signup(signupRequest(
                "phone.race@example.com", "5553334444", "654321"), httpRequest);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        SignupOtpResponse body = assertInstanceOf(SignupOtpResponse.class, response.getBody());
        assertEquals(SignupOtpCode.OTP_INVALID, body.getCode());
        assertEquals("Unable to complete signup with the provided details.", body.getMessage());
        verify(signupOtpChallengeService).clear("phone.race@example.com");
        verify(userRepository, times(1)).save(any(User.class));
        verify(jwtUtil, never()).generateToken(anyString(), anyString());
    }

    private SignupRequest signupRequest(String email, String phone, String otp) {
        SignupRequest request = new SignupRequest();
        request.setName("Race User");
        request.setEmail(email);
        request.setPhone(phone);
        request.setPassword("Password1!");
        request.setOtp(otp);
        return request;
    }

    private SendOtpRequest signupOtpRequest(String email, String phone) {
        SendOtpRequest request = new SendOtpRequest();
        request.setEmail(email);
        request.setPhone(phone);
        return request;
    }

}
