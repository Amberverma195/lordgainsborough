package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.OtpChallengeStatus;
import com.lordgainsborough.restaurant.model.OtpSendStatus;
import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignupOtpStatusOwnershipTest {
    final SignupOtpChallengeStore store=mock(SignupOtpChallengeStore.class);
    final Instant now=Instant.parse("2026-11-09T15:00:00Z");
    final SignupOtpChallengePersistence persistence=new SignupOtpChallengePersistence(store,Clock.fixed(now,ZoneOffset.UTC));
    final SignupOtpChallengeService service=new SignupOtpChallengeService(persistence,mock(EmailSenderService.class),mock(SignupOtpObservability.class));
    final String email="applicant@example.com", token="private-owner-attempt-token";
    SignupOtpChallenge challenge;

    @BeforeEach void setup() {
        challenge=new SignupOtpChallenge();challenge.setChallengeId("owned-challenge");challenge.setNormalizedEmail(email);
        challenge.setAttemptTokenHash(persistence.hashAttemptToken(token));challenge.setStatus(OtpChallengeStatus.ACTIVE);
        challenge.setSendStatus(OtpSendStatus.SENT);challenge.setChallengeExpiresAt(now.plusSeconds(1800));
        challenge.setOtpExpiresAt(now.plusSeconds(600));challenge.setNextResendAt(now.plusSeconds(30));
        lenient().when(store.findById(persistence.emailKeyFromRawEmail(email))).thenReturn(Optional.of(challenge));
    }

    @Test void missingAndOversizedTokensAreRejectedBeforeDatabaseLookup() {
        for(String invalid:new String[]{null,""," ","x".repeat(513)})
            assertThat(service.getChallengeStatus(email,"owned-challenge",invalid)).isInstanceOf(SignupOtpChallengeService.ChallengeStatusResult.Missing.class);
        verifyNoInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings={"ACTIVE","LOCKED","EXPIRED","EXHAUSTED","DELIVERY_UNKNOWN","IN_FLIGHT"})
    void everyDetailedStateRequiresPossessionOfTheMatchingToken(String state) {
        Class<?> expected=switch(state) {
            case "LOCKED" -> {challenge.setStatus(OtpChallengeStatus.LOCKED);yield SignupOtpChallengeService.ChallengeStatusResult.Locked.class;}
            case "EXPIRED" -> {challenge.setChallengeExpiresAt(now.minusSeconds(1));yield SignupOtpChallengeService.ChallengeStatusResult.Expired.class;}
            case "EXHAUSTED" -> {challenge.setResendCount(5);yield SignupOtpChallengeService.ChallengeStatusResult.Exhausted.class;}
            case "DELIVERY_UNKNOWN" -> {challenge.setSendStatus(OtpSendStatus.DELIVERY_UNKNOWN);yield SignupOtpChallengeService.ChallengeStatusResult.DeliveryUnknown.class;}
            case "IN_FLIGHT" -> {challenge.setSendStatus(OtpSendStatus.RESERVED);challenge.setSendLeaseUntil(now.plusSeconds(20));yield SignupOtpChallengeService.ChallengeStatusResult.InFlight.class;}
            default -> SignupOtpChallengeService.ChallengeStatusResult.Active.class;
        };
        assertThat(service.getChallengeStatus(email,"owned-challenge","wrong-token"))
                .isInstanceOf(SignupOtpChallengeService.ChallengeStatusResult.Missing.class);
        assertThat(service.getChallengeStatus(email,"owned-challenge",token)).isInstanceOf(expected);
    }

    @Test void challengeIdAndEmailCannotSubstituteForAttemptOwnership() {
        assertThat(service.getChallengeStatus(email,"another-challenge",token)).isInstanceOf(SignupOtpChallengeService.ChallengeStatusResult.Missing.class);
        assertThat(service.getChallengeStatus("other@example.com","owned-challenge",token)).isInstanceOf(SignupOtpChallengeService.ChallengeStatusResult.Missing.class);
        assertThat(service.getChallengeStatus(email,null,token)).isInstanceOf(SignupOtpChallengeService.ChallengeStatusResult.Active.class);
    }

    @Test void legacyUnboundAndConsumedChallengesDoNotExposeMetadata() {
        challenge.setAttemptTokenHash(null);
        assertThat(service.getChallengeStatus(email,"owned-challenge",token)).isInstanceOf(SignupOtpChallengeService.ChallengeStatusResult.Missing.class);
        challenge.setAttemptTokenHash(persistence.hashAttemptToken(token));challenge.setStatus(OtpChallengeStatus.VERIFIED);
        assertThat(service.getChallengeStatus(email,"owned-challenge",token)).isInstanceOf(SignupOtpChallengeService.ChallengeStatusResult.Missing.class);
    }
}
