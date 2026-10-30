package com.lordgainsborough.restaurant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailSenderServiceTest {

    private final ResendEmailService resendEmailService = mock(ResendEmailService.class);
    private EmailSenderService emailSenderService;

    @BeforeEach
    void setUp() {
        emailSenderService = new EmailSenderService(resendEmailService);
    }

    @Test
    void sendOtpEmail_WithOperationId_FormatsTemplatesAndForwardsToResend() {
        when(resendEmailService.isConfigured()).thenReturn(true);

        emailSenderService.sendOtpEmail("member@example.com", "123456", "op-otp-1");

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> opCaptor = ArgumentCaptor.forClass(String.class);

        verify(resendEmailService).sendEmail(
                toCaptor.capture(),
                subjectCaptor.capture(),
                htmlCaptor.capture(),
                textCaptor.capture(),
                opCaptor.capture()
        );

        assertEquals("member@example.com", toCaptor.getValue());
        assertEquals("Lord Gainsborough - Your Verification Code", subjectCaptor.getValue());
        assertEquals("op-otp-1", opCaptor.getValue());

        // Verify HTML template
        String html = htmlCaptor.getValue();
        assertTrue(html.contains("123456"));
        assertTrue(html.contains("Verify Your Email"));
        assertTrue(html.contains("This code is valid for 10 minutes."));

        // Verify plain-text template
        String text = textCaptor.getValue();
        assertTrue(text.contains("123456"));
        assertTrue(text.contains("Lord Gainsborough - Email Verification"));
        assertTrue(text.contains("This code expires in 10 minutes."));
    }

    @Test
    void sendOtpEmail_WithoutOperationId_CallsOverloadWithNullOperationId() {
        when(resendEmailService.isConfigured()).thenReturn(true);

        emailSenderService.sendOtpEmail("member@example.com", "654321");

        verify(resendEmailService).sendEmail(
                eq("member@example.com"),
                eq("Lord Gainsborough - Your Verification Code"),
                contains("654321"),
                contains("654321"),
                isNull()
        );
    }

    @Test
    void sendPasswordResetCodeEmail_WithOperationId_FormatsTemplatesAndForwardsToResend() {
        when(resendEmailService.isConfigured()).thenReturn(true);

        emailSenderService.sendPasswordResetCodeEmail("reset@example.com", "789012", "op-reset-1");

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> opCaptor = ArgumentCaptor.forClass(String.class);

        verify(resendEmailService).sendEmail(
                toCaptor.capture(),
                subjectCaptor.capture(),
                htmlCaptor.capture(),
                textCaptor.capture(),
                opCaptor.capture()
        );

        assertEquals("reset@example.com", toCaptor.getValue());
        assertEquals("Lord Gainsborough - Password Reset Code", subjectCaptor.getValue());
        assertEquals("op-reset-1", opCaptor.getValue());

        // Verify HTML template
        String html = htmlCaptor.getValue();
        assertTrue(html.contains("789012"));
        assertTrue(html.contains("Reset Your Password"));
        assertTrue(html.contains("This code is valid for 10 minutes."));

        // Verify plain-text template
        String text = textCaptor.getValue();
        assertTrue(text.contains("789012"));
        assertTrue(text.contains("Lord Gainsborough - Password Reset"));
        assertTrue(text.contains("This code expires in 10 minutes."));
    }

    @Test
    void sendPasswordResetCodeEmail_WithoutOperationId_CallsOverloadWithNullOperationId() {
        when(resendEmailService.isConfigured()).thenReturn(true);

        emailSenderService.sendPasswordResetCodeEmail("reset@example.com", "888999");

        verify(resendEmailService).sendEmail(
                eq("reset@example.com"),
                eq("Lord Gainsborough - Password Reset Code"),
                contains("888999"),
                contains("888999"),
                isNull()
        );
    }

    @Test
    void noSmtpOrBrevoFallback_ExceptionsPropagateDirectly() {
        // When Resend fails ambiguously, no fallback occurs; exception propagates directly
        doThrow(new AmbiguousEmailDeliveryException("Resend API timed out; delivery status unknown"))
                .when(resendEmailService).sendEmail(any(), any(), any(), any(), any());

        assertThrows(AmbiguousEmailDeliveryException.class, () ->
                emailSenderService.sendOtpEmail("test@example.com", "123456", "op-1"));

        // When Resend fails definitely, no fallback occurs; exception propagates directly
        doThrow(new DefiniteEmailRejectionException("Resend authentication failed: HTTP_401"))
                .when(resendEmailService).sendEmail(any(), any(), any(), any(), any());

        assertThrows(DefiniteEmailRejectionException.class, () ->
                emailSenderService.sendOtpEmail("test@example.com", "123456", "op-2"));
    }

    @Test
    void isConfigured_DelegatesToResendEmailService() {
        when(resendEmailService.isConfigured()).thenReturn(true);
        assertTrue(emailSenderService.isConfigured());

        when(resendEmailService.isConfigured()).thenReturn(false);
        assertFalse(emailSenderService.isConfigured());
    }
}
