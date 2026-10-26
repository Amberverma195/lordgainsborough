package com.lordgainsborough.restaurant.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FlywayMigrationIntegrationTest {

    private static final String TEST_DB_PREFIX = "flyway_test_";

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

    private static DisposableDatabaseSession directDatabaseSession;
    private static String directDbName;

    private static DisposableDatabaseSession springDatabaseSession;
    private static String springDbName;

    private String getTestPort() {
        return System.getProperty("test.postgres.port");
    }

    private String getRequiredTestPort(String testName) {
        String port = getTestPort();
        boolean mandatory = Boolean.getBoolean("test.postgres.mandatory")
                || "true".equalsIgnoreCase(System.getenv("CI_POSTGRES_MANDATORY"))
                || "true".equalsIgnoreCase(System.getenv("CI"));
        if (mandatory) {
            assertThat(port)
                    .withFailMessage("test.postgres.port system property is required for PG-003 Flyway integration tests in mandatory CI mode (" + testName + ")")
                    .isNotBlank();
        } else {
            assumeTrue(
                    port != null && !port.isBlank(),
                    "test.postgres.port system property is required for " + testName + "; skipping because PostgreSQL database is unavailable"
            );
        }
        return port;
    }

    private String getJdbcUrl(String dbName) {
        String port = getTestPort();
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + dbName + "?sslmode=disable";
    }

    static void assertSafeTestEnvironment(String port, String dbName) {
        assertThat(port)
                .withFailMessage("Test PostgreSQL port must not be blank")
                .isNotBlank();
        assertThat(dbName)
                .withFailMessage("Test database name must start with expected prefix: " + TEST_DB_PREFIX)
                .startsWith(TEST_DB_PREFIX);
        assertThat(dbName)
                .withFailMessage("Test database name must match expected identifier pattern")
                .matches("^[a-z0-9_]{5,63}$");
        assertThat(dbName)
                .withFailMessage("Refusing to operate on system/production database")
                .isNotIn("postgres", "template0", "template1", "public", "lordgainsborough", "restaurant");
    }

    static class DisposableDatabaseSession {
        private final String port;
        private final String dbName;
        private boolean createdAndOwned = false;

        DisposableDatabaseSession(String port, String dbName) {
            this.port = port;
            this.dbName = dbName;
        }

        void provision() throws Exception {
            assertSafeTestEnvironment(port, dbName);
            String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE " + dbName);
                this.createdAndOwned = true;
            }
        }

        void drop() {
            if (!createdAndOwned || dbName == null) {
                return;
            }
            assertSafeTestEnvironment(port, dbName);
            String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid()");
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
                this.createdAndOwned = false;
            } catch (Exception e) {
                System.err.println("Warning: Failed to drop disposable test database " + dbName + ": " + e.getMessage());
            }
        }

        boolean isCreatedAndOwned() {
            return createdAndOwned;
        }

        String getDbName() {
            return dbName;
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (directDatabaseSession != null) {
            directDatabaseSession.drop();
        }
        if (springDatabaseSession != null) {
            springDatabaseSession.drop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Direct Flyway API: V1 migration executes cleanly and records history with all 17 tables")
    void test1_directFlyway_initialMigration_executesAndPopulatesSchemaHistoryAnd17Tables() throws Exception {
        String port = getRequiredTestPort("test1_directFlyway_initialMigration");

        directDbName = TEST_DB_PREFIX + "direct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        directDatabaseSession = new DisposableDatabaseSession(port, directDbName);
        directDatabaseSession.provision();
        assertThat(directDatabaseSession.isCreatedAndOwned()).isTrue();

        String jdbcUrl = getJdbcUrl(directDbName);

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
        String port = getRequiredTestPort("test2_directFlyway_idempotency");

        assertThat(directDatabaseSession)
                .withFailMessage("directDatabaseSession must be initialized by test1")
                .isNotNull();
        assertThat(directDatabaseSession.isCreatedAndOwned()).isTrue();

        String jdbcUrl = getJdbcUrl(directDbName);

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
        String port = getRequiredTestPort("test3_directFlyway_validate");

        assertThat(directDatabaseSession)
                .withFailMessage("directDatabaseSession must be initialized by test1")
                .isNotNull();
        assertThat(directDatabaseSession.isCreatedAndOwned()).isTrue();

        String jdbcUrl = getJdbcUrl(directDbName);

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
        String port = getRequiredTestPort("test4_springBootAutoConfiguration");

        springDbName = TEST_DB_PREFIX + "spring_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        springDatabaseSession = new DisposableDatabaseSession(port, springDbName);
        springDatabaseSession.provision();
        assertThat(springDatabaseSession.isCreatedAndOwned()).isTrue();

        String jdbcUrl = getJdbcUrl(springDbName);

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

    @Test
    @Order(5)
    @DisplayName("Disposable database session refuses unowned drops and enforces safe naming guardrails")
    void test5_disposableDatabaseSession_guardrails() throws Exception {
        // 1. Assert protected names and invalid prefixes are rejected
        assertThatThrownBy(() -> assertSafeTestEnvironment("5432", "postgres"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment("5432", "template0"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment("5432", "template1"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment("5432", "public"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment("5432", "lordgainsborough"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment("5432", "restaurant"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment("5432", "invalid_prefix_db"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertSafeTestEnvironment("", TEST_DB_PREFIX + "valid"))
                .isInstanceOf(AssertionError.class);

        // 2. An unowned session must refuse to drop the database
        DisposableDatabaseSession unownedSession = new DisposableDatabaseSession("5432", TEST_DB_PREFIX + "unowned");
        assertThat(unownedSession.isCreatedAndOwned()).isFalse();
        unownedSession.drop(); // Safe NO-OP
        assertThat(unownedSession.isCreatedAndOwned()).isFalse();

        // 3. Live provision/ownership/drop lifecycle if test.postgres.port is provided
        String port = getTestPort();
        boolean mandatory = Boolean.getBoolean("test.postgres.mandatory")
                || "true".equalsIgnoreCase(System.getenv("CI_POSTGRES_MANDATORY"))
                || "true".equalsIgnoreCase(System.getenv("CI"));
        if (mandatory) {
            assertThat(port)
                    .withFailMessage("test.postgres.port system property is required for test5 live lifecycle verification in mandatory CI mode")
                    .isNotBlank();
        }
        if (port != null && !port.isBlank()) {
            String ownedName = TEST_DB_PREFIX + "guard_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            DisposableDatabaseSession ownedSession = new DisposableDatabaseSession(port, ownedName);
            ownedSession.provision();
            assertThat(ownedSession.isCreatedAndOwned()).isTrue();

            String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?sslmode=disable";
            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + ownedName + "'")) {
                assertThat(rs.next()).isTrue();
            }

            ownedSession.drop();
            assertThat(ownedSession.isCreatedAndOwned()).isFalse();

            try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + ownedName + "'")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }
}
