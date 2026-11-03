package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.reservations.rate-limit")
public class ReservationRateLimitProperties {

    private int ipRequests = 10;
    private Duration ipWindow = Duration.ofMinutes(15);

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
}
