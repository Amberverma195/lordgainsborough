package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SignupAbuseLimitRepository extends MongoRepository<SignupAbuseLimit, String> {
}
