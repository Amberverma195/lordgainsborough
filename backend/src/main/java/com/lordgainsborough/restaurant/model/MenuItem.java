package com.lordgainsborough.restaurant.model;

public class MenuItem {

    private String id;
    private String name;
    private String description;
    private Double price;
    private String category;
    private String image;
    private boolean available = true;
    private boolean isMostLoved = false;

    public MenuItem() {}

    public MenuItem(String id, String name, String description, Double price, String category, String image) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.image = image;
        this.available = true;
    }

    public MenuItem(String id, String name, String description, Double price, String category, String image, boolean available) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.image = image;
        this.available = available;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public boolean isMostLoved() { return isMostLoved; }
    public void setMostLoved(boolean mostLoved) { isMostLoved = mostLoved; }
}

