package com.lordgainsborough.restaurant.dto;

public class SendOtpRequest {
    private String email;
    private String phone;
    private String name;
    private String password;
    private String attemptToken;
    private Boolean restart;
    private Boolean termsAccepted;
    private String termsVersion;
    private String privacyVersion;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getAttemptToken() { return attemptToken; }
    public void setAttemptToken(String attemptToken) { this.attemptToken = attemptToken; }

    public Boolean getRestart() { return restart; }
    public void setRestart(Boolean restart) { this.restart = restart; }

    public Boolean getTermsAccepted() { return termsAccepted; }
    public void setTermsAccepted(Boolean termsAccepted) { this.termsAccepted = termsAccepted; }

    public Boolean getAgreedToTerms() { return termsAccepted; }
    public void setAgreedToTerms(Boolean agreedToTerms) { this.termsAccepted = agreedToTerms; }

    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    public String getPrivacyVersion() { return privacyVersion; }
    public void setPrivacyVersion(String privacyVersion) { this.privacyVersion = privacyVersion; }
}
