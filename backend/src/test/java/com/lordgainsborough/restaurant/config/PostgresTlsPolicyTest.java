package com.lordgainsborough.restaurant.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PostgresTlsPolicyTest {
    @TempDir Path directory;
    Path ca;
    @BeforeEach void fixture() throws Exception {
        // Policy checks file availability; the actual certificate chain is verified by pgJDBC at connection time.
        ca=Files.writeString(directory.resolve("provider CA.pem"),"certificate fixture");
    }
    HikariDataSource pool(String url) {
        var pool=new HikariDataSource();pool.setJdbcUrl(url);
        pool.addDataSourceProperty("sslrootcert",ca.toString());return pool;
    }

    @Test void defaultsRequireVerifiedTlsAndExposeCaFileProperty() throws Exception {
        var env=new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("DB_HOST","database.example.test","DB_NAME","restaurant","DB_SSL_ROOT_CERT",ca.toString())));
        env.getPropertySources().addLast(new ResourcePropertySource(new ClassPathResource("application.properties")));
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://database.example.test:5432/restaurant?sslmode=verify-full");
        assertThat(env.getProperty("spring.datasource.hikari.data-source-properties.sslrootcert")).isEqualTo(ca.toString());
        env.getPropertySources().addFirst(new ResourcePropertySource(new ClassPathResource("application-postgres-migration.properties")));
        assertThat(env.getProperty("spring.datasource.url")).endsWith("sslmode=verify-full");
    }

    @ParameterizedTest @ValueSource(strings={"disable","allow","prefer","require","verify-ca"})
    void refusesWeakModesForRemoteHosts(String mode) {
        try(var pool=pool("jdbc:postgresql://database.example.test/restaurant?sslmode="+mode)) {
            assertThatThrownBy(()->PostgresTlsPolicy.validate(pool)).isInstanceOf(BeanInitializationException.class).hasMessageContaining("verify-full");
        }
    }

    @Test void remoteVerifiedTlsRequiresReadableCa() {
        try(var pool=pool("jdbc:postgresql://database.example.test/restaurant?sslmode=verify-full")) {
            assertThatCode(()->PostgresTlsPolicy.validate(pool)).doesNotThrowAnyException();
            pool.addDataSourceProperty("sslrootcert","");
            assertThatThrownBy(()->PostgresTlsPolicy.validate(pool)).hasMessageContaining("DB_SSL_ROOT_CERT");
            pool.addDataSourceProperty("sslrootcert",directory.resolve("missing.pem").toString());
            assertThatThrownBy(()->PostgresTlsPolicy.validate(pool)).hasMessageContaining("DB_SSL_ROOT_CERT");
        }
    }

    @ParameterizedTest @ValueSource(strings={"127.0.0.1","localhost","[::1]"})
    void permitsExplicitPlaintextOnlyForLoopback(String host) {
        try(var pool=pool("jdbc:postgresql://"+host+"/restaurant?sslmode=disable")) {
            pool.addDataSourceProperty("sslrootcert","");
            assertThatCode(()->PostgresTlsPolicy.validate(pool)).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest @ValueSource(strings={"localhost.attacker.example","10.0.0.5","192.168.1.5","127.0.0.1:5432,database.example.test:5432"})
    void privateNetworksAndMixedHostListsDoNotGetLoopbackException(String host) {
        try(var pool=pool("jdbc:postgresql://"+host+"/restaurant?sslmode=disable")) {
            assertThatThrownBy(()->PostgresTlsPolicy.validate(pool)).hasMessageContaining("verify-full");
        }
    }

    @Test void validatesDriverPrecedenceForUrlOverridesAndCaPathsWithSpaces() {
        try(var pool=pool("jdbc:postgresql://database.example.test/restaurant?sslmode=disable")) {
            pool.addDataSourceProperty("sslmode","verify-full");
            assertThatThrownBy(()->PostgresTlsPolicy.validate(pool)).hasMessageContaining("verify-full");
        }
        String encodedCa=URLEncoder.encode(ca.toString(),StandardCharsets.UTF_8);
        try(var pool=pool("jdbc:postgresql://database.example.test/restaurant?sslmode=verify-full&sslrootcert="+encodedCa)) {
            pool.addDataSourceProperty("sslmode","disable");pool.addDataSourceProperty("sslrootcert","");
            assertThatCode(()->PostgresTlsPolicy.validate(pool)).doesNotThrowAnyException();
        }
    }

    @Test void refusesNonValidatingFactoriesAndCustomHostnameVerifiers() {
        try(var pool=pool("jdbc:postgresql://database.example.test/restaurant?sslmode=verify-full")) {
            pool.addDataSourceProperty("sslfactory","org.postgresql.ssl.NonValidatingFactory");
            assertThatThrownBy(()->PostgresTlsPolicy.validate(pool)).hasMessageContaining("LibPQFactory");
            pool.getDataSourceProperties().remove("sslfactory");
            pool.addDataSourceProperty("sslhostnameverifier","example.AllowAllVerifier");
            assertThatThrownBy(()->PostgresTlsPolicy.validate(pool)).hasMessageContaining("hostname verifiers");
        }
    }

    @Test void startupPostProcessorRejectsMisconfigurationWithoutDisclosingCredentials() {
        try(var pool=pool("jdbc:postgresql://database.example.test/restaurant?user=private-user&password=private-password&sslmode=prefer")) {
            assertThatThrownBy(()->new PostgresTlsPolicy().postProcessBeforeInitialization(pool,"dataSource"))
                    .isInstanceOf(BeanInitializationException.class).hasMessageNotContaining("private-user")
                    .hasMessageNotContaining("private-password").hasMessageNotContaining("jdbc:postgresql");
        }
    }
}
