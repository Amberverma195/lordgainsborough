package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.SignupOtpChallenge;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SignupOtpChallengeRepository extends MongoRepository<SignupOtpChallenge, String> {
}
