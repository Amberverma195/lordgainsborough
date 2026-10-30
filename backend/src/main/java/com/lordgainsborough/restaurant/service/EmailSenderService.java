package com.lordgainsborough.restaurant.service;

import org.springframework.stereotype.Service;

/**
 * Outbound email service for OTP verification and password reset messages,
 * sending exclusively via Resend HTTPS REST API.
 */
@Service
public class EmailSenderService {

    private final ResendEmailService resendEmailService;

    public EmailSenderService(ResendEmailService resendEmailService) {
        this.resendEmailService = resendEmailService;
    }

    public boolean isConfigured() {
        return resendEmailService.isConfigured();
    }

    public void sendOtpEmail(String recipientEmail, String otpCode) {
        sendOtpEmail(recipientEmail, otpCode, null);
    }

    /**
     * Sends signup OTP email.
     *
     * @param recipientEmail recipient email address
     * @param otpCode 6-digit verification code
     * @param sendOperationId server-generated operation id forwarded as Resend's Idempotency-Key
     */
    public void sendOtpEmail(String recipientEmail, String otpCode, String sendOperationId) {
        String subject = "Lord Gainsborough - Your Verification Code";
        String plainText = buildOtpPlainText(otpCode);
        String htmlContent = buildOtpHtmlContent(otpCode);
        resendEmailService.sendEmail(recipientEmail, subject, htmlContent, plainText, sendOperationId);
    }

    public void sendPasswordResetCodeEmail(String recipientEmail, String otpCode) {
        sendPasswordResetCodeEmail(recipientEmail, otpCode, null);
    }

    /**
     * Sends password-reset OTP email.
     *
     * @param recipientEmail recipient email address
     * @param otpCode 6-digit password reset code
     * @param sendOperationId server-generated operation id forwarded as Resend's Idempotency-Key
     */
    public void sendPasswordResetCodeEmail(
            String recipientEmail,
            String otpCode,
            String sendOperationId) {
        String subject = "Lord Gainsborough - Password Reset Code";
        String plainText = buildPasswordResetPlainText(otpCode);
        String htmlContent = buildPasswordResetHtmlContent(otpCode);
        resendEmailService.sendEmail(recipientEmail, subject, htmlContent, plainText, sendOperationId);
    }

    private String buildOtpPlainText(String otpCode) {
        return "Lord Gainsborough - Email Verification\n\n"
                + "Your verification code is: " + otpCode + "\n\n"
                + "This code expires in 10 minutes.\n"
                + "If you did not request this code, you can safely ignore this email.";
    }

    private String buildOtpHtmlContent(String otpCode) {
        return "<div style='font-family: Arial, sans-serif; max-width: 450px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                + "<h2 style='color: #1a1a1a;'>Verify Your Email</h2>"
                + "<p>Please use the following code to complete your Lord Gainsborough account registration:</p>"
                + "<div style='text-align: center; margin: 20px 0;'>"
                + "  <h1 style='color: #0284c7; background: #f0f9ff; display: inline-block; padding: 10px 25px; border-radius: 5px; letter-spacing: 5px; font-size: 32px; margin: 0;'>" + otpCode + "</h1>"
                + "</div>"
                + "<p style='color: #666; font-size: 13px;'>This code is valid for 10 minutes. If you did not request this code, please ignore this message.</p>"
                + "</div>";
    }

    private String buildPasswordResetPlainText(String otpCode) {
        return "Lord Gainsborough - Password Reset\n\n"
                + "Your password reset code is: " + otpCode + "\n\n"
                + "This code expires in 10 minutes.\n"
                + "If you did not request this reset, you can safely ignore this email.";
    }

    private String buildPasswordResetHtmlContent(String otpCode) {
        return "<div style='font-family: Arial, sans-serif; max-width: 450px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                + "<h2 style='color: #1a1a1a;'>Reset Your Password</h2>"
                + "<p>Use this code to reset your Lord Gainsborough account password:</p>"
                + "<div style='text-align: center; margin: 20px 0;'>"
                + "<h1 style='color: #0284c7; background: #f0f9ff; display: inline-block; padding: 10px 25px; border-radius: 5px; letter-spacing: 5px; font-size: 32px; margin: 0;'>"
                + otpCode + "</h1></div>"
                + "<p style='color: #666; font-size: 13px;'>This code is valid for 10 minutes. If you did not request a password reset, ignore this message.</p>"
                + "</div>";
    }
}
