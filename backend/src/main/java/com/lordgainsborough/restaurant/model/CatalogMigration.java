package com.lordgainsborough.restaurant.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "catalogMigrations")
public class CatalogMigration {
    @Id
    private String id;
    private Instant appliedAt;

    public CatalogMigration() {}

    public CatalogMigration(String id, Instant appliedAt) {
        this.id = id;
        this.appliedAt = appliedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }
}
