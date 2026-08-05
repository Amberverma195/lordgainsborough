package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.CatalogMigration;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CatalogMigrationRepository extends MongoRepository<CatalogMigration, String> {
}
