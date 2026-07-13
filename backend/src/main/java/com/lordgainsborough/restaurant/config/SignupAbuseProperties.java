package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configurable secondary abuse limits for signup OTP (layered on per-email challenge rules).
 * Defaults intentionally allow shared NATs / public Wi-Fi without being useless against scraping.
 */
@ConfigurationProperties(prefix = "app.abuse")
public class SignupAbuseProperties {

    /** HMAC secret used to hash client IPs before storage. */
    private String ipHashSecret = "";

    /**
     * Proxies whose {@code X-Forwarded-For} / {@code X-Real-IP} headers may be trusted.
     * Entries may be exact IPs or CIDR ranges (e.g. {@code 10.0.0.0/8}).
     */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));

    private int initialSendsPerIp = 20;
    private Duration initialSendWindow = Duration.ofMinutes(15);

    private int totalSendsPerIp = 100;
    private Duration totalSendWindow = Duration.ofHours(24);

    private int verificationsPerIp = 60;
    private Duration verificationWindow = Duration.ofMinutes(15);

    private int globalProviderSends = 200;
    private Duration globalProviderWindow = Duration.ofMinutes(1);

    /** After this many initial sends from one IP in the short window, require CAPTCHA. */
    private int captchaAfterInitialSends = 12;

    private boolean captchaEnabled = false;
    /** Shared token accepted when CAPTCHA is enabled (replace with a real verifier in production). */
    private String captchaSharedToken = "";

    public String getIpHashSecret() {
        return ipHashSecret;
    }

    public void setIpHashSecret(String ipHashSecret) {
        this.ipHashSecret = ipHashSecret;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public int getInitialSendsPerIp() {
        return initialSendsPerIp;
    }

    public void setInitialSendsPerIp(int initialSendsPerIp) {
        this.initialSendsPerIp = initialSendsPerIp;
    }

    public Duration getInitialSendWindow() {
        return initialSendWindow;
    }

    public void setInitialSendWindow(Duration initialSendWindow) {
        this.initialSendWindow = initialSendWindow;
    }

    public int getTotalSendsPerIp() {
        return totalSendsPerIp;
    }

    public void setTotalSendsPerIp(int totalSendsPerIp) {
        this.totalSendsPerIp = totalSendsPerIp;
    }

    public Duration getTotalSendWindow() {
        return totalSendWindow;
    }

    public void setTotalSendWindow(Duration totalSendWindow) {
        this.totalSendWindow = totalSendWindow;
    }

    public int getVerificationsPerIp() {
        return verificationsPerIp;
    }

    public void setVerificationsPerIp(int verificationsPerIp) {
        this.verificationsPerIp = verificationsPerIp;
    }

    public Duration getVerificationWindow() {
        return verificationWindow;
    }

    public void setVerificationWindow(Duration verificationWindow) {
        this.verificationWindow = verificationWindow;
    }

    public int getGlobalProviderSends() {
        return globalProviderSends;
    }

    public void setGlobalProviderSends(int globalProviderSends) {
        this.globalProviderSends = globalProviderSends;
    }

    public Duration getGlobalProviderWindow() {
        return globalProviderWindow;
    }

    public void setGlobalProviderWindow(Duration globalProviderWindow) {
        this.globalProviderWindow = globalProviderWindow;
    }

    public int getCaptchaAfterInitialSends() {
        return captchaAfterInitialSends;
    }

    public void setCaptchaAfterInitialSends(int captchaAfterInitialSends) {
        this.captchaAfterInitialSends = captchaAfterInitialSends;
    }

    public boolean isCaptchaEnabled() {
        return captchaEnabled;
    }

    public void setCaptchaEnabled(boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
    }

    public String getCaptchaSharedToken() {
        return captchaSharedToken;
    }

    public void setCaptchaSharedToken(String captchaSharedToken) {
        this.captchaSharedToken = captchaSharedToken;
    }
}
