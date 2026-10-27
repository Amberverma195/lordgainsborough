package com.lordgainsborough.restaurant.model;

import java.time.Instant;

public class CatalogMigration {

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
