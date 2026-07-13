package com.lordgainsborough.restaurant.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "orders")
public class CustomerOrder {
    @Id
    private String id;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String pickupDate;
    private String pickupTime;
    private String specialInstructions;
    private String status = "NEW";
    private int averageReadyMinutes = 25;
    private List<OrderItem> items = new ArrayList<>();
    private List<OrderMessage> messages = new ArrayList<>();
    private double subtotal;
    private double tax;
    private double total;
    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime readyAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime noShowAt;
    private boolean reviewed;
    @JsonIgnore
    private String trackingTokenHash;
    @Transient
    private int customerOrderCount;
    @Transient
    private int customerNoShowCount;

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

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public List<OrderMessage> getMessages() { return messages; }
    public void setMessages(List<OrderMessage> messages) { this.messages = messages; }

    public double getSubtotal() { return subtotal; }
    public void setSubtotal(double subtotal) { this.subtotal = subtotal; }

    public double getTax() { return tax; }
    public void setTax(double tax) { this.tax = tax; }

    public double getTotal() { return total; }
    public void setTotal(double total) { this.total = total; }

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

    public int getCustomerOrderCount() { return customerOrderCount; }
    public void setCustomerOrderCount(int customerOrderCount) { this.customerOrderCount = customerOrderCount; }

    public int getCustomerNoShowCount() { return customerNoShowCount; }
    public void setCustomerNoShowCount(int customerNoShowCount) { this.customerNoShowCount = customerNoShowCount; }

    public static class OrderItem {
        private String menuItemId;
        private String name;
        private int quantity;
        private double unitPrice;
        private double total;
        private String notes;

        public String getMenuItemId() { return menuItemId; }
        public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }

        public double getUnitPrice() { return unitPrice; }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }

        public double getTotal() { return total; }
        public void setTotal(double total) { this.total = total; }

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    public static class OrderMessage {
        private String id;
        private String senderRole;
        private String senderName;
        private String text;
        private LocalDateTime createdAt;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getSenderRole() { return senderRole; }
        public void setSenderRole(String senderRole) { this.senderRole = senderRole; }

        public String getSenderName() { return senderName; }
        public void setSenderName(String senderName) { this.senderName = senderName; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}
