package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth.password-reset")
public class PasswordResetProperties {

    private Duration resendCooldown = Duration.ofSeconds(30);
    private int maxResends = 5;
    private Duration codeTtl = Duration.ofMinutes(10);
    private Duration challengeTtl = Duration.ofMinutes(30);
    private Duration minRemainingChallengeLifetime = Duration.ofMinutes(1);
    private int maxVerificationFailures = 5;
    private int newChallengesPerEmail = 3;
    private Duration newChallengeWindow = Duration.ofHours(1);
    private int emailOperationsPerIp = 10;
    private Duration emailOperationWindow = Duration.ofMinutes(15);
    private int verificationsPerIp = 30;
    private Duration verificationWindow = Duration.ofMinutes(15);
    private int globalEmailOperations = 100;
    private Duration globalEmailWindow = Duration.ofMinutes(1);
    private Duration sendLease = Duration.ofMinutes(1);
    private boolean requireTransaction = false;
    private String queueEncryptionKey;
    private int queueMaxAttempts = 5;
    private Duration queueRetryBackoff = Duration.ofSeconds(10);
    private Duration queueMaxRetryBackoff = Duration.ofMinutes(2);
    private Duration queueRetention = Duration.ofDays(1);

    public int getQueueMaxAttempts() { return queueMaxAttempts; }
    public void setQueueMaxAttempts(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("queueMaxAttempts must be 1..100");
        queueMaxAttempts = value;
    }
    public Duration getQueueRetryBackoff() { return queueRetryBackoff; }
    public void setQueueRetryBackoff(Duration value) { queueRetryBackoff = positive(value); }
    public Duration getQueueMaxRetryBackoff() { return queueMaxRetryBackoff; }
    public void setQueueMaxRetryBackoff(Duration value) { queueMaxRetryBackoff = positive(value); }
    public Duration getQueueRetention() { return queueRetention; }
    public void setQueueRetention(Duration value) { queueRetention = positive(value); }
    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero())
            throw new IllegalArgumentException("Queue durations must be positive");
        return value;
    }

    public Duration getResendCooldown() { return resendCooldown; }
    public void setResendCooldown(Duration value) { this.resendCooldown = value; }
    public int getMaxResends() { return maxResends; }
    public void setMaxResends(int value) { this.maxResends = value; }
    public Duration getCodeTtl() { return codeTtl; }
    public void setCodeTtl(Duration value) { this.codeTtl = value; }
    public Duration getChallengeTtl() { return challengeTtl; }
    public void setChallengeTtl(Duration value) { this.challengeTtl = value; }
    public Duration getMinRemainingChallengeLifetime() { return minRemainingChallengeLifetime; }
    public void setMinRemainingChallengeLifetime(Duration minRemainingChallengeLifetime) {
        this.minRemainingChallengeLifetime = minRemainingChallengeLifetime;
    }
    public int getMaxVerificationFailures() { return maxVerificationFailures; }
    public void setMaxVerificationFailures(int value) { this.maxVerificationFailures = value; }
    public int getNewChallengesPerEmail() { return newChallengesPerEmail; }
    public void setNewChallengesPerEmail(int value) { this.newChallengesPerEmail = value; }
    public Duration getNewChallengeWindow() { return newChallengeWindow; }
    public void setNewChallengeWindow(Duration value) { this.newChallengeWindow = value; }
    public int getEmailOperationsPerIp() { return emailOperationsPerIp; }
    public void setEmailOperationsPerIp(int value) { this.emailOperationsPerIp = value; }
    public Duration getEmailOperationWindow() { return emailOperationWindow; }
    public void setEmailOperationWindow(Duration value) { this.emailOperationWindow = value; }
    public int getVerificationsPerIp() { return verificationsPerIp; }
    public void setVerificationsPerIp(int value) { this.verificationsPerIp = value; }
    public Duration getVerificationWindow() { return verificationWindow; }
    public void setVerificationWindow(Duration value) { this.verificationWindow = value; }
    public int getGlobalEmailOperations() { return globalEmailOperations; }
    public void setGlobalEmailOperations(int value) { this.globalEmailOperations = value; }
    public Duration getGlobalEmailWindow() { return globalEmailWindow; }
    public void setGlobalEmailWindow(Duration value) { this.globalEmailWindow = value; }
    public Duration getSendLease() { return sendLease; }
    public void setSendLease(Duration value) { this.sendLease = value; }
    public boolean isRequireTransaction() { return requireTransaction; }
    public void setRequireTransaction(boolean requireTransaction) { this.requireTransaction = requireTransaction; }
    public String getQueueEncryptionKey() { return queueEncryptionKey; }
    public void setQueueEncryptionKey(String queueEncryptionKey) { this.queueEncryptionKey = queueEncryptionKey; }
}
