package com.lordgainsborough.restaurant.dto;

public class ProfileVerificationResponse {
    private boolean requiresEmailOtp;
    private boolean requiresPhoneOtp;
    private String emailOtpSentTo;
    private String phoneOtpSentTo;

    public boolean isRequiresEmailOtp() { return requiresEmailOtp; }
    public void setRequiresEmailOtp(boolean requiresEmailOtp) { this.requiresEmailOtp = requiresEmailOtp; }

    public boolean isRequiresPhoneOtp() { return requiresPhoneOtp; }
    public void setRequiresPhoneOtp(boolean requiresPhoneOtp) { this.requiresPhoneOtp = requiresPhoneOtp; }

    public String getEmailOtpSentTo() { return emailOtpSentTo; }
    public void setEmailOtpSentTo(String emailOtpSentTo) { this.emailOtpSentTo = emailOtpSentTo; }

    public String getPhoneOtpSentTo() { return phoneOtpSentTo; }
    public void setPhoneOtpSentTo(String phoneOtpSentTo) { this.phoneOtpSentTo = phoneOtpSentTo; }
}
