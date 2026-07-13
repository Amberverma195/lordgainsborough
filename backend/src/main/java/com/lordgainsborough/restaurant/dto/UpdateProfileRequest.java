package com.lordgainsborough.restaurant.dto;

public class UpdateProfileRequest {
    private String name;
    private String email;
    private String phone;
    private String emailOtp;
    private String phoneOtp;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmailOtp() { return emailOtp; }
    public void setEmailOtp(String emailOtp) { this.emailOtp = emailOtp; }

    public String getPhoneOtp() { return phoneOtp; }
    public void setPhoneOtp(String phoneOtp) { this.phoneOtp = phoneOtp; }
}
