package com.lordgainsborough.restaurant.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

public class PostgresConfigurationSmokeTest {

    @Test
    @DisplayName("Default application.properties enables PostgreSQL and validates the migrated schema")
    void testDefaultApplicationProperties_enablePostgres() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new ResourcePropertySource(new ClassPathResource("application.properties")));

        assertThat(env.getProperty("spring.autoconfigure.exclude")).isNull();
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("application-postgres-migration.properties un-excludes and defines Hikari and Flyway settings")
    void testMigrationProfileProperties_configuredCorrectly() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Load default first, then migration profile with override
        env.getPropertySources().addLast(new ResourcePropertySource(new ClassPathResource("application.properties")));
        env.getPropertySources().addFirst(new ResourcePropertySource(new ClassPathResource("application-postgres-migration.properties")));

        // 1. Verify exclusion is overridden to empty
        String exclusions = env.getProperty("spring.autoconfigure.exclude");
        assertThat(exclusions).isNull();

        // 2. Verify Flyway properties
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(env.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
        assertThat(env.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");

        // 3. Verify JPA / Hibernate safe defaults (no DDL manipulation)
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(env.getProperty("spring.jpa.database-platform")).isEqualTo("org.hibernate.dialect.PostgreSQLDialect");

        // 4. Verify Hikari pool settings
        assertThat(env.getProperty("spring.datasource.type")).isEqualTo("com.zaxxer.hikari.HikariDataSource");
        assertThat(env.getProperty("spring.datasource.hikari.pool-name")).isEqualTo("LordGainsboroughHikariPool");
        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).contains("10");
        assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).contains("2");
        assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).contains("30000");
        assertThat(env.getProperty("spring.datasource.hikari.idle-timeout")).contains("600000");
        assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
    }

    @Test
    @DisplayName("HikariDataSource connects and queries PostgreSQL when local instance is running")
    void testHikariDataSource_liveConnectivity() {
        String testPort = com.lordgainsborough.restaurant.support.LocalPostgres.requirePort();
        if (testPort == null || testPort.isBlank()) {
            System.out.println("No test.postgres.port supplied; skipping live socket connection check.");
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("LordGainsboroughHikariPool");
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + testPort + "/postgres?sslmode=disable");
        config.setUsername("postgres");
        config.setPassword("postgres");
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            assertThat(dataSource.getPoolName()).isEqualTo("LordGainsboroughHikariPool");
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1;")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                System.out.println("Live HikariCP connectivity to PostgreSQL verified: SELECT 1 returned 1");
            }
        } catch (Exception e) {
            throw new RuntimeException("Live HikariCP connection test failed", e);
        }
    }
}
