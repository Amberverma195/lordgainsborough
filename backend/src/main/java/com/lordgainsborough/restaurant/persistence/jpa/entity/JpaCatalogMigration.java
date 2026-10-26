package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "catalog_migrations")
public class JpaCatalogMigration {

    @Id
    @Column(name = "id", length = 128, nullable = false)
    private String id;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt = Instant.now();

    public JpaCatalogMigration() {}

    public JpaCatalogMigration(String id, Instant appliedAt) {
        this.id = id;
        this.appliedAt = appliedAt != null ? appliedAt : Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaCatalogMigration that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
