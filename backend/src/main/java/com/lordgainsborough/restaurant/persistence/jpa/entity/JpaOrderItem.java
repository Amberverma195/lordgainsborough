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
@Table(name = "order_items")
public class JpaOrderItem {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private JpaCustomerOrder order;

    /**
     * Managed by Hibernate @OrderColumn on JpaCustomerOrder.items.
     * Exposed read-only on the child entity for query or inspection purposes.
     */
    @Column(name = "item_index", insertable = false, updatable = false)
    private Integer itemIndex;

    @Column(name = "menu_item_id", length = 64)
    private String menuItemId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "total", precision = 10, scale = 2, nullable = false)
    private BigDecimal total;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public JpaOrderItem() {}

    public JpaOrderItem(String id, String menuItemId, String name, int quantity, BigDecimal unitPrice, BigDecimal total) {
        this.id = id;
        this.menuItemId = menuItemId;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
    }

    public JpaOrderItem(String menuItemId, String name, int quantity, BigDecimal unitPrice, BigDecimal total) {
        this(null, menuItemId, name, quantity, unitPrice, total);
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.total == null && this.unitPrice != null) {
            this.total = this.unitPrice.multiply(BigDecimal.valueOf(this.quantity)).setScale(2);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public JpaCustomerOrder getOrder() { return order; }
    public void setOrder(JpaCustomerOrder order) { this.order = order; }

    public Integer getItemIndex() { return itemIndex; }
    public void setItemIndex(Integer itemIndex) { this.itemIndex = itemIndex; }

    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaOrderItem that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
