package com.lordgainsborough.restaurant.dto;

public class AuthResponse {
    private String token;
    private String name;
    private String email;
    private String phone;
    private String memberSince;
    private String role;
    private boolean canUpdateProfile;
    private String nextProfileUpdateAt;

    // Default constructor
    public AuthResponse() {}

    // Getters and Setters
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    
    public String getMemberSince() { return memberSince; }
    public void setMemberSince(String memberSince) { this.memberSince = memberSince; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isCanUpdateProfile() { return canUpdateProfile; }
    public void setCanUpdateProfile(boolean canUpdateProfile) { this.canUpdateProfile = canUpdateProfile; }

    public String getNextProfileUpdateAt() { return nextProfileUpdateAt; }
    public void setNextProfileUpdateAt(String nextProfileUpdateAt) { this.nextProfileUpdateAt = nextProfileUpdateAt; }
}
