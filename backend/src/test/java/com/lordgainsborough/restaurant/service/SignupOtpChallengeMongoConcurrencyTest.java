package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.controller.AuthController;
import com.lordgainsborough.restaurant.dto.SignupRequest;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.OrderReviewRepository;
import com.lordgainsborough.restaurant.repository.SignupOtpChallengeRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-MongoDB coverage for every signup OTP concurrency/atomicity guarantee.
 *
 * <p>The class name ends in {@code Test} deliberately so Maven Surefire runs it during
 * the normal {@code test} phase. Docker is a required test dependency: an unavailable
 * real MongoDB must fail the suite rather than produce a false green atomicity result.</p>
 */
@Testcontainers
class SignupOtpChallengeMongoConcurrencyTest {

    private static final int THREADS = 20;
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final String EMAIL = "mongo.race@example.com";
    private static final String OLD_OTP = "123456";

    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    private static MongoClient mongoClient;
    private static MongoTemplate mongoTemplate;

    private MutableClock clock;
    private SignupOtpChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private SignupOtpChallengePersistence persistence;
    private SignupOtpChallengeService service;
    private SignupOtpObservability observability;
    private EmailSenderService emailSenderService;
    private ConcurrentLinkedQueue<MailCall> mailCalls;

    @BeforeAll
    static void connectMongo() {
        mongoClient = MongoClients.create(MONGO.getConnectionString());
        mongoTemplate = new MongoTemplate(mongoClient, "signup_otp_concurrency");
    }

    @AfterAll
    static void closeMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(SignupOtpChallenge.class);
        mongoTemplate.dropCollection(User.class);

        MongoRepositoryFactory factory = new MongoRepositoryFactory(mongoTemplate);
        challengeRepository = factory.getRepository(SignupOtpChallengeRepository.class);
        userRepository = factory.getRepository(UserRepository.class);

        mongoTemplate.indexOps(User.class)
                .ensureIndex(new Index().on("email", Direction.ASC).unique());
        mongoTemplate.indexOps(User.class)
                .ensureIndex(new Index().on("phoneNormalized", Direction.ASC).unique().sparse());

        clock = new MutableClock(NOW);
        mailCalls = new ConcurrentLinkedQueue<>();
        emailSenderService = capturingEmailSender(mailCalls);
        persistence = new SignupOtpChallengePersistence(challengeRepository, mongoTemplate, clock);
        observability = new SignupOtpObservability(new SimpleMeterRegistry());
        service = new SignupOtpChallengeService(
                persistence, emailSenderService, observability);
    }

    @Test
    void twentyConcurrentInitialSendsCreateOneChallengeAcceptOneSendAndCallMailOnce()
            throws Exception {
        List<SignupOtpChallengeService.RequestOtpResult> results = race(
                THREADS,
                index -> service.requestSignupOtp(EMAIL, "initial-" + index));

        assertEquals(1, results.stream().filter(this::isNewlyAcceptedSend).count());
        assertEquals(1, challengeRepository.count());
        assertEquals(1, mailCalls.size());

        SignupOtpChallenge stored = loadChallenge(EMAIL);
        assertEquals(OtpChallengeStatus.ACTIVE, stored.getStatus());
        assertEquals(OtpSendStatus.SENT, stored.getSendStatus());
        assertEquals(0, stored.getResendCount());
        assertTrue(persistence.otpMatches(mailCalls.element().otp(), stored.getOtpDigest()));
    }

    @Test
    void twentyConcurrentResendsAfterCooldownConsumeOneSlotRotateOnceAndCallMailOnce()
            throws Exception {
        SignupOtpChallenge before = seedActiveChallenge(EMAIL, OLD_OTP, NOW.minusSeconds(1));
        String oldDigest = before.getOtpDigest();

        List<SignupOtpChallengeService.RequestOtpResult> results = race(
                THREADS,
                index -> service.resendOtp(EMAIL, "resend-" + index));

        assertEquals(1, results.stream().filter(this::isNewlyAcceptedSend).count());
        assertEquals(1, mailCalls.size());
        assertEquals(1, challengeRepository.count());

        SignupOtpChallenge stored = loadChallenge(EMAIL);
        assertEquals(1, stored.getResendCount());
        assertNotEquals(oldDigest, stored.getOtpDigest());
        assertEquals(OtpSendStatus.SENT, stored.getSendStatus());
        assertTrue(persistence.otpMatches(mailCalls.element().otp(), stored.getOtpDigest()));
    }

    @Test
    void twoIndependentServicesSharingMongoKeepInitialAndResendGuarantees() throws Exception {
        SignupOtpChallengeRepository secondRepository =
                new MongoRepositoryFactory(mongoTemplate)
                        .getRepository(SignupOtpChallengeRepository.class);
        SignupOtpChallengePersistence secondPersistence =
                new SignupOtpChallengePersistence(secondRepository, mongoTemplate, clock);
        SignupOtpChallengeService secondService =
                new SignupOtpChallengeService(
                        secondPersistence, emailSenderService, observability);

        List<SignupOtpChallengeService.RequestOtpResult> initialResults = race(
                THREADS,
                index -> serviceForIndex(index, service, secondService)
                        .requestSignupOtp(EMAIL, "two-service-initial-" + index));

        assertEquals(1, initialResults.stream().filter(this::isNewlyAcceptedSend).count());
        assertEquals(1, challengeRepository.count());
        assertEquals(1, mailCalls.size());

        mongoTemplate.updateFirst(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id")
                                .is(persistence.emailKeyFromRawEmail(EMAIL))),
                new org.springframework.data.mongodb.core.query.Update()
                        .set("nextResendAt", NOW.minusSeconds(1))
                        .set("sendStatus", OtpSendStatus.SENT),
                SignupOtpChallenge.class);
        String digestBeforeResend = loadChallenge(EMAIL).getOtpDigest();
        mailCalls.clear();

        List<SignupOtpChallengeService.RequestOtpResult> resendResults = race(
                THREADS,
                index -> serviceForIndex(index, service, secondService)
                        .resendOtp(EMAIL, "two-service-resend-" + index));

        assertEquals(1, resendResults.stream().filter(this::isNewlyAcceptedSend).count());
        assertEquals(1, challengeRepository.count());
        assertEquals(1, mailCalls.size());

        SignupOtpChallenge stored = loadChallenge(EMAIL);
        assertEquals(1, stored.getResendCount());
        assertNotEquals(digestBeforeResend, stored.getOtpDigest());
        assertTrue(persistence.otpMatches(mailCalls.element().otp(), stored.getOtpDigest()));
    }

    @Test
    void expiredDocumentStillPresentIsAtomicallyReplacedOnceAndMailedOnce() throws Exception {
        SignupOtpChallenge expired = seedActiveChallenge(
                EMAIL,
                OLD_OTP,
                NOW.minusSeconds(120));
        expired.setChallengeId("expired-challenge");
        expired.setChallengeExpiresAt(NOW.minusSeconds(1));
        expired.setOtpExpiresAt(NOW.minusSeconds(1));
        expired.setResendCount(3);
        challengeRepository.save(expired);

        List<SignupOtpChallengeService.RequestOtpResult> results = race(
                THREADS,
                index -> service.requestSignupOtp(EMAIL, "expired-replace-" + index));

        assertEquals(1, results.stream().filter(this::isNewlyAcceptedSend).count());
        assertEquals(1, challengeRepository.count());
        assertEquals(1, mailCalls.size());

        SignupOtpChallenge stored = loadChallenge(EMAIL);
        assertNotEquals("expired-challenge", stored.getChallengeId());
        assertEquals(0, stored.getResendCount());
        assertEquals(OtpChallengeStatus.ACTIVE, stored.getStatus());
        assertTrue(stored.getChallengeExpiresAt().isAfter(NOW));
        assertTrue(persistence.otpMatches(mailCalls.element().otp(), stored.getOtpDigest()));
    }

    @Test
    void twentyConcurrentCorrectVerificationsTransitionAndCreateExactlyOneUser()
            throws Exception {
        seedActiveChallenge(EMAIL, OLD_OTP, NOW.minusSeconds(1));
        AtomicInteger verifiedTransitions = new AtomicInteger();

        SignupOtpChallengePersistence observablePersistence =
                new SignupOtpChallengePersistence(challengeRepository, mongoTemplate, clock) {
                    @Override
                    public java.util.Optional<SignupOtpChallenge> tryAtomicMarkVerified(
                            String emailKey, String otpDigest, Instant now) {
                        java.util.Optional<SignupOtpChallenge> result =
                                super.tryAtomicMarkVerified(emailKey, otpDigest, now);
                        if (result.isPresent()) {
                            verifiedTransitions.incrementAndGet();
                        }
                        return result;
                    }
                };
        SignupOtpChallengeService observableService =
                new SignupOtpChallengeService(
                        observablePersistence, emailSenderService, observability);
        AuthController controller = signupController(observableService);
        HttpServletRequest httpRequest = httpRequest();

        List<ResponseEntity<?>> responses = race(
                THREADS,
                index -> controller.signup(signupRequest(OLD_OTP), httpRequest));

        assertEquals(
                1,
                responses.stream()
                        .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                        .count());
        assertEquals(1, verifiedTransitions.get());
        assertEquals(1, userRepository.count());
        assertEquals(0, challengeRepository.count(), "successful signup cleans up the challenge");
        assertEquals(0, mailCalls.size());
    }

    @Test
    void oldOtpRacingWithResendOnlyVerifiesIfItsDigestRemainsCurrent() throws Exception {
        SignupOtpChallenge seeded = seedActiveChallenge(EMAIL, OLD_OTP, NOW.minusSeconds(1));
        String oldDigest = seeded.getOtpDigest();

        List<Object> results = race(
                2,
                index -> index == 0
                        ? service.verifyOtp(EMAIL, OLD_OTP)
                        : service.resendOtp(EMAIL, "old-otp-race-resend"));

        SignupOtpChallengeService.VerifyOtpResult verifyResult =
                (SignupOtpChallengeService.VerifyOtpResult) results.get(0);
        SignupOtpChallengeService.RequestOtpResult resendResult =
                (SignupOtpChallengeService.RequestOtpResult) results.get(1);
        SignupOtpChallenge stored = loadChallenge(EMAIL);

        if (verifyResult instanceof SignupOtpChallengeService.VerifyOtpResult.Verified) {
            assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Denied.class, resendResult);
            assertEquals(OtpChallengeStatus.VERIFIED, stored.getStatus());
            assertEquals(oldDigest, stored.getOtpDigest());
            assertEquals(0, mailCalls.size());
        } else {
            assertInstanceOf(SignupOtpChallengeService.VerifyOtpResult.Invalid.class, verifyResult);
            assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, resendResult);
            assertEquals(OtpChallengeStatus.ACTIVE, stored.getStatus());
            assertNotEquals(oldDigest, stored.getOtpDigest());
            assertEquals(1, mailCalls.size());
            assertTrue(persistence.otpMatches(mailCalls.element().otp(), stored.getOtpDigest()));
            assertInstanceOf(
                    SignupOtpChallengeService.VerifyOtpResult.Invalid.class,
                    service.verifyOtp(EMAIL, OLD_OTP));
            assertInstanceOf(
                    SignupOtpChallengeService.VerifyOtpResult.Verified.class,
                    service.verifyOtp(EMAIL, mailCalls.element().otp()));
        }
    }

    @Test
    void successfulUserInsertWithFailedChallengeDeletionCannotDuplicateOrReuseVerifiedOtp() {
        seedActiveChallenge(EMAIL, OLD_OTP, NOW.minusSeconds(1));
        AtomicBoolean failDeleteOnce = new AtomicBoolean(true);
        SignupOtpChallengePersistence failingDeletePersistence =
                new SignupOtpChallengePersistence(challengeRepository, mongoTemplate, clock) {
                    @Override
                    public void deleteByEmail(String email) {
                        if (failDeleteOnce.compareAndSet(true, false)) {
                            throw new IllegalStateException("simulated challenge cleanup failure");
                        }
                        super.deleteByEmail(email);
                    }
                };
        SignupOtpChallengeService failingDeleteService =
                new SignupOtpChallengeService(
                        failingDeletePersistence, emailSenderService, observability);
        AuthController controller = signupController(failingDeleteService);
        HttpServletRequest httpRequest = httpRequest();

        assertThrows(
                IllegalStateException.class,
                () -> controller.signup(signupRequest(OLD_OTP), httpRequest));

        assertEquals(1, userRepository.count(), "user insertion committed before cleanup failed");
        assertEquals(OtpChallengeStatus.VERIFIED, loadChallenge(EMAIL).getStatus());
        assertInstanceOf(
                SignupOtpChallengeService.VerifyOtpResult.Invalid.class,
                failingDeleteService.verifyOtp(EMAIL, OLD_OTP));

        ResponseEntity<?> retry = controller.signup(signupRequest(OLD_OTP), httpRequest);
        assertEquals(HttpStatus.CONFLICT, retry.getStatusCode());
        assertEquals(1, userRepository.count(), "retry must not create a duplicate user");
        assertEquals(OtpChallengeStatus.VERIFIED, loadChallenge(EMAIL).getStatus());
        assertEquals(0, mailCalls.size());
    }

    @Test
    void idempotencyReplayDoesNotDuplicateEmailOrConsumeResendTwice() throws Exception {
        String initialKey = "initial-idempotency-key";
        List<SignupOtpChallengeService.RequestOtpResult> initialRace = race(
                THREADS,
                index -> service.requestSignupOtp(EMAIL, initialKey));
        SignupOtpChallengeService.RequestOtpResult initialReplay =
                service.requestSignupOtp(EMAIL, initialKey);

        assertTrue(initialRace.stream().allMatch(this::isIdempotentReplayOutcome));
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, initialReplay);
        assertEquals(1, mailCalls.size());
        SignupOtpChallenge afterInitial = loadChallenge(EMAIL);
        assertEquals(0, afterInitial.getResendCount());
        String initialDigest = afterInitial.getOtpDigest();

        clock.set(NOW.plusSeconds(31));
        String resendKey = "resend-idempotency-key";
        List<SignupOtpChallengeService.RequestOtpResult> resendRace = race(
                THREADS,
                index -> service.resendOtp(EMAIL, resendKey));
        SignupOtpChallenge afterResend = loadChallenge(EMAIL);
        SignupOtpChallengeService.RequestOtpResult resendReplay =
                service.resendOtp(EMAIL, resendKey);
        SignupOtpChallenge afterReplay = loadChallenge(EMAIL);

        assertTrue(resendRace.stream().allMatch(this::isIdempotentReplayOutcome));
        assertInstanceOf(SignupOtpChallengeService.RequestOtpResult.Sent.class, resendReplay);
        assertEquals(2, mailCalls.size());
        assertEquals(1, afterResend.getResendCount());
        assertEquals(1, afterReplay.getResendCount());
        assertNotEquals(initialDigest, afterResend.getOtpDigest());
        assertEquals(afterResend.getOtpDigest(), afterReplay.getOtpDigest());
        MailCall lastMail = new ArrayList<>(mailCalls).get(1);
        assertTrue(persistence.otpMatches(lastMail.otp(), afterReplay.getOtpDigest()));
        assertEquals(1, challengeRepository.count());
    }

    private SignupOtpChallenge seedActiveChallenge(
            String email,
            String rawOtp,
            Instant nextResendAt) {
        String digest = persistence.digestOtp(rawOtp);
        SignupOtpChallenge challenge = persistence.buildNewActiveChallenge(
                email,
                digest,
                "seed-" + System.nanoTime(),
                "seed-send-" + System.nanoTime(),
                "seed-key");
        challenge.setNextResendAt(nextResendAt);
        challenge.setSendStatus(OtpSendStatus.SENT);
        challenge.setSendLeaseUntil(null);
        return challengeRepository.save(challenge);
    }

    private SignupOtpChallenge loadChallenge(String email) {
        return challengeRepository.findById(persistence.emailKeyFromRawEmail(email))
                .orElseThrow();
    }

    private boolean isNewlyAcceptedSend(SignupOtpChallengeService.RequestOtpResult result) {
        return result instanceof SignupOtpChallengeService.RequestOtpResult.Sent sent
                && sent.newlyAccepted();
    }

    private boolean isIdempotentReplayOutcome(SignupOtpChallengeService.RequestOtpResult result) {
        return result instanceof SignupOtpChallengeService.RequestOtpResult.Sent
                || result instanceof SignupOtpChallengeService.RequestOtpResult.InFlight;
    }

    private static SignupOtpChallengeService serviceForIndex(
            int index,
            SignupOtpChallengeService first,
            SignupOtpChallengeService second) {
        return index % 2 == 0 ? first : second;
    }

    private AuthController signupController(SignupOtpChallengeService signupService) {
        SignupAbuseLimiter abuseLimiter = mock(SignupAbuseLimiter.class);
        when(abuseLimiter.checkAndConsumeVerification(anyString()))
                .thenReturn(new SignupAbuseLimiter.Decision.Allowed());

        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        when(clientIpResolver.resolve(
                anyString(),
                nullable(String.class),
                nullable(String.class)))
                .thenReturn("203.0.113.10");

        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.generateToken(anyString(), anyString())).thenReturn("test-token");
        when(jwtUtil.generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("test-token");

        return new AuthController(
                emailSenderService,
                userRepository,
                mock(CustomerOrderRepository.class),
                mock(OrderReviewRepository.class),
                jwtUtil,
                mock(OtpService.class),
                signupService,
                abuseLimiter,
                clientIpResolver,
                observability,
                mock(LoginRateLimiter.class),
                mock(PasswordResetService.class),
                mock(AuthSecurityObservability.class));
    }

    private static HttpServletRequest httpRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        return request;
    }

    private static SignupRequest signupRequest(String otp) {
        SignupRequest request = new SignupRequest();
        request.setName("Mongo Race");
        request.setEmail(EMAIL);
        request.setPhone("5551234567");
        request.setPassword("Password1!");
        request.setOtp(otp);
        return request;
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
        }).when(sender).sendOtpEmail(anyString(), anyString(), nullable(String.class));
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

    private record MailCall(String recipient, String otp, String operationId) {
        private MailCall {
            assertNotNull(recipient);
            assertNotNull(otp);
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
