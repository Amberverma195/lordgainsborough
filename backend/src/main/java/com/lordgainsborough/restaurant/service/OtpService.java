package com.lordgainsborough.restaurant.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpService {

    private static final int OTP_EXPIRY_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;

    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    public void storeOtp(String email, String otp) {
        storeOtpForKey(normalizeEmail(email), otp);
    }

    public boolean verifyOtp(String email, String otp) {
        return verifyOtpForKey(normalizeEmail(email), otp);
    }

    public void storeOtpForKey(String key, String otp) {
        otpStore.put(key, new OtpEntry(otp, LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES)));
    }

    public boolean verifyOtpForKey(String key, String otp) {
        OtpEntry entry = otpStore.get(key);
        if (entry == null || LocalDateTime.now().isAfter(entry.expiresAt())) {
            otpStore.remove(key);
            return false;
        }

        // Cap guesses so a short-lived code cannot be brute forced within its lifetime.
        if (entry.attempts().incrementAndGet() > MAX_ATTEMPTS) {
            otpStore.remove(key);
            return false;
        }

        if (!entry.otp().equals(otp)) {
            return false;
        }

        otpStore.remove(key);
        return true;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private record OtpEntry(String otp, LocalDateTime expiresAt, java.util.concurrent.atomic.AtomicInteger attempts) {
        OtpEntry(String otp, LocalDateTime expiresAt) {
            this(otp, expiresAt, new java.util.concurrent.atomic.AtomicInteger(0));
        }
    }
}
