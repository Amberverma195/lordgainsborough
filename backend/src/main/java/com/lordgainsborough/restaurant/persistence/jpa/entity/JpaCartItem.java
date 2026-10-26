package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "cart_items")
public class JpaCartItem {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private JpaCart cart;

    /**
     * Managed by Hibernate @OrderColumn on JpaCart.items.
     * Exposed read-only on the child entity for query or inspection purposes.
     * Collection ordering in JpaCart.getItems() is the authoritative source.
     */
    @Column(name = "item_index", insertable = false, updatable = false)
    private Integer itemIndex;

    @Column(name = "menu_item_id", length = 64)
    private String menuItemId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", precision = 10, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "image", length = 1024)
    private String image;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    public JpaCartItem() {}

    public JpaCartItem(String id, String menuItemId, String name, String description, BigDecimal price, String category, String image, int quantity) {
        this.id = id;
        this.menuItemId = menuItemId;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.image = image;
        this.quantity = quantity;
    }

    public JpaCartItem(String menuItemId, String name, String description, BigDecimal price, String category, String image, int quantity) {
        this(null, menuItemId, name, description, price, category, image, quantity);
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public JpaCart getCart() { return cart; }
    public void setCart(JpaCart cart) { this.cart = cart; }

    public Integer getItemIndex() { return itemIndex; }

    /**
     * Internal/package-private setter. Callers must reorder the parent
     * {@link JpaCart#getItems()} collection to persist new positions.
     * Direct invocation does not alter database persistence.
     */
    void setItemIndex(Integer itemIndex) { this.itemIndex = itemIndex; }

    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaCartItem that)) return false;
        return this.id != null && Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
