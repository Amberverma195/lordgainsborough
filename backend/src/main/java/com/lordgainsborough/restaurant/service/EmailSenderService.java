package com.lordgainsborough.restaurant.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailSenderService {

    private static final Logger logger = LoggerFactory.getLogger(EmailSenderService.class);

    private final JavaMailSender mailSender;
    private final BrevoApiEmailService brevoApiEmailService;

    @Value("${app.mail.from:lordgainsborough5@gmail.com}")
    private String fromEmail;

    @Value("${app.mail.from-name:Lord Gainsborough}")
    private String fromName;

    @Value("${app.otp.log-to-console:false}")
    private boolean logOtpToConsole;

    public EmailSenderService(JavaMailSender mailSender, BrevoApiEmailService brevoApiEmailService) {
        this.mailSender = mailSender;
        this.brevoApiEmailService = brevoApiEmailService;
    }

    public void sendOtpEmail(String recipientEmail, String otpCode) {
        String subject = "Lord Gainsborough - Your Verification Code";
        String plainText = buildPlainText(otpCode);
        String htmlContent = buildHtmlContent(otpCode);

        logOtpForTroubleshooting(recipientEmail, otpCode);

        if (brevoApiEmailService.isConfigured()) {
            try {
                brevoApiEmailService.sendEmail(recipientEmail, subject, htmlContent, plainText);
                logger.info("OTP email dispatched via Brevo API to {}", recipientEmail);
                return;
            } catch (Exception e) {
                logger.warn("Brevo API send failed for {}, falling back to SMTP: {}", recipientEmail, e.getMessage());
            }
        }

        try {
            sendViaSmtp(recipientEmail, subject, plainText, htmlContent);
            logger.info("OTP email dispatched via SMTP to {}", recipientEmail);
        } catch (Exception e) {
            logger.error("Failed to send OTP email to {} via SMTP", recipientEmail, e);
            throw new RuntimeException("Failed to send verification email: " + e.getMessage(), e);
        }
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
                + "This code expires in 5 minutes.\n"
                + "If you did not request this code, you can safely ignore this email.";
    }

    private String buildHtmlContent(String otpCode) {
        return "<div style='font-family: Arial, sans-serif; max-width: 450px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                + "<h2 style='color: #1a1a1a;'>Verify Your Email</h2>"
                + "<p>Please use the following code to complete your Lord Gainsborough account registration:</p>"
                + "<div style='text-align: center; margin: 20px 0;'>"
                + "  <h1 style='color: #0284c7; background: #f0f9ff; display: inline-block; padding: 10px 25px; border-radius: 5px; letter-spacing: 5px; font-size: 32px; margin: 0;'>" + otpCode + "</h1>"
                + "</div>"
                + "<p style='color: #666; font-size: 13px;'>This code is valid for 5 minutes. If you did not request this code, please ignore this message.</p>"
                + "</div>";
    }

    private void logOtpForTroubleshooting(String recipientEmail, String otpCode) {
        if (logOtpToConsole) {
            logger.warn("=== OTP for {}: {} (valid 5 min) — check Brevo sender verification if email not received ===",
                    recipientEmail, otpCode);
        }
    }
}
