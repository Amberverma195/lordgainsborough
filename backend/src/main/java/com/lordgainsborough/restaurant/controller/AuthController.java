package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.AuthResponse;
import com.lordgainsborough.restaurant.dto.LoginRequest;
import com.lordgainsborough.restaurant.dto.LoginResponse;
import com.lordgainsborough.restaurant.dto.PasswordResetResendRequest;
import com.lordgainsborough.restaurant.dto.PasswordResetResponse;
import com.lordgainsborough.restaurant.dto.ProfileVerificationRequest;
import com.lordgainsborough.restaurant.dto.ProfileVerificationResponse;
import com.lordgainsborough.restaurant.dto.ResetPasswordRequest;
import com.lordgainsborough.restaurant.dto.SendOtpRequest;
import com.lordgainsborough.restaurant.dto.SignupOtpChallengeView;
import com.lordgainsborough.restaurant.dto.SignupOtpCode;
import com.lordgainsborough.restaurant.dto.SignupOtpResponse;
import com.lordgainsborough.restaurant.dto.SignupRequest;
import com.lordgainsborough.restaurant.dto.UpdateProfileRequest;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import com.lordgainsborough.restaurant.service.CustomerNoShowService;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import com.lordgainsborough.restaurant.service.PostgresUserAuthStore;
import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.AuthSecurityObservability;
import com.lordgainsborough.restaurant.service.LoginRateLimiter;
import com.lordgainsborough.restaurant.service.OtpService;
import com.lordgainsborough.restaurant.service.PasswordResetService;
import com.lordgainsborough.restaurant.service.SignupAbuseLimiter;
import com.lordgainsborough.restaurant.service.SignupOtpChallengePersistence;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeService;
import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.util.EmailValidator;
import com.lordgainsborough.restaurant.util.PasswordValidator;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
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
    private final UserAuthStore userAuthStore;
    private final JwtUtil jwtUtil;
    private final OtpService otpService;
    private final SignupOtpChallengeService signupOtpChallengeService;
    private final SignupAbuseLimiter signupAbuseLimiter;
    private final ClientIpResolver clientIpResolver;
    private final SignupOtpObservability signupOtpObservability;
    private final LoginRateLimiter loginRateLimiter;
    private final PasswordResetService passwordResetService;
    private final AuthSecurityObservability authSecurityObservability;

    @Autowired
    public AuthController(EmailSenderService emailSenderService, UserAuthStore userAuthStore,
            JwtUtil jwtUtil, OtpService otpService, SignupOtpChallengeService signupOtpChallengeService,
            SignupAbuseLimiter signupAbuseLimiter, ClientIpResolver clientIpResolver,
            SignupOtpObservability signupOtpObservability, LoginRateLimiter loginRateLimiter,
            PasswordResetService passwordResetService, AuthSecurityObservability authSecurityObservability) {
        this.emailSenderService = emailSenderService;
        this.userAuthStore = userAuthStore;
        this.jwtUtil = jwtUtil;
        this.otpService = otpService;
        this.signupOtpChallengeService = signupOtpChallengeService;
        this.signupAbuseLimiter = signupAbuseLimiter;
        this.clientIpResolver = clientIpResolver;
        this.signupOtpObservability = signupOtpObservability;
        this.loginRateLimiter = loginRateLimiter;
        this.passwordResetService = passwordResetService;
        this.authSecurityObservability = authSecurityObservability;
    }

    public UserAuthStore getUserAuthStore() { return userAuthStore; }
    public SignupOtpChallengeService getSignupOtpChallengeService() { return signupOtpChallengeService; }
    public SignupAbuseLimiter getSignupAbuseLimiter() { return signupAbuseLimiter; }
    public LoginRateLimiter getLoginRateLimiter() { return loginRateLimiter; }
    public PasswordResetService getPasswordResetService() { return passwordResetService; }

    public boolean hasPostgresUserAuthentication() { return userAuthStore instanceof PostgresUserAuthStore; }

    private String resolveAttemptToken(String bodyToken, String headerToken) {
        if (headerToken != null && !headerToken.isBlank()) {
            return headerToken.trim();
        }
        if (bodyToken != null && !bodyToken.isBlank()) {
            return bodyToken.trim();
        }
        return null;
    }

    public ResponseEntity<?> signup(
            SignupRequest request,
            HttpServletRequest httpRequest) {
        return signup(null, request, httpRequest);
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(
            @RequestHeader(value = "X-Signup-Attempt-Token", required = false) String attemptTokenHeader,
            @RequestBody SignupRequest request,
            HttpServletRequest httpRequest) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Email is required."));
        }

        if (request.getEmail().length() > 254 || !EmailValidator.isValidEmail(request.getEmail())) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Please enter a valid email address."));
        }

        if (request.getOtp() == null || request.getOtp().isBlank()) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Verification code is required."));
        }

        String email = request.getEmail().trim().toLowerCase();
        String rawAttemptToken = resolveAttemptToken(request.getAttemptToken(), attemptTokenHeader);

        String clientIp = resolveClientIp(httpRequest);
        ResponseEntity<SignupOtpResponse> verifyLimit = mapAbuseDecision(
                signupAbuseLimiter.checkAndConsumeVerification(clientIp),
                signupAbuseLimiter.hashClientIp(clientIp));
        if (verifyLimit != null) {
            return verifyLimit;
        }

        Optional<SignupOtpChallenge> activeOpt = signupOtpChallengeService.findActiveChallenge(email);
        boolean isStaged = false;
        if (activeOpt.isPresent()) {
            SignupOtpChallenge active = activeOpt.get();
            if (active.getPendingPasswordHash() != null) {
                isStaged = true;
                boolean validAttempt = active.getAttemptTokenHash() == null
                        || signupOtpChallengeService.verifyAttemptToken(active, rawAttemptToken);
                if (!validAttempt) {
                    authSecurityObservability.record(
                            AuthSecurityObservability.Flow.SIGNUP,
                            AuthSecurityObservability.Outcome.INVALID,
                            AuthSecurityObservability.Reason.SIGNUP_ATTEMPT_MISMATCH);
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_ATTEMPT_CONFLICT,
                            "A signup verification session is already in progress for this email address. Please complete verification in your original browser window, or wait for the session to expire."));
                }
                if (request.getName() != null && !request.getName().trim().isBlank()
                        && active.getPendingName() != null
                        && !request.getName().trim().equals(active.getPendingName())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_ATTEMPT_CONFLICT,
                            "Registration details do not match the pending verification session."));
                }
                if (request.getPhone() != null && !request.getPhone().isBlank()) {
                    String reqNormPhone = normalizePhone(request.getPhone());
                    if (active.getPendingPhoneNormalized() != null
                            && !reqNormPhone.equals(active.getPendingPhoneNormalized())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                                SignupOtpCode.OTP_ATTEMPT_CONFLICT,
                                "Registration details do not match the pending verification session."));
                    }
                }
                if (request.getPassword() != null && !request.getPassword().isBlank()) {
                    String cleanPassword = PasswordValidator.sanitize(request.getPassword());
                    if (!PasswordUtil.checkPassword(cleanPassword, active.getPendingPasswordHash())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                                SignupOtpCode.OTP_ATTEMPT_CONFLICT,
                                "Registration details do not match the pending verification session."));
                    }
                }
            } else if (request.getPassword() == null || request.getPassword().isBlank()) {
                isStaged = true;
            }
        }

        UserAuthStore.Registration registration;
        if (isStaged) {
            String challengeId = (request.getChallengeId() != null && !request.getChallengeId().isBlank())
                    ? request.getChallengeId()
                    : activeOpt.map(SignupOtpChallenge::getChallengeId).orElse(null);
            registration = userAuthStore.registerPending(email, request.getOtp().trim(), challengeId, rawAttemptToken, signupOtpChallengeService);
        } else if (request.getPassword() != null && !request.getPassword().isBlank()) {
            String cleanPassword = PasswordValidator.sanitize(request.getPassword());
            if (request.getName() == null || request.getName().trim().isBlank()) {
                return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Full name is required."));
            }
            if (request.getName().trim().length() > 255) {
                return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Name must not exceed 255 characters."));
            }
            if (!PasswordValidator.isValid(cleanPassword)) {
                return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        PasswordValidator.getValidationErrorMessage(cleanPassword)));
            }
            if (request.getPhone() == null || request.getPhone().isBlank() || request.getPhone().length() > 50) {
                return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Please enter a valid 10-digit phone number."));
            }
            String normalizedPhone = normalizePhone(request.getPhone());
            if (normalizedPhone.length() != 10) {
                return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Please enter a valid 10-digit phone number."));
            }
            if (findSignupConflict(email, normalizedPhone)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Unable to complete signup with the provided details."));
            }
            User user = new User();
            user.setName(request.getName().trim());
            user.setEmail(email);
            applyPhoneToUser(user, normalizedPhone);
            user.setMemberSince(user.getCreatedAt().format(DateTimeFormatter.ofPattern("MMMM yyyy")));
            registration = userAuthStore.register(user, cleanPassword, request.getOtp().trim(), signupOtpChallengeService);
        } else {
            registration = userAuthStore.registerPending(email, request.getOtp().trim(), request.getChallengeId(), rawAttemptToken, signupOtpChallengeService);
        }

        ResponseEntity<SignupOtpResponse> verifyFailure = mapVerifyFailure(registration.verification());
        if (verifyFailure != null) return verifyFailure;
        if (registration.conflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID, "Unable to complete signup with the provided details."));
        }
        User user = registration.user();
        if (user == null) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID, "Unable to complete signup with the provided details."));
        }
        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(createAuthResponse(user, token));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        Instant now = Instant.now();
        String clientIp = resolveClientIp(httpRequest);
        var ipLimit = loginRateLimiter.consumeIpRequest(clientIp);
        if (ipLimit.isPresent()) {
            return loginRateLimited(ipLimit.getAsLong(), now, AuthSecurityObservability.Reason.IP_LIMIT);
        }

        String identifier = request.getEmailOrPhone() == null ? "" : request.getEmailOrPhone().trim();
        if (identifier.isBlank() || request.getPassword() == null || request.getPassword().isBlank()) {
            return ResponseEntity.badRequest().body(LoginResponse.error(
                    "LOGIN_INVALID_REQUEST",
                    "Email or phone number and password are required.",
                    null,
                    now));
        }

        boolean loginWithEmail = looksLikeEmail(identifier);
        String normalizedIdentifier;
        Optional<User> userOpt;

        if (loginWithEmail) {
            normalizedIdentifier = normalizeEmail(identifier);
            userOpt = userAuthStore.findByEmailIgnoreCase(normalizedIdentifier);
        } else {
            String normalizedPhone = normalizePhone(identifier);
            if (normalizedPhone.length() != 10) {
                return invalidLoginCredentials("", clientIp, request.getPassword(), Optional.empty(), now);
            }
            normalizedIdentifier = normalizedPhone;
            userOpt = findUserByPhone(normalizedPhone);
        }

        var cooldown = loginRateLimiter.activeCooldown(normalizedIdentifier, clientIp);
        if (cooldown.isPresent()) {
            return loginRateLimited(
                    cooldown.getAsLong(),
                    now,
                    AuthSecurityObservability.Reason.IDENTIFIER_IP_COOLDOWN);
        }

        String cleanPassword = PasswordValidator.sanitize(request.getPassword());
        if (userOpt.isEmpty()) {
            return invalidLoginCredentials(
                    normalizedIdentifier,
                    clientIp,
                    cleanPassword,
                    Optional.empty(),
                    now);
        }

        User user = userOpt.get();
        if (!PasswordUtil.checkPassword(cleanPassword, user.getPassword())) {
            return invalidLoginCredentials(
                    normalizedIdentifier,
                    clientIp,
                    cleanPassword,
                    userOpt,
                    now);
        }

        // A concurrent failure may have started a cooldown while BCrypt was running.
        cooldown = loginRateLimiter.activeCooldown(normalizedIdentifier, clientIp);
        if (cooldown.isPresent()) {
            return loginRateLimited(
                    cooldown.getAsLong(),
                    now,
                    AuthSecurityObservability.Reason.IDENTIFIER_IP_COOLDOWN);
        }

        Optional<User> current = userAuthStore.confirmLogin(user);
        if (current.isEmpty()) {
            return invalidLoginCredentials(normalizedIdentifier, clientIp, cleanPassword, userOpt, now);
        }
        user = current.get();
        // PostgreSQL confirmation may have waited behind a password reset or profile update.
        if (userAuthStore instanceof PostgresUserAuthStore) {
            cooldown = loginRateLimiter.activeCooldown(normalizedIdentifier, clientIp);
            if (cooldown.isPresent()) return loginRateLimited(cooldown.getAsLong(), now,
                    AuthSecurityObservability.Reason.IDENTIFIER_IP_COOLDOWN);
        }
        loginRateLimiter.clearFailures(normalizedIdentifier, clientIp);
        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        authSecurityObservability.record(
                AuthSecurityObservability.Flow.LOGIN,
                AuthSecurityObservability.Outcome.SUCCESS,
                AuthSecurityObservability.Reason.LOGIN_SUCCESS);
        return ResponseEntity.ok(LoginResponse.success(createAuthResponse(user, token), now));
    }

    @PostMapping("/forgot-password/send-otp")
    public ResponseEntity<PasswordResetResponse> sendPasswordResetOtp(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SendOtpRequest request,
            HttpServletRequest httpRequest) {
        if (passwordResetService != null && passwordResetService.isPostgresStore()
                && (!hasPostgresUserAuthentication())) {
            throw new IllegalStateException(
                    "PostgreSQL password reset path is isolated and cannot be invoked from an incompatible identity store.");
        }
        String email = normalizeEmail(request.getEmail());
        if (email.isBlank()) {
            return ResponseEntity.badRequest().body(PasswordResetResponse.of(
                    "RESET_CODE_INVALID",
                    "Email is required.",
                    Instant.now()));
        }

        PasswordResetService.Result result = userAuthStore.requestPasswordReset(
                passwordResetService, email, resolveClientIp(httpRequest), idempotencyKey);
        return passwordResetResponse(result);
    }

    @PostMapping("/forgot-password/resend-otp")
    public ResponseEntity<PasswordResetResponse> resendPasswordResetOtp(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PasswordResetResendRequest request,
            HttpServletRequest httpRequest) {
        if (passwordResetService != null && passwordResetService.isPostgresStore()
                && (!hasPostgresUserAuthentication())) {
            throw new IllegalStateException(
                    "PostgreSQL password reset path is isolated and cannot be invoked from an incompatible identity store.");
        }
        String email = normalizeEmail(request.getEmail());
        if (email.isBlank()) {
            return ResponseEntity.badRequest().body(PasswordResetResponse.of(
                    "RESET_CODE_INVALID",
                    "Email is required.",
                    Instant.now()));
        }
        PasswordResetService.Result result = passwordResetService.resend(
                email,
                request.getChallengeId(),
                resolveClientIp(httpRequest),
                idempotencyKey);
        return passwordResetResponse(result);
    }

    @PostMapping("/forgot-password/reset")
    public ResponseEntity<PasswordResetResponse> resetPassword(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        if (passwordResetService != null && passwordResetService.isPostgresStore()
                && (!hasPostgresUserAuthentication())) {
            throw new IllegalStateException(
                    "PostgreSQL password reset path is isolated and cannot be invoked from an incompatible identity store.");
        }
        String email = normalizeEmail(request.getEmail());
        if (email.isBlank()) {
            return ResponseEntity.badRequest().body(PasswordResetResponse.of(
                    "RESET_CODE_INVALID",
                    "Email is required.",
                    Instant.now()));
        }
        PasswordResetService.Result result = passwordResetService.complete(
                email,
                request.getChallengeId(),
                request.getOtp(),
                request.getPassword(),
                resolveClientIp(httpRequest),
                idempotencyKey);
        return passwordResetResponse(result);
    }

    private ResponseEntity<LoginResponse> invalidLoginCredentials(
            String normalizedIdentifier,
            String clientIp,
            String suppliedPassword,
            Optional<User> user,
            Instant now) {
        if (user.isEmpty()) {
            PasswordUtil.dummyPasswordCheck(suppliedPassword);
        }
        LoginRateLimiter.FailureResult failure =
                loginRateLimiter.recordFailure(normalizedIdentifier, clientIp);
        if (failure.denied()) {
            return loginRateLimited(
                    failure.retryAfterSeconds(),
                    now,
                    AuthSecurityObservability.Reason.IDENTIFIER_IP_COOLDOWN);
        }
        authSecurityObservability.record(
                AuthSecurityObservability.Flow.LOGIN,
                AuthSecurityObservability.Outcome.INVALID,
                AuthSecurityObservability.Reason.INVALID_CREDENTIALS);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(LoginResponse.error(
                "LOGIN_INVALID_CREDENTIALS",
                "Invalid email, phone number, or password.",
                null,
                now));
    }

    private ResponseEntity<LoginResponse> loginRateLimited(
            long retryAfterSeconds,
            Instant now,
            AuthSecurityObservability.Reason reason) {
        authSecurityObservability.record(
                AuthSecurityObservability.Flow.LOGIN,
                AuthSecurityObservability.Outcome.DENIED,
                reason);
        LoginResponse body = LoginResponse.error(
                "LOGIN_RATE_LIMITED",
                "Too many login attempts. Please try again later.",
                Math.max(1L, retryAfterSeconds),
                now);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1L, retryAfterSeconds)))
                .body(body);
    }

    private ResponseEntity<PasswordResetResponse> passwordResetResponse(
            PasswordResetService.Result result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status());
        if (result.retryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(result.retryAfterSeconds()));
        }
        return builder.body(result.body());
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

        Optional<User> userOpt = userAuthStore.authenticate(token, jwtUtil);

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

        var history = userAuthStore.history(userOpt.get(), authHeader.substring(7), jwtUtil);
        if (history.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        return ResponseEntity.ok(history.get());
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

        if (emailChanged && userAuthStore.existsByEmailIgnoreCase(request.getEmail().trim())) {
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
            logSafeAuthFailure("profile_verification_otp_send", e);
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

        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body("Name is required.");
        }

        var updated = userAuthStore.updateName(user, authHeader.substring(7), request.getName().trim(), jwtUtil);
        if (updated.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        user = updated.get();

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        return ResponseEntity.ok(createAuthResponse(user, token));
    }

    @GetMapping("/signup/otp-status")
    public ResponseEntity<SignupOtpResponse> getSignupOtpStatus(
            @RequestParam String email,
            @RequestParam(required = false) String challengeId) {
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Email is required."));
        }

        SignupOtpChallengeService.ChallengeStatusResult status =
                signupOtpChallengeService.getChallengeStatus(email.trim().toLowerCase(), challengeId);
        return mapChallengeStatus(status);
    }

    public ResponseEntity<SignupOtpResponse> sendSignupOtp(
            String idempotencyKey,
            String captchaToken,
            SendOtpRequest request,
            HttpServletRequest httpRequest) {
        return sendSignupOtp(idempotencyKey, captchaToken, null, request, httpRequest);
    }

    @PostMapping("/signup/send-otp")
    public ResponseEntity<SignupOtpResponse> sendSignupOtp(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Captcha-Token", required = false) String captchaToken,
            @RequestHeader(value = "X-Signup-Attempt-Token", required = false) String attemptTokenHeader,
            @RequestBody SendOtpRequest request,
            HttpServletRequest httpRequest) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Email is required."));
        }

        if (request.getEmail().length() > 254 || !EmailValidator.isValidEmail(request.getEmail())) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Please enter a valid email address."));
        }

        String email = request.getEmail().trim().toLowerCase();
        String rawAttemptToken = resolveAttemptToken(request.getAttemptToken(), attemptTokenHeader);

        if (request.getPhone() == null || request.getPhone().isBlank()) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Phone number is required."));
        }

        if (request.getPhone().length() > 50) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Please enter a valid 10-digit phone number."));
        }

        String normalizedPhone = normalizePhone(request.getPhone());
        if (normalizedPhone.length() != 10) {
            return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_INVALID,
                    "Please enter a valid 10-digit phone number."));
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<SignupOtpChallengeService.RequestOtpResult> replay =
                    signupOtpChallengeService.checkIdempotentReplay(email, idempotencyKey, rawAttemptToken);
            if (replay.isPresent()) {
                return mapSendResult(replay.get());
            }
        }

        boolean isInitialSend = signupOtpChallengeService.looksLikeInitialSend(email);
        if (isInitialSend) {
            boolean hasName = request.getName() != null && !request.getName().trim().isBlank();
            boolean hasPassword = request.getPassword() != null && !request.getPassword().isBlank();

            if (hasName && !hasPassword) {
                return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Password is required."));
            }
            if (!hasName && hasPassword) {
                return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Full name is required."));
            }

            String pendingName = null;
            String pendingPasswordHash = null;

            if (hasName) {
                if (request.getName().trim().length() > 255) {
                    return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            "Name must not exceed 255 characters."));
                }
                String cleanPassword = PasswordValidator.sanitize(request.getPassword());
                if (!PasswordValidator.isValid(cleanPassword)) {
                    return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            PasswordValidator.getValidationErrorMessage(cleanPassword)));
                }
                pendingName = request.getName().trim();
                pendingPasswordHash = PasswordUtil.hashPassword(cleanPassword);
            }

            if (findSignupConflict(email, normalizedPhone)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                        SignupOtpCode.OTP_INVALID,
                        "Unable to complete signup with the provided details."));
            }

            String clientIp = resolveClientIp(httpRequest);
            ResponseEntity<SignupOtpResponse> abuseDenied = mapAbuseDecision(
                    signupAbuseLimiter.checkAndConsumeSend(clientIp, true, captchaToken),
                    signupAbuseLimiter.hashClientIp(clientIp));
            if (abuseDenied != null) {
                return abuseDenied;
            }

            SignupOtpChallengeService.RequestOtpResult sendResult = (rawAttemptToken != null && !rawAttemptToken.isBlank())
                    ? signupOtpChallengeService.requestSignupOtp(
                            email, idempotencyKey, pendingName, request.getPhone(), normalizedPhone, pendingPasswordHash, rawAttemptToken)
                    : signupOtpChallengeService.requestSignupOtp(
                            email, idempotencyKey, pendingName, request.getPhone(), normalizedPhone, pendingPasswordHash);
            return mapSendResult(sendResult);
        }

        // Active challenge already exists
        Optional<SignupOtpChallenge> activeOpt = signupOtpChallengeService.findActiveChallenge(email);
        if (activeOpt.isPresent()) {
            SignupOtpChallenge active = activeOpt.get();
            if (active.getAttemptTokenHash() != null) {
                boolean validAttempt = signupOtpChallengeService.verifyAttemptToken(active, rawAttemptToken);
                if (!validAttempt) {
                    authSecurityObservability.record(
                            AuthSecurityObservability.Flow.SIGNUP,
                            AuthSecurityObservability.Outcome.INVALID,
                            AuthSecurityObservability.Reason.SIGNUP_ATTEMPT_MISMATCH);
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(fromChallenge(
                            SignupOtpCode.OTP_ATTEMPT_CONFLICT,
                            "A signup verification session is already in progress for this email address. Please complete verification in your original browser window, or wait for the session to expire.",
                            signupOtpChallengeService.getChallengeView(email)));
                }
            }

            // Original applicant possessing valid attempt secret
            boolean restartRequested = Boolean.TRUE.equals(request.getRestart());
            String cleanPassword = (request.getPassword() != null && !request.getPassword().isBlank())
                    ? PasswordValidator.sanitize(request.getPassword())
                    : null;
            boolean detailsChanged = false;

            if (request.getName() != null && !request.getName().trim().isBlank()
                    && !request.getName().trim().equals(active.getPendingName())) {
                detailsChanged = true;
            }
            if (normalizedPhone != null && !normalizedPhone.equals(active.getPendingPhoneNormalized())) {
                detailsChanged = true;
            }
            if (cleanPassword != null && !cleanPassword.isBlank()
                    && (active.getPendingPasswordHash() == null || !PasswordUtil.checkPassword(cleanPassword, active.getPendingPasswordHash()))) {
                detailsChanged = true;
            }

            if (restartRequested || detailsChanged) {
                String nameToUse = (request.getName() != null && !request.getName().trim().isBlank())
                        ? request.getName().trim()
                        : active.getPendingName();
                if (nameToUse == null || nameToUse.isBlank()) {
                    return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            "Full name is required."));
                }
                if (nameToUse.length() > 255) {
                    return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            "Name must not exceed 255 characters."));
                }

                String passwordHashToUse;
                if (cleanPassword != null && !cleanPassword.isBlank()) {
                    if (!PasswordValidator.isValid(cleanPassword)) {
                        return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            PasswordValidator.getValidationErrorMessage(cleanPassword)));
                    }
                    passwordHashToUse = PasswordUtil.hashPassword(cleanPassword);
                } else {
                    passwordHashToUse = active.getPendingPasswordHash();
                    if (passwordHashToUse == null) {
                        return ResponseEntity.badRequest().body(SignupOtpResponse.of(
                                SignupOtpCode.OTP_INVALID,
                                "Password is required."));
                    }
                }

                if (findSignupConflict(email, normalizedPhone)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            "Unable to complete signup with the provided details."));
                }

                String clientIp = resolveClientIp(httpRequest);
                ResponseEntity<SignupOtpResponse> abuseDenied = mapAbuseDecision(
                        signupAbuseLimiter.checkAndConsumeSend(clientIp, false, captchaToken),
                        signupAbuseLimiter.hashClientIp(clientIp));
                if (abuseDenied != null) {
                    return abuseDenied;
                }

                SignupOtpChallengeService.RequestOtpResult restartResult = signupOtpChallengeService.restartSignupOtp(
                        email, idempotencyKey, nameToUse, request.getPhone(), normalizedPhone, passwordHashToUse, rawAttemptToken);
                return mapSendResult(restartResult);
            }
        }

        // Details did not change; resend for original applicant
        String clientIp = resolveClientIp(httpRequest);
        ResponseEntity<SignupOtpResponse> abuseDenied = mapAbuseDecision(
                signupAbuseLimiter.checkAndConsumeSend(clientIp, false, captchaToken),
                signupAbuseLimiter.hashClientIp(clientIp));
        if (abuseDenied != null) {
            return abuseDenied;
        }

        SignupOtpChallengeService.RequestOtpResult resendResult = signupOtpChallengeService.requestSignupOtp(email, idempotencyKey);
        return mapSendResult(resendResult);
    }

    private String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"));
    }

    private ResponseEntity<SignupOtpResponse> mapAbuseDecision(
            SignupAbuseLimiter.Decision decision,
            String clientKey) {
        if (decision == null || decision instanceof SignupAbuseLimiter.Decision.Allowed) {
            return null;
        }
        SignupAbuseLimiter.Decision.Denied denied = (SignupAbuseLimiter.Decision.Denied) decision;
        SignupOtpObservability.Event event = switch (denied.reason()) {
            case INITIAL_SEND_IP, TOTAL_SEND_IP, VERIFY_IP ->
                    SignupOtpObservability.Event.IP_LIMIT_REJECTED;
            case GLOBAL_PROVIDER_SEND ->
                    SignupOtpObservability.Event.GLOBAL_PROVIDER_LIMIT_REJECTED;
            case CAPTCHA_REQUIRED -> SignupOtpObservability.Event.CAPTCHA_REQUIRED;
        };
        signupOtpObservability.record(
                event,
                SignupOtpObservability.Context.forClientKey(
                        clientKey, denied.reason().name()));
        boolean captcha = denied.reason() == SignupAbuseLimiter.SignupAbuseDenyReason.CAPTCHA_REQUIRED;
        String message = captcha
                ? "Additional verification is required before continuing."
                : "Too many requests. Please try again later.";
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (denied.retryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(denied.retryAfterSeconds()));
        }
        return builder.body(SignupOtpResponse.rateLimited(message, captcha));
    }

    private ResponseEntity<SignupOtpResponse> mapChallengeStatus(
            SignupOtpChallengeService.ChallengeStatusResult status) {
        return switch (status) {
            case SignupOtpChallengeService.ChallengeStatusResult.Active active ->
                    ResponseEntity.ok(fromChallenge(
                            SignupOtpCode.OTP_ALREADY_ACTIVE,
                            "Verification challenge is active.",
                            active.challenge()));
            case SignupOtpChallengeService.ChallengeStatusResult.Exhausted exhausted ->
                    ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(fromChallenge(
                            SignupOtpCode.OTP_RESEND_LIMIT_REACHED,
                            "No more verification codes can be sent for this signup attempt.",
                            exhausted.challenge()));
            case SignupOtpChallengeService.ChallengeStatusResult.Expired expired ->
                    ResponseEntity.status(HttpStatus.GONE).body(fromChallenge(
                            SignupOtpCode.OTP_CHALLENGE_EXPIRED,
                            "This verification session has expired.",
                            expired.challenge()));
            case SignupOtpChallengeService.ChallengeStatusResult.Locked locked ->
                    ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(fromChallenge(
                            SignupOtpCode.OTP_VERIFICATION_LOCKED,
                            "Verification is locked for this signup attempt.",
                            locked.challenge()));
            case SignupOtpChallengeService.ChallengeStatusResult.DeliveryUnknown unknown ->
                    ResponseEntity.accepted().body(fromChallenge(
                            SignupOtpCode.OTP_DELIVERY_UNKNOWN,
                            "Verification email delivery status is unknown. Check your inbox before requesting another code.",
                            unknown.challenge()));
            case SignupOtpChallengeService.ChallengeStatusResult.InFlight inFlight -> {
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CONFLICT);
                if (inFlight.retryAfterSeconds() > 0) {
                    builder.header("Retry-After", String.valueOf(inFlight.retryAfterSeconds()));
                }
                yield builder.body(fromChallenge(
                        SignupOtpCode.OTP_SEND_IN_PROGRESS,
                        "A verification email send is already in progress.",
                        inFlight.challenge()));
            }
            case SignupOtpChallengeService.ChallengeStatusResult.Missing missing ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            "Unable to load verification status."));
        };
    }

    private ResponseEntity<SignupOtpResponse> mapSendResult(
            SignupOtpChallengeService.RequestOtpResult sendResult) {
        if (sendResult == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(SignupOtpResponse.of(
                    SignupOtpCode.OTP_DELIVERY_UNKNOWN,
                    "Unable to send verification code right now. Please try again."));
        }
        return switch (sendResult) {
            case SignupOtpChallengeService.RequestOtpResult.Sent sent -> {
                SignupOtpCode code = sent.newlyAccepted()
                        ? SignupOtpCode.OTP_SENT
                        : SignupOtpCode.OTP_ALREADY_ACTIVE;
                String message = sent.newlyAccepted()
                        ? "Verification code accepted for delivery."
                        : "A verification challenge is already active for this request.";
                SignupOtpResponse response = fromChallenge(code, message, sent.challenge());
                if (sent.attemptToken() != null) {
                    response.setAttemptToken(sent.attemptToken());
                }
                yield ResponseEntity.accepted().body(response);
            }
            case SignupOtpChallengeService.RequestOtpResult.AttemptConflict conflict ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(fromChallenge(
                            SignupOtpCode.OTP_ATTEMPT_CONFLICT,
                            conflict.message(),
                            conflict.challenge()));
            case SignupOtpChallengeService.RequestOtpResult.Denied denied -> {
                SignupOtpCode code = switch (denied.reason()) {
                    case COOLDOWN_ACTIVE -> SignupOtpCode.OTP_RESEND_COOLDOWN;
                    case RESEND_LIMIT_REACHED -> SignupOtpCode.OTP_RESEND_LIMIT_REACHED;
                    case CHALLENGE_EXPIRED -> SignupOtpCode.OTP_CHALLENGE_EXPIRED;
                    case CHALLENGE_LOCKED -> SignupOtpCode.OTP_VERIFICATION_LOCKED;
                    case CHALLENGE_MISSING -> SignupOtpCode.OTP_INVALID;
                };
                HttpStatus status = switch (denied.reason()) {
                    case COOLDOWN_ACTIVE, RESEND_LIMIT_REACHED -> HttpStatus.TOO_MANY_REQUESTS;
                    case CHALLENGE_EXPIRED -> HttpStatus.GONE;
                    case CHALLENGE_LOCKED -> HttpStatus.TOO_MANY_REQUESTS;
                    case CHALLENGE_MISSING -> HttpStatus.BAD_REQUEST;
                };
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
                if (denied.reason() == SignupOtpChallengePersistence.ResendDenialReason.COOLDOWN_ACTIVE
                        && denied.retryAfterSeconds() > 0) {
                    builder.header("Retry-After", String.valueOf(denied.retryAfterSeconds()));
                }
                yield builder.body(fromChallenge(code, denied.message(), denied.challenge()));
            }
            case SignupOtpChallengeService.RequestOtpResult.Failed failed ->
                    ResponseEntity.unprocessableEntity().body(fromChallenge(
                            SignupOtpCode.OTP_DELIVERY_UNKNOWN,
                            "Unable to send a verification email right now. Please try again later.",
                            failed.challenge()));
            case SignupOtpChallengeService.RequestOtpResult.DeliveryUnknown unknown -> {
                SignupOtpResponse response = fromChallenge(
                        SignupOtpCode.OTP_DELIVERY_UNKNOWN,
                        "We couldn't confirm whether the code was sent. Please check your inbox and spam folder. You can request another code when the timer ends.",
                        unknown.challenge());
                if (unknown.attemptToken() != null) {
                    response.setAttemptToken(unknown.attemptToken());
                }
                yield ResponseEntity.accepted().body(response);
            }
            case SignupOtpChallengeService.RequestOtpResult.InFlight inFlight -> {
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CONFLICT);
                if (inFlight.retryAfterSeconds() > 0) {
                    builder.header("Retry-After", String.valueOf(inFlight.retryAfterSeconds()));
                }
                yield builder.body(fromChallenge(
                        SignupOtpCode.OTP_SEND_IN_PROGRESS,
                        "A verification email send is already in progress.",
                        inFlight.challenge()));
            }
        };
    }

    private ResponseEntity<SignupOtpResponse> mapVerifyFailure(
            SignupOtpChallengeService.VerifyOtpResult verifyResult) {
        return switch (verifyResult) {
            case SignupOtpChallengeService.VerifyOtpResult.Verified verified -> null;
            case SignupOtpChallengeService.VerifyOtpResult.Invalid invalid ->
                    ResponseEntity.badRequest().body(fromChallenge(
                            SignupOtpCode.OTP_INVALID,
                            "Invalid verification code.",
                            invalid.challenge()));
            case SignupOtpChallengeService.VerifyOtpResult.OtpExpired expired ->
                    ResponseEntity.status(HttpStatus.GONE).body(fromChallenge(
                            SignupOtpCode.OTP_EXPIRED,
                            "This verification code has expired.",
                            expired.challenge()));
            case SignupOtpChallengeService.VerifyOtpResult.ChallengeExpired expired ->
                    ResponseEntity.status(HttpStatus.GONE).body(fromChallenge(
                            SignupOtpCode.OTP_CHALLENGE_EXPIRED,
                            "This verification session has expired.",
                            expired.challenge()));
            case SignupOtpChallengeService.VerifyOtpResult.Locked locked ->
                    ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(fromChallenge(
                            SignupOtpCode.OTP_VERIFICATION_LOCKED,
                            "Verification is locked for this signup attempt.",
                            locked.challenge()));
            case SignupOtpChallengeService.VerifyOtpResult.Missing missing ->
                    ResponseEntity.badRequest().body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_INVALID,
                            "Invalid verification code."));
            case SignupOtpChallengeService.VerifyOtpResult.AttemptMismatch mismatch ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(SignupOtpResponse.of(
                            SignupOtpCode.OTP_ATTEMPT_CONFLICT,
                            "Invalid or missing signup attempt verification. Please restart signup."));
        };
    }

    private static SignupOtpResponse fromChallenge(
            SignupOtpCode code, String message, SignupOtpChallengeView challenge) {
        SignupOtpResponse response = SignupOtpResponse.of(code, message);
        if (challenge != null) {
            response.setChallengeId(challenge.challengeId());
            response.setServerTime(challenge.serverTime());
            response.setNextResendAt(challenge.nextResendAt());
            response.setOtpExpiresAt(challenge.otpExpiresAt());
            response.setChallengeExpiresAt(challenge.challengeExpiresAt());
            response.setResendsRemaining(challenge.resendsRemaining());
        }
        return response;
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
        response.setCanUpdateProfile(true);
        response.setNextProfileUpdateAt(null);
        response.setNoShowCount(user.getNoShowCount());
        response.setPickupDisabled(user.getNoShowCount() >= CustomerNoShowService.NO_SHOW_THRESHOLD);
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

        return userAuthStore.authenticate(token, jwtUtil);
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

    private boolean looksLikeEmail(String identifier) {
        return identifier != null && identifier.contains("@");
    }

    private Optional<User> findUserByPhone(String digits) { return userAuthStore.findByPhone(digits); }

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

    private boolean findSignupConflict(String email, String normalizedPhoneDigits) {
        if (email != null && !email.isBlank() && userAuthStore.existsByEmailIgnoreCase(email.trim())) {
            return true;
        }

        return normalizedPhoneDigits != null
                && normalizedPhoneDigits.length() == 10
                && phoneAlreadyRegistered(normalizedPhoneDigits);
    }

    private boolean phoneAlreadyRegistered(String normalizedPhoneDigits) {
        return phoneAlreadyRegisteredByAnotherUser(normalizedPhoneDigits, null);
    }

    private boolean phoneAlreadyRegisteredByAnotherUser(String digits, String currentId) {
        return userAuthStore.phoneRegisteredByAnotherUser(digits, currentId);
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
        if (digits.startsWith("1") && digits.length() == 11) {
            digits = digits.substring(1);
        }
        return digits;
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

    private void logSafeAuthFailure(String operation, Exception exception) {
        var builder = logger.atError();
        if (MDC.get(SignupOtpObservability.CORRELATION_MDC_KEY) == null) {
            String cid = SignupOtpObservability.currentCorrelationId();
            if (cid != null && !"none".equals(cid)) {
                builder.addKeyValue("correlationId", cid);
            }
        }
        builder.addKeyValue("operation", operation)
                .addKeyValue("stage", AuthExceptionHandler.classifyStage(exception))
                .addKeyValue("exceptionClass", exception.getClass().getName())
                .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                .log("Authentication operation failed");
    }
}
