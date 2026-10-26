package com.lordgainsborough.restaurant.persistence.jpa.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_reviews")
public class JpaOrderReview {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private JpaCustomerOrder order;

    @Column(name = "customer_name", length = 255, nullable = false)
    private String customerName;

    @Column(name = "customer_email", length = 255, nullable = false)
    private String customerEmail;

    @Column(name = "customer_phone", length = 50, nullable = false)
    private String customerPhone;

    @Column(name = "status", length = 50, nullable = false)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "item_index", nullable = false)
    private List<JpaOrderReviewItem> items = new ArrayList<>();

    public JpaOrderReview() {}

    public JpaOrderReview(String id, JpaCustomerOrder order, String customerName, String customerEmail, String customerPhone) {
        this.id = id;
        this.order = order;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.customerPhone = customerPhone;
    }

    public JpaOrderReview(JpaCustomerOrder order, String customerName, String customerEmail, String customerPhone) {
        this(null, order, customerName, customerEmail, customerPhone);
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = this.createdAt;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void addItem(JpaOrderReviewItem item) {
        if (item != null) {
            items.add(item);
            item.setReview(this);
        }
    }

    public void removeItem(JpaOrderReviewItem item) {
        if (item != null && items.remove(item)) {
            if (item.getReview() == this) {
                item.setReview(null);
            }
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public JpaCustomerOrder getOrder() { return order; }
    public void setOrder(JpaCustomerOrder order) { this.order = order; }

    public String getOrderId() { return order != null ? order.getId() : null; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<JpaOrderReviewItem> getItems() { return items; }
    public void setItems(List<JpaOrderReviewItem> items) {
        if (this.items == items) {
            return;
        }
        if (items == null) {
            Iterator<JpaOrderReviewItem> iterator = this.items.iterator();
            while (iterator.hasNext()) {
                JpaOrderReviewItem existing = iterator.next();
                iterator.remove();
                if (existing.getReview() == this) {
                    existing.setReview(null);
                }
            }
            return;
        }

        List<JpaOrderReviewItem> incoming = new ArrayList<>(items);

        // 1. Remove elements no longer present in incoming list
        Iterator<JpaOrderReviewItem> iterator = this.items.iterator();
        while (iterator.hasNext()) {
            JpaOrderReviewItem existing = iterator.next();
            if (!incoming.contains(existing)) {
                iterator.remove();
                if (existing.getReview() == this) {
                    existing.setReview(null);
                }
            }
        }

        // 2. Synchronize order and add new items
        for (int i = 0; i < incoming.size(); i++) {
            JpaOrderReviewItem targetItem = incoming.get(i);
            targetItem.setReview(this);
            int currentIndex = this.items.indexOf(targetItem);
            if (currentIndex == -1) {
                if (i < this.items.size()) {
                    this.items.add(i, targetItem);
                } else {
                    this.items.add(targetItem);
                }
            } else if (currentIndex != i) {
                this.items.remove(currentIndex);
                if (i < this.items.size()) {
                    this.items.add(i, targetItem);
                } else {
                    this.items.add(targetItem);
                }
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaOrderReview that)) return false;
        return this.id != null && Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
