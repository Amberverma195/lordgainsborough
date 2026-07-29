package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.PasswordResetProperties;
import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.dto.PasswordResetResponse;
import com.lordgainsborough.restaurant.model.PasswordResetChallengeStatus;
import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.PasswordResetOtpChallengeRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.PasswordUtil;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@Testcontainers
class PasswordResetMongoTest {

    private static final Instant NOW = Instant.parse("2026-07-13T14:00:00Z");
    private static final String IP = "203.0.113.40";
    private static final String EMAIL = "reset.member@example.com";
    private static final String OLD_PASSWORD = "OldPassword1!";
    private static final String NEW_PASSWORD = "NewPassword2@";

    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    private static MongoClient mongoClient;
    private static MongoTemplate mongoTemplate;

    private MutableClock clock;
    private PasswordResetProperties properties;
    private PasswordResetOtpChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private PasswordResetOtpChallengePersistence persistence;
    private PasswordResetService service;
    private PasswordResetService secondService;
    private ConcurrentLinkedQueue<MailCall> mailCalls;
    private JwtUtil jwtUtil;

    @BeforeAll
    static void connectMongo() {
        mongoClient = MongoClients.create(MONGO.getConnectionString());
        mongoTemplate = new MongoTemplate(mongoClient, "password_reset_test");
    }

    @AfterAll
    static void closeMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(PasswordResetOtpChallenge.class);
        mongoTemplate.dropCollection(User.class);
        mongoTemplate.dropCollection(SignupAbuseLimit.class);

        mongoTemplate.indexOps(PasswordResetOtpChallenge.class)
                .ensureIndex(new Index().on("challengeId", Direction.ASC).unique());
        mongoTemplate.indexOps(User.class)
                .ensureIndex(new Index().on("email", Direction.ASC).unique());

        MongoRepositoryFactory factory = new MongoRepositoryFactory(mongoTemplate);
        challengeRepository = factory.getRepository(PasswordResetOtpChallengeRepository.class);
        userRepository = factory.getRepository(UserRepository.class);

        properties = new PasswordResetProperties();
        properties.setResendCooldown(Duration.ofSeconds(30));
        properties.setMaxResends(5);
        properties.setCodeTtl(Duration.ofMinutes(10));
        properties.setChallengeTtl(Duration.ofMinutes(30));
        properties.setMaxVerificationFailures(5);
        properties.setNewChallengesPerEmail(100);
        properties.setEmailOperationsPerIp(1_000);
        properties.setGlobalEmailOperations(10_000);
        properties.setVerificationsPerIp(1_000);

        SignupAbuseProperties hashingProperties = new SignupAbuseProperties();
        hashingProperties.setIpHashSecret("password-reset-test-secret");
        AbuseIpHasher hasher = new AbuseIpHasher(hashingProperties);
        clock = new MutableClock(NOW);
        mailCalls = new ConcurrentLinkedQueue<>();
        EmailSenderService sender = capturingEmailSender(mailCalls);
        AuthSecurityObservability observability =
                new AuthSecurityObservability(new SimpleMeterRegistry());

        persistence = new PasswordResetOtpChallengePersistence(
                challengeRepository, mongoTemplate, properties, hasher, clock);
        PasswordResetAbuseLimiter limiter = new PasswordResetAbuseLimiter(
                new MongoAbuseCounterStore(mongoTemplate), properties, hasher, clock);
        PasswordResetPublicHandleService handles = new PasswordResetPublicHandleService(hasher);
        service = new PasswordResetService(
                persistence,
                limiter,
                handles,
                properties,
                userRepository,
                mongoTemplate,
                sender,
                hasher,
                observability);

        PasswordResetOtpChallengeRepository secondRepository =
                new MongoRepositoryFactory(mongoTemplate)
                        .getRepository(PasswordResetOtpChallengeRepository.class);
        PasswordResetOtpChallengePersistence secondPersistence =
                new PasswordResetOtpChallengePersistence(
                        secondRepository, mongoTemplate, properties, hasher, clock);
        secondService = new PasswordResetService(
                secondPersistence,
                new PasswordResetAbuseLimiter(
                        new MongoAbuseCounterStore(mongoTemplate), properties, hasher, clock),
                new PasswordResetPublicHandleService(hasher),
                properties,
                userRepository,
                mongoTemplate,
                sender,
                hasher,
                observability);

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(
                jwtUtil,
                "secret",
                "password-reset-jwt-test-secret-that-is-at-least-32-bytes");
        ReflectionTestUtils.setField(jwtUtil, "expirationTime", 60_000L);
    }

    @Test
    void initialSendStoresOnlyDigestAndAChallengeIsBoundToItsEmail() {
        User first = saveUser("user-a", EMAIL);
        User second = saveUser("user-b", "other.reset@example.com");

        PasswordResetService.Result firstResult = initial(first, "initial-key-a");
        PasswordResetService.Result secondResult = initial(second, "initial-key-b");
        MailCall firstMail = mailFor(EMAIL);
        MailCall secondMail = mailFor(second.getEmail());
        PasswordResetOtpChallenge stored = loadChallenge(EMAIL);

        assertEquals(HttpStatus.ACCEPTED, firstResult.status());
        assertEquals(6, firstMail.code().length());
        assertTrue(firstMail.code().chars().allMatch(Character::isDigit));
        assertNotEquals(firstMail.code(), stored.getCodeDigest());
        assertTrue(persistence.codeMatches(firstMail.code(), stored.getCodeDigest()));
        assertFalse(persistence.codeMatches("000000", stored.getCodeDigest()));

        Document rawDocument = mongoTemplate
                .getCollection("password_reset_otp_challenges")
                .find(eq("_id", persistence.emailKey(EMAIL)))
                .first();
        assertNotNull(rawDocument);
        assertFalse(rawDocument.values().contains(firstMail.code()), "raw code must not be stored");

        PasswordResetService.Result crossed = service.complete(
                EMAIL,
                secondResult.body().getChallengeId(),
                secondMail.code(),
                NEW_PASSWORD,
                IP,
                "complete-crossed-key");
        assertEquals(HttpStatus.BAD_REQUEST, crossed.status());
        assertEquals("RESET_CODE_INVALID", crossed.body().getCode());
        assertEquals(0L, userRepository.findById(first.getId()).orElseThrow().getTokenVersion());
        assertEquals(0L, userRepository.findById(second.getId()).orElseThrow().getTokenVersion());
        assertEquals(0, loadChallenge(EMAIL).getFailedVerificationAttempts());
    }

    @Test
    void resendEnforcesCooldownFiveResendCapOldCodeInvalidationAndIdempotency() {
        User user = saveUser("user-resend", EMAIL);
        PasswordResetService.Result initial = initial(user, "initial-resend-key");
        String handle = initial.body().getChallengeId();
        String originalCode = mailFor(EMAIL).code();

        PasswordResetService.Result cooling =
                service.resend(EMAIL, handle, IP, "resend-cooldown-key");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, cooling.status());
        assertEquals("RESET_CODE_RESEND_COOLDOWN", cooling.body().getCode());
        assertEquals(30L, cooling.retryAfterSeconds());
        assertEquals(1, mailCalls.size());

        clock.advance(Duration.ofSeconds(31));
        PasswordResetService.Result firstResend =
                service.resend(EMAIL, handle, IP, "resend-idempotent-key");
        assertEquals(HttpStatus.ACCEPTED, firstResend.status());
        assertEquals(2, mailCalls.size());
        String digestAfterRotation = loadChallenge(EMAIL).getCodeDigest();

        PasswordResetService.Result replay = secondService.resend(
                EMAIL,
                firstResend.body().getChallengeId(),
                IP,
                "resend-idempotent-key");
        assertEquals(HttpStatus.ACCEPTED, replay.status());
        assertEquals(2, mailCalls.size(), "idempotent replay must not call provider again");
        assertEquals(1, loadChallenge(EMAIL).getResendCount());
        assertEquals(digestAfterRotation, loadChallenge(EMAIL).getCodeDigest());

        PasswordResetService.Result oldCode = service.complete(
                EMAIL,
                firstResend.body().getChallengeId(),
                originalCode,
                NEW_PASSWORD,
                IP,
                "complete-old-code");
        assertEquals(HttpStatus.BAD_REQUEST, oldCode.status());
        assertEquals("RESET_CODE_INVALID", oldCode.body().getCode());

        handle = firstResend.body().getChallengeId();
        for (int resendNumber = 2; resendNumber <= 5; resendNumber++) {
            clock.advance(Duration.ofSeconds(31));
            PasswordResetService.Result resent = service.resend(
                    EMAIL,
                    handle,
                    IP,
                    "resend-cap-key-" + resendNumber);
            assertEquals(HttpStatus.ACCEPTED, resent.status(), "resend #" + resendNumber);
            handle = resent.body().getChallengeId();
        }

        assertEquals(5, loadChallenge(EMAIL).getResendCount());
        assertEquals(6, mailCalls.size(), "one initial plus five resends");
        clock.advance(Duration.ofSeconds(31));
        PasswordResetService.Result capped =
                service.resend(EMAIL, handle, IP, "resend-over-cap-key");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, capped.status());
        assertEquals("RESET_CODE_RESEND_LIMIT_REACHED", capped.body().getCode());
        assertEquals(6, mailCalls.size());
    }

    @Test
    void completionRequiresStrictSixAsciiDigitsAndRejectsOverlongPassword() {
        User user = saveUser("user-validation", EMAIL);
        PasswordResetService.Result initial = initial(user, "initial-validation-key");
        String handle = initial.body().getChallengeId();
        String validCode = mailFor(EMAIL).code();

        for (String malformed : List.of(
                "12345",
                "1234567",
                " 123456",
                "123456 ",
                "+123456",
                "１２３４５６")) {
            PasswordResetService.Result result = service.complete(
                    EMAIL,
                    handle,
                    malformed,
                    NEW_PASSWORD,
                    IP,
                    "strict-code-key-" + malformed.length());
            assertEquals(HttpStatus.BAD_REQUEST, result.status(), malformed);
            assertEquals("RESET_CODE_INVALID", result.body().getCode());
        }
        assertEquals(0, loadChallenge(EMAIL).getFailedVerificationAttempts());

        String overlongPassword = "Aa1!" + "x".repeat(125);
        assertEquals(129, overlongPassword.length());
        PasswordResetService.Result overlong = service.complete(
                EMAIL,
                handle,
                validCode,
                overlongPassword,
                IP,
                "overlong-password-key");
        assertEquals(HttpStatus.BAD_REQUEST, overlong.status());
        assertEquals("RESET_CODE_INVALID", overlong.body().getCode());
        assertEquals(0L, userRepository.findById(user.getId()).orElseThrow().getTokenVersion());
        assertTrue(PasswordUtil.checkPassword(
                OLD_PASSWORD,
                userRepository.findById(user.getId()).orElseThrow().getPassword()));
        assertEquals(PasswordResetChallengeStatus.ACTIVE, loadChallenge(EMAIL).getStatus());
    }

    @Test
    void fifthWrongCodeLocksChallengeAndRejectsTheCorrectCodeAfterward() {
        User user = saveUser("user-lock", EMAIL);
        PasswordResetService.Result initial = initial(user, "initial-lock-key");
        String handle = initial.body().getChallengeId();
        String correctCode = mailFor(EMAIL).code();

        for (int attempt = 1; attempt <= 4; attempt++) {
            PasswordResetService.Result wrong = service.complete(
                    EMAIL,
                    handle,
                    "000000",
                    NEW_PASSWORD,
                    IP,
                    "wrong-code-key-" + attempt);
            assertEquals(HttpStatus.BAD_REQUEST, wrong.status());
        }
        PasswordResetService.Result fifth = service.complete(
                EMAIL,
                handle,
                "000000",
                NEW_PASSWORD,
                IP,
                "wrong-code-key-5");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, fifth.status());
        assertEquals("RESET_VERIFICATION_LOCKED", fifth.body().getCode());

        PasswordResetOtpChallenge locked = loadChallenge(EMAIL);
        assertEquals(5, locked.getFailedVerificationAttempts());
        assertEquals(PasswordResetChallengeStatus.LOCKED, locked.getStatus());
        PasswordResetService.Result correctAfterLock = service.complete(
                EMAIL,
                handle,
                correctCode,
                NEW_PASSWORD,
                IP,
                "correct-after-lock-key");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, correctAfterLock.status());
        assertEquals(0L, userRepository.findById(user.getId()).orElseThrow().getTokenVersion());
    }

    @Test
    void codeAndChallengeExpiryAreEnforcedAtTheirExactBoundaries() {
        User codeExpiryUser = saveUser("user-code-expiry", EMAIL);
        PasswordResetService.Result initial = initial(codeExpiryUser, "initial-expiry-key");
        String code = mailFor(EMAIL).code();
        clock.advance(properties.getCodeTtl());

        PasswordResetService.Result codeExpired = service.complete(
                EMAIL,
                initial.body().getChallengeId(),
                code,
                NEW_PASSWORD,
                IP,
                "complete-expired-code");
        assertEquals(HttpStatus.GONE, codeExpired.status());
        assertEquals("RESET_CHALLENGE_EXPIRED", codeExpired.body().getCode());

        clock.set(NOW);
        User challengeExpiryUser =
                saveUser("user-challenge-expiry", "challenge.expiry@example.com");
        PasswordResetService.Result challengeInitial =
                initial(challengeExpiryUser, "initial-challenge-expiry");
        clock.advance(properties.getChallengeTtl());
        PasswordResetService.Result challengeExpired = service.resend(
                challengeExpiryUser.getEmail(),
                challengeInitial.body().getChallengeId(),
                IP,
                "resend-expired-challenge");
        assertEquals(HttpStatus.GONE, challengeExpired.status());
        assertEquals("RESET_CHALLENGE_EXPIRED", challengeExpired.body().getCode());
    }

    @Test
    void successfulResetUpdatesPasswordOnceIncrementsVersionAndRevokesOldJwt() {
        User user = saveUser("user-success", EMAIL);
        String oldJwt = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getTokenVersion());
        PasswordResetService.Result initial = initial(user, "initial-success-key");
        String code = mailFor(EMAIL).code();

        PasswordResetService.Result completed = service.complete(
                EMAIL,
                initial.body().getChallengeId(),
                code,
                NEW_PASSWORD,
                IP,
                "complete-success-key");

        assertEquals(HttpStatus.OK, completed.status());
        assertEquals("PASSWORD_RESET_COMPLETED", completed.body().getCode());
        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(1L, updated.getTokenVersion());
        assertNotNull(updated.getPasswordChangedAt());
        assertTrue(PasswordUtil.checkPassword(NEW_PASSWORD, updated.getPassword()));
        assertFalse(PasswordUtil.checkPassword(OLD_PASSWORD, updated.getPassword()));
        assertFalse(jwtUtil.validateTokenForUser(oldJwt, updated));

        PasswordResetOtpChallenge consumed = loadChallenge(EMAIL);
        assertEquals(PasswordResetChallengeStatus.CONSUMED, consumed.getStatus());
        assertTrue(consumed.isPasswordUpdated());
        assertNull(consumed.getCodeDigest());

        PasswordResetService.Result replay = secondService.complete(
                EMAIL,
                initial.body().getChallengeId(),
                code,
                NEW_PASSWORD,
                IP,
                "complete-success-key");
        assertEquals(HttpStatus.OK, replay.status());
        assertEquals(1L, userRepository.findById(user.getId()).orElseThrow().getTokenVersion());
        assertEquals(1, mailCalls.size(), "provider operation occurs only for the initial code");
    }

    @Test
    void concurrentResendsAcrossTwoServicesRotateAndCallProviderOnce() throws Exception {
        User user = saveUser("user-resend-race", EMAIL);
        PasswordResetService.Result initial = initial(user, "initial-race-key");
        String handle = initial.body().getChallengeId();
        String oldDigest = loadChallenge(EMAIL).getCodeDigest();
        clock.advance(Duration.ofSeconds(31));
        mailCalls.clear();

        List<PasswordResetService.Result> results = race(
                20,
                index -> serviceFor(index).resend(
                        EMAIL,
                        handle,
                        IP,
                        "concurrent-resend-key-" + index));

        assertEquals(
                1,
                results.stream().filter(result -> result.status() == HttpStatus.ACCEPTED).count());
        assertEquals(1, mailCalls.size(), "only the winning reservation calls the provider");
        PasswordResetOtpChallenge stored = loadChallenge(EMAIL);
        assertEquals(1, stored.getResendCount());
        assertNotEquals(oldDigest, stored.getCodeDigest());
        assertTrue(persistence.codeMatches(mailCalls.element().code(), stored.getCodeDigest()));
        assertEquals(mailCalls.element().operationId(), stored.getSendOperationId());
    }

    @Test
    void concurrentCorrectResetsHaveOneWinnerAndOnePasswordMutation() throws Exception {
        User user = saveUser("user-reset-race", EMAIL);
        PasswordResetService.Result initial = initial(user, "initial-reset-race");
        String handle = initial.body().getChallengeId();
        String code = mailFor(EMAIL).code();

        List<PasswordResetService.Result> results = race(
                20,
                index -> serviceFor(index).complete(
                        EMAIL,
                        handle,
                        code,
                        NEW_PASSWORD,
                        IP,
                        "concurrent-complete-key-" + index));

        assertEquals(
                1,
                results.stream().filter(result -> result.status() == HttpStatus.OK).count());
        assertEquals(
                19,
                results.stream().filter(result -> result.status() == HttpStatus.BAD_REQUEST).count());
        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(1L, updated.getTokenVersion());
        assertNotNull(updated.getLastPasswordResetOperationId());
        assertTrue(PasswordUtil.checkPassword(NEW_PASSWORD, updated.getPassword()));
        PasswordResetOtpChallenge consumed = loadChallenge(EMAIL);
        assertEquals(PasswordResetChallengeStatus.CONSUMED, consumed.getStatus());
        assertTrue(consumed.isPasswordUpdated());
    }

    private PasswordResetService serviceFor(int index) {
        return index % 2 == 0 ? service : secondService;
    }

    private User saveUser(String id, String email) {
        User user = new User();
        user.setId(id);
        user.setName("Password Reset Test");
        user.setEmail(email);
        user.setPassword(PasswordUtil.hashPassword(OLD_PASSWORD));
        user.setTokenVersion(0L);
        return userRepository.save(user);
    }

    private PasswordResetService.Result initial(User user, String idempotencyKey) {
        return service.requestInitial(
                user.getEmail(),
                Optional.of(user),
                IP,
                idempotencyKey);
    }

    private PasswordResetOtpChallenge loadChallenge(String email) {
        return challengeRepository.findById(persistence.emailKey(email)).orElseThrow();
    }

    private MailCall mailFor(String recipient) {
        return mailCalls.stream()
                .filter(call -> recipient.equals(call.recipient()))
                .findFirst()
                .orElseThrow();
    }

    private static EmailSenderService capturingEmailSender(
            ConcurrentLinkedQueue<MailCall> calls) {
        EmailSenderService sender = mock(EmailSenderService.class);
        doAnswer(invocation -> {
            calls.add(new MailCall(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)));
            return null;
        }).when(sender).sendPasswordResetCodeEmail(anyString(), anyString(), anyString());
        return sender;
    }

    private static <T> List<T> race(int participants, RacingOperation<T> operation)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(participants);
        CyclicBarrier barrier = new CyclicBarrier(participants);
        List<Callable<T>> tasks = new ArrayList<>(participants);
        for (int index = 0; index < participants; index++) {
            int taskIndex = index;
            tasks.add(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return operation.run(taskIndex);
            });
        }

        try {
            List<Future<T>> futures = new ArrayList<>(participants);
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>(participants);
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface RacingOperation<T> {
        T run(int index) throws Exception;
    }

    private record MailCall(String recipient, String code, String operationId) {
        private MailCall {
            assertNotNull(recipient);
            assertNotNull(code);
            assertNotNull(operationId);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
