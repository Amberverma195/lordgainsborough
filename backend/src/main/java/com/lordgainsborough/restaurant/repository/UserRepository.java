package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);
    boolean existsByPhoneNormalized(String phoneNormalized);
    List<User> findByPhoneNormalized(String phoneNormalized);
    List<User> findByPhoneIsNotNull();
}
