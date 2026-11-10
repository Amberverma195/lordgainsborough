package com.lordgainsborough.restaurant.config;

import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.Driver;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Validate the effective driver settings before Flyway/Hibernate can open a connection. */
@Component
public class PostgresTlsPolicy implements BeanPostProcessor {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource) {
            if (!(bean instanceof HikariDataSource pool))
                throw invalid("Use the configured Hikari PostgreSQL datasource so TLS settings can be verified.");
            validate(pool);
        }
        return bean;
    }

    static void validate(HikariDataSource pool) {
        if (pool.getDataSource() != null || pool.getDataSourceClassName() != null)
            throw invalid("Configure PostgreSQL through spring.datasource.url; alternate datasource factories are not supported.");
        String url = pool.getJdbcUrl();
        Properties effective = url == null ? null : Driver.parseURL(url, pool.getDataSourceProperties());
        if (effective == null) throw invalid("A valid PostgreSQL JDBC URL is required.");
        String hosts = effective.getProperty("PGHOST", "localhost");
        boolean onlyLoopback = Arrays.stream(hosts.split(",", -1))
                .allMatch(host -> LOOPBACK.contains(host.toLowerCase(Locale.ROOT)));
        if (onlyLoopback) return; // Explicit sslmode=disable is permitted only for local development/test clusters.

        String mode = effective.getProperty("sslmode", "");
        boolean verified = "verify-full".equalsIgnoreCase(mode)
                || (mode.isEmpty() && "true".equalsIgnoreCase(effective.getProperty("ssl")));
        if (!verified) throw invalid("Remote PostgreSQL requires sslmode=verify-full; plaintext and weaker TLS modes are refused.");
        String factory = effective.getProperty("sslfactory", "org.postgresql.ssl.LibPQFactory");
        if (!Set.of("org.postgresql.ssl.LibPQFactory", "org.postgresql.ssl.jdbc4.LibPQFactory").contains(factory))
            throw invalid("Remote PostgreSQL must use the validating LibPQFactory SSL implementation.");
        String verifier = effective.getProperty("sslhostnameverifier", "org.postgresql.ssl.PGjdbcHostnameVerifier");
        if (!"org.postgresql.ssl.PGjdbcHostnameVerifier".equals(verifier))
            throw invalid("Custom PostgreSQL hostname verifiers are refused.");
        String ca = effective.getProperty("sslrootcert", "");
        try {
            if (ca.isBlank() || !Files.isRegularFile(Path.of(ca)) || !Files.isReadable(Path.of(ca)) || Files.size(Path.of(ca)) == 0)
                throw invalid("Set DB_SSL_ROOT_CERT to a readable provider CA certificate file for remote PostgreSQL.");
        } catch (java.io.IOException | java.nio.file.InvalidPathException failure) {
            throw invalid("The configured PostgreSQL CA certificate file cannot be read.");
        }
    }

    private static BeanInitializationException invalid(String reason) {
        // Never include connection URLs, credentials, or their query parameters in errors.
        return new BeanInitializationException(reason);
    }
}
