package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.MenuItem;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuItemRepository extends MongoRepository<MenuItem, String> {
    List<MenuItem> findByCategory(String category);
    Optional<MenuItem> findFirstByNameIgnoreCase(String name);
}
