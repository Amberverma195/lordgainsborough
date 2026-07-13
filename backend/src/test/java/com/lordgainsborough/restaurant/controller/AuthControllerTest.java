package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.ResetPasswordRequest;
import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final EmailSenderService emailSenderService = mock(EmailSenderService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomerOrderRepository orderRepository = mock(CustomerOrderRepository.class);
    private final OrderReviewRepository reviewRepository = mock(OrderReviewRepository.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final OtpService otpService = mock(OtpService.class);
    private final AuthController controller = new AuthController(
            emailSenderService,
            userRepository,
            orderRepository,
            reviewRepository,
            jwtUtil,
            otpService);

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(emailSenderService, userRepository, orderRepository, reviewRepository, jwtUtil, otpService);
    }

    @Test
    void sendSignupOtpRejectsDuplicateNormalizedPhoneBeforeEmailing() {
        User existing = new User();
        existing.setId("existing-user");
        existing.setPhoneNormalized("5551234567");

        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(userRepository.findByPhoneNormalized("5551234567")).thenReturn(List.of(existing));
        when(userRepository.findByPhoneIsNotNull()).thenReturn(List.of());

        ResponseEntity<String> response = controller.sendSignupOtp(signupOtpRequest(
                "new@example.com",
                "+1 (555)-123-4567"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("An account already exists with this phone number.", response.getBody());
        verify(otpService, never()).storeOtp(anyString(), anyString());
        verify(emailSenderService, never()).sendOtpEmail(anyString(), anyString());
    }

    @Test
    void sendPasswordResetOtpSanitizesEmailAndStoresPasswordResetKeyForExistingUser() {
        User existing = new User();
        existing.setEmail("Member@Example.com");

        when(userRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(existing));

        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("  Member@Example.com\u200B  ");

        ResponseEntity<String> response = controller.sendPasswordResetOtp(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Reset code sent to Member@Example.com.", response.getBody());
        verify(otpService).storeOtpForKey(eq("password-reset:member@example.com"), anyString());
        verify(emailSenderService).sendOtpEmail(eq("Member@Example.com"), anyString());
    }

    @Test
    void sendPasswordResetOtpDoesNotEmailUnknownAccounts() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        SendOtpRequest request = new SendOtpRequest();
        request.setEmail("missing@example.com");

        ResponseEntity<String> response = controller.sendPasswordResetOtp(request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("No account exists with this email.", response.getBody());
        verifyNoInteractions(emailSenderService);
        verify(otpService, never()).storeOtpForKey(anyString(), anyString());
    }

    @Test
    void resetPasswordRejectsUnknownEmailBeforeCheckingOtp() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.resetPassword(resetPasswordRequest(
                "missing@example.com",
                "123456",
                "NewPassword1!"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("No account exists with this email.", response.getBody());
        verify(otpService, never()).verifyOtpForKey(anyString(), anyString());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void resetPasswordRejectsInvalidPasswordResetOtp() {
        User existing = new User();
        existing.setEmail("member@example.com");
        existing.setPassword("old-hash");

        when(userRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(existing));
        when(otpService.verifyOtpForKey("password-reset:member@example.com", "123456")).thenReturn(false);

        ResponseEntity<String> response = controller.resetPassword(resetPasswordRequest(
                "member@example.com",
                "123456",
                "NewPassword1!"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid or expired verification code.", response.getBody());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void resetPasswordHashesAndSavesNewPasswordAfterValidOtp() {
        User existing = new User();
        existing.setEmail("member@example.com");
        existing.setPassword("old-hash");

        when(userRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(existing));
        when(otpService.verifyOtpForKey("password-reset:member@example.com", "123456")).thenReturn(true);

        ResponseEntity<String> response = controller.resetPassword(resetPasswordRequest(
                "member@example.com",
                "123456",
                "NewPassword1!"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password reset successfully.", response.getBody());
        assertTrue(PasswordUtil.checkPassword("NewPassword1!", userCaptor.getValue().getPassword()));
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
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(existing));
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

        ResponseEntity<String> response = controller.sendSignupOtp(signupOtpRequest(
                "new@example.com",
                "1 555 123 4567"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("An account already exists with this phone number.", response.getBody());
        verify(otpService, never()).storeOtp(anyString(), anyString());
        verify(emailSenderService, never()).sendOtpEmail(anyString(), anyString());
    }

    private SendOtpRequest signupOtpRequest(String email, String phone) {
        SendOtpRequest request = new SendOtpRequest();
        request.setEmail(email);
        request.setPhone(phone);
        return request;
    }

    private ResetPasswordRequest resetPasswordRequest(String email, String otp, String password) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setEmail(email);
        request.setOtp(otp);
        request.setPassword(password);
        return request;
    }
}
