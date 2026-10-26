package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
public class JpaUser {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 255)
    private String name;

    /**
     * Note: Unique constraint is enforced by the PostgreSQL functional index
     * `uq_users_email_lower ON users (LOWER(email))`. Do not add unique = true here.
     */
    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name = "role", length = 50, nullable = false)
    private String role = "MEMBER";

    @Column(name = "member_since", length = 50)
    private String memberSince;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_profile_update_at")
    private LocalDateTime lastProfileUpdateAt;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion = 0L;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "last_password_reset_operation_id", length = 64)
    private String lastPasswordResetOperationId;

    @Column(name = "no_show_count", nullable = false)
    private int noShowCount = 0;

    public JpaUser() {}

    public JpaUser(String id, String name, String email, String phone, String role) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.role = role != null ? role : "MEMBER";
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

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
    public void setLastPasswordResetOperationId(String lastPasswordResetOperationId) { this.lastPasswordResetOperationId = lastPasswordResetOperationId; }

    public int getNoShowCount() { return noShowCount; }
    public void setNoShowCount(int noShowCount) { this.noShowCount = noShowCount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaUser that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
