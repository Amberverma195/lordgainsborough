package com.lordgainsborough.restaurant.dto;

import com.lordgainsborough.restaurant.model.CustomerOrder;

import java.time.LocalDateTime;
import java.util.List;

public class OrderStatusResponse {
    private String id;
    private String pickupDate;
    private String pickupTime;
    private String specialInstructions;
    private String status;
    private int averageReadyMinutes;
    private List<CustomerOrder.OrderItem> items;
    private List<CustomerOrder.OrderMessage> messages;
    private double subtotal;
    private double tax;
    private double total;
    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime readyAt;
    private LocalDateTime pickedUpAt;
    private boolean reviewed;

    public static OrderStatusResponse from(CustomerOrder order) {
        OrderStatusResponse response = new OrderStatusResponse();
        response.setId(order.getId());
        response.setPickupDate(order.getPickupDate());
        response.setPickupTime(order.getPickupTime());
        response.setSpecialInstructions(order.getSpecialInstructions());
        response.setStatus(order.getStatus());
        response.setAverageReadyMinutes(order.getAverageReadyMinutes());
        response.setItems(order.getItems());
        response.setMessages(order.getMessages());
        response.setSubtotal(order.getSubtotal());
        response.setTax(order.getTax());
        response.setTotal(order.getTotal());
        response.setCreatedAt(order.getCreatedAt());
        response.setAcceptedAt(order.getAcceptedAt());
        response.setReadyAt(order.getReadyAt());
        response.setPickedUpAt(order.getPickedUpAt());
        response.setReviewed(order.isReviewed());
        return response;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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

    public List<CustomerOrder.OrderItem> getItems() { return items; }
    public void setItems(List<CustomerOrder.OrderItem> items) { this.items = items; }

    public List<CustomerOrder.OrderMessage> getMessages() { return messages; }
    public void setMessages(List<CustomerOrder.OrderMessage> messages) { this.messages = messages; }

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

    public boolean isReviewed() { return reviewed; }
    public void setReviewed(boolean reviewed) { this.reviewed = reviewed; }
}
