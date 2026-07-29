package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PasswordResetOtpChallengeRepository
        extends MongoRepository<PasswordResetOtpChallenge, String> {

    Optional<PasswordResetOtpChallenge> findByChallengeId(String challengeId);
}
