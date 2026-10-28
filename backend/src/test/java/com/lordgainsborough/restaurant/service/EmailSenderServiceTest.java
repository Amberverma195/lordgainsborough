package com.lordgainsborough.restaurant.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailSenderServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final BrevoApiEmailService brevoApiEmailService = mock(BrevoApiEmailService.class);
    private EmailSenderService emailSenderService;

    @BeforeEach
    void setUp() {
        emailSenderService = new EmailSenderService(mailSender, brevoApiEmailService);
        ReflectionTestUtils.setField(emailSenderService, "fromEmail", "lordgainsborough5@gmail.com");
        ReflectionTestUtils.setField(emailSenderService, "fromName", "Lord Gainsborough");
        when(brevoApiEmailService.isConfigured()).thenReturn(false);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void timeoutThrowsAmbiguousEmailDeliveryException() {
        SocketTimeoutException timeout = new SocketTimeoutException("connect timed out");
        MailSendException mailException = new MailSendException("Mail server connection failed", timeout);
        doThrow(mailException).when(mailSender).send(any(MimeMessage.class));

        assertThrows(AmbiguousEmailDeliveryException.class, () ->
                emailSenderService.sendOtpEmail("test@example.com", "123456", "op-1"));
    }

    @Test
    void connectionRefusedThrowsAmbiguousEmailDeliveryException() {
        java.net.ConnectException connect = new java.net.ConnectException("Connection refused: connect");
        MailSendException mailException = new MailSendException("Mail server connection failed", connect);
        doThrow(mailException).when(mailSender).send(any(MimeMessage.class));

        assertThrows(AmbiguousEmailDeliveryException.class, () ->
                emailSenderService.sendOtpEmail("test@example.com", "123456", "op-2"));
    }

    @Test
    void authenticationFailureThrowsDefiniteEmailRejectionException() {
        org.springframework.mail.MailAuthenticationException authException =
                new org.springframework.mail.MailAuthenticationException("Authentication failed: 535 Bad credentials");
        doThrow(authException).when(mailSender).send(any(MimeMessage.class));

        assertThrows(DefiniteEmailRejectionException.class, () ->
                emailSenderService.sendOtpEmail("test@example.com", "123456", "op-3"));
    }

    @Test
    void rejection550ThrowsDefiniteEmailRejectionException() {
        MailSendException mailException = new MailSendException("550 Requested action not taken: mailbox unavailable");
        doThrow(mailException).when(mailSender).send(any(MimeMessage.class));

        assertThrows(DefiniteEmailRejectionException.class, () ->
                emailSenderService.sendOtpEmail("test@example.com", "123456", "op-4"));
    }

    @Test
    void brevoApiUsedWhenConfigured() {
        when(brevoApiEmailService.isConfigured()).thenReturn(true);

        emailSenderService.sendOtpEmail("member@example.com", "654321", "op-brevo");

        org.mockito.Mockito.verify(brevoApiEmailService).sendEmail(
                org.mockito.ArgumentMatchers.eq("member@example.com"),
                org.mockito.ArgumentMatchers.contains("Verification Code"),
                org.mockito.ArgumentMatchers.contains("654321"),
                org.mockito.ArgumentMatchers.contains("654321"),
                org.mockito.ArgumentMatchers.eq("op-brevo")
        );
        org.mockito.Mockito.verifyNoInteractions(mailSender);
    }
}
