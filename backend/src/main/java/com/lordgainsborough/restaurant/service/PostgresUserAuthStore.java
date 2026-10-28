package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import com.lordgainsborough.restaurant.security.JwtUtil;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class PostgresUserAuthStore implements UserAuthStore {
    private final JpaUserRepository users;
    private final JpaCustomerOrderRepository orders;
    private final JpaOrderReviewRepository reviews;
    private final JpaTransactionManager manager;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PostgresUserAuthStore(JpaUserRepository users, JpaCustomerOrderRepository orders,
            JpaOrderReviewRepository reviews, JpaTransactionManager manager, Clock clock) {
        this.users = users; this.orders = orders; this.reviews = reviews; this.manager = manager; this.clock = clock.withZone(java.time.ZoneId.systemDefault());
        jdbc = new JdbcTemplate(Objects.requireNonNull(manager.getDataSource()));
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public JpaTransactionManager getTransactionManager() { return manager; }
    public EntityManagerFactory getEntityManagerFactory() { return manager.getEntityManagerFactory(); }
    public JpaUserRepository getUserRepository() { return users; }
    public JpaCustomerOrderRepository getOrderRepository() { return orders; }

    public Optional<User> findByEmailIgnoreCase(String email) {
        if (!usableEmail(email)) return Optional.empty();
        return reads.execute(tx -> users.findByEmailIgnoreCase(email).map(this::snapshot));
    }
    public boolean existsByEmailIgnoreCase(String email) {
        return usableEmail(email) && Boolean.TRUE.equals(reads.execute(tx -> users.existsByEmailIgnoreCase(email)));
    }
    public Optional<User> findByPhone(String digits) {
        if (digits == null || !digits.matches("[0-9]{10}")) return Optional.empty();
        return reads.execute(tx -> {
            var found = users.findByPhoneNormalized(digits);
            // Existing schema guarantees uniqueness. Never choose an arbitrary account if that invariant is broken.
            return found.size() == 1 ? Optional.of(snapshot(found.getFirst())) : Optional.empty();
        });
    }
    public boolean phoneRegisteredByAnotherUser(String digits, String currentId) {
        if (digits == null || !digits.matches("[0-9]{10}")) return false;
        return Boolean.TRUE.equals(reads.execute(tx -> users.findByPhoneNormalized(digits).stream()
                .anyMatch(u -> !Objects.equals(u.getId(), currentId))));
    }
    @Override public Optional<User> authenticate(String token, JwtUtil jwt) {
        if (token == null || token.isBlank() || !jwt.validateToken(token)) return Optional.empty();
        String email = jwt.getEmailFromToken(token);
        if (!usableEmail(email)) return Optional.empty();
        return reads.execute(tx -> users.findByEmailIgnoreCase(email)
                .filter(u -> jwt.validateTokenForIdentity(token, u.getId(), u.getTokenVersion())).map(this::snapshot));
    }
    public Optional<User> confirmLogin(User checked) {
        return writes.execute(tx -> users.findForAuthById(checked.getId())
                .filter(u -> Objects.equals(u.getEmail(), checked.getEmail())
                        && Objects.equals(u.getPassword(), checked.getPassword())
                        && u.getTokenVersion() == checked.getTokenVersion()).map(this::snapshot));
    }
    public Registration register(User draft, String rawPassword, String otp, SignupOtpChallengeService challenges) {
        // Validate all PostgreSQL-bound values and hash passwords before entering this transaction.
        PostgresInput.text(draft.getName(), 255, true);
        PostgresInput.text(draft.getEmail(), 255, true);
        if (draft.getPhoneNormalized() == null || !draft.getPhoneNormalized().matches("[0-9]{10}")) PostgresInput.invalid();
        JpaUser created = new JpaUser();
        created.setId(UUID.randomUUID().toString());
        created.setName(draft.getName()); created.setEmail(draft.getEmail().trim().toLowerCase(Locale.ROOT));
        String digits = draft.getPhoneNormalized();
        created.setPhone(String.format("+1 (%s)-%s-%s", digits.substring(0, 3), digits.substring(3, 6), digits.substring(6)));
        created.setPhoneNormalized(digits);
        created.setPassword(com.lordgainsborough.restaurant.security.PasswordUtil.hashPassword(rawPassword)); created.setRole("MEMBER");
        if (!(challenges.getPersistence().getStore() instanceof PostgresSignupOtpChallengeStore challengeStore)
                || challengeStore.getTransactionManager() != manager || challengeStore.getDataSource() != manager.getDataSource())
            throw new IllegalStateException("Signup OTP and user creation must share actual PostgreSQL transaction resources");
        return writes.execute(tx -> {
            // JDBC OTP transitions join this exact JpaTransactionManager/DataSource transaction.
            // Wrong-code attempts commit their counters; they are not rolled back by an exception response.
            var verification = challenges.verifyOtp(created.getEmail(), otp);
            if (!(verification instanceof SignupOtpChallengeService.VerifyOtpResult.Verified))
                return new Registration(null, verification, false);
            created.setCreatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
            created.setMemberSince(created.getCreatedAt().format(DateTimeFormatter.ofPattern("MMMM yyyy")));
            int inserted = jdbc.update("""
                    insert into users (id, name, email, phone, phone_normalized, password, role, member_since,
                        created_at, token_version, no_show_count)
                    values (?, ?, ?, ?, ?, ?, 'MEMBER', ?, ?, 0, 0) on conflict do nothing
                    """, created.getId(), created.getName(), created.getEmail(), created.getPhone(),
                    created.getPhoneNormalized(), created.getPassword(), created.getMemberSince(), Timestamp.valueOf(created.getCreatedAt()));
            if (inserted == 0) {
                // Only actual email/phone conflicts receive the public generic conflict response.
                // SQL/check/connection errors propagate and cannot masquerade as an existing account.
                if (!users.existsByEmailIgnoreCase(created.getEmail()) && !users.existsByPhoneNormalized(created.getPhoneNormalized()))
                    throw new IllegalStateException("Unexpected identity uniqueness conflict");
                // Keep VERIFIED consumed; do not reset/reactivate it after an unsuccessful insert.
                return new Registration(null, verification, true);
            }
            challenges.clear(created.getEmail());
            return new Registration(snapshot(created), verification, false);
        });
    }

    @Override
    public Registration registerPending(String email, String otp, String challengeId, SignupOtpChallengeService challenges) {
        PostgresInput.text(email, 255, true);
        String cleanEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!(challenges.getPersistence().getStore() instanceof PostgresSignupOtpChallengeStore challengeStore)
                || challengeStore.getTransactionManager() != manager || challengeStore.getDataSource() != manager.getDataSource())
            throw new IllegalStateException("Signup OTP and user creation must share actual PostgreSQL transaction resources");
        return writes.execute(tx -> {
            String emailKey = challenges.getPersistence().emailKeyFromRawEmail(cleanEmail);
            Optional<SignupOtpChallenge> optChallenge = challengeStore.findById(emailKey);
            if (optChallenge.isEmpty()) {
                return new Registration(null, new SignupOtpChallengeService.VerifyOtpResult.Missing(), false);
            }
            SignupOtpChallenge challenge = optChallenge.get();
            if (challengeId != null && !challengeId.isBlank() && !Objects.equals(challenge.getChallengeId(), challengeId)) {
                return new Registration(null, new SignupOtpChallengeService.VerifyOtpResult.Missing(), false);
            }
            if (challenge.getPendingPasswordHash() == null || challenge.getPendingPasswordHash().isBlank()
                    || challenge.getPendingName() == null || challenge.getPendingName().isBlank()
                    || challenge.getPendingPhoneNormalized() == null || !challenge.getPendingPhoneNormalized().matches("[0-9]{10}")) {
                return new Registration(null, new SignupOtpChallengeService.VerifyOtpResult.Missing(), false);
            }

            var verification = challenges.verifyOtp(cleanEmail, otp);
            if (!(verification instanceof SignupOtpChallengeService.VerifyOtpResult.Verified)) {
                return new Registration(null, verification, false);
            }

            JpaUser created = new JpaUser();
            created.setId(UUID.randomUUID().toString());
            created.setName(challenge.getPendingName());
            created.setEmail(cleanEmail);
            String digits = challenge.getPendingPhoneNormalized();
            created.setPhone(challenge.getPendingPhone() != null && !challenge.getPendingPhone().isBlank()
                    ? challenge.getPendingPhone()
                    : String.format("+1 (%s)-%s-%s", digits.substring(0, 3), digits.substring(3, 6), digits.substring(6)));
            created.setPhoneNormalized(digits);
            created.setPassword(challenge.getPendingPasswordHash());
            created.setRole("MEMBER");
            created.setCreatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
            created.setMemberSince(created.getCreatedAt().format(DateTimeFormatter.ofPattern("MMMM yyyy")));

            int inserted = jdbc.update("""
                    insert into users (id, name, email, phone, phone_normalized, password, role, member_since,
                        created_at, token_version, no_show_count)
                    values (?, ?, ?, ?, ?, ?, 'MEMBER', ?, ?, 0, 0) on conflict do nothing
                    """, created.getId(), created.getName(), created.getEmail(), created.getPhone(),
                    created.getPhoneNormalized(), created.getPassword(), created.getMemberSince(), Timestamp.valueOf(created.getCreatedAt()));

            if (inserted == 0) {
                if (!users.existsByEmailIgnoreCase(created.getEmail()) && !users.existsByPhoneNormalized(created.getPhoneNormalized()))
                    throw new IllegalStateException("Unexpected identity uniqueness conflict");
                return new Registration(null, verification, true);
            }
            challenges.clear(cleanEmail);
            return new Registration(snapshot(created), verification, false);
        });
    }
    public Optional<User> updateName(User user, String token, String name, JwtUtil jwt) {
        PostgresInput.text(name, 255, true);
        return writes.execute(tx -> users.findForAuthById(user.getId())
                .filter(u -> jwt.validateTokenForIdentity(token, u.getId(), u.getTokenVersion())
                        && Objects.equals(u.getEmail(), jwt.getEmailFromToken(token)))
                .map(u -> {
                    if (users.updateProfileName(u.getId(), name) != 1) throw new IllegalStateException("Profile update lost its owner");
                    User result = snapshot(u); result.setName(name); return result;
                }));
    }
    public Optional<List<CustomerOrder>> history(User user, String token, JwtUtil jwt) {
        if (!jwt.validateToken(token)) return Optional.empty();
        return reads.execute(tx -> users.findByEmailIgnoreCase(jwt.getEmailFromToken(token))
                .filter(u -> jwt.validateTokenForIdentity(token, u.getId(), u.getTokenVersion()) && u.getId().equals(user.getId()))
                .map(u -> {
                    String phone = u.getPhoneNormalized();
                    if (phone == null || phone.isBlank()) phone = normalizePhone(u.getPhone());
                    boolean validPhone = phone.length() == 10 && u.getPhone() != null && !u.getPhone().isBlank();
                    List<CustomerOrder> found = new PostgresCustomerHistoryReader(orders).load(
                            List.of(u.getEmail().toLowerCase(Locale.ROOT)), validPhone ? List.of(u.getPhone()) : List.of());
                    Map<String, CustomerOrder> distinct = new LinkedHashMap<>();
                    // Email first and exact raw-phone fallback, with normalized equality checked again.
                    for (var order : found) if (u.getEmail().equalsIgnoreCase(order.getCustomerEmail()))
                        distinct.putIfAbsent(order.getId(), order);
                    if (validPhone) for (var order : found)
                        if (u.getPhone().equals(order.getCustomerPhone()) && phone.equals(normalizePhone(order.getCustomerPhone())))
                            distinct.putIfAbsent(order.getId(), order);
                    List<CustomerOrder> result = new ArrayList<>(distinct.values());
                    for (int start = 0; start < result.size(); start += 128) {
                        var batch = result.subList(start, Math.min(start + 128, result.size()));
                        Set<String> reviewed = new HashSet<>(reviews.findReviewedOrderIds(batch.stream().map(CustomerOrder::getId).toList()));
                        batch.forEach(o -> o.setReviewed(o.isReviewed() || reviewed.contains(o.getId())));
                    }
                    result.sort(Comparator.comparing(CustomerOrder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
                    return result;
                }));
    }
    public PasswordResetService.Result requestPasswordReset(PasswordResetService service, String email, String ip, String key) {
        if (!(service.getPersistence().getStore() instanceof PostgresPasswordResetOtpChallengeStore reset)
                || reset.getTransactionManager() != manager || reset.getDataSource() != manager.getDataSource())
            throw new IllegalStateException("Password reset and authentication must share actual PostgreSQL transaction resources");
        Optional<JpaUser> found = usableEmail(email) ? reads.execute(tx -> users.findByEmailIgnoreCase(email)) : Optional.empty();
        // Email delivery happens after the identity read transaction has ended.
        return service.requestInitialForJpaUser(email, found, ip, key);
    }
    private User snapshot(JpaUser source) {
        User result = new User();
        result.setId(source.getId()); result.setName(source.getName()); result.setEmail(source.getEmail());
        result.setPhone(source.getPhone()); result.setPhoneNormalized(source.getPhoneNormalized());
        result.setPassword(source.getPassword()); result.setRole(source.getRole()); result.setTokenVersion(source.getTokenVersion());
        result.setCreatedAt(source.getCreatedAt()); result.setMemberSince(source.getMemberSince());
        result.setLastProfileUpdateAt(source.getLastProfileUpdateAt()); result.setNoShowCount(source.getNoShowCount());
        return result;
    }
    private static boolean usableEmail(String email) {
        if (email == null || email.isBlank()) return false;
        try { PostgresInput.text(email, 255, true); return true; }
        catch (org.springframework.web.server.ResponseStatusException invalid) { return false; }
    }
    private static String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() == 11) digits = digits.substring(1);
        return digits;
    }
}
