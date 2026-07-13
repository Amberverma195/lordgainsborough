package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.dto.SignupOtpChallengeView;
import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import com.lordgainsborough.restaurant.service.SignupOtpChallengePersistence.ResendDenialReason;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Signup OTP challenge orchestration backed by MongoDB.
 * Initial send is create-once; resend uses a single atomic findAndModify.
 * Client {@code Idempotency-Key} replays do not rotate OTP or re-call the provider.
 */
@Service
public class SignupOtpChallengeService {

    private final SignupOtpChallengePersistence persistence;
    private final EmailSenderService emailSenderService;
    private final SignupOtpObservability observability;
    private final SecureRandom secureRandom = new SecureRandom();

    public SignupOtpChallengeService(
            SignupOtpChallengePersistence persistence,
            EmailSenderService emailSenderService,
            SignupOtpObservability observability) {
        this.persistence = persistence;
        this.emailSenderService = emailSenderService;
        this.observability = observability;
    }

    public enum InitialSendResult {
        CREATED,
        ALREADY_EXISTS,
        SEND_FAILED,
        DELIVERY_UNKNOWN,
        IN_FLIGHT
    }

    public sealed interface RequestOtpResult {
        record Sent(boolean initial, boolean newlyAccepted, SignupOtpChallengeView challenge)
                implements RequestOtpResult {}
        record Denied(ResendDenialReason reason, long retryAfterSeconds, String message,
                      SignupOtpChallengeView challenge) implements RequestOtpResult {}
        record Failed(SignupOtpChallengeView challenge) implements RequestOtpResult {}
        record DeliveryUnknown(SignupOtpChallengeView challenge) implements RequestOtpResult {}
        record InFlight(long retryAfterSeconds, SignupOtpChallengeView challenge) implements RequestOtpResult {}
    }

    public sealed interface VerifyOtpResult {
        record Verified(SignupOtpChallengeView challenge) implements VerifyOtpResult {}
        record Invalid(SignupOtpChallengeView challenge) implements VerifyOtpResult {}
        record OtpExpired(SignupOtpChallengeView challenge) implements VerifyOtpResult {}
        record ChallengeExpired(SignupOtpChallengeView challenge) implements VerifyOtpResult {}
        record Locked(SignupOtpChallengeView challenge) implements VerifyOtpResult {}
        record Missing() implements VerifyOtpResult {}
    }

    /**
     * Initial create-once send, or atomic resend when an ACTIVE challenge already exists.
     *
     * @param idempotencyKey client {@code Idempotency-Key}; blank values are treated as a unique key
     */
    public RequestOtpResult requestSignupOtp(String email, String idempotencyKey) {
        String normalizedEmail = persistence.normalizeEmail(email);
        if (normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String key = normalizeIdempotencyKey(idempotencyKey);

        Optional<RequestOtpResult> replay = replayIdempotentResult(normalizedEmail, key);
        if (replay.isPresent()) {
            return replay.get();
        }

        if (persistence.findActiveUnexpiredByEmail(normalizedEmail).isEmpty()) {
            return switch (sendInitialOtp(normalizedEmail, key)) {
                case CREATED -> sentResult(normalizedEmail, true, true);
                case ALREADY_EXISTS -> resendOtp(normalizedEmail, key);
                case SEND_FAILED -> new RequestOtpResult.Failed(challengeViewForEmail(normalizedEmail));
                case DELIVERY_UNKNOWN ->
                        new RequestOtpResult.DeliveryUnknown(challengeViewForEmail(normalizedEmail));
                case IN_FLIGHT -> new RequestOtpResult.InFlight(
                        SignupOtpChallengePersistence.SEND_LEASE.getSeconds(),
                        challengeViewForEmail(normalizedEmail));
            };
        }

        return resendOtp(normalizedEmail, key);
    }

    /** @deprecated Prefer {@link #requestSignupOtp(String, String)}. */
    public RequestOtpResult requestSignupOtp(String email) {
        return requestSignupOtp(email, UUID.randomUUID().toString());
    }

    public InitialSendResult sendInitialOtp(String email, String idempotencyKey) {
        String normalizedEmail = persistence.normalizeEmail(email);
        if (normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String key = normalizeIdempotencyKey(idempotencyKey);

        Optional<RequestOtpResult> replay = replayIdempotentResult(normalizedEmail, key);
        if (replay.isPresent()) {
            return switch (replay.get()) {
                case RequestOtpResult.Sent s -> InitialSendResult.CREATED;
                case RequestOtpResult.Failed f -> InitialSendResult.SEND_FAILED;
                case RequestOtpResult.DeliveryUnknown d -> InitialSendResult.DELIVERY_UNKNOWN;
                case RequestOtpResult.InFlight i -> InitialSendResult.IN_FLIGHT;
                case RequestOtpResult.Denied denied -> InitialSendResult.ALREADY_EXISTS;
            };
        }

        if (persistence.findActiveUnexpiredByEmail(normalizedEmail).isPresent()) {
            return InitialSendResult.ALREADY_EXISTS;
        }

        String rawOtp = generateSixDigitOtp();
        String otpDigest = persistence.digestOtp(rawOtp);
        String challengeId = UUID.randomUUID().toString();
        String sendOperationId = UUID.randomUUID().toString();
        SignupOtpChallenge challenge = persistence.buildNewActiveChallenge(
                normalizedEmail, otpDigest, challengeId, sendOperationId, key);

        if (!persistence.tryInsertOrReplaceExpired(challenge)) {
            // Another request may have just created the challenge with this same key.
            Optional<RequestOtpResult> raced = replayIdempotentResult(normalizedEmail, key);
            if (raced.isPresent()) {
                return switch (raced.get()) {
                    case RequestOtpResult.Sent s -> InitialSendResult.CREATED;
                    case RequestOtpResult.Failed f -> InitialSendResult.SEND_FAILED;
                    case RequestOtpResult.DeliveryUnknown d -> InitialSendResult.DELIVERY_UNKNOWN;
                    case RequestOtpResult.InFlight i -> InitialSendResult.IN_FLIGHT;
                    case RequestOtpResult.Denied denied -> InitialSendResult.ALREADY_EXISTS;
                };
            }
            return InitialSendResult.ALREADY_EXISTS;
        }

        return finalizeProviderCall(
                normalizedEmail,
                rawOtp,
                challenge.getEmailKey(),
                sendOperationId,
                true);
    }

    /** @deprecated Prefer {@link #sendInitialOtp(String, String)}. */
    public InitialSendResult sendInitialOtp(String email) {
        return sendInitialOtp(email, UUID.randomUUID().toString());
    }

    public RequestOtpResult resendOtp(String email, String idempotencyKey) {
        String normalizedEmail = persistence.normalizeEmail(email);
        String emailKey = persistence.emailKeyFromNormalized(normalizedEmail);
        String key = normalizeIdempotencyKey(idempotencyKey);
        Instant now = persistence.now();

        Optional<RequestOtpResult> replay = replayIdempotentResult(normalizedEmail, key);
        if (replay.isPresent()) {
            return replay.get();
        }

        String rawOtp = generateSixDigitOtp();
        String otpDigest = persistence.digestOtp(rawOtp);
        String sendOperationId = UUID.randomUUID().toString();

        var updated = persistence.tryAtomicResend(emailKey, otpDigest, sendOperationId, key, now);
        if (updated.isPresent()) {
            return toRequestResult(
                    finalizeProviderCall(normalizedEmail, rawOtp, emailKey, sendOperationId, false),
                    normalizedEmail);
        }

        // Lost the atomic race — another key may have reserved; check replay once more.
        Optional<RequestOtpResult> raced = replayIdempotentResult(normalizedEmail, key);
        if (raced.isPresent()) {
            return raced.get();
        }

        ResendDenialReason reason = persistence.diagnoseResendDenial(emailKey, now);
        return toDenied(reason, emailKey, now);
    }

    /** @deprecated Prefer {@link #resendOtp(String, String)}. */
    public RequestOtpResult resendOtp(String email) {
        return resendOtp(email, UUID.randomUUID().toString());
    }

    public VerifyOtpResult verifyOtp(String email, String rawOtp) {
        String normalizedEmail = persistence.normalizeEmail(email);
        if (normalizedEmail.isBlank() || rawOtp == null || rawOtp.isBlank()) {
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_FAILURE,
                    SignupOtpObservability.Context.forEmailKey(null, "OTP_INVALID"));
            return new VerifyOtpResult.Missing();
        }

        Instant now = persistence.now();
        String emailKey = persistence.emailKeyFromNormalized(normalizedEmail);
        Optional<SignupOtpChallenge> found = persistence.findByEmailKey(emailKey);
        if (found.isEmpty()) {
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_FAILURE,
                    SignupOtpObservability.Context.forEmailKey(emailKey, "OTP_INVALID"));
            return new VerifyOtpResult.Missing();
        }

        SignupOtpChallenge challenge = found.get();
        SignupOtpChallengeView view = toChallengeView(challenge, now);

        if (challenge.getStatus() == OtpChallengeStatus.LOCKED) {
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_LOCK,
                    SignupOtpObservability.Context.fromChallenge(
                            challenge, "OTP_VERIFICATION_LOCKED", null));
            return new VerifyOtpResult.Locked(view);
        }
        if (challenge.getStatus() == OtpChallengeStatus.VERIFIED) {
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_FAILURE,
                    SignupOtpObservability.Context.fromChallenge(
                            challenge, "OTP_INVALID", null));
            return new VerifyOtpResult.Invalid(view);
        }
        if (challenge.getStatus() != OtpChallengeStatus.ACTIVE) {
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_FAILURE,
                    SignupOtpObservability.Context.fromChallenge(
                            challenge, "OTP_INVALID", null));
            return new VerifyOtpResult.Invalid(view);
        }
        if (persistence.isChallengeExpired(challenge, now)) {
            observability.record(
                    SignupOtpObservability.Event.CHALLENGE_EXPIRED,
                    SignupOtpObservability.Context.fromChallenge(
                            challenge, "OTP_CHALLENGE_EXPIRED", null));
            return new VerifyOtpResult.ChallengeExpired(view);
        }
        if (persistence.isOtpExpired(challenge, now)) {
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_FAILURE,
                    SignupOtpObservability.Context.fromChallenge(
                            challenge, "OTP_EXPIRED", null));
            return new VerifyOtpResult.OtpExpired(view);
        }

        if (!persistence.otpMatches(rawOtp, challenge.getOtpDigest())) {
            var updated = persistence.tryAtomicRecordFailedVerify(emailKey, now);
            if (updated.isPresent()
                    && updated.get().getFailedVerifyAttempts()
                    >= SignupOtpChallengePersistence.MAX_FAILED_VERIFY_ATTEMPTS) {
                SignupOtpChallenge locked = persistence.tryAtomicLockAfterMaxFailures(emailKey, now)
                        .orElse(updated.get());
                observability.record(
                        SignupOtpObservability.Event.VERIFICATION_FAILURE,
                        SignupOtpObservability.Context.fromChallenge(
                                locked, "OTP_INVALID", null));
                observability.record(
                        SignupOtpObservability.Event.VERIFICATION_LOCK,
                        SignupOtpObservability.Context.fromChallenge(
                                locked, "OTP_VERIFICATION_LOCKED", null));
                return new VerifyOtpResult.Locked(challengeViewForEmail(normalizedEmail));
            }
            SignupOtpChallenge failedState = updated
                    .orElseGet(() -> persistence.findByEmailKey(emailKey).orElse(challenge));
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_FAILURE,
                    SignupOtpObservability.Context.fromChallenge(
                            failedState, "OTP_INVALID", null));
            return new VerifyOtpResult.Invalid(challengeViewForEmail(normalizedEmail));
        }

        Optional<SignupOtpChallenge> verified =
                persistence.tryAtomicMarkVerified(emailKey, challenge.getOtpDigest(), now);
        if (verified.isEmpty()) {
            observability.record(
                    SignupOtpObservability.Event.VERIFICATION_FAILURE,
                    SignupOtpObservability.Context.fromChallenge(
                            persistence.findByEmailKey(emailKey).orElse(challenge),
                            "OTP_INVALID",
                            null));
            return new VerifyOtpResult.Invalid(challengeViewForEmail(normalizedEmail));
        }
        observability.record(
                SignupOtpObservability.Event.VERIFICATION_SUCCESS,
                SignupOtpObservability.Context.fromChallenge(
                        verified.get(), "OTP_VERIFIED", null));
        return new VerifyOtpResult.Verified(challengeViewForEmail(normalizedEmail));
    }

    /**
     * @deprecated Prefer {@link #verifyOtp(String, String)}.
     */
    public boolean verifyAndConsume(String email, String rawOtp) {
        return verifyOtp(email, rawOtp) instanceof VerifyOtpResult.Verified;
    }

    public void clear(String email) {
        persistence.deleteByEmail(email);
    }

    /**
     * True when there is no usable active challenge for the email — the next send
     * will create/replace as an initial OTP send for abuse accounting.
     */
    public boolean looksLikeInitialSend(String email) {
        return persistence.findActiveUnexpiredByEmail(email).isEmpty();
    }

    /**
     * Public challenge status for verification UI sync. Does not reveal account existence
     * beyond whether a matching challenge document is present for the email (+ optional id).
     */
    public ChallengeStatusResult getChallengeStatus(String email, String challengeId) {
        String normalizedEmail = persistence.normalizeEmail(email);
        if (normalizedEmail.isBlank()) {
            return new ChallengeStatusResult.Missing();
        }

        Instant now = persistence.now();
        String emailKey = persistence.emailKeyFromNormalized(normalizedEmail);
        Optional<SignupOtpChallenge> found = persistence.findByEmailKey(emailKey);
        if (found.isEmpty()) {
            return new ChallengeStatusResult.Missing();
        }

        SignupOtpChallenge challenge = found.get();
        if (challengeId != null
                && !challengeId.isBlank()
                && challenge.getChallengeId() != null
                && !challenge.getChallengeId().equals(challengeId)) {
            return new ChallengeStatusResult.Missing();
        }

        SignupOtpChallengeView view = toChallengeView(challenge, now);

        if (challenge.getStatus() == OtpChallengeStatus.LOCKED) {
            return new ChallengeStatusResult.Locked(view);
        }
        if (challenge.getStatus() == OtpChallengeStatus.VERIFIED) {
            return new ChallengeStatusResult.Missing();
        }
        if (persistence.isChallengeExpired(challenge, now)) {
            return new ChallengeStatusResult.Expired(view);
        }
        if (challenge.getResendCount() >= SignupOtpChallengePersistence.MAX_RESENDS) {
            return new ChallengeStatusResult.Exhausted(view);
        }
        if (challenge.getSendStatus() == OtpSendStatus.DELIVERY_UNKNOWN
                || challenge.getSendStatus() == OtpSendStatus.DEFINITELY_FAILED) {
            return new ChallengeStatusResult.DeliveryUnknown(view);
        }
        if (challenge.getSendStatus() == OtpSendStatus.RESERVED
                && challenge.getSendLeaseUntil() != null
                && challenge.getSendLeaseUntil().isAfter(now)) {
            long retryAfter = Math.max(1,
                    Duration.between(now, challenge.getSendLeaseUntil()).getSeconds());
            return new ChallengeStatusResult.InFlight(retryAfter, view);
        }
        return new ChallengeStatusResult.Active(view);
    }

    public sealed interface ChallengeStatusResult {
        record Active(SignupOtpChallengeView challenge) implements ChallengeStatusResult {}
        record Exhausted(SignupOtpChallengeView challenge) implements ChallengeStatusResult {}
        record Expired(SignupOtpChallengeView challenge) implements ChallengeStatusResult {}
        record Locked(SignupOtpChallengeView challenge) implements ChallengeStatusResult {}
        record DeliveryUnknown(SignupOtpChallengeView challenge) implements ChallengeStatusResult {}
        record InFlight(long retryAfterSeconds, SignupOtpChallengeView challenge)
                implements ChallengeStatusResult {}
        record Missing() implements ChallengeStatusResult {}
    }

    private InitialSendResult finalizeProviderCall(
            String normalizedEmail,
            String rawOtp,
            String emailKey,
            String sendOperationId,
            boolean initial) {
        Instant now = persistence.now();
        try {
            emailSenderService.sendOtpEmail(normalizedEmail, rawOtp, sendOperationId);
            SignupOtpChallenge sent = persistence.tryMarkSendOutcome(
                            emailKey, sendOperationId, OtpSendStatus.SENT, now)
                    .orElseGet(() -> persistence.findByEmailKey(emailKey).orElse(null));
            observability.record(
                    initial
                            ? SignupOtpObservability.Event.INITIAL_SEND_ACCEPTED
                            : SignupOtpObservability.Event.RESEND_ACCEPTED,
                    SignupOtpObservability.Context.fromChallenge(
                            sent,
                            "OTP_SENT",
                            SignupOtpObservability.ProviderCategory.ACCEPTED));
            return InitialSendResult.CREATED;
        } catch (DefiniteEmailRejectionException ex) {
            SignupOtpChallenge failed = persistence.tryMarkSendOutcome(
                            emailKey, sendOperationId, OtpSendStatus.DEFINITELY_FAILED, now)
                    .orElseGet(() -> persistence.findByEmailKey(emailKey).orElse(null));
            if (initial) {
                persistence.expireAfterDefiniteInitialFailure(emailKey, sendOperationId, now);
            }
            observability.record(
                    SignupOtpObservability.Event.PROVIDER_DEFINITE_FAILURE,
                    SignupOtpObservability.Context.fromChallenge(
                            failed,
                            "OTP_PROVIDER_DEFINITE_FAILURE",
                            SignupOtpObservability.ProviderCategory.DEFINITE_FAILURE));
            return InitialSendResult.SEND_FAILED;
        } catch (AmbiguousEmailDeliveryException ex) {
            SignupOtpChallenge unknown = persistence.tryMarkSendOutcome(
                            emailKey, sendOperationId, OtpSendStatus.DELIVERY_UNKNOWN, now)
                    .orElseGet(() -> persistence.findByEmailKey(emailKey).orElse(null));
            observability.record(
                    SignupOtpObservability.Event.PROVIDER_TIMEOUT,
                    SignupOtpObservability.Context.fromChallenge(
                            unknown,
                            "OTP_DELIVERY_UNKNOWN",
                            SignupOtpObservability.ProviderCategory.TIMEOUT_OR_AMBIGUOUS));
            return InitialSendResult.DELIVERY_UNKNOWN;
        } catch (RuntimeException ex) {
            SignupOtpChallenge unknown = persistence.tryMarkSendOutcome(
                            emailKey, sendOperationId, OtpSendStatus.DELIVERY_UNKNOWN, now)
                    .orElseGet(() -> persistence.findByEmailKey(emailKey).orElse(null));
            observability.record(
                    SignupOtpObservability.Event.PROVIDER_TIMEOUT,
                    SignupOtpObservability.Context.fromChallenge(
                            unknown,
                            "OTP_DELIVERY_UNKNOWN",
                            SignupOtpObservability.ProviderCategory.TIMEOUT_OR_AMBIGUOUS));
            return InitialSendResult.DELIVERY_UNKNOWN;
        }
    }

    private RequestOtpResult toRequestResult(InitialSendResult result, String normalizedEmail) {
        SignupOtpChallengeView view = challengeViewForEmail(normalizedEmail);
        return switch (result) {
            case CREATED -> new RequestOtpResult.Sent(false, true, view);
            case ALREADY_EXISTS -> toDenied(
                    ResendDenialReason.COOLDOWN_ACTIVE,
                    persistence.emailKeyFromNormalized(normalizedEmail),
                    persistence.now());
            case SEND_FAILED -> new RequestOtpResult.Failed(view);
            case DELIVERY_UNKNOWN -> new RequestOtpResult.DeliveryUnknown(view);
            case IN_FLIGHT -> new RequestOtpResult.InFlight(
                    SignupOtpChallengePersistence.SEND_LEASE.getSeconds(), view);
        };
    }

    private RequestOtpResult sentResult(String normalizedEmail, boolean initial, boolean newlyAccepted) {
        return new RequestOtpResult.Sent(initial, newlyAccepted, challengeViewForEmail(normalizedEmail));
    }

    private Optional<RequestOtpResult> replayIdempotentResult(String normalizedEmail, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String emailKey = persistence.emailKeyFromNormalized(normalizedEmail);
        Optional<SignupOtpChallenge> found = persistence.findByEmailKey(emailKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        SignupOtpChallenge challenge = found.get();
        if (challenge.getClientIdempotencyKey() == null
                || !challenge.getClientIdempotencyKey().equals(idempotencyKey)) {
            return Optional.empty();
        }

        Instant now = persistence.now();
        OtpSendStatus sendStatus = challenge.getSendStatus();
        if (sendStatus == null) {
            return Optional.empty();
        }

        SignupOtpChallengeView view = toChallengeView(challenge, now);
        Optional<RequestOtpResult> result = switch (sendStatus) {
            case SENT -> Optional.of(new RequestOtpResult.Sent(
                    challenge.isLastSendWasInitial(), false, view));
            case DEFINITELY_FAILED -> Optional.of(new RequestOtpResult.Failed(view));
            case DELIVERY_UNKNOWN -> Optional.of(new RequestOtpResult.DeliveryUnknown(view));
            case RESERVED -> {
                if (challenge.getSendLeaseUntil() != null && challenge.getSendLeaseUntil().isAfter(now)) {
                    long retryAfter = Math.max(1,
                            Duration.between(now, challenge.getSendLeaseUntil()).getSeconds());
                    yield Optional.of(new RequestOtpResult.InFlight(retryAfter, view));
                }
                persistence.tryMarkSendOutcome(
                        emailKey, challenge.getSendOperationId(), OtpSendStatus.DELIVERY_UNKNOWN, now);
                yield Optional.of(new RequestOtpResult.DeliveryUnknown(
                        challengeViewForEmail(normalizedEmail)));
            }
        };
        result.ifPresent(ignored -> observability.record(
                SignupOtpObservability.Event.IDEMPOTENCY_REPLAY,
                SignupOtpObservability.Context.fromChallenge(
                        challenge,
                        "IDEMPOTENCY_REPLAY",
                        providerCategory(sendStatus))));
        return result;
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return idempotencyKey.trim();
    }

    private RequestOtpResult.Denied toDenied(ResendDenialReason reason, String emailKey, Instant now) {
        SignupOtpChallenge challenge = persistence.findByEmailKey(emailKey).orElse(null);
        SignupOtpChallengeView view = challenge == null ? null : toChallengeView(challenge, now);
        SignupOtpObservability.Event event = switch (reason) {
            case COOLDOWN_ACTIVE -> SignupOtpObservability.Event.COOLDOWN_REJECTED;
            case RESEND_LIMIT_REACHED -> SignupOtpObservability.Event.RESEND_LIMIT_REACHED;
            case CHALLENGE_EXPIRED -> SignupOtpObservability.Event.CHALLENGE_EXPIRED;
            case CHALLENGE_LOCKED, CHALLENGE_MISSING -> null;
        };
        if (event != null) {
            observability.record(
                    event,
                    SignupOtpObservability.Context.fromChallenge(
                            challenge, reason.name(), null));
        }
        return switch (reason) {
            case COOLDOWN_ACTIVE -> {
                long retryAfter = persistence.findByEmailKey(emailKey)
                        .map(SignupOtpChallenge::getNextResendAt)
                        .filter(at -> at != null && at.isAfter(now))
                        .map(at -> Math.max(1, Duration.between(now, at).getSeconds()))
                        .orElse(SignupOtpChallengePersistence.RESEND_COOLDOWN.getSeconds());
                yield new RequestOtpResult.Denied(
                        reason,
                        retryAfter,
                        "Please wait before requesting another code.",
                        view);
            }
            case RESEND_LIMIT_REACHED -> new RequestOtpResult.Denied(
                    reason,
                    0,
                    "No more verification codes can be sent for this signup attempt.",
                    view);
            case CHALLENGE_EXPIRED -> new RequestOtpResult.Denied(
                    reason,
                    0,
                    "This verification session has expired. Please start again.",
                    view);
            case CHALLENGE_LOCKED -> new RequestOtpResult.Denied(
                    reason,
                    0,
                    "Verification is locked for this signup attempt. Please start again.",
                    view);
            case CHALLENGE_MISSING -> new RequestOtpResult.Denied(
                    reason,
                    0,
                    "Unable to continue verification. Please start again.",
                    view);
        };
    }

    private static SignupOtpObservability.ProviderCategory providerCategory(
            OtpSendStatus sendStatus) {
        return switch (sendStatus) {
            case SENT -> SignupOtpObservability.ProviderCategory.ACCEPTED;
            case DEFINITELY_FAILED -> SignupOtpObservability.ProviderCategory.DEFINITE_FAILURE;
            case DELIVERY_UNKNOWN, RESERVED ->
                    SignupOtpObservability.ProviderCategory.TIMEOUT_OR_AMBIGUOUS;
        };
    }

    private SignupOtpChallengeView challengeViewForEmail(String email) {
        Instant now = persistence.now();
        return persistence.findByEmailKey(persistence.emailKeyFromNormalized(persistence.normalizeEmail(email)))
                .map(c -> toChallengeView(c, now))
                .orElse(null);
    }

    private SignupOtpChallengeView toChallengeView(SignupOtpChallenge challenge, Instant now) {
        if (challenge == null) {
            return null;
        }
        int remaining = Math.max(
                0,
                SignupOtpChallengePersistence.MAX_RESENDS - challenge.getResendCount());
        return new SignupOtpChallengeView(
                challenge.getChallengeId(),
                now,
                challenge.getNextResendAt(),
                challenge.getOtpExpiresAt(),
                challenge.getChallengeExpiresAt(),
                remaining);
    }

    private String generateSixDigitOtp() {
        return String.valueOf(100000 + secureRandom.nextInt(900000));
    }
}
