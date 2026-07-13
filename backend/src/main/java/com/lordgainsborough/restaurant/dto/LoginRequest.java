package com.lordgainsborough.restaurant.dto;

public class LoginRequest {
    /** Email address or phone number used to sign in. */
    private String emailOrPhone;
    /** Legacy field — still accepted for older clients. */
    private String email;
    private String password;

    public String getEmailOrPhone() {
        if (emailOrPhone != null && !emailOrPhone.isBlank()) {
            return emailOrPhone;
        }
        return email;
    }

    public void setEmailOrPhone(String emailOrPhone) {
        this.emailOrPhone = emailOrPhone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
