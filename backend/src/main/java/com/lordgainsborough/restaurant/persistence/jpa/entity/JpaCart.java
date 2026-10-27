package com.lordgainsborough.restaurant.persistence.jpa.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "carts")
public class JpaCart {

    public static final int TTL_SECONDS = 7 * 24 * 60 * 60;

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private JpaUser user;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Transient
    private boolean explicitTouch;

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
        explicitTouch = false;
    }

    @PreUpdate
    public void preUpdate() {
        if (!explicitTouch) this.updatedAt = Instant.now();
        explicitTouch = false;
    }

    /**
     * Touches the cart by advancing {@link #updatedAt} to {@link Instant#now()}.
     * <p>
     * In JPA/Hibernate, modifying child entities (e.g. adding, removing, updating,
     * or reordering cart items) does not dirty the parent entity row and therefore
     * does not trigger the {@code @PreUpdate} lifecycle callback on {@code JpaCart}.
     * Calling {@code touch()} ensures the parent entity is marked dirty within the
     * active persistence context, causing an {@code UPDATE carts SET updated_at = ...}
     * upon transaction flush/commit and resetting the 7-day TTL countdown.
     * </p>
     */
    public void touch() {
        this.updatedAt = Instant.now();
        explicitTouch = false;
    }

    /**
     * Touches the cart with a specific timestamp.
     * The next persistence lifecycle callback preserves this caller-owned Clock value.
     *
     * @param timestamp the new instant to set
     */
    public void touch(Instant timestamp) {
        this.updatedAt = timestamp != null ? timestamp : Instant.now();
        explicitTouch = true;
    }

    /**
     * Checks whether the cart has expired relative to the current system clock.
     * Carts expire 7 days after the last {@link #updatedAt} timestamp.
     *
     * @return {@code true} if expired or if {@link #updatedAt} is null (fail-closed); {@code false} otherwise
     */
    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    /**
     * Checks whether the cart has expired relative to a specific reference instant.
     * <p>
     * Boundary is exact: at {@code referenceTime.equals(updatedAt.plusSeconds(TTL_SECONDS))},
     * the 7-day TTL has fully elapsed and the cart is evaluated as expired.
     * </p>
     *
     * @param referenceTime the reference instant to check against (defaults to {@link Instant#now()} if null)
     * @return {@code true} if expired or if {@link #updatedAt} is null (fail-closed); {@code false} otherwise
     */
    public boolean isExpired(Instant referenceTime) {
        if (this.updatedAt == null) {
            return true;
        }
        Instant now = referenceTime != null ? referenceTime : Instant.now();
        Instant expiresAt = this.updatedAt.plusSeconds(TTL_SECONDS);
        return !now.isBefore(expiresAt);
    }

    public void addItem(JpaCartItem item) {
        if (item != null) {
            items.add(item);
            item.setCart(this);
            touch();
        }
    }

    public void removeItem(JpaCartItem item) {
        if (item != null && items.remove(item)) {
            if (item.getCart() == this) {
                item.setCart(null);
            }
            touch();
        }
    }

    /**
     * Reorders an item within the cart from one index to another, touching the cart
     * so that the updated timestamp advances.
     *
     * @param fromIndex source position
     * @param toIndex   target position
     */
    public void reorderItems(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < items.size() && toIndex >= 0 && toIndex < items.size()) {
            if (fromIndex != toIndex) {
                JpaCartItem moved = items.remove(fromIndex);
                items.add(toIndex, moved);
                touch();
            }
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @JsonIgnore
    public JpaUser getUser() { return user; }
    public void setUser(JpaUser user) { this.user = user; }

    @JsonProperty("userId")
    public String getUserId() { return user != null ? user.getId() : null; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
        this.explicitTouch = false;
    }

    /**
     * Returns the list of cart items.
     * <p>
     * <b>Recommendation:</b> Direct mutations to this list (such as {@code getItems().add(...)}
     * or {@code Collections.swap(getItems(), ...)}) bypass the {@link #touch()} mechanism
     * and will not automatically update {@code carts.updated_at}.
     * Callers should use dedicated helper methods ({@link #addItem(JpaCartItem)},
     * {@link #removeItem(JpaCartItem)}, {@link #reorderItems(int, int)}, or {@link #setItems(List)})
     * or explicitly invoke {@link #touch()} when modifying cart items to guarantee the 7-day
     * expiration countdown is refreshed.
     * </p>
     */
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
            touch();
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
        touch();
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
