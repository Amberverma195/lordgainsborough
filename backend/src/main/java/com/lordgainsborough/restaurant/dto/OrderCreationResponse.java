package com.lordgainsborough.restaurant.dto;

public class OrderCreationResponse {
    private OrderStatusResponse order;
    private String trackingToken;

    public OrderCreationResponse(OrderStatusResponse order, String trackingToken) {
        this.order = order;
        this.trackingToken = trackingToken;
    }

    public OrderStatusResponse getOrder() { return order; }
    public void setOrder(OrderStatusResponse order) { this.order = order; }

    public String getTrackingToken() { return trackingToken; }
    public void setTrackingToken(String trackingToken) { this.trackingToken = trackingToken; }
}
