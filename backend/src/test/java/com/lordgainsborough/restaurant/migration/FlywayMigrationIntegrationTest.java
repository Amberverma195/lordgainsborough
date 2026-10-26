package com.lordgainsborough.restaurant.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FlywayMigrationIntegrationTest {

    private static final Set<String> EXPECTED_APPLICATION_TABLES = Set.of(
            "users",
            "categories",
            "menu_items",
            "catalog_migrations",
            "store_settings",
            "customer_orders",
            "order_items",
            "order_messages",
            "carts",
            "cart_items",
            "order_reviews",
            "order_review_items",
            "reservations",
            "signup_otp_challenges",
            "password_reset_otp_challenges",
            "signup_abuse_limits",
            "login_failure_states"
    );

    private String getTestPort() {
        return System.getProperty("test.postgres.port");
    }

    private String getJdbcUrl(String dbName) {
        String port = getTestPort();
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + dbName + "?sslmode=disable";
    }

    private void ensureDatabaseExists(String dbName) throws Exception {
        String port = getTestPort();
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'");
            if (!rs.next()) {
                stmt.executeUpdate("CREATE DATABASE " + dbName);
            }
        }
    }

    private void ensureFreshDatabase(String dbName) throws Exception {
        String port = getTestPort();
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid()");
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + dbName);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Direct Flyway API: V1 migration executes cleanly and records history with all 17 tables")
    void test1_directFlyway_initialMigration_executesAndPopulatesSchemaHistoryAnd17Tables() throws Exception {
        String port = getTestPort();
        if (port == null || port.isBlank()) {
            System.out.println("No test.postgres.port provided; skipping test1.");
            return;
        }

        String dbName = "flyway_direct_test";
        ensureFreshDatabase(dbName);
        String jdbcUrl = getJdbcUrl(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();

        // 1. Execute initial migration
        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.targetSchemaVersion).isEqualTo("1");
        assertThat(result.migrations).hasSize(1);

        MigrateOutput output = result.migrations.get(0);
        assertThat(output.version).isEqualTo("1");
        assertThat(output.description).isEqualTo("init relational schema");
        assertThat(output.type).isEqualTo("SQL");
        assertThat(output.filepath).contains("V1__init_relational_schema.sql");
        assertThat(output.executionTime).isGreaterThan(0);

        // 2. Query and verify flyway_schema_history table
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT installed_rank, version, description, type, script, checksum, installed_by, success " +
                    "FROM flyway_schema_history")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("installed_rank")).isEqualTo(1);
                assertThat(rs.getString("version")).isEqualTo("1");
                assertThat(rs.getString("description")).isEqualTo("init relational schema");
                assertThat(rs.getString("type")).isEqualTo("SQL");
                assertThat(rs.getString("script")).isEqualTo("V1__init_relational_schema.sql");
                assertThat(rs.getInt("checksum")).isNotZero();
                assertThat(rs.getString("installed_by")).isNotBlank();
                assertThat(rs.getBoolean("success")).isTrue();
                assertThat(rs.next()).isFalse(); // Exactly one row
            }

            // 3. Verify exactly 17 application tables in public schema
            List<String> actualTables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name != 'flyway_schema_history' " +
                    "ORDER BY table_name")) {
                while (rs.next()) {
                    actualTables.add(rs.getString("table_name"));
                }
            }

            assertThat(actualTables).hasSize(17);
            assertThat(new TreeSet<>(actualTables)).isEqualTo(new TreeSet<>(EXPECTED_APPLICATION_TABLES));

            // 4. Verify total tables in public schema is 18 (17 app tables + flyway_schema_history)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(18);
            }

            // 5. Verify constraints (foreign keys and checks exist)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT constraint_name FROM information_schema.table_constraints " +
                    "WHERE table_schema = 'public' AND constraint_type = 'FOREIGN KEY'")) {
                List<String> fkList = new ArrayList<>();
                while (rs.next()) {
                    fkList.add(rs.getString("constraint_name"));
                }
                assertThat(fkList).contains(
                        "fk_order_items_order",
                        "fk_order_messages_order",
                        "fk_cart_items_cart",
                        "fk_order_reviews_order",
                        "fk_order_review_items_review",
                        "fk_pwd_reset_user"
                );
            }

            // 6. Verify unique lower-case email index exists
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'uq_users_email_lower'")) {
                assertThat(rs.next()).isTrue();
            }

            // 7. Verify index count across application tables (55 custom + constraints)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename != 'flyway_schema_history'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(55);
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Direct Flyway API: Idempotency check executes zero migrations on second run")
    void test2_directFlyway_idempotency_secondRunAppliesZeroMigrations() throws Exception {
        String port = getTestPort();
        if (port == null || port.isBlank()) {
            System.out.println("No test.postgres.port provided; skipping test2.");
            return;
        }

        String dbName = "flyway_direct_test";
        String jdbcUrl = getJdbcUrl(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();

        // Second migration run against already migrated database
        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(0);
        assertThat(result.migrations).isEmpty();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().current().getState().isApplied()).isTrue();

        // Verify flyway_schema_history still has exactly 1 row
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Direct Flyway API: Validation succeeds with zero errors and matching checksum")
    void test3_directFlyway_validate_validationSuccessful() {
        String port = getTestPort();
        if (port == null || port.isBlank()) {
            System.out.println("No test.postgres.port provided; skipping test3.");
            return;
        }

        String dbName = "flyway_direct_test";
        String jdbcUrl = getJdbcUrl(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "postgres", "postgres")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();

        ValidateResult valResult = flyway.validateWithResult();

        assertThat(valResult.validationSuccessful).isTrue();
        assertThat(valResult.invalidMigrations).isEmpty();
        assertThat(valResult.validateCount).isEqualTo(1);
        assertThat(valResult.errorDetails).isNull();
    }

    @Test
    @Order(4)
    @DisplayName("Spring Boot Auto-Configuration: Minimal context boots DataSource, HikariCP, and Flyway cleanly")
    void test4_springBootAutoConfiguration_minimalContext_bootsAndMigrates() throws Exception {
        String port = getTestPort();
        if (port == null || port.isBlank()) {
            System.out.println("No test.postgres.port provided; skipping test4.");
            return;
        }

        String dbName = "flyway_spring_test";
        ensureDatabaseExists(dbName);
        String jdbcUrl = getJdbcUrl(dbName);

        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.username=postgres",
                        "spring.datasource.password=postgres",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
                        "spring.datasource.hikari.pool-name=LordGainsboroughHikariPool",
                        "spring.datasource.hikari.maximum-pool-size=10",
                        "spring.datasource.hikari.minimum-idle=2",
                        "spring.datasource.hikari.connection-timeout=30000",
                        "spring.datasource.hikari.idle-timeout=600000",
                        "spring.flyway.enabled=true",
                        "spring.flyway.locations=classpath:db/migration",
                        "spring.flyway.baseline-on-migrate=false",
                        "spring.flyway.clean-disabled=true"
                );

        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // 1. Verify DataSource bean
            assertThat(context).hasSingleBean(DataSource.class);
            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikariDs = (HikariDataSource) dataSource;
            assertThat(hikariDs.getPoolName()).isEqualTo("LordGainsboroughHikariPool");

            // 2. Verify Flyway bean and migration execution on startup
            assertThat(context).hasSingleBean(Flyway.class);
            Flyway flyway = context.getBean(Flyway.class);
            assertThat(flyway.info().current()).isNotNull();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
            assertThat(flyway.info().current().getState().isApplied()).isTrue();

            // 3. Verify database state produced by Spring Boot Flyway auto-configuration
            try (Connection conn = hikariDs.getConnection();
                 Statement stmt = conn.createStatement()) {

                // Schema history verification
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT version, success FROM flyway_schema_history WHERE version = '1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean("success")).isTrue();
                }

                // Table count verification (17 application tables)
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name != 'flyway_schema_history'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(17);
                }
            }
        });
    }
}
