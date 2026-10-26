package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_messages")
public class JpaOrderMessage {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private JpaCustomerOrder order;

    /**
     * Managed by Hibernate @OrderColumn on JpaCustomerOrder.messages.
     * Exposed read-only on the child entity for query or inspection purposes.
     */
    @Column(name = "item_index", insertable = false, updatable = false)
    private Integer itemIndex;

    @Column(name = "sender_role", length = 50, nullable = false)
    private String senderRole;

    @Column(name = "sender_name", length = 255, nullable = false)
    private String senderName;

    @Column(name = "text", columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public JpaOrderMessage() {}

    public JpaOrderMessage(String id, String senderRole, String senderName, String text) {
        this.id = id;
        this.senderRole = senderRole;
        this.senderName = senderName;
        this.text = text;
        this.createdAt = LocalDateTime.now();
    }

    public JpaOrderMessage(String senderRole, String senderName, String text) {
        this(null, senderRole, senderName, text);
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

    public JpaCustomerOrder getOrder() { return order; }
    public void setOrder(JpaCustomerOrder order) { this.order = order; }

    public Integer getItemIndex() { return itemIndex; }
    public void setItemIndex(Integer itemIndex) { this.itemIndex = itemIndex; }

    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String senderRole) { this.senderRole = senderRole; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaOrderMessage that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
