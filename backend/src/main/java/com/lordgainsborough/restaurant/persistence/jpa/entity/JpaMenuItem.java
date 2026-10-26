package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "menu_items")
public class JpaMenuItem {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", precision = 10, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "category", length = 255, nullable = false)
    private String category;

    @Column(name = "image", length = 1024)
    private String image;

    @Column(name = "available", nullable = false)
    private boolean available = true;

    @Column(name = "is_most_loved", nullable = false)
    private boolean isMostLoved = false;

    public JpaMenuItem() {}

    public JpaMenuItem(String id, String name, String description, BigDecimal price, String category, String image) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.image = image;
        this.available = true;
        this.isMostLoved = false;
    }

    public JpaMenuItem(String id, String name, String description, BigDecimal price, String category, String image, boolean available, boolean isMostLoved) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.image = image;
        this.available = available;
        this.isMostLoved = isMostLoved;
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    public boolean isMostLoved() { return isMostLoved; }
    public void setMostLoved(boolean mostLoved) { isMostLoved = mostLoved; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaMenuItem that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
