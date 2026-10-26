package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_review_items")
public class JpaOrderReviewItem {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private JpaOrderReview review;

    /**
     * Managed by Hibernate @OrderColumn on JpaOrderReview.items.
     * Exposed read-only on the child entity for query or inspection purposes.
     * Collection ordering in JpaOrderReview.getItems() is the authoritative source.
     */
    @Column(name = "item_index", insertable = false, updatable = false)
    private Integer itemIndex;

    @Column(name = "menu_item_id", length = 64)
    private String menuItemId;

    @Column(name = "item_name", length = 255, nullable = false)
    private String itemName;

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public JpaOrderReviewItem() {}

    public JpaOrderReviewItem(String id, String menuItemId, String itemName, int rating, String description) {
        this.id = id;
        this.menuItemId = menuItemId;
        this.itemName = itemName;
        this.rating = rating;
        this.description = description;
    }

    public JpaOrderReviewItem(String menuItemId, String itemName, int rating, String description) {
        this(null, menuItemId, itemName, rating, description);
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public JpaOrderReview getReview() { return review; }
    public void setReview(JpaOrderReview review) { this.review = review; }

    public Integer getItemIndex() { return itemIndex; }

    /**
     * Internal/package-private setter. Callers must reorder the parent
     * {@link JpaOrderReview#getItems()} collection to persist new positions.
     * Direct invocation does not alter database persistence.
     */
    void setItemIndex(Integer itemIndex) { this.itemIndex = itemIndex; }

    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaOrderReviewItem that)) return false;
        return this.id != null && Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
