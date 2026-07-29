package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth.login")
public class LoginRateLimitProperties {

    private int ipAttempts = 20;
    private Duration ipWindow = Duration.ofMinutes(5);
    private int identifierIpFailures = 5;
    private Duration failureWindow = Duration.ofMinutes(5);
    private Duration cooldown = Duration.ofSeconds(30);

    public int getIpAttempts() {
        return ipAttempts;
    }

    public void setIpAttempts(int ipAttempts) {
        this.ipAttempts = ipAttempts;
    }

    public Duration getIpWindow() {
        return ipWindow;
    }

    public void setIpWindow(Duration ipWindow) {
        this.ipWindow = ipWindow;
    }

    public int getIdentifierIpFailures() {
        return identifierIpFailures;
    }

    public void setIdentifierIpFailures(int identifierIpFailures) {
        this.identifierIpFailures = identifierIpFailures;
    }

    public Duration getFailureWindow() {
        return failureWindow;
    }

    public void setFailureWindow(Duration failureWindow) {
        this.failureWindow = failureWindow;
    }

    public Duration getCooldown() {
        return cooldown;
    }

    public void setCooldown(Duration cooldown) {
        this.cooldown = cooldown;
    }
}
