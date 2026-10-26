package com.lordgainsborough.restaurant.persistence.jpa.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "customer_orders")
public class JpaCustomerOrder {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "customer_name", length = 255, nullable = false)
    private String customerName;

    @Column(name = "customer_email", length = 255, nullable = false)
    private String customerEmail;

    @Column(name = "customer_phone", length = 50, nullable = false)
    private String customerPhone;

    @Column(name = "pickup_date", length = 20, nullable = false)
    private String pickupDate;

    @Column(name = "pickup_time", length = 20)
    private String pickupTime;

    @Column(name = "special_instructions", columnDefinition = "TEXT")
    private String specialInstructions;

    @Column(name = "status", length = 50, nullable = false)
    private String status = "NEW";

    @Column(name = "average_ready_minutes", nullable = false)
    private int averageReadyMinutes = 25;

    @Column(name = "subtotal", precision = 10, scale = 2, nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO.setScale(2);

    @Column(name = "tax", precision = 10, scale = 2, nullable = false)
    private BigDecimal tax = BigDecimal.ZERO.setScale(2);

    @Column(name = "total", precision = 10, scale = 2, nullable = false)
    private BigDecimal total = BigDecimal.ZERO.setScale(2);

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "no_show_at")
    private LocalDateTime noShowAt;

    @Column(name = "reviewed", nullable = false)
    private boolean reviewed = false;

    @JsonIgnore
    @Column(name = "tracking_token_hash", length = 255)
    private String trackingTokenHash;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "item_index", nullable = false)
    private List<JpaOrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "item_index", nullable = false)
    private List<JpaOrderMessage> messages = new ArrayList<>();

    public JpaCustomerOrder() {}

    public JpaCustomerOrder(String id, String customerName, String customerEmail, String customerPhone, String pickupDate) {
        this.id = id;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.customerPhone = customerPhone;
        this.pickupDate = pickupDate;
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.subtotal == null) {
            this.subtotal = BigDecimal.ZERO.setScale(2);
        }
        if (this.tax == null) {
            this.tax = BigDecimal.ZERO.setScale(2);
        }
        if (this.total == null) {
            this.total = BigDecimal.ZERO.setScale(2);
        }
    }

    public void addItem(JpaOrderItem item) {
        if (item != null) {
            items.add(item);
            item.setOrder(this);
        }
    }

    public void removeItem(JpaOrderItem item) {
        if (item != null) {
            items.remove(item);
            item.setOrder(null);
        }
    }

    public void addMessage(JpaOrderMessage message) {
        if (message != null) {
            messages.add(message);
            message.setOrder(this);
        }
    }

    public void removeMessage(JpaOrderMessage message) {
        if (message != null) {
            messages.remove(message);
            message.setOrder(null);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getPickupDate() { return pickupDate; }
    public void setPickupDate(String pickupDate) { this.pickupDate = pickupDate; }

    public String getPickupTime() { return pickupTime; }
    public void setPickupTime(String pickupTime) { this.pickupTime = pickupTime; }

    public String getSpecialInstructions() { return specialInstructions; }
    public void setSpecialInstructions(String specialInstructions) { this.specialInstructions = specialInstructions; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAverageReadyMinutes() { return averageReadyMinutes; }
    public void setAverageReadyMinutes(int averageReadyMinutes) { this.averageReadyMinutes = averageReadyMinutes; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getTax() { return tax; }
    public void setTax(BigDecimal tax) { this.tax = tax; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }

    public LocalDateTime getReadyAt() { return readyAt; }
    public void setReadyAt(LocalDateTime readyAt) { this.readyAt = readyAt; }

    public LocalDateTime getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(LocalDateTime pickedUpAt) { this.pickedUpAt = pickedUpAt; }

    public LocalDateTime getNoShowAt() { return noShowAt; }
    public void setNoShowAt(LocalDateTime noShowAt) { this.noShowAt = noShowAt; }

    public boolean isReviewed() { return reviewed; }
    public void setReviewed(boolean reviewed) { this.reviewed = reviewed; }

    public String getTrackingTokenHash() { return trackingTokenHash; }
    public void setTrackingTokenHash(String trackingTokenHash) { this.trackingTokenHash = trackingTokenHash; }

    public List<JpaOrderItem> getItems() { return items; }
    public void setItems(List<JpaOrderItem> items) {
        this.items.clear();
        if (items != null) {
            for (JpaOrderItem item : items) {
                addItem(item);
            }
        }
    }

    public List<JpaOrderMessage> getMessages() { return messages; }
    public void setMessages(List<JpaOrderMessage> messages) {
        this.messages.clear();
        if (messages != null) {
            for (JpaOrderMessage message : messages) {
                addMessage(message);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaCustomerOrder that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
