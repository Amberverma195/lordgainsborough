package com.lordgainsborough.restaurant.persistence.jpa.entity;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "carts")
public class JpaCart {

    /** Carts expire 7 days after the last update (matching original MongoDB TTL). */
    public static final int TTL_SECONDS = 7 * 24 * 60 * 60;

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private JpaUser user;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "item_index", nullable = false)
    private List<JpaCartItem> items = new ArrayList<>();

    public JpaCart() {}

    public JpaCart(String id, JpaUser user) {
        this.id = id;
        this.user = user;
        this.updatedAt = Instant.now();
    }

    public JpaCart(JpaUser user) {
        this(null, user);
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isExpired() {
        if (this.updatedAt == null) {
            return true;
        }
        return this.updatedAt.isBefore(Instant.now().minusSeconds(TTL_SECONDS));
    }

    public boolean isExpired(Instant referenceTime) {
        if (this.updatedAt == null) {
            return true;
        }
        return this.updatedAt.isBefore(referenceTime.minusSeconds(TTL_SECONDS));
    }

    public void addItem(JpaCartItem item) {
        if (item != null) {
            items.add(item);
            item.setCart(this);
        }
    }

    public void removeItem(JpaCartItem item) {
        if (item != null && items.remove(item)) {
            if (item.getCart() == this) {
                item.setCart(null);
            }
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public JpaUser getUser() { return user; }
    public void setUser(JpaUser user) { this.user = user; }

    public String getUserId() { return user != null ? user.getId() : null; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<JpaCartItem> getItems() { return items; }
    public void setItems(List<JpaCartItem> items) {
        if (this.items == items) {
            return;
        }
        if (items == null) {
            Iterator<JpaCartItem> iterator = this.items.iterator();
            while (iterator.hasNext()) {
                JpaCartItem existing = iterator.next();
                iterator.remove();
                if (existing.getCart() == this) {
                    existing.setCart(null);
                }
            }
            return;
        }

        List<JpaCartItem> incoming = new ArrayList<>(items);

        // 1. Remove elements no longer present in incoming list
        Iterator<JpaCartItem> iterator = this.items.iterator();
        while (iterator.hasNext()) {
            JpaCartItem existing = iterator.next();
            if (!incoming.contains(existing)) {
                iterator.remove();
                if (existing.getCart() == this) {
                    existing.setCart(null);
                }
            }
        }

        // 2. Synchronize order and add new items
        for (int i = 0; i < incoming.size(); i++) {
            JpaCartItem targetItem = incoming.get(i);
            targetItem.setCart(this);
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
        if (!(o instanceof JpaCart that)) return false;
        return this.id != null && Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
