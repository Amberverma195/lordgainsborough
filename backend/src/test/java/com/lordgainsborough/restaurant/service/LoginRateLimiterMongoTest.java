package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.LoginRateLimitProperties;
import com.lordgainsborough.restaurant.config.SignupAbuseProperties;
import com.lordgainsborough.restaurant.model.LoginFailureState;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.security.AbuseIpHasher;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class LoginRateLimiterMongoTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:10Z");
    private static final String IDENTIFIER = "member@example.com";
    private static final String IP = "203.0.113.10";

    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    private static MongoClient mongoClient;
    private static MongoTemplate mongoTemplate;

    private MutableClock clock;
    private LoginRateLimitProperties properties;
    private LoginRateLimiter limiter;
    private LoginRateLimiter secondLimiter;

    @BeforeAll
    static void connectMongo() {
        mongoClient = MongoClients.create(MONGO.getConnectionString());
        mongoTemplate = new MongoTemplate(mongoClient, "login_rate_limiter_test");
    }

    @AfterAll
    static void closeMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(LoginFailureState.class);
        mongoTemplate.dropCollection(SignupAbuseLimit.class);

        properties = new LoginRateLimitProperties();
        properties.setIpAttempts(3);
        properties.setIpWindow(Duration.ofMinutes(5));
        properties.setIdentifierIpFailures(5);
        properties.setFailureWindow(Duration.ofMinutes(5));
        properties.setCooldown(Duration.ofSeconds(30));

        SignupAbuseProperties hashingProperties = new SignupAbuseProperties();
        hashingProperties.setIpHashSecret("login-rate-limiter-test-secret");
        AbuseIpHasher hasher = new AbuseIpHasher(hashingProperties);
        clock = new MutableClock(NOW);

        limiter = new LoginRateLimiter(
                new MongoAbuseCounterStore(mongoTemplate),
                mongoTemplate,
                properties,
                hasher,
                clock);
        secondLimiter = new LoginRateLimiter(
                new MongoAbuseCounterStore(mongoTemplate),
                mongoTemplate,
                properties,
                hasher,
                clock);
    }

    @Test
    void fixedWindowIpLimitIsSharedAcrossServiceInstances() {
        assertTrue(limiter.consumeIpRequest(IP).isEmpty());
        assertTrue(secondLimiter.consumeIpRequest(IP).isEmpty());
        assertTrue(limiter.consumeIpRequest(IP).isEmpty());

        var denied = secondLimiter.consumeIpRequest(IP);
        assertTrue(denied.isPresent());
        assertEquals(290L, denied.getAsLong());
        assertTrue(limiter.consumeIpRequest("203.0.113.11").isEmpty());
    }

    @Test
    void fifthFailureStartsThirtySecondCooldownButRemainsInvalidCredentials() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            LoginRateLimiter.FailureResult result = limiter.recordFailure(IDENTIFIER, IP);
            assertFalse(result.denied(), "threshold failure #" + attempt);
        }

        var cooldown = limiter.activeCooldown(IDENTIFIER, IP);
        assertTrue(cooldown.isPresent());
        assertEquals(30L, cooldown.getAsLong());

        LoginRateLimiter.FailureResult laterFailure =
                secondLimiter.recordFailure(IDENTIFIER, IP);
        assertTrue(laterFailure.denied());
        assertEquals(30L, laterFailure.retryAfterSeconds());
    }

    @Test
    void cooldownExpiryResetsTheFailureWindow() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.recordFailure(IDENTIFIER, IP);
        }
        clock.set(NOW.plusSeconds(30));

        assertTrue(secondLimiter.activeCooldown(IDENTIFIER, IP).isEmpty());
        LoginRateLimiter.FailureResult firstAfterCooldown =
                secondLimiter.recordFailure(IDENTIFIER, IP);

        assertFalse(firstAfterCooldown.denied());
        LoginFailureState state =
                mongoTemplate.findById(limiter.failureStateId(IDENTIFIER, IP), LoginFailureState.class);
        assertNotNull(state);
        assertEquals(1, state.getFailureCount());
        assertNull(state.getBlockedUntil());
        assertEquals(clock.instant(), state.getWindowStartedAt());
    }

    @Test
    void successfulLoginClearsOnlyTheExactIdentifierIpCombination() {
        limiter.recordFailure(IDENTIFIER, IP);
        limiter.recordFailure(IDENTIFIER, "203.0.113.20");
        limiter.recordFailure("other@example.com", IP);

        limiter.clearFailures(IDENTIFIER, IP);

        assertNull(mongoTemplate.findById(
                limiter.failureStateId(IDENTIFIER, IP), LoginFailureState.class));
        assertNotNull(mongoTemplate.findById(
                limiter.failureStateId(IDENTIFIER, "203.0.113.20"), LoginFailureState.class));
        assertNotNull(mongoTemplate.findById(
                limiter.failureStateId("other@example.com", IP), LoginFailureState.class));
    }

    @Test
    void parallelFailuresAcrossTwoInstancesStopAtOneSharedThreshold() throws Exception {
        int participants = 20;
        List<LoginRateLimiter.FailureResult> results = race(
                participants,
                index -> (index % 2 == 0 ? limiter : secondLimiter)
                        .recordFailure(IDENTIFIER, IP));

        assertEquals(
                5,
                results.stream().filter(result -> !result.denied()).count(),
                "exactly the five threshold failures are admitted");
        assertEquals(
                15,
                results.stream().filter(LoginRateLimiter.FailureResult::denied).count());

        LoginFailureState state =
                mongoTemplate.findById(limiter.failureStateId(IDENTIFIER, IP), LoginFailureState.class);
        assertNotNull(state);
        assertEquals(5, state.getFailureCount());
        assertEquals(NOW.plusSeconds(30), state.getBlockedUntil());
        assertEquals(30L, secondLimiter.activeCooldown(IDENTIFIER, IP).orElseThrow());
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
