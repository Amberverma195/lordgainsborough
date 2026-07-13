package com.lordgainsborough.restaurant.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "carts")
public class Cart {
    /** Carts expire 7 days after the last update (MongoDB TTL index). */
    public static final int TTL_SECONDS = 7 * 24 * 60 * 60;

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private List<CartItem> items = new ArrayList<>();

    @Indexed(name = "cart_updated_at_ttl", expireAfterSeconds = TTL_SECONDS)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public List<CartItem> getItems() { return items; }
    public void setItems(List<CartItem> items) { this.items = items; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isExpired() {
        if (updatedAt == null) {
            return true;
        }
        return updatedAt.isBefore(Instant.now().minusSeconds(TTL_SECONDS));
    }

    public static class CartItem {
        private String menuItemId;
        private String name;
        private String description;
        private double price;
        private String category;
        private String image;
        private int quantity;

        public String getMenuItemId() { return menuItemId; }
        public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }
}
