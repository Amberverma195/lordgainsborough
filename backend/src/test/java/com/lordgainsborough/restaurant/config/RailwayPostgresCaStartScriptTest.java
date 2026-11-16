package com.lordgainsborough.restaurant.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RailwayPostgresCaStartScriptTest {
    private static final String SCRIPT = "scripts/railway-start.sh";
    private static Path bash;
    private static String certificatePem;
    private static String certificateBase64;

    @BeforeAll
    static void createCertificate() throws Exception {
        assertThat(Files.exists(Path.of(SCRIPT))).isTrue();
        bash = bashExecutable();
        Result generated = run(Map.of(), "openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-keyout", "/tmp/lgb-test-ca.key", "-out", "/tmp/lgb-test-ca.pem",
                "-days", "1", "-nodes", "-subj", "//CN=LordGainsboroughTestCA");
        assertThat(generated.exitCode).as(generated.stderr).isZero();
        Result pem = run(Map.of(), "cat", "/tmp/lgb-test-ca.pem");
        certificatePem = pem.stdout.replace("\r\n", "\n");
        certificateBase64 = Base64.getEncoder().encodeToString(certificatePem.getBytes(StandardCharsets.US_ASCII));
        run(Map.of(), "rm", "-f", "/tmp/postgres-ca.pem", "/tmp/lgb-test-ca.pem", "/tmp/lgb-test-ca.key");
    }

    @AfterEach
    void removeCertificateFile() throws Exception {
        run(Map.of(), "rm", "-f", "/tmp/postgres-ca.pem");
    }

    @Test
    void writesRestrictedCaAndExecutesCurrentJavaArguments() throws Exception {
        Result result = run(Map.of("DB_SSL_ROOT_CERT_BASE64", wrapped(certificateBase64)),
                SCRIPT, "bash", probeScript(), "unchanged-argument");

        assertThat(result.exitCode).isZero();
        assertThat(result.stdout.lines().toList()).containsExactly("verify-full", "/tmp/postgres-ca.pem", "unchanged-argument");
        assertThat(Files.readString(Path.of(SCRIPT))).contains("chmod 0600");
        assertThat(result.stderr).doesNotContain("BEGIN CERTIFICATE").doesNotContain(certificateBase64);
        Result displayed = run(Map.of(), "openssl", "x509", "-in", "/tmp/postgres-ca.pem", "-noout", "-subject");
        assertThat(displayed.exitCode).isZero();
        assertThat(displayed.stdout).contains("LordGainsboroughTestCA");
    }

    @Test
    void preservesExplicitVerifyFullMode() throws Exception {
        Result result = run(Map.of(
                        "DB_SSL_MODE", "VERIFY-FULL",
                        "DB_SSL_ROOT_CERT_BASE64", certificateBase64,
                        "SPRING_DATASOURCE_URL", "jdbc:postgresql://db.example.test/postgres?sslmode=verify-full"),
                SCRIPT, "bash", probeScript());

        assertThat(result.exitCode).isZero();
        assertThat(result.stdout.lines().findFirst().orElse("")).isEqualTo("verify-full");
    }

    @Test
    void failsClosedWithoutStartingJavaForInvalidCertificateData() throws Exception {
        assertRejected(Map.of(), "DB_SSL_ROOT_CERT_BASE64 is required.");
        assertRejected(Map.of("DB_SSL_ROOT_CERT_BASE64", "   \n"), "DB_SSL_ROOT_CERT_BASE64 is required.");
        assertRejected(Map.of("DB_SSL_ROOT_CERT_BASE64", "not-valid-SECRETVALUE!!!!"), "not valid Base64");
        assertRejected(Map.of("DB_SSL_ROOT_CERT_BASE64", Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.US_ASCII))),
                "not a PEM certificate");
        String privateKey = """
                -----BEGIN CERTIFICATE-----
                QUJDRA==
                -----END CERTIFICATE-----
                -----BEGIN PRIVATE KEY-----
                secret-key
                -----END PRIVATE KEY-----
                """;
        assertRejected(Map.of("DB_SSL_ROOT_CERT_BASE64", Base64.getEncoder().encodeToString(privateKey.getBytes(StandardCharsets.US_ASCII))),
                "must not include a private key");
        assertRejected(Map.of("DB_SSL_MODE", "prefer", "DB_SSL_ROOT_CERT_BASE64", certificateBase64),
                "DB_SSL_MODE must be verify-full.");
        assertRejected(Map.of(
                        "DB_SSL_ROOT_CERT_BASE64", certificateBase64,
                        "SPRING_DATASOURCE_URL", "jdbc:postgresql://db.example.test/postgres?password=secret-password&sslmode=require"),
                "SPRING_DATASOURCE_URL must use sslmode=verify-full.");
        Result missingArguments = run(Map.of("DB_SSL_ROOT_CERT_BASE64", certificateBase64), SCRIPT);
        assertThat(missingArguments.exitCode).isNotZero();
        assertThat(missingArguments.stderr).contains("Java startup arguments are required.");
    }

    @Test
    void rejectsOversizedDecodedCertificate() throws Exception {
        byte[] oversized = new byte[70_000];
        Result result = run(Map.of("DB_SSL_ROOT_CERT_BASE64", Base64.getEncoder().encodeToString(oversized)),
                SCRIPT, "bash", "-c", "printf RAN");
        assertThat(result.exitCode).isNotZero();
        assertThat(result.stdout).doesNotContain("RAN");
        assertThat(result.stderr).contains("invalid size");
    }

    private static void assertRejected(Map<String, String> environment, String message) throws Exception {
        Result result = run(environment, SCRIPT, "bash", "-c", "printf RAN");
        assertThat(result.exitCode).isNotZero();
        assertThat(result.stdout).doesNotContain("RAN");
        assertThat(result.stderr).contains(message);
        assertThat(result.stderr).doesNotContain("BEGIN CERTIFICATE")
                .doesNotContain("secret-password")
                .doesNotContain("SECRETVALUE")
                .doesNotContain("secret-key");
        Result absent = run(Map.of(), "bash", "-c", "if [ -f /tmp/postgres-ca.pem ]; then printf PRESENT; fi");
        assertThat(absent.stdout).doesNotContain("PRESENT");
    }

    private static String probeScript() throws Exception {
        Path probe = Path.of("target", "ca-start-probe.sh");
        Files.createDirectories(probe.getParent());
        Files.writeString(probe, "#!/bin/sh\n" +
                "printf '%s\\n' \"$DB_SSL_MODE\"\n" +
                "printf '%s\\n' \"$DB_SSL_ROOT_CERT\"\n" +
                "printf '%s\\n' \"$1\"\n");
        return probe.toString().replace('\\', '/');
    }

    private static String wrapped(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index += 64) {
            builder.append(value, index, Math.min(index + 64, value.length())).append('\n');
        }
        return builder.toString();
    }

    private static Result run(Map<String, String> environment, String... command) throws Exception {
        List<String> invocation = new ArrayList<>();
        invocation.add(bash.toString());
        invocation.add("--noprofile");
        invocation.add("--norc");
        invocation.add("-c");
        invocation.add("exec \"$@\"");
        invocation.add("bash");
        invocation.addAll(List.of(command));
        ProcessBuilder builder = new ProcessBuilder(invocation);
        Map<String, String> inherited = new HashMap<>(builder.environment());
        builder.environment().clear();
        for (String key : List.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATHEXT", "HOME", "USERPROFILE")) {
            if (inherited.get(key) != null) {
                builder.environment().put(key, inherited.get(key));
            }
        }
        builder.environment().putAll(environment);
        if (!environment.containsKey("DB_SSL_ROOT_CERT_BASE64")) {
            builder.environment().remove("DB_SSL_ROOT_CERT_BASE64");
        }
        if (!environment.containsKey("SPRING_DATASOURCE_URL")) {
            builder.environment().remove("SPRING_DATASOURCE_URL");
        }
        if (!environment.containsKey("DB_SSL_MODE")) {
            builder.environment().remove("DB_SSL_MODE");
        }
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Thread outThread = Thread.startVirtualThread(() -> transfer(process.getInputStream(), output));
        Thread errorThread = Thread.startVirtualThread(() -> transfer(process.getErrorStream(), error));
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        outThread.join(5000);
        errorThread.join(5000);
        return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private static void transfer(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input) {
            input.transferTo(output);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Path bashExecutable() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("D:/Files/GIT/Git/bin/bash.exe"));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry, "bash.exe"));
                }
            }
        }
        return candidates.stream()
                .filter(Files::isRegularFile)
                .filter(candidate -> !candidate.toString().toLowerCase().contains("system32"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Git Bash is required to test the Railway startup script."));
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}