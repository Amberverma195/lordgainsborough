package com.lordgainsborough.restaurant.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Outbound email for OTP and related messages.
 *
 * <p><b>Provider idempotency:</b> Brevo REST accepts {@code sendOperationId} via the
 * {@code idempotencyKey} header. SMTP has no idempotency/deduplication key, so when
 * mail is sent only over SMTP, absolute exactly-once email delivery cannot be guaranteed
 * across ambiguous timeouts and client retries.
 */
@Service
public class EmailSenderService {

    private final JavaMailSender mailSender;
    private final BrevoApiEmailService brevoApiEmailService;

    @Value("${app.mail.from:lordgainsborough5@gmail.com}")
    private String fromEmail;

    @Value("${app.mail.from-name:Lord Gainsborough}")
    private String fromName;

    public EmailSenderService(JavaMailSender mailSender, BrevoApiEmailService brevoApiEmailService) {
        this.mailSender = mailSender;
        this.brevoApiEmailService = brevoApiEmailService;
    }

    public void sendOtpEmail(String recipientEmail, String otpCode) {
        sendOtpEmail(recipientEmail, otpCode, null);
    }

    /**
     * @param sendOperationId server-generated operation id; forwarded to Brevo when configured
     */
    public void sendOtpEmail(String recipientEmail, String otpCode, String sendOperationId) {
        String subject = "Lord Gainsborough - Your Verification Code";
        String plainText = buildPlainText(otpCode);
        String htmlContent = buildHtmlContent(otpCode);
        send(recipientEmail, subject, plainText, htmlContent, sendOperationId);
    }

    public void sendPasswordResetCodeEmail(
            String recipientEmail,
            String otpCode,
            String sendOperationId) {
        String subject = "Lord Gainsborough - Password Reset Code";
        String plainText = "Lord Gainsborough - Password Reset\n\n"
                + "Your password reset code is: " + otpCode + "\n\n"
                + "This code expires in 10 minutes.\n"
                + "If you did not request this reset, you can safely ignore this email.";
        String htmlContent = "<div style='font-family: Arial, sans-serif; max-width: 450px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                + "<h2 style='color: #1a1a1a;'>Reset Your Password</h2>"
                + "<p>Use this code to reset your Lord Gainsborough account password:</p>"
                + "<div style='text-align: center; margin: 20px 0;'>"
                + "<h1 style='color: #0284c7; background: #f0f9ff; display: inline-block; padding: 10px 25px; border-radius: 5px; letter-spacing: 5px; font-size: 32px; margin: 0;'>"
                + otpCode + "</h1></div>"
                + "<p style='color: #666; font-size: 13px;'>This code is valid for 10 minutes. If you did not request a password reset, ignore this message.</p>"
                + "</div>";
        send(recipientEmail, subject, plainText, htmlContent, sendOperationId);
    }

    private void send(
            String recipientEmail,
            String subject,
            String plainText,
            String htmlContent,
            String sendOperationId) {
        if (brevoApiEmailService.isConfigured()) {
            try {
                brevoApiEmailService.sendEmail(
                        recipientEmail, subject, htmlContent, plainText, sendOperationId);
                return;
            } catch (AmbiguousEmailDeliveryException ambiguous) {
                // Do not SMTP-fallback after an ambiguous Brevo outcome — that risks a duplicate send.
                throw ambiguous;
            } catch (DefiniteEmailRejectionException definite) {
                // A definite Brevo rejection is safe to retry through SMTP.
            } catch (RuntimeException e) {
                // Do not log provider exception messages: they may contain recipient/payload details.
            }
        }

        try {
            sendViaSmtp(recipientEmail, subject, plainText, htmlContent);
        } catch (MailAuthenticationException e) {
            throw new DefiniteEmailRejectionException("SMTP authentication failed", e);
        } catch (MailSendException e) {
            throw classifySmtpSendException(e);
        } catch (MessagingException e) {
            throw new DefiniteEmailRejectionException("SMTP message rejected", e);
        } catch (Exception e) {
            throw new AmbiguousEmailDeliveryException(
                    "SMTP send failed with unknown delivery status", e);
        }
    }

    private RuntimeException classifySmtpSendException(MailSendException e) {
        Throwable root = e.getRootCause() != null ? e.getRootCause() : e;
        String name = root.getClass().getName().toLowerCase();
        String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase();
        if (name.contains("timeout") || message.contains("timed out") || message.contains("timeout")
                || name.contains("connect") || message.contains("connection") || message.contains("refused")) {
            return new AmbiguousEmailDeliveryException("SMTP timed out or connection failed; delivery status unknown", e);
        }
        if (message.contains("invalid") || message.contains("reject") || message.contains("550")) {
            return new DefiniteEmailRejectionException("SMTP rejected the message", e);
        }
        return new AmbiguousEmailDeliveryException("SMTP send failed; delivery status unknown", e);
    }

    private void sendViaSmtp(String recipientEmail, String subject, String plainText, String htmlContent)
            throws MessagingException, java.io.UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromEmail, fromName);
        helper.setReplyTo(fromEmail);
        helper.setTo(recipientEmail);
        helper.setSubject(subject);
        helper.setText(plainText, htmlContent);

        mailSender.send(message);
    }

    private String buildPlainText(String otpCode) {
        return "Lord Gainsborough - Email Verification\n\n"
                + "Your verification code is: " + otpCode + "\n\n"
                + "This code expires in 10 minutes.\n"
                + "If you did not request this code, you can safely ignore this email.";
    }

    private String buildHtmlContent(String otpCode) {
        return "<div style='font-family: Arial, sans-serif; max-width: 450px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                + "<h2 style='color: #1a1a1a;'>Verify Your Email</h2>"
                + "<p>Please use the following code to complete your Lord Gainsborough account registration:</p>"
                + "<div style='text-align: center; margin: 20px 0;'>"
                + "  <h1 style='color: #0284c7; background: #f0f9ff; display: inline-block; padding: 10px 25px; border-radius: 5px; letter-spacing: 5px; font-size: 32px; margin: 0;'>" + otpCode + "</h1>"
                + "</div>"
                + "<p style='color: #666; font-size: 13px;'>This code is valid for 10 minutes. If you did not request this code, please ignore this message.</p>"
                + "</div>";
    }

}
