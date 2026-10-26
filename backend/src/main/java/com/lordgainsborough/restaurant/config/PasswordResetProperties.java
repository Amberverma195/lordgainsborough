package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth.password-reset")
public class PasswordResetProperties {

    private Duration resendCooldown = Duration.ofSeconds(30);
    private int maxResends = 5;
    private Duration codeTtl = Duration.ofMinutes(10);
    private Duration challengeTtl = Duration.ofMinutes(30);
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

    public Duration getResendCooldown() { return resendCooldown; }
    public void setResendCooldown(Duration value) { this.resendCooldown = value; }
    public int getMaxResends() { return maxResends; }
    public void setMaxResends(int value) { this.maxResends = value; }
    public Duration getCodeTtl() { return codeTtl; }
    public void setCodeTtl(Duration value) { this.codeTtl = value; }
    public Duration getChallengeTtl() { return challengeTtl; }
    public void setChallengeTtl(Duration value) { this.challengeTtl = value; }
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
}
