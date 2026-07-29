package com.lordgainsborough.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse extends AuthResponse {

    private String code;
    private String message;
    private Long retryAfterSeconds;
    private Instant serverTime;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getRetryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(Long retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }
    public Instant getServerTime() { return serverTime; }
    public void setServerTime(Instant serverTime) { this.serverTime = serverTime; }

    public static LoginResponse error(String code, String message, Long retryAfterSeconds, Instant serverTime) {
        LoginResponse response = new LoginResponse();
        response.setCode(code);
        response.setMessage(message);
        response.setRetryAfterSeconds(retryAfterSeconds);
        response.setServerTime(serverTime);
        return response;
    }

    public static LoginResponse success(AuthResponse auth, Instant serverTime) {
        LoginResponse response = new LoginResponse();
        response.setCode("LOGIN_SUCCESS");
        response.setServerTime(serverTime);
        response.setToken(auth.getToken());
        response.setName(auth.getName());
        response.setEmail(auth.getEmail());
        response.setPhone(auth.getPhone());
        response.setMemberSince(auth.getMemberSince());
        response.setRole(auth.getRole());
        response.setCanUpdateProfile(auth.isCanUpdateProfile());
        response.setNextProfileUpdateAt(auth.getNextProfileUpdateAt());
        response.setNoShowCount(auth.getNoShowCount());
        response.setPickupDisabled(auth.isPickupDisabled());
        return response;
    }
}
