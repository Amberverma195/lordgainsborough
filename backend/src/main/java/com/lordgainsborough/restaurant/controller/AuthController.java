package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.AuthResponse;
import com.lordgainsborough.restaurant.dto.LoginRequest;
import com.lordgainsborough.restaurant.dto.ProfileVerificationRequest;
import com.lordgainsborough.restaurant.dto.ProfileVerificationResponse;
import com.lordgainsborough.restaurant.dto.ResetPasswordRequest;
import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.dto.SignupRequest;
import com.lordgainsborough.restaurant.dto.UpdateProfileRequest;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.OtpService;
import com.lordgainsborough.restaurant.service.SignupOtpRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private static final int PROFILE_UPDATE_COOLDOWN_DAYS = 7;
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 128;
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile("[a-z]");
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d");
    private static final Pattern SPECIAL_CHAR_PATTERN = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]");

    private final EmailSenderService emailSenderService;
    private final UserRepository userRepository;
    private final CustomerOrderRepository orderRepository;
    private final OrderReviewRepository reviewRepository;
    private final JwtUtil jwtUtil;
    private final OtpService otpService;
    private final SignupOtpRateLimiter signupOtpRateLimiter;

    public AuthController(
            EmailSenderService emailSenderService,
            UserRepository userRepository,
            CustomerOrderRepository orderRepository,
            OrderReviewRepository reviewRepository,
            JwtUtil jwtUtil,
            OtpService otpService,
            SignupOtpRateLimiter signupOtpRateLimiter) {
        this.emailSenderService = emailSenderService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.jwtUtil = jwtUtil;
        this.otpService = otpService;
        this.signupOtpRateLimiter = signupOtpRateLimiter;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        // Basic validation
        if (request.getEmail() == null || request.getPassword() == null || request.getName() == null) {
            return ResponseEntity.badRequest().body("Name, email, and password are required.");
        }
        
        if (request.getPassword().length() < 8) {
            return ResponseEntity.badRequest().body("Password must be at least 8 characters long.");
        }

        if (request.getOtp() == null || request.getOtp().isBlank()) {
            return ResponseEntity.badRequest().body("Verification code is required.");
        }

        String email = request.getEmail().trim().toLowerCase();

        if (!otpService.verifyOtp(email, request.getOtp().trim())) {
            return ResponseEntity.badRequest().body("Invalid or expired verification code.");
        }

        String normalizedPhone = normalizePhone(request.getPhone());
        if (normalizedPhone.length() != 10) {
            return ResponseEntity.badRequest().body("Please enter a valid 10-digit phone number.");
        }

        Optional<String> signupConflict = findSignupConflict(email, normalizedPhone);
        if (signupConflict.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(signupConflict.get());
        }

        // Create user
        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(email);
        applyPhoneToUser(user, normalizedPhone);
        user.setPassword(PasswordUtil.hashPassword(request.getPassword()));
        
        // Format memberSince (e.g., "May 2026")
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM yyyy");
        user.setMemberSince(user.getCreatedAt().format(formatter));
        
        userRepository.save(user);

        // Generate token
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        signupOtpRateLimiter.clear(email);

        return ResponseEntity.status(HttpStatus.CREATED).body(createAuthResponse(user, token));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String identifier = request.getEmailOrPhone() == null ? "" : request.getEmailOrPhone().trim();
        if (identifier.isBlank() || request.getPassword() == null || request.getPassword().isBlank()) {
            return ResponseEntity.badRequest().body("Email or phone number and password are required.");
        }

        boolean loginWithEmail = looksLikeEmail(identifier);
        Optional<User> userOpt;

        if (loginWithEmail) {
            userOpt = userRepository.findByEmailIgnoreCase(normalizeEmail(identifier));
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("No account found with this email.");
            }
        } else {
            String normalizedPhone = normalizePhone(identifier);
            if (normalizedPhone.length() != 10) {
                return ResponseEntity.badRequest()
                        .body("Enter a valid email address or 10-digit phone number.");
            }
            userOpt = findUserByPhone(normalizedPhone);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("No account found with this phone number.");
            }
        }

        User user = userOpt.get();
        if (!PasswordUtil.checkPassword(request.getPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Incorrect password.");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(createAuthResponse(user, token));
    }

    @PostMapping("/forgot-password/send-otp")
    public ResponseEntity<String> sendPasswordResetOtp(@RequestBody SendOtpRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (email.isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No account exists with this email.");
        }

        try {
            String generatedOtp = generateOtp();
            otpService.storeOtpForKey(passwordResetOtpKey(email), generatedOtp);
            emailSenderService.sendOtpEmail(userOpt.get().getEmail(), generatedOtp);
            return ResponseEntity.ok("Reset code sent to " + userOpt.get().getEmail() + ".");
        } catch (Exception e) {
            logger.error("Failed to send password reset OTP to {}", email, e);
            return ResponseEntity.status(500).body("Failed to send verification email. Please try again in a moment.");
        }
    }

    @PostMapping("/forgot-password/reset")
    public ResponseEntity<String> resetPassword(@RequestBody ResetPasswordRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (email.isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }

        String otp = normalizeOtp(request.getOtp());
        if (otp.isBlank()) {
            return ResponseEntity.badRequest().body("Verification code is required.");
        }

        String password = sanitizePassword(request.getPassword());
        Optional<String> passwordError = validateNewPassword(password);
        if (passwordError.isPresent()) {
            return ResponseEntity.badRequest().body(passwordError.get());
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No account exists with this email.");
        }

        if (!otpService.verifyOtpForKey(passwordResetOtpKey(email), otp)) {
            return ResponseEntity.badRequest().body("Invalid or expired verification code.");
        }

        User user = userOpt.get();
        user.setPassword(PasswordUtil.hashPassword(password));
        userRepository.save(user);

        return ResponseEntity.ok("Password reset successfully.");
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Expired or invalid token.");
        }

        String email = jwtUtil.getEmailFromToken(token);
        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found.");
        }

        return ResponseEntity.ok(createAuthResponse(userOpt.get(), token));
    }

    @GetMapping("/orders")
    public ResponseEntity<?> getMyOrders(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> userOpt = getAuthenticatedUser(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        }

        User user = userOpt.get();
        Map<String, CustomerOrder> byId = new LinkedHashMap<>();

        orderRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(user.getEmail())
                .forEach(order -> byId.putIfAbsent(order.getId(), order));

        String normalizedPhone = user.getPhoneNormalized();
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            normalizedPhone = normalizePhone(user.getPhone());
        }
        if (normalizedPhone.length() == 10 && user.getPhone() != null && !user.getPhone().isBlank()) {
            for (CustomerOrder order : orderRepository.findByCustomerPhoneOrderByCreatedAtDesc(user.getPhone())) {
                if (normalizedPhone.equals(normalizePhone(order.getCustomerPhone()))) {
                    byId.putIfAbsent(order.getId(), order);
                }
            }
        }

        List<CustomerOrder> orders = new ArrayList<>(byId.values());
        orders.forEach(this::applyReviewState);
        orders.sort(Comparator.comparing(CustomerOrder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/profile/send-verification")
    public ResponseEntity<?> sendProfileVerification(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ProfileVerificationRequest request) {
        Optional<User> userOpt = getAuthenticatedUser(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        }

        User user = userOpt.get();
        if (!canUpdateProfile(user)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("You can update your profile again on " + formatNextUpdateDate(user) + ".");
        }

        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }

        String normalizedPhone = normalizePhone(request.getPhone());
        if (normalizedPhone.length() != 10) {
            return ResponseEntity.badRequest().body("Please enter a valid 10-digit phone number.");
        }

        boolean emailChanged = !request.getEmail().trim().equalsIgnoreCase(user.getEmail());
        boolean phoneChanged = !normalizedPhone.equals(normalizePhone(user.getPhone()));

        if (!emailChanged && !phoneChanged) {
            ProfileVerificationResponse response = new ProfileVerificationResponse();
            response.setRequiresEmailOtp(false);
            response.setRequiresPhoneOtp(false);
            return ResponseEntity.ok(response);
        }

        if (emailChanged && userRepository.existsByEmailIgnoreCase(request.getEmail().trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("An account with this email already exists.");
        }

        if (phoneChanged && phoneAlreadyRegisteredByAnotherUser(normalizedPhone, user.getId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("An account already exists with this phone number.");
        }

        ProfileVerificationResponse response = new ProfileVerificationResponse();

        try {
            if (emailChanged) {
                String otp = generateOtp();
                String key = profileEmailOtpKey(request.getEmail().trim());
                otpService.storeOtpForKey(key, otp);
                emailSenderService.sendOtpEmail(request.getEmail().trim(), otp);
                response.setRequiresEmailOtp(true);
                response.setEmailOtpSentTo(request.getEmail().trim());
            }

            if (phoneChanged) {
                String otp = generateOtp();
                String key = profilePhoneOtpKey(user.getId());
                otpService.storeOtpForKey(key, otp);
                emailSenderService.sendOtpEmail(user.getEmail(), otp);
                response.setRequiresPhoneOtp(true);
                response.setPhoneOtpSentTo(user.getEmail());
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to send profile verification OTP for user {}", user.getEmail(), e);
            return ResponseEntity.status(500).body("Failed to send verification email. Please try again in a moment.");
        }
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody UpdateProfileRequest request) {
        Optional<User> userOpt = getAuthenticatedUser(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        }

        User user = userOpt.get();

        if (!canUpdateProfile(user)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("You can update your profile again on " + formatNextUpdateDate(user) + ".");
        }

        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body("Name is required.");
        }

        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }

        String normalizedPhone = normalizePhone(request.getPhone());
        if (normalizedPhone.length() != 10) {
            return ResponseEntity.badRequest().body("Please enter a valid 10-digit phone number.");
        }

        boolean emailChanged = !request.getEmail().trim().equalsIgnoreCase(user.getEmail());
        boolean phoneChanged = !normalizedPhone.equals(normalizePhone(user.getPhone()));

        if (emailChanged) {
            if (request.getEmailOtp() == null || request.getEmailOtp().isBlank()) {
                return ResponseEntity.badRequest().body("Email verification code is required.");
            }
            if (!otpService.verifyOtpForKey(profileEmailOtpKey(request.getEmail().trim()), request.getEmailOtp().trim())) {
                return ResponseEntity.badRequest().body("Invalid or expired email verification code.");
            }
            if (userRepository.existsByEmailIgnoreCase(request.getEmail().trim())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body("An account with this email already exists.");
            }
        }

        if (phoneChanged) {
            if (phoneAlreadyRegisteredByAnotherUser(normalizedPhone, user.getId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body("An account already exists with this phone number.");
            }
            if (request.getPhoneOtp() == null || request.getPhoneOtp().isBlank()) {
                return ResponseEntity.badRequest().body("Phone verification code is required.");
            }
            if (!otpService.verifyOtpForKey(profilePhoneOtpKey(user.getId()), request.getPhoneOtp().trim())) {
                return ResponseEntity.badRequest().body("Invalid or expired phone verification code.");
            }
        }

        user.setName(request.getName().trim());
        user.setEmail(request.getEmail().trim());
        applyPhoneToUser(user, normalizedPhone);
        user.setLastProfileUpdateAt(LocalDateTime.now());
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(createAuthResponse(user, token));
    }

    @PostMapping("/signup/send-otp")
    public ResponseEntity<String> sendSignupOtp(@RequestBody SendOtpRequest request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("Email is required.");
        }

        if (request.getPhone() == null || request.getPhone().isBlank()) {
            return ResponseEntity.badRequest().body("Phone number is required.");
        }

        String email = request.getEmail().trim().toLowerCase();
        String normalizedPhone = normalizePhone(request.getPhone());
        if (normalizedPhone.length() != 10) {
            return ResponseEntity.badRequest().body("Please enter a valid 10-digit phone number.");
        }

        Optional<String> signupConflict = findSignupConflict(email, normalizedPhone);
        if (signupConflict.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(signupConflict.get());
        }

        SignupOtpRateLimiter.AllowResult rateLimit = signupOtpRateLimiter.tryAcquire(email);
        if (!rateLimit.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(rateLimit.retryAfterSeconds()))
                    .body(rateLimit.message());
        }

        try {
            String generatedOtp = generateOtp();
            otpService.storeOtp(email, generatedOtp);
            emailSenderService.sendOtpEmail(email, generatedOtp);
            return ResponseEntity.ok("Verification code sent to " + email);
        } catch (Exception e) {
            logger.error("Failed to send signup OTP to {}", email, e);
            return ResponseEntity.status(500).body("Failed to send verification email. Please try again in a moment.");
        }
    }

    // Moved from root to /api/auth/send-otp
    @GetMapping("/send-otp")
    public ResponseEntity<String> requestOtp(@RequestParam String email) {
        try {
            String generatedOtp = generateOtp();
            otpService.storeOtp(email, generatedOtp);
            emailSenderService.sendOtpEmail(email, generatedOtp);
            return ResponseEntity.ok("OTP successfully sent to " + email);
        } catch (Exception e) {
            logger.error("Failed to send OTP to {}", email, e);
            return ResponseEntity.status(500).body("Failed to send verification email. Please try again in a moment.");
        }
    }

    private String generateOtp() {
        SecureRandom random = new SecureRandom();
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private AuthResponse createAuthResponse(User user, String token) {
        AuthResponse response = new AuthResponse();
        response.setToken(token);
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setMemberSince(user.getMemberSince());
        response.setRole(user.getRole());
        response.setCanUpdateProfile(canUpdateProfile(user));
        response.setNextProfileUpdateAt(canUpdateProfile(user) ? null : formatNextUpdateDate(user));
        return response;
    }

    private Optional<User> getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return Optional.empty();
        }

        String email = jwtUtil.getEmailFromToken(token);
        return userRepository.findByEmail(email);
    }

    private void applyReviewState(CustomerOrder order) {
        order.setReviewed(order.isReviewed() || reviewRepository.existsByOrderId(order.getId()));
    }

    private boolean canUpdateProfile(User user) {
        if (user.getLastProfileUpdateAt() == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(user.getLastProfileUpdateAt().plusDays(PROFILE_UPDATE_COOLDOWN_DAYS));
    }

    private String formatNextUpdateDate(User user) {
        if (user.getLastProfileUpdateAt() == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy");
        return user.getLastProfileUpdateAt().plusDays(PROFILE_UPDATE_COOLDOWN_DAYS).format(formatter);
    }

    private String profileEmailOtpKey(String email) {
        return "profile-email:" + email.trim().toLowerCase();
    }

    private String profilePhoneOtpKey(String userId) {
        return "profile-phone:" + userId;
    }

    private String passwordResetOtpKey(String email) {
        return "password-reset:" + email.trim().toLowerCase();
    }

    private boolean looksLikeEmail(String identifier) {
        return identifier != null && identifier.contains("@");
    }

    private Optional<User> findUserByPhone(String normalizedPhoneDigits) {
        if (normalizedPhoneDigits == null || normalizedPhoneDigits.length() != 10) {
            return Optional.empty();
        }

        List<User> byNormalized = userRepository.findByPhoneNormalized(normalizedPhoneDigits);
        if (!byNormalized.isEmpty()) {
            return Optional.of(byNormalized.get(0));
        }

        return userRepository.findByPhoneIsNotNull().stream()
                .filter(user -> user.getPhone() != null && !user.getPhone().isBlank())
                .filter(user -> normalizedPhoneDigits.equals(normalizePhone(user.getPhone())))
                .findFirst();
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }

        return email
                .replaceAll("[\\u0000-\\u001F\\u007F-\\u009F]", "")
                .replaceAll("[\\u200B-\\u200D\\uFEFF\\u202A-\\u202E]", "")
                .trim()
                .toLowerCase();
    }

    private String normalizeOtp(String otp) {
        if (otp == null) {
            return "";
        }

        String digits = otp.replaceAll("\\D", "");
        return digits.substring(0, Math.min(6, digits.length()));
    }

    private String sanitizePassword(String password) {
        if (password == null) {
            return "";
        }

        String sanitized = password
                .replaceAll("[\\u0000-\\u001F\\u007F-\\u009F]", "")
                .replaceAll("[\\u200B-\\u200D\\uFEFF\\u202A-\\u202E]", "");
        return sanitized.substring(0, Math.min(PASSWORD_MAX_LENGTH, sanitized.length()));
    }

    private Optional<String> validateNewPassword(String password) {
        if (password == null || password.isEmpty()) {
            return Optional.of("Password is required.");
        }

        if (password.length() < PASSWORD_MIN_LENGTH) {
            return Optional.of("Password must be at least 8 characters long.");
        }

        if (password.length() > PASSWORD_MAX_LENGTH) {
            return Optional.of("Password must be 128 characters or fewer.");
        }

        if (!LOWERCASE_PATTERN.matcher(password).find()) {
            return Optional.of("Password must include one lowercase letter.");
        }

        if (!UPPERCASE_PATTERN.matcher(password).find()) {
            return Optional.of("Password must include one uppercase letter.");
        }

        if (!NUMBER_PATTERN.matcher(password).find()) {
            return Optional.of("Password must include one number.");
        }

        if (!SPECIAL_CHAR_PATTERN.matcher(password).find()) {
            return Optional.of("Password must include one special character.");
        }

        return Optional.empty();
    }

    private Optional<String> findSignupConflict(String email, String normalizedPhoneDigits) {
        if (email != null && !email.isBlank() && userRepository.existsByEmailIgnoreCase(email.trim())) {
            return Optional.of("An account already exists with this email.");
        }

        if (normalizedPhoneDigits != null
                && normalizedPhoneDigits.length() == 10
                && phoneAlreadyRegistered(normalizedPhoneDigits)) {
            return Optional.of("An account already exists with this phone number.");
        }

        return Optional.empty();
    }

    private boolean phoneAlreadyRegistered(String normalizedPhoneDigits) {
        return phoneAlreadyRegisteredByAnotherUser(normalizedPhoneDigits, null);
    }

    private boolean phoneAlreadyRegisteredByAnotherUser(String normalizedPhoneDigits, String currentUserId) {
        if (normalizedPhoneDigits == null || normalizedPhoneDigits.length() != 10) {
            return false;
        }

        boolean normalizedMatch = userRepository.findByPhoneNormalized(normalizedPhoneDigits).stream()
                .anyMatch(user -> isDifferentUser(user, currentUserId));
        if (normalizedMatch) {
            return true;
        }

        return userRepository.findByPhoneIsNotNull().stream()
                .filter(user -> user.getPhone() != null && !user.getPhone().isBlank())
                .filter(user -> isDifferentUser(user, currentUserId))
                .anyMatch(user -> normalizedPhoneDigits.equals(normalizePhone(user.getPhone())));
    }

    private boolean isDifferentUser(User user, String currentUserId) {
        return currentUserId == null || user.getId() == null || !user.getId().equals(currentUserId);
    }

    private void applyPhoneToUser(User user, String normalizedPhoneDigits) {
        user.setPhone(formatPhoneForStorage(normalizedPhoneDigits));
        user.setPhoneNormalized(normalizedPhoneDigits);
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) {
            digits = digits.substring(1);
        }
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }

    private String formatPhoneForStorage(String digits) {
        if (digits.length() != 10) {
            return digits;
        }
        return String.format("+1 (%s)-%s-%s",
                digits.substring(0, 3),
                digits.substring(3, 6),
                digits.substring(6));
    }
}
