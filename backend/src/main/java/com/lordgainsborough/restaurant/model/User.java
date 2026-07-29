package com.lordgainsborough.restaurant.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.time.LocalDateTime;

@Document(collection = "users")
public class User {
    @Id
    private String id;
    
    private String name;
    
    @Indexed(unique = true)
    private String email;
    
    private String phone;

    @Indexed(unique = true, sparse = true)
    private String phoneNormalized;

    private String password;
    private String role = "MEMBER";
    private String memberSince;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastProfileUpdateAt;
    private long tokenVersion = 0L;
    private Instant passwordChangedAt;
    private String lastPasswordResetOperationId;

    /** Cumulative pickup no-shows recorded by KDS. Pickup is blocked at 3+. */
    private int noShowCount = 0;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPhoneNormalized() { return phoneNormalized; }
    public void setPhoneNormalized(String phoneNormalized) { this.phoneNormalized = phoneNormalized; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getMemberSince() { return memberSince; }
    public void setMemberSince(String memberSince) { this.memberSince = memberSince; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastProfileUpdateAt() { return lastProfileUpdateAt; }
    public void setLastProfileUpdateAt(LocalDateTime lastProfileUpdateAt) { this.lastProfileUpdateAt = lastProfileUpdateAt; }

    public long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }

    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(Instant passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }

    public String getLastPasswordResetOperationId() { return lastPasswordResetOperationId; }
    public void setLastPasswordResetOperationId(String value) { this.lastPasswordResetOperationId = value; }

    public int getNoShowCount() { return noShowCount; }
    public void setNoShowCount(int noShowCount) { this.noShowCount = noShowCount; }
}
