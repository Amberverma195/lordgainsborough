package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.orders.rate-limit")
public class OrderRateLimitProperties {

    private int ipRequests = 10;
    private Duration ipWindow = Duration.ofMinutes(15);

    private int accountRequests = 5;
    private Duration accountWindow = Duration.ofMinutes(15);

    private int maxActiveOrders = 3;

    private int maxCartItems = 30;
    private int minItemQuantity = 1;
    private int maxItemQuantity = 20;
    private int maxTotalQuantity = 50;

    private double maxOrderTotal = 1000.00;

    public int getIpRequests() {
        return ipRequests;
    }

    public void setIpRequests(int ipRequests) {
        this.ipRequests = ipRequests;
    }

    public Duration getIpWindow() {
        return ipWindow;
    }

    public void setIpWindow(Duration ipWindow) {
        this.ipWindow = ipWindow;
    }

    public int getAccountRequests() {
        return accountRequests;
    }

    public void setAccountRequests(int accountRequests) {
        this.accountRequests = accountRequests;
    }

    public Duration getAccountWindow() {
        return accountWindow;
    }

    public void setAccountWindow(Duration accountWindow) {
        this.accountWindow = accountWindow;
    }

    public int getMaxActiveOrders() {
        return maxActiveOrders;
    }

    public void setMaxActiveOrders(int maxActiveOrders) {
        this.maxActiveOrders = maxActiveOrders;
    }

    public int getMaxCartItems() {
        return maxCartItems;
    }

    public void setMaxCartItems(int maxCartItems) {
        this.maxCartItems = maxCartItems;
    }

    public int getMinItemQuantity() {
        return minItemQuantity;
    }

    public void setMinItemQuantity(int minItemQuantity) {
        this.minItemQuantity = minItemQuantity;
    }

    public int getMaxItemQuantity() {
        return maxItemQuantity;
    }

    public void setMaxItemQuantity(int maxItemQuantity) {
        this.maxItemQuantity = maxItemQuantity;
    }

    public int getMaxTotalQuantity() {
        return maxTotalQuantity;
    }

    public void setMaxTotalQuantity(int maxTotalQuantity) {
        this.maxTotalQuantity = maxTotalQuantity;
    }

    public double getMaxOrderTotal() {
        return maxOrderTotal;
    }

    public void setMaxOrderTotal(double maxOrderTotal) {
        this.maxOrderTotal = maxOrderTotal;
    }
}
